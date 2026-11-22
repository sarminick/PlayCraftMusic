package com.playcraft.music.client.gui;

import com.playcraft.music.client.PlayCraftClient;
import com.playcraft.music.client.audio.ClientAudioEngine;
import com.playcraft.music.client.gui.ui.ModernUI;
import com.playcraft.music.client.gui.ui.PlayCraftIcons;
import com.playcraft.music.model.TrackInfo;
import com.playcraft.music.network.ControlTrackPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class MusicQuickControlsScreen extends Screen {

    private ScaledIconButton playPauseBtn;
    private ScaledIconButton muteBtn;
    private ModernMediaSlider posSlider;
    private ModernMediaSlider volSlider;

    public MusicQuickControlsScreen() {
        super(Component.literal("Controles Rápidos"));
    }

    private static class ScaledIconButton extends Button {
        private PlayCraftIcons.Icon icon;
        private final boolean isPrimary;
        private final float uiScale;

        public ScaledIconButton(int x, int y, int width, int height,
                                PlayCraftIcons.Icon icon, boolean isPrimary, float uiScale, OnPress onPress) {
            super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
            this.icon = icon;
            this.isPrimary = isPrimary;
            this.uiScale = uiScale;
        }

        public void setIcon(PlayCraftIcons.Icon icon) {
            this.icon = icon;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            if (!this.visible) return;
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            int h = this.getHeight();
            boolean hovered = this.active && this.isHoveredOrFocused();
            int accent = MusicHudOverlay.getActiveAccentColor();

            int bg;
            int border;
            int iconColor;
            if (isPrimary && this.active) {
                bg = hovered ? ModernUI.brighten(accent, 0.16f) : accent;
                border = ModernUI.brighten(accent, 0.25f);
                iconColor = ModernUI.isBrightColor(accent) ? ModernUI.TEXT_DARK : ModernUI.TEXT_PRIMARY;
            } else {
                bg = hovered ? ModernUI.BG_HOVER : ModernUI.BG_ELEVATED;
                border = hovered ? ModernUI.BORDER_HOVER : ModernUI.BORDER_SUBTLE;
                iconColor = hovered ? ModernUI.TEXT_PRIMARY : ModernUI.TEXT_SECONDARY;
            }

            ModernUI.drawRoundedCard(context, x, y, w, h, 4, bg, border);
            int iconSize = Math.max(8, Math.min(13, Math.round(10 * uiScale)));
            int iconX = x + (w - iconSize) / 2;
            int iconY = y + (h - iconSize) / 2;
            PlayCraftIcons.draw(context, this.icon, iconX, iconY, iconSize, iconColor);
        }
    }

    @Override
    protected void init() {
        float scale = MusicHudOverlay.HUD_SCALE;
        int trayX = MusicHudOverlay.HUD_X;
        int hudH = Math.round(MusicHudOverlay.HUD_HEIGHT * scale);
        int trayY = MusicHudOverlay.HUD_Y + hudH;
        int trayWidth = Math.round(MusicHudOverlay.HUD_WIDTH * scale);

        int pad = Math.max(3, Math.round(6 * scale));
        int btnH = Math.max(12, Math.round(18 * scale));
        int sliderH = Math.max(14, Math.round(22 * scale));
        int rowSpacing = Math.max(3, Math.round(4 * scale));
        int colGap = Math.max(2, Math.round(4 * scale));

        int availW = trayWidth - (pad * 2);
        int currentY = trayY + rowSpacing;

        // Row 1: Rewind, Play/Pause, Skip
        int btnW = (availW - (colGap * 2)) / 3;
        int rem = availW - (btnW * 3 + colGap * 2);

        this.addRenderableWidget(new ScaledIconButton(
                trayX + pad, currentY, btnW, btnH,
                PlayCraftIcons.Icon.PREV, false, scale,
                button -> ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.REWIND))
        ));

        ClientAudioEngine engine = ClientAudioEngine.getInstance();
        PlayCraftIcons.Icon playIcon = engine.isPlaying() ? PlayCraftIcons.Icon.PAUSE : PlayCraftIcons.Icon.PLAY;
        playPauseBtn = new ScaledIconButton(
                trayX + pad + btnW + colGap, currentY, btnW + rem, btnH,
                playIcon, true, scale,
                button -> ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.TOGGLE_PAUSE))
        );
        this.addRenderableWidget(playPauseBtn);

        this.addRenderableWidget(new ScaledIconButton(
                trayX + pad + (btnW * 2) + rem + (colGap * 2), currentY, btnW, btnH,
                PlayCraftIcons.Icon.NEXT, false, scale,
                button -> ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.SKIP))
        ));

        // Row 2: Seek bar
        currentY += btnH + rowSpacing;
        TrackInfo cur = engine.getCurrentTrack();
        double initPos = 0.0;
        if (cur != null && cur.durationMs() > 0) {
            initPos = Math.min(1.0, (double) MusicHudOverlay.getLivePositionMs() / (double) cur.durationMs());
        }

        posSlider = new ModernMediaSlider(
                trayX + pad, currentY, availW, sliderH, initPos,
                ModernMediaSlider.Theme.fromHudTheme(MusicHudOverlay.CURRENT_THEME),
                val -> {
                    TrackInfo t = ClientAudioEngine.getInstance().getCurrentTrack();
                    if (t == null || t.durationMs() <= 0) {
                        return ModernUI.text("--:-- / --:--");
                    }
                    long curMs = (long) (val * t.durationMs());
                    return ModernUI.text(formatDuration(curMs) + " / " + formatDuration(t.durationMs()));
                },
                val -> {
                    TrackInfo t = ClientAudioEngine.getInstance().getCurrentTrack();
                    if (t != null && t.durationMs() > 0) {
                        long targetMs = (long) (val * t.durationMs());
                        ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.SEEK, targetMs));
                    }
                },
                true
        );
        posSlider.setUiScale(scale);
        this.addRenderableWidget(posSlider);

        // Row 3: Volume slider + Mute button (no reconnect button in quick HUD tray)
        currentY += sliderH + rowSpacing;
        int muteW = Math.max(20, Math.round(28 * scale));
        int volW = availW - muteW - colGap;
        double initVol = engine.isMuted() ? 0.0 : engine.getVolume();

        volSlider = new ModernMediaSlider(
                trayX + pad, currentY, volW, sliderH, initVol,
                ModernMediaSlider.Theme.VOLUME,
                val -> {
                    int pct = (int) Math.round(val * 100);
                    return ModernUI.text(pct == 0 ? "Silenciado" : "Volumen: " + pct + "%");
                },
                val -> {
                    ClientAudioEngine eng = ClientAudioEngine.getInstance();
                    eng.setVolume(val.floatValue());
                    if (muteBtn != null) {
                        muteBtn.setIcon(eng.isMuted() ? PlayCraftIcons.Icon.MUTE : PlayCraftIcons.Icon.VOLUME);
                    }
                }
        );
        volSlider.setUiScale(scale);
        this.addRenderableWidget(volSlider);

        muteBtn = new ScaledIconButton(
                trayX + pad + volW + colGap, currentY, muteW, sliderH,
                engine.isMuted() ? PlayCraftIcons.Icon.MUTE : PlayCraftIcons.Icon.VOLUME,
                false, scale,
                button -> {
                    ClientAudioEngine eng = ClientAudioEngine.getInstance();
                    eng.toggleMute();
                    button.setFocused(false);
                    updateMuteVisuals();
                }
        );
        this.addRenderableWidget(muteBtn);
    }

    private void updateMuteVisuals() {
        ClientAudioEngine eng = ClientAudioEngine.getInstance();
        if (muteBtn != null) {
            muteBtn.setIcon(eng.isMuted() ? PlayCraftIcons.Icon.MUTE : PlayCraftIcons.Icon.VOLUME);
        }
        if (volSlider != null && !volSlider.isDragging()) {
            volSlider.setProgressDirect(eng.isMuted() ? 0.0 : eng.getVolume());
        }
    }

    private static String formatDuration(long ms) {
        if (ms <= 0) return "00:00";
        long sec = ms / 1000;
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (PlayCraftClient.openQuickControlsKey != null && PlayCraftClient.openQuickControlsKey.matches(event)) {
            this.onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Sin difuminado para mantener el entorno de juego visible bajo el widget
    }

    public void onSyncUpdate() {
        ClientAudioEngine engine = ClientAudioEngine.getInstance();
        if (playPauseBtn != null) {
            playPauseBtn.setIcon(engine.isPlaying() ? PlayCraftIcons.Icon.PAUSE : PlayCraftIcons.Icon.PLAY);
        }
        updateMuteVisuals();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        ClientAudioEngine engine = ClientAudioEngine.getInstance();
        TrackInfo cur = engine.getCurrentTrack();

        if (playPauseBtn != null) {
            playPauseBtn.setIcon(engine.isPlaying() ? PlayCraftIcons.Icon.PAUSE : PlayCraftIcons.Icon.PLAY);
        }
        if (posSlider != null && !posSlider.isDragging() && cur != null && cur.durationMs() > 0) {
            posSlider.setProgressDirect((double) MusicHudOverlay.getLivePositionMs() / (double) cur.durationMs());
        }

        float scale = MusicHudOverlay.HUD_SCALE;
        int trayX = MusicHudOverlay.HUD_X;
        int trayY = MusicHudOverlay.HUD_Y;
        int trayWidth = Math.round(MusicHudOverlay.HUD_WIDTH * scale);
        int hudH = Math.round(MusicHudOverlay.HUD_HEIGHT * scale);

        int bg = MusicHudOverlay.getActiveBgColor(MusicHudOverlay.HUD_OPACITY);
        int border = ModernUI.withAlpha(MusicHudOverlay.getActiveBorderColor(), 200);

        int btnH = Math.max(12, Math.round(18 * scale));
        int sliderH = Math.max(14, Math.round(22 * scale));
        int rowSpacing = Math.max(3, Math.round(4 * scale));
        int trayOnlyHeight = btnH + (sliderH * 2) + (rowSpacing * 4);
        int unifiedHeight = hudH + trayOnlyHeight;

        ModernUI.drawRoundedCard(context, trayX, trayY, trayWidth, unifiedHeight, 6, bg, border);
        context.fill(trayX + 8, trayY + hudH, trayX + trayWidth - 8, trayY + hudH + 1,
                ModernUI.withAlpha(MusicHudOverlay.getActiveBorderColor(), 80));

        if (cur != null) {
            MusicHudOverlay.renderHudCardContent(context, this.minecraft, cur, false);
        } else {
            int centerX = trayX + trayWidth / 2;
            int centerY = trayY + (hudH / 2) - 4;
            ModernUI.drawCentered(context, this.font, "No hay canción reproduciéndose", centerX, centerY, ModernUI.TEXT_SECONDARY);
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
