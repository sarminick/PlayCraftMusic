package com.playcraft.music.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Localiza ejecutables nativos (yt-dlp, ffmpeg) en el sistema.
 * En Windows busca inteligentemente en el PATH y en rutas típicas de instalación
 * (WinGet, Chocolatey, Scoop, AppData, Program Files, etc.) para que funcione
 * sin requerir configurar variables de entorno manualmente.
 */
public class BinaryLocator {
    private static final Logger LOGGER = LoggerFactory.getLogger("PlayCraft-BinLocator");

    private static String cachedYtdlp = null;
    private static String cachedFfmpeg = null;
    private static Boolean ytdlpAvailable = null;
    private static Boolean ffmpegAvailable = null;

    public static synchronized String getYtdlp() {
        if (cachedYtdlp == null) {
            cachedYtdlp = findBinary("yt-dlp");
        }
        return cachedYtdlp;
    }

    public static synchronized String getFfmpeg() {
        if (cachedFfmpeg == null) {
            cachedFfmpeg = findBinary("ffmpeg");
        }
        return cachedFfmpeg;
    }

    public static synchronized boolean isYtdlpAvailable() {
        if (ytdlpAvailable == null) {
            ytdlpAvailable = testExecutable(getYtdlp());
        }
        return ytdlpAvailable;
    }

    public static synchronized boolean isFfmpegAvailable() {
        if (ffmpegAvailable == null) {
            ffmpegAvailable = testExecutable(getFfmpeg());
        }
        return ffmpegAvailable;
    }

    public static synchronized boolean hasRequiredBinaries() {
        return isYtdlpAvailable() && isFfmpegAvailable();
    }

    private static String findBinary(String name) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String exeName = isWindows ? name + ".exe" : name;

        // 1. Probar si el comando base responde directamente en PATH
        if (testExecutable(name) || (isWindows && testExecutable(exeName))) {
            LOGGER.info("[PlayCraft] Encontrado {} directamente en PATH del sistema.", name);
            return name;
        }

        if (!isWindows) {
            // Rutas típicas Linux / macOS
            String[] unixPaths = {"/usr/bin/" + name, "/usr/local/bin/" + name, "/opt/homebrew/bin/" + name, System.getProperty("user.home") + "/.local/bin/" + name};
            for (String p : unixPaths) {
                if (new File(p).canExecute()) {
                    LOGGER.info("[PlayCraft] Encontrado {} en ruta UNIX: {}", name, p);
                    return p;
                }
            }
            return name;
        }

        // 2. Búsqueda en rutas conocidas de Windows
        List<Path> searchRoots = new ArrayList<>();
        String localAppData = System.getenv("LOCALAPPDATA");
        String appData = System.getenv("APPDATA");
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        String userProfile = System.getenv("USERPROFILE");

        // 0. Revisar directorio actual del juego (.minecraft / instancia) y carpeta config/playcraftmusic/bin
        try {
            Path currentDir = Paths.get("").toAbsolutePath();
            searchRoots.add(currentDir);
            searchRoots.add(currentDir.resolve("config").resolve("playcraftmusic").resolve("bin"));
            searchRoots.add(currentDir.resolve("bin"));
        } catch (Exception ignored) {}

        // Rutas fijas comunes
        if (localAppData != null) {
            searchRoots.add(Paths.get(localAppData, "Microsoft", "WinGet", "Links"));
            searchRoots.add(Paths.get(localAppData, "Microsoft", "WinGet", "Packages"));
            searchRoots.add(Paths.get(localAppData, "Programs"));
        }
        if (appData != null) {
            searchRoots.add(Paths.get(appData, ".minecraft"));
            searchRoots.add(Paths.get(appData, ".sklauncher"));
        }
        if (userProfile != null) {
            searchRoots.add(Paths.get(userProfile, "scoop", "shims"));
            searchRoots.add(Paths.get(userProfile, ".local", "bin"));
            searchRoots.add(Paths.get(userProfile, "Downloads"));
        }
        searchRoots.add(Paths.get("C:\\ProgramData\\chocolatey\\bin"));
        searchRoots.add(Paths.get("C:\\ffmpeg\\bin"));
        searchRoots.add(Paths.get("C:\\yt-dlp"));
        if (programFiles != null) searchRoots.add(Paths.get(programFiles));
        if (programFilesX86 != null) searchRoots.add(Paths.get(programFilesX86));

        for (Path root : searchRoots) {
            if (!Files.exists(root)) continue;
            try {
                // Si el archivo está directo en la carpeta raíz
                Path directFile = root.resolve(exeName);
                if (Files.isExecutable(directFile)) {
                    String found = directFile.toAbsolutePath().toString();
                    LOGGER.info("[PlayCraft] Encontrado {} en {}", name, found);
                    return found;
                }

                // Búsqueda superficial en subcarpetas (hasta 4 niveles de profundidad para WinGet Packages)
                try (var stream = Files.find(root, 4, (path, attrs) -> path.getFileName().toString().equalsIgnoreCase(exeName))) {
                    var match = stream.findFirst();
                    if (match.isPresent()) {
                        String found = match.get().toAbsolutePath().toString();
                        LOGGER.info("[PlayCraft] Encontrado {} automáticamente en {}", name, found);
                        return found;
                    }
                }
            } catch (Exception ignored) {}
        }

        LOGGER.warn("[PlayCraft] No se encontró ruta específica para {}, usando nombre por defecto.", name);
        return name;
    }

    private static boolean testExecutable(String command) {
        try {
            Process p = new ProcessBuilder(command, "--version").start();
            p.getInputStream().close();
            p.getErrorStream().close();
            p.waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
