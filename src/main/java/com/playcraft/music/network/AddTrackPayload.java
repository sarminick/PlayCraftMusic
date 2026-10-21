package com.playcraft.music.network;

import com.playcraft.music.PlayCraftMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Paquete C2S: El cliente solicita al servidor añadir una canción o enlace.
 */
public record AddTrackPayload(String queryOrUrl, String title, String author, String thumbnailUrl, long durationMs) implements CustomPacketPayload {
    public static final Type<AddTrackPayload> ID = new Type<>(Identifier.fromNamespaceAndPath(PlayCraftMod.MOD_ID, "add_track"));

    public AddTrackPayload(String queryOrUrl) {
        this(queryOrUrl, "", "", "", 0L);
    }

    public static final StreamCodec<FriendlyByteBuf, AddTrackPayload> CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeUtf(payload.queryOrUrl());
                buf.writeUtf(payload.title());
                buf.writeUtf(payload.author());
                buf.writeUtf(payload.thumbnailUrl());
                buf.writeLong(payload.durationMs());
            },
            buf -> new AddTrackPayload(
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readLong()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
