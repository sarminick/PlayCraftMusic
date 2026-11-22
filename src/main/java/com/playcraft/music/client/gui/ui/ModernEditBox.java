package com.playcraft.music.client.gui.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.playcraft.music.client.gui.MusicHudOverlay;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.FormattedCharSequence;

public class ModernEditBox extends EditBox {

    private int cardX;
    private int cardY;
    private final int cardW;
    private final int cardH;
    private final PlayCraftIcons.Icon leadingIcon;
    private final int textPadLeft;

    public ModernEditBox(Font font, int x, int y, int width, int height,
                         PlayCraftIcons.Icon leadingIcon, String hint) {
        this(font, x, y, width, height, leadingIcon, hint, x + (leadingIcon != null ? 26 : 9));
    }

    private ModernEditBox(Font font, int x, int y, int width, int height,
                          PlayCraftIcons.Icon leadingIcon, String hint, int innerX) {
        super(font, innerX, y + Math.max(2, (height - 9) / 2),
                Math.max(20, (x + width - 9) - innerX), 11, ModernUI.text(hint != null ? hint : ""));
        this.cardX = x;
        this.cardY = y;
        this.cardW = width;
        this.cardH = height;
        this.leadingIcon = leadingIcon;
        this.textPadLeft = leadingIcon != null ? 26 : 9;

        setBordered(false);
        setTextShadow(false);
        setTextColor(ModernUI.TEXT_PRIMARY);
        setTextColorUneditable(ModernUI.TEXT_MUTED);
        addFormatter((value, firstCharIndex) ->
                FormattedCharSequence.forward(value, ModernUI.style(ModernUI.TEXT_PRIMARY, false)));
        if (hint != null && !hint.isEmpty()) {
            setModernHint(hint);
        }
    }

    public void setModernHint(String hint) {
        if (hint == null || hint.isEmpty()) {
            setHint(ModernUI.text("", ModernUI.TEXT_MUTED));
        } else {
            setHint(ModernUI.text(hint, ModernUI.TEXT_MUTED));
        }
    }

    public void setCardPosition(int newCardX, int newCardY) {
        this.cardX = newCardX;
        this.cardY = newCardY;
        this.setX(newCardX + this.textPadLeft);
        this.setY(newCardY + Math.max(2, (this.cardH - 9) / 2));
    }

    private boolean isInsideCard(double mx, double my) {
        return this.visible && mx >= cardX && mx < cardX + cardW && my >= cardY && my < cardY + cardH;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return isInsideCard(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!this.active || !this.visible) return false;
        if (isInsideCard(event.x(), event.y()) && this.isValidClickButton(event.buttonInfo())) {
            double clampedX = Math.max(this.getX(), Math.min(this.getRight() - 1, event.x()));
            double clampedY = Math.max(this.getY(), Math.min(this.getBottom() - 1, event.y()));
            MouseButtonEvent innerEvent = new MouseButtonEvent(clampedX, clampedY, event.buttonInfo());
            return super.mouseClicked(innerEvent, doubleClick);
        }
        return false;
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (!this.visible) return;
        boolean hovered = isInsideCard(mouseX, mouseY);
        int accent = MusicHudOverlay.getActiveAccentColor();
        int borderColor = this.isFocused()
                ? ModernUI.withAlpha(accent, 210)
                : (hovered ? ModernUI.BORDER_HOVER : ModernUI.BORDER_SUBTLE);
        ModernUI.drawRoundedCard(context, cardX, cardY, cardW, cardH, 4, ModernUI.BG_INPUT, borderColor);

        if (this.leadingIcon != null) {
            int iconColor = this.isFocused() ? accent : ModernUI.TEXT_MUTED;
            PlayCraftIcons.draw(context, this.leadingIcon, cardX + 9, cardY + (cardH - 11) / 2, 11, iconColor);
        }

        context.enableScissor(cardX + textPadLeft - 2, cardY + 1, cardX + cardW - 6, cardY + cardH - 1);
        try {
            super.extractWidgetRenderState(context, mouseX, mouseY, delta);
        } finally {
            context.disableScissor();
        }
        if (hovered && this.active) {
            context.requestCursor(CursorTypes.IBEAM);
        }
    }
}
