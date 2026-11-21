package com.playcraft.music.client.gui;

import com.playcraft.music.client.PlayCraftClient;
import com.playcraft.music.client.PlayCraftConfig;
import com.playcraft.music.client.SavedPlaylistsManager;
import com.playcraft.music.client.SavedPlaylistsManager.SavedPlaylist;
import com.playcraft.music.client.SavedPlaylistsManager.SavedTrack;
import com.playcraft.music.client.audio.ClientAudioEngine;
import com.playcraft.music.client.audio.ClientAudioEngine.PlaybackStatus;
import com.playcraft.music.model.TrackInfo;
import com.playcraft.music.network.AddBatchTracksPayload;
import com.playcraft.music.network.AddTrackPayload;
import com.playcraft.music.network.ControlTrackPayload;
import com.playcraft.music.network.ModifyQueuePayload;
import com.playcraft.music.util.BinaryLocator;
import com.playcraft.music.util.YouTubeMetadataResolver;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Centro musical completo con 5 pestañas:
 * 1. [Now Playing]: Carátula vinilo, estado del motor de audio, Autoplay/Loop/Shuffle, Favorito y Reconectar audio.
 * 2. [Search]: Buscador con "Ver más resultados" y Modo Enlace Directo con importación desde cliente.
 * 3. [Queue]: Cola de reproducción con vista previa de Autoplay e Historial reciente.
 * 4. [Playlists]: Mis Playlists locales persistentes (crear, guardar cola, favoritos, cargar o mezclar).
 * 5. [Settings]: Personalización del HUD, silenciado de música Minecraft, avisos y actualización de yt-dlp.
 */
public class YouTubeSearchScreen extends Screen {

    public enum Tab {
        NOW_PLAYING,
        SEARCH,
        QUEUE,
        PLAYLISTS,
        SETTINGS
    }

    private static final int PANEL_HEIGHT = 264;
    private static Tab currentTab = Tab.NOW_PLAYING;

    // Now Playing feedback
    private static String favButtonFeedback = "";
    private static long favButtonFeedbackTime = 0L;

    // Search & Link state
    private EditBox searchInput;
    private static String lastSearchQuery = "";
    private static boolean isSearchMode = true;
    private static boolean importPlaylistOnListParam = true;
    private static boolean saveImportedPlaylistLocally = false;
    private static List<TrackInfo> searchResults = new ArrayList<>();
    private static boolean isSearching = false;
    private static int searchResultLimit = 5;
    private int searchScrollOffset = 0;
    private boolean isDraggingSearchScrollbar = false;
    private static final Set<String> addedSearchUrls = new HashSet<>();
    private static String linkErrorMessage = "";
    private static long linkErrorTimestamp = 0L;
    private static String linkSuccessMessage = "";
    private static long linkSuccessTimestamp = 0L;

    // Queue & History state
    private static boolean showHistorySubTab = false;
    private int queueScrollOffset = 0;
    private int historyScrollOffset = 0;
    private boolean isDraggingQueueScrollbar = false;
    private static String queueStatusMessage = "";
    private static long queueStatusTimestamp = 0L;

    // Playlists tab state
    private EditBox playlistNameInput;
    private EditBox renamePlaylistInput;
    private boolean isRenamingPlaylist = false;
    private static String lastPlaylistNameInput = "";
    private static String selectedPlaylistId = null;
    private int playlistsScrollOffset = 0;
    private boolean isDraggingPlaylistsScrollbar = false;
    private static String playlistStatusMessage = "";
    private static long playlistStatusTimestamp = 0L;

    // Settings tab state
    private static String ytdlpUpdateStatus = "";
    private static String ytdlpUpdateHint = "";
    private static boolean isCheckingUpdates = false;

    // Sync diff tracking
    private String lastSyncSignature = "";

    public YouTubeSearchScreen() {
        super(Component.literal("PlayCraftMusic - Music Center"));
    }

    public boolean isSettingsTabActive() {
        return currentTab == Tab.SETTINGS;
    }

    private int getPanelW() {
        return Math.min(540, this.width - 40);
    }

    private int getPanelX(int panelW) {
        return (this.width - panelW) / 2;
    }

    private int getPanelY() {
        return Math.min(22, Math.max(6, (this.height - PANEL_HEIGHT) / 2));
    }

    private boolean shouldShowChangelogModal() {
        return !PlayCraftConfig.CURRENT_MOD_VERSION.equals(PlayCraftConfig.LAST_SEEN_CHANGELOG_VERSION);
    }

    private void dismissChangelogModal() {
        PlayCraftConfig.LAST_SEEN_CHANGELOG_VERSION = PlayCraftConfig.CURRENT_MOD_VERSION;
        PlayCraftConfig.save();
        this.rebuildWidgets();
    }

    @Override
    protected void init() {
        this.searchInput = null;
        this.playlistNameInput = null;
        this.renamePlaylistInput = null;
        int panelW = getPanelW();
        int panelX = getPanelX(panelW);
        int panelY = getPanelY();
        lastSyncSignature = computeSyncSignature();

        if (shouldShowChangelogModal()) {
            this.addRenderableWidget(Button.builder(Component.literal("✖"), b -> dismissChangelogModal())
                    .bounds(panelX + panelW - 28, panelY + 8, 20, 18).build());
            this.addRenderableWidget(Button.builder(Component.literal("§aEntendido"), b -> dismissChangelogModal())
                    .bounds(panelX + (panelW - 110) / 2, panelY + 222, 110, 20).build());
            return;
        }

        // 5 pestañas superiores distribuidas exactamente al píxel
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int tX = panelX + (i * panelW / tabs.length);
            int nextTX = panelX + ((i + 1) * panelW / tabs.length);
            int tW = nextTX - tX;
            final Tab tabTarget = tabs[i];
            String label = switch (tabTarget) {
                case NOW_PLAYING -> currentTab == Tab.NOW_PLAYING ? "§6§lNow Playing" : "Now Playing";
                case SEARCH -> currentTab == Tab.SEARCH ? "§6§lSearch" : "Search";
                case QUEUE -> currentTab == Tab.QUEUE ? "§6§lQueue" : "Queue";
                case PLAYLISTS -> currentTab == Tab.PLAYLISTS ? "§6§lPlaylists" : "Playlists";
                case SETTINGS -> currentTab == Tab.SETTINGS ? "§6§lSettings" : "Settings";
            };
            this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
                currentTab = tabTarget;
                this.isRenamingPlaylist = false;
                this.rebuildWidgets();
            }).bounds(tX, panelY, tW, 20).build());
        }

        switch (currentTab) {
            case NOW_PLAYING -> initNowPlayingTab(panelX, panelY, panelW);
            case SEARCH -> initSearchTab(panelX, panelY, panelW);
            case QUEUE -> initQueueTab(panelX, panelY, panelW);
            case PLAYLISTS -> initPlaylistsTab(panelX, panelY, panelW);
            case SETTINGS -> initSettingsTab(panelX, panelY, panelW);
        }
    }

    @Override
    public void removed() {
        PlayCraftConfig.save();
        super.removed();
    }

    /* ------------------------------------------------------------- */
    /* PESTAÑA 1: NOW PLAYING                                        */
    /* ------------------------------------------------------------- */
    private void initNowPlayingTab(int panelX, int panelY, int panelW) {
        ClientAudioEngine engine = ClientAudioEngine.getInstance();
        TrackInfo track = engine.getCurrentTrack();
        boolean hasTrack = track != null;
        int cardY = panelY + 30;

        // Botones laterales en la tarjeta: [Reconectar] y [★ Favorito]
        Button reconnectBtn = Button.builder(Component.literal("Reconectar"), b -> {
            engine.reconnectCurrentTrack();
            this.rebuildWidgets();
        }).bounds(panelX + panelW - 84, cardY + 6, 72, 18).build();
        reconnectBtn.active = hasTrack;
        this.addRenderableWidget(reconnectBtn);

        boolean showFavAck = !favButtonFeedback.isEmpty() && (System.currentTimeMillis() - favButtonFeedbackTime < 2500L);
        Button favBtn = Button.builder(Component.literal(showFavAck ? favButtonFeedback : "§e★ Favorito"), b -> {
            TrackInfo t = engine.getCurrentTrack();
            if (t != null) {
                boolean added = SavedPlaylistsManager.addTrackToPlaylist(SavedPlaylistsManager.FAVORITES_ID, t);
                favButtonFeedback = added ? "§a✔ Guardada" : "§eEn Favoritos";
                favButtonFeedbackTime = System.currentTimeMillis();
                this.rebuildWidgets();
            }
        }).bounds(panelX + panelW - 84, cardY + 28, 72, 18).build();
        favBtn.active = hasTrack;
        this.addRenderableWidget(favBtn);

        // Slider de Seek / Progreso
        long trackDuration = hasTrack && track.durationMs() > 0 ? track.durationMs() : 0L;
        long livePos = hasTrack ? MusicHudOverlay.getLivePositionMs() : 0L;
        double initialPosRatio = (hasTrack && trackDuration > 0)
                ? Math.max(0.0, Math.min(1.0, (double) livePos / (double) trackDuration))
                : 0.0;

        ModernMediaSlider posSlider = new ModernMediaSlider(
                panelX + 10,
                panelY + 112,
                panelW - 20,
                22,
                initialPosRatio,
                ModernMediaSlider.Theme.fromHudTheme(MusicHudOverlay.CURRENT_THEME),
                val -> {
                    TrackInfo t = ClientAudioEngine.getInstance().getCurrentTrack();
                    if (t == null) return Component.literal("⏱ 00:00 / 00:00");
                    long d = t.durationMs() > 0 ? t.durationMs() : 0L;
                    return Component.literal("⏱ " + formatDuration((long) (val * d)) + " / " + formatDuration(d));
                },
                val -> {
                    TrackInfo t = ClientAudioEngine.getInstance().getCurrentTrack();
                    if (t != null && t.durationMs() > 0) {
                        long targetMs = (long) (val * t.durationMs());
                        MusicHudOverlay.updatePlaybackState(targetMs, ClientAudioEngine.getInstance().isPlaying());
                        ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.SEEK, targetMs));
                    }
                },
                true
        ) {
            @Override
            public void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
                TrackInfo t = ClientAudioEngine.getInstance().getCurrentTrack();
                if (t != null && t.durationMs() > 0) {
                    long currentLive = MusicHudOverlay.getLivePositionMs();
                    this.setProgressDirect((double) currentLive / (double) t.durationMs());
                }
                super.extractWidgetRenderState(context, mouseX, mouseY, delta);
            }
        };
        posSlider.active = hasTrack;
        this.addRenderableWidget(posSlider);

        // Slider de Volumen y Mute
        int volY = panelY + 140;
        float currentVol = engine.getVolume();
        boolean isMuted = engine.isMuted();
        int muteBtnW = 32;

        this.addRenderableWidget(new ModernMediaSlider(
                panelX + 10,
                volY,
                panelW - 20 - muteBtnW - 6,
                22,
                currentVol,
                ModernMediaSlider.Theme.volumeFromHudTheme(MusicHudOverlay.CURRENT_THEME),
                val -> Component.literal("🔊 Volumen: " + (int) (val * 100) + "%"),
                val -> {
                    ClientAudioEngine.getInstance().setVolume(val.floatValue());
                    PlayCraftConfig.save();
                }
        ));

        this.addRenderableWidget(Button.builder(Component.literal(isMuted ? "🔇" : "🔊"), b -> {
            ClientAudioEngine.getInstance().toggleMute();
            PlayCraftConfig.save();
            this.rebuildWidgets();
        }).bounds(panelX + panelW - 10 - muteBtnW, volY, muteBtnW, 22).build());

        // Controles principales: [⏮ Prev] [❚❚ Pause/▶ Resume] [⏭ Skip] [⏹ Stop]
        int ctrlY = panelY + 170;
        int btnW = 90;
        int startX = panelX + (panelW - (btnW * 4 + 18)) / 2;

        this.addRenderableWidget(Button.builder(Component.literal("⏮ Prev"), b -> {
            ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.REWIND));
        }).bounds(startX, ctrlY, btnW, 20).build());

        boolean isPlaying = engine.isPlaying();
        Button playPauseBtn = Button.builder(Component.literal(isPlaying ? "❚❚ Pause" : "▶ Resume"), b -> {
            ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.TOGGLE_PAUSE));
            this.rebuildWidgets();
        }).bounds(startX + btnW + 6, ctrlY, btnW, 20).build();
        playPauseBtn.active = hasTrack;
        this.addRenderableWidget(playPauseBtn);

        Button skipBtn = Button.builder(Component.literal("⏭ Skip"), b -> {
            ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.SKIP));
        }).bounds(startX + (btnW * 2) + 12, ctrlY, btnW, 20).build();
        skipBtn.active = hasTrack;
        this.addRenderableWidget(skipBtn);

        Button stopBtn = Button.builder(Component.literal("⏹ Stop"), b -> {
            ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.STOP));
            this.onClose();
        }).bounds(startX + (btnW * 3) + 18, ctrlY, btnW, 20).build();
        stopBtn.active = hasTrack;
        this.addRenderableWidget(stopBtn);

        // Fila de Modos: [∞ Autoplay] [🔁 Loop] [🔀 Shuffle]
        int modeY = panelY + 198;
        int autoW = 132;
        int loopW = 132;
        int shufW = 100;
        int modeStartX = panelX + (panelW - (autoW + loopW + shufW + 12)) / 2;

        boolean autoplayOn = engine.isAutoplayEnabled();
        this.addRenderableWidget(Button.builder(
                Component.literal(autoplayOn ? "∞ Autoplay: §dON" : "∞ Autoplay: §7OFF"),
                b -> ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.TOGGLE_AUTOPLAY))
        ).bounds(modeStartX, modeY, autoW, 18).build());

        int loopOrd = engine.getLoopModeOrdinal();
        String loopLabel = switch (loopOrd) {
            case 1 -> "🔁 Loop: §b1 Canción";
            case 2 -> "🔁 Loop: §aCola";
            default -> "🔁 Loop: §7Off";
        };
        this.addRenderableWidget(Button.builder(
                Component.literal(loopLabel),
                b -> ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.CYCLE_LOOP))
        ).bounds(modeStartX + autoW + 6, modeY, loopW, 18).build());

        Button shuffleBtn = Button.builder(
                Component.literal("🔀 Shuffle"),
                b -> ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.SHUFFLE_QUEUE))
        ).bounds(modeStartX + autoW + loopW + 12, modeY, shufW, 18).build();
        shuffleBtn.active = engine.getQueue().size() > 1;
        this.addRenderableWidget(shuffleBtn);
    }

    /* ------------------------------------------------------------- */
    /* PESTAÑA 2: SEARCH (Buscador y Enlace Directo desde Cliente)   */
    /* ------------------------------------------------------------- */
    private void initSearchTab(int panelX, int panelY, int panelW) {
        int inputY = panelY + 30;
        int btnModeW = 100;
        int btnActionW = 70;
        int inputW = panelW - btnModeW - btnActionW - 28;

        this.addRenderableWidget(Button.builder(
                Component.literal(isSearchMode ? "§bMode: Search" : "§eMode: Link"),
                b -> {
                    isSearchMode = !isSearchMode;
                    lastSearchQuery = "";
                    linkErrorMessage = "";
                    linkSuccessMessage = "";
                    if (this.searchInput != null) {
                        this.searchInput.setValue("");
                    }
                    this.rebuildWidgets();
                }
        ).bounds(panelX + 10, inputY, btnModeW, 20).build());

        String placeholder = isSearchMode ? "Search song or artist..." : "Paste YouTube link or playlist...";
        this.searchInput = new EditBox(
                this.font,
                panelX + 10 + btnModeW + 4,
                inputY,
                inputW,
                20,
                Component.literal(placeholder)
        );
        this.searchInput.setMaxLength(512);
        this.searchInput.setValue(lastSearchQuery);
        this.searchInput.setResponder(val -> lastSearchQuery = val);
        this.addRenderableWidget(this.searchInput);

        this.addRenderableWidget(Button.builder(
                Component.literal(isSearchMode ? "§aSearch" : "§a+ Add"),
                b -> executeSearchOrAdd()
        ).bounds(panelX + 10 + btnModeW + inputW + 8, inputY, btnActionW, 20).build());

        if (isSearchMode && !isSearching && !searchResults.isEmpty()) {
            int listY = panelY + 56;
            int visibleCount = 4;
            int maxOffset = Math.max(0, searchResults.size() - visibleCount);
            if (searchScrollOffset > maxOffset) searchScrollOffset = maxOffset;
            if (searchScrollOffset < 0) searchScrollOffset = 0;

            int endIdx = Math.min(searchResults.size(), searchScrollOffset + visibleCount);
            for (int i = searchScrollOffset; i < endIdx; i++) {
                TrackInfo item = searchResults.get(i);
                int rowIdx = i - searchScrollOffset;
                int rowY = listY + (rowIdx * 34);
                boolean alreadyAdded = addedSearchUrls.contains(item.streamUrl());

                // Botón guardar en Favoritos [★]
                this.addRenderableWidget(Button.builder(Component.literal("§e★"), b -> {
                    SavedPlaylistsManager.addTrackToPlaylist(SavedPlaylistsManager.FAVORITES_ID, item);
                    b.setMessage(Component.literal("§a✔"));
                    b.active = false;
                }).bounds(panelX + panelW - 102, rowY + 5, 22, 20).build());

                // Botón añadir a cola [+ Add]
                Button addBtn = Button.builder(Component.literal(alreadyAdded ? "§e✔ Added" : "§a+ Add"), b -> {
                    ThumbnailTextureManager.getThumbnail(item.thumbnailUrl());
                    ClientPlayNetworking.send(new AddTrackPayload(
                            item.streamUrl(),
                            item.title(),
                            item.author(),
                            item.thumbnailUrl(),
                            item.durationMs()
                    ));
                    addedSearchUrls.add(item.streamUrl());
                    b.setMessage(Component.literal("§e✔ Added"));
                    b.active = false;
                }).bounds(panelX + panelW - 76, rowY + 5, 62, 20).build();
                addBtn.active = !alreadyAdded;
                this.addRenderableWidget(addBtn);
            }

            if (searchResultLimit < 20 && !lastSearchQuery.trim().isEmpty()) {
                int moreBtnW = 150;
                int moreBtnH = 18;
                int moreBtnY = panelY + 202;
                this.addRenderableWidget(Button.builder(Component.literal("§b➕ Ver más resultados"), b -> {
                    loadMoreSearchResults();
                }).bounds(panelX + (panelW - moreBtnW) / 2, moreBtnY, moreBtnW, moreBtnH).build());
            }
        } else if (!isSearchMode && !isSearching) {
            // Opciones inteligentes de importación en Modo Link
            int toggleY = panelY + 58;
            int halfW = (panelW - 26) / 2;

            this.addRenderableWidget(Button.builder(
                    Component.literal("Si trae list=: " + (importPlaylistOnListParam ? "§aImportar Playlist" : "§7Solo Canción")),
                    b -> {
                        importPlaylistOnListParam = !importPlaylistOnListParam;
                        this.rebuildWidgets();
                    }
            ).bounds(panelX + 10, toggleY, halfW, 18).build());

            this.addRenderableWidget(Button.builder(
                    Component.literal("Guardar Playlist local: " + (saveImportedPlaylistLocally ? "§aSí" : "§7No")),
                    b -> {
                        saveImportedPlaylistLocally = !saveImportedPlaylistLocally;
                        this.rebuildWidgets();
                    }
            ).bounds(panelX + 16 + halfW, toggleY, halfW, 18).build());
        }
    }

    private void executeSearchOrAdd() {
        if (this.searchInput == null) return;
        String query = searchInput.getValue().trim();
        if (query.isEmpty()) return;

        if (!isSearchMode) {
            if (!query.startsWith("http://") && !query.startsWith("https://")) {
                linkErrorMessage = "Introduce un enlace válido que comience con http:// o https://.";
                linkErrorTimestamp = System.currentTimeMillis();
                linkSuccessMessage = "";
                return;
            }
            linkErrorMessage = "";
            linkSuccessMessage = "";

            Minecraft mc = Minecraft.getInstance();
            String playerName = (mc != null && mc.player != null) ? mc.player.getName().getString() : "Player";
            searchInput.setValue("");
            lastSearchQuery = "";
            isSearching = true;
            this.rebuildWidgets();

            if (YouTubeMetadataResolver.isPlaylistUrl(query) && importPlaylistOnListParam) {
                YouTubeMetadataResolver.resolvePlaylistWithInfo(query, playerName, 50).thenAccept(resolved -> {
                    if (mc != null) {
                        mc.execute(() -> {
                            isSearching = false;
                            List<TrackInfo> tracks = resolved != null ? resolved.tracks() : List.of();
                            if (tracks == null || tracks.isEmpty()) {
                                linkErrorMessage = "No se pudieron obtener pistas válidas de ese enlace.";
                                linkErrorTimestamp = System.currentTimeMillis();
                            } else {
                                String baseTitle = (resolved.title() != null && !resolved.title().isEmpty())
                                        ? resolved.title()
                                        : "Colección de YouTube";
                                String finalLabel = baseTitle;
                                if (saveImportedPlaylistLocally) {
                                    SavedPlaylist sp = SavedPlaylistsManager.createPlaylist(baseTitle);
                                    SavedPlaylistsManager.addTracksToPlaylist(sp.id, tracks);
                                    finalLabel = sp.name;
                                }
                                ClientAudioEngine.getInstance().prefetchStreamUrl(tracks.get(0).streamUrl());
                                ClientPlayNetworking.send(new AddBatchTracksPayload(tracks, finalLabel, false));
                                linkSuccessMessage = saveImportedPlaylistLocally
                                        ? "Playlist añadida (" + tracks.size() + " pistas) y guardada como \"" + finalLabel + "\"."
                                        : "Playlist añadida a la cola (" + tracks.size() + " pistas).";
                                linkSuccessTimestamp = System.currentTimeMillis();
                            }
                            if (mc.gui.screen() instanceof YouTubeSearchScreen activeScreen) {
                                activeScreen.rebuildWidgets();
                            }
                        });
                    }
                });
            } else {
                YouTubeMetadataResolver.resolve(query, playerName).thenAccept(track -> {
                    ClientAudioEngine.getInstance().prefetchStreamUrl(track.streamUrl());
                    if (mc != null) {
                        mc.execute(() -> {
                            ThumbnailTextureManager.getThumbnail(track.thumbnailUrl());
                            ClientPlayNetworking.send(new AddTrackPayload(
                                    track.streamUrl(),
                                    track.title(),
                                    track.author(),
                                    track.thumbnailUrl(),
                                    track.durationMs()
                            ));
                            isSearching = false;
                            String shortTitle = track.title().length() > 36 ? track.title().substring(0, 34) + "..." : track.title();
                            linkSuccessMessage = "Pista añadida a la cola: " + shortTitle;
                            linkSuccessTimestamp = System.currentTimeMillis();
                            if (mc.gui.screen() instanceof YouTubeSearchScreen activeScreen) {
                                activeScreen.rebuildWidgets();
                            }
                        });
                    }
                });
            }
        } else {
            lastSearchQuery = query;
            isSearching = true;
            searchResultLimit = 5;
            searchScrollOffset = 0;
            addedSearchUrls.clear();
            searchResults.clear();
            this.rebuildWidgets();
            YouTubeMetadataResolver.searchMultiple(query, searchResultLimit).thenAccept(results -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    mc.execute(() -> {
                        searchResults = results;
                        isSearching = false;
                        if (results != null && !results.isEmpty()) {
                            ClientAudioEngine.getInstance().prefetchStreamUrl(results.get(0).streamUrl());
                        }
                        if (mc.gui.screen() instanceof YouTubeSearchScreen activeScreen) {
                            activeScreen.rebuildWidgets();
                        }
                    });
                }
            });
        }
    }

    private void loadMoreSearchResults() {
        String query = lastSearchQuery.trim();
        if (query.isEmpty() || isSearching) return;
        searchResultLimit = Math.min(20, searchResultLimit + 5);
        int previousSize = searchResults.size();
        isSearching = true;
        this.rebuildWidgets();
        YouTubeMetadataResolver.searchMultiple(query, searchResultLimit).thenAccept(results -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    searchResults = results;
                    isSearching = false;
                    if (results.size() > previousSize) {
                        searchScrollOffset = Math.min(Math.max(0, results.size() - 4), previousSize);
                    }
                    if (mc.gui.screen() instanceof YouTubeSearchScreen activeScreen) {
                        activeScreen.rebuildWidgets();
                    }
                });
            }
        });
    }

    /* ------------------------------------------------------------- */
    /* PESTAÑA 3: QUEUE (Cola de reproducción + Historial)           */
    /* ------------------------------------------------------------- */
    private void initQueueTab(int panelX, int panelY, int panelW) {
        ClientAudioEngine engine = ClientAudioEngine.getInstance();
        List<TrackInfo> queue = engine.getQueue();
        List<TrackInfo> history = engine.getHistory();

        int subBarY = panelY + 26;
        // Sub-pestañas Cola vs Historial
        this.addRenderableWidget(Button.builder(
                Component.literal(!showHistorySubTab ? "§6Cola (" + queue.size() + ")" : "Cola (" + queue.size() + ")"),
                b -> {
                    showHistorySubTab = false;
                    this.rebuildWidgets();
                }
        ).bounds(panelX + 10, subBarY, 82, 18).build());

        this.addRenderableWidget(Button.builder(
                Component.literal(showHistorySubTab ? "§6🕒 Historial (" + history.size() + ")" : "🕒 Historial (" + history.size() + ")"),
                b -> {
                    showHistorySubTab = true;
                    this.rebuildWidgets();
                }
        ).bounds(panelX + 96, subBarY, 102, 18).build());

        if (!showHistorySubTab) {
            // Botones rápidos de cola
            Button shuffleBtn = Button.builder(Component.literal("§b🔀 Mezclar"), b -> {
                ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.SHUFFLE_QUEUE));
            }).bounds(panelX + panelW - 216, subBarY, 70, 18).build();
            shuffleBtn.active = queue.size() > 1;
            this.addRenderableWidget(shuffleBtn);

            Button saveQueueBtn = Button.builder(Component.literal("§e★ Guardar"), b -> {
                TrackInfo cur = engine.getCurrentTrack();
                List<TrackInfo> q = engine.getQueue();
                if (cur != null || !q.isEmpty()) {
                    String name = buildDistinctiveQueuePlaylistName(cur);
                    SavedPlaylist pl = SavedPlaylistsManager.saveCurrentQueueAsPlaylist(name, cur, q);
                    if (pl != null) {
                        queueStatusMessage = "§aCola actual guardada como \"" + pl.name + "\".";
                        queueStatusTimestamp = System.currentTimeMillis();
                        this.rebuildWidgets();
                    }
                }
            }).bounds(panelX + panelW - 142, subBarY, 72, 18).build();
            saveQueueBtn.active = engine.getCurrentTrack() != null || !queue.isEmpty();
            this.addRenderableWidget(saveQueueBtn);

            Button clearBtn = Button.builder(Component.literal("§c🧹 Clear"), b -> {
                ClientPlayNetworking.send(new ModifyQueuePayload(ModifyQueuePayload.Action.CLEAR, 0, 0));
                this.rebuildWidgets();
            }).bounds(panelX + panelW - 66, subBarY, 56, 18).build();
            clearBtn.active = !queue.isEmpty();
            this.addRenderableWidget(clearBtn);

            // Lista de cola (5 filas visibles)
            int listY = panelY + 50;
            int visibleCount = 5;
            if (!queue.isEmpty()) {
                int maxOffset = Math.max(0, queue.size() - visibleCount);
                if (queueScrollOffset > maxOffset) queueScrollOffset = maxOffset;
                if (queueScrollOffset < 0) queueScrollOffset = 0;

                int endIdx = Math.min(queue.size(), queueScrollOffset + visibleCount);
                for (int i = queueScrollOffset; i < endIdx; i++) {
                    final int idx = i;
                    int rowIdx = i - queueScrollOffset;
                    int rowY = listY + (rowIdx * 32);

                    if (idx > 0) {
                        this.addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
                            ClientPlayNetworking.send(new ModifyQueuePayload(ModifyQueuePayload.Action.MOVE, idx, idx - 1));
                        }).bounds(panelX + panelW - 88, rowY + 4, 22, 20).build());
                    }
                    if (idx < queue.size() - 1) {
                        this.addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
                            ClientPlayNetworking.send(new ModifyQueuePayload(ModifyQueuePayload.Action.MOVE, idx, idx + 1));
                        }).bounds(panelX + panelW - 63, rowY + 4, 22, 20).build());
                    }
                    this.addRenderableWidget(Button.builder(Component.literal("§c✖"), b -> {
                        ClientPlayNetworking.send(new ModifyQueuePayload(ModifyQueuePayload.Action.REMOVE, idx, 0));
                    }).bounds(panelX + panelW - 38, rowY + 4, 25, 20).build());
                }
            } else if (engine.isAutoplayEnabled() && engine.getCurrentTrack() != null && engine.getAutoplayNextTrack() != null) {
                // Tarjeta inferior de vista previa Autoplay con botón [Cambiar]
                this.addRenderableWidget(Button.builder(Component.literal("Cambiar"), b -> {
                    ClientPlayNetworking.send(new ModifyQueuePayload(ModifyQueuePayload.Action.REROLL_AUTOPLAY, 0, 0));
                }).bounds(panelX + panelW - 72, panelY + 220, 58, 18).build());
            }
        } else {
            // Sub-pestaña Historial (5 filas visibles)
            int listY = panelY + 50;
            int visibleCount = 5;
            if (!history.isEmpty()) {
                int maxOffset = Math.max(0, history.size() - visibleCount);
                if (historyScrollOffset > maxOffset) historyScrollOffset = maxOffset;
                if (historyScrollOffset < 0) historyScrollOffset = 0;

                int endIdx = Math.min(history.size(), historyScrollOffset + visibleCount);
                for (int i = historyScrollOffset; i < endIdx; i++) {
                    TrackInfo item = history.get(i);
                    int rowIdx = i - historyScrollOffset;
                    int rowY = listY + (rowIdx * 32);

                    this.addRenderableWidget(Button.builder(Component.literal("§e★"), b -> {
                        SavedPlaylistsManager.addTrackToPlaylist(SavedPlaylistsManager.FAVORITES_ID, item);
                        b.setMessage(Component.literal("§a✔"));
                        b.active = false;
                    }).bounds(panelX + panelW - 102, rowY + 4, 22, 20).build());

                    this.addRenderableWidget(Button.builder(Component.literal("§a+ Add"), b -> {
                        ThumbnailTextureManager.getThumbnail(item.thumbnailUrl());
                        ClientPlayNetworking.send(new AddTrackPayload(
                                item.streamUrl(),
                                item.title(),
                                item.author(),
                                item.thumbnailUrl(),
                                item.durationMs()
                        ));
                        b.setMessage(Component.literal("§e✔ Added"));
                        b.active = false;
                    }).bounds(panelX + panelW - 76, rowY + 4, 62, 20).build());
                }
            }
        }
    }

    /* ------------------------------------------------------------- */
    /* PESTAÑA 4: PLAYLISTS (Mis Playlists Guardadas)                */
    /* ------------------------------------------------------------- */
    private void initPlaylistsTab(int panelX, int panelY, int panelW) {
        if (selectedPlaylistId != null && SavedPlaylistsManager.getPlaylistById(selectedPlaylistId) == null) {
            selectedPlaylistId = null;
        }

        if (selectedPlaylistId == null) {
            // Vista Lista de Playlists Guardadas
            int topY = panelY + 28;
            int createW = 68;
            int saveQueueW = 96;
            int inputW = panelW - createW - saveQueueW - 30;

            this.playlistNameInput = new EditBox(
                    this.font,
                    panelX + 10,
                    topY,
                    inputW,
                    20,
                    Component.literal("Nombre nueva playlist...")
            );
            this.playlistNameInput.setMaxLength(40);
            this.playlistNameInput.setValue(lastPlaylistNameInput);
            this.playlistNameInput.setResponder(val -> lastPlaylistNameInput = val);
            this.addRenderableWidget(this.playlistNameInput);

            this.addRenderableWidget(Button.builder(Component.literal("§a+ Crear"), b -> {
                createNewSavedPlaylist();
            }).bounds(panelX + 14 + inputW, topY, createW, 20).build());

            ClientAudioEngine engine = ClientAudioEngine.getInstance();
            Button saveCurBtn = Button.builder(Component.literal("§e★ Guardar Cola"), b -> {
                TrackInfo cur = engine.getCurrentTrack();
                List<TrackInfo> q = engine.getQueue();
                String customName = this.playlistNameInput != null ? this.playlistNameInput.getValue().trim() : "";
                if (customName.isEmpty()) {
                    customName = buildDistinctiveQueuePlaylistName(cur);
                }
                SavedPlaylist created = SavedPlaylistsManager.saveCurrentQueueAsPlaylist(customName, cur, q);
                if (created != null) {
                    if (this.playlistNameInput != null) this.playlistNameInput.setValue("");
                    lastPlaylistNameInput = "";
                    playlistStatusMessage = "§aCola actual guardada como \"" + created.name + "\" (" + created.tracks.size() + " pistas).";
                    playlistStatusTimestamp = System.currentTimeMillis();
                    this.rebuildWidgets();
                }
            }).bounds(panelX + panelW - 10 - saveQueueW, topY, saveQueueW, 20).build();
            saveCurBtn.active = engine.getCurrentTrack() != null || !engine.getQueue().isEmpty();
            this.addRenderableWidget(saveCurBtn);

            List<SavedPlaylist> pls = SavedPlaylistsManager.getPlaylists();
            int listY = panelY + 56;
            int visibleCount = 5;
            int maxOffset = Math.max(0, pls.size() - visibleCount);
            if (playlistsScrollOffset > maxOffset) playlistsScrollOffset = maxOffset;
            if (playlistsScrollOffset < 0) playlistsScrollOffset = 0;

            int endIdx = Math.min(pls.size(), playlistsScrollOffset + visibleCount);
            for (int i = playlistsScrollOffset; i < endIdx; i++) {
                SavedPlaylist pl = pls.get(i);
                int rowIdx = i - playlistsScrollOffset;
                int rowY = listY + (rowIdx * 34);
                boolean isFav = SavedPlaylistsManager.FAVORITES_ID.equals(pl.id);
                boolean hasTracks = !pl.tracks.isEmpty();

                int rightEdge = panelX + panelW - 14;
                if (!isFav) {
                    this.addRenderableWidget(Button.builder(Component.literal("§c✖"), b -> {
                        SavedPlaylistsManager.deletePlaylist(pl.id);
                        this.rebuildWidgets();
                    }).bounds(rightEdge - 22, rowY + 5, 22, 20).build());
                }

                this.addRenderableWidget(Button.builder(Component.literal("Abrir"), b -> {
                    selectedPlaylistId = pl.id;
                    isRenamingPlaylist = false;
                    playlistsScrollOffset = 0;
                    this.rebuildWidgets();
                }).bounds(rightEdge - 70, rowY + 5, 44, 20).build());

                Button mixBtn = Button.builder(Component.literal("§b🔀"), b -> {
                    List<TrackInfo> batch = pl.toTrackInfoList();
                    if (!batch.isEmpty()) {
                        ClientAudioEngine.getInstance().prefetchStreamUrl(batch.get(0).streamUrl());
                        ClientPlayNetworking.send(new AddBatchTracksPayload(batch, pl.name, true));
                        playlistStatusMessage = "§aSe añadieron " + batch.size() + " pistas en modo aleatorio desde \"" + pl.name + "\".";
                        playlistStatusTimestamp = System.currentTimeMillis();
                        this.rebuildWidgets();
                    }
                }).bounds(rightEdge - 98, rowY + 5, 24, 20).build();
                mixBtn.active = hasTracks;
                this.addRenderableWidget(mixBtn);

                Button loadBtn = Button.builder(Component.literal("§a▶ Play"), b -> {
                    List<TrackInfo> batch = pl.toTrackInfoList();
                    if (!batch.isEmpty()) {
                        ClientAudioEngine.getInstance().prefetchStreamUrl(batch.get(0).streamUrl());
                        ClientPlayNetworking.send(new AddBatchTracksPayload(batch, pl.name, false));
                        playlistStatusMessage = "§aSe añadieron " + batch.size() + " pistas a la cola desde \"" + pl.name + "\".";
                        playlistStatusTimestamp = System.currentTimeMillis();
                        this.rebuildWidgets();
                    }
                }).bounds(rightEdge - 156, rowY + 5, 54, 20).build();
                loadBtn.active = hasTracks;
                this.addRenderableWidget(loadBtn);
            }
        } else {
            // Vista Detalle de una Playlist Guardada con Banner e Inline Rename
            SavedPlaylist pl = SavedPlaylistsManager.getPlaylistById(selectedPlaylistId);
            if (pl == null) return;
            ClientAudioEngine engine = ClientAudioEngine.getInstance();
            boolean isFav = SavedPlaylistsManager.FAVORITES_ID.equals(pl.id);
            int topY = panelY + 24;

            this.addRenderableWidget(Button.builder(Component.literal("← Volver"), b -> {
                selectedPlaylistId = null;
                isRenamingPlaylist = false;
                playlistsScrollOffset = 0;
                this.rebuildWidgets();
            }).bounds(panelX + 10, topY, 58, 18).build());

            int curX = panelX + panelW - 10;
            if (!isFav) {
                this.addRenderableWidget(Button.builder(Component.literal(isRenamingPlaylist ? "§eEditando" : "Renombrar"), b -> {
                    isRenamingPlaylist = !isRenamingPlaylist;
                    this.rebuildWidgets();
                }).bounds(curX - 66, topY, 66, 18).build());
                curX -= 70;
            }

            Button addCurrentBtn = Button.builder(Component.literal("§e+ Actual"), b -> {
                TrackInfo cur = engine.getCurrentTrack();
                if (cur != null) {
                    boolean added = SavedPlaylistsManager.addTrackToPlaylist(pl.id, cur);
                    playlistStatusMessage = added
                            ? "§aPista actual añadida a \"" + pl.name + "\"."
                            : "§eLa pista actual ya forma parte de esta playlist.";
                    playlistStatusTimestamp = System.currentTimeMillis();
                    this.rebuildWidgets();
                }
            }).bounds(curX - 62, topY, 62, 18).build();
            addCurrentBtn.active = engine.getCurrentTrack() != null;
            this.addRenderableWidget(addCurrentBtn);
            curX -= 66;

            Button mixAllBtn = Button.builder(Component.literal("§b🔀 Mezclar"), b -> {
                List<TrackInfo> batch = pl.toTrackInfoList();
                if (!batch.isEmpty()) {
                    ClientPlayNetworking.send(new AddBatchTracksPayload(batch, pl.name, true));
                    playlistStatusMessage = "§aSe añadieron " + batch.size() + " pistas en modo aleatorio desde \"" + pl.name + "\".";
                    playlistStatusTimestamp = System.currentTimeMillis();
                    this.rebuildWidgets();
                }
            }).bounds(curX - 68, topY, 68, 18).build();
            mixAllBtn.active = !pl.tracks.isEmpty();
            this.addRenderableWidget(mixAllBtn);
            curX -= 72;

            Button loadAllBtn = Button.builder(Component.literal("§a▶ Play"), b -> {
                List<TrackInfo> batch = pl.toTrackInfoList();
                if (!batch.isEmpty()) {
                    engine.prefetchStreamUrl(batch.get(0).streamUrl());
                    ClientPlayNetworking.send(new AddBatchTracksPayload(batch, pl.name, false));
                    playlistStatusMessage = "§aSe añadieron " + batch.size() + " pistas a la cola desde \"" + pl.name + "\".";
                    playlistStatusTimestamp = System.currentTimeMillis();
                    this.rebuildWidgets();
                }
            }).bounds(curX - 56, topY, 56, 18).build();
            loadAllBtn.active = !pl.tracks.isEmpty();
            this.addRenderableWidget(loadAllBtn);

            if (isRenamingPlaylist && !isFav) {
                int bannerX = panelX + 10;
                int bannerY = panelY + 46;
                int bannerW = panelW - 20;

                this.renamePlaylistInput = new EditBox(
                        this.font,
                        bannerX + 14,
                        bannerY + 24,
                        bannerW - 144,
                        18,
                        Component.literal("Nuevo nombre...")
                );
                this.renamePlaylistInput.setMaxLength(40);
                this.renamePlaylistInput.setValue(pl.name);
                this.addRenderableWidget(this.renamePlaylistInput);
                this.setInitialFocus(this.renamePlaylistInput);

                this.addRenderableWidget(Button.builder(Component.literal("§aGuardar"), b -> {
                    confirmPlaylistRename();
                }).bounds(bannerX + bannerW - 124, bannerY + 24, 56, 18).build());

                this.addRenderableWidget(Button.builder(Component.literal("§cCancelar"), b -> {
                    isRenamingPlaylist = false;
                    this.rebuildWidgets();
                }).bounds(bannerX + bannerW - 64, bannerY + 24, 56, 18).build());
            }

            int listY = panelY + 114;
            int visibleCount = 3;
            int maxOffset = Math.max(0, pl.tracks.size() - visibleCount);
            if (playlistsScrollOffset > maxOffset) playlistsScrollOffset = maxOffset;
            if (playlistsScrollOffset < 0) playlistsScrollOffset = 0;

            int endIdx = Math.min(pl.tracks.size(), playlistsScrollOffset + visibleCount);
            for (int i = playlistsScrollOffset; i < endIdx; i++) {
                final int idx = i;
                SavedTrack st = pl.tracks.get(i);
                int rowIdx = i - playlistsScrollOffset;
                int rowY = listY + (rowIdx * 32);

                this.addRenderableWidget(Button.builder(Component.literal("§a+ Cola"), b -> {
                    ThumbnailTextureManager.getThumbnail(st.thumbnailUrl);
                    ClientPlayNetworking.send(new AddTrackPayload(
                            st.streamUrl,
                            st.title,
                            st.author,
                            st.thumbnailUrl,
                            st.durationMs
                    ));
                    b.setMessage(Component.literal("§e✔"));
                    b.active = false;
                }).bounds(panelX + panelW - 98, rowY + 4, 58, 20).build());

                this.addRenderableWidget(Button.builder(Component.literal("§c✖"), b -> {
                    SavedPlaylistsManager.removeTrackFromPlaylist(pl.id, idx);
                    this.rebuildWidgets();
                }).bounds(panelX + panelW - 36, rowY + 4, 22, 20).build());
            }
        }
    }

    private void confirmPlaylistRename() {
        if (selectedPlaylistId == null || this.renamePlaylistInput == null) return;
        String newName = this.renamePlaylistInput.getValue().trim();
        if (newName.isEmpty()) {
            playlistStatusMessage = "§eIntroduce un nombre válido para la playlist.";
            playlistStatusTimestamp = System.currentTimeMillis();
            return;
        }
        if (SavedPlaylistsManager.renamePlaylist(selectedPlaylistId, newName)) {
            SavedPlaylist updated = SavedPlaylistsManager.getPlaylistById(selectedPlaylistId);
            String finalName = updated != null ? updated.name : newName;
            isRenamingPlaylist = false;
            playlistStatusMessage = "§aNombre actualizado a \"" + finalName + "\".";
            playlistStatusTimestamp = System.currentTimeMillis();
            this.rebuildWidgets();
        }
    }

    private void createNewSavedPlaylist() {
        if (this.playlistNameInput == null) return;
        String name = this.playlistNameInput.getValue().trim();
        SavedPlaylist created = SavedPlaylistsManager.createPlaylist(name);
        this.playlistNameInput.setValue("");
        lastPlaylistNameInput = "";
        playlistStatusMessage = "§aPlaylist creada correctamente: \"" + created.name + "\".";
        playlistStatusTimestamp = System.currentTimeMillis();
        this.rebuildWidgets();
    }

    private String buildDistinctiveQueuePlaylistName(TrackInfo cur) {
        if (cur == null) return "Colección de cola";
        String author = cur.author() != null ? cur.author().trim() : "";
        boolean generic = author.isEmpty()
                || "YouTube".equalsIgnoreCase(author)
                || "Player".equalsIgnoreCase(author)
                || "Autoplay".equalsIgnoreCase(author);
        String basis = !generic ? "Selección • " + author : "Selección • " + cur.title();
        return basis.length() > 34 ? basis.substring(0, 32).trim() + "..." : basis;
    }

    /* ------------------------------------------------------------- */
    /* PESTAÑA 5: SETTINGS                                           */
    /* ------------------------------------------------------------- */
    private void initSettingsTab(int panelX, int panelY, int panelW) {
        int halfW = (panelW - 46) / 2;
        int leftX = panelX + 20;
        int rightX = leftX + halfW + 6;

        int scaledHudW = (int) (MusicHudOverlay.HUD_WIDTH * MusicHudOverlay.HUD_SCALE);
        int totalMenuH = (int) ((MusicHudOverlay.HUD_HEIGHT + 80) * MusicHudOverlay.HUD_SCALE);
        int maxW = Math.max(0, this.width - scaledHudW);
        int maxH = Math.max(0, this.height - totalMenuH);

        // Fila 1 (+36): HUD X | HUD Y
        this.addRenderableWidget(new AbstractSliderButton(
                leftX, panelY + 36, halfW, 18,
                Component.literal("HUD X: " + MusicHudOverlay.HUD_X + "px"),
                maxW > 0 ? Math.min(1.0, (double) MusicHudOverlay.HUD_X / (double) maxW) : 0.0
        ) {
            @Override
            protected void updateMessage() {
                int curMax = Math.max(0, YouTubeSearchScreen.this.width - (int) (MusicHudOverlay.HUD_WIDTH * MusicHudOverlay.HUD_SCALE));
                this.setMessage(Component.literal("HUD X: " + (int) (this.value * curMax) + "px"));
            }

            @Override
            protected void applyValue() {
                int curMax = Math.max(0, YouTubeSearchScreen.this.width - (int) (MusicHudOverlay.HUD_WIDTH * MusicHudOverlay.HUD_SCALE));
                MusicHudOverlay.HUD_X = (int) (this.value * curMax);
                PlayCraftConfig.save();
            }
        });

        this.addRenderableWidget(new AbstractSliderButton(
                rightX, panelY + 36, halfW, 18,
                Component.literal("HUD Y: " + MusicHudOverlay.HUD_Y + "px"),
                maxH > 0 ? Math.min(1.0, (double) MusicHudOverlay.HUD_Y / (double) maxH) : 0.0
        ) {
            @Override
            protected void updateMessage() {
                int curMax = Math.max(0, YouTubeSearchScreen.this.height - (int) ((MusicHudOverlay.HUD_HEIGHT + 80) * MusicHudOverlay.HUD_SCALE));
                this.setMessage(Component.literal("HUD Y: " + (int) (this.value * curMax) + "px"));
            }

            @Override
            protected void applyValue() {
                int curMax = Math.max(0, YouTubeSearchScreen.this.height - (int) ((MusicHudOverlay.HUD_HEIGHT + 80) * MusicHudOverlay.HUD_SCALE));
                MusicHudOverlay.HUD_Y = (int) (this.value * curMax);
                PlayCraftConfig.save();
            }
        });

        // Fila 2 (+58): Escala HUD | Opacidad
        this.addRenderableWidget(new AbstractSliderButton(
                leftX, panelY + 58, halfW, 18,
                Component.literal("Escala HUD (,): " + (int) (MusicHudOverlay.HUD_SCALE * 100) + "%"),
                (double) (MusicHudOverlay.HUD_SCALE - 0.70f) / 0.80f
        ) {
            @Override
            protected void updateMessage() {
                float scale = 0.70f + (float) (this.value * 0.80f);
                this.setMessage(Component.literal("Escala HUD (,): " + (int) (scale * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                MusicHudOverlay.HUD_SCALE = Math.round((0.70f + (float) (this.value * 0.80f)) * 100.0f) / 100.0f;
                int curMaxW = Math.max(0, YouTubeSearchScreen.this.width - (int) (MusicHudOverlay.HUD_WIDTH * MusicHudOverlay.HUD_SCALE));
                int curMaxH = Math.max(0, YouTubeSearchScreen.this.height - (int) ((MusicHudOverlay.HUD_HEIGHT + 80) * MusicHudOverlay.HUD_SCALE));
                if (MusicHudOverlay.HUD_X > curMaxW) MusicHudOverlay.HUD_X = curMaxW;
                if (MusicHudOverlay.HUD_Y > curMaxH) MusicHudOverlay.HUD_Y = curMaxH;
                PlayCraftConfig.save();
            }
        });

        this.addRenderableWidget(new AbstractSliderButton(
                rightX, panelY + 58, halfW, 18,
                Component.literal("Opacidad: " + (int) (MusicHudOverlay.HUD_OPACITY * 100) + "%"),
                (double) (MusicHudOverlay.HUD_OPACITY - 0.20f) / 0.80f
        ) {
            @Override
            protected void updateMessage() {
                float op = 0.20f + (float) (this.value * 0.80f);
                this.setMessage(Component.literal("Opacidad: " + (int) (op * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                MusicHudOverlay.HUD_OPACITY = Math.round((0.20f + (float) (this.value * 0.80f)) * 100.0f) / 100.0f;
                PlayCraftConfig.save();
            }
        });

        // Fila 3 (+80): Theme | HUD Flotante Visible/Oculto
        this.addRenderableWidget(Button.builder(
                Component.literal("Theme: " + MusicHudOverlay.CURRENT_THEME.displayName),
                b -> {
                    MusicHudOverlay.ColorTheme[] themes = MusicHudOverlay.ColorTheme.values();
                    int nextIdx = (MusicHudOverlay.CURRENT_THEME.ordinal() + 1) % themes.length;
                    MusicHudOverlay.CURRENT_THEME = themes[nextIdx];
                    ThumbnailTextureManager.clearCache();
                    PlayCraftConfig.save();
                    b.setMessage(Component.literal("Theme: " + MusicHudOverlay.CURRENT_THEME.displayName));
                }
        ).bounds(leftX, panelY + 80, halfW, 18).build());

        this.addRenderableWidget(Button.builder(
                Component.literal(MusicHudOverlay.HUD_ENABLED ? "HUD Flotante: §aVisible" : "HUD Flotante: §cOculto"),
                b -> {
                    MusicHudOverlay.HUD_ENABLED = !MusicHudOverlay.HUD_ENABLED;
                    PlayCraftConfig.save();
                    this.rebuildWidgets();
                }
        ).bounds(rightX, panelY + 80, halfW, 18).build());

        // Fila 4 (+102): Música Vanilla | Avisos Activados/Desactivados
        this.addRenderableWidget(Button.builder(
                Component.literal(PlayCraftConfig.MUTE_MINECRAFT_MUSIC ? "Música MC: §aSilenciar" : "Música MC: §7Normal"),
                b -> {
                    PlayCraftConfig.MUTE_MINECRAFT_MUSIC = !PlayCraftConfig.MUTE_MINECRAFT_MUSIC;
                    PlayCraftConfig.save();
                    this.rebuildWidgets();
                }
        ).bounds(leftX, panelY + 102, halfW, 18).build());

        this.addRenderableWidget(Button.builder(
                Component.literal(MusicHudOverlay.NOTIFICATIONS_ENABLED ? "Avisos: §aActivados" : "Avisos: §cDesactivados"),
                b -> {
                    MusicHudOverlay.NOTIFICATIONS_ENABLED = !MusicHudOverlay.NOTIFICATIONS_ENABLED;
                    if (MusicHudOverlay.NOTIFICATIONS_ENABLED) {
                        MusicHudOverlay.showNotification("§6[PlayCraftMusic] §aAvisos de actividad activados");
                    }
                    PlayCraftConfig.save();
                    this.rebuildWidgets();
                }
        ).bounds(rightX, panelY + 102, halfW, 18).build());

        // Fila 5 (+135): Aviso X | Aviso Y
        int maxNotifX = Math.max(0, this.width - 180);
        int maxNotifY = Math.max(0, this.height - 24);

        AbstractSliderButton notifXSlider = new AbstractSliderButton(
                leftX, panelY + 135, halfW, 18,
                Component.literal("Aviso X: " + MusicHudOverlay.NOTIFICATION_X + "px"),
                maxNotifX > 0 ? Math.min(1.0, (double) MusicHudOverlay.NOTIFICATION_X / (double) maxNotifX) : 0.0
        ) {
            @Override
            protected void updateMessage() {
                int curMax = Math.max(0, YouTubeSearchScreen.this.width - 180);
                this.setMessage(Component.literal("Aviso X: " + (int) (this.value * curMax) + "px"));
            }

            @Override
            protected void applyValue() {
                int curMax = Math.max(0, YouTubeSearchScreen.this.width - 180);
                MusicHudOverlay.NOTIFICATION_X = (int) (this.value * curMax);
                PlayCraftConfig.save();
            }
        };
        notifXSlider.active = MusicHudOverlay.NOTIFICATIONS_ENABLED;
        this.addRenderableWidget(notifXSlider);

        AbstractSliderButton notifYSlider = new AbstractSliderButton(
                rightX, panelY + 135, halfW, 18,
                Component.literal("Aviso Y: " + MusicHudOverlay.NOTIFICATION_Y + "px"),
                maxNotifY > 0 ? Math.min(1.0, (double) MusicHudOverlay.NOTIFICATION_Y / (double) maxNotifY) : 0.0
        ) {
            @Override
            protected void updateMessage() {
                int curMax = Math.max(0, YouTubeSearchScreen.this.height - 24);
                this.setMessage(Component.literal("Aviso Y: " + (int) (this.value * curMax) + "px"));
            }

            @Override
            protected void applyValue() {
                int curMax = Math.max(0, YouTubeSearchScreen.this.height - 24);
                MusicHudOverlay.NOTIFICATION_Y = (int) (this.value * curMax);
                PlayCraftConfig.save();
            }
        };
        notifYSlider.active = MusicHudOverlay.NOTIFICATIONS_ENABLED;
        this.addRenderableWidget(notifYSlider);

        // Fila 6 (+157): Tamaño de Avisos
        AbstractSliderButton notifScaleSlider = new AbstractSliderButton(
                leftX, panelY + 157, panelW - 40, 18,
                Component.literal("Tamaño de Avisos: " + (int) (MusicHudOverlay.NOTIFICATION_SCALE * 100) + "%"),
                (double) (MusicHudOverlay.NOTIFICATION_SCALE - 0.60f) / 0.90f
        ) {
            @Override
            protected void updateMessage() {
                float sc = 0.60f + (float) (this.value * 0.90f);
                this.setMessage(Component.literal("Tamaño de Avisos: " + (int) (sc * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                MusicHudOverlay.NOTIFICATION_SCALE = Math.round((0.60f + (float) (this.value * 0.90f)) * 100.0f) / 100.0f;
                PlayCraftConfig.save();
            }
        };
        notifScaleSlider.active = MusicHudOverlay.NOTIFICATIONS_ENABLED;
        this.addRenderableWidget(notifScaleSlider);

        // Fila 7 (+181): Buscar actualizaciones de yt-dlp | Reset to Defaults
        Button updateYtdlpBtn = Button.builder(
                Component.literal(isCheckingUpdates ? "§eVerificando..." : "§bBuscar actualizaciones"),
                b -> {
                    if (isCheckingUpdates) return;
                    isCheckingUpdates = true;
                    ytdlpUpdateStatus = "§eConsultando última versión de yt-dlp...";
                    ytdlpUpdateHint = "";
                    this.rebuildWidgets();
                    BinaryLocator.checkYtdlpUpdateAsync().thenAccept(res -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc != null) {
                            mc.execute(() -> {
                                isCheckingUpdates = false;
                                ytdlpUpdateStatus = res.statusLine();
                                ytdlpUpdateHint = res.hintLine();
                                if (mc.gui.screen() instanceof YouTubeSearchScreen activeScreen) {
                                    activeScreen.rebuildWidgets();
                                }
                            });
                        }
                    });
                }
        ).bounds(leftX, panelY + 181, halfW, 18).build();
        updateYtdlpBtn.active = !isCheckingUpdates;
        this.addRenderableWidget(updateYtdlpBtn);

        this.addRenderableWidget(Button.builder(Component.literal("Reset to Defaults"), b -> {
            MusicHudOverlay.HUD_X = 12;
            MusicHudOverlay.HUD_Y = 12;
            MusicHudOverlay.HUD_WIDTH = 250;
            MusicHudOverlay.HUD_SCALE = 1.0f;
            MusicHudOverlay.HUD_OPACITY = 0.88f;
            MusicHudOverlay.HUD_ENABLED = true;
            PlayCraftConfig.MUTE_MINECRAFT_MUSIC = true;
            MusicHudOverlay.CURRENT_THEME = MusicHudOverlay.ColorTheme.GOLD;
            MusicHudOverlay.NOTIFICATIONS_ENABLED = false;
            MusicHudOverlay.NOTIFICATION_X = 12;
            MusicHudOverlay.NOTIFICATION_Y = 76;
            MusicHudOverlay.NOTIFICATION_SCALE = 0.9f;
            ThumbnailTextureManager.clearCache();
            PlayCraftConfig.save();
            this.rebuildWidgets();
        }).bounds(rightX, panelY + 181, halfW, 18).build());
    }

    /* ------------------------------------------------------------- */
    /* SINCRONIZACIÓN INTELIGENTE (SIN REBUILD INNECESARIOS)         */
    /* ------------------------------------------------------------- */
    private String computeSyncSignature() {
        ClientAudioEngine engine = ClientAudioEngine.getInstance();
        TrackInfo cur = engine.getCurrentTrack();
        StringBuilder sb = new StringBuilder();
        sb.append(cur != null ? cur.id() : "null").append('|')
                .append(engine.isPlaying()).append('|')
                .append(engine.getPlaybackStatus()).append('|')
                .append(engine.isAutoplayEnabled()).append('|')
                .append(engine.getLoopModeOrdinal()).append('|')
                .append(engine.getAutoplayNextTrack() != null ? engine.getAutoplayNextTrack().streamUrl() : "none").append('|')
                .append(engine.getHistory().size()).append('|');
        List<TrackInfo> q = engine.getQueue();
        sb.append(q.size()).append(':');
        for (TrackInfo t : q) {
            sb.append(t.id()).append(',');
        }
        return sb.toString();
    }

    public void onSyncUpdate() {
        if (shouldShowChangelogModal()) {
            return;
        }
        String sig = computeSyncSignature();
        if (sig.equals(lastSyncSignature)) {
            return;
        }
        lastSyncSignature = sig;
        if (currentTab == Tab.NOW_PLAYING || currentTab == Tab.QUEUE
                || (currentTab == Tab.PLAYLISTS && selectedPlaylistId != null && !isRenamingPlaylist)) {
            this.rebuildWidgets();
        }
    }

    /* ------------------------------------------------------------- */
    /* RENDERIZADO VISUAL                                            */
    /* ------------------------------------------------------------- */
    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Sin desenfoque gaussiano (blur) para mantener la pantalla nítida.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        int panelW = getPanelW();
        int panelX = getPanelX(panelW);
        int panelY = getPanelY();

        context.fill(panelX, panelY, panelX + panelW, panelY + PANEL_HEIGHT, 0xEE121217);
        context.outline(panelX, panelY, panelW, PANEL_HEIGHT, MusicHudOverlay.CURRENT_THEME.borderColor);

        if (shouldShowChangelogModal()) {
            renderChangelogModal(context, panelX, panelY, panelW);
            super.extractRenderState(context, mouseX, mouseY, delta);
            return;
        }

        switch (currentTab) {
            case NOW_PLAYING -> renderNowPlaying(context, panelX, panelY, panelW);
            case SEARCH -> renderSearch(context, panelX, panelY, panelW, mouseX, mouseY);
            case QUEUE -> renderQueue(context, panelX, panelY, panelW, mouseX, mouseY);
            case PLAYLISTS -> renderPlaylists(context, panelX, panelY, panelW, mouseX, mouseY);
            case SETTINGS -> renderSettings(context, panelX, panelY, panelW);
        }

        if (!BinaryLocator.hasRequiredBinaries()) {
            renderMissingBinariesWarning(context, panelX, panelY, panelW);
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private void renderChangelogModal(GuiGraphicsExtractor context, int panelX, int panelY, int panelW) {
        int centerX = panelX + (panelW / 2);
        context.centeredText(this.font, "§6§lNovedades en PlayCraftMusic v" + PlayCraftConfig.CURRENT_MOD_VERSION, centerX, panelY + 11, 0xFFFFFFFF);
        context.centeredText(this.font, "§7Resumen de mejoras y funciones incorporadas", centerX, panelY + 23, 0xFFAAAAAA);
        context.fill(panelX + 14, panelY + 36, panelX + panelW - 14, panelY + 37, MusicHudOverlay.CURRENT_THEME.borderColor);

        String[][] items = {
                {"§b• Motor de Audio v2", "§7Búfer ampliado, watchdog inicial, respaldo vía pipe y botón 'Reconectar'."},
                {"§e• Playlists e Importación", "§7Importa playlists desde el cliente y crea o renombra colecciones locales."},
                {"§d• Reproducción Automática", "§7Sugiere y encadena pistas relacionadas tipo YouTube Mix al terminar la cola."},
                {"§a• Controles e Historial", "§7Modos Loop (1 pista / cola), Shuffle, Favoritos e Historial reciente."},
                {"§6• Ajustes Integrados", "§7Silencio automático de música vanilla y verificador de actualizaciones de yt-dlp."}
        };

        int itemY = panelY + 44;
        for (String[] entry : items) {
            context.fill(panelX + 16, itemY, panelX + panelW - 16, itemY + 28, 0xAA181822);
            context.outline(panelX + 16, itemY, panelW - 32, 28, 0xFF282838);
            context.text(this.font, entry[0], panelX + 24, itemY + 4, 0xFFFFFFFF);
            context.text(this.font, entry[1], panelX + 24, itemY + 15, 0xFFBBBBBB);
            itemY += 31;
        }

        context.centeredText(this.font, "§8Este aviso se muestra una sola vez tras actualizar el mod.", centerX, panelY + 204, 0xFF888888);
    }

    private void renderMissingBinariesWarning(GuiGraphicsExtractor context, int panelX, int panelY, int panelW) {
        int bannerH = 18;
        int bannerY = panelY + PANEL_HEIGHT - bannerH;
        context.fill(panelX, bannerY, panelX + panelW, bannerY + bannerH, 0xDD7A1111);
        context.outline(panelX, bannerY, panelW, bannerH, 0xFFFF4444);

        boolean ytdlpOk = BinaryLocator.isYtdlpAvailable();
        boolean ffmpegOk = BinaryLocator.isFfmpegAvailable();
        String missing = (!ytdlpOk && !ffmpegOk) ? "yt-dlp y ffmpeg" : (!ytdlpOk ? "yt-dlp" : "ffmpeg");
        context.centeredText(this.font, "§c⚠ Falta " + missing + " en tu sistema. Instálalos para reproducir música.", panelX + (panelW / 2), bannerY + 5, 0xFFFFFFFF);
    }

    private void renderNowPlaying(GuiGraphicsExtractor context, int panelX, int panelY, int panelW) {
        ClientAudioEngine engine = ClientAudioEngine.getInstance();
        TrackInfo track = engine.getCurrentTrack();
        int cardY = panelY + 30;
        int thumbX = panelX + 14;
        int discSize = 70;

        if (track != null) {
            Identifier thumbId = ThumbnailTextureManager.getThumbnail(track.thumbnailUrl());
            int centerX = thumbX + discSize / 2;
            int centerY = cardY + discSize / 2;
            int r = discSize / 2;

            context.pose().pushMatrix();
            context.pose().translate(centerX, centerY);
            context.pose().rotate((float) Math.toRadians(MusicHudOverlay.getDiscRotationAngle()));
            if (thumbId != null) {
                context.blit(RenderPipelines.GUI_TEXTURED, thumbId, -r, -r, 0.0f, 0.0f, discSize, discSize, discSize, discSize);
            }
            context.pose().popMatrix();

            int metaX = thumbX + discSize + 16;
            String title = track.title().length() > 28 ? track.title().substring(0, 26) + "..." : track.title();
            context.text(this.font, "§f§l" + title, metaX, cardY + 6, 0xFFFFFFFF);
            String author = track.author().length() > 26 ? track.author().substring(0, 24) + "..." : track.author();
            context.text(this.font, "§7Artista: §e" + author, metaX, cardY + 22, 0xFFCCCCCC);
            context.text(this.font, "§7Origen: §fYouTube", metaX, cardY + 36, 0xFFCCCCCC);

            PlaybackStatus st = engine.getPlaybackStatus();
            String statusText = switch (st) {
                case LOADING -> "§eCargando flujo de audio...";
                case RECONNECTING -> "§6Reconectando flujo de audio...";
                case ERROR -> "§cReintentando conexión (usa 'Reconectar' para forzar)";
                case PAUSED -> "§6❚❚ En pausa";
                case PLAYING -> "§a▶ Reproduciendo en vivo";
                default -> engine.isPlaying() ? "§a▶ Reproduciendo en vivo" : "§6❚❚ En pausa";
            };
            context.text(this.font, statusText, metaX, cardY + 50, 0xFF88FFAA);
        } else {
            context.fill(thumbX, cardY, thumbX + discSize, cardY + discSize, 0xFF1C1C24);
            context.centeredText(this.font, "♫", thumbX + (discSize / 2), cardY + 30, 0xFF666666);
            context.text(this.font, "§8Ninguna canción reproduciéndose actualmente", thumbX + discSize + 16, cardY + 28, 0xFF888888);
        }

        // Pie de resumen: cola / autoplay siguiente
        String footer;
        if (!engine.getQueue().isEmpty()) {
            TrackInfo next = engine.getQueue().get(0);
            String shortNext = next.title().length() > 34 ? next.title().substring(0, 32) + "..." : next.title();
            footer = "§7Siguiente en cola (" + engine.getQueue().size() + "): §f" + shortNext;
        } else if (engine.isAutoplayEnabled() && engine.getCurrentTrack() != null) {
            TrackInfo auto = engine.getAutoplayNextTrack();
            if (auto != null) {
                String shortAuto = auto.title().length() > 32 ? auto.title().substring(0, 30) + "..." : auto.title();
                footer = "§d∞ Autoplay siguiente: §f" + shortAuto;
            } else {
                footer = "§d∞ Autoplay activo — buscando sugerencia relacionada...";
            }
        } else {
            footer = "§8Cola vacía • Usa Search o Playlists para añadir canciones";
        }
        context.centeredText(this.font, footer, panelX + (panelW / 2), panelY + 228, 0xFFAAAAAA);
    }

    private void renderSearch(GuiGraphicsExtractor context, int panelX, int panelY, int panelW, int mouseX, int mouseY) {
        if (isSearching) {
            int centerX = panelX + (panelW / 2);
            int baseY = panelY + 132;
            int numBars = 7;
            int barW = 5;
            int gap = 4;
            int startX = centerX - (((numBars * barW) + ((numBars - 1) * gap)) / 2);

            double time = System.currentTimeMillis() / 140.0;
            int mainColor = MusicHudOverlay.CURRENT_THEME.borderColor;
            int accentColor = MusicHudOverlay.CURRENT_THEME.accentColor;

            for (int i = 0; i < numBars; i++) {
                double wave = Math.sin(time + i * 0.85) * 0.5 + 0.5;
                double wave2 = Math.cos(time * 0.7 - i * 0.5) * 0.5 + 0.5;
                int barH = 6 + (int) Math.round(((wave * 0.65) + (wave2 * 0.35)) * 26.0);
                int bx = startX + i * (barW + gap);
                int by = baseY - barH;
                context.fill(bx, baseY - 32, bx + barW, baseY, 0x332A2A38);
                context.fill(bx, by, bx + barW, baseY, mainColor);
                context.fill(bx, by, bx + barW, Math.min(baseY, by + 3), accentColor);
            }

            context.centeredText(this.font, isSearchMode ? "§eBuscando en YouTube..." : "§eResolviendo enlace / playlist...", centerX, baseY + 10, 0xFFFFFF55);
            return;
        }

        if (isSearchMode) {
            int listY = panelY + 56;
            int visibleCount = 4;
            int maxOffset = Math.max(0, searchResults.size() - visibleCount);
            if (searchScrollOffset > maxOffset) searchScrollOffset = maxOffset;

            int endIdx = Math.min(searchResults.size(), searchScrollOffset + visibleCount);
            for (int i = searchScrollOffset; i < endIdx; i++) {
                TrackInfo item = searchResults.get(i);
                int rowIdx = i - searchScrollOffset;
                int rowY = listY + (rowIdx * 34);

                context.fill(panelX + 10, rowY, panelX + panelW - 12, rowY + 30, 0xFF181820);
                context.outline(panelX + 10, rowY, panelW - 22, 30, 0xFF2A2A38);

                String title = item.title().length() > 35 ? item.title().substring(0, 33) + "..." : item.title();
                context.text(this.font, "§f" + title, panelX + 16, rowY + 5, 0xFFFFFFFF);
                long sec = item.durationMs() / 1000;
                context.text(this.font, "§7" + item.author() + " §8• §e" + String.format("%02d:%02d", sec / 60, sec % 60), panelX + 16, rowY + 17, 0xFFAAAAAA);
            }

            if (maxOffset > 0) {
                drawScrollbar(context, panelX + panelW - 8, listY, visibleCount * 34 - 4, visibleCount, searchResults.size(), searchScrollOffset, isDraggingSearchScrollbar, mouseX, mouseY);
            }

            if (searchResults.isEmpty()) {
                context.centeredText(this.font, "§7Introduce un término de búsqueda para ver resultados aquí", panelX + (panelW / 2), panelY + 122, 0xFF777777);
            }
        } else {
            int centerX = panelX + (panelW / 2);
            context.centeredText(this.font, "§eModo Enlace Directo (Canción o Playlist)", centerX, panelY + 105, 0xFFFFFFDD);

            if (!linkErrorMessage.isEmpty() && (System.currentTimeMillis() - linkErrorTimestamp < 6000)) {
                context.centeredText(this.font, "§c⚠ " + linkErrorMessage, centerX, panelY + 125, 0xFFFF5555);
            } else if (!linkSuccessMessage.isEmpty() && (System.currentTimeMillis() - linkSuccessTimestamp < 6000)) {
                int boxW = Math.min(panelW - 40, this.font.width(linkSuccessMessage) + 24);
                int boxX = centerX - (boxW / 2);
                context.fill(boxX, panelY + 119, boxX + boxW, panelY + 139, 0xCC102A18);
                context.outline(boxX, panelY + 119, boxW, 20, 0xFF00E676);
                context.centeredText(this.font, "§a" + linkSuccessMessage, centerX, panelY + 125, 0xFF55FF55);
            } else {
                context.centeredText(this.font, "§7Pega un enlace de YouTube (video o playlist) arriba y pulsa §a+ Add", centerX, panelY + 125, 0xFFAAAAAA);
            }

            context.centeredText(this.font, "§8Las playlists se cargan desde tu cliente para evitar bloqueos del servidor", centerX, panelY + 150, 0xFF777777);
        }
    }

    private void renderQueue(GuiGraphicsExtractor context, int panelX, int panelY, int panelW, int mouseX, int mouseY) {
        ClientAudioEngine engine = ClientAudioEngine.getInstance();
        int listY = panelY + 50;
        int visibleCount = 5;

        if (!showHistorySubTab) {
            List<TrackInfo> queue = engine.getQueue();
            if (queue.isEmpty()) {
                context.centeredText(this.font, "§7La cola de reproducción está vacía.", panelX + (panelW / 2), panelY + 112, 0xFF888888);
                context.centeredText(this.font, "§8Añade canciones desde 'Search' o 'Playlists'.", panelX + (panelW / 2), panelY + 128, 0xFF666666);
            } else {
                int maxOffset = Math.max(0, queue.size() - visibleCount);
                if (queueScrollOffset > maxOffset) queueScrollOffset = maxOffset;

                int endIdx = Math.min(queue.size(), queueScrollOffset + visibleCount);
                for (int i = queueScrollOffset; i < endIdx; i++) {
                    TrackInfo item = queue.get(i);
                    int rowIdx = i - queueScrollOffset;
                    int rowY = listY + (rowIdx * 32);

                    context.fill(panelX + 10, rowY, panelX + panelW - 12, rowY + 28, 0xFF181820);
                    context.outline(panelX + 10, rowY, panelW - 22, 28, 0xFF282836);

                    String title = item.title().length() > 32 ? item.title().substring(0, 30) + "..." : item.title();
                    context.text(this.font, "§6" + (i + 1) + ". §f" + title, panelX + 16, rowY + 5, 0xFFFFFFFF);
                    context.text(this.font, "§7" + item.author(), panelX + 16, rowY + 15, 0xFF888888);
                }

                if (maxOffset > 0) {
                    drawScrollbar(context, panelX + panelW - 8, listY, visibleCount * 32 - 4, visibleCount, queue.size(), queueScrollOffset, isDraggingQueueScrollbar, mouseX, mouseY);
                }
            }

            if (!queueStatusMessage.isEmpty() && (System.currentTimeMillis() - queueStatusTimestamp < 4500L)) {
                context.centeredText(this.font, queueStatusMessage, panelX + (panelW / 2), panelY + 224, 0xFF55FF55);
            } else if (queue.isEmpty() && engine.isAutoplayEnabled() && engine.getCurrentTrack() != null) {
                int boxY = panelY + 216;
                context.fill(panelX + 10, boxY, panelX + panelW - 10, boxY + 26, 0xFF1B1526);
                context.outline(panelX + 10, boxY, panelW - 20, 26, 0xFFD500F9);

                TrackInfo autoNext = engine.getAutoplayNextTrack();
                if (engine.getLoopModeOrdinal() == 1) {
                    context.text(this.font, "§b🔁 Repetición de canción actual activa", panelX + 18, boxY + 9, 0xFFAAEEFF);
                } else if (autoNext != null) {
                    String shortT = autoNext.title().length() > 34 ? autoNext.title().substring(0, 32) + "..." : autoNext.title();
                    context.text(this.font, "§d∞ Siguiente (Autoplay): §f" + shortT, panelX + 18, boxY + 9, 0xFFFFFFFF);
                } else {
                    context.text(this.font, "§d∞ Autoplay: §7Buscando siguiente canción sugerida...", panelX + 18, boxY + 9, 0xFFCCCCCC);
                }
            }
        } else {
            List<TrackInfo> history = engine.getHistory();
            if (history.isEmpty()) {
                context.centeredText(this.font, "§7El historial reciente está vacío.", panelX + (panelW / 2), panelY + 118, 0xFF888888);
                return;
            }
            int maxOffset = Math.max(0, history.size() - visibleCount);
            if (historyScrollOffset > maxOffset) historyScrollOffset = maxOffset;

            int endIdx = Math.min(history.size(), historyScrollOffset + visibleCount);
            for (int i = historyScrollOffset; i < endIdx; i++) {
                TrackInfo item = history.get(i);
                int rowIdx = i - historyScrollOffset;
                int rowY = listY + (rowIdx * 32);

                context.fill(panelX + 10, rowY, panelX + panelW - 12, rowY + 28, 0xFF181820);
                context.outline(panelX + 10, rowY, panelW - 22, 28, 0xFF282836);

                String title = item.title().length() > 32 ? item.title().substring(0, 30) + "..." : item.title();
                context.text(this.font, "§7" + (i + 1) + ". §f" + title, panelX + 16, rowY + 5, 0xFFFFFFFF);
                context.text(this.font, "§8" + item.author(), panelX + 16, rowY + 15, 0xFF888888);
            }

            if (maxOffset > 0) {
                drawScrollbar(context, panelX + panelW - 8, listY, visibleCount * 32 - 4, visibleCount, history.size(), historyScrollOffset, isDraggingQueueScrollbar, mouseX, mouseY);
            }
        }
    }

    private void renderPlaylists(GuiGraphicsExtractor context, int panelX, int panelY, int panelW, int mouseX, int mouseY) {
        if (selectedPlaylistId == null) {
            List<SavedPlaylist> pls = SavedPlaylistsManager.getPlaylists();
            int listY = panelY + 56;
            int visibleCount = 5;
            int maxOffset = Math.max(0, pls.size() - visibleCount);
            if (playlistsScrollOffset > maxOffset) playlistsScrollOffset = maxOffset;

            int endIdx = Math.min(pls.size(), playlistsScrollOffset + visibleCount);
            for (int i = playlistsScrollOffset; i < endIdx; i++) {
                SavedPlaylist pl = pls.get(i);
                int rowIdx = i - playlistsScrollOffset;
                int rowY = listY + (rowIdx * 34);
                boolean isFav = SavedPlaylistsManager.FAVORITES_ID.equals(pl.id);

                context.fill(panelX + 10, rowY, panelX + panelW - 12, rowY + 30, isFav ? 0xFF201D14 : 0xFF181820);
                context.outline(panelX + 10, rowY, panelW - 22, 30, isFav ? 0xFF665522 : 0xFF2A2A38);

                int thumbX = panelX + 12;
                int thumbY = rowY + 2;
                int thumbW = 46;
                int thumbH = 26;
                Identifier thumbId = !pl.tracks.isEmpty()
                        ? ThumbnailTextureManager.getFullThumbnail(pl.tracks.get(0).thumbnailUrl)
                        : null;
                if (thumbId != null) {
                    context.blit(RenderPipelines.GUI_TEXTURED, thumbId, thumbX, thumbY, 0.0f, 0.0f, thumbW, thumbH, thumbW, thumbH);
                    context.outline(thumbX, thumbY, thumbW, thumbH, isFav ? 0xFFCC9933 : 0xFF3A3A4E);
                } else {
                    context.fill(thumbX, thumbY, thumbX + thumbW, thumbY + thumbH, 0xFF12121A);
                    context.outline(thumbX, thumbY, thumbW, thumbH, 0xFF2E2E40);
                    context.centeredText(this.font, isFav ? "§e★" : "§b♪", thumbX + (thumbW / 2), thumbY + 9, 0xFFFFFFFF);
                }

                int textX = panelX + 64;
                String prefix = isFav ? "§e★ " : "§b♪ ";
                int maxTitleW = Math.max(50, panelW - 250);
                String trimmedName = this.font.plainSubstrByWidth(pl.name, maxTitleW);
                if (trimmedName.length() < pl.name.length()) {
                    String shorter = this.font.plainSubstrByWidth(pl.name, Math.max(10, maxTitleW - this.font.width("...")));
                    trimmedName = shorter + "...";
                }
                context.text(this.font, prefix + "§f" + trimmedName, textX, rowY + 5, 0xFFFFFFFF);

                long totalMs = 0L;
                for (SavedTrack st : pl.tracks) {
                    if (st != null && st.durationMs > 0) totalMs += st.durationMs;
                }
                String trackWord = pl.tracks.size() == 1 ? " pista" : " pistas";
                String metaText = totalMs > 0
                        ? "§7" + pl.tracks.size() + trackWord + " §8• §e" + formatTotalDuration(totalMs)
                        : "§7" + pl.tracks.size() + trackWord;
                context.text(this.font, metaText, textX, rowY + 17, 0xFFAAAAAA);
            }

            if (maxOffset > 0) {
                drawScrollbar(context, panelX + panelW - 8, listY, visibleCount * 34 - 4, visibleCount, pls.size(), playlistsScrollOffset, isDraggingPlaylistsScrollbar, mouseX, mouseY);
            }
        } else {
            SavedPlaylist pl = SavedPlaylistsManager.getPlaylistById(selectedPlaylistId);
            if (pl == null) return;
            boolean isFav = SavedPlaylistsManager.FAVORITES_ID.equals(pl.id);

            int bannerX = panelX + 10;
            int bannerY = panelY + 46;
            int bannerW = panelW - 20;
            int bannerH = 62;

            Identifier bannerId = !pl.tracks.isEmpty()
                    ? ThumbnailTextureManager.getPlaylistBanner(pl.tracks.get(0).thumbnailUrl)
                    : null;
            if (bannerId != null) {
                context.blit(RenderPipelines.GUI_TEXTURED, bannerId, bannerX, bannerY, 0.0f, 0.0f, bannerW, bannerH, bannerW, bannerH);
            } else {
                context.fill(bannerX, bannerY, bannerX + bannerW, bannerY + bannerH, 0xFF1A1A24);
            }
            context.outline(bannerX, bannerY, bannerW, bannerH, MusicHudOverlay.CURRENT_THEME.borderColor);

            int infoX = bannerX + 14;
            if (isRenamingPlaylist) {
                context.text(this.font, "§eEditar nombre de la playlist", infoX, bannerY + 9, 0xFFFFFFFF);
            } else {
                context.text(this.font, isFav ? "§e★ PLAYLIST DE FAVORITOS" : "§bPLAYLIST PERSONALIZADA", infoX, bannerY + 9, 0xFFFFFFFF);
                String displayTitle = pl.name.length() > 46 ? pl.name.substring(0, 44) + "..." : pl.name;
                context.text(this.font, "§f§l" + displayTitle, infoX, bannerY + 23, 0xFFFFFFFF);
                long totalMs = 0L;
                for (SavedTrack st : pl.tracks) {
                    if (st != null && st.durationMs > 0) totalMs += st.durationMs;
                }
                String trackWord = pl.tracks.size() == 1 ? " pista" : " pistas";
                context.text(this.font, "§7" + pl.tracks.size() + trackWord + " §8• §7Duración total: §e" + formatTotalDuration(totalMs), infoX, bannerY + 39, 0xFFCCCCCC);
            }

            if (pl.tracks.isEmpty()) {
                context.centeredText(this.font, "§7Esta playlist aún no contiene pistas.", panelX + (panelW / 2), panelY + 148, 0xFF888888);
                context.centeredText(this.font, "§8Usa ★ en Search / Now Playing o '+ Actual' para añadir música.", panelX + (panelW / 2), panelY + 164, 0xFF666666);
            } else {
                int listY = panelY + 114;
                int visibleCount = 3;
                int maxOffset = Math.max(0, pl.tracks.size() - visibleCount);
                if (playlistsScrollOffset > maxOffset) playlistsScrollOffset = maxOffset;

                int endIdx = Math.min(pl.tracks.size(), playlistsScrollOffset + visibleCount);
                for (int i = playlistsScrollOffset; i < endIdx; i++) {
                    SavedTrack st = pl.tracks.get(i);
                    int rowIdx = i - playlistsScrollOffset;
                    int rowY = listY + (rowIdx * 32);

                    context.fill(panelX + 10, rowY, panelX + panelW - 12, rowY + 28, 0xFF181820);
                    context.outline(panelX + 10, rowY, panelW - 22, 28, 0xFF2A2A38);

                    String title = st.title.length() > 35 ? st.title.substring(0, 33) + "..." : st.title;
                    context.text(this.font, "§6" + (i + 1) + ". §f" + title, panelX + 16, rowY + 5, 0xFFFFFFFF);
                    context.text(this.font, "§7" + st.author, panelX + 16, rowY + 16, 0xFF999999);
                }

                if (maxOffset > 0) {
                    drawScrollbar(context, panelX + panelW - 8, listY, visibleCount * 32 - 4, visibleCount, pl.tracks.size(), playlistsScrollOffset, isDraggingPlaylistsScrollbar, mouseX, mouseY);
                }
            }
        }

        if (!playlistStatusMessage.isEmpty() && (System.currentTimeMillis() - playlistStatusTimestamp < 4500L)) {
            int msgY = (selectedPlaylistId == null) ? panelY + 230 : panelY + 216;
            context.centeredText(this.font, playlistStatusMessage, panelX + (panelW / 2), msgY, 0xFF55FF55);
        }
    }

    private void renderSettings(GuiGraphicsExtractor context, int panelX, int panelY, int panelW) {
        context.centeredText(this.font, "§ePersonalización y Sistema", panelX + (panelW / 2), panelY + 25, 0xFFFFFFDD);
        context.centeredText(this.font, "§eAvisos de Actividad del Reproductor", panelX + (panelW / 2), panelY + 124, 0xFFFFFFDD);

        boolean ytdlpOk = BinaryLocator.isYtdlpAvailable();
        boolean ffmpegOk = BinaryLocator.isFfmpegAvailable();
        String ytdlpVer = ytdlpOk ? BinaryLocator.getYtdlpVersion() : "";
        String ytdlpLabel = ytdlpOk
                ? "§ayt-dlp: OK" + (ytdlpVer.isEmpty() ? "" : " (" + ytdlpVer + ")")
                : "§cyt-dlp: No detectado";
        String ffmpegLabel = ffmpegOk ? "§affmpeg: OK" : "§cffmpeg: No detectado";
        context.centeredText(this.font, "§7Dependencias: " + ytdlpLabel + " §8| " + ffmpegLabel, panelX + (panelW / 2), panelY + 206, 0xFFAAAAAA);

        if (!ytdlpUpdateStatus.isEmpty()) {
            context.centeredText(this.font, ytdlpUpdateStatus, panelX + (panelW / 2), panelY + 219, 0xFFDDDDDD);
        }
        if (!ytdlpUpdateHint.isEmpty()) {
            context.centeredText(this.font, ytdlpUpdateHint, panelX + (panelW / 2), panelY + 231, 0xFFAAAAAA);
        }
    }

    private void drawScrollbar(
            GuiGraphicsExtractor context,
            int trackAreaX,
            int trackAreaY,
            int trackAreaHeight,
            int visibleCount,
            int totalItems,
            int offset,
            boolean dragging,
            int mouseX,
            int mouseY
    ) {
        int trackWidth = 5;
        int maxOffset = Math.max(1, totalItems - visibleCount);
        boolean hovered = mouseX >= trackAreaX - 2 && mouseX <= trackAreaX + trackWidth + 2
                && mouseY >= trackAreaY && mouseY <= trackAreaY + trackAreaHeight;
        context.fill(trackAreaX, trackAreaY, trackAreaX + trackWidth, trackAreaY + trackAreaHeight, hovered ? 0xFF282836 : 0xFF1C1C24);

        int thumbHeight = Math.max(20, (int) ((double) visibleCount / (double) totalItems * trackAreaHeight));
        int scrollRange = trackAreaHeight - thumbHeight;
        int thumbY = trackAreaY + (int) ((double) offset / (double) maxOffset * scrollRange);

        int thumbColor = dragging ? 0xFFFFCC33 : (hovered ? 0xFFFFB300 : 0xFFFFAA00);
        context.fill(trackAreaX, thumbY, trackAreaX + trackWidth, thumbY + thumbHeight, thumbColor);
        context.outline(trackAreaX, thumbY, trackWidth, thumbHeight, 0x88FFFFFF);
    }

    private static String formatDuration(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    private static String formatTotalDuration(long totalMs) {
        long totalSec = Math.max(0, totalMs / 1000);
        long hours = totalSec / 3600;
        long mins = (totalSec % 3600) / 60;
        long secs = totalSec % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, mins, secs)
                : String.format("%02d:%02d", mins, secs);
    }

    /* ------------------------------------------------------------- */
    /* EVENTOS DE RATÓN Y TECLADO                                    */
    /* ------------------------------------------------------------- */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (shouldShowChangelogModal()) {
            return super.mouseClicked(event, doubleClick);
        }
        if (event.button() == 0) {
            int panelW = getPanelW();
            int panelX = getPanelX(panelW);
            int panelY = getPanelY();
            int trackAreaX = panelX + panelW - 8;
            double mouseX = event.x();
            double mouseY = event.y();

            if (mouseX >= trackAreaX - 3 && mouseX <= trackAreaX + 8) {
                if (currentTab == Tab.SEARCH && isSearchMode && searchResults.size() > 4) {
                    int trackY = panelY + 56;
                    int trackH = 4 * 34 - 4;
                    if (mouseY >= trackY && mouseY <= trackY + trackH) {
                        isDraggingSearchScrollbar = true;
                        searchScrollOffset = computeScrollOffset(mouseY, searchResults.size(), 4, trackY, trackH);
                        this.rebuildWidgets();
                        return true;
                    }
                } else if (currentTab == Tab.QUEUE) {
                    List<TrackInfo> list = showHistorySubTab
                            ? ClientAudioEngine.getInstance().getHistory()
                            : ClientAudioEngine.getInstance().getQueue();
                    if (list.size() > 5) {
                        int trackY = panelY + 50;
                        int trackH = 5 * 32 - 4;
                        if (mouseY >= trackY && mouseY <= trackY + trackH) {
                            isDraggingQueueScrollbar = true;
                            int off = computeScrollOffset(mouseY, list.size(), 5, trackY, trackH);
                            if (showHistorySubTab) historyScrollOffset = off;
                            else queueScrollOffset = off;
                            this.rebuildWidgets();
                            return true;
                        }
                    }
                } else if (currentTab == Tab.PLAYLISTS) {
                    if (selectedPlaylistId == null) {
                        List<SavedPlaylist> pls = SavedPlaylistsManager.getPlaylists();
                        if (pls.size() > 5) {
                            int trackY = panelY + 56;
                            int trackH = 5 * 34 - 4;
                            if (mouseY >= trackY && mouseY <= trackY + trackH) {
                                isDraggingPlaylistsScrollbar = true;
                                playlistsScrollOffset = computeScrollOffset(mouseY, pls.size(), 5, trackY, trackH);
                                this.rebuildWidgets();
                                return true;
                            }
                        }
                    } else {
                        SavedPlaylist pl = SavedPlaylistsManager.getPlaylistById(selectedPlaylistId);
                        if (pl != null && pl.tracks.size() > 3) {
                            int trackY = panelY + 114;
                            int trackH = 3 * 32 - 4;
                            if (mouseY >= trackY && mouseY <= trackY + trackH) {
                                isDraggingPlaylistsScrollbar = true;
                                playlistsScrollOffset = computeScrollOffset(mouseY, pl.tracks.size(), 3, trackY, trackH);
                                this.rebuildWidgets();
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (shouldShowChangelogModal()) {
            return super.mouseDragged(event, deltaX, deltaY);
        }
        int panelY = getPanelY();
        if (isDraggingSearchScrollbar && currentTab == Tab.SEARCH && isSearchMode && searchResults.size() > 4) {
            int newOff = computeScrollOffset(event.y(), searchResults.size(), 4, panelY + 56, 4 * 34 - 4);
            if (newOff != searchScrollOffset) {
                searchScrollOffset = newOff;
                this.rebuildWidgets();
            }
            return true;
        }
        if (isDraggingQueueScrollbar && currentTab == Tab.QUEUE) {
            List<TrackInfo> list = showHistorySubTab
                    ? ClientAudioEngine.getInstance().getHistory()
                    : ClientAudioEngine.getInstance().getQueue();
            if (list.size() > 5) {
                int newOff = computeScrollOffset(event.y(), list.size(), 5, panelY + 50, 5 * 32 - 4);
                int cur = showHistorySubTab ? historyScrollOffset : queueScrollOffset;
                if (newOff != cur) {
                    if (showHistorySubTab) historyScrollOffset = newOff;
                    else queueScrollOffset = newOff;
                    this.rebuildWidgets();
                }
                return true;
            }
        }
        if (isDraggingPlaylistsScrollbar && currentTab == Tab.PLAYLISTS) {
            if (selectedPlaylistId == null) {
                List<SavedPlaylist> pls = SavedPlaylistsManager.getPlaylists();
                if (pls.size() > 5) {
                    int newOff = computeScrollOffset(event.y(), pls.size(), 5, panelY + 56, 5 * 34 - 4);
                    if (newOff != playlistsScrollOffset) {
                        playlistsScrollOffset = newOff;
                        this.rebuildWidgets();
                    }
                    return true;
                }
            } else {
                SavedPlaylist pl = SavedPlaylistsManager.getPlaylistById(selectedPlaylistId);
                if (pl != null && pl.tracks.size() > 3) {
                    int newOff = computeScrollOffset(event.y(), pl.tracks.size(), 3, panelY + 114, 3 * 32 - 4);
                    if (newOff != playlistsScrollOffset) {
                        playlistsScrollOffset = newOff;
                        this.rebuildWidgets();
                    }
                    return true;
                }
            }
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) {
            isDraggingQueueScrollbar = false;
            isDraggingSearchScrollbar = false;
            isDraggingPlaylistsScrollbar = false;
        }
        return super.mouseReleased(event);
    }

    private int computeScrollOffset(double mouseY, int totalItems, int visibleCount, int trackAreaY, int trackAreaHeight) {
        int maxOffset = Math.max(0, totalItems - visibleCount);
        int thumbHeight = Math.max(20, (int) ((double) visibleCount / (double) totalItems * trackAreaHeight));
        int scrollRange = trackAreaHeight - thumbHeight;
        if (scrollRange <= 0) return 0;
        double relativeY = mouseY - trackAreaY - (thumbHeight / 2.0);
        double ratio = Math.max(0.0, Math.min(1.0, relativeY / (double) scrollRange));
        return (int) Math.round(ratio * maxOffset);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (shouldShowChangelogModal()) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        if (currentTab == Tab.SEARCH && isSearchMode && searchResults.size() > 4) {
            int maxOffset = searchResults.size() - 4;
            if (verticalAmount > 0 && searchScrollOffset > 0) {
                searchScrollOffset--;
                this.rebuildWidgets();
                return true;
            } else if (verticalAmount < 0 && searchScrollOffset < maxOffset) {
                searchScrollOffset++;
                this.rebuildWidgets();
                return true;
            }
        } else if (currentTab == Tab.QUEUE) {
            List<TrackInfo> list = showHistorySubTab
                    ? ClientAudioEngine.getInstance().getHistory()
                    : ClientAudioEngine.getInstance().getQueue();
            if (list.size() > 5) {
                int maxOffset = list.size() - 5;
                int cur = showHistorySubTab ? historyScrollOffset : queueScrollOffset;
                if (verticalAmount > 0 && cur > 0) {
                    if (showHistorySubTab) historyScrollOffset--;
                    else queueScrollOffset--;
                    this.rebuildWidgets();
                    return true;
                } else if (verticalAmount < 0 && cur < maxOffset) {
                    if (showHistorySubTab) historyScrollOffset++;
                    else queueScrollOffset++;
                    this.rebuildWidgets();
                    return true;
                }
            }
        } else if (currentTab == Tab.PLAYLISTS) {
            int visible = selectedPlaylistId == null ? 5 : 3;
            int total = selectedPlaylistId == null
                    ? SavedPlaylistsManager.getPlaylists().size()
                    : (SavedPlaylistsManager.getPlaylistById(selectedPlaylistId) != null
                    ? SavedPlaylistsManager.getPlaylistById(selectedPlaylistId).tracks.size()
                    : 0);
            if (total > visible) {
                int maxOffset = total - visible;
                if (verticalAmount > 0 && playlistsScrollOffset > 0) {
                    playlistsScrollOffset--;
                    this.rebuildWidgets();
                    return true;
                } else if (verticalAmount < 0 && playlistsScrollOffset < maxOffset) {
                    playlistsScrollOffset++;
                    this.rebuildWidgets();
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (shouldShowChangelogModal()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                dismissChangelogModal();
                return true;
            }
            return super.keyPressed(event);
        }

        boolean isEnter = (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER);

        if (isEnter && currentTab == Tab.SEARCH && this.searchInput != null && this.searchInput.isFocused()) {
            executeSearchOrAdd();
            return true;
        }
        if (isEnter && currentTab == Tab.PLAYLISTS && this.playlistNameInput != null && this.playlistNameInput.isFocused()) {
            createNewSavedPlaylist();
            return true;
        }
        if (currentTab == Tab.PLAYLISTS && isRenamingPlaylist && this.renamePlaylistInput != null && this.renamePlaylistInput.isFocused()) {
            if (isEnter) {
                confirmPlaylistRename();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                isRenamingPlaylist = false;
                this.rebuildWidgets();
                return true;
            }
        }

        boolean typing = (this.searchInput != null && this.searchInput.isFocused())
                || (this.playlistNameInput != null && this.playlistNameInput.isFocused())
                || (this.renamePlaylistInput != null && this.renamePlaylistInput.isFocused());
        boolean isOpenGuiKey = keyCode == GLFW.GLFW_KEY_PERIOD
                || (PlayCraftClient.openGuiKey != null && PlayCraftClient.openGuiKey.matches(event));
        if (isOpenGuiKey && !typing) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
