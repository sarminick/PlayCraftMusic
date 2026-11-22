package com.playcraft.music.client.gui.ui;

import com.playcraft.music.client.gui.MusicHudOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class ModernButton extends Button {

    public enum Variant {
        PRIMARY,
        SECONDARY,
        GHOST,
        DANGER,
        SIDEBAR,
        SIDEBAR_ACTIVE,
        TOGGLE_ACTIVE,
        ICON_PILL
    }

    private Variant variant;
    private PlayCraftIcons.Icon icon;
    private String label;
    private int radius = 4;
    private int customAccentColor = 0;

    public ModernButton(int x, int y, int width, int height,
                        Variant variant, PlayCraftIcons.Icon icon, String label, OnPress onPress) {
        super(x, y, width, height, Component.literal(label != null ? label : ""), onPress, DEFAULT_NARRATION);
        this.variant = variant != null ? variant : Variant.SECONDARY;
        this.icon = icon;
        this.label = label != null ? label : "";
        if (height <= 18) {
            this.radius = 3;
        }
    }

    public void setVariant(Variant variant) {
        this.variant = variant;
    }

    public void setIcon(PlayCraftIcons.Icon icon) {
        this.icon = icon;
    }

    public void setLabel(String label) {
        this.label = label != null ? label : "";
        setMessage(Component.literal(this.label));
    }

    public void setRadius(int radius) {
        this.radius = radius;
    }

    public void setCustomAccentColor(int customAccentColor) {
        this.customAccentColor = customAccentColor;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (!this.visible) return;

        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        boolean hovered = this.active && this.isHoveredOrFocused();
        int accent = customAccentColor != 0 ? customAccentColor : MusicHudOverlay.getActiveAccentColor();

        int bgColor;
        int borderColor;
        int fgColor;

        switch (variant) {
            case PRIMARY -> {
                if (!this.active) {
                    bgColor = ModernUI.BG_ELEVATED;
                    borderColor = ModernUI.BORDER_SUBTLE;
                    fgColor = ModernUI.TEXT_MUTED;
                } else {
                    bgColor = hovered ? ModernUI.brighten(accent, 0.16f) : accent;
                    borderColor = ModernUI.brighten(accent, 0.25f);
                    fgColor = ModernUI.isBrightColor(accent) ? ModernUI.TEXT_DARK : ModernUI.TEXT_PRIMARY;
                }
            }
            case SIDEBAR -> {
                if (!this.active) {
                    bgColor = 0x00000000;
                    borderColor = 0x00000000;
                    fgColor = ModernUI.TEXT_MUTED;
                } else {
                    bgColor = hovered ? ModernUI.BG_ELEVATED : 0x00000000;
                    borderColor = 0x00000000;
                    fgColor = hovered ? ModernUI.TEXT_PRIMARY : ModernUI.TEXT_SECONDARY;
                }
            }
            case SIDEBAR_ACTIVE -> {
                bgColor = hovered ? ModernUI.withAlpha(accent, 48) : ModernUI.withAlpha(accent, 34);
                borderColor = ModernUI.withAlpha(accent, 110);
                fgColor = ModernUI.TEXT_PRIMARY;
            }
            case TOGGLE_ACTIVE -> {
                bgColor = hovered ? ModernUI.BG_HOVER : ModernUI.withAlpha(accent, 34);
                borderColor = ModernUI.withAlpha(accent, 145);
                fgColor = accent;
            }
            case DANGER -> {
                if (!this.active) {
                    bgColor = ModernUI.BG_ELEVATED;
                    borderColor = ModernUI.BORDER_SUBTLE;
                    fgColor = ModernUI.TEXT_MUTED;
                } else {
                    bgColor = hovered ? ModernUI.DANGER_HOVER : ModernUI.DANGER_FILL;
                    borderColor = ModernUI.DANGER_BORDER;
                    fgColor = 0xFFFCA5A5;
                }
            }
            case GHOST, ICON_PILL -> {
                if (!this.active) {
                    bgColor = 0x00000000;
                    borderColor = 0x00000000;
                    fgColor = ModernUI.TEXT_MUTED;
                } else {
                    bgColor = hovered ? ModernUI.BG_HOVER : ModernUI.withAlpha(ModernUI.BG_ELEVATED, 120);
                    borderColor = hovered ? ModernUI.BORDER_HOVER : 0x00000000;
                    fgColor = hovered ? ModernUI.TEXT_PRIMARY : ModernUI.TEXT_SECONDARY;
                }
            }
            default -> { // SECONDARY
                if (!this.active) {
                    bgColor = ModernUI.BG_SURFACE;
                    borderColor = ModernUI.BORDER_SUBTLE;
                    fgColor = ModernUI.TEXT_MUTED;
                } else {
                    bgColor = hovered ? ModernUI.BG_HOVER : ModernUI.BG_ELEVATED;
                    borderColor = hovered ? ModernUI.BORDER_HOVER : ModernUI.BORDER_SUBTLE;
                    fgColor = hovered ? ModernUI.TEXT_PRIMARY : ModernUI.TEXT_SECONDARY;
                }
            }
        }

        if ((bgColor >>> 24) > 0 || (borderColor >>> 24) > 0) {
            ModernUI.drawRoundedCard(context, x, y, w, h, radius, bgColor, borderColor);
        }

        // Left active indicator pill for sidebar items
        if (variant == Variant.SIDEBAR_ACTIVE) {
            ModernUI.fillRoundedRect(context, x + 2, y + 5, 3, h - 10, 1, accent);
        } else if (variant == Variant.SIDEBAR && hovered) {
            ModernUI.fillRoundedRect(context, x + 2, y + 6, 2, h - 12, 1, ModernUI.withAlpha(accent, 120));
        }

        Font font = Minecraft.getInstance().font;
        boolean hasIcon = this.icon != null;
        boolean hasText = this.label != null && !this.label.isEmpty();

        int iconSize = hasText ? Math.min(11, Math.max(9, h - 8)) : Math.min(12, Math.max(9, Math.min(w, h) - 6));
        int gap = (hasIcon && hasText) ? 5 : 0;

        if (variant == Variant.SIDEBAR || variant == Variant.SIDEBAR_ACTIVE) {
            int padLeft = 8;
            int iconX = x + padLeft;
            int iconTint = (variant == Variant.SIDEBAR_ACTIVE) ? accent : fgColor;
            if (hasIcon) {
                int iconY = y + (h - iconSize) / 2;
                PlayCraftIcons.draw(context, this.icon, iconX, iconY, iconSize, iconTint);
            }
            if (hasText) {
                int textX = hasIcon ? iconX + iconSize + 5 : x + padLeft;
                int maxTextW = (x + w - 5) - textX;
                String drawn = (variant == Variant.SIDEBAR_ACTIVE)
                        ? ModernUI.truncateBold(font, this.label, maxTextW)
                        : ModernUI.truncate(font, this.label, maxTextW);
                int textY = y + (h - 9) / 2;
                if (variant == Variant.SIDEBAR_ACTIVE) {
                    ModernUI.drawBold(context, font, drawn, textX, textY, fgColor, false);
                } else {
                    ModernUI.drawText(context, font, drawn, textX, textY, fgColor, false);
                }
            }
            return;
        }

        if (hasIcon && !hasText) {
            int iconX = x + (w - iconSize) / 2;
            int iconY = y + (h - iconSize) / 2;
            PlayCraftIcons.draw(context, this.icon, iconX, iconY, iconSize, fgColor);
            return;
        }

        if (!hasIcon && hasText) {
            String drawn = (variant == Variant.PRIMARY)
                    ? ModernUI.truncateBold(font, this.label, Math.max(8, w - 10))
                    : ModernUI.truncate(font, this.label, Math.max(8, w - 10));
            int textW = (variant == Variant.PRIMARY) ? ModernUI.boldWidth(font, drawn) : ModernUI.width(font, drawn);
            int textX = x + (w - textW) / 2;
            int textY = y + (h - 9) / 2;
            if (variant == Variant.PRIMARY) {
                ModernUI.drawBold(context, font, drawn, textX, textY, fgColor, false);
            } else {
                ModernUI.drawText(context, font, drawn, textX, textY, fgColor, false);
            }
            return;
        }

        if (hasIcon) {
            int maxTextW = Math.max(8, w - 10 - iconSize - gap);
            String drawn = (variant == Variant.PRIMARY)
                    ? ModernUI.truncateBold(font, this.label, maxTextW)
                    : ModernUI.truncate(font, this.label, maxTextW);
            int textW = (variant == Variant.PRIMARY) ? ModernUI.boldWidth(font, drawn) : ModernUI.width(font, drawn);
            int totalW = iconSize + gap + textW;
            int startX = x + (w - totalW) / 2;
            int iconY = y + (h - iconSize) / 2;
            int textY = y + (h - 9) / 2;
            PlayCraftIcons.draw(context, this.icon, startX, iconY, iconSize, fgColor);
            if (variant == Variant.PRIMARY) {
                ModernUI.drawBold(context, font, drawn, startX + iconSize + gap, textY, fgColor, false);
            } else {
                ModernUI.drawText(context, font, drawn, startX + iconSize + gap, textY, fgColor, false);
            }
        }
    }
}
