package com.playcraft.music.client.gui;

import com.playcraft.music.client.audio.ClientAudioEngine;
import com.playcraft.music.client.gui.ui.ModernUI;
import com.playcraft.music.model.TrackInfo;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.awt.Color;

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
        DYNAMIC("Dinámico (Carátula)", 0xFFFFCC33, 0xFFFFAA00, 0x0E1016, 0xFFFFAA00),
        GOLD("Gold / Orange", 0xFFFFCC33, 0xFFFFAA00, 0x111116, 0xFFFFB300),
        CYAN("Neon Cyan", 0xFF00E5FF, 0xFF00E5FF, 0x10161A, 0xFF00E5FF),
        PURPLE("Purple / Neon", 0xFFEA80FC, 0xFFD500F9, 0x16101A, 0xFFE040FB),
        EMERALD("Emerald Green", 0xFF00E676, 0xFF00E676, 0x101A12, 0xFF00E676),
        RED("Crimson Red", 0xFFFF5252, 0xFFFF1744, 0x1A1010, 0xFFFF5252);

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

    public static ColorTheme CURRENT_THEME = ColorTheme.DYNAMIC;

    private static volatile int dynamicAccentArgb = 0xFFFFAA00;
    private static volatile int dynamicBorderArgb = 0xFFFFCC33;
    private static volatile int dynamicBgRgb = 0x0E1016;
    private static volatile int dynamicVolumeArgb = 0xFF38BDF8;
    private static String lastDynamicTrackId = "";

    public static void updateDynamicColorFromRgb(int vibrantRgb) {
        int r = (vibrantRgb >>> 16) & 0xFF;
        int g = (vibrantRgb >>> 8) & 0xFF;
        int b = vibrantRgb & 0xFF;
        float[] hsb = Color.RGBtoHSB(r, g, b, null);
        float hue = hsb[0];
        float sat = Math.max(0.55f, Math.min(0.95f, hsb[1]));
        float bri = Math.max(0.76f, Math.min(0.96f, hsb[2]));

        int accentRgb = Color.HSBtoRGB(hue, sat, bri) & 0x00FFFFFF;
        int borderRgb = Color.HSBtoRGB(hue, Math.max(0.45f, sat * 0.85f), Math.min(1.0f, bri + 0.06f)) & 0x00FFFFFF;
        int bgRgb = Color.HSBtoRGB(hue, Math.min(0.40f, sat * 0.45f), 0.08f) & 0x00FFFFFF;
        int volRgb = Color.HSBtoRGB((hue + 0.08f) % 1.0f, Math.max(0.50f, sat * 0.85f), 0.94f) & 0x00FFFFFF;

        dynamicAccentArgb = 0xFF000000 | accentRgb;
        dynamicBorderArgb = 0xFF000000 | borderRgb;
        dynamicBgRgb = bgRgb;
        dynamicVolumeArgb = 0xFF000000 | volRgb;
    }

    public static int getActiveAccentColor() {
        return CURRENT_THEME == ColorTheme.DYNAMIC ? dynamicAccentArgb : CURRENT_THEME.accentColor;
    }

    public static int getActiveBorderColor() {
        return CURRENT_THEME == ColorTheme.DYNAMIC ? dynamicBorderArgb : CURRENT_THEME.borderColor;
    }

    public static int getActiveProgressColor() {
        return CURRENT_THEME == ColorTheme.DYNAMIC ? dynamicAccentArgb : CURRENT_THEME.progressColor;
    }

    public static int getActiveBgColor(float opacity) {
        if (CURRENT_THEME == ColorTheme.DYNAMIC) {
            int alpha = Math.max(25, Math.min(255, (int) (opacity * 255.0f)));
            return (alpha << 24) | (dynamicBgRgb & 0x00FFFFFF);
        }
        return CURRENT_THEME.getThemedBgColor(opacity);
    }

    public static int getActiveVolumeColor() {
        return CURRENT_THEME == ColorTheme.DYNAMIC ? dynamicVolumeArgb : 0xFF38BDF8;
    }

    private static float currentAngle = 0.0f;
    private static long lastAngleUpdateTime = System.currentTimeMillis();

    public static void updatePlaybackState(long positionMs, boolean playing) {
        TrackInfo track = ClientAudioEngine.getInstance().getCurrentTrack();
        String curId = track != null ? track.id() : "";
        if (!curId.equals(lastTrackId)) {
            lastTrackId = curId;
            currentPositionMs = positionMs;
            syncDynamicThemeForTrack(track);
        } else {
            currentPositionMs = positionMs;
        }
        lastUpdateTime = System.currentTimeMillis();
        isPlaying = playing;
    }

    private static void syncDynamicThemeForTrack(TrackInfo track) {
        if (track == null) return;
        String trackKey = track.id() != null ? track.id() : "";
        if (!trackKey.equals(lastDynamicTrackId)) {
            lastDynamicTrackId = trackKey;
            Integer cachedRgb = ThumbnailTextureManager.getCachedDominantRgb(track.thumbnailUrl());
            if (cachedRgb != null) {
                updateDynamicColorFromRgb(cachedRgb);
            }
        }
    }

    public static void onLocalStreamStarted() {
        lastUpdateTime = System.currentTimeMillis();
    }

    public static float getDiscRotationAngle() {
        long now = System.currentTimeMillis();
        if (isPlaying && ClientAudioEngine.getInstance().isActualSoundPlaying()) {
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

        boolean hideForScreen = client.gui.screen() instanceof MusicQuickControlsScreen
                || (client.gui.screen() instanceof YouTubeSearchScreen screen && !screen.isSettingsTabActive());
        if (!HUD_ENABLED || hideForScreen) return;

        TrackInfo track = ClientAudioEngine.getInstance().getCurrentTrack();
        if (track == null) return;

        renderHudCardContent(drawContext, client, track, true);
    }

    public static void renderHudCardContent(GuiGraphicsExtractor drawContext, Minecraft client, TrackInfo track, boolean drawBackgroundAndBorder) {
        syncDynamicThemeForTrack(track);
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

        // 1. Contenedor: Cuando el menú rápido está cerrado dibujamos el fondo y borde modernos del HUD.
        int activeAccent = getActiveAccentColor();
        int activeBorder = getActiveBorderColor();
        int activeProgress = getActiveProgressColor();
        if (drawBackgroundAndBorder) {
            ModernUI.drawRoundedCard(drawContext, HUD_X, HUD_Y, HUD_WIDTH, HUD_HEIGHT, 6,
                    getActiveBgColor(HUD_OPACITY), ModernUI.withAlpha(activeBorder, 190));
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
            drawContext.blit(RenderPipelines.GUI_TEXTURED, thumbId, -r, -r, 0.0f, 0.0f, discSize, discSize, discSize, discSize);
        }

        drawContext.pose().popMatrix();

        // 3. Título de la pista (con carrusel suave si excede el ancho disponible)
        int textX = HUD_X + 62;
        ModernUI.drawScrollingBold(drawContext, client.font, track.title(), textX, HUD_Y + 8, HUD_WIDTH - 70, ModernUI.TEXT_PRIMARY);

        // 4. Autor o estado en vivo del motor de audio
        ClientAudioEngine.PlaybackStatus status = ClientAudioEngine.getInstance().getPlaybackStatus();
        String subLine;
        int subColor;
        if (status == ClientAudioEngine.PlaybackStatus.LOADING) {
            subLine = "Cargando audio...";
            subColor = 0xFFFBBF24;
        } else if (status == ClientAudioEngine.PlaybackStatus.RECONNECTING) {
            subLine = "Reconectando audio...";
            subColor = 0xFFFB923C;
        } else if (status == ClientAudioEngine.PlaybackStatus.ERROR) {
            subLine = "Reintentando audio...";
            subColor = 0xFFF87171;
        } else {
            subLine = ModernUI.truncate(client.font, track.author(), HUD_WIDTH - 72);
            subColor = ModernUI.TEXT_SECONDARY;
        }
        ModernUI.drawText(drawContext, client.font, subLine, textX, HUD_Y + 21, subColor, false);

        // 5. Barra de progreso estética moderna
        int barX = textX;
        int barY = HUD_Y + 38;
        int barWidth = 108;
        int barHeight = 4;

        ModernUI.fillRoundedRect(drawContext, barX, barY, barWidth, barHeight, 2, 0xFF232734);
        int fillWidth = Math.max(0, (int) (barWidth * progress));
        if (fillWidth > 0) {
            ModernUI.fillRoundedRect(drawContext, barX, barY, fillWidth, barHeight, 2, activeProgress);
        }

        // 6. Tiempo numérico (ej: 01:23/03:45)
        long currentSec = livePos / 1000;
        long totalSec = duration / 1000;
        String timeStr = String.format("%02d:%02d/%02d:%02d", currentSec / 60, currentSec % 60, totalSec / 60, totalSec % 60);
        ModernUI.drawText(drawContext, client.font, timeStr, barX + barWidth + 7, barY - 2, activeAccent, false);

        drawContext.pose().popMatrix();
    }

    private void renderNotificationToast(GuiGraphicsExtractor drawContext, Minecraft client) {
        if (!NOTIFICATIONS_ENABLED) return;

        boolean settingsPreview = client.gui.screen() instanceof YouTubeSearchScreen screen
                && screen.isSettingsTabActive();
        long elapsed = System.currentTimeMillis() - notificationTimestamp;

        String msgToRender = null;
        if (!activeNotification.isEmpty() && elapsed < NOTIFICATION_DURATION_MS) {
            msgToRender = activeNotification.replaceAll("§.", "");
        } else if (settingsPreview) {
            msgToRender = "[PlayCraft] Vista previa de aviso de actividad";
        }

        if (msgToRender == null) return;

        int textW = ModernUI.width(client.font, msgToRender);
        int boxW = textW + 16;
        int boxH = 18;

        drawContext.pose().pushMatrix();
        drawContext.pose().translate(NOTIFICATION_X, NOTIFICATION_Y);
        drawContext.pose().scale(NOTIFICATION_SCALE, NOTIFICATION_SCALE);

        ModernUI.drawRoundedCard(drawContext, 0, 0, boxW, boxH, 4,
                getActiveBgColor(HUD_OPACITY), ModernUI.withAlpha(getActiveBorderColor(), 190));
        ModernUI.drawText(drawContext, client.font, msgToRender, 8, 5, ModernUI.TEXT_PRIMARY, false);

        drawContext.pose().popMatrix();
    }
}
