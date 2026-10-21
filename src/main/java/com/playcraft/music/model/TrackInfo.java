package com.playcraft.music.model;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Representa una pista musical con sus metadatos principales.
 */
public record TrackInfo(
        String id,
        String title,
        String author,
        String streamUrl,
        String thumbnailUrl,
        long durationMs
) {
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(id);
        buf.writeUtf(title);
        buf.writeUtf(author);
        buf.writeUtf(streamUrl);
        buf.writeUtf(thumbnailUrl);
        buf.writeLong(durationMs);
    }

    public static TrackInfo read(FriendlyByteBuf buf) {
        return new TrackInfo(
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readLong()
        );
    }
}
