package com.playcraft.music.client.gui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.playcraft.music.client.gui.ui.ModernUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public class ModernMediaSlider extends AbstractSliderButton {

    public enum Theme {
        GOLD(false),
        CYAN(false),
        PURPLE(false),
        EMERALD(false),
        RED(false),
        VOLUME(true);

        public final boolean isVolume;

        Theme(boolean isVolume) {
            this.isVolume = isVolume;
        }

        public int resolveFillColor() {
            return isVolume ? MusicHudOverlay.getActiveVolumeColor() : MusicHudOverlay.getActiveAccentColor();
        }

        public static Theme fromHudTheme(MusicHudOverlay.ColorTheme hudTheme) {
            if (hudTheme == null) return GOLD;
            return switch (hudTheme) {
                case CYAN -> CYAN;
                case PURPLE -> PURPLE;
                case EMERALD -> EMERALD;
                case RED -> RED;
                default -> GOLD;
            };
        }

        public static Theme volumeFromHudTheme(MusicHudOverlay.ColorTheme hudTheme) {
            return VOLUME;
        }
    }

    @FunctionalInterface
    public interface ValueMessageProvider {
        Component getMessage(double progress);
    }

    private Theme theme;
    private final ValueMessageProvider messageProvider;
    private final Consumer<Double> onChangeCallback;
    private final boolean fireOnReleaseOnly;
    private boolean isDragging = false;
    private float uiScale = 1.0f;

    public ModernMediaSlider(int x, int y, int width, int height, double initialValue, Theme theme,
                             ValueMessageProvider messageProvider, Consumer<Double> onChangeCallback) {
        this(x, y, width, height, initialValue, theme, messageProvider, onChangeCallback, false);
    }

    public ModernMediaSlider(int x, int y, int width, int height, double initialValue, Theme theme,
                             ValueMessageProvider messageProvider, Consumer<Double> onChangeCallback, boolean fireOnReleaseOnly) {
        super(x, y, width, height, Component.empty(), Math.max(0.0, Math.min(1.0, initialValue)));
        this.theme = theme != null ? theme : Theme.GOLD;
        this.messageProvider = messageProvider;
        this.onChangeCallback = onChangeCallback;
        this.fireOnReleaseOnly = fireOnReleaseOnly;
        updateMessage();
    }

    public ModernMediaSlider(int x, int y, int width, int height, double initialValue,
                             ValueMessageProvider messageProvider, Consumer<Double> onChangeCallback) {
        this(x, y, width, height, initialValue, Theme.fromHudTheme(MusicHudOverlay.CURRENT_THEME),
                messageProvider, onChangeCallback, false);
    }

    public void setTheme(Theme theme) {
        this.theme = theme != null ? theme : Theme.GOLD;
    }

    public void setUiScale(float uiScale) {
        this.uiScale = Math.max(0.5f, Math.min(2.0f, uiScale));
    }

    public boolean isDragging() {
        return isDragging;
    }

    @Override
    protected void updateMessage() {
        if (this.messageProvider != null) {
            this.setMessage(this.messageProvider.getMessage(this.value));
        }
    }

    @Override
    protected void applyValue() {
        if (!fireOnReleaseOnly && this.onChangeCallback != null) {
            this.onChangeCallback.accept(this.value);
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        this.isDragging = true;
        super.onClick(event, doubleClick);
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        if (this.isDragging) {
            this.isDragging = false;
            if (fireOnReleaseOnly && this.onChangeCallback != null) {
                this.onChangeCallback.accept(this.value);
            }
        }
    }

    public void setProgressDirect(double progress) {
        if (!this.isDragging) {
            this.value = Math.max(0.0, Math.min(1.0, progress));
            updateMessage();
        }
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (!this.visible) return;

        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        boolean hovered = this.active && (this.isDragging || (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h));

        int accent = this.theme.resolveFillColor();
        int bgColor = hovered ? 0xFF1B202C : ModernUI.BG_INPUT;
        int borderColor = hovered ? ModernUI.withAlpha(accent, 210) : ModernUI.BORDER_SUBTLE;
        ModernUI.drawRoundedCard(context, x, y, w, h, 4, bgColor, borderColor);

        int padX = Math.max(4, Math.round(8 * uiScale));
        int trackW = Math.max(10, w - padX * 2);
        int trackX = x + padX;
        int trackH = Math.max(2, Math.round(3 * uiScale));
        int bottomMargin = Math.max(4, Math.round(7 * uiScale));
        int trackY = y + h - bottomMargin;
        int knobS = Math.max(3, Math.round(5 * uiScale));

        int trackEmptyColor = hovered ? 0xFF3E475C : 0xFF2B3142;
        ModernUI.fillRoundedRect(context, trackX, trackY, trackW, trackH, 1, trackEmptyColor);
        int fillW = (int) Math.round(trackW * Math.max(0.0, Math.min(1.0, this.value)));
        if (fillW > 0) {
            ModernUI.fillRoundedRect(context, trackX, trackY, fillW, trackH, 1, accent);
        }

        if (hovered) {
            int knobX = Math.max(trackX, Math.min(trackX + trackW - knobS, trackX + fillW - (knobS / 2)));
            int knobY = trackY + (trackH - knobS) / 2;
            ModernUI.fillRoundedRect(context, knobX, knobY, knobS, knobS, 1, 0xFFFFFFFF);
            context.requestCursor(this.isDragging ? CursorTypes.RESIZE_EW : CursorTypes.POINTING_HAND);
        }

        String msgText = this.getMessage().getString();
        if (msgText != null && !msgText.isEmpty()) {
            msgText = msgText.replaceAll("§.", "");
            Font font = Minecraft.getInstance().font;
            int maxUnscaledW = Math.max(10, Math.round((w - padX * 2) / uiScale));
            String trimmed = ModernUI.truncate(font, msgText, maxUnscaledW);
            int availTopH = Math.max(8, trackY - y - 1);
            int scaledTextH = Math.round(9 * uiScale);
            float centerX = x + (w / 2.0f);
            float drawTopY = y + Math.max(1.0f, (availTopH - scaledTextH) / 2.0f);
            int textColor = hovered ? ModernUI.TEXT_PRIMARY : ModernUI.TEXT_SECONDARY;

            if (Math.abs(uiScale - 1.0f) < 0.01f) {
                ModernUI.drawCentered(context, font, trimmed, x + w / 2, Math.round(drawTopY), textColor);
            } else {
                context.pose().pushMatrix();
                context.pose().translate(centerX, drawTopY);
                context.pose().scale(uiScale, uiScale);
                ModernUI.drawCentered(context, font, trimmed, 0, 0, textColor);
                context.pose().popMatrix();
            }
        }
    }
}
