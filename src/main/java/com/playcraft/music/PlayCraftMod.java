package com.playcraft.music;

import com.playcraft.music.model.TrackInfo;
import com.playcraft.music.network.AddTrackPayload;
import com.playcraft.music.network.ControlTrackPayload;
import com.playcraft.music.network.ModifyQueuePayload;
import com.playcraft.music.network.SyncPlaybackPayload;
import com.playcraft.music.server.ServerMusicManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlayCraftMod implements ModInitializer {
    public static final String MOD_ID = "playcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[PlayCraft] Registrando paquetes de red y eventos...");

        PayloadTypeRegistry.serverboundPlay().register(AddTrackPayload.ID, AddTrackPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ControlTrackPayload.ID, ControlTrackPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModifyQueuePayload.ID, ModifyQueuePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SyncPlaybackPayload.ID, SyncPlaybackPayload.CODEC);

        // Ciclo de vida del servidor
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerMusicManager.getInstance().setServer(server);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ServerMusicManager.getInstance().setServer(null);
        });

        // Tick del servidor para avanzar y sincronizar canciones
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ServerMusicManager.getInstance().tick();
        });

        // Sincronizar estado al entrar un nuevo jugador al servidor
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerMusicManager.getInstance().syncToPlayer(handler.getPlayer());
        });

        // Receptor C2S: Petición de añadir canción / link
        ServerPlayNetworking.registerGlobalReceiver(AddTrackPayload.ID, (payload, context) -> {
            String query = payload.queryOrUrl();
            String playerName = context.player().getName().getString();
            var player = context.player();
            var server = context.server();

            if (payload.title() != null && !payload.title().isEmpty() && payload.thumbnailUrl() != null && !payload.thumbnailUrl().isEmpty()) {
                TrackInfo directTrack = new TrackInfo(
                        String.valueOf(System.currentTimeMillis()),
                        payload.title(),
                        payload.author(),
                        query,
                        payload.thumbnailUrl(),
                        payload.durationMs()
                );
                server.execute(() -> {
                    ServerMusicManager.getInstance().enqueue(directTrack, player);
                });
            } else if (com.playcraft.music.util.YouTubeMetadataResolver.isPlaylistUrl(query)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§6[PlayCraft] §7Cargando playlist de YouTube..."));
                com.playcraft.music.util.YouTubeMetadataResolver.resolvePlaylist(query, playerName, 15).thenAccept(tracks -> {
                    server.execute(() -> {
                        if (tracks.isEmpty()) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[PlayCraft] No se pudieron cargar pistas de la playlist."));
                        } else {
                            for (TrackInfo t : tracks) {
                                ServerMusicManager.getInstance().enqueue(t, player);
                            }
                        }
                    });
                });
            } else {
                com.playcraft.music.util.YouTubeMetadataResolver.resolve(query, playerName).thenAccept(track -> {
                    server.execute(() -> {
                        ServerMusicManager.getInstance().enqueue(track, player);
                    });
                });
            }
        });

        // Receptor C2S: Control de reproducción
        ServerPlayNetworking.registerGlobalReceiver(ControlTrackPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerMusicManager.getInstance().handleControl(payload, context.player());
            });
        });

        // Receptor C2S: Modificar cola (remover / reordenar)
        ServerPlayNetworking.registerGlobalReceiver(ModifyQueuePayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                if (payload.action() == ModifyQueuePayload.Action.REMOVE) {
                    ServerMusicManager.getInstance().removeFromQueue(payload.fromIndex(), context.player());
                } else if (payload.action() == ModifyQueuePayload.Action.MOVE) {
                    ServerMusicManager.getInstance().moveInQueue(payload.fromIndex(), payload.toIndex());
                } else if (payload.action() == ModifyQueuePayload.Action.CLEAR) {
                    ServerMusicManager.getInstance().clearQueue(context.player());
                }
            });
        });

        LOGGER.info("[PlayCraft] Mod inicializado en el servidor.");
    }
}
