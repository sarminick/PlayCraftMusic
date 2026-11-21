package com.playcraft.music.network;

import com.playcraft.music.PlayCraftMod;
import com.playcraft.music.model.TrackInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Paquete C2S: El cliente responde al servidor con la canción sugerida para Autoplay.
 */
public record SubmitAutoplayPayload(
        String forTrackId,
        boolean found,
        TrackInfo track
) implements CustomPacketPayload {
    public static final Type<SubmitAutoplayPayload> ID = new Type<>(Identifier.fromNamespaceAndPath(PlayCraftMod.MOD_ID, "submit_autoplay"));

    public static final StreamCodec<FriendlyByteBuf, SubmitAutoplayPayload> CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeUtf(payload.forTrackId() != null ? payload.forTrackId() : "");
                boolean has = payload.found() && payload.track() != null;
                buf.writeBoolean(has);
                if (has) {
                    payload.track().write(buf);
                }
            },
            buf -> {
                String trackId = buf.readUtf();
                boolean has = buf.readBoolean();
                TrackInfo track = has ? TrackInfo.read(buf) : null;
                return new SubmitAutoplayPayload(trackId, has, track);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
