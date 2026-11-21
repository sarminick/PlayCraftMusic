package com.playcraft.music.network;

import com.playcraft.music.PlayCraftMod;
import com.playcraft.music.model.TrackInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Paquete C2S: El cliente envía un lote de pistas resueltas (ej. playlist importada o playlist guardada)
 * para evitar resolver URLs de YouTube desde IPs de servidores datacenter.
 */
public record AddBatchTracksPayload(
        List<TrackInfo> tracks,
        String sourceLabel,
        boolean shuffleOnAdd
) implements CustomPacketPayload {
    public static final int MAX_BATCH_SIZE = 50;
    public static final Type<AddBatchTracksPayload> ID = new Type<>(Identifier.fromNamespaceAndPath(PlayCraftMod.MOD_ID, "add_batch_tracks"));

    public static final StreamCodec<FriendlyByteBuf, AddBatchTracksPayload> CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                List<TrackInfo> list = payload.tracks() != null ? payload.tracks() : List.of();
                int count = Math.min(MAX_BATCH_SIZE, list.size());
                buf.writeInt(count);
                for (int i = 0; i < count; i++) {
                    list.get(i).write(buf);
                }
                buf.writeUtf(payload.sourceLabel() != null ? payload.sourceLabel() : "");
                buf.writeBoolean(payload.shuffleOnAdd());
            },
            buf -> {
                int rawCount = buf.readInt();
                int count = Math.max(0, Math.min(MAX_BATCH_SIZE, rawCount));
                List<TrackInfo> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    list.add(TrackInfo.read(buf));
                }
                String label = buf.readUtf();
                boolean shuffle = buf.readBoolean();
                return new AddBatchTracksPayload(list, label, shuffle);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
