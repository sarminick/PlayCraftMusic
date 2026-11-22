package com.playcraft.music.client.gui.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public final class PlayCraftIcons {

    public enum Icon {
        DISC("disc"),
        SEARCH("search"),
        LINK("link"),
        QUEUE("queue"),
        HISTORY("history"),
        PLAYLIST("playlist"),
        SETTINGS("settings"),
        PLAY("play"),
        PAUSE("pause"),
        PREV("prev"),
        NEXT("next"),
        STOP("stop"),
        SHUFFLE("shuffle"),
        LOOP("loop"),
        AUTOPLAY("autoplay"),
        VOLUME("volume"),
        MUTE("mute"),
        STAR("star"),
        STAR_FILLED("star_filled"),
        RECONNECT("reconnect"),
        PLUS("plus"),
        CLOSE("close"),
        TRASH("trash"),
        EDIT("edit"),
        UP("up"),
        DOWN("down"),
        BACK("back"),
        CHECK("check");

        public final Identifier texture;

        Icon(String name) {
            this.texture = Identifier.fromNamespaceAndPath("playcraft", "textures/gui/icons/" + name + ".png");
        }
    }

    private PlayCraftIcons() {}

    public static void draw(GuiGraphicsExtractor context, Icon icon, int x, int y, int size, int argbColor) {
        if (icon == null || size <= 0) return;
        context.blit(RenderPipelines.GUI_TEXTURED, icon.texture, x, y, 0.0f, 0.0f, size, size, size, size, argbColor);
    }
}
