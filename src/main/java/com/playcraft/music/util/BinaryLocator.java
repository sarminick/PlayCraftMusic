package com.playcraft.music.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static String cachedYtdlpVersion = null;
    private static String cachedFfmpegVersion = null;

    public static synchronized void invalidateCache() {
        cachedYtdlp = null;
        cachedFfmpeg = null;
        ytdlpAvailable = null;
        ffmpegAvailable = null;
        cachedYtdlpVersion = null;
        cachedFfmpegVersion = null;
    }

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

    public static synchronized String getYtdlpVersion() {
        if (cachedYtdlpVersion == null) {
            cachedYtdlpVersion = queryFirstOutputLine(getYtdlp(), "--version");
        }
        return cachedYtdlpVersion;
    }

    public static synchronized String getFfmpegVersion() {
        if (cachedFfmpegVersion == null) {
            String line = queryFirstOutputLine(getFfmpeg(), "-version");
            if (!line.isEmpty()) {
                String[] tokens = line.split("\\s+");
                if (tokens.length >= 3 && "version".equalsIgnoreCase(tokens[1])) {
                    cachedFfmpegVersion = tokens[2];
                } else {
                    cachedFfmpegVersion = tokens[0];
                }
            } else {
                cachedFfmpegVersion = "";
            }
        }
        return cachedFfmpegVersion;
    }

    private static String queryFirstOutputLine(String command, String arg) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, arg);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String firstLine = "";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if (line != null) {
                    firstLine = line.trim();
                }
            }
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return "";
            }
            return p.exitValue() == 0 ? firstLine : "";
        } catch (Exception e) {
            return "";
        }
    }

    public record UpdateCheckResult(String statusLine, String hintLine) {
    }

    private static final HttpClient UPDATE_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public static CompletableFuture<UpdateCheckResult> checkYtdlpUpdateAsync() {
        return CompletableFuture.supplyAsync(() -> {
            invalidateCache();
            String localVer = getYtdlpVersion();
            if (localVer.isEmpty()) {
                return new UpdateCheckResult(
                        "§cNo se detectó yt-dlp en el sistema.",
                        getOsUpdateSuggestion(true)
                );
            }

            String latestVer = fetchLatestYtdlpTag();
            if (latestVer == null || latestVer.isEmpty()) {
                return new UpdateCheckResult(
                        "§cNo fue posible verificar actualizaciones (revisa tu conexión).",
                        ""
                );
            }

            if (compareYtdlpVersions(localVer, latestVer) >= 0) {
                return new UpdateCheckResult(
                        "§a✔ yt-dlp está actualizado (" + localVer + ").",
                        ""
                );
            }

            return new UpdateCheckResult(
                    "§eNueva versión disponible: §f" + latestVer + " §7(actual: " + localVer + ")",
                    getOsUpdateSuggestion(false)
            );
        });
    }

    private static String fetchLatestYtdlpTag() {
        // 1. Petición ligera a /releases/latest leyendo cabecera Location (.../releases/tag/YYYY.MM.DD)
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://github.com/yt-dlp/yt-dlp/releases/latest"))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "PlayCraftMusic-Mod")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> resp = UPDATE_HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
            String location = resp.headers().firstValue("location").orElse("");
            int tagIdx = location.lastIndexOf("/tag/");
            if (tagIdx != -1) {
                String tag = location.substring(tagIdx + 5).trim();
                int qIdx = tag.indexOf('?');
                if (qIdx != -1) tag = tag.substring(0, qIdx);
                if (!tag.isEmpty()) return tag;
            }
        } catch (Exception ignored) {
        }

        // 2. Fallback por GitHub Releases API JSON
        try {
            HttpClient followClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(6))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "PlayCraftMusic-Mod")
                    .GET()
                    .build();
            HttpResponse<String> resp = followClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && resp.body() != null) {
                Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(resp.body());
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int compareYtdlpVersions(String localVer, String remoteVer) {
        String[] localParts = localVer.trim().split("\\.");
        String[] remoteParts = remoteVer.trim().split("\\.");
        int len = Math.max(localParts.length, remoteParts.length);
        for (int i = 0; i < len; i++) {
            String ls = i < localParts.length ? localParts[i].replaceAll("[^0-9]", "") : "0";
            String rs = i < remoteParts.length ? remoteParts[i].replaceAll("[^0-9]", "") : "0";
            int lv = ls.isEmpty() ? 0 : Integer.parseInt(ls);
            int rv = rs.isEmpty() ? 0 : Integer.parseInt(rs);
            if (lv != rv) {
                return Integer.compare(lv, rv);
            }
        }
        return 0;
    }

    private static String getOsUpdateSuggestion(boolean isInstall) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return isInstall
                    ? "§7Windows: §fwinget install yt-dlp.yt-dlp"
                    : "§7Windows: §fwinget upgrade yt-dlp.yt-dlp §7o §fyt-dlp -U";
        }
        if (os.contains("mac")) {
            return isInstall
                    ? "§7macOS: §fbrew install yt-dlp"
                    : "§7macOS: §fbrew upgrade yt-dlp";
        }
        if (new File("/usr/bin/pacman").exists()) {
            return isInstall
                    ? "§7Arch Linux: §fsudo pacman -S yt-dlp"
                    : "§7Arch Linux: §fsudo pacman -Syu yt-dlp";
        }
        if (new File("/usr/bin/apt").exists()) {
            return isInstall
                    ? "§7Linux (apt): §fsudo apt install yt-dlp"
                    : "§7Linux (apt): §fsudo apt upgrade yt-dlp §7o §fsudo yt-dlp -U";
        }
        if (new File("/usr/bin/dnf").exists()) {
            return isInstall
                    ? "§7Linux (dnf): §fsudo dnf install yt-dlp"
                    : "§7Linux (dnf): §fsudo dnf upgrade yt-dlp §7o §fsudo yt-dlp -U";
        }
        return "§7Linux: §fsudo yt-dlp -U";
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

        // 0. Revisar directorio actual del juego (.minecraft / instancia) y carpeta playcraft/bin / config/playcraftmusic/bin
        try {
            Path currentDir = Paths.get("").toAbsolutePath();
            searchRoots.add(currentDir);
            searchRoots.add(currentDir.resolve("playcraft").resolve("bin"));
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
