package com.playcraft.music.client.gui;

import com.playcraft.music.client.audio.ClientAudioEngine;
import com.playcraft.music.model.TrackInfo;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Overlay flotante limpio, estético y minimalista:
 * - Miniatura real de YouTube
 * - Título del video en negrita
 * - Autor de la canción (sin la palabra 'Canal')
 * - Barra de progreso estética con tiempo actual y total
 * - Avisos de actividad opcionales (desactivados por defecto) con posición y escala configurables
 */
public class MusicHudOverlay implements HudElement {
    private static long currentPositionMs = 0;
    private static long lastUpdateTime = 0;
    private static boolean isPlaying = false;
    private static String lastTrackId = "";

    // Coordenadas y personalización del widget flotante (Settings tab)
    public static int HUD_X = 12;
    public static int HUD_Y = 12;
    public static int HUD_WIDTH = 250;
    public static int HUD_HEIGHT = 56;
    public static float HUD_SCALE = 1.0f; // Escala proporcional del contenedor completo (0.7f a 1.5f)
    public static float HUD_OPACITY = 0.88f; // Transparencia / Opacidad del fondo del HUD y menú (0.20f a 1.0f)
    public static boolean HUD_ENABLED = true; // Permite ocultar el widget flotante si a un jugador le incomoda en pantalla

    // Avisos de actividad del reproductor (DESACTIVADOS por defecto, configurables en Settings)
    public static boolean NOTIFICATIONS_ENABLED = false;
    public static int NOTIFICATION_X = 12;
    public static int NOTIFICATION_Y = 76;
    public static float NOTIFICATION_SCALE = 0.9f;

    private static String activeNotification = "";
    private static long notificationTimestamp = 0L;
    private static final long NOTIFICATION_DURATION_MS = 3500L;

    public static void showNotification(String message) {
        if (!NOTIFICATIONS_ENABLED || message == null || message.isEmpty()) return;
        activeNotification = message;
        notificationTimestamp = System.currentTimeMillis();
    }

    public enum ColorTheme {
        GOLD("§6Gold / Orange", 0xFFFFAA00, 0xFFFFCC33, 0x111116, 0xFFFFB300),
        CYAN("§bNeon Cyan", 0xFF00E5FF, 0xFF69F0AE, 0x10161A, 0xFF00E5FF),
        PURPLE("§dPurple / Neon", 0xFFD500F9, 0xFFEA80FC, 0x16101A, 0xFFE040FB),
        EMERALD("§aEmerald Green", 0xFF00E676, 0xFFB9F6CA, 0x101A12, 0xFF00E676),
        RED("§cCrimson Red", 0xFFFF1744, 0xFFFF8A80, 0x1A1010, 0xFFFF5252);

        public final String displayName;
        public final int borderColor;
        public final int accentColor;
        public final int rgbBg;
        public final int progressColor;

        ColorTheme(String displayName, int borderColor, int accentColor, int rgbBg, int progressColor) {
            this.displayName = displayName;
            this.borderColor = borderColor;
            this.accentColor = accentColor;
            this.rgbBg = rgbBg;
            this.progressColor = progressColor;
        }

        public int getThemedBgColor(float opacity) {
            int alpha = Math.max(25, Math.min(255, (int) (opacity * 255.0f)));
            return (alpha << 24) | (this.rgbBg & 0x00FFFFFF);
        }
    }

    public static ColorTheme CURRENT_THEME = ColorTheme.GOLD;

    private static float currentAngle = 0.0f;
    private static long lastAngleUpdateTime = System.currentTimeMillis();

    public static void updatePlaybackState(long positionMs, boolean playing) {
        TrackInfo track = ClientAudioEngine.getInstance().getCurrentTrack();
        String curId = track != null ? track.id() : "";
        if (!curId.equals(lastTrackId)) {
            lastTrackId = curId;
            currentPositionMs = positionMs;
        } else {
            currentPositionMs = positionMs;
        }
        lastUpdateTime = System.currentTimeMillis();
        isPlaying = playing;
    }

    public static void onLocalStreamStarted() {
        lastUpdateTime = System.currentTimeMillis();
    }

    public static float getDiscRotationAngle() {
        long now = System.currentTimeMillis();
        if (isPlaying) {
            long delta = now - lastAngleUpdateTime;
            if (delta > 0 && delta < 500) { // Limitar por saltos bruscos
                currentAngle = (currentAngle + (delta / 4000.0f * 360.0f)) % 360.0f;
            }
        }
        lastAngleUpdateTime = now;
        return currentAngle;
    }

    public static long getLivePositionMs() {
        long livePos = currentPositionMs;
        if (isPlaying && ClientAudioEngine.getInstance().isActualSoundPlaying()) {
            livePos += (System.currentTimeMillis() - lastUpdateTime);
        }
        return livePos;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui.hud.isHidden()) return;

        // Renderizar aviso de actividad flotante si el jugador los activó en Settings
        renderNotificationToast(drawContext, client);

        boolean quickControlsOpen = client.gui.screen() instanceof MusicQuickControlsScreen;
        if (!HUD_ENABLED || quickControlsOpen) return;

        TrackInfo track = ClientAudioEngine.getInstance().getCurrentTrack();
        if (track == null) return;

        renderHudCardContent(drawContext, client, track, true);
    }

    public static void renderHudCardContent(GuiGraphicsExtractor drawContext, Minecraft client, TrackInfo track, boolean drawBackgroundAndBorder) {
        long livePos = getLivePositionMs();

        long duration = track.durationMs() > 0 ? track.durationMs() : 180000L;
        float progress = Math.min(1.0f, (float) livePos / (float) duration);

        // Aplicar transformación de escala si HUD_SCALE != 1.0f
        drawContext.pose().pushMatrix();
        if (HUD_SCALE != 1.0f) {
            drawContext.pose().translate(HUD_X, HUD_Y);
            drawContext.pose().scale(HUD_SCALE, HUD_SCALE);
            drawContext.pose().translate(-HUD_X, -HUD_Y);
        }

        // 1. Contenedor: Cuando el menú rápido está cerrado dibujamos el fondo y borde propios del HUD.
        if (drawBackgroundAndBorder) {
            drawContext.fill(HUD_X, HUD_Y, HUD_X + HUD_WIDTH, HUD_Y + HUD_HEIGHT, CURRENT_THEME.getThemedBgColor(HUD_OPACITY));
            drawContext.outline(HUD_X, HUD_Y, HUD_WIDTH, HUD_HEIGHT, CURRENT_THEME.borderColor);
        }

        // 2. Miniatura como Disco de Vinilo girando en vivo
        Identifier thumbId = ThumbnailTextureManager.getThumbnail(track.thumbnailUrl());
        int discSize = 44;
        int discX = HUD_X + 8;
        int discY = HUD_Y + 6;
        int centerX = discX + discSize / 2;
        int centerY = discY + discSize / 2;
        int r = discSize / 2;

        drawContext.pose().pushMatrix();
        drawContext.pose().translate(centerX, centerY);

        float angle = getDiscRotationAngle();
        drawContext.pose().rotate((float) Math.toRadians(angle));

        if (thumbId != null) {
            // Se dibuja directamente el disco de vinilo circular transparente generado
            drawContext.blit(RenderPipelines.GUI_TEXTURED, thumbId, -r, -r, 0.0f, 0.0f, discSize, discSize, discSize, discSize);
        }

        drawContext.pose().popMatrix();

        // 3. Título del video de YouTube
        int textX = HUD_X + 64;
        String rawTitle = track.title();
        String title = rawTitle.length() > 25 ? rawTitle.substring(0, 23) + "..." : rawTitle;
        drawContext.text(client.font, "§f§l" + title, textX, HUD_Y + 7, 0xFFFFFFFF);

        // 4. Autor de la canción (solo el nombre del artista/autor)
        String rawAuthor = track.author();
        String author = rawAuthor.length() > 26 ? rawAuthor.substring(0, 24) + "..." : rawAuthor;
        drawContext.text(client.font, "§e" + author, textX, HUD_Y + 20, 0xFFEEEEEE);

        // 5. Barra de progreso estética
        int barX = textX;
        int barY = HUD_Y + 36;
        int barWidth = 110;
        int barHeight = 4;

        drawContext.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF33333E);
        drawContext.fill(barX, barY, barX + (int) (barWidth * progress), barY + barHeight, CURRENT_THEME.progressColor);

        // 6. Tiempo numérico (ej: 01:23 / 03:45)
        long currentSec = (livePos / 1000);
        long totalSec = (duration / 1000);
        String timeStr = String.format("%02d:%02d/%02d:%02d", currentSec / 60, currentSec % 60, totalSec / 60, totalSec % 60);
        drawContext.text(client.font, "§6" + timeStr, barX + barWidth + 6, barY - 2, 0xFFFFFFDD);

        drawContext.pose().popMatrix();
    }

    private void renderNotificationToast(GuiGraphicsExtractor drawContext, Minecraft client) {
        if (!NOTIFICATIONS_ENABLED) return;

        boolean settingsPreview = client.gui.screen() instanceof YouTubeSearchScreen screen
                && screen.isSettingsTabActive();
        long elapsed = System.currentTimeMillis() - notificationTimestamp;

        String msgToRender = null;
        if (!activeNotification.isEmpty() && elapsed < NOTIFICATION_DURATION_MS) {
            msgToRender = activeNotification;
        } else if (settingsPreview) {
            msgToRender = "§6[PlayCraftMusic] §eVista previa de aviso de actividad";
        }

        if (msgToRender == null) return;

        int textW = client.font.width(msgToRender);
        int boxW = textW + 12;
        int boxH = 16;

        drawContext.pose().pushMatrix();
        drawContext.pose().translate(NOTIFICATION_X, NOTIFICATION_Y);
        drawContext.pose().scale(NOTIFICATION_SCALE, NOTIFICATION_SCALE);

        drawContext.fill(0, 0, boxW, boxH, CURRENT_THEME.getThemedBgColor(HUD_OPACITY));
        drawContext.text(client.font, msgToRender, 6, 4, 0xFFFFFFFF);

        drawContext.pose().popMatrix();
    }
}
