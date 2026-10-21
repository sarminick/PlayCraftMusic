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

        // Descarga y conversión asíncrona compatible con WebP, AVIF, JPG, PNG usando ffmpeg o HTTP
        CompletableFuture.runAsync(() -> {
            BufferedImage bImg = null;

            // 1. Intentamos leer la imagen usando ffmpeg pipe (lee WebP, AVIF, JPG, PNG de YouTube sin fallar)
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
                try (InputStream in = proc.getInputStream()) {
                    bImg = ImageIO.read(in);
                }
                boolean done = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (!done) {
                    proc.destroyForcibly();
                }
            } catch (Exception e) {
                LOGGER.warn("[PlayCraft] ffmpeg falló al decodificar imagen, probando HTTP fallback: {}", e.getMessage());
            }

            // 2. Si ffmpeg no estaba disponible o falló, fallback con HttpClient + ImageIO
            if (bImg == null) {
                try {
                    HttpResponse<byte[]> response = HTTP_CLIENT.send(
                            HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(6)).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray()
                    );
                    if (response.statusCode() == 200) {
                        bImg = ImageIO.read(new ByteArrayInputStream(response.body()));
                    }
                } catch (Exception ignored) {}
            }

            if (bImg != null) {
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

    public static void clearCache() {
        TEXTURE_CACHE.clear();
        DEFAULT_VINYL_ID = null;
    }
}
