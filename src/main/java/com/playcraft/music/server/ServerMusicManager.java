package com.playcraft.music.server;

import com.playcraft.music.model.TrackInfo;
import com.playcraft.music.network.ControlTrackPayload;
import com.playcraft.music.network.RequestAutoplayPayload;
import com.playcraft.music.network.SyncPlaybackPayload;
import com.playcraft.music.util.YouTubeMetadataResolver;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Administrador central de la cola de reproducción en el servidor.
 */
public class ServerMusicManager {
    private static final ServerMusicManager INSTANCE = new ServerMusicManager();

    public enum LoopMode {
        OFF,
        TRACK,
        QUEUE
    }

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

    // Autoplay, Loop y sugerencias tipo YouTube
    private boolean autoplayEnabled = false;
    private LoopMode loopMode = LoopMode.OFF;
    private TrackInfo autoplayNextTrack = null;
    private String autoplayRequestedForTrackId = "";
    private long autoplayRequestSentAtMs = 0L;
    private int autoplayRetryIndex = 0;
    private final Set<String> dismissedAutoplayIds = new LinkedHashSet<>();
    private UUID lastRequesterUuid = null;

    private ServerMusicManager() {}

    public static ServerMusicManager getInstance() {
        return INSTANCE;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public synchronized void enqueue(TrackInfo track, ServerPlayer requester) {
        if (track == null) return;
        if (requester != null) {
            lastRequesterUuid = requester.getUUID();
        }
        String notif = "";
        if (currentTrack == null) {
            playTrack(track);
            if (requester != null) {
                notif = "§6[PlayCraftMusic] §e" + requester.getName().getString() + " §7reproduciendo: §b" + track.title();
            }
        } else {
            queue.offerLast(track);
            this.autoplayNextTrack = null;
            this.autoplayRequestedForTrackId = "";
            if (requester != null) {
                notif = "§6[PlayCraftMusic] §e" + requester.getName().getString() + " §7agregó a la cola: §b" + track.title();
            }
        }
        broadcastSync(notif);
    }

    public synchronized void enqueueBatch(List<TrackInfo> tracks, ServerPlayer requester, String sourceLabel, boolean shuffleOnAdd) {
        if (tracks == null || tracks.isEmpty()) return;
        if (requester != null) {
            lastRequesterUuid = requester.getUUID();
        }
        int cap = Math.min(50, tracks.size());
        List<TrackInfo> copy = new ArrayList<>(tracks.subList(0, cap));
        if (shuffleOnAdd && copy.size() > 1) {
            Collections.shuffle(copy);
        }

        int startIdx = 0;
        if (currentTrack == null) {
            playTrack(copy.get(0));
            startIdx = 1;
        }
        for (int i = startIdx; i < copy.size(); i++) {
            queue.offerLast(copy.get(i));
        }
        if (!queue.isEmpty()) {
            this.autoplayNextTrack = null;
            this.autoplayRequestedForTrackId = "";
        }

        String notif = "";
        if (requester != null) {
            String labelPart = (sourceLabel != null && !sourceLabel.isEmpty()) ? " (" + sourceLabel + ")" : "";
            notif = "§6[PlayCraftMusic] §e" + requester.getName().getString()
                    + " §7añadió §b" + copy.size() + (copy.size() == 1 ? " canción" : " canciones") + labelPart;
        }
        broadcastSync(notif);
    }

    public synchronized void shuffleQueue(ServerPlayer player) {
        if (queue.size() <= 1) return;
        List<TrackInfo> list = new ArrayList<>(queue);
        Collections.shuffle(list);
        queue.clear();
        queue.addAll(list);
        String notif = player != null
                ? "§6[PlayCraftMusic] §e" + player.getName().getString() + " §7mezcló la cola de reproducción."
                : "§6[PlayCraftMusic] §7Cola de reproducción mezclada.";
        broadcastSync(notif);
    }

    public synchronized void rerollAutoplay(ServerPlayer player) {
        if (autoplayNextTrack != null) {
            String id = YouTubeMetadataResolver.extractVideoId(autoplayNextTrack.streamUrl());
            if (id != null && !id.isEmpty()) {
                dismissedAutoplayIds.add(id);
                while (dismissedAutoplayIds.size() > 30) {
                    var it = dismissedAutoplayIds.iterator();
                    it.next();
                    it.remove();
                }
            }
        }
        this.autoplayNextTrack = null;
        this.autoplayRequestedForTrackId = "";
        this.autoplayRequestSentAtMs = 0L;
        maybeRequestAutoplaySuggestion();
        broadcastSync("");
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
        this.autoplayNextTrack = null;
        this.autoplayRequestedForTrackId = "";
        this.autoplayRequestSentAtMs = 0L;
        maybeRequestAutoplaySuggestion();
    }

    public synchronized void handleControl(ControlTrackPayload payload, ServerPlayer player) {
        ControlTrackPayload.Action action = payload.action();
        boolean trackOptionalAction = action == ControlTrackPayload.Action.TOGGLE_AUTOPLAY
                || action == ControlTrackPayload.Action.CYCLE_LOOP
                || action == ControlTrackPayload.Action.SHUFFLE_QUEUE
                || (action == ControlTrackPayload.Action.REWIND && !history.isEmpty());
        if (currentTrack == null && !trackOptionalAction) return;

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
                String nextMsg = skipNext(true);
                notif = !nextMsg.isEmpty() ? nextMsg : skippedMsg;
            }
            case TOGGLE_AUTOPLAY -> {
                autoplayEnabled = !autoplayEnabled;
                if (!autoplayEnabled) {
                    autoplayNextTrack = null;
                    autoplayRequestedForTrackId = "";
                    notif = "§6[PlayCraftMusic] §e" + player.getName().getString() + " §7desactivó la §dreproducción automática§7.";
                } else {
                    maybeRequestAutoplaySuggestion();
                    notif = "§6[PlayCraftMusic] §e" + player.getName().getString() + " §7activó la §dreproducción automática§7.";
                }
            }
            case CYCLE_LOOP -> {
                LoopMode[] modes = LoopMode.values();
                loopMode = modes[(loopMode.ordinal() + 1) % modes.length];
                String modeName = switch (loopMode) {
                    case OFF -> "§cDesactivado";
                    case TRACK -> "§bCanción actual";
                    case QUEUE -> "§aToda la cola";
                };
                if (loopMode != LoopMode.TRACK) {
                    maybeRequestAutoplaySuggestion();
                }
                notif = "§6[PlayCraftMusic] §e" + player.getName().getString() + " §7cambió el modo de repetición a: " + modeName;
            }
            case SHUFFLE_QUEUE -> {
                shuffleQueue(player);
                return;
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
                autoplayNextTrack = null;
                autoplayRequestedForTrackId = "";
                autoplayRequestSentAtMs = 0L;
                dismissedAutoplayIds.clear();
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
                String notif = skipNext(false);
                broadcastSync(notif);
                return;
            }

            maybeRequestAutoplaySuggestion();

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

    private String skipNext(boolean manualSkip) {
        if (!manualSkip && loopMode == LoopMode.TRACK && currentTrack != null) {
            playTrack(currentTrack);
            return "§6[PlayCraftMusic] §7Repitiendo: §b" + currentTrack.title();
        }
        if (currentTrack != null) {
            history.push(currentTrack);
            while (history.size() > 20) {
                history.removeLast();
            }
            if (loopMode == LoopMode.QUEUE) {
                queue.offerLast(currentTrack);
            }
        }
        if (!queue.isEmpty()) {
            playTrack(queue.pollFirst());
            return "§6[PlayCraftMusic] §7Siguiente pista: §b" + currentTrack.title();
        } else if (autoplayEnabled && autoplayNextTrack != null) {
            TrackInfo next = autoplayNextTrack;
            playTrack(next);
            return "§6[PlayCraftMusic] §d∞ Autoplay: §b" + currentTrack.title();
        } else {
            currentTrack = null;
            isPlaying = false;
            waitingForFirstAudio = false;
            pausedPositionMs = 0;
            autoplayNextTrack = null;
            autoplayRequestedForTrackId = "";
            return "";
        }
    }

    private void maybeRequestAutoplaySuggestion() {
        if (server == null || !autoplayEnabled || currentTrack == null
                || !queue.isEmpty() || autoplayNextTrack != null || loopMode == LoopMode.TRACK) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        long now = System.currentTimeMillis();
        boolean isFirstAttemptForTrack = !currentTrack.id().equals(autoplayRequestedForTrackId);
        if (!isFirstAttemptForTrack && (now - autoplayRequestSentAtMs < 12000L)) {
            return;
        }

        ServerPlayer chosen = null;
        if (isFirstAttemptForTrack && lastRequesterUuid != null) {
            for (ServerPlayer p : players) {
                if (p.getUUID().equals(lastRequesterUuid)) {
                    chosen = p;
                    break;
                }
            }
        }
        if (chosen == null) {
            chosen = players.get(Math.floorMod(autoplayRetryIndex++, players.size()));
        }

        Set<String> recentIds = new LinkedHashSet<>();
        String curId = YouTubeMetadataResolver.extractVideoId(currentTrack.streamUrl());
        if (curId != null && !curId.isEmpty()) {
            recentIds.add(curId);
        }
        for (TrackInfo h : history) {
            if (recentIds.size() >= 25) break;
            String hid = YouTubeMetadataResolver.extractVideoId(h.streamUrl());
            if (hid != null && !hid.isEmpty()) {
                recentIds.add(hid);
            }
        }
        for (String dismissed : dismissedAutoplayIds) {
            if (recentIds.size() >= 30) break;
            recentIds.add(dismissed);
        }

        autoplayRequestedForTrackId = currentTrack.id();
        autoplayRequestSentAtMs = now;
        ServerPlayNetworking.send(chosen, new RequestAutoplayPayload(
                currentTrack.id(),
                currentTrack.streamUrl(),
                currentTrack.title(),
                currentTrack.author(),
                new ArrayList<>(recentIds)
        ));
    }

    public synchronized void handleAutoplaySuggestion(String forTrackId, boolean found, TrackInfo suggested) {
        if (!found || suggested == null) {
            if (currentTrack != null && currentTrack.id().equals(forTrackId)) {
                // Reducir espera a ~3s para intentar con otro jugador
                autoplayRequestSentAtMs = System.currentTimeMillis() - 9000L;
            }
            return;
        }
        if (autoplayEnabled && currentTrack != null && currentTrack.id().equals(forTrackId)
                && queue.isEmpty() && autoplayNextTrack == null) {
            this.autoplayNextTrack = suggested;
            broadcastSync("");
        }
    }

    public long getCurrentPositionMs() {
        if (currentTrack == null) return 0;
        if (!isPlaying || waitingForFirstAudio) return pausedPositionMs;
        return System.currentTimeMillis() - playbackStartTimeMs;
    }

    private List<TrackInfo> historySnapshot() {
        List<TrackInfo> snap = new ArrayList<>();
        for (TrackInfo t : history) {
            snap.add(t);
            if (snap.size() >= 15) break;
        }
        return snap;
    }

    private SyncPlaybackPayload buildSyncPayload(String notificationMessage) {
        return new SyncPlaybackPayload(
                currentTrack != null,
                currentTrack,
                getCurrentPositionMs(),
                isPlaying,
                new ArrayList<>(queue),
                notificationMessage != null ? notificationMessage : "",
                autoplayEnabled,
                loopMode.ordinal(),
                autoplayNextTrack != null,
                autoplayNextTrack,
                historySnapshot()
        );
    }

    public void syncToPlayer(ServerPlayer player) {
        ServerPlayNetworking.send(player, buildSyncPayload(""));
    }

    public void broadcastSync() {
        broadcastSync("");
    }

    public void broadcastSync(String notificationMessage) {
        if (server == null) return;
        SyncPlaybackPayload payload = buildSyncPayload(notificationMessage);
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
