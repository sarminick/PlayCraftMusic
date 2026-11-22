package com.playcraft.music.client.gui.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.playcraft.music.client.gui.MusicHudOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

public class ModernSettingSlider extends AbstractSliderButton {

    @FunctionalInterface
    public interface ValueFormatter {
        String format(double value);
    }

    @FunctionalInterface
    public interface ValueChangedListener {
        void onChanged(double value);
    }

    private final String label;
    private final ValueFormatter formatter;
    private final ValueChangedListener listener;

    public ModernSettingSlider(int x, int y, int width, int height,
                               String label, double initialValue,
                               ValueFormatter formatter, ValueChangedListener listener) {
        super(x, y, width, height, Component.empty(), Math.max(0.0, Math.min(1.0, initialValue)));
        this.label = label != null ? label : "";
        this.formatter = formatter != null ? formatter : v -> Math.round(v * 100) + "%";
        this.listener = listener;
        updateMessage();
    }

    public void setNormalizedValue(double val) {
        this.value = Math.max(0.0, Math.min(1.0, val));
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(ModernUI.text(label + ": " + formatter.format(this.value)));
    }

    @Override
    protected void applyValue() {
        if (listener != null) {
            listener.onChanged(this.value);
        }
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (!this.visible) return;
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int accent = MusicHudOverlay.getActiveAccentColor();

        int bg = hovered ? ModernUI.BG_HOVER : ModernUI.BG_ELEVATED;
        int border = hovered ? ModernUI.BORDER_HOVER : ModernUI.BORDER_SUBTLE;
        ModernUI.drawRoundedCard(context, x, y, w, h, 4, bg, border);

        Font font = Minecraft.getInstance().font;
        String valueText = formatter.format(this.value);
        int padX = (w < 110) ? 6 : 9;
        int textY = y + 3;
        ModernUI.drawText(context, font, this.label, x + padX, textY, ModernUI.TEXT_PRIMARY, false);
        int valW = ModernUI.boldWidth(font, valueText);
        ModernUI.drawBold(context, font, valueText, x + w - padX - valW, textY, accent, false);

        int trackX = x + padX;
        int trackW = Math.max(20, w - padX * 2);
        int trackY = y + h - 7;
        int trackH = 3;
        int trackEmptyColor = hovered ? 0xFF3E475C : 0xFF2D3344;
        ModernUI.fillRoundedRect(context, trackX, trackY, trackW, trackH, 1, trackEmptyColor);

        int fillW = (int) Math.round(trackW * Math.max(0.0, Math.min(1.0, this.value)));
        if (fillW > 0) {
            ModernUI.fillRoundedRect(context, trackX, trackY, fillW, trackH, 1, accent);
        }

        int knobX = Math.max(trackX, Math.min(trackX + trackW - 5, trackX + fillW - 2));
        ModernUI.fillRoundedRect(context, knobX, trackY - 1, 5, 5, 1, hovered ? 0xFFFFFFFF : accent);
        if (hovered) {
            context.requestCursor(this.active ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
        }
    }
}
