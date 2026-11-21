package com.playcraft.music.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Descarga miniaturas (JPEG o WEBP) y las convierte a PNG en memoria para NativeImage de Minecraft.
 */
public class ThumbnailTextureManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("PlayCraft-Thumbnails");
    private static final Map<String, Identifier> TEXTURE_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PENDING_DOWNLOADS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Identifier> BANNER_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PENDING_BANNERS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Identifier> FULL_THUMB_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PENDING_FULL_THUMBS = ConcurrentHashMap.newKeySet();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static Identifier DEFAULT_VINYL_ID = null;

    private static Identifier getDefaultVinylTexture() {
        if (DEFAULT_VINYL_ID != null) {
            return DEFAULT_VINYL_ID;
        }
        try {
            int discDiameter = 256;
            BufferedImage vinylImg = new BufferedImage(discDiameter, discDiameter, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g2 = vinylImg.createGraphics();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

            // Disco negro redondo
            g2.setColor(new java.awt.Color(0x11, 0x11, 0x16, 0xFF));
            g2.fillOval(0, 0, discDiameter, discDiameter);

            g2.setColor(new java.awt.Color(0x2E, 0x2E, 0x3C, 0xFF));
            g2.setStroke(new java.awt.BasicStroke(3.0f));
            g2.drawOval(1, 1, discDiameter - 3, discDiameter - 3);

            // Centro con nota musical
            int labelDiameter = 232;
            int labelOffset = (discDiameter - labelDiameter) / 2;
            g2.setColor(new java.awt.Color(0x22, 0x22, 0x2C, 0xFF));
            g2.fillOval(labelOffset, labelOffset, labelDiameter, labelDiameter);

            g2.setColor(new java.awt.Color((MusicHudOverlay.CURRENT_THEME.borderColor >> 16) & 0xFF, (MusicHudOverlay.CURRENT_THEME.borderColor >> 8) & 0xFF, MusicHudOverlay.CURRENT_THEME.borderColor & 0xFF, 0xEE));
            g2.setStroke(new java.awt.BasicStroke(2.0f));
            g2.drawOval(labelOffset, labelOffset, labelDiameter, labelDiameter);

            // Spindle hole
            int holeDiameter = 18;
            int holeOffset = (discDiameter - holeDiameter) / 2;
            g2.setColor(new java.awt.Color(0x11, 0x11, 0x16, 0xFF));
            g2.fillOval(holeOffset, holeOffset, holeDiameter, holeDiameter);
            g2.dispose();

            ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
            ImageIO.write(vinylImg, "png", pngOut);
            NativeImage image = NativeImage.read(new ByteArrayInputStream(pngOut.toByteArray()));
            DynamicTexture texture = new DynamicTexture(() -> "playcraft_default_vinyl", image);
            DEFAULT_VINYL_ID = Identifier.fromNamespaceAndPath("playcraft", "default_vinyl_disc");
            Minecraft.getInstance().getTextureManager().register(DEFAULT_VINYL_ID, texture);
        } catch (Exception ignored) {}
        return DEFAULT_VINYL_ID;
    }

    private static BufferedImage downloadBestThumbnail(String url) {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (url.contains("/hqdefault.jpg")) {
            candidates.add(url.replace("/hqdefault.jpg", "/maxresdefault.jpg"));
            candidates.add(url.replace("/hqdefault.jpg", "/mqdefault.jpg"));
        }
        candidates.add(url);

        for (String candidate : candidates) {
            try {
                HttpResponse<byte[]> response = HTTP_CLIENT.send(
                        HttpRequest.newBuilder().uri(URI.create(candidate)).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray()
                );
                if (response.statusCode() == 200) {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(response.body()));
                    if (img != null && img.getWidth() > 120 && img.getHeight() > 90) {
                        return img;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Fallback con ffmpeg (soporta WebP/AVIF y redirecciones especiales)
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    com.playcraft.music.util.BinaryLocator.getFfmpeg(),
                    "-i", url,
                    "-vframes", "1",
                    "-f", "image2pipe",
                    "-vcodec", "png",
                    "-v", "quiet",
                    "pipe:1"
            );
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            BufferedImage img;
            try (InputStream in = proc.getInputStream()) {
                img = ImageIO.read(in);
            }
            boolean done = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
            }
            if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                return img;
            }
        } catch (Exception e) {
            LOGGER.warn("[PlayCraft] Fallback ffmpeg de miniatura falló: {}", e.getMessage());
        }
        return null;
    }

    private static BufferedImage stripLetterboxIf4x3(BufferedImage img) {
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return img;
        double aspect = (double) img.getWidth() / (double) img.getHeight();
        if (aspect < 1.5 && img.getHeight() >= 100) {
            int cropTop = Math.min(img.getHeight() / 3, (int) Math.round(img.getHeight() * 0.125));
            int cropH = Math.max(1, img.getHeight() - (cropTop * 2));
            return img.getSubimage(0, cropTop, img.getWidth(), cropH);
        }
        return img;
    }

    public static Identifier getThumbnail(String url) {
        if (url == null || url.trim().isEmpty()) {
            return getDefaultVinylTexture();
        }

        Identifier cached = TEXTURE_CACHE.get(url);
        if (cached != null) {
            return cached;
        }

        if (!PENDING_DOWNLOADS.add(url)) {
            return getDefaultVinylTexture();
        }

        // Descarga y conversión asíncrona compatible con HD 16:9, WebP, AVIF, JPG y PNG
        CompletableFuture.runAsync(() -> {
            BufferedImage rawImg = downloadBestThumbnail(url);
            BufferedImage bImg = stripLetterboxIf4x3(rawImg);

            if (bImg != null && bImg.getWidth() > 0 && bImg.getHeight() > 0) {
                try {
                    int discDiameter = 256;
                    BufferedImage vinylImg = new BufferedImage(discDiameter, discDiameter, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g2 = vinylImg.createGraphics();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);

                    // 1. Un solo borde exterior negro de acetato limpio (sin aros excesivos)
                    g2.setColor(new java.awt.Color(0x11, 0x11, 0x16, 0xFF));
                    g2.fillOval(0, 0, discDiameter, discDiameter);

                    g2.setColor(new java.awt.Color(0x2E, 0x2E, 0x3C, 0xFF));
                    g2.setStroke(new java.awt.BasicStroke(3.0f));
                    g2.drawOval(1, 1, discDiameter - 3, discDiameter - 3);

                    // 2. La miniatura ocupa prácticamente todo el disco (232px de 256px), dejando solo 1 aro de marco fino
                    int labelDiameter = 232;
                    int labelOffset = (discDiameter - labelDiameter) / 2;
                    java.awt.Shape oldClip = g2.getClip();
                    g2.setClip(new java.awt.geom.Ellipse2D.Float(labelOffset, labelOffset, labelDiameter, labelDiameter));

                    // Escalar y centrar la miniatura en la etiqueta
                    int imgMin = Math.min(bImg.getWidth(), bImg.getHeight());
                    int cropX = (bImg.getWidth() - imgMin) / 2;
                    int cropY = (bImg.getHeight() - imgMin) / 2;
                    BufferedImage cropped = bImg.getSubimage(cropX, cropY, imgMin, imgMin);
                    g2.drawImage(cropped, labelOffset, labelOffset, labelDiameter, labelDiameter, null);
                    g2.setClip(oldClip);

                    // 3. Fino anillo decorativo delimitando la carátula según el tema de color seleccionado
                    int themeBorder = MusicHudOverlay.CURRENT_THEME.borderColor;
                    g2.setColor(new java.awt.Color((themeBorder >> 16) & 0xFF, (themeBorder >> 8) & 0xFF, themeBorder & 0xFF, 0xEE));
                    g2.setStroke(new java.awt.BasicStroke(2.0f));
                    g2.drawOval(labelOffset, labelOffset, labelDiameter, labelDiameter);

                    // 4. Pequeño orificio central del disco (Spindle Hole discreto)
                    int holeDiameter = 18;
                    int holeOffset = (discDiameter - holeDiameter) / 2;
                    g2.setColor(new java.awt.Color(0x11, 0x11, 0x16, 0xFF));
                    g2.fillOval(holeOffset, holeOffset, holeDiameter, holeDiameter);

                    g2.setColor(new java.awt.Color(0xFA, 0xFA, 0xFA, 0xEE));
                    g2.setStroke(new java.awt.BasicStroke(1.5f));
                    g2.drawOval(holeOffset, holeOffset, holeDiameter, holeDiameter);

                    int centerPin = 6;
                    int pinOffset = (discDiameter - centerPin) / 2;
                    g2.setColor(new java.awt.Color(0x22, 0x22, 0x28, 0xFF));
                    g2.fillOval(pinOffset, pinOffset, centerPin, centerPin);

                    g2.dispose();

                    ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
                    ImageIO.write(vinylImg, "png", pngOut);
                    byte[] pngBytes = pngOut.toByteArray();

                    try (InputStream in = new ByteArrayInputStream(pngBytes)) {
                        NativeImage image = NativeImage.read(in);
                        Minecraft.getInstance().execute(() -> {
                            try {
                                String id = "thumbnail_" + Math.abs(url.hashCode());
                                DynamicTexture texture = new DynamicTexture(() -> id, image);
                                Identifier identifier = Identifier.fromNamespaceAndPath("playcraft", id);
                                Minecraft.getInstance().getTextureManager().register(identifier, texture);
                                TEXTURE_CACHE.put(url, identifier);
                                PENDING_DOWNLOADS.remove(url);
                                LOGGER.info("[PlayCraft] Disco de vinilo generado y registrado: {}", url);
                            } catch (Exception ex) {
                                PENDING_DOWNLOADS.remove(url);
                            }
                        });
                    }
                } catch (Exception e) {
                    PENDING_DOWNLOADS.remove(url);
                }
            } else {
                PENDING_DOWNLOADS.remove(url);
            }
        });

        return getDefaultVinylTexture();
    }

    public static Identifier getPlaylistBanner(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        Identifier cached = BANNER_CACHE.get(url);
        if (cached != null) {
            return cached;
        }
        if (!PENDING_BANNERS.add(url)) {
            return null;
        }

        CompletableFuture.runAsync(() -> {
            BufferedImage clean = stripLetterboxIf4x3(downloadBestThumbnail(url));
            if (clean != null && clean.getWidth() > 0 && clean.getHeight() > 0) {
                try {
                    int bannerW = 1040;
                    int bannerH = 124;
                    int cw = clean.getWidth();
                    int ch = clean.getHeight();

                    BufferedImage bannerImg = new BufferedImage(bannerW, bannerH, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g2 = bannerImg.createGraphics();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);

                    // Banda central con relación de aspecto exacta del banner (sin deformar ni estirar)
                    int bandH = Math.min(ch, Math.max(1, (int) Math.round(cw * ((double) bannerH / (double) bannerW))));
                    int bandY = Math.max(0, (ch - bandH) / 2);
                    g2.drawImage(clean, 0, 0, bannerW, bannerH, 0, bandY, cw, bandY + bandH, null);

                    // Degradado oscuro suave izquierda->derecha + viñeta inferior para máxima legibilidad del texto
                    java.awt.GradientPaint horizontalGrad = new java.awt.GradientPaint(
                            0, 0, new java.awt.Color(14, 14, 20, 230),
                            bannerW, 0, new java.awt.Color(14, 14, 20, 120)
                    );
                    g2.setPaint(horizontalGrad);
                    g2.fillRect(0, 0, bannerW, bannerH);

                    java.awt.GradientPaint bottomVignette = new java.awt.GradientPaint(
                            0, bannerH / 2.0f, new java.awt.Color(10, 10, 14, 0),
                            0, bannerH, new java.awt.Color(10, 10, 14, 145)
                    );
                    g2.setPaint(bottomVignette);
                    g2.fillRect(0, 0, bannerW, bannerH);
                    g2.dispose();

                    ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
                    ImageIO.write(bannerImg, "png", pngOut);
                    try (InputStream in = new ByteArrayInputStream(pngOut.toByteArray())) {
                        NativeImage image = NativeImage.read(in);
                        Minecraft.getInstance().execute(() -> {
                            try {
                                String id = "banner_" + Math.abs(url.hashCode());
                                DynamicTexture texture = new DynamicTexture(() -> id, image);
                                Identifier identifier = Identifier.fromNamespaceAndPath("playcraft", id);
                                Minecraft.getInstance().getTextureManager().register(identifier, texture);
                                BANNER_CACHE.put(url, identifier);
                            } catch (Exception ignored) {
                            } finally {
                                PENDING_BANNERS.remove(url);
                            }
                        });
                    }
                } catch (Exception e) {
                    PENDING_BANNERS.remove(url);
                }
            } else {
                PENDING_BANNERS.remove(url);
            }
        });

        return null;
    }

    public static Identifier getFullThumbnail(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        Identifier cached = FULL_THUMB_CACHE.get(url);
        if (cached != null) {
            return cached;
        }
        if (!PENDING_FULL_THUMBS.add(url)) {
            return null;
        }

        CompletableFuture.runAsync(() -> {
            BufferedImage clean = stripLetterboxIf4x3(downloadBestThumbnail(url));
            if (clean != null && clean.getWidth() > 0 && clean.getHeight() > 0) {
                try {
                    int outW = 160;
                    int outH = 90;
                    int cw = clean.getWidth();
                    int ch = clean.getHeight();
                    double srcAspect = (double) cw / (double) ch;
                    double targetAspect = 16.0 / 9.0;

                    int srcX = 0;
                    int srcY = 0;
                    int srcW = cw;
                    int srcH = ch;
                    if (Math.abs(srcAspect - targetAspect) > 0.02) {
                        if (srcAspect > targetAspect) {
                            srcW = Math.max(1, (int) Math.round(ch * targetAspect));
                            srcX = Math.max(0, (cw - srcW) / 2);
                        } else {
                            srcH = Math.max(1, (int) Math.round(cw / targetAspect));
                            srcY = Math.max(0, (ch - srcH) / 2);
                        }
                    }

                    BufferedImage thumbImg = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g2 = thumbImg.createGraphics();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g2.drawImage(clean, 0, 0, outW, outH, srcX, srcY, srcX + srcW, srcY + srcH, null);
                    g2.dispose();

                    ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
                    ImageIO.write(thumbImg, "png", pngOut);
                    try (InputStream in = new ByteArrayInputStream(pngOut.toByteArray())) {
                        NativeImage image = NativeImage.read(in);
                        Minecraft.getInstance().execute(() -> {
                            try {
                                String id = "fullthumb_" + Math.abs(url.hashCode());
                                DynamicTexture texture = new DynamicTexture(() -> id, image);
                                Identifier identifier = Identifier.fromNamespaceAndPath("playcraft", id);
                                Minecraft.getInstance().getTextureManager().register(identifier, texture);
                                FULL_THUMB_CACHE.put(url, identifier);
                            } catch (Exception ignored) {
                            } finally {
                                PENDING_FULL_THUMBS.remove(url);
                            }
                        });
                    }
                } catch (Exception e) {
                    PENDING_FULL_THUMBS.remove(url);
                }
            } else {
                PENDING_FULL_THUMBS.remove(url);
            }
        });

        return null;
    }

    public static void clearCache() {
        TEXTURE_CACHE.clear();
        BANNER_CACHE.clear();
        FULL_THUMB_CACHE.clear();
        DEFAULT_VINYL_ID = null;
    }
}
