package com.playcraft.music.network;

import com.playcraft.music.PlayCraftMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Paquete S2C: El servidor solicita a un cliente conectado resolver la siguiente canción sugerida
 * para Autoplay usando la IP residencial del cliente en vez del servidor.
 */
public record RequestAutoplayPayload(
        String forTrackId,
        String videoUrl,
        String title,
        String author,
        List<String> recentVideoIds
) implements CustomPacketPayload {
    public static final Type<RequestAutoplayPayload> ID = new Type<>(Identifier.fromNamespaceAndPath(PlayCraftMod.MOD_ID, "request_autoplay"));

    public static final StreamCodec<FriendlyByteBuf, RequestAutoplayPayload> CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeUtf(payload.forTrackId() != null ? payload.forTrackId() : "");
                buf.writeUtf(payload.videoUrl() != null ? payload.videoUrl() : "");
                buf.writeUtf(payload.title() != null ? payload.title() : "");
                buf.writeUtf(payload.author() != null ? payload.author() : "");
                List<String> ids = payload.recentVideoIds() != null ? payload.recentVideoIds() : List.of();
                int count = Math.min(30, ids.size());
                buf.writeInt(count);
                for (int i = 0; i < count; i++) {
                    buf.writeUtf(ids.get(i));
                }
            },
            buf -> {
                String trackId = buf.readUtf();
                String videoUrl = buf.readUtf();
                String title = buf.readUtf();
                String author = buf.readUtf();
                int count = Math.max(0, Math.min(30, buf.readInt()));
                List<String> ids = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    ids.add(buf.readUtf());
                }
                return new RequestAutoplayPayload(trackId, videoUrl, title, author, ids);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
