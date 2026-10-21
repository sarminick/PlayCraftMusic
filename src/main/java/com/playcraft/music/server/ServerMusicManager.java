package com.playcraft.music.server;

import com.playcraft.music.model.TrackInfo;
import com.playcraft.music.network.ControlTrackPayload;
import com.playcraft.music.network.SyncPlaybackPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Administrador central de la cola de reproducción en el servidor.
 */
public class ServerMusicManager {
    private static final ServerMusicManager INSTANCE = new ServerMusicManager();

    private final Deque<TrackInfo> queue = new ArrayDeque<>();
    private final Deque<TrackInfo> history = new ArrayDeque<>();
    private TrackInfo currentTrack = null;
    private long playbackStartTimeMs = 0;
    private long pausedPositionMs = 0;
    private boolean isPlaying = false;
    private boolean waitingForFirstAudio = false;
    private long waitingStartTimestampMs = 0;
    private MinecraftServer server = null;
    private long lastSeekMessageTimeMs = 0;

    private ServerMusicManager() {}

    public static ServerMusicManager getInstance() {
        return INSTANCE;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public synchronized void enqueue(TrackInfo track, ServerPlayer requester) {
        String notif = "";
        if (currentTrack == null) {
            playTrack(track);
            if (requester != null) {
                notif = "§6[PlayCraftMusic] §e" + requester.getName().getString() + " §7reproduciendo: §b" + track.title();
            }
        } else {
            queue.offerLast(track);
            if (requester != null) {
                notif = "§6[PlayCraftMusic] §e" + requester.getName().getString() + " §7agregó a la cola: §b" + track.title();
            }
        }
        broadcastSync(notif);
    }

    public synchronized void removeFromQueue(int index, ServerPlayer player) {
        if (index >= 0 && index < queue.size()) {
            java.util.List<TrackInfo> list = new java.util.ArrayList<>(queue);
            TrackInfo removed = list.remove(index);
            queue.clear();
            queue.addAll(list);
            String notif = "";
            if (player != null && removed != null) {
                notif = "§6[PlayCraftMusic] §e" + player.getName().getString() + " §7removió de la cola: §b" + removed.title();
            }
            broadcastSync(notif);
        }
    }

    public synchronized void clearQueue(ServerPlayer player) {
        if (!canModerate(player)) {
            if (player != null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[PlayCraft] No tienes permisos de moderación para vaciar la cola."));
            }
            return;
        }
        if (!queue.isEmpty()) {
            queue.clear();
            String notif = "";
            if (player != null) {
                notif = "§6[PlayCraftMusic] §e" + player.getName().getString() + " §7limpió toda la lista de reproducción.";
            }
            broadcastSync(notif);
        }
    }

    public synchronized void moveInQueue(int fromIndex, int toIndex) {
        if (fromIndex >= 0 && fromIndex < queue.size() && toIndex >= 0 && toIndex < queue.size() && fromIndex != toIndex) {
            java.util.List<TrackInfo> list = new java.util.ArrayList<>(queue);
            TrackInfo item = list.remove(fromIndex);
            list.add(toIndex, item);
            queue.clear();
            queue.addAll(list);
            broadcastSync("");
        }
    }

    private void playTrack(TrackInfo track) {
        // Generamos un ID único de sesión para esta reproducción para que el cliente detecte cambios o reinicios de pista
        this.currentTrack = new TrackInfo(
                UUID.randomUUID().toString(),
                track.title(),
                track.author(),
                track.streamUrl(),
                track.thumbnailUrl(),
                track.durationMs()
        );
        this.playbackStartTimeMs = System.currentTimeMillis();
        this.pausedPositionMs = 0;
        this.isPlaying = true;
        this.waitingForFirstAudio = true;
        this.waitingStartTimestampMs = System.currentTimeMillis();
    }

    public synchronized void handleControl(ControlTrackPayload payload, ServerPlayer player) {
        ControlTrackPayload.Action action = payload.action();
        if (currentTrack == null && (action != ControlTrackPayload.Action.REWIND || history.isEmpty())) return;

        String notif = "";
        switch (action) {
            case TOGGLE_PAUSE -> {
                waitingForFirstAudio = false;
                if (isPlaying) {
                    pausedPositionMs = getCurrentPositionMs();
                    isPlaying = false;
                    notif = "§6[PlayCraftMusic] §e" + player.getName().getString() + " §7pausó la música.";
                } else {
                    playbackStartTimeMs = System.currentTimeMillis() - pausedPositionMs;
                    isPlaying = true;
                    notif = "§6[PlayCraftMusic] §e" + player.getName().getString() + " §7reanudó la música.";
                }
            }
            case PAUSE -> {
                waitingForFirstAudio = false;
                if (isPlaying) {
                    pausedPositionMs = getCurrentPositionMs();
                    isPlaying = false;
                    notif = "§6[PlayCraftMusic] §e" + player.getName().getString() + " §7pausó la música.";
                }
            }
            case RESUME -> {
                waitingForFirstAudio = false;
                if (!isPlaying) {
                    playbackStartTimeMs = System.currentTimeMillis() - pausedPositionMs;
                    isPlaying = true;
                    notif = "§6[PlayCraftMusic] §e" + player.getName().getString() + " §7reanudó la música.";
                }
            }
            case SKIP -> {
                String skippedMsg = "§6[PlayCraftMusic] §e" + player.getName().getString() + " §7saltó la pista actual.";
                String nextMsg = skipNext();
                notif = !nextMsg.isEmpty() ? nextMsg : skippedMsg;
            }
            case REWIND -> {
                if (!history.isEmpty()) {
                    if (currentTrack != null) {
                        queue.offerFirst(currentTrack);
                    }
                    TrackInfo previous = history.pop();
                    playTrack(previous);
                    notif = "§6[PlayCraftMusic] §e" + player.getName().getString() + " §7volvió a: §b" + previous.title();
                } else if (currentTrack != null) {
                    playTrack(currentTrack);
                    notif = "§6[PlayCraftMusic] §e" + player.getName().getString() + " §7reinició la canción actual.";
                }
            }
            case SEEK -> {
                if (currentTrack != null) {
                    waitingForFirstAudio = false;
                    long targetMs = Math.max(0, Math.min(currentTrack.durationMs(), payload.seekPositionMs()));
                    this.currentTrack = new TrackInfo(
                            UUID.randomUUID().toString(),
                            currentTrack.title(),
                            currentTrack.author(),
                            currentTrack.streamUrl(),
                            currentTrack.thumbnailUrl(),
                            currentTrack.durationMs()
                    );
                    this.playbackStartTimeMs = System.currentTimeMillis() - targetMs;
                    this.pausedPositionMs = targetMs;
                    long now = System.currentTimeMillis();
                    if (now - lastSeekMessageTimeMs > 800) {
                        lastSeekMessageTimeMs = now;
                        long sec = targetMs / 1000;
                        notif = "§6[PlayCraftMusic] §e" + player.getName().getString() + " §7saltó a §b" + String.format("%02d:%02d", sec / 60, sec % 60);
                    }
                }
            }
            case STREAM_READY -> {
                if (currentTrack != null && waitingForFirstAudio) {
                    waitingForFirstAudio = false;
                    playbackStartTimeMs = System.currentTimeMillis() - pausedPositionMs;
                    broadcastSync("");
                }
                return;
            }
            case STOP -> {
                if (!canModerate(player)) {
                    if (player != null) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[PlayCraft] No tienes permisos de moderación para detener la música."));
                    }
                    return;
                }
                currentTrack = null;
                isPlaying = false;
                waitingForFirstAudio = false;
                pausedPositionMs = 0;
                queue.clear();
                history.clear();
                notif = "§6[PlayCraftMusic] §e" + player.getName().getString() + " §7detuvo la reproducción.";
            }
        }
        broadcastSync(notif);
    }

    private int tickCounter = 0;

    public synchronized void tick() {
        if (isPlaying && currentTrack != null) {
            // Si aún estamos esperando que el cliente termine de cargar yt-dlp/ffmpeg para que empiece a sonar
            // (con un límite de seguridad de 15 segundos), mantenemos el reloj anclado al inicio (00:00).
            if (waitingForFirstAudio) {
                if (System.currentTimeMillis() - waitingStartTimestampMs < 15000L) {
                    playbackStartTimeMs = System.currentTimeMillis() - pausedPositionMs;
                } else {
                    waitingForFirstAudio = false;
                }
            }

            long pos = getCurrentPositionMs();
            if (!waitingForFirstAudio && currentTrack.durationMs() > 0 && pos >= currentTrack.durationMs()) {
                String notif = skipNext();
                broadcastSync(notif);
                return;
            }

            // Cada segundo (20 ticks), enviar un heartbeat de sincronización silencioso
            // para que jugadores que se reconectaron o cuyo stream tardó en resolver se sincronicen
            tickCounter++;
            if (tickCounter % 20 == 0) {
                broadcastSync("");
            }
        } else {
            tickCounter = 0;
        }
    }

    private String skipNext() {
        if (currentTrack != null) {
            history.push(currentTrack);
        }
        if (!queue.isEmpty()) {
            playTrack(queue.pollFirst());
            return "§6[PlayCraftMusic] §7Siguiente pista: §b" + currentTrack.title();
        } else {
            currentTrack = null;
            isPlaying = false;
            pausedPositionMs = 0;
            return "";
        }
    }

    public long getCurrentPositionMs() {
        if (currentTrack == null) return 0;
        if (!isPlaying || waitingForFirstAudio) return pausedPositionMs;
        return System.currentTimeMillis() - playbackStartTimeMs;
    }

    public void syncToPlayer(ServerPlayer player) {
        SyncPlaybackPayload payload = new SyncPlaybackPayload(
                currentTrack != null,
                currentTrack,
                getCurrentPositionMs(),
                isPlaying,
                new java.util.ArrayList<>(queue),
                ""
        );
        ServerPlayNetworking.send(player, payload);
    }

    public void broadcastSync() {
        broadcastSync("");
    }

    public void broadcastSync(String notificationMessage) {
        if (server == null) return;
        SyncPlaybackPayload payload = new SyncPlaybackPayload(
                currentTrack != null,
                currentTrack,
                getCurrentPositionMs(),
                isPlaying,
                new java.util.ArrayList<>(queue),
                notificationMessage != null ? notificationMessage : ""
        );
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    public boolean canModerate(ServerPlayer player) {
        if (player == null || server == null) return true; // Consola o servidor integrado
        return server.getPlayerList().isOp(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
    }

    public TrackInfo getCurrentTrack() {
        return currentTrack;
    }

    public java.util.List<TrackInfo> getQueueList() {
        return new java.util.ArrayList<>(queue);
    }
}
