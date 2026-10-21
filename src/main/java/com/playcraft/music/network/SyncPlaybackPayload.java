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
 * Paquete S2C: Sincroniza la pista activa, posición, estado de reproducción, la lista de canciones en cola
 * y un mensaje opcional de actividad para que cada cliente decida si mostrarlo según su configuración.
 */
public record SyncPlaybackPayload(
        boolean hasTrack,
        TrackInfo track,
        long positionMs,
        boolean isPlaying,
        List<TrackInfo> queue,
        String notificationMessage
) implements CustomPacketPayload {
    public static final Type<SyncPlaybackPayload> ID = new Type<>(Identifier.fromNamespaceAndPath(PlayCraftMod.MOD_ID, "sync_playback"));

    public SyncPlaybackPayload(boolean hasTrack, TrackInfo track, long positionMs, boolean isPlaying, List<TrackInfo> queue) {
        this(hasTrack, track, positionMs, isPlaying, queue, "");
    }

    public static final StreamCodec<FriendlyByteBuf, SyncPlaybackPayload> CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeBoolean(payload.hasTrack());
                if (payload.hasTrack() && payload.track() != null) {
                    payload.track().write(buf);
                }
                buf.writeLong(payload.positionMs());
                buf.writeBoolean(payload.isPlaying());

                // Serializar cola de reproducción
                buf.writeInt(payload.queue().size());
                for (TrackInfo item : payload.queue()) {
                    item.write(buf);
                }

                // Mensaje opcional de actividad
                buf.writeUtf(payload.notificationMessage() != null ? payload.notificationMessage() : "");
            },
            buf -> {
                boolean hasTrack = buf.readBoolean();
                TrackInfo track = hasTrack ? TrackInfo.read(buf) : null;
                long positionMs = buf.readLong();
                boolean isPlaying = buf.readBoolean();

                int queueSize = buf.readInt();
                List<TrackInfo> queue = new ArrayList<>(queueSize);
                for (int i = 0; i < queueSize; i++) {
                    queue.add(TrackInfo.read(buf));
                }

                String notificationMessage = buf.isReadable() ? buf.readUtf() : "";

                return new SyncPlaybackPayload(hasTrack, track, positionMs, isPlaying, queue, notificationMessage);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
