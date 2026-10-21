package com.playcraft.music.client.gui;

import com.playcraft.music.client.audio.ClientAudioEngine;
import com.playcraft.music.model.TrackInfo;
import com.playcraft.music.network.ControlTrackPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Submenú/Bandeja rápida de reproducción:
 * Aparece integrada directamente debajo del contenedor flotante como una sola tarjeta unificada (sin borde divisor):
 * - [⏮ Prev] [⏯ Play/Pause] [⏭ Next] con botones modernos planos que escalan su tamaño y texto proporcionalmente
 * - [⏱ Seek Bar] con el color principal del tema
 * - [🔊 Volume Slider] con un color secundario de alto contraste
 */
public class MusicQuickControlsScreen extends Screen {

    public MusicQuickControlsScreen() {
        super(Component.literal("PlayCraft - Bandeja de Controles"));
    }

    /**
     * Botón plano y moderno que escala su altura, borde y texto de forma 100% proporcional a HUD_SCALE,
     * evitando que el texto "Play/Pause" se recorte en escalas pequeñas (0.7x).
     */
    private static class ScaledModernButton extends Button {
        private final float uiScale;

        public ScaledModernButton(int x, int y, int width, int height, Component message, float uiScale, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
            this.uiScale = uiScale;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            int h = this.getHeight();
            boolean hovered = this.isHovered() && this.active;

            int bgColor = !this.active ? 0x8816161E : (hovered ? 0xEE2C2C3A : 0xCC1E1E28);
            int borderColor = !this.active ? 0x662A2A35 : (hovered ? MusicHudOverlay.CURRENT_THEME.borderColor : 0xFF38384A);
            int textColor = !this.active ? 0xFF666677 : (hovered ? 0xFFFFFFFF : 0xFFEEEEEE);

            context.fill(x, y, x + w, y + h, bgColor);
            context.outline(x, y, w, h, borderColor);

            Minecraft mc = Minecraft.getInstance();
            Component msg = this.getMessage();
            int textW = mc.font.width(msg);

            // Calculamos la escala del texto según uiScale y también garantizamos que siempre quepa dentro del ancho del botón
            float maxFitScale = (w - 4.0f) / Math.max(1.0f, (float) textW);
            float finalScale = Math.min(uiScale * 0.92f, maxFitScale);

            float centerX = x + (w / 2.0f);
            float centerY = y + (h / 2.0f) - (4.0f * finalScale);

            context.pose().pushMatrix();
            context.pose().translate(centerX, centerY);
            context.pose().scale(finalScale, finalScale);
            context.text(mc.font, msg, (int) (-textW / 2.0f), 0, textColor);
            context.pose().popMatrix();
        }
    }

    @Override
    protected void init() {
        float scale = MusicHudOverlay.HUD_SCALE;
        int trayX = MusicHudOverlay.HUD_X;
        int hudH = Math.round(MusicHudOverlay.HUD_HEIGHT * scale);
        int trayY = MusicHudOverlay.HUD_Y + hudH; // Adosado directamente sin separación
        int trayWidth = Math.round(MusicHudOverlay.HUD_WIDTH * scale);

        TrackInfo currentTrack = ClientAudioEngine.getInstance().getCurrentTrack();
        boolean hasTrack = currentTrack != null;

        int pad = Math.max(4, Math.round(8 * scale));
        int gap = Math.max(2, Math.round(3 * scale));
        int btnW = (trayWidth - (pad * 2) - (gap * 2)) / 3;
        int btnH = Math.max(11, Math.round(18 * scale));

        int row1Y = trayY + Math.round(4 * scale);

        // Fila 1: Botones modernos proporcionales (nunca recortan el texto ni se desbordan)
        ScaledModernButton prevBtn = new ScaledModernButton(
                trayX + pad, row1Y, btnW, btnH,
                Component.literal("⏮ Prev"), scale,
                button -> ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.REWIND))
        );
        prevBtn.active = hasTrack;
        this.addRenderableWidget(prevBtn);

        ScaledModernButton playBtn = new ScaledModernButton(
                trayX + pad + btnW + gap, row1Y, btnW, btnH,
                Component.literal("⏯ Play/Pause"), scale,
                button -> ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.TOGGLE_PAUSE))
        );
        playBtn.active = hasTrack;
        this.addRenderableWidget(playBtn);

        ScaledModernButton nextBtn = new ScaledModernButton(
                trayX + pad + (btnW + gap) * 2, row1Y, btnW, btnH,
                Component.literal("⏭ Next"), scale,
                button -> ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.SKIP))
        );
        nextBtn.active = hasTrack;
        this.addRenderableWidget(nextBtn);

        // Fila 2: Barra de tiempo interactiva (Seek Bar) con el color principal del tema
        long currentDuration = (hasTrack && currentTrack.durationMs() > 0) ? currentTrack.durationMs() : 0L;
        long livePos = hasTrack ? MusicHudOverlay.getLivePositionMs() : 0L;
        double initialPosRatio = (hasTrack && currentDuration > 0) ? Math.max(0.0, Math.min(1.0, (double) livePos / (double) currentDuration)) : 0.0;
        int sliderH = Math.max(12, Math.round(20 * scale));
        int row2Y = trayY + Math.round(27 * scale);

        ModernMediaSlider posSlider = new ModernMediaSlider(
                trayX + pad,
                row2Y,
                trayWidth - (pad * 2),
                sliderH,
                initialPosRatio,
                ModernMediaSlider.Theme.fromHudTheme(MusicHudOverlay.CURRENT_THEME),
                val -> {
                    TrackInfo t = ClientAudioEngine.getInstance().getCurrentTrack();
                    if (t == null) {
                        return Component.literal("⏱ 00:00 / 00:00");
                    }
                    long d = t.durationMs() > 0 ? t.durationMs() : 0L;
                    return Component.literal("⏱ " + formatDuration((long) (val * d)) + " / " + formatDuration(d));
                },
                val -> {
                    TrackInfo t = ClientAudioEngine.getInstance().getCurrentTrack();
                    if (t != null && t.durationMs() > 0) {
                        long targetMs = (long) (val * t.durationMs());
                        MusicHudOverlay.updatePlaybackState(targetMs, ClientAudioEngine.getInstance().isPlaying());
                        ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.SEEK, targetMs));
                    }
                },
                true,
                scale
        ) {
            @Override
            public void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
                TrackInfo t = ClientAudioEngine.getInstance().getCurrentTrack();
                if (t != null && t.durationMs() > 0) {
                    long currentLive = MusicHudOverlay.getLivePositionMs();
                    this.setProgressDirect((double) currentLive / (double) t.durationMs());
                }
                super.extractWidgetRenderState(context, mouseX, mouseY, delta);
            }
        };
        posSlider.active = hasTrack;
        this.addRenderableWidget(posSlider);

        // Fila 3: Slider de Volumen con color contrastante (volumeFromHudTheme) y botón de silenciar proporcional
        float currentVol = ClientAudioEngine.getInstance().getVolume();
        boolean isMuted = ClientAudioEngine.getInstance().isMuted();
        int muteBtnW = Math.max(16, Math.round(24 * scale));
        int row3Y = trayY + Math.round(52 * scale);

        this.addRenderableWidget(new ModernMediaSlider(
                trayX + pad,
                row3Y,
                trayWidth - (pad * 2) - muteBtnW - gap,
                sliderH,
                currentVol,
                ModernMediaSlider.Theme.volumeFromHudTheme(MusicHudOverlay.CURRENT_THEME),
                val -> Component.literal("🔊 " + (int) (val * 100) + "%"),
                val -> {
                    ClientAudioEngine.getInstance().setVolume(val.floatValue());
                    com.playcraft.music.client.PlayCraftConfig.save();
                },
                false,
                scale
        ));

        this.addRenderableWidget(new ScaledModernButton(
                trayX + trayWidth - pad - muteBtnW, row3Y, muteBtnW, sliderH,
                Component.literal(isMuted ? "🔇" : "🔊"), scale,
                b -> {
                    ClientAudioEngine.getInstance().toggleMute();
                    com.playcraft.music.client.PlayCraftConfig.save();
                    this.rebuildWidgets();
                }
        ));
    }

    private static String formatDuration(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Cerrar también al pulsar la tecla Coma (',') o la tecla asignada en controles (además de Escape)
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_COMMA
                || (com.playcraft.music.client.PlayCraftClient.openQuickControlsKey != null
                && com.playcraft.music.client.PlayCraftClient.openQuickControlsKey.matches(event))) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Sin desenfoque gaussiano (blur) para mantener el juego y el HUD nítidos.
    }

    public void onSyncUpdate() {
        TrackInfo currentTrack = ClientAudioEngine.getInstance().getCurrentTrack();
        boolean hasTrack = currentTrack != null;
        String trackUrl = hasTrack ? currentTrack.streamUrl() : "";
        if (lastTrackUrl == null || !lastTrackUrl.equals(trackUrl) || lastHasTrack == null || lastHasTrack != hasTrack) {
            lastTrackUrl = trackUrl;
            lastHasTrack = hasTrack;
            this.rebuildWidgets();
        }
    }

    private String lastTrackUrl = null;
    private Boolean lastHasTrack = null;

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        TrackInfo currentTrack = ClientAudioEngine.getInstance().getCurrentTrack();
        boolean hasTrack = currentTrack != null;
        String trackUrl = hasTrack ? currentTrack.streamUrl() : "";
        if (lastTrackUrl == null || lastHasTrack == null) {
            lastTrackUrl = trackUrl;
            lastHasTrack = hasTrack;
        } else if (!lastTrackUrl.equals(trackUrl) || lastHasTrack != hasTrack) {
            lastTrackUrl = trackUrl;
            lastHasTrack = hasTrack;
            this.rebuildWidgets();
            return;
        }

        float scale = MusicHudOverlay.HUD_SCALE;
        int trayX = MusicHudOverlay.HUD_X;
        int trayY = MusicHudOverlay.HUD_Y;
        int trayWidth = Math.round(MusicHudOverlay.HUD_WIDTH * scale);
        int hudH = Math.round(MusicHudOverlay.HUD_HEIGHT * scale);
        int totalHeight = hudH + Math.round(80 * scale);

        int themedBg = MusicHudOverlay.CURRENT_THEME.getThemedBgColor(MusicHudOverlay.HUD_OPACITY);

        // Dibujamos un único fondo continuo para todo el contenedor (HUD + bandeja rápida) evitando cualquier
        // costura de sub-píxeles entre ambos bloques, y luego renderizamos la miniatura y texto ENCIMA para que no se opaquen.
        context.fill(trayX, trayY, trayX + trayWidth, trayY + totalHeight, themedBg);
        if (currentTrack != null) {
            MusicHudOverlay.renderHudCardContent(context, Minecraft.getInstance(), currentTrack, false);
        } else {
            int discSize = Math.round(40 * scale);
            int discX = trayX + Math.round(10 * scale);
            int discY = trayY + Math.round(8 * scale);
            context.fill(discX, discY, discX + discSize, discY + discSize, 0xFF181822);
            context.outline(discX, discY, discSize, discSize, 0xFF353545);
            int textX = discX + discSize + Math.round(10 * scale);

            String line1 = "No music playing";
            String line2 = "Press '.' to search";

            context.pose().pushMatrix();
            context.pose().translate(textX, trayY + Math.round(13 * scale));
            context.pose().scale(scale, scale);
            context.text(this.font, "§7" + line1, 0, 0, 0xFFAAAAAA);
            context.text(this.font, "§8" + line2, 0, 14, 0xFF777777);
            context.pose().popMatrix();
        }

        // Único borde exterior continuo que rodea tanto el HUD como la bandeja de controles sin línea divisoria intermedia
        context.outline(trayX, trayY, trayWidth, totalHeight, MusicHudOverlay.CURRENT_THEME.borderColor);

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
