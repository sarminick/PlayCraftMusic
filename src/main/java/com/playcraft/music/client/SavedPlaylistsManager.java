package com.playcraft.music.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.playcraft.music.model.TrackInfo;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Administra y guarda de forma persistente las playlists personalizadas y Favoritos del jugador
 * en config/playcraft_playlists.json.
 */
public class SavedPlaylistsManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("PlayCraftMusic-Playlists");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PLAYLISTS_PATH = FabricLoader.getInstance().getConfigDir().resolve("playcraft_playlists.json");
    public static final String FAVORITES_ID = "favorites";

    private static final List<SavedPlaylist> playlists = new ArrayList<>();

    public static class SavedTrack {
        public String title = "";
        public String author = "";
        public String streamUrl = "";
        public String thumbnailUrl = "";
        public long durationMs = 0L;

        public SavedTrack() {
        }

        public SavedTrack(TrackInfo track) {
            this.title = track.title() != null ? track.title() : "YouTube Track";
            this.author = track.author() != null ? track.author() : "YouTube";
            this.streamUrl = track.streamUrl() != null ? track.streamUrl() : "";
            this.thumbnailUrl = track.thumbnailUrl() != null ? track.thumbnailUrl() : "";
            this.durationMs = track.durationMs();
        }

        public TrackInfo toTrackInfo() {
            return new TrackInfo(
                    UUID.randomUUID().toString(),
                    title,
                    author,
                    streamUrl,
                    thumbnailUrl,
                    durationMs
            );
        }
    }

    public static class SavedPlaylist {
        public String id = "";
        public String name = "";
        public List<SavedTrack> tracks = new ArrayList<>();

        public SavedPlaylist() {
        }

        public SavedPlaylist(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public List<TrackInfo> toTrackInfoList() {
            List<TrackInfo> result = new ArrayList<>(tracks.size());
            for (SavedTrack st : tracks) {
                if (st != null && st.streamUrl != null && !st.streamUrl.isEmpty()) {
                    result.add(st.toTrackInfo());
                }
            }
            return result;
        }
    }

    public static synchronized void load() {
        playlists.clear();
        if (!Files.exists(PLAYLISTS_PATH)) {
            getOrCreateFavorites();
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(PLAYLISTS_PATH)) {
            Type listType = new TypeToken<List<SavedPlaylist>>() {}.getType();
            List<SavedPlaylist> loaded = GSON.fromJson(reader, listType);
            if (loaded != null) {
                for (SavedPlaylist pl : loaded) {
                    if (pl != null && pl.id != null && !pl.id.isEmpty()) {
                        if (pl.tracks == null) pl.tracks = new ArrayList<>();
                        playlists.add(pl);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[PlayCraftMusic] Error cargando playlists guardadas: {}", e.getMessage());
        }
        getOrCreateFavorites();
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(PLAYLISTS_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PLAYLISTS_PATH)) {
                GSON.toJson(playlists, writer);
            }
        } catch (Exception e) {
            LOGGER.warn("[PlayCraftMusic] No se pudieron guardar playlists: {}", e.getMessage());
        }
    }

    public static synchronized List<SavedPlaylist> getPlaylists() {
        return new ArrayList<>(playlists);
    }

    public static synchronized SavedPlaylist getPlaylistById(String id) {
        if (id == null) return null;
        for (SavedPlaylist pl : playlists) {
            if (id.equals(pl.id)) return pl;
        }
        return null;
    }

    public static synchronized SavedPlaylist getOrCreateFavorites() {
        SavedPlaylist fav = getPlaylistById(FAVORITES_ID);
        if (fav == null) {
            fav = new SavedPlaylist(FAVORITES_ID, "Favoritos");
            playlists.add(0, fav);
            save();
        }
        return fav;
    }

    public static synchronized SavedPlaylist createPlaylist(String name) {
        String cleanName = name != null ? name.trim() : "";
        if (cleanName.length() > 40) {
            cleanName = cleanName.substring(0, 40).trim();
        }
        if (cleanName.isEmpty()) {
            cleanName = "Colección #" + (playlists.size() + 1);
        }
        String uniqueName = makeUniqueName(cleanName, null);
        SavedPlaylist pl = new SavedPlaylist(UUID.randomUUID().toString(), uniqueName);
        playlists.add(pl);
        save();
        return pl;
    }

    public static synchronized boolean renamePlaylist(String id, String newName) {
        if (id == null || FAVORITES_ID.equals(id) || newName == null) return false;
        String cleanName = newName.trim();
        if (cleanName.isEmpty()) return false;
        if (cleanName.length() > 40) {
            cleanName = cleanName.substring(0, 40).trim();
        }
        SavedPlaylist target = getPlaylistById(id);
        if (target == null) return false;
        target.name = makeUniqueName(cleanName, id);
        save();
        return true;
    }

    private static String makeUniqueName(String baseName, String ignoreId) {
        String candidate = baseName;
        int suffix = 2;
        while (existsName(candidate, ignoreId)) {
            String tag = " (" + suffix + ")";
            int maxBase = Math.max(1, 40 - tag.length());
            String trimmedBase = baseName.length() > maxBase ? baseName.substring(0, maxBase).trim() : baseName;
            candidate = trimmedBase + tag;
            suffix++;
        }
        return candidate;
    }

    private static boolean existsName(String name, String ignoreId) {
        for (SavedPlaylist pl : playlists) {
            if (ignoreId != null && ignoreId.equals(pl.id)) continue;
            if (pl.name != null && pl.name.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public static synchronized boolean deletePlaylist(String id) {
        if (id == null || FAVORITES_ID.equals(id)) return false;
        boolean removed = playlists.removeIf(pl -> id.equals(pl.id));
        if (removed) save();
        return removed;
    }

    public static synchronized boolean addTrackToPlaylist(String playlistId, TrackInfo track) {
        if (track == null || track.streamUrl() == null || track.streamUrl().isEmpty()) return false;
        SavedPlaylist pl = FAVORITES_ID.equals(playlistId) ? getOrCreateFavorites() : getPlaylistById(playlistId);
        if (pl == null) return false;
        for (SavedTrack existing : pl.tracks) {
            if (track.streamUrl().equals(existing.streamUrl)) {
                return false; // Evitar duplicados en la misma playlist
            }
        }
        pl.tracks.add(new SavedTrack(track));
        save();
        return true;
    }

    public static synchronized int addTracksToPlaylist(String playlistId, List<TrackInfo> tracks) {
        if (tracks == null || tracks.isEmpty()) return 0;
        SavedPlaylist pl = FAVORITES_ID.equals(playlistId) ? getOrCreateFavorites() : getPlaylistById(playlistId);
        if (pl == null) return 0;
        int added = 0;
        for (TrackInfo t : tracks) {
            if (t == null || t.streamUrl() == null || t.streamUrl().isEmpty()) continue;
            boolean dup = false;
            for (SavedTrack existing : pl.tracks) {
                if (t.streamUrl().equals(existing.streamUrl)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                pl.tracks.add(new SavedTrack(t));
                added++;
            }
        }
        if (added > 0) save();
        return added;
    }

    public static synchronized boolean removeTrackFromPlaylist(String playlistId, int index) {
        SavedPlaylist pl = getPlaylistById(playlistId);
        if (pl == null || index < 0 || index >= pl.tracks.size()) return false;
        pl.tracks.remove(index);
        save();
        return true;
    }

    public static synchronized SavedPlaylist saveCurrentQueueAsPlaylist(String name, TrackInfo current, List<TrackInfo> queue) {
        List<TrackInfo> combined = new ArrayList<>();
        if (current != null) combined.add(current);
        if (queue != null) combined.addAll(queue);
        if (combined.isEmpty()) return null;
        SavedPlaylist pl = createPlaylist(name);
        addTracksToPlaylist(pl.id, combined);
        return pl;
    }
}
