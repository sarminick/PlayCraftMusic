package com.playcraft.music.client.gui.ui;

import com.playcraft.music.client.PlayCraftConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

public final class ModernUI {

    public static final FontDescription MODERN_FONT =
            new FontDescription.Resource(Identifier.fromNamespaceAndPath("playcraft", "modern"));

    // Modern dark-mode palette (Spotify / YouTube Music aesthetic)
    public static final int BG_OUTER = 0xFA0E1015;
    public static final int BG_SIDEBAR = 0xFA0A0B0F;
    public static final int BG_SURFACE = 0xFF151821;
    public static final int BG_ELEVATED = 0xFF1C202B;
    public static final int BG_HOVER = 0xFF252A38;
    public static final int BG_INPUT = 0xFF11131A;
    public static final int BORDER_SUBTLE = 0xFF272B38;
    public static final int BORDER_HOVER = 0xFF3A4154;

    public static final int TEXT_PRIMARY = 0xFFF4F6FA;
    public static final int TEXT_SECONDARY = 0xFFA2A8B8;
    public static final int TEXT_MUTED = 0xFF6B7285;
    public static final int TEXT_DARK = 0xFF0D0F14;

    public static final int STATUS_ONLINE = 0xFF22C55E;
    public static final int STATUS_ERROR = 0xFFEF4444;
    public static final int STATUS_WARN = 0xFFF59E0B;
    public static final int STATUS_PAUSED = 0xFF38BDF8;
    public static final int DANGER_FILL = 0xFF361920;
    public static final int DANGER_HOVER = 0xFF4C202B;
    public static final int DANGER_BORDER = 0xFF8A2E3E;

    private ModernUI() {}

    public static Style style(int rgbOrArgb, boolean bold) {
        Style st = Style.EMPTY.withColor(rgbOrArgb & 0x00FFFFFF).withBold(bold);
        if (PlayCraftConfig.USE_MODERN_FONT) {
            st = st.withFont(MODERN_FONT);
        }
        return st;
    }

    public static Component text(String value) {
        return Component.literal(value != null ? value : "").withStyle(style(TEXT_PRIMARY, false));
    }

    public static Component text(String value, int rgbOrArgb) {
        return Component.literal(value != null ? value : "").withStyle(style(rgbOrArgb, false));
    }

    public static Component bold(String value, int rgbOrArgb) {
        return Component.literal(value != null ? value : "").withStyle(style(rgbOrArgb, true));
    }

    public static void drawText(GuiGraphicsExtractor context, Font font, String value, int x, int y, int argb, boolean shadow) {
        if (value == null || value.isEmpty()) return;
        context.text(font, text(value, argb), x, y, argb, shadow);
    }

    public static void drawBold(GuiGraphicsExtractor context, Font font, String value, int x, int y, int argb, boolean shadow) {
        if (value == null || value.isEmpty()) return;
        context.text(font, bold(value, argb), x, y, argb, shadow);
    }

    public static void drawCentered(GuiGraphicsExtractor context, Font font, String value, int centerX, int y, int argb) {
        if (value == null || value.isEmpty()) return;
        Component comp = text(value, argb);
        int w = font.width(comp);
        context.text(font, comp, centerX - w / 2, y, argb, false);
    }

    public static void drawCenteredBold(GuiGraphicsExtractor context, Font font, String value, int centerX, int y, int argb) {
        if (value == null || value.isEmpty()) return;
        Component comp = bold(value, argb);
        int w = font.width(comp);
        context.text(font, comp, centerX - w / 2, y, argb, false);
    }

    public static int width(Font font, String value) {
        if (value == null || value.isEmpty()) return 0;
        return font.width(text(value));
    }

    public static int boldWidth(Font font, String value) {
        if (value == null || value.isEmpty()) return 0;
        return font.width(bold(value, TEXT_PRIMARY));
    }

    public static String truncate(Font font, String value, int maxWidth) {
        if (value == null) return "";
        if (maxWidth <= 0) return "";
        Component full = text(value);
        if (font.width(full) <= maxWidth) return value;
        Component ellipsis = text("...");
        int ellipsisW = font.width(ellipsis);
        int budget = Math.max(0, maxWidth - ellipsisW);
        String trimmed = font.substrByWidth(full, budget).getString();
        return trimmed + "...";
    }

    public static String truncateBold(Font font, String value, int maxWidth) {
        if (value == null) return "";
        if (maxWidth <= 0) return "";
        Component full = bold(value, TEXT_PRIMARY);
        if (font.width(full) <= maxWidth) return value;
        int ellipsisW = font.width(bold("...", TEXT_PRIMARY));
        int budget = Math.max(0, maxWidth - ellipsisW);
        String trimmed = font.substrByWidth(full, budget).getString();
        return trimmed + "...";
    }

    public static void drawScrollingBold(GuiGraphicsExtractor context, Font font, String value,
                                         int x, int y, int maxWidth, int argb) {
        drawScrollingText(context, font, value, x, y, maxWidth, argb, true, false);
    }

    public static void drawScrollingText(GuiGraphicsExtractor context, Font font, String value,
                                         int x, int y, int maxWidth, int argb, boolean bold, boolean shadow) {
        if (value == null || value.isEmpty() || maxWidth <= 0) return;
        int fullW = bold ? boldWidth(font, value) : width(font, value);
        if (fullW <= maxWidth) {
            if (bold) drawBold(context, font, value, x, y, argb, shadow);
            else drawText(context, font, value, x, y, argb, shadow);
            return;
        }

        String spacer = "     \u2022     ";
        int spacerW = bold ? boldWidth(font, spacer) : width(font, spacer);
        int loopSpan = Math.max(1, fullW + spacerW);
        long scrollMs = Math.max(500L, Math.round((loopSpan / 24.0) * 1000.0));
        long pauseMs = 1600L;
        long totalCycleMs = pauseMs + scrollMs;
        long elapsed = System.currentTimeMillis() % totalCycleMs;

        int offset = 0;
        if (elapsed > pauseMs) {
            offset = (int) Math.floor(((elapsed - pauseMs) / 1000.0) * 24.0);
            if (offset >= loopSpan) offset = 0;
        }

        context.enableScissor(x, y - 2, x + maxWidth, y + 12);
        try {
            int firstX = x - offset;
            if (bold) {
                drawBold(context, font, value, firstX, y, argb, shadow);
                drawText(context, font, spacer, firstX + fullW, y, withAlpha(argb, 150), false);
                if (firstX + loopSpan < x + maxWidth) {
                    drawBold(context, font, value, firstX + loopSpan, y, argb, shadow);
                }
            } else {
                drawText(context, font, value, firstX, y, argb, shadow);
                drawText(context, font, spacer, firstX + fullW, y, withAlpha(argb, 150), false);
                if (firstX + loopSpan < x + maxWidth) {
                    drawText(context, font, value, firstX + loopSpan, y, argb, shadow);
                }
            }
        } finally {
            context.disableScissor();
        }
    }

    public static void fillRoundedRect(GuiGraphicsExtractor context, int x, int y, int w, int h, int radius, int argb) {
        if (w <= 0 || h <= 0) return;
        int r = Math.min(radius, Math.min(w, h) / 2);
        if (r <= 0) {
            context.fill(x, y, x + w, y + h, argb);
            return;
        }
        for (int row = 0; row < r; row++) {
            int inset = cornerInset(r, row);
            context.fill(x + inset, y + row, x + w - inset, y + row + 1, argb);
        }
        if (h > 2 * r) {
            context.fill(x, y + r, x + w, y + h - r, argb);
        }
        for (int row = 0; row < r; row++) {
            int inset = cornerInset(r, r - 1 - row);
            context.fill(x + inset, y + h - r + row, x + w - inset, y + h - r + row + 1, argb);
        }
    }

    public static void drawRoundedCard(GuiGraphicsExtractor context, int x, int y, int w, int h, int radius,
                                       int fillArgb, int borderArgb) {
        fillRoundedRect(context, x, y, w, h, radius, fillArgb);
        if ((borderArgb >>> 24) > 0) {
            drawRoundedBorder(context, x, y, w, h, radius, borderArgb);
        }
    }

    public static void drawRoundedBorder(GuiGraphicsExtractor context, int x, int y, int w, int h, int radius, int borderArgb) {
        if (w <= 1 || h <= 1) return;
        int r = Math.min(radius, Math.min(w, h) / 2);
        if (r <= 0) {
            context.fill(x, y, x + w, y + 1, borderArgb);
            context.fill(x, y + h - 1, x + w, y + h, borderArgb);
            context.fill(x, y + 1, x + 1, y + h - 1, borderArgb);
            context.fill(x + w - 1, y + 1, x + w, y + h - 1, borderArgb);
            return;
        }
        // Top & bottom arc rows
        for (int row = 0; row < r; row++) {
            int cur = cornerInset(r, row);
            if (row == 0) {
                context.fill(x + cur, y, x + w - cur, y + 1, borderArgb);
                context.fill(x + cur, y + h - 1, x + w - cur, y + h, borderArgb);
            } else {
                int prev = cornerInset(r, row - 1);
                int span = Math.max(1, prev - cur);
                context.fill(x + cur, y + row, x + cur + span, y + row + 1, borderArgb);
                context.fill(x + w - cur - span, y + row, x + w - cur, y + row + 1, borderArgb);
                int by = y + h - 1 - row;
                context.fill(x + cur, by, x + cur + span, by + 1, borderArgb);
                context.fill(x + w - cur - span, by, x + w - cur, by + 1, borderArgb);
            }
        }
        // Vertical sides between top & bottom arcs
        if (h > 2 * r) {
            context.fill(x, y + r, x + 1, y + h - r, borderArgb);
            context.fill(x + w - 1, y + r, x + w, y + h - r, borderArgb);
        }
    }

    private static int cornerInset(int radius, int rowFromEdge) {
        int idx = Math.max(0, Math.min(radius - 1, rowFromEdge));
        return switch (radius) {
            case 1 -> 1;
            case 2 -> (idx == 0) ? 1 : 0;
            case 3 -> switch (idx) {
                case 0 -> 2;
                case 1 -> 1;
                default -> 0;
            };
            case 4 -> switch (idx) {
                case 0 -> 3;
                case 1, 2 -> 1;
                default -> 0;
            };
            case 5 -> switch (idx) {
                case 0 -> 3;
                case 1 -> 2;
                case 2, 3 -> 1;
                default -> 0;
            };
            case 6 -> switch (idx) {
                case 0 -> 4;
                case 1 -> 2;
                case 2, 3 -> 1;
                default -> 0;
            };
            default -> {
                double dy = radius - idx - 0.5;
                double dx = radius - Math.sqrt(Math.max(0.0, radius * radius - dy * dy));
                yield Math.max(0, (int) Math.round(dx));
            }
        };
    }

    public static int withAlpha(int rgbOrArgb, int alphaByte) {
        int a = Math.max(0, Math.min(255, alphaByte));
        return (a << 24) | (rgbOrArgb & 0x00FFFFFF);
    }

    public static int withAlphaFloat(int rgbOrArgb, float alpha) {
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        return (a << 24) | (rgbOrArgb & 0x00FFFFFF);
    }

    public static int brighten(int argb, float amount) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        r = Math.min(255, Math.round(r + (255 - r) * amount));
        g = Math.min(255, Math.round(g + (255 - g) * amount));
        b = Math.min(255, Math.round(b + (255 - b) * amount));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int darken(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        float keep = Math.max(0.0f, 1.0f - factor);
        return (a << 24) | (Math.round(r * keep) << 16) | (Math.round(g * keep) << 8) | Math.round(b * keep);
    }

    public static boolean isBrightColor(int rgbOrArgb) {
        int r = (rgbOrArgb >>> 16) & 0xFF;
        int g = (rgbOrArgb >>> 8) & 0xFF;
        int b = rgbOrArgb & 0xFF;
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance > 0.60;
    }
}
