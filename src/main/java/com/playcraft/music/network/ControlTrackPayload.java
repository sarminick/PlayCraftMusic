package com.playcraft.music.network;

import com.playcraft.music.PlayCraftMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Paquete C2S: El cliente envía un comando de control de reproducción (pause, resume, skip).
 */
public record ControlTrackPayload(Action action, long seekPositionMs) implements CustomPacketPayload {
    public static final Type<ControlTrackPayload> ID = new Type<>(Identifier.fromNamespaceAndPath(PlayCraftMod.MOD_ID, "control_track"));

    public ControlTrackPayload(Action action) {
        this(action, 0L);
    }

    public enum Action {
        PAUSE,
        RESUME,
        TOGGLE_PAUSE,
        SKIP,
        REWIND,
        STOP,
        SEEK,
        STREAM_READY
    }

    public static final StreamCodec<FriendlyByteBuf, ControlTrackPayload> CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeEnum(payload.action());
                buf.writeLong(payload.seekPositionMs());
            },
            buf -> new ControlTrackPayload(
                    buf.readEnum(Action.class),
                    buf.readLong()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
