package com.playcraft.music.network;

import com.playcraft.music.PlayCraftMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Paquete C2S: Modificar elementos en la cola (remover o reordenar).
 */
public record ModifyQueuePayload(Action action, int fromIndex, int toIndex) implements CustomPacketPayload {
    public static final Type<ModifyQueuePayload> ID = new Type<>(Identifier.fromNamespaceAndPath(PlayCraftMod.MOD_ID, "modify_queue"));

    public enum Action {
        REMOVE,
        MOVE,
        CLEAR
    }

    public static final StreamCodec<FriendlyByteBuf, ModifyQueuePayload> CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeEnum(payload.action());
                buf.writeInt(payload.fromIndex());
                buf.writeInt(payload.toIndex());
            },
            buf -> new ModifyQueuePayload(
                    buf.readEnum(Action.class),
                    buf.readInt(),
                    buf.readInt()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
