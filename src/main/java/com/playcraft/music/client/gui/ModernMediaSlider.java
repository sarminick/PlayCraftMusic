package com.playcraft.music.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Control deslizante moderno, plano y minimalista con estética Spotify/Apple Music:
 * - Pista fina y oscura de fondo con bordes redondeados.
 * - Pista activa rellena con acento brillante (naranja/dorado o cian/blanco).
 * - Indicador circular / píldora moderno (Thumb) que resalta al pasar el ratón.
 * - Texto y grosor de barra escalados proporcionalmente según uiScale.
 */
public class ModernMediaSlider extends AbstractSliderButton {

    public enum Theme {
        ACCENT_ORANGE(0xFFFFAA00, 0xFFFFCC33, 0xFF353542),
        ACCENT_CYAN(0xFF00E5FF, 0xFF80F8FF, 0xFF253238),
        ACCENT_PURPLE(0xFFD500F9, 0xFFEA80FC, 0xFF2A2535),
        ACCENT_EMERALD(0xFF00E676, 0xFFB9F6CA, 0xFF223025),
        ACCENT_RED(0xFFFF1744, 0xFFFF8A80, 0xFF332020),
        ACCENT_PINK(0xFFFF4081, 0xFFFF80AB, 0xFF33222A);

        final int activeColor;
        final int thumbColor;
        final int trackBgColor;

        Theme(int activeColor, int thumbColor, int trackBgColor) {
            this.activeColor = activeColor;
            this.thumbColor = thumbColor;
            this.trackBgColor = trackBgColor;
        }

        /**
         * Tema principal para la barra de progreso de la canción (coincide con el tema del HUD).
         */
        public static Theme fromHudTheme(MusicHudOverlay.ColorTheme hudTheme) {
            return switch (hudTheme) {
                case CYAN -> ACCENT_CYAN;
                case PURPLE -> ACCENT_PURPLE;
                case EMERALD -> ACCENT_EMERALD;
                case RED -> ACCENT_RED;
                default -> ACCENT_ORANGE;
            };
        }

        /**
         * Tema secundario de alto contraste para la barra de volumen,
         * garantizando que siempre se distinga visualmente de la barra de la canción.
         */
        public static Theme volumeFromHudTheme(MusicHudOverlay.ColorTheme hudTheme) {
            return switch (hudTheme) {
                case GOLD -> ACCENT_CYAN;       // Canción Dorada -> Volumen Cian Neón
                case CYAN -> ACCENT_ORANGE;     // Canción Cian -> Volumen Dorado/Naranja
                case PURPLE -> ACCENT_CYAN;     // Canción Púrpura -> Volumen Cian Neón
                case EMERALD -> ACCENT_ORANGE;  // Canción Esmeralda -> Volumen Dorado/Naranja
                case RED -> ACCENT_CYAN;        // Canción Roja -> Volumen Cian Neón
            };
        }
    }

    private final Theme theme;
    private final Consumer<Double> onValueApplied;
    private final ValueMessageProvider messageProvider;
    private final boolean applyOnRelease;
    private float uiScale = 1.0f;

    @FunctionalInterface
    public interface ValueMessageProvider {
        Component getMessage(double value);
    }

    public ModernMediaSlider(int x, int y, int width, int height, double initialValue, Theme theme,
                             ValueMessageProvider messageProvider, Consumer<Double> onValueApplied) {
        this(x, y, width, height, initialValue, theme, messageProvider, onValueApplied, false, 1.0f);
    }

    public ModernMediaSlider(int x, int y, int width, int height, double initialValue, Theme theme,
                             ValueMessageProvider messageProvider, Consumer<Double> onValueApplied, boolean applyOnRelease) {
        this(x, y, width, height, initialValue, theme, messageProvider, onValueApplied, applyOnRelease, 1.0f);
    }

    public ModernMediaSlider(int x, int y, int width, int height, double initialValue, Theme theme,
                             ValueMessageProvider messageProvider, Consumer<Double> onValueApplied, boolean applyOnRelease, float uiScale) {
        super(x, y, width, height, messageProvider.getMessage(initialValue), initialValue);
        this.theme = theme;
        this.messageProvider = messageProvider;
        this.onValueApplied = onValueApplied;
        this.applyOnRelease = applyOnRelease;
        this.uiScale = uiScale;
    }

    @Override
    protected void updateMessage() {
        if (messageProvider != null) {
            this.setMessage(messageProvider.getMessage(this.value));
        }
    }

    @Override
    protected void applyValue() {
        if (!applyOnRelease && onValueApplied != null) {
            onValueApplied.accept(this.value);
        }
    }

    private boolean isUserDragging = false;

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        isUserDragging = true;
        super.onClick(event, doubleClick);
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double deltaX, double deltaY) {
        isUserDragging = true;
        super.onDrag(event, deltaX, deltaY);
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        boolean wasDragging = isUserDragging;
        isUserDragging = false;
        super.onRelease(event);
        if (applyOnRelease && wasDragging && onValueApplied != null) {
            updateMessage();
            onValueApplied.accept(this.value);
        }
    }

    public void setProgressDirect(double progress) {
        if (!isUserDragging) {
            this.value = Math.max(0.0, Math.min(1.0, progress));
            updateMessage();
        }
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();

        boolean hovered = this.isHovered();

        // 1. Texto informativo encima de la barra, escalado proporcionalmente según uiScale
        Minecraft mc = Minecraft.getInstance();
        Component msg = this.getMessage();
        int textW = mc.font.width(msg);
        float textScale = Math.max(0.65f, Math.min(1.35f, uiScale));
        float centerX = x + (w / 2.0f);
        float textY = y + Math.max(1.0f, 1.5f * uiScale);

        context.pose().pushMatrix();
        context.pose().translate(centerX, textY);
        context.pose().scale(textScale, textScale);
        context.text(mc.font, msg, (int) (-textW / 2.0f), 0, hovered ? 0xFFFFFFFF : 0xFFCCCCCC);
        context.pose().popMatrix();

        // 2. Pista de fondo moderna con grosor proporcional a la escala
        int trackHeight = Math.max(2, Math.round(4 * uiScale));
        int bottomOffset = Math.max(3, Math.round(5 * uiScale));
        int trackY = y + h - bottomOffset;
        context.fill(x, trackY, x + w, trackY + trackHeight, theme.trackBgColor);

        // 3. Pista de progreso activa rellena
        int filledWidth = (int) (this.value * w);
        if (filledWidth > 0) {
            context.fill(x, trackY, x + filledWidth, trackY + trackHeight, theme.activeColor);
        }

        // 4. Indicador de arrastre (Thumb) con tamaño proporcional a la escala
        int baseRadius = Math.max(2, Math.round(3.8f * uiScale));
        int thumbRadius = hovered ? baseRadius + 1 : baseRadius;
        int thumbX = x + filledWidth;
        int thumbY = trackY + (trackHeight / 2);

        int thumbColor = hovered ? 0xFFFFFFFF : theme.thumbColor;
        context.fill(thumbX - thumbRadius, thumbY - thumbRadius, thumbX + thumbRadius, thumbY + thumbRadius, thumbColor);
        context.outline(thumbX - thumbRadius, thumbY - thumbRadius, thumbRadius * 2, thumbRadius * 2, 0x99000000);
    }
}
