package com.playcraft.music.util;

import com.playcraft.music.model.TrackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Extrae metadatos de YouTube de forma individual o en lote de búsqueda.
 */
public class YouTubeMetadataResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger("PlayCraft-Meta");
    private static final ExecutorService META_EXECUTOR = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "PlayCraft-MetaResolver");
        t.setDaemon(true);
        return t;
    });

    public static boolean isPlaylistUrl(String url) {
        if (url == null) return false;
        return (url.startsWith("http://") || url.startsWith("https://")) && url.contains("list=");
    }

    public static CompletableFuture<List<TrackInfo>> resolvePlaylist(String playlistUrl, String requestedBy, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<TrackInfo> tracks = new ArrayList<>();
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(BinaryLocator.getYtdlp());
                cmd.add("--flat-playlist");
                cmd.add("--print");
                cmd.add("%(id)s|||%(title)s|||%(channel,uploader)s|||%(duration)s|||%(webpage_url)s");
                cmd.add("--no-check-certificates");
                cmd.add("--socket-timeout");
                cmd.add("6");
                cmd.add("--force-ipv4");
                cmd.add("--no-warnings");
                cmd.add(playlistUrl);

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (tracks.size() >= limit) {
                        continue;
                    }
                    if (line.contains("|||")) {
                        String[] parts = line.split("\\|\\|\\|", -1);
                        String id = parts.length > 0 ? parts[0].trim() : "";
                        String title = parts.length > 1 && !parts[1].isEmpty() ? parts[1].trim() : "YouTube Track";
                        String author = parts.length > 2 && !parts[2].isEmpty() && !parts[2].equals("NA") ? parts[2].trim() : requestedBy;
                        String durStr = parts.length > 3 ? parts[3].trim() : "";
                        String actualUrl = parts.length > 4 && !parts[4].isEmpty() ? parts[4].trim() : ("https://www.youtube.com/watch?v=" + id);

                        long durationSec = 180;
                        if (!durStr.isEmpty() && !durStr.equals("NA")) {
                            try {
                                durationSec = (long) Double.parseDouble(durStr);
                            } catch (NumberFormatException ignored) {}
                        }
                        String thumbnail = !id.isEmpty() ? "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg" : "";

                        tracks.add(new TrackInfo(
                                String.valueOf(System.currentTimeMillis() + tracks.size()),
                                title,
                                author,
                                actualUrl,
                                thumbnail,
                                durationSec * 1000L
                        ));
                    }
                }
                boolean done = process.waitFor(12, TimeUnit.SECONDS);
                if (!done) {
                    process.destroyForcibly();
                }
            } catch (Exception e) {
                LOGGER.error("[PlayCraftMusic] Error resolviendo playlist: ", e);
            }
            return tracks;
        }, META_EXECUTOR);
    }

    public static CompletableFuture<TrackInfo> resolve(String queryOrUrl, String requestedBy) {
        return CompletableFuture.supplyAsync(() -> {
            String target = queryOrUrl;
            boolean isDirectUrl = target.startsWith("http://") || target.startsWith("https://");
            if (!isDirectUrl) {
                target = "ytsearch5:" + queryOrUrl;
            }

            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(BinaryLocator.getYtdlp());
                if (!isDirectUrl) {
                    cmd.add("--flat-playlist");
                }
                cmd.add("--no-playlist");
                cmd.add("--print");
                cmd.add("%(id)s|||%(title)s|||%(channel,uploader)s|||%(duration)s|||%(webpage_url)s");
                cmd.add("--no-check-certificates");
                cmd.add("--socket-timeout");
                cmd.add("6");
                cmd.add("--force-ipv4");
                cmd.add("--no-warnings");
                cmd.add(target);

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                TrackInfo resolved = null;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("|||")) {
                        String[] parts = line.split("\\|\\|\\|", -1);
                        String id = parts.length > 0 ? parts[0].trim() : "";
                        String title = parts.length > 1 && !parts[1].isEmpty() ? parts[1].trim() : queryOrUrl;
                        String author = parts.length > 2 && !parts[2].isEmpty() && !parts[2].equals("NA") ? parts[2].trim() : requestedBy;
                        String durStr = parts.length > 3 ? parts[3].trim() : "";
                        String actualUrl = parts.length > 4 && !parts[4].isEmpty() ? parts[4].trim() : queryOrUrl;

                        // Ignorar entradas de canales o playlists cuando se busca por texto
                        if (!isDirectUrl && (actualUrl.contains("/channel/") || actualUrl.contains("/@") || durStr.isEmpty() || durStr.equals("NA"))) {
                            continue;
                        }

                        long durationSec = 180;
                        if (!durStr.isEmpty() && !durStr.equals("NA")) {
                            try {
                                durationSec = (long) Double.parseDouble(durStr);
                            } catch (NumberFormatException ignored) {}
                        }
                        String thumbnail = !id.isEmpty() ? "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg" : "";

                        resolved = new TrackInfo(
                                String.valueOf(System.currentTimeMillis()),
                                title,
                                author,
                                actualUrl,
                                thumbnail,
                                durationSec * 1000L
                        );
                        break;
                    }
                }
                boolean done = process.waitFor(8, TimeUnit.SECONDS);
                if (!done) {
                    process.destroyForcibly();
                }
                if (resolved != null) {
                    return resolved;
                }
            } catch (Exception e) {
                LOGGER.error("[PlayCraftMusic] Error resolviendo metadatos: ", e);
            }

            String fallbackThumb = "";
            String videoId = extractVideoId(queryOrUrl);
            if (videoId != null && !videoId.isEmpty()) {
                fallbackThumb = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
            }

            return new TrackInfo(
                    String.valueOf(System.currentTimeMillis()),
                    queryOrUrl,
                    requestedBy,
                    queryOrUrl,
                    fallbackThumb,
                    180000L
            );
        }, META_EXECUTOR);
    }

    public static String extractVideoId(String url) {
        if (url == null) return null;
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "(?:v=|/v/|youtu\\.be/|/embed/|/shorts/|/live/|watch\\?v=|&v=)([a-zA-Z0-9_-]{11})"
            );
            java.util.regex.Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static CompletableFuture<List<TrackInfo>> searchMultiple(String query, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<TrackInfo> results = new ArrayList<>();
            // Pedimos un margen extra (limit + 5) con --flat-playlist para saltar canales/playlists
            // y obtener resultados en ~1.5 segundos en vez de 18 segundos.
            int fetchCount = Math.max(limit + 5, 10);
            String target = "ytsearch" + fetchCount + ":" + query;

            try {
                ProcessBuilder pb = new ProcessBuilder(
                        BinaryLocator.getYtdlp(),
                        "--flat-playlist",
                        "--print", "%(id)s|||%(title)s|||%(channel,uploader)s|||%(duration)s|||%(webpage_url)s",
                        "--no-check-certificates",
                        "--socket-timeout", "6",
                        "--force-ipv4",
                        "--no-warnings",
                        target
                );
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (results.size() >= limit) {
                        continue;
                    }
                    if (line.contains("|||")) {
                        String[] parts = line.split("\\|\\|\\|", -1);
                        String id = parts.length > 0 ? parts[0].trim() : "";
                        String title = parts.length > 1 && !parts[1].isEmpty() ? parts[1].trim() : "YouTube Audio";
                        String author = parts.length > 2 && !parts[2].isEmpty() && !parts[2].equals("NA") ? parts[2].trim() : "YouTube";
                        String durStr = parts.length > 3 ? parts[3].trim() : "";
                        String url = parts.length > 4 && !parts[4].isEmpty() ? parts[4].trim() : "";

                        // Ignorar entradas que son canales de artistas (ej. "Don Toliver" canal), listas o transmisiones sin duración
                        if (url.contains("/channel/") || url.contains("/user/") || url.contains("/@") || url.contains("list=")
                                || durStr.isEmpty() || durStr.equals("NA") || id.length() != 11) {
                            continue;
                        }

                        long durationSec = 180;
                        try {
                            durationSec = (long) Double.parseDouble(durStr);
                        } catch (NumberFormatException ignored) {
                            continue;
                        }
                        if (durationSec <= 0) {
                            continue;
                        }

                        // Miniatura directa JPG oficial de YouTube (carga instantánea en Java ImageIO sin requerir WebP)
                        String thumbnail = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";

                        results.add(new TrackInfo(
                                String.valueOf(System.currentTimeMillis() + results.size()),
                                title,
                                author,
                                url,
                                thumbnail,
                                durationSec * 1000L
                        ));
                    }
                }
                boolean done = process.waitFor(10, TimeUnit.SECONDS);
                if (!done) {
                    process.destroyForcibly();
                }
            } catch (Exception e) {
                LOGGER.error("[PlayCraftMusic] Error en búsqueda múltiple: ", e);
            }
            return results;
        }, META_EXECUTOR);
    }
}
