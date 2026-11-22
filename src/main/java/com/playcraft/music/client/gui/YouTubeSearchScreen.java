package com.playcraft.music.client.gui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.playcraft.music.client.PlayCraftClient;
import com.playcraft.music.client.PlayCraftConfig;
import com.playcraft.music.client.SavedPlaylistsManager;
import com.playcraft.music.client.SavedPlaylistsManager.SavedPlaylist;
import com.playcraft.music.client.SavedPlaylistsManager.SavedTrack;
import com.playcraft.music.client.audio.ClientAudioEngine;
import com.playcraft.music.client.gui.ui.ModernButton;
import com.playcraft.music.client.gui.ui.ModernEditBox;
import com.playcraft.music.client.gui.ui.ModernSettingSlider;
import com.playcraft.music.client.gui.ui.ModernUI;
import com.playcraft.music.client.gui.ui.PlayCraftIcons;
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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Centro Musical PlayCraftMusic v2.0.0.
 * Diseño adaptable a cualquier resolución/escala GUI de Minecraft con barra lateral vertical,
 * tipografía moderna e iconos vectoriales HD.
 */
public class YouTubeSearchScreen extends Screen {

    public enum Tab {
        NOW_PLAYING, SEARCH, QUEUE, PLAYLISTS, SETTINGS
    }

    private static final int MAX_PANEL_WIDTH = 560;
    private static final int MAX_PANEL_HEIGHT = 276;

    private static Tab currentTab = Tab.SEARCH;
    private static boolean isSearchMode = true;
    private static boolean isHistorySubTab = false;
    private static String selectedPlaylistId = null;

    // Estado de búsqueda y enlaces
    private static List<TrackInfo> searchResults = new ArrayList<>();
    private static String lastSearchQuery = "";
    private static int searchResultLimit = 5;
    private static final Set<String> addedSearchUrls = new HashSet<>();
    private static final Map<String, Long> recentlyEnqueuedTracks = new HashMap<>();
    private static long lastNonNullTrackTimestamp = 0L;
    private static boolean isSearching = false;
    private static String statusBanner = "";
    private static int statusBannerColor = ModernUI.TEXT_SECONDARY;
    private static long statusBannerTimestamp = 0L;

    // Opciones de importación de enlaces
    private static boolean importPlaylistOnListParam = true;
    private static boolean saveImportedPlaylistLocally = false;

    // Verificador de dependencias yt-dlp
    private static boolean isCheckingUpdates = false;
    private static String binaryUpdateStatus = "";
    private static String binaryUpdateHint = "";

    // Scrolls independientes por sección
    private static int searchScrollOffset = 0;
    private static int queueScrollOffset = 0;
    private static int historyScrollOffset = 0;
    private static int playlistsScrollOffset = 0;
    private static int playlistDetailScrollOffset = 0;

    // Filas visibles dinámicas para sincronizar mouseScrolled con viewport actual
    private int lastSearchVisibleRows = 4;
    private int lastQueueVisibleRows = 4;
    private int lastHistoryVisibleRows = 5;
    private int lastPlaylistsVisibleRows = 4;
    private int lastPlaylistDetailVisibleRows = 4;

    // Estado persistente de inputs
    private static String lastPlaylistNameInput = "";
    private boolean renamingPlaylist = false;
    private String renameDraft = "";

    // Modales superpuestos
    private boolean showingChangelog = false;
    private boolean showingPlaylistPicker = false;
    private TrackInfo pickerTargetTrack = null;
    private int pickerScrollOffset = 0;

    // Coordenadas calculadas dinámicamente
    private int panelW;
    private int panelH;
    private int sidebarW;
    private int panelX;
    private int panelY;
    private int contentX;
    private int contentW;

    // Referencias de widgets interactivos para sincronización en vivo
    private ModernButton navNowPlayingBtn;
    private ModernButton navSearchBtn;
    private ModernButton navQueueBtn;
    private ModernButton navPlaylistsBtn;
    private ModernButton navSettingsBtn;

    private ModernButton playPauseBtn;
    private ModernButton shuffleBtn;
    private ModernButton loopBtn;
    private ModernButton autoplayBtn;
    private ModernButton favHeroBtn;
    private ModernButton muteBtn;
    private ModernMediaSlider posSlider;
    private ModernMediaSlider volSlider;

    private ModernEditBox searchInput;
    private ModernEditBox playlistNameInput;
    private ModernEditBox renameInput;

    private ModernButton queueSubBtn;
    private ModernButton historySubBtn;
    private ModernButton rerollAutoplayBtn;

    private ModernButton checkUpdatesBtn;
    private ModernButton themeBtn;
    private ModernButton fontBtn;

    private record ClickRow(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<ClickRow> dynamicClickZones = new ArrayList<>();
    private final List<ClickRow> modalClickZones = new ArrayList<>();
    private int miniPlayerX, miniPlayerY, miniPlayerW, miniPlayerH;
    private String pendingTooltipText = null;
    private int pendingTooltipX = 0;
    private int pendingTooltipY = 0;

    public YouTubeSearchScreen() {
        super(Component.literal("PlayCraftMusic Center"));
        if (ClientAudioEngine.getInstance().getCurrentTrack() != null) {
            lastNonNullTrackTimestamp = System.currentTimeMillis();
        }
        if (shouldShowChangelogModal()) {
            this.showingChangelog = true;
        }
    }

    public boolean isSettingsTabActive() {
        return currentTab == Tab.SETTINGS;
    }

    private boolean isCompactV() {
        return panelH < 268;
    }

    private boolean isCompactH() {
        return contentW < 360;
    }

    private static boolean shouldShowChangelogModal() {
        return !PlayCraftConfig.CURRENT_MOD_VERSION.equals(PlayCraftConfig.LAST_SEEN_CHANGELOG_VERSION);
    }

    private void dismissChangelogModal() {
        this.showingChangelog = false;
        if (!PlayCraftConfig.CURRENT_MOD_VERSION.equals(PlayCraftConfig.LAST_SEEN_CHANGELOG_VERSION)) {
            PlayCraftConfig.LAST_SEEN_CHANGELOG_VERSION = PlayCraftConfig.CURRENT_MOD_VERSION;
            PlayCraftConfig.save();
        }
    }

    private static void setStatusFeedback(String message, int color) {
        statusBanner = message != null ? message : "";
        statusBannerColor = color;
        statusBannerTimestamp = System.currentTimeMillis();
    }

    @Override
    protected void init() {
        super.init();
        boolean missingDeps = !BinaryLocator.hasRequiredBinaries();
        int depSpace = missingDeps ? 22 : 0;

        this.panelW = Math.max(380, Math.min(MAX_PANEL_WIDTH, this.width - 20));
        this.panelH = Math.max(224, Math.min(MAX_PANEL_HEIGHT, this.height - 14 - depSpace));
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = Math.max(6, (this.height - this.panelH - depSpace) / 2);

        this.sidebarW = (this.panelW < 490) ? 118 : 122;
        int pad = (this.panelW < 490) ? 10 : 14;
        this.contentX = this.panelX + this.sidebarW + pad;
        this.contentW = this.panelW - this.sidebarW - (pad * 2);

        this.searchInput = null;
        this.playlistNameInput = null;
        this.renameInput = null;
        this.playPauseBtn = null;
        this.shuffleBtn = null;
        this.loopBtn = null;
        this.autoplayBtn = null;
        this.favHeroBtn = null;
        this.muteBtn = null;
        this.posSlider = null;
        this.volSlider = null;
        this.queueSubBtn = null;
        this.historySubBtn = null;
        this.rerollAutoplayBtn = null;
        this.checkUpdatesBtn = null;
        this.themeBtn = null;
        this.fontBtn = null;

        // Cabecera superior derecha: Botón [?] Novedades y [X] Cerrar
        this.addRenderableWidget(new ModernButton(
                panelX + panelW - 49, panelY + 9, 19, 19,
                ModernButton.Variant.GHOST, null, "?",
                b -> {
                    this.showingPlaylistPicker = false;
                    this.showingChangelog = true;
                }
        ));
        this.addRenderableWidget(new ModernButton(
                panelX + panelW - 27, panelY + 9, 19, 19,
                ModernButton.Variant.GHOST, PlayCraftIcons.Icon.CLOSE, "",
                b -> this.onClose()
        ));

        buildSidebarNav();

        switch (currentTab) {
            case NOW_PLAYING -> initNowPlayingTab();
            case SEARCH -> initSearchTab();
            case QUEUE -> initQueueTab();
            case PLAYLISTS -> initPlaylistsTab();
            case SETTINGS -> initSettingsTab();
        }
    }

    private void buildSidebarNav() {
        boolean cv = isCompactV();
        int navX = panelX + 7;
        int navW = sidebarW - 14;
        int navH = cv ? 21 : 24;
        int startY = cv ? panelY + 37 : panelY + 42;
        int stride = cv ? 23 : 27;

        navNowPlayingBtn = addSidebarButton(navX, startY, navW, navH,
                Tab.NOW_PLAYING, PlayCraftIcons.Icon.DISC, "Reproducción");
        navSearchBtn = addSidebarButton(navX, startY + stride, navW, navH,
                Tab.SEARCH, PlayCraftIcons.Icon.SEARCH, "Buscar");
        navQueueBtn = addSidebarButton(navX, startY + stride * 2, navW, navH,
                Tab.QUEUE, PlayCraftIcons.Icon.QUEUE, getQueueNavLabel());
        navPlaylistsBtn = addSidebarButton(navX, startY + stride * 3, navW, navH,
                Tab.PLAYLISTS, PlayCraftIcons.Icon.PLAYLIST, "Playlists");
        navSettingsBtn = addSidebarButton(navX, startY + stride * 4, navW, navH,
                Tab.SETTINGS, PlayCraftIcons.Icon.SETTINGS, "Ajustes");
    }

    private ModernButton addSidebarButton(int x, int y, int w, int h, Tab target, PlayCraftIcons.Icon icon, String label) {
        ModernButton.Variant variant = (currentTab == target)
                ? ModernButton.Variant.SIDEBAR_ACTIVE
                : ModernButton.Variant.SIDEBAR;
        return this.addRenderableWidget(new ModernButton(x, y, w, h, variant, icon, label, b -> {
            if (currentTab != target) {
                currentTab = target;
                this.renamingPlaylist = false;
                this.rebuildWidgets();
            }
        }));
    }

    private String getQueueNavLabel() {
        int qSize = ClientAudioEngine.getInstance().getQueue().size();
        return qSize > 0 ? "Cola (" + qSize + ")" : "Cola";
    }

    // ==================== TAB 1: NOW PLAYING ====================
    private void initNowPlayingTab() {
        ClientAudioEngine engine = ClientAudioEngine.getInstance();
        TrackInfo cur = engine.getCurrentTrack();
        boolean cv = isCompactV();
        boolean ch = isCompactH();

        int heroY = panelY + 39;
        int heroH = cv ? 78 : 92;
        int metaOffset = cv ? 74 : 94;
        int metaX = contentX + metaOffset;
        int metaW = Math.max(150, contentW - metaOffset - 10);

        int btnGap = 4;
        int btnW = (metaW - btnGap * 2) / 3;
        int heroBtnH = cv ? 18 : 20;
        int heroBtnY = heroY + heroH - heroBtnH - 6;

        boolean isFav = cur != null && isTrackInFavorites(cur);
        favHeroBtn = this.addRenderableWidget(new ModernButton(
                metaX, heroBtnY, btnW, heroBtnH,
                isFav ? ModernButton.Variant.TOGGLE_ACTIVE : ModernButton.Variant.SECONDARY,
                isFav ? PlayCraftIcons.Icon.STAR_FILLED : PlayCraftIcons.Icon.STAR,
                getFavButtonLabel(isFav, ch),
                b -> {
                    TrackInfo t = ClientAudioEngine.getInstance().getCurrentTrack();
                    if (t != null) {
                        toggleFavoriteTrack(t);
                        updateLiveControls();
                    }
                }
        ));
        favHeroBtn.active = cur != null;

        ModernButton addPlBtn = this.addRenderableWidget(new ModernButton(
                metaX + btnW + btnGap, heroBtnY, btnW, heroBtnH,
                ModernButton.Variant.SECONDARY, PlayCraftIcons.Icon.PLUS,
                "Playlist",
                b -> {
                    TrackInfo t = ClientAudioEngine.getInstance().getCurrentTrack();
                    if (t != null) {
                        this.pickerTargetTrack = t;
                        this.pickerScrollOffset = 0;
                        this.showingPlaylistPicker = true;
                    }
                }
        ));
        addPlBtn.active = cur != null;

        ModernButton reconnectBtn = this.addRenderableWidget(new ModernButton(
                metaX + (btnW + btnGap) * 2, heroBtnY, metaW - (btnW + btnGap) * 2, heroBtnH,
                ModernButton.Variant.SECONDARY, PlayCraftIcons.Icon.RECONNECT,
                "Reconectar",
                b -> {
                    ClientAudioEngine eng = ClientAudioEngine.getInstance();
                    if (eng.getCurrentTrack() != null) {
                        eng.reconnectCurrentTrack();
                        MusicHudOverlay.showNotification("Reconectando flujo de audio local...");
                    }
                }
        ));
        reconnectBtn.active = cur != null;

        int seekY = heroY + heroH + (cv ? 5 : 8);
        int seekH = cv ? 22 : 24;
        double initPos = 0.0;
        if (cur != null && cur.durationMs() > 0) {
            initPos = Math.min(1.0, (double) MusicHudOverlay.getLivePositionMs() / (double) cur.durationMs());
        }
        posSlider = new ModernMediaSlider(
                contentX, seekY, contentW, seekH, initPos,
                ModernMediaSlider.Theme.fromHudTheme(MusicHudOverlay.CURRENT_THEME),
                val -> {
                    TrackInfo t = ClientAudioEngine.getInstance().getCurrentTrack();
                    if (t == null || t.durationMs() <= 0) {
                        return ModernUI.text("--:-- / --:--");
                    }
                    long curMs = (long) (val * t.durationMs());
                    return ModernUI.text(formatDuration(curMs) + " / " + formatDuration(t.durationMs()));
                },
                val -> {
                    TrackInfo t = ClientAudioEngine.getInstance().getCurrentTrack();
                    if (t != null && t.durationMs() > 0) {
                        ClientPlayNetworking.send(new ControlTrackPayload(
                                ControlTrackPayload.Action.SEEK, (long) (val * t.durationMs())));
                    }
                },
                true
        );
        this.addRenderableWidget(posSlider);

        // Fila de transporte centrada adaptable
        int ty = seekY + seekH + (cv ? 5 : 8);
        int th = cv ? 22 : 26;
        int wShuf = ch ? 24 : 28;
        int wPrev = ch ? 28 : 34;
        int wPlay = ch ? 44 : 54;
        int wNext = ch ? 28 : 34;
        int wStop = ch ? 24 : 28;
        int wLoop = ch ? 54 : 68;
        int wAuto = ch ? 54 : 66;
        int gap = ch ? 4 : 5;
        int rowTotal = wShuf + wPrev + wPlay + wNext + wStop + wLoop + wAuto + gap * 6;
        int tx = contentX + Math.max(0, (contentW - rowTotal) / 2);

        shuffleBtn = this.addRenderableWidget(new ModernButton(
                tx, ty, wShuf, th, ModernButton.Variant.SECONDARY, PlayCraftIcons.Icon.SHUFFLE, "",
                b -> ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.SHUFFLE_QUEUE))
        ));
        tx += wShuf + gap;
        this.addRenderableWidget(new ModernButton(
                tx, ty, wPrev, th, ModernButton.Variant.SECONDARY, PlayCraftIcons.Icon.PREV, "",
                b -> ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.REWIND))
        ));
        tx += wPrev + gap;
        playPauseBtn = this.addRenderableWidget(new ModernButton(
                tx, ty, wPlay, th, ModernButton.Variant.PRIMARY,
                engine.isPlaying() ? PlayCraftIcons.Icon.PAUSE : PlayCraftIcons.Icon.PLAY, "",
                b -> ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.TOGGLE_PAUSE))
        ));
        tx += wPlay + gap;
        this.addRenderableWidget(new ModernButton(
                tx, ty, wNext, th, ModernButton.Variant.SECONDARY, PlayCraftIcons.Icon.NEXT, "",
                b -> ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.SKIP))
        ));
        tx += wNext + gap;
        this.addRenderableWidget(new ModernButton(
                tx, ty, wStop, th, ModernButton.Variant.DANGER, PlayCraftIcons.Icon.STOP, "",
                b -> {
                    lastNonNullTrackTimestamp = 0L;
                    ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.STOP));
                }
        ));
        tx += wStop + gap;

        int loopOrd = engine.getLoopModeOrdinal();
        loopBtn = this.addRenderableWidget(new ModernButton(
                tx, ty, wLoop, th,
                loopOrd > 0 ? ModernButton.Variant.TOGGLE_ACTIVE : ModernButton.Variant.SECONDARY,
                PlayCraftIcons.Icon.LOOP, getLoopLabel(loopOrd),
                b -> ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.CYCLE_LOOP))
        ));
        tx += wLoop + gap;

        boolean autoOn = engine.isAutoplayEnabled();
        autoplayBtn = this.addRenderableWidget(new ModernButton(
                tx, ty, wAuto, th,
                autoOn ? ModernButton.Variant.TOGGLE_ACTIVE : ModernButton.Variant.SECONDARY,
                PlayCraftIcons.Icon.AUTOPLAY, "Auto",
                b -> ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.TOGGLE_AUTOPLAY))
        ));

        int vy = ty + th + (cv ? 5 : 8);
        int vh = cv ? 22 : 24;
        int muteW = 26;
        muteBtn = this.addRenderableWidget(new ModernButton(
                contentX, vy, muteW, vh,
                engine.isMuted() ? ModernButton.Variant.DANGER : ModernButton.Variant.SECONDARY,
                engine.isMuted() ? PlayCraftIcons.Icon.MUTE : PlayCraftIcons.Icon.VOLUME, "",
                b -> {
                    ClientAudioEngine eng = ClientAudioEngine.getInstance();
                    eng.toggleMute();
                    b.setFocused(false);
                    updateMuteControls();
                }
        ));

        volSlider = new ModernMediaSlider(
                contentX + muteW + 5, vy, contentW - muteW - 5, vh,
                engine.isMuted() ? 0.0 : engine.getVolume(),
                ModernMediaSlider.Theme.VOLUME,
                val -> {
                    int pct = (int) Math.round(val * 100);
                    return ModernUI.text(pct == 0 ? "Volumen: Silenciado" : "Volumen: " + pct + "%");
                },
                val -> {
                    ClientAudioEngine eng = ClientAudioEngine.getInstance();
                    eng.setVolume(val.floatValue());
                    if (muteBtn != null) {
                        muteBtn.setIcon(eng.isMuted() ? PlayCraftIcons.Icon.MUTE : PlayCraftIcons.Icon.VOLUME);
                        muteBtn.setVariant(eng.isMuted() ? ModernButton.Variant.DANGER : ModernButton.Variant.SECONDARY);
                    }
                }
        );
        this.addRenderableWidget(volSlider);
    }

    private String getFavButtonLabel(boolean isFav, boolean compact) {
        if (compact) return isFav ? "En Fav." : "Favorito";
        return isFav ? "En Favoritos" : "Favorito";
    }

    // ==================== TAB 2: SEARCH / LINK ====================
    private void initSearchTab() {
        boolean cv = isCompactV();
        boolean ch = isCompactH();

        int topY = panelY + 39;
        int subH = cv ? 20 : 22;
        int subW = Math.min(156, (contentW - 6) / 2);
        this.addRenderableWidget(new ModernButton(
                contentX, topY, subW, subH,
                isSearchMode ? ModernButton.Variant.TOGGLE_ACTIVE : ModernButton.Variant.SECONDARY,
                PlayCraftIcons.Icon.SEARCH, ch ? "Buscar YouTube" : "Buscar en YouTube",
                b -> {
                    if (!isSearchMode) {
                        isSearchMode = true;
                        this.rebuildWidgets();
                    }
                }
        ));
        this.addRenderableWidget(new ModernButton(
                contentX + subW + 6, topY, subW, subH,
                !isSearchMode ? ModernButton.Variant.TOGGLE_ACTIVE : ModernButton.Variant.SECONDARY,
                PlayCraftIcons.Icon.LINK, "Enlace o Playlist",
                b -> {
                    if (isSearchMode) {
                        isSearchMode = false;
                        this.rebuildWidgets();
                    }
                }
        ));

        int inputY = topY + subH + (cv ? 5 : 7);
        int inputH = cv ? 22 : 24;
        int submitW = ch ? 72 : 82;
        int inputW = contentW - submitW - 6;

        String hint = isSearchMode
                ? "Buscar canción, artista o tema..."
                : "Pega la URL de YouTube (video o playlist)...";
        this.searchInput = new ModernEditBox(
                this.font, contentX, inputY, inputW, inputH,
                isSearchMode ? PlayCraftIcons.Icon.SEARCH : PlayCraftIcons.Icon.LINK, hint
        );
        this.searchInput.setMaxLength(512);
        this.searchInput.setValue(lastSearchQuery);
        this.searchInput.setResponder(val -> lastSearchQuery = val);
        this.addRenderableWidget(this.searchInput);
        this.setInitialFocus(this.searchInput);

        this.addRenderableWidget(new ModernButton(
                contentX + inputW + 6, inputY, submitW, inputH,
                ModernButton.Variant.PRIMARY,
                isSearchMode ? PlayCraftIcons.Icon.SEARCH : PlayCraftIcons.Icon.PLAY,
                isSearchMode ? "Buscar" : "Añadir",
                b -> executeSearchOrAdd()
        ));

        if (isSearchMode) {
            if (!isSearching && !searchResults.isEmpty() && searchResultLimit < 20 && !lastSearchQuery.trim().isEmpty()) {
                int moreW = 108;
                this.addRenderableWidget(new ModernButton(
                        contentX + contentW - moreW, panelY + panelH - 24, moreW, 19,
                        ModernButton.Variant.SECONDARY, PlayCraftIcons.Icon.PLUS, "Cargar más",
                        b -> loadMoreSearchResults()
                ));
            }
        } else {
            int toggleY = inputY + inputH + (cv ? 5 : 6);
            int halfW = (contentW - 6) / 2;
            this.addRenderableWidget(new ModernButton(
                    contentX, toggleY, halfW, 20,
                    importPlaylistOnListParam ? ModernButton.Variant.TOGGLE_ACTIVE : ModernButton.Variant.SECONDARY,
                    PlayCraftIcons.Icon.PLAYLIST,
                    ch
                            ? (importPlaylistOnListParam ? "list=: Playlist" : "list=: Canción")
                            : (importPlaylistOnListParam ? "Con list=: Importar Playlist" : "Con list=: Solo Canción"),
                    b -> {
                        importPlaylistOnListParam = !importPlaylistOnListParam;
                        this.rebuildWidgets();
                    }
            ));
            this.addRenderableWidget(new ModernButton(
                    contentX + halfW + 6, toggleY, contentW - halfW - 6, 20,
                    saveImportedPlaylistLocally ? ModernButton.Variant.TOGGLE_ACTIVE : ModernButton.Variant.SECONDARY,
                    PlayCraftIcons.Icon.CHECK,
                    saveImportedPlaylistLocally ? "Guardar local: Sí" : "Guardar local: No",
                    b -> {
                        saveImportedPlaylistLocally = !saveImportedPlaylistLocally;
                        this.rebuildWidgets();
                    }
            ));
        }
    }

    private void executeSearchOrAdd() {
        if (this.searchInput == null) return;
        String query = this.searchInput.getValue().trim();
        if (query.isEmpty() || isSearching) return;

        Minecraft mc = Minecraft.getInstance();
        String playerName = (mc.player != null) ? mc.player.getName().getString() : "Player";

        if (!isSearchMode) {
            if (!query.startsWith("http://") && !query.startsWith("https://")) {
                setStatusFeedback("Introduce un enlace válido que comience con http:// o https://.", ModernUI.STATUS_ERROR);
                return;
            }
            this.searchInput.setValue("");
            lastSearchQuery = "";
            isSearching = true;
            setStatusFeedback("Resolviendo enlace desde tu cliente...", ModernUI.TEXT_SECONDARY);
            this.rebuildWidgets();

            if (YouTubeMetadataResolver.isPlaylistUrl(query) && importPlaylistOnListParam) {
                YouTubeMetadataResolver.resolvePlaylistWithInfo(query, playerName, 50).thenAccept(resolved -> {
                    mc.execute(() -> {
                        isSearching = false;
                        List<TrackInfo> tracks = resolved != null ? resolved.tracks() : List.of();
                        if (tracks == null || tracks.isEmpty()) {
                            setStatusFeedback("No se pudieron obtener pistas válidas de ese enlace.", ModernUI.STATUS_ERROR);
                        } else {
                            String baseTitle = (resolved.title() != null && !resolved.title().trim().isEmpty())
                                    ? resolved.title().trim()
                                    : "Colección de YouTube";
                            String finalLabel = baseTitle;
                            if (saveImportedPlaylistLocally) {
                                SavedPlaylist sp = SavedPlaylistsManager.createPlaylist(baseTitle);
                                SavedPlaylistsManager.addTracksToPlaylist(sp.id, tracks);
                                finalLabel = sp.name;
                            }
                            ClientAudioEngine.getInstance().prefetchStreamUrl(tracks.get(0).streamUrl());
                            ClientPlayNetworking.send(new AddBatchTracksPayload(tracks, finalLabel, false));
                            setStatusFeedback(
                                    saveImportedPlaylistLocally
                                            ? "Playlist importada (" + tracks.size() + " pistas) y guardada como \"" + finalLabel + "\"."
                                            : "Playlist añadida a la cola (" + tracks.size() + " pistas).",
                                    ModernUI.STATUS_ONLINE
                            );
                        }
                        if (mc.gui.screen() instanceof YouTubeSearchScreen screen) {
                            screen.rebuildWidgets();
                        }
                    });
                });
            } else {
                YouTubeMetadataResolver.resolve(query, playerName).thenAccept(track -> mc.execute(() -> {
                    isSearching = false;
                    if (track == null || track.streamUrl() == null || track.streamUrl().isEmpty()) {
                        setStatusFeedback("No se pudo obtener metadatos de audio del enlace.", ModernUI.STATUS_ERROR);
                    } else {
                        ClientAudioEngine.getInstance().prefetchStreamUrl(track.streamUrl());
                        ThumbnailTextureManager.getThumbnail(track.thumbnailUrl());
                        ClientPlayNetworking.send(new AddTrackPayload(
                                track.streamUrl(), track.title(), track.author(), track.thumbnailUrl(), track.durationMs()
                        ));
                        setStatusFeedback("Pista añadida a la cola: " + track.title(), ModernUI.STATUS_ONLINE);
                    }
                    if (mc.gui.screen() instanceof YouTubeSearchScreen screen) {
                        screen.rebuildWidgets();
                    }
                }));
            }
            return;
        }

        lastSearchQuery = query;
        isSearching = true;
        searchResultLimit = 5;
        searchScrollOffset = 0;
        addedSearchUrls.clear();
        setStatusFeedback("Buscando en YouTube...", ModernUI.TEXT_SECONDARY);
        this.rebuildWidgets();

        YouTubeMetadataResolver.searchMultiple(query, searchResultLimit).thenAccept(results -> mc.execute(() -> {
            isSearching = false;
            searchResults = results != null ? new ArrayList<>(results) : new ArrayList<>();
            setStatusFeedback(
                    searchResults.isEmpty()
                            ? "Sin resultados para esa búsqueda."
                            : searchResults.size() + " resultados encontrados.",
                    searchResults.isEmpty() ? ModernUI.STATUS_WARN : ModernUI.TEXT_SECONDARY
            );
            if (mc.gui.screen() instanceof YouTubeSearchScreen screen) {
                screen.rebuildWidgets();
            }
        }));
    }

    private void loadMoreSearchResults() {
        String query = lastSearchQuery.trim();
        if (query.isEmpty() || isSearching) return;
        searchResultLimit = Math.min(20, searchResultLimit + 5);
        isSearching = true;
        this.rebuildWidgets();

        Minecraft mc = Minecraft.getInstance();
        YouTubeMetadataResolver.searchMultiple(query, searchResultLimit).thenAccept(results -> mc.execute(() -> {
            isSearching = false;
            if (results != null) {
                searchResults = new ArrayList<>(results);
                setStatusFeedback(searchResults.size() + " resultados cargados.", ModernUI.TEXT_SECONDARY);
            }
            if (mc.gui.screen() instanceof YouTubeSearchScreen screen) {
                screen.rebuildWidgets();
            }
        }));
    }

    // ==================== TAB 3: QUEUE / HISTORY ====================
    private void initQueueTab() {
        ClientAudioEngine engine = ClientAudioEngine.getInstance();
        boolean cv = isCompactV();
        boolean ch = isCompactH();
        int topY = panelY + 39;
        int topH = cv ? 20 : 22;

        if (!isHistorySubTab) {
            int shufW = ch ? 68 : 74;
            int delW = ch ? 60 : 64;
            int gap = 4;
            int subW = Math.min(122, (contentW - shufW - delW - gap * 3) / 2);

            queueSubBtn = this.addRenderableWidget(new ModernButton(
                    contentX, topY, subW, topH,
                    ModernButton.Variant.TOGGLE_ACTIVE,
                    PlayCraftIcons.Icon.QUEUE, "Cola (" + engine.getQueue().size() + ")",
                    b -> {}
            ));
            historySubBtn = this.addRenderableWidget(new ModernButton(
                    contentX + subW + gap, topY, subW, topH,
                    ModernButton.Variant.SECONDARY,
                    PlayCraftIcons.Icon.HISTORY,
                    (ch ? "Hist. (" : "Historial (") + engine.getHistory().size() + ")",
                    b -> {
                        isHistorySubTab = true;
                        this.rebuildWidgets();
                    }
            ));

            int rightEnd = contentX + contentW;
            this.addRenderableWidget(new ModernButton(
                    rightEnd - delW, topY, delW, topH,
                    ModernButton.Variant.DANGER, PlayCraftIcons.Icon.TRASH, "Vaciar",
                    b -> {
                        if (!ClientAudioEngine.getInstance().getQueue().isEmpty()) {
                            ClientPlayNetworking.send(new ModifyQueuePayload(ModifyQueuePayload.Action.CLEAR, -1, -1));
                            queueScrollOffset = 0;
                            setStatusFeedback("Cola de reproducción vaciada.", ModernUI.TEXT_SECONDARY);
                        }
                    }
            ));
            this.addRenderableWidget(new ModernButton(
                    rightEnd - delW - gap - shufW, topY, shufW, topH,
                    ModernButton.Variant.SECONDARY, PlayCraftIcons.Icon.SHUFFLE, "Mezclar",
                    b -> {
                        if (!ClientAudioEngine.getInstance().getQueue().isEmpty()) {
                            ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.SHUFFLE_QUEUE));
                            setStatusFeedback("Cola mezclada aleatoriamente.", ModernUI.STATUS_ONLINE);
                        }
                    }
            ));

            int apY = topY + topH + (cv ? 4 : 6);
            int apH = cv ? 30 : 34;
            rerollAutoplayBtn = this.addRenderableWidget(new ModernButton(
                    contentX + contentW - 76, apY + (apH - 20) / 2, 72, 20,
                    ModernButton.Variant.SECONDARY, PlayCraftIcons.Icon.RECONNECT, "Cambiar",
                    b -> {
                        ClientPlayNetworking.send(new ModifyQueuePayload(ModifyQueuePayload.Action.REROLL_AUTOPLAY, -1, -1));
                        setStatusFeedback("Buscando otra recomendación de Autoplay...", ModernUI.TEXT_SECONDARY);
                    }
            ));
            rerollAutoplayBtn.active = engine.isAutoplayEnabled() && engine.getCurrentTrack() != null;
        } else {
            int subW = Math.min(126, (contentW - 6) / 2);
            queueSubBtn = this.addRenderableWidget(new ModernButton(
                    contentX, topY, subW, topH,
                    ModernButton.Variant.SECONDARY,
                    PlayCraftIcons.Icon.QUEUE, "Cola (" + engine.getQueue().size() + ")",
                    b -> {
                        isHistorySubTab = false;
                        this.rebuildWidgets();
                    }
            ));
            historySubBtn = this.addRenderableWidget(new ModernButton(
                    contentX + subW + 6, topY, subW, topH,
                    ModernButton.Variant.TOGGLE_ACTIVE,
                    PlayCraftIcons.Icon.HISTORY,
                    (ch ? "Hist. (" : "Historial (") + engine.getHistory().size() + ")",
                    b -> {}
            ));
        }
    }

    // ==================== TAB 4: PLAYLISTS ====================
    private void initPlaylistsTab() {
        boolean cv = isCompactV();
        boolean ch = isCompactH();

        if (selectedPlaylistId == null) {
            int barY = panelY + 39;
            int barH = cv ? 22 : 24;
            int createW = ch ? 62 : 74;
            int saveQW = ch ? 94 : 114;
            int inputW = contentW - createW - saveQW - 10;

            this.playlistNameInput = new ModernEditBox(
                    this.font, contentX, barY, inputW, barH,
                    PlayCraftIcons.Icon.PLAYLIST, "Nombre de playlist..."
            );
            this.playlistNameInput.setMaxLength(40);
            this.playlistNameInput.setValue(lastPlaylistNameInput);
            this.playlistNameInput.setResponder(val -> lastPlaylistNameInput = val);
            this.addRenderableWidget(this.playlistNameInput);

            this.addRenderableWidget(new ModernButton(
                    contentX + inputW + 5, barY, createW, barH,
                    ModernButton.Variant.PRIMARY, PlayCraftIcons.Icon.PLUS, "Crear",
                    b -> createNewPlaylistFromInput()
            ));

            this.addRenderableWidget(new ModernButton(
                    contentX + inputW + 10 + createW, barY, saveQW, barH,
                    ModernButton.Variant.SECONDARY, PlayCraftIcons.Icon.DISC, "Guardar Cola",
                    b -> {
                        ClientAudioEngine engine = ClientAudioEngine.getInstance();
                        TrackInfo cur = engine.getCurrentTrack();
                        List<TrackInfo> q = engine.getQueue();
                        if (cur == null && q.isEmpty()) {
                            setStatusFeedback("No hay pistas activas ni en cola para guardar.", ModernUI.STATUS_WARN);
                            return;
                        }
                        String customName = this.playlistNameInput != null ? this.playlistNameInput.getValue().trim() : "";
                        SavedPlaylist saved = SavedPlaylistsManager.saveCurrentQueueAsPlaylist(customName, cur, q);
                        lastPlaylistNameInput = "";
                        if (this.playlistNameInput != null) this.playlistNameInput.setValue("");
                        selectedPlaylistId = saved.id;
                        playlistDetailScrollOffset = 0;
                        setStatusFeedback("Playlist \"" + saved.name + "\" guardada (" + saved.tracks.size() + " pistas).", ModernUI.STATUS_ONLINE);
                        this.rebuildWidgets();
                    }
            ));
            return;
        }

        // Vista detalle de Playlist
        SavedPlaylist pl = SavedPlaylistsManager.getPlaylistById(selectedPlaylistId);
        if (pl == null) {
            selectedPlaylistId = null;
            this.renamingPlaylist = false;
            this.rebuildWidgets();
            return;
        }
        boolean isFav = SavedPlaylistsManager.FAVORITES_ID.equals(pl.id);
        int headerY = panelY + 39;

        // Botón Volver fuera del contenedor del banner, integrado en la cabecera
        this.addRenderableWidget(new ModernButton(
                contentX, panelY + 9, 60, 19,
                ModernButton.Variant.SECONDARY, PlayCraftIcons.Icon.BACK, "Volver",
                b -> {
                    selectedPlaylistId = null;
                    this.renamingPlaylist = false;
                    this.rebuildWidgets();
                }
        ));

        int rightEnd = contentX + contentW - 6;
        int playW = 54;
        ModernButton detailPlayBtn = this.addRenderableWidget(new ModernButton(
                rightEnd - playW, headerY + 5, playW, 18,
                ModernButton.Variant.PRIMARY, PlayCraftIcons.Icon.PLAY, "Play",
                b -> playSavedPlaylist(pl, false)
        ));
        detailPlayBtn.active = !pl.tracks.isEmpty();

        ModernButton detailShuffleBtn = this.addRenderableWidget(new ModernButton(
                rightEnd - playW - 26, headerY + 5, 22, 18,
                ModernButton.Variant.SECONDARY, PlayCraftIcons.Icon.SHUFFLE, "",
                b -> playSavedPlaylist(pl, true)
        ));
        detailShuffleBtn.active = !pl.tracks.isEmpty();

        ModernButton detailAddCurrentBtn = this.addRenderableWidget(new ModernButton(
                rightEnd - playW - 52, headerY + 5, 22, 18,
                ModernButton.Variant.SECONDARY, PlayCraftIcons.Icon.PLUS, "",
                b -> {
                    TrackInfo cur = ClientAudioEngine.getInstance().getCurrentTrack();
                    if (cur != null) {
                        boolean added = SavedPlaylistsManager.addTrackToPlaylist(pl.id, cur);
                        setStatusFeedback(
                                added ? "Pista actual añadida a \"" + pl.name + "\"."
                                      : "La pista ya se encontraba en esta playlist.",
                                added ? ModernUI.STATUS_ONLINE : ModernUI.STATUS_WARN
                        );
                    }
                }
        ));
        detailAddCurrentBtn.active = ClientAudioEngine.getInstance().getCurrentTrack() != null;

        if (!isFav) {
            int renameY = headerY + (cv ? 24 : 27);
            if (this.renamingPlaylist) {
                int renameW = Math.max(120, contentW - 64);
                this.renameInput = new ModernEditBox(
                        this.font, contentX + 8, renameY, renameW, 18,
                        PlayCraftIcons.Icon.EDIT, "Nuevo nombre..."
                );
                this.renameInput.setMaxLength(40);
                this.renameInput.setValue(this.renameDraft);
                this.renameInput.setResponder(v -> this.renameDraft = v);
                this.addRenderableWidget(this.renameInput);
                this.setInitialFocus(this.renameInput);

                this.addRenderableWidget(new ModernButton(
                        contentX + 8 + renameW + 4, renameY, 20, 18,
                        ModernButton.Variant.PRIMARY, PlayCraftIcons.Icon.CHECK, "",
                        b -> confirmPlaylistRename(pl)
                ));
                this.addRenderableWidget(new ModernButton(
                        contentX + 8 + renameW + 26, renameY, 20, 18,
                        ModernButton.Variant.GHOST, PlayCraftIcons.Icon.CLOSE, "",
                        b -> {
                            this.renamingPlaylist = false;
                            this.rebuildWidgets();
                        }
                ));
            } else {
                this.addRenderableWidget(new ModernButton(
                        contentX + contentW - 26, renameY, 20, 18,
                        ModernButton.Variant.GHOST, PlayCraftIcons.Icon.EDIT, "",
                        b -> {
                            this.renameDraft = pl.name;
                            this.renamingPlaylist = true;
                            this.rebuildWidgets();
                        }
                ));
            }
        }
    }

    private void createNewPlaylistFromInput() {
        if (this.playlistNameInput == null) return;
        String name = this.playlistNameInput.getValue().trim();
        SavedPlaylist created = SavedPlaylistsManager.createPlaylist(name);
        this.playlistNameInput.setValue("");
        lastPlaylistNameInput = "";
        setStatusFeedback("Playlist creada: \"" + created.name + "\".", ModernUI.STATUS_ONLINE);
    }

    private void confirmPlaylistRename(SavedPlaylist pl) {
        if (pl == null || this.renameInput == null) return;
        String newName = this.renameInput.getValue().trim();
        if (newName.isEmpty()) {
            setStatusFeedback("El nombre de la playlist no puede estar vacío.", ModernUI.STATUS_WARN);
            return;
        }
        if (SavedPlaylistsManager.renamePlaylist(pl.id, newName)) {
            setStatusFeedback("Playlist renombrada a \"" + pl.name + "\".", ModernUI.STATUS_ONLINE);
        } else {
            setStatusFeedback("Ya existe otra playlist con ese nombre.", ModernUI.STATUS_WARN);
        }
        this.renamingPlaylist = false;
        this.rebuildWidgets();
    }

    private void playSavedPlaylist(SavedPlaylist pl, boolean shuffle) {
        if (pl == null || pl.tracks.isEmpty()) {
            setStatusFeedback("La playlist seleccionada está vacía.", ModernUI.STATUS_WARN);
            return;
        }
        List<TrackInfo> list = pl.toTrackInfoList();
        if (list.isEmpty()) return;
        ClientAudioEngine.getInstance().prefetchStreamUrl(list.get(0).streamUrl());
        ClientPlayNetworking.send(new AddBatchTracksPayload(list, pl.name, shuffle));
        setStatusFeedback(
                (shuffle ? "Mezclando y reproduciendo \"" : "Reproduciendo \"") + pl.name + "\" (" + list.size() + " pistas).",
                ModernUI.STATUS_ONLINE
        );
    }

    // ==================== TAB 5: SETTINGS ====================
    private void initSettingsTab() {
        boolean cv = isCompactV();
        boolean ch = isCompactH();

        int colGap = 8;
        int colW = (contentW - colGap) / 2;
        int col1X = contentX;
        int col2X = contentX + colW + colGap;

        int startY = panelY + (cv ? 48 : 52);
        int slH = cv ? 23 : 27;
        int slStep = slH + 2;
        int btnH = cv ? 19 : 22;
        int btnStep = btnH + 2;

        int maxHudW = Math.max(0, this.width - (int) (MusicHudOverlay.HUD_WIDTH * MusicHudOverlay.HUD_SCALE));
        int maxHudH = Math.max(0, this.height - (int) ((MusicHudOverlay.HUD_HEIGHT + 80) * MusicHudOverlay.HUD_SCALE));
        double initX = maxHudW > 0 ? Math.min(1.0, (double) MusicHudOverlay.HUD_X / maxHudW) : 0.0;
        double initY = maxHudH > 0 ? Math.min(1.0, (double) MusicHudOverlay.HUD_Y / maxHudH) : 0.0;

        this.addRenderableWidget(new ModernSettingSlider(
                col1X, startY, colW, slH, "HUD X", initX,
                val -> {
                    int curMax = Math.max(0, this.width - (int) (MusicHudOverlay.HUD_WIDTH * MusicHudOverlay.HUD_SCALE));
                    return (int) (val * curMax) + "px";
                },
                val -> {
                    int curMax = Math.max(0, this.width - (int) (MusicHudOverlay.HUD_WIDTH * MusicHudOverlay.HUD_SCALE));
                    MusicHudOverlay.HUD_X = (int) (val * curMax);
                    PlayCraftConfig.save();
                }
        ));

        this.addRenderableWidget(new ModernSettingSlider(
                col1X, startY + slStep, colW, slH, "HUD Y", initY,
                val -> {
                    int curMax = Math.max(0, this.height - (int) ((MusicHudOverlay.HUD_HEIGHT + 80) * MusicHudOverlay.HUD_SCALE));
                    return (int) (val * curMax) + "px";
                },
                val -> {
                    int curMax = Math.max(0, this.height - (int) ((MusicHudOverlay.HUD_HEIGHT + 80) * MusicHudOverlay.HUD_SCALE));
                    MusicHudOverlay.HUD_Y = (int) (val * curMax);
                    PlayCraftConfig.save();
                }
        ));

        double initScale = Math.max(0.0, Math.min(1.0, (MusicHudOverlay.HUD_SCALE - 0.70f) / 0.80f));
        this.addRenderableWidget(new ModernSettingSlider(
                col1X, startY + slStep * 2, colW, slH, "Escala HUD", initScale,
                val -> Math.round((0.70f + (float) (val * 0.80f)) * 100) + "%",
                val -> {
                    MusicHudOverlay.HUD_SCALE = Math.round((0.70f + (float) (val * 0.80f)) * 100.0f) / 100.0f;
                    int curMaxW = Math.max(0, this.width - (int) (MusicHudOverlay.HUD_WIDTH * MusicHudOverlay.HUD_SCALE));
                    int curMaxH = Math.max(0, this.height - (int) ((MusicHudOverlay.HUD_HEIGHT + 80) * MusicHudOverlay.HUD_SCALE));
                    MusicHudOverlay.HUD_X = Math.min(MusicHudOverlay.HUD_X, curMaxW);
                    MusicHudOverlay.HUD_Y = Math.min(MusicHudOverlay.HUD_Y, curMaxH);
                    PlayCraftConfig.save();
                }
        ));

        double initOpacity = Math.max(0.0, Math.min(1.0, (MusicHudOverlay.HUD_OPACITY - 0.20f) / 0.80f));
        this.addRenderableWidget(new ModernSettingSlider(
                col1X, startY + slStep * 3, colW, slH, "Opacidad", initOpacity,
                val -> Math.round((0.20f + (float) (val * 0.80f)) * 100) + "%",
                val -> {
                    MusicHudOverlay.HUD_OPACITY = Math.round((0.20f + (float) (val * 0.80f)) * 100.0f) / 100.0f;
                    PlayCraftConfig.save();
                }
        ));

        int togglesY = startY + slStep * 4 + 2;
        this.addRenderableWidget(new ModernButton(
                col1X, togglesY, colW, btnH,
                MusicHudOverlay.HUD_ENABLED ? ModernButton.Variant.TOGGLE_ACTIVE : ModernButton.Variant.SECONDARY,
                PlayCraftIcons.Icon.DISC,
                MusicHudOverlay.HUD_ENABLED ? "HUD: Visible" : "HUD: Oculto",
                b -> {
                    MusicHudOverlay.HUD_ENABLED = !MusicHudOverlay.HUD_ENABLED;
                    PlayCraftConfig.save();
                    this.rebuildWidgets();
                }
        ));

        this.addRenderableWidget(new ModernButton(
                col1X, togglesY + btnStep, colW, btnH,
                PlayCraftConfig.MUTE_MINECRAFT_MUSIC ? ModernButton.Variant.TOGGLE_ACTIVE : ModernButton.Variant.SECONDARY,
                PlayCraftIcons.Icon.MUTE,
                PlayCraftConfig.MUTE_MINECRAFT_MUSIC
                        ? (ch ? "Vanilla: Silencio" : "Música Vanilla: Silenciada")
                        : (ch ? "Vanilla: Activa" : "Música Vanilla: Activa"),
                b -> {
                    PlayCraftConfig.MUTE_MINECRAFT_MUSIC = !PlayCraftConfig.MUTE_MINECRAFT_MUSIC;
                    PlayCraftConfig.save();
                    this.rebuildWidgets();
                }
        ));

        this.addRenderableWidget(new ModernButton(
                col1X, togglesY + btnStep * 2, colW, btnH,
                ModernButton.Variant.DANGER, PlayCraftIcons.Icon.RECONNECT, "Restablecer",
                b -> {
                    MusicHudOverlay.HUD_X = 12;
                    MusicHudOverlay.HUD_Y = 12;
                    MusicHudOverlay.HUD_SCALE = 1.0f;
                    MusicHudOverlay.HUD_OPACITY = 0.88f;
                    MusicHudOverlay.HUD_ENABLED = true;
                    PlayCraftConfig.MUTE_MINECRAFT_MUSIC = true;
                    PlayCraftConfig.USE_MODERN_FONT = true;
                    MusicHudOverlay.CURRENT_THEME = MusicHudOverlay.ColorTheme.DYNAMIC;
                    MusicHudOverlay.NOTIFICATIONS_ENABLED = false;
                    MusicHudOverlay.NOTIFICATION_X = 12;
                    MusicHudOverlay.NOTIFICATION_Y = 76;
                    MusicHudOverlay.NOTIFICATION_SCALE = 0.9f;
                    PlayCraftConfig.save();
                    this.rebuildWidgets();
                }
        ));

        // Columna 2
        themeBtn = this.addRenderableWidget(new ModernButton(
                col2X, startY, colW, btnH,
                ModernButton.Variant.TOGGLE_ACTIVE, PlayCraftIcons.Icon.STAR_FILLED,
                "Tema: " + MusicHudOverlay.CURRENT_THEME.displayName,
                b -> {
                    MusicHudOverlay.ColorTheme[] themes = MusicHudOverlay.ColorTheme.values();
                    MusicHudOverlay.CURRENT_THEME = themes[(MusicHudOverlay.CURRENT_THEME.ordinal() + 1) % themes.length];
                    if (MusicHudOverlay.CURRENT_THEME == MusicHudOverlay.ColorTheme.DYNAMIC) {
                        TrackInfo cur = ClientAudioEngine.getInstance().getCurrentTrack();
                        if (cur != null) {
                            Integer rgb = ThumbnailTextureManager.getCachedDominantRgb(cur.thumbnailUrl());
                            if (rgb != null) MusicHudOverlay.updateDynamicColorFromRgb(rgb);
                        }
                    }
                    PlayCraftConfig.save();
                    this.rebuildWidgets();
                }
        ));

        fontBtn = this.addRenderableWidget(new ModernButton(
                col2X, startY + btnStep, colW, btnH,
                PlayCraftConfig.USE_MODERN_FONT ? ModernButton.Variant.TOGGLE_ACTIVE : ModernButton.Variant.SECONDARY,
                PlayCraftIcons.Icon.EDIT,
                PlayCraftConfig.USE_MODERN_FONT ? "Fuente: Moderna" : "Fuente: Minecraft",
                b -> {
                    PlayCraftConfig.USE_MODERN_FONT = !PlayCraftConfig.USE_MODERN_FONT;
                    PlayCraftConfig.save();
                    this.rebuildWidgets();
                }
        ));

        this.addRenderableWidget(new ModernButton(
                col2X, startY + btnStep * 2, colW, btnH,
                MusicHudOverlay.NOTIFICATIONS_ENABLED ? ModernButton.Variant.TOGGLE_ACTIVE : ModernButton.Variant.SECONDARY,
                PlayCraftIcons.Icon.AUTOPLAY,
                MusicHudOverlay.NOTIFICATIONS_ENABLED ? "Avisos: Activos" : "Avisos: Ocultos",
                b -> {
                    MusicHudOverlay.NOTIFICATIONS_ENABLED = !MusicHudOverlay.NOTIFICATIONS_ENABLED;
                    PlayCraftConfig.save();
                    this.rebuildWidgets();
                }
        ));

        int dualY = startY + btnStep * 3 + 2;
        int halfColW = (colW - 4) / 2;
        int maxNotifX = Math.max(0, this.width - 140);
        int maxNotifY = Math.max(0, this.height - 20);
        double initNotifX = maxNotifX > 0 ? Math.min(1.0, (double) MusicHudOverlay.NOTIFICATION_X / maxNotifX) : 0.0;
        double initNotifY = maxNotifY > 0 ? Math.min(1.0, (double) MusicHudOverlay.NOTIFICATION_Y / maxNotifY) : 0.0;

        this.addRenderableWidget(new ModernSettingSlider(
                col2X, dualY, halfColW, slH, "Av. X", initNotifX,
                val -> {
                    int m = Math.max(0, this.width - 140);
                    return (int) (val * m) + "px";
                },
                val -> {
                    int m = Math.max(0, this.width - 140);
                    MusicHudOverlay.NOTIFICATION_X = (int) (val * m);
                    PlayCraftConfig.save();
                }
        ));

        this.addRenderableWidget(new ModernSettingSlider(
                col2X + halfColW + 4, dualY, colW - halfColW - 4, slH, "Av. Y", initNotifY,
                val -> {
                    int m = Math.max(0, this.height - 20);
                    return (int) (val * m) + "px";
                },
                val -> {
                    int m = Math.max(0, this.height - 20);
                    MusicHudOverlay.NOTIFICATION_Y = (int) (val * m);
                    PlayCraftConfig.save();
                }
        ));

        double initNotifScale = Math.max(0.0, Math.min(1.0, (MusicHudOverlay.NOTIFICATION_SCALE - 0.60f) / 0.90f));
        this.addRenderableWidget(new ModernSettingSlider(
                col2X, dualY + slStep, colW, slH, "Escala Avisos", initNotifScale,
                val -> Math.round((0.60f + (float) (val * 0.90f)) * 100) + "%",
                val -> {
                    MusicHudOverlay.NOTIFICATION_SCALE = Math.round((0.60f + (float) (val * 0.90f)) * 100.0f) / 100.0f;
                    PlayCraftConfig.save();
                }
        ));

        int depCardY = dualY + slStep * 2 + 3;
        checkUpdatesBtn = this.addRenderableWidget(new ModernButton(
                col2X + 6, depCardY + 17, colW - 12, 18,
                ModernButton.Variant.SECONDARY, PlayCraftIcons.Icon.RECONNECT,
                isCheckingUpdates ? "Comprobando..." : "Comprobar yt-dlp",
                b -> triggerYtdlpUpdateCheck()
        ));
        checkUpdatesBtn.active = !isCheckingUpdates;
    }

    private void triggerYtdlpUpdateCheck() {
        if (isCheckingUpdates) return;
        isCheckingUpdates = true;
        binaryUpdateStatus = "Consultando versión oficial de yt-dlp...";
        binaryUpdateHint = "";
        if (checkUpdatesBtn != null) {
            checkUpdatesBtn.setLabel("Comprobando...");
            checkUpdatesBtn.active = false;
        }
        Minecraft mc = Minecraft.getInstance();
        BinaryLocator.checkYtdlpUpdateAsync().thenAccept(res -> mc.execute(() -> {
            isCheckingUpdates = false;
            if (res != null) {
                binaryUpdateStatus = cleanDiagnosticText(res.statusLine());
                binaryUpdateHint = cleanDiagnosticText(res.hintLine());
            }
            if (mc.gui.screen() instanceof YouTubeSearchScreen activeScreen) {
                activeScreen.rebuildWidgets();
            }
        }));
    }

    private static String cleanDiagnosticText(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("§.", "")
                .replace("✓ ", "")
                .replace("✔ ", "")
                .replace("⚠ ", "")
                .replace("✗ ", "")
                .replace("✓", "")
                .replace("✔", "")
                .trim();
    }

    // ==================== LIVE SYNC ====================
    public void onSyncUpdate() {
        updateLiveControls();
    }

    private void updateLiveControls() {
        ClientAudioEngine engine = ClientAudioEngine.getInstance();
        TrackInfo cur = engine.getCurrentTrack();
        if (cur != null) {
            lastNonNullTrackTimestamp = System.currentTimeMillis();
        }
        boolean ch = isCompactH();

        if (navQueueBtn != null) {
            navQueueBtn.setLabel(getQueueNavLabel());
        }
        if (currentTab == Tab.NOW_PLAYING) {
            if (playPauseBtn != null) {
                playPauseBtn.setIcon(engine.isPlaying() ? PlayCraftIcons.Icon.PAUSE : PlayCraftIcons.Icon.PLAY);
            }
            if (loopBtn != null) {
                int ord = engine.getLoopModeOrdinal();
                loopBtn.setLabel(getLoopLabel(ord));
                loopBtn.setVariant(ord > 0 ? ModernButton.Variant.TOGGLE_ACTIVE : ModernButton.Variant.SECONDARY);
            }
            if (autoplayBtn != null) {
                boolean on = engine.isAutoplayEnabled();
                autoplayBtn.setVariant(on ? ModernButton.Variant.TOGGLE_ACTIVE : ModernButton.Variant.SECONDARY);
            }
            if (favHeroBtn != null) {
                favHeroBtn.active = cur != null;
                boolean isFav = cur != null && isTrackInFavorites(cur);
                favHeroBtn.setVariant(isFav ? ModernButton.Variant.TOGGLE_ACTIVE : ModernButton.Variant.SECONDARY);
                favHeroBtn.setIcon(isFav ? PlayCraftIcons.Icon.STAR_FILLED : PlayCraftIcons.Icon.STAR);
                favHeroBtn.setLabel(getFavButtonLabel(isFav, ch));
            }
            updateMuteControls();
        } else if (currentTab == Tab.QUEUE) {
            if (queueSubBtn != null) queueSubBtn.setLabel("Cola (" + engine.getQueue().size() + ")");
            if (historySubBtn != null) historySubBtn.setLabel((ch ? "Hist. (" : "Historial (") + engine.getHistory().size() + ")");
            if (rerollAutoplayBtn != null) rerollAutoplayBtn.active = engine.isAutoplayEnabled() && cur != null;
        }
    }

    private void updateMuteControls() {
        ClientAudioEngine eng = ClientAudioEngine.getInstance();
        if (muteBtn != null) {
            muteBtn.setIcon(eng.isMuted() ? PlayCraftIcons.Icon.MUTE : PlayCraftIcons.Icon.VOLUME);
            muteBtn.setVariant(eng.isMuted() ? ModernButton.Variant.DANGER : ModernButton.Variant.SECONDARY);
        }
        if (volSlider != null && !volSlider.isDragging()) {
            volSlider.setProgressDirect(eng.isMuted() ? 0.0 : eng.getVolume());
        }
    }

    // ==================== RENDER LOOP ====================
    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0x7806080C);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        this.dynamicClickZones.clear();
        this.modalClickZones.clear();
        this.pendingTooltipText = null;

        boolean modalBlocking = this.showingChangelog || this.showingPlaylistPicker;
        int effMouseX = modalBlocking ? -1000 : mouseX;
        int effMouseY = modalBlocking ? -1000 : mouseY;

        int activeAccent = MusicHudOverlay.getActiveAccentColor();
        ClientAudioEngine engine = ClientAudioEngine.getInstance();
        TrackInfo currentTrack = engine.getCurrentTrack();
        if (currentTrack != null) {
            lastNonNullTrackTimestamp = System.currentTimeMillis();
        }

        if (posSlider != null && !posSlider.isDragging() && currentTrack != null && currentTrack.durationMs() > 0) {
            posSlider.setProgressDirect((double) MusicHudOverlay.getLivePositionMs() / (double) currentTrack.durationMs());
        }

        // 1. Ventana principal redondeada + barra lateral oscura
        ModernUI.drawRoundedCard(context, panelX, panelY, panelW, panelH, 8,
                ModernUI.BG_OUTER, ModernUI.withAlpha(activeAccent, 115));

        ModernUI.fillRoundedRect(context, panelX + 1, panelY + 1, sidebarW - 1, panelH - 2, 7, ModernUI.BG_SIDEBAR);
        context.fill(panelX + sidebarW, panelY + 1, panelX + sidebarW + 1, panelY + panelH - 1, ModernUI.BORDER_SUBTLE);

        // Cabecera de marca en Sidebar adaptada a sidebarW
        if (sidebarW < 115) {
            PlayCraftIcons.draw(context, PlayCraftIcons.Icon.DISC, panelX + 8, panelY + 10, 11, activeAccent);
            ModernUI.drawBold(context, this.font, "PlayCraft", panelX + 22, panelY + 10, ModernUI.TEXT_PRIMARY, false);
            int badgeX = panelX + sidebarW - 29;
            ModernUI.drawRoundedCard(context, badgeX, panelY + 9, 23, 11, 3,
                    ModernUI.withAlpha(activeAccent, 42), ModernUI.withAlpha(activeAccent, 140));
            ModernUI.drawCenteredBold(context, this.font, "v2", badgeX + 11, panelY + 10, activeAccent);
            ModernUI.drawText(context, this.font, "Music Center", panelX + 22, panelY + 22, ModernUI.TEXT_MUTED, false);
        } else {
            PlayCraftIcons.draw(context, PlayCraftIcons.Icon.DISC, panelX + 11, panelY + 11, 13, activeAccent);
            ModernUI.drawBold(context, this.font, "PlayCraft", panelX + 28, panelY + 11, ModernUI.TEXT_PRIMARY, false);
            int badgeX = panelX + 86;
            ModernUI.drawRoundedCard(context, badgeX, panelY + 10, 26, 12, 3,
                    ModernUI.withAlpha(activeAccent, 42), ModernUI.withAlpha(activeAccent, 140));
            ModernUI.drawCenteredBold(context, this.font, "v2.0", badgeX + 13, panelY + 12, activeAccent);
            ModernUI.drawText(context, this.font, "Music Center", panelX + 28, panelY + 23, ModernUI.TEXT_MUTED, false);
        }

        // Mini-reproductor inferior de la barra lateral
        renderSidebarMiniPlayer(context, effMouseX, effMouseY, currentTrack, engine, activeAccent);

        // Cabecera superior del contenido
        renderContentHeader(context, currentTrack, engine);

        // Renderizado de vista activa
        switch (currentTab) {
            case NOW_PLAYING -> renderNowPlaying(context, currentTrack, engine, activeAccent);
            case SEARCH -> renderSearch(context, effMouseX, effMouseY, activeAccent);
            case QUEUE -> renderQueue(context, effMouseX, effMouseY, engine, activeAccent);
            case PLAYLISTS -> renderPlaylists(context, effMouseX, effMouseY, activeAccent);
            case SETTINGS -> renderSettings(context, activeAccent);
        }

        if (!BinaryLocator.hasRequiredBinaries()) {
            renderMissingDependenciesBanner(context);
        }

        super.extractRenderState(context, effMouseX, effMouseY, delta);

        if (!modalBlocking && this.pendingTooltipText != null && !this.pendingTooltipText.isEmpty()) {
            renderFloatingTooltip(context, this.pendingTooltipText, this.pendingTooltipX, this.pendingTooltipY, activeAccent);
        }

        if (this.showingPlaylistPicker && this.pickerTargetTrack != null) {
            renderPlaylistPickerModal(context, mouseX, mouseY, activeAccent);
        }
        if (this.showingChangelog) {
            renderChangelogModal(context, mouseX, mouseY, activeAccent);
        }

        requestCustomHoverCursor(context, modalBlocking, mouseX, mouseY, effMouseX, effMouseY);
    }

    private void requestCustomHoverCursor(GuiGraphicsExtractor context, boolean modalBlocking,
                                          int mouseX, int mouseY, int effMouseX, int effMouseY) {
        if (modalBlocking) {
            for (ClickRow row : modalClickZones) {
                if (row.contains(mouseX, mouseY)) {
                    context.requestCursor(CursorTypes.POINTING_HAND);
                    return;
                }
            }
            return;
        }
        if (effMouseX >= miniPlayerX && effMouseX < miniPlayerX + miniPlayerW
                && effMouseY >= miniPlayerY && effMouseY < miniPlayerY + miniPlayerH) {
            context.requestCursor(CursorTypes.POINTING_HAND);
            return;
        }
        for (ClickRow row : dynamicClickZones) {
            if (row.contains(effMouseX, effMouseY)) {
                context.requestCursor(CursorTypes.POINTING_HAND);
                return;
            }
        }
    }

    private void renderSidebarMiniPlayer(GuiGraphicsExtractor context, int mouseX, int mouseY,
                                         TrackInfo track, ClientAudioEngine engine, int activeAccent) {
        boolean cv = isCompactV();
        this.miniPlayerX = panelX + 7;
        this.miniPlayerW = sidebarW - 14;
        this.miniPlayerH = cv ? 48 : 54;
        this.miniPlayerY = panelY + panelH - miniPlayerH - 6;

        boolean hovered = mouseX >= miniPlayerX && mouseX < miniPlayerX + miniPlayerW
                && mouseY >= miniPlayerY && mouseY < miniPlayerY + miniPlayerH;
        ModernUI.drawRoundedCard(context, miniPlayerX, miniPlayerY, miniPlayerW, miniPlayerH, 5,
                hovered ? ModernUI.BG_HOVER : ModernUI.BG_SURFACE,
                hovered ? ModernUI.withAlpha(activeAccent, 160) : ModernUI.BORDER_SUBTLE);

        if (track == null) {
            if (isWaitingForAutoplay(engine)) {
                int dots = (int) ((System.currentTimeMillis() / 350L) % 4);
                PlayCraftIcons.draw(context, PlayCraftIcons.Icon.AUTOPLAY, miniPlayerX + 8, miniPlayerY + (miniPlayerH - 14) / 2, 14, 0xFFFBBF24);
                ModernUI.drawBold(context, this.font, "Autoplay", miniPlayerX + 27, miniPlayerY + (miniPlayerH / 2) - 9, 0xFFFBBF24, false);
                ModernUI.drawText(context, this.font, "Buscando" + ".".repeat(dots), miniPlayerX + 27, miniPlayerY + (miniPlayerH / 2) + 2, ModernUI.TEXT_SECONDARY, false);
            } else {
                PlayCraftIcons.draw(context, PlayCraftIcons.Icon.DISC, miniPlayerX + 8, miniPlayerY + (miniPlayerH - 14) / 2, 14, ModernUI.TEXT_MUTED);
                ModernUI.drawText(context, this.font, "Sin música", miniPlayerX + 27, miniPlayerY + (miniPlayerH / 2) - 9, ModernUI.TEXT_SECONDARY, false);
                ModernUI.drawText(context, this.font, "En espera", miniPlayerX + 27, miniPlayerY + (miniPlayerH / 2) + 2, ModernUI.TEXT_MUTED, false);
            }
            return;
        }

        Identifier thumbId = ThumbnailTextureManager.getThumbnail(track.thumbnailUrl());
        int discSize = cv ? 22 : 26;
        int discPad = 7;
        int cx = miniPlayerX + discPad + discSize / 2;
        int cy = miniPlayerY + 7 + discSize / 2;
        context.pose().pushMatrix();
        context.pose().translate(cx, cy);
        context.pose().rotate((float) Math.toRadians(MusicHudOverlay.getDiscRotationAngle()));
        if (thumbId != null) {
            context.blit(RenderPipelines.GUI_TEXTURED, thumbId, -discSize / 2, -discSize / 2, 0.0f, 0.0f, discSize, discSize, discSize, discSize);
        }
        context.pose().popMatrix();

        int tx = miniPlayerX + discPad + discSize + 6;
        int maxTW = Math.max(20, (miniPlayerX + miniPlayerW - 5) - tx);
        String author = ModernUI.truncate(this.font, track.author(), maxTW);
        ModernUI.drawScrollingBold(context, this.font, track.title(), tx, miniPlayerY + 7, maxTW, ModernUI.TEXT_PRIMARY);
        ModernUI.drawText(context, this.font, author, tx, miniPlayerY + 18, ModernUI.TEXT_SECONDARY, false);
        if (!cv) {
            ModernUI.drawText(context, this.font, getShortStatusText(engine), tx, miniPlayerY + 30, getStatusColor(engine), false);
        }

        long duration = track.durationMs() > 0 ? track.durationMs() : 180000L;
        float progress = Math.min(1.0f, (float) MusicHudOverlay.getLivePositionMs() / (float) duration);
        int barX = miniPlayerX + 7;
        int barW = miniPlayerW - 14;
        int barY = miniPlayerY + miniPlayerH - 7;
        ModernUI.fillRoundedRect(context, barX, barY, barW, 2, 1, 0xFF232836);
        int fillW = Math.max(0, Math.round(barW * progress));
        if (fillW > 0) {
            ModernUI.fillRoundedRect(context, barX, barY, fillW, 2, 1, activeAccent);
        }
    }

    private void renderContentHeader(GuiGraphicsExtractor context, TrackInfo cur, ClientAudioEngine engine) {
        String viewTitle = switch (currentTab) {
            case NOW_PLAYING -> "Reproducción en Curso";
            case SEARCH -> isSearchMode ? "Explorar y Buscar" : "Importar Enlace";
            case QUEUE -> isHistorySubTab ? "Historial Reciente" : "Cola de Reproducción";
            case PLAYLISTS -> {
                if (selectedPlaylistId != null) {
                    SavedPlaylist pl = SavedPlaylistsManager.getPlaylistById(selectedPlaylistId);
                    yield pl != null ? pl.name : "Detalle de Playlist";
                }
                yield "Mis Playlists";
            }
            case SETTINGS -> "Ajustes";
        };

        boolean waitingAuto = cur == null && isWaitingForAutoplay(engine);
        String chip = cur != null ? getShortStatusText(engine) : (waitingAuto ? "Buscando Auto..." : "Sin reproducción");
        int chipColor = cur != null ? getStatusColor(engine) : (waitingAuto ? 0xFFFBBF24 : ModernUI.TEXT_MUTED);
        int chipW = Math.min(108, ModernUI.width(this.font, chip) + 18);
        int chipX = panelX + panelW - 55 - chipW;

        int titleStartX = (currentTab == Tab.PLAYLISTS && selectedPlaylistId != null)
                ? contentX + 66 : contentX;
        int maxTitleW = Math.max(50, chipX - titleStartX - 8);
        String drawnTitle = ModernUI.truncateBold(this.font, viewTitle, maxTitleW);
        ModernUI.drawBold(context, this.font, drawnTitle, titleStartX, panelY + 14, ModernUI.TEXT_PRIMARY, false);

        ModernUI.drawRoundedCard(context, chipX, panelY + 10, chipW, 17, 4,
                ModernUI.BG_SURFACE, ModernUI.withAlpha(chipColor, 110));
        context.fill(chipX + 6, panelY + 17, chipX + 10, panelY + 21, chipColor);
        ModernUI.drawText(context, this.font, ModernUI.truncate(this.font, chip, chipW - 16),
                chipX + 13, panelY + 14, chipColor, false);

        context.fill(contentX, panelY + 34, contentX + contentW, panelY + 35, ModernUI.BORDER_SUBTLE);
    }

    // ==================== RENDER VISTA 1: NOW PLAYING ====================
    private void renderNowPlaying(GuiGraphicsExtractor context, TrackInfo track, ClientAudioEngine engine, int activeAccent) {
        boolean cv = isCompactV();
        int heroX = contentX;
        int heroY = panelY + 39;
        int heroW = contentW;
        int heroH = cv ? 78 : 92;

        ModernUI.drawRoundedCard(context, heroX, heroY, heroW, heroH, 6,
                ModernUI.BG_SURFACE, ModernUI.withAlpha(activeAccent, 95));

        if (track != null) {
            Identifier bannerId = ThumbnailTextureManager.getPlaylistBanner(track.thumbnailUrl());
            if (bannerId != null) {
                context.blit(RenderPipelines.GUI_TEXTURED, bannerId,
                        heroX + 1, heroY + 1, 0.0f, 0.0f, heroW - 2, heroH - 2, heroW - 2, heroH - 2, 0x3CFFFFFF);
                context.fillGradient(heroX + 1, heroY + 1, heroX + heroW - 1, heroY + heroH - 1, 0xB80E1015, 0xEA0E1015);
                ModernUI.drawRoundedBorder(context, heroX, heroY, heroW, heroH, 6, ModernUI.withAlpha(activeAccent, 110));
            }

            int discSize = cv ? 54 : 68;
            Identifier thumbId = ThumbnailTextureManager.getThumbnail(track.thumbnailUrl());
            int cx = heroX + 10 + discSize / 2;
            int cy = heroY + (heroH - discSize) / 2 + discSize / 2;
            context.pose().pushMatrix();
            context.pose().translate(cx, cy);
            context.pose().rotate((float) Math.toRadians(MusicHudOverlay.getDiscRotationAngle()));
            if (thumbId != null) {
                context.blit(RenderPipelines.GUI_TEXTURED, thumbId, -discSize / 2, -discSize / 2, 0.0f, 0.0f, discSize, discSize, discSize, discSize);
            }
            context.pose().popMatrix();

            int metaOffset = cv ? 74 : 94;
            int metaX = heroX + metaOffset;
            int metaW = Math.max(120, heroW - metaOffset - 10);
            String statusText = getLongStatusText(engine);
            int statusColor = getStatusColor(engine);
            int pillW = Math.min(metaW, ModernUI.boldWidth(this.font, statusText) + 18);
            ModernUI.drawRoundedCard(context, metaX, heroY + 7, pillW, 14, 3,
                    ModernUI.withAlpha(statusColor, 32), ModernUI.withAlpha(statusColor, 130));
            context.fill(metaX + 5, heroY + 12, metaX + 8, heroY + 15, statusColor);
            ModernUI.drawBold(context, this.font, ModernUI.truncateBold(this.font, statusText, pillW - 14),
                    metaX + 11, heroY + 10, statusColor, false);

            int titleY = heroY + (cv ? 24 : 28);
            ModernUI.drawScrollingBold(context, this.font, track.title(), metaX, titleY, metaW, ModernUI.TEXT_PRIMARY);

            String authorLine = track.author() != null && !track.author().isEmpty() ? track.author() : "YouTube";
            int authorY = heroY + (cv ? 36 : 42);
            ModernUI.drawText(context, this.font,
                    ModernUI.truncate(this.font, authorLine + "  |  " + formatDuration(track.durationMs()), metaW),
                    metaX, authorY, ModernUI.TEXT_SECONDARY, false);
        } else if (isWaitingForAutoplay(engine)) {
            int discS = cv ? 32 : 42;
            int dots = (int) ((System.currentTimeMillis() / 350L) % 4);
            PlayCraftIcons.draw(context, PlayCraftIcons.Icon.AUTOPLAY, heroX + 18, heroY + (heroH - discS) / 2, discS, 0xFFFBBF24);
            int tx = heroX + 26 + discS;
            ModernUI.drawBold(context, this.font, "Autoplay buscando siguiente canción" + ".".repeat(dots),
                    tx, heroY + (cv ? 18 : 26), 0xFFFBBF24, false);
            ModernUI.drawText(context, this.font,
                    ModernUI.truncate(this.font, "Esperando la siguiente pista recomendada...", contentW - (tx - heroX) - 10),
                    tx, heroY + (cv ? 32 : 41), ModernUI.TEXT_SECONDARY, false);
        } else {
            int discS = cv ? 32 : 42;
            PlayCraftIcons.draw(context, PlayCraftIcons.Icon.DISC, heroX + 18, heroY + (heroH - discS) / 2, discS, ModernUI.TEXT_MUTED);
            int tx = heroX + 26 + discS;
            ModernUI.drawBold(context, this.font, "No hay ninguna pista reproduciéndose",
                    tx, heroY + (cv ? 18 : 26), ModernUI.TEXT_PRIMARY, false);
            ModernUI.drawText(context, this.font,
                    ModernUI.truncate(this.font, "Busca una canción o abre tus playlists para iniciar.", contentW - (tx - heroX) - 10),
                    tx, heroY + (cv ? 32 : 41), ModernUI.TEXT_SECONDARY, false);
        }

        if (!cv) {
            ModernUI.drawCentered(context, this.font,
                    ModernUI.truncate(this.font, "Atajos: [.] Hub Musical  |  [,] Controles Rápidos  |  '+ Playlist' para guardar", contentW),
                    contentX + contentW / 2, panelY + panelH - 16, ModernUI.TEXT_MUTED);
        }
    }

    // ==================== RENDER VISTA 2: SEARCH / LINK ====================
    private void renderSearch(GuiGraphicsExtractor context, int mouseX, int mouseY, int activeAccent) {
        boolean cv = isCompactV();
        int subH = cv ? 20 : 22;
        int inputY = panelY + 39 + subH + (cv ? 5 : 7);
        int inputH = cv ? 22 : 24;

        if (!isSearchMode) {
            int toggleY = inputY + inputH + (cv ? 5 : 6);
            int cardY = toggleY + 25;
            int cardH = Math.max(60, (panelY + panelH - 22) - cardY);
            ModernUI.drawRoundedCard(context, contentX, cardY, contentW, cardH, 6,
                    ModernUI.BG_SURFACE, ModernUI.BORDER_SUBTLE);

            PlayCraftIcons.draw(context, PlayCraftIcons.Icon.LINK, contentX + 12, cardY + 11, 13, activeAccent);
            ModernUI.drawBold(context, this.font, "Importación directa desde el cliente", contentX + 30, cardY + 12, ModernUI.TEXT_PRIMARY, false);
            ModernUI.drawText(context, this.font, ModernUI.truncate(this.font, "- Pega enlaces directos de YouTube, youtu.be o YT Music.", contentW - 24),
                    contentX + 12, cardY + 30, ModernUI.TEXT_SECONDARY, false);
            ModernUI.drawText(context, this.font, ModernUI.truncate(this.font, "- Soporta playlists públicas de hasta 50 pistas en un lote.", contentW - 24),
                    contentX + 12, cardY + 44, ModernUI.TEXT_SECONDARY, false);
            if (cardH >= 84) {
                ModernUI.drawText(context, this.font, ModernUI.truncate(this.font, "- Extrae automáticamente el título real al guardarla.", contentW - 24),
                        contentX + 12, cardY + 58, ModernUI.TEXT_SECONDARY, false);
            }
            if (cardH >= 98) {
                ModernUI.drawText(context, this.font, ModernUI.truncate(this.font, "- Resolución ejecutada desde tu IP evitando bloqueos cloud.", contentW - 24),
                        contentX + 12, cardY + 72, ModernUI.TEXT_MUTED, false);
            }

            renderStatusBannerIfAny(context, panelY + panelH - 17, contentW);
            return;
        }

        int listY = inputY + inputH + (cv ? 3 : 6);
        int footerY = panelY + panelH - 24;
        int availH = Math.max(60, (footerY - 3) - listY);

        if (isSearching) {
            ModernUI.drawRoundedCard(context, contentX, listY, contentW, availH, 6,
                    ModernUI.BG_SURFACE, ModernUI.BORDER_SUBTLE);
            int dots = (int) ((System.currentTimeMillis() / 300L) % 4);
            ModernUI.drawCenteredBold(context, this.font, "Buscando en YouTube" + ".".repeat(dots),
                    contentX + contentW / 2, listY + (availH / 2) - 10, activeAccent);
            ModernUI.drawCentered(context, this.font,
                    ModernUI.truncate(this.font, "Obteniendo carátulas y metadatos en segundo plano", contentW - 24),
                    contentX + contentW / 2, listY + (availH / 2) + 5, ModernUI.TEXT_SECONDARY);
            return;
        }

        if (searchResults.isEmpty()) {
            ModernUI.drawRoundedCard(context, contentX, listY, contentW, availH, 6,
                    ModernUI.BG_SURFACE, ModernUI.BORDER_SUBTLE);
            PlayCraftIcons.draw(context, PlayCraftIcons.Icon.SEARCH, contentX + contentW / 2 - 11, listY + (availH / 2) - 24, 22, ModernUI.TEXT_MUTED);
            ModernUI.drawCenteredBold(context, this.font, "Busca cualquier canción o artista",
                    contentX + contentW / 2, listY + (availH / 2) + 6, ModernUI.TEXT_PRIMARY);
            ModernUI.drawCentered(context, this.font,
                    ModernUI.truncate(this.font, "Escribe arriba y pulsa Enter o 'Buscar' para ver resultados.", contentW - 24),
                    contentX + contentW / 2, listY + (availH / 2) + 20, ModernUI.TEXT_SECONDARY);
            renderStatusBannerIfAny(context, panelY + panelH - 17, contentW);
            return;
        }

        int cardH = cv ? 29 : 32;
        int stride = cv ? 31 : 35;
        int visibleRows = Math.max(2, Math.min(4, (availH + 2) / stride));
        this.lastSearchVisibleRows = visibleRows;

        int maxScroll = Math.max(0, searchResults.size() - visibleRows);
        searchScrollOffset = Math.max(0, Math.min(searchScrollOffset, maxScroll));
        int rowW = contentW - (maxScroll > 0 ? 7 : 0);
        int thumbW = cv ? 38 : 42;
        int thumbH = cv ? 21 : 24;

        for (int idx = 0; idx < visibleRows; idx++) {
            int dataIdx = idx + searchScrollOffset;
            if (dataIdx >= searchResults.size()) break;

            TrackInfo item = searchResults.get(dataIdx);
            int ry = listY + idx * stride;
            boolean rowHovered = mouseX >= contentX && mouseX < contentX + rowW && mouseY >= ry && mouseY < ry + cardH;

            ModernUI.drawRoundedCard(context, contentX, ry, rowW, cardH, 4,
                    rowHovered ? ModernUI.BG_HOVER : ModernUI.BG_SURFACE,
                    rowHovered ? ModernUI.BORDER_HOVER : ModernUI.BORDER_SUBTLE);

            drawFullThumbnailBox(context, item.thumbnailUrl(), contentX + 5, ry + (cardH - thumbH) / 2, thumbW, thumbH, activeAccent);

            int textX = contentX + thumbW + 12;
            int textMaxW = Math.max(60, rowW - thumbW - 68);
            int tY = cv ? ry + 4 : ry + 6;
            int sY = cv ? ry + 15 : ry + 18;
            if (rowHovered) {
                ModernUI.drawScrollingBold(context, this.font, item.title(), textX, tY, textMaxW, ModernUI.TEXT_PRIMARY);
            } else {
                ModernUI.drawBold(context, this.font, ModernUI.truncateBold(this.font, item.title(), textMaxW),
                        textX, tY, ModernUI.TEXT_PRIMARY, false);
            }
            String sub = (item.author() != null ? item.author() : "YouTube") + "  |  " + formatDuration(item.durationMs());
            ModernUI.drawText(context, this.font, ModernUI.truncate(this.font, sub, textMaxW),
                    textX, sY, ModernUI.TEXT_SECONDARY, false);

            int btnSize = 19;
            int btnY = ry + (cardH - btnSize) / 2;
            boolean isFav = isTrackInFavorites(item);
            drawInlineIconButton(context, contentX + rowW - 46, btnY, btnSize, btnSize, mouseX, mouseY,
                    isFav ? PlayCraftIcons.Icon.STAR_FILLED : PlayCraftIcons.Icon.STAR,
                    isFav ? activeAccent : ModernUI.TEXT_SECONDARY,
                    isFav ? ModernUI.withAlpha(activeAccent, 32) : ModernUI.BG_ELEVATED,
                    isFav ? "Quitar de Favoritos" : "Guardar en Favoritos",
                    () -> toggleFavoriteTrack(item));

            boolean alreadyAdded = addedSearchUrls.contains(item.streamUrl()) || isTrackRecentlyEnqueued(item.streamUrl());
            drawInlineIconButton(context, contentX + rowW - 24, btnY, btnSize, btnSize, mouseX, mouseY,
                    alreadyAdded ? PlayCraftIcons.Icon.CHECK : PlayCraftIcons.Icon.PLUS,
                    alreadyAdded ? ModernUI.STATUS_ONLINE : (ModernUI.isBrightColor(activeAccent) ? ModernUI.TEXT_DARK : ModernUI.TEXT_PRIMARY),
                    alreadyAdded ? ModernUI.withAlpha(ModernUI.STATUS_ONLINE, 34) : activeAccent,
                    "Añadir a la cola",
                    () -> enqueueTrackFromUi(item, true));

            dynamicClickZones.add(new ClickRow(contentX, ry, rowW - 50, cardH, () -> enqueueTrackFromUi(item, true)));
        }

        if (maxScroll > 0) {
            renderVerticalScrollbar(context, contentX + contentW - 4, listY, visibleRows * stride - 3,
                    searchScrollOffset, searchResults.size(), visibleRows, activeAccent);
        }

        int statusMaxW = (!isSearching && !searchResults.isEmpty() && searchResultLimit < 20 && !lastSearchQuery.trim().isEmpty())
                ? contentW - 116 : contentW;
        renderStatusBannerIfAny(context, panelY + panelH - 19, statusMaxW);
    }

    // ==================== RENDER VISTA 3: QUEUE / HISTORY ====================
    private void renderQueue(GuiGraphicsExtractor context, int mouseX, int mouseY, ClientAudioEngine engine, int activeAccent) {
        boolean cv = isCompactV();
        int topH = cv ? 20 : 22;
        int topY = panelY + 39;

        if (!isHistorySubTab) {
            int apY = topY + topH + (cv ? 4 : 6);
            int apH = cv ? 30 : 34;
            ModernUI.drawRoundedCard(context, contentX, apY, contentW, apH, 5,
                    ModernUI.BG_ELEVATED,
                    engine.isAutoplayEnabled() ? ModernUI.withAlpha(activeAccent, 90) : ModernUI.BORDER_SUBTLE);

            PlayCraftIcons.draw(context, PlayCraftIcons.Icon.AUTOPLAY, contentX + 9, apY + (apH - 13) / 2, 13,
                    engine.isAutoplayEnabled() ? activeAccent : ModernUI.TEXT_MUTED);

            int apMaxW = Math.max(80, contentW - 112);
            TrackInfo apTrack = engine.getAutoplayNextTrack();
            int apLine1Y = apY + (cv ? 5 : 7);
            int apLine2Y = apY + (cv ? 16 : 19);
            if (!engine.isAutoplayEnabled()) {
                ModernUI.drawBold(context, this.font, "Autoplay desactivado", contentX + 28, apLine1Y, ModernUI.TEXT_SECONDARY, false);
                ModernUI.drawText(context, this.font, ModernUI.truncate(this.font, "Actívalo en 'Reproducción' para música continua.", apMaxW),
                        contentX + 28, apLine2Y, ModernUI.TEXT_MUTED, false);
            } else if (apTrack != null) {
                ModernUI.drawBold(context, this.font,
                        ModernUI.truncateBold(this.font, "Sig. Auto: " + apTrack.title(), apMaxW),
                        contentX + 28, apLine1Y, activeAccent, false);
                ModernUI.drawText(context, this.font,
                        ModernUI.truncate(this.font, (apTrack.author() != null ? apTrack.author() : "Mix") + "  |  " + formatDuration(apTrack.durationMs()), apMaxW),
                        contentX + 28, apLine2Y, ModernUI.TEXT_SECONDARY, false);
            } else {
                ModernUI.drawBold(context, this.font, "Autoplay en espera", contentX + 28, apLine1Y, ModernUI.TEXT_PRIMARY, false);
                ModernUI.drawText(context, this.font, ModernUI.truncate(this.font, "Se calculará al avanzar la pista actual.", apMaxW),
                        contentX + 28, apLine2Y, ModernUI.TEXT_MUTED, false);
            }

            List<TrackInfo> queue = engine.getQueue();
            int listY = apY + apH + 5;
            int availH = Math.max(60, (panelY + panelH - 8) - listY);
            if (queue.isEmpty()) {
                ModernUI.drawRoundedCard(context, contentX, listY, contentW, availH, 6, ModernUI.BG_SURFACE, ModernUI.BORDER_SUBTLE);
                ModernUI.drawCenteredBold(context, this.font, "La cola de reproducción está vacía",
                        contentX + contentW / 2, listY + (availH / 2) - 10, ModernUI.TEXT_PRIMARY);
                ModernUI.drawCentered(context, this.font,
                        ModernUI.truncate(this.font, "Añade canciones desde 'Buscar' o carga una Playlist.", contentW - 24),
                        contentX + contentW / 2, listY + (availH / 2) + 5, ModernUI.TEXT_SECONDARY);
                return;
            }

            int cardH = cv ? 30 : 33;
            int stride = cv ? 33 : 36;
            int visibleRows = Math.max(2, Math.min(4, (availH + 3) / stride));
            this.lastQueueVisibleRows = visibleRows;

            int maxScroll = Math.max(0, queue.size() - visibleRows);
            queueScrollOffset = Math.max(0, Math.min(queueScrollOffset, maxScroll));
            int rowW = contentW - (maxScroll > 0 ? 7 : 0);
            int thumbW = cv ? 36 : 40;
            int thumbH = cv ? 21 : 23;

            for (int i = 0; i < visibleRows; i++) {
                int idx = i + queueScrollOffset;
                if (idx >= queue.size()) break;
                TrackInfo t = queue.get(idx);
                int ry = listY + i * stride;
                boolean hovered = mouseX >= contentX && mouseX < contentX + rowW && mouseY >= ry && mouseY < ry + cardH;

                ModernUI.drawRoundedCard(context, contentX, ry, rowW, cardH, 4,
                        hovered ? ModernUI.BG_HOVER : ModernUI.BG_SURFACE,
                        hovered ? ModernUI.BORDER_HOVER : ModernUI.BORDER_SUBTLE);

                ModernUI.drawBold(context, this.font, "#" + (idx + 1), contentX + 7, ry + (cardH - 9) / 2, activeAccent, false);
                drawFullThumbnailBox(context, t.thumbnailUrl(), contentX + 26, ry + (cardH - thumbH) / 2, thumbW, thumbH, activeAccent);

                int tx = contentX + 26 + thumbW + 6;
                int maxTW = Math.max(50, rowW - (tx - contentX) - 72);
                int qTitleY = ry + (cv ? 5 : 6);
                if (hovered) {
                    ModernUI.drawScrollingBold(context, this.font, t.title(), tx, qTitleY, maxTW, ModernUI.TEXT_PRIMARY);
                } else {
                    ModernUI.drawBold(context, this.font, ModernUI.truncateBold(this.font, t.title(), maxTW),
                            tx, qTitleY, ModernUI.TEXT_PRIMARY, false);
                }
                ModernUI.drawText(context, this.font,
                        ModernUI.truncate(this.font, (t.author() != null ? t.author() : "YouTube") + "  |  " + formatDuration(t.durationMs()), maxTW),
                        tx, ry + (cv ? 16 : 18), ModernUI.TEXT_SECONDARY, false);

                final int qIndex = idx;
                int btnS = 18;
                int btnY = ry + (cardH - btnS) / 2;
                if (idx > 0) {
                    drawInlineIconButton(context, contentX + rowW - 65, btnY, btnS, btnS, mouseX, mouseY,
                            PlayCraftIcons.Icon.UP, ModernUI.TEXT_SECONDARY, ModernUI.BG_ELEVATED, "Subir en la cola",
                            () -> ClientPlayNetworking.send(new ModifyQueuePayload(ModifyQueuePayload.Action.MOVE, qIndex, qIndex - 1)));
                }
                if (idx < queue.size() - 1) {
                    drawInlineIconButton(context, contentX + rowW - 44, btnY, btnS, btnS, mouseX, mouseY,
                            PlayCraftIcons.Icon.DOWN, ModernUI.TEXT_SECONDARY, ModernUI.BG_ELEVATED, "Bajar en la cola",
                            () -> ClientPlayNetworking.send(new ModifyQueuePayload(ModifyQueuePayload.Action.MOVE, qIndex, qIndex + 1)));
                }
                drawInlineIconButton(context, contentX + rowW - 23, btnY, btnS, btnS, mouseX, mouseY,
                        PlayCraftIcons.Icon.CLOSE, 0xFFFCA5A5, ModernUI.DANGER_FILL, "Quitar de la cola",
                        () -> ClientPlayNetworking.send(new ModifyQueuePayload(ModifyQueuePayload.Action.REMOVE, qIndex, -1)));
            }

            if (maxScroll > 0) {
                renderVerticalScrollbar(context, contentX + contentW - 4, listY, visibleRows * stride - 3,
                        queueScrollOffset, queue.size(), visibleRows, activeAccent);
            }
            return;
        }

        // Sub-pestaña Historial
        List<TrackInfo> history = engine.getHistory();
        int listY = topY + topH + (cv ? 5 : 7);
        int availH = Math.max(60, (panelY + panelH - 8) - listY);
        if (history.isEmpty()) {
            ModernUI.drawRoundedCard(context, contentX, listY, contentW, availH, 6, ModernUI.BG_SURFACE, ModernUI.BORDER_SUBTLE);
            ModernUI.drawCenteredBold(context, this.font, "El historial está vacío",
                    contentX + contentW / 2, listY + (availH / 2) - 10, ModernUI.TEXT_PRIMARY);
            ModernUI.drawCentered(context, this.font,
                    ModernUI.truncate(this.font, "Las canciones reproducidas aparecerán aquí.", contentW - 24),
                    contentX + contentW / 2, listY + (availH / 2) + 5, ModernUI.TEXT_SECONDARY);
            return;
        }

        int cardH = cv ? 30 : 33;
        int stride = cv ? 33 : 36;
        int visibleRows = Math.max(2, Math.min(5, (availH + 3) / stride));
        this.lastHistoryVisibleRows = visibleRows;

        int maxScroll = Math.max(0, history.size() - visibleRows);
        historyScrollOffset = Math.max(0, Math.min(historyScrollOffset, maxScroll));
        int rowW = contentW - (maxScroll > 0 ? 7 : 0);
        int thumbW = cv ? 36 : 40;
        int thumbH = cv ? 21 : 23;

        for (int i = 0; i < visibleRows; i++) {
            int idx = i + historyScrollOffset;
            if (idx >= history.size()) break;
            TrackInfo t = history.get(idx);
            int ry = listY + i * stride;
            boolean hovered = mouseX >= contentX && mouseX < contentX + rowW && mouseY >= ry && mouseY < ry + cardH;

            ModernUI.drawRoundedCard(context, contentX, ry, rowW, cardH, 4,
                    hovered ? ModernUI.BG_HOVER : ModernUI.BG_SURFACE,
                    hovered ? ModernUI.BORDER_HOVER : ModernUI.BORDER_SUBTLE);

            drawFullThumbnailBox(context, t.thumbnailUrl(), contentX + 7, ry + (cardH - thumbH) / 2, thumbW, thumbH, activeAccent);

            int tx = contentX + thumbW + 13;
            int maxTW = Math.max(50, rowW - (tx - contentX) - 54);
            int hTitleY = ry + (cv ? 5 : 6);
            if (hovered) {
                ModernUI.drawScrollingBold(context, this.font, t.title(), tx, hTitleY, maxTW, ModernUI.TEXT_PRIMARY);
            } else {
                ModernUI.drawBold(context, this.font, ModernUI.truncateBold(this.font, t.title(), maxTW),
                        tx, hTitleY, ModernUI.TEXT_PRIMARY, false);
            }
            ModernUI.drawText(context, this.font,
                    ModernUI.truncate(this.font, (t.author() != null ? t.author() : "YouTube") + "  |  " + formatDuration(t.durationMs()), maxTW),
                    tx, ry + (cv ? 16 : 18), ModernUI.TEXT_SECONDARY, false);

            int btnS = 19;
            int btnY = ry + (cardH - btnS) / 2;
            boolean isFav = isTrackInFavorites(t);
            drawInlineIconButton(context, contentX + rowW - 46, btnY, btnS, btnS, mouseX, mouseY,
                    isFav ? PlayCraftIcons.Icon.STAR_FILLED : PlayCraftIcons.Icon.STAR,
                    isFav ? activeAccent : ModernUI.TEXT_SECONDARY,
                    isFav ? ModernUI.withAlpha(activeAccent, 32) : ModernUI.BG_ELEVATED,
                    isFav ? "Quitar de Favoritos" : "Guardar en Favoritos",
                    () -> toggleFavoriteTrack(t));

            boolean addedHist = isTrackRecentlyEnqueued(t.streamUrl());
            drawInlineIconButton(context, contentX + rowW - 24, btnY, btnS, btnS, mouseX, mouseY,
                    addedHist ? PlayCraftIcons.Icon.CHECK : PlayCraftIcons.Icon.PLUS,
                    addedHist ? ModernUI.STATUS_ONLINE : (ModernUI.isBrightColor(activeAccent) ? ModernUI.TEXT_DARK : ModernUI.TEXT_PRIMARY),
                    addedHist ? ModernUI.withAlpha(ModernUI.STATUS_ONLINE, 34) : activeAccent,
                    "Añadir a la cola", () -> enqueueTrackFromUi(t, false));
        }

        if (maxScroll > 0) {
            renderVerticalScrollbar(context, contentX + contentW - 4, listY, visibleRows * stride - 3,
                    historyScrollOffset, history.size(), visibleRows, activeAccent);
        }
    }

    // ==================== RENDER VISTA 4: PLAYLISTS ====================
    private void renderPlaylists(GuiGraphicsExtractor context, int mouseX, int mouseY, int activeAccent) {
        boolean cv = isCompactV();
        if (selectedPlaylistId == null) {
            List<SavedPlaylist> playlists = SavedPlaylistsManager.getPlaylists();
            int barH = cv ? 22 : 24;
            int listY = panelY + 39 + barH + (cv ? 5 : 8);
            int availH = Math.max(70, (panelY + panelH - 18) - listY);
            int cardH = cv ? 35 : 39;
            int stride = cv ? 39 : 43;
            int visibleRows = Math.max(2, Math.min(4, (availH + 4) / stride));
            this.lastPlaylistsVisibleRows = visibleRows;

            int maxScroll = Math.max(0, playlists.size() - visibleRows);
            playlistsScrollOffset = Math.max(0, Math.min(playlistsScrollOffset, maxScroll));
            int rowW = contentW - (maxScroll > 0 ? 7 : 0);
            int thumbW = cv ? 42 : 48;
            int thumbH = cv ? 24 : 27;

            TrackInfo curTrack = ClientAudioEngine.getInstance().getCurrentTrack();

            for (int i = 0; i < visibleRows; i++) {
                int idx = i + playlistsScrollOffset;
                if (idx >= playlists.size()) break;

                SavedPlaylist pl = playlists.get(idx);
                boolean isFav = SavedPlaylistsManager.FAVORITES_ID.equals(pl.id);
                int ry = listY + i * stride;
                boolean hovered = mouseX >= contentX && mouseX < contentX + rowW && mouseY >= ry && mouseY < ry + cardH;

                ModernUI.drawRoundedCard(context, contentX, ry, rowW, cardH, 5,
                        hovered ? ModernUI.BG_HOVER : ModernUI.BG_SURFACE,
                        isFav ? ModernUI.withAlpha(activeAccent, 110) : (hovered ? ModernUI.BORDER_HOVER : ModernUI.BORDER_SUBTLE));

                String coverUrl = (!pl.tracks.isEmpty()) ? pl.tracks.get(0).thumbnailUrl : "";
                Identifier fullThumb = ThumbnailTextureManager.getFullThumbnail(coverUrl);
                int thumbX = contentX + 6;
                int thumbY = ry + (cardH - thumbH) / 2;
                if (fullThumb != null) {
                    context.blit(RenderPipelines.GUI_TEXTURED, fullThumb, thumbX, thumbY, 0.0f, 0.0f, thumbW, thumbH, thumbW, thumbH, 0xFFFFFFFF);
                    context.outline(thumbX, thumbY, thumbW, thumbH, ModernUI.withAlpha(activeAccent, 120));
                } else {
                    ModernUI.drawRoundedCard(context, thumbX, thumbY, thumbW, thumbH, 3, ModernUI.BG_ELEVATED, ModernUI.BORDER_SUBTLE);
                    PlayCraftIcons.draw(context, isFav ? PlayCraftIcons.Icon.STAR_FILLED : PlayCraftIcons.Icon.PLAYLIST,
                            thumbX + (thumbW - 11) / 2, thumbY + (thumbH - 11) / 2, 11, isFav ? activeAccent : ModernUI.TEXT_MUTED);
                }

                // Botones derechos alineados dinámicamente
                int rightX = contentX + rowW - 6;
                int btnY = ry + (cardH - 20) / 2;
                if (!isFav) {
                    int delX = rightX - 20;
                    drawInlineIconButton(context, delX, btnY, 20, 20, mouseX, mouseY,
                            PlayCraftIcons.Icon.TRASH, 0xFFFCA5A5, ModernUI.DANGER_FILL, "Eliminar playlist",
                            () -> {
                                SavedPlaylistsManager.deletePlaylist(pl.id);
                                setStatusFeedback("Playlist eliminada: \"" + pl.name + "\".", ModernUI.TEXT_SECONDARY);
                            });
                    rightX = delX - 4;
                }

                int shufX = rightX - 20;
                drawInlineIconButton(context, shufX, btnY, 20, 20, mouseX, mouseY,
                        PlayCraftIcons.Icon.SHUFFLE, pl.tracks.isEmpty() ? ModernUI.TEXT_MUTED : ModernUI.TEXT_SECONDARY,
                        ModernUI.BG_ELEVATED, "Mezclar y reproducir", () -> playSavedPlaylist(pl, true));

                int playW = 44;
                int playX = shufX - playW - 4;
                drawInlinePillButton(context, playX, btnY, playW, 20, mouseX, mouseY,
                        PlayCraftIcons.Icon.PLAY, "Play",
                        pl.tracks.isEmpty() ? ModernUI.TEXT_MUTED : (ModernUI.isBrightColor(activeAccent) ? ModernUI.TEXT_DARK : ModernUI.TEXT_PRIMARY),
                        pl.tracks.isEmpty() ? ModernUI.BG_ELEVATED : activeAccent,
                        "Reproducir toda la playlist",
                        () -> playSavedPlaylist(pl, false));

                int addCurX = playX - 24;
                drawInlineIconButton(context, addCurX, btnY, 20, 20, mouseX, mouseY,
                        PlayCraftIcons.Icon.PLUS, curTrack == null ? ModernUI.TEXT_MUTED : ModernUI.TEXT_PRIMARY,
                        ModernUI.BG_ELEVATED, "Añadir pista actual a esta playlist", () -> {
                            if (curTrack != null) {
                                boolean added = SavedPlaylistsManager.addTrackToPlaylist(pl.id, curTrack);
                                setStatusFeedback(
                                        added ? "Pista actual añadida a \"" + pl.name + "\"."
                                              : "La pista ya se encontraba en esta playlist.",
                                        added ? ModernUI.STATUS_ONLINE : ModernUI.STATUS_WARN
                                );
                            }
                        });

                int metaX = thumbX + thumbW + 7;
                int titleMaxW = Math.max(40, addCurX - metaX - 6);
                if (isFav) {
                    PlayCraftIcons.draw(context, PlayCraftIcons.Icon.STAR_FILLED, metaX, ry + (cv ? 6 : 7), 10, activeAccent);
                    ModernUI.drawBold(context, this.font, ModernUI.truncateBold(this.font, pl.name, Math.max(20, titleMaxW - 13)),
                            metaX + 13, ry + (cv ? 6 : 8), activeAccent, false);
                } else {
                    ModernUI.drawBold(context, this.font, ModernUI.truncateBold(this.font, pl.name, titleMaxW),
                            metaX, ry + (cv ? 6 : 8), ModernUI.TEXT_PRIMARY, false);
                }

                long totalMs = computePlaylistDurationMs(pl);
                String countLabel = pl.tracks.size() == 1 ? "1 pista" : pl.tracks.size() + " pistas";
                String subLine = countLabel + "  |  " + formatTotalDuration(totalMs);
                ModernUI.drawText(context, this.font, ModernUI.truncate(this.font, subLine, titleMaxW),
                        metaX, ry + (cv ? 19 : 21), ModernUI.TEXT_SECONDARY, false);

                dynamicClickZones.add(new ClickRow(contentX, ry, addCurX - contentX - 3, cardH, () -> {
                    selectedPlaylistId = pl.id;
                    playlistDetailScrollOffset = 0;
                    this.renamingPlaylist = false;
                    this.rebuildWidgets();
                }));
            }

            if (maxScroll > 0) {
                renderVerticalScrollbar(context, contentX + contentW - 4, listY, visibleRows * stride - 4,
                        playlistsScrollOffset, playlists.size(), visibleRows, activeAccent);
            }
            renderStatusBannerIfAny(context, panelY + panelH - 16, contentW);
            return;
        }

        // Vista de detalle de Playlist
        SavedPlaylist pl = SavedPlaylistsManager.getPlaylistById(selectedPlaylistId);
        if (pl == null) return;
        boolean isFav = SavedPlaylistsManager.FAVORITES_ID.equals(pl.id);

        int bannerX = contentX;
        int bannerY = panelY + 39;
        int bannerW = contentW;
        int bannerH = cv ? 54 : 64;
        ModernUI.drawRoundedCard(context, bannerX, bannerY, bannerW, bannerH, 6,
                ModernUI.BG_SURFACE, ModernUI.withAlpha(activeAccent, 120));

        if (!pl.tracks.isEmpty()) {
            Identifier bannerTex = ThumbnailTextureManager.getPlaylistBanner(pl.tracks.get(0).thumbnailUrl);
            if (bannerTex != null) {
                context.blit(RenderPipelines.GUI_TEXTURED, bannerTex,
                        bannerX + 1, bannerY + 1, 0.0f, 0.0f, bannerW - 2, bannerH - 2, bannerW - 2, bannerH - 2, 0xCCFFFFFF);
            }
        }
        context.fillGradient(bannerX + 1, bannerY + 1, bannerX + bannerW - 1, bannerY + bannerH - 1, 0x9E0B0D13, 0xE40B0D13);
        ModernUI.drawRoundedBorder(context, bannerX, bannerY, bannerW, bannerH, 6, ModernUI.withAlpha(activeAccent, 130));

        ModernUI.drawBold(context, this.font,
                isFav ? "FAVORITOS" : "PLAYLIST PERSONALIZADA",
                bannerX + 9, bannerY + 9, activeAccent, false);

        if (!this.renamingPlaylist) {
            int maxTitleW = isFav ? bannerW - 20 : bannerW - 44;
            ModernUI.drawScrollingBold(context, this.font, pl.name, bannerX + 9, bannerY + (cv ? 26 : 31), maxTitleW, ModernUI.TEXT_PRIMARY);
        }

        long totalMs = computePlaylistDurationMs(pl);
        String trackWord = pl.tracks.size() == 1 ? "pista" : "pistas";
        ModernUI.drawText(context, this.font,
                pl.tracks.size() + " " + trackWord + "  |  Duración total: " + formatTotalDuration(totalMs),
                bannerX + 9, bannerY + bannerH - 14, ModernUI.TEXT_SECONDARY, false);

        int listY = bannerY + bannerH + (cv ? 4 : 5);
        int availH = Math.max(60, (panelY + panelH - 15) - listY);
        if (pl.tracks.isEmpty()) {
            ModernUI.drawRoundedCard(context, contentX, listY, contentW, availH, 6, ModernUI.BG_SURFACE, ModernUI.BORDER_SUBTLE);
            ModernUI.drawCenteredBold(context, this.font, "Esta playlist todavía no tiene pistas",
                    contentX + contentW / 2, listY + (availH / 2) - 10, ModernUI.TEXT_PRIMARY);
            ModernUI.drawCentered(context, this.font,
                    ModernUI.truncate(this.font, "Pulsa '+' arriba o añade desde 'Buscar' y 'Reproducción'.", contentW - 24),
                    contentX + contentW / 2, listY + (availH / 2) + 5, ModernUI.TEXT_SECONDARY);
            renderStatusBannerIfAny(context, panelY + panelH - 14, contentW);
            return;
        }

        int cardH = cv ? 29 : 33;
        int stride = cv ? 31 : 36;
        int visibleRows = Math.max(2, Math.min(4, (availH + 2) / stride));
        this.lastPlaylistDetailVisibleRows = visibleRows;

        int maxScroll = Math.max(0, pl.tracks.size() - visibleRows);
        playlistDetailScrollOffset = Math.max(0, Math.min(playlistDetailScrollOffset, maxScroll));
        int rowW = contentW - (maxScroll > 0 ? 7 : 0);
        int thumbW = cv ? 36 : 40;
        int thumbH = cv ? 20 : 23;

        for (int i = 0; i < visibleRows; i++) {
            int idx = i + playlistDetailScrollOffset;
            if (idx >= pl.tracks.size()) break;

            SavedTrack st = pl.tracks.get(idx);
            int ry = listY + i * stride;
            boolean hovered = mouseX >= contentX && mouseX < contentX + rowW && mouseY >= ry && mouseY < ry + cardH;

            ModernUI.drawRoundedCard(context, contentX, ry, rowW, cardH, 4,
                    hovered ? ModernUI.BG_HOVER : ModernUI.BG_SURFACE,
                    hovered ? ModernUI.BORDER_HOVER : ModernUI.BORDER_SUBTLE);

            drawFullThumbnailBox(context, st.thumbnailUrl, contentX + 6, ry + (cardH - thumbH) / 2, thumbW, thumbH, activeAccent);

            int tx = contentX + thumbW + 12;
            int maxTW = Math.max(50, rowW - (tx - contentX) - 52);
            int rowTitleY = ry + (cv ? 4 : 6);
            if (hovered) {
                ModernUI.drawScrollingBold(context, this.font, st.title, tx, rowTitleY, maxTW, ModernUI.TEXT_PRIMARY);
            } else {
                ModernUI.drawBold(context, this.font, ModernUI.truncateBold(this.font, st.title, maxTW),
                        tx, rowTitleY, ModernUI.TEXT_PRIMARY, false);
            }
            ModernUI.drawText(context, this.font,
                    ModernUI.truncate(this.font, (st.author != null ? st.author : "YouTube") + "  |  " + formatDuration(st.durationMs), maxTW),
                    tx, ry + (cv ? 15 : 18), ModernUI.TEXT_SECONDARY, false);

            final int trackIdx = idx;
            TrackInfo tInfo = st.toTrackInfo();
            int btnS = 19;
            int btnY = ry + (cardH - btnS) / 2;
            boolean recentlyAdded = isTrackRecentlyEnqueued(tInfo.streamUrl());
            drawInlineIconButton(context, contentX + rowW - 46, btnY, btnS, btnS, mouseX, mouseY,
                    recentlyAdded ? PlayCraftIcons.Icon.CHECK : PlayCraftIcons.Icon.PLUS,
                    recentlyAdded ? ModernUI.STATUS_ONLINE : (ModernUI.isBrightColor(activeAccent) ? ModernUI.TEXT_DARK : ModernUI.TEXT_PRIMARY),
                    recentlyAdded ? ModernUI.withAlpha(ModernUI.STATUS_ONLINE, 34) : activeAccent,
                    "Añadir solo esta canción a la cola",
                    () -> enqueueTrackFromUi(tInfo, false));

            drawInlineIconButton(context, contentX + rowW - 24, btnY, btnS, btnS, mouseX, mouseY,
                    PlayCraftIcons.Icon.CLOSE, 0xFFFCA5A5, ModernUI.DANGER_FILL,
                    "Quitar de la playlist",
                    () -> {
                        SavedPlaylistsManager.removeTrackFromPlaylist(pl.id, trackIdx);
                        setStatusFeedback("Pista eliminada de la playlist.", ModernUI.TEXT_SECONDARY);
                    });

            dynamicClickZones.add(new ClickRow(contentX, ry, rowW - 50, cardH, () -> enqueueTrackFromUi(tInfo, false)));
        }

        if (maxScroll > 0) {
            renderVerticalScrollbar(context, contentX + contentW - 4, listY, visibleRows * stride - 3,
                    playlistDetailScrollOffset, pl.tracks.size(), visibleRows, activeAccent);
        }
        renderStatusBannerIfAny(context, panelY + panelH - 14, contentW);
    }

    // ==================== RENDER VISTA 5: SETTINGS ====================
    private void renderSettings(GuiGraphicsExtractor context, int activeAccent) {
        boolean cv = isCompactV();
        int colGap = 8;
        int colW = (contentW - colGap) / 2;
        int col1X = contentX;
        int col2X = contentX + colW + colGap;

        int headerY = panelY + (cv ? 38 : 41);
        ModernUI.drawBold(context, this.font, "HUD Y AUDIO", col1X, headerY, activeAccent, false);
        ModernUI.drawBold(context, this.font, "APARIENCIA Y SISTEMA", col2X, headerY, activeAccent, false);

        int startY = panelY + (cv ? 48 : 52);
        int slH = cv ? 23 : 27;
        int slStep = slH + 2;
        int btnH = cv ? 19 : 22;
        int btnStep = btnH + 2;
        int dualY = startY + btnStep * 3 + 2;
        int depCardY = dualY + slStep * 2 + 3;
        int depCardH = Math.max(46, (panelY + panelH - 8) - depCardY);

        ModernUI.drawRoundedCard(context, col2X, depCardY, colW, depCardH, 5, ModernUI.BG_SURFACE, ModernUI.BORDER_SUBTLE);

        boolean hasYtdlp = BinaryLocator.isYtdlpAvailable();
        boolean hasFfmpeg = BinaryLocator.isFfmpegAvailable();
        String ytStr = "yt-dlp: " + (hasYtdlp ? "OK" : "NO");
        String ffStr = "ffmpeg: " + (hasFfmpeg ? "OK" : "NO");
        ModernUI.drawBold(context, this.font, ytStr, col2X + 7, depCardY + 5,
                hasYtdlp ? ModernUI.STATUS_ONLINE : ModernUI.STATUS_ERROR, false);
        ModernUI.drawBold(context, this.font, ffStr, col2X + colW / 2 + 2, depCardY + 5,
                hasFfmpeg ? ModernUI.STATUS_ONLINE : ModernUI.STATUS_ERROR, false);

        int infoY = depCardY + 38;
        int infoMaxW = colW - 14;
        int maxBottom = depCardY + depCardH - 10;
        if (!binaryUpdateStatus.isEmpty()) {
            int linesDrawn = drawWrappedLines(context, binaryUpdateStatus, col2X + 7, infoY, infoMaxW, 2, 10, ModernUI.TEXT_PRIMARY);
            infoY += linesDrawn * 10 + 1;
        }
        if (!binaryUpdateHint.isEmpty() && infoY <= maxBottom) {
            int availLines = Math.max(1, (maxBottom - infoY + 10) / 10);
            drawWrappedLines(context, binaryUpdateHint, col2X + 7, infoY, infoMaxW, Math.min(2, availLines), 10, activeAccent);
        } else if (binaryUpdateStatus.isEmpty()) {
            String ver = BinaryLocator.getYtdlpVersion();
            String verTxt = (ver != null && !ver.isEmpty()) ? "Versión: " + ver : "Comprueba actualizaciones de yt-dlp";
            drawWrappedLines(context, verTxt, col2X + 7, infoY + 1, infoMaxW, 2, 10, ModernUI.TEXT_MUTED);
        }
    }

    private int drawWrappedLines(GuiGraphicsExtractor context, String text, int x, int y, int maxW,
                                 int maxLines, int lineStep, int color) {
        if (text == null || text.isEmpty() || maxLines <= 0 || maxW <= 12) return 0;
        String remaining = text.trim();
        int drawn = 0;
        while (!remaining.isEmpty() && drawn < maxLines) {
            String slice;
            if (ModernUI.width(this.font, remaining) <= maxW) {
                slice = remaining;
                remaining = "";
            } else if (drawn == maxLines - 1) {
                slice = ModernUI.truncate(this.font, remaining, maxW);
                remaining = "";
            } else {
                String fit = this.font.substrByWidth(ModernUI.text(remaining), maxW).getString();
                if (fit.isEmpty()) {
                    slice = ModernUI.truncate(this.font, remaining, maxW);
                    remaining = "";
                } else {
                    int spaceIdx = fit.lastIndexOf(' ');
                    if (spaceIdx > Math.max(3, fit.length() / 3)) {
                        fit = fit.substring(0, spaceIdx);
                    }
                    slice = fit.trim();
                    remaining = remaining.substring(Math.min(remaining.length(), fit.length())).trim();
                }
            }
            ModernUI.drawText(context, this.font, slice, x, y + drawn * lineStep, color, false);
            drawn++;
        }
        return drawn;
    }

    // ==================== BANNER DEPENDENCIAS Y MODALES ====================
    private void renderMissingDependenciesBanner(GuiGraphicsExtractor context) {
        int bannerW = panelW;
        int bannerH = 18;
        int bx = panelX;
        int by = panelY + panelH + 3;
        ModernUI.drawRoundedCard(context, bx, by, bannerW, bannerH, 4, 0xE62B1216, ModernUI.DANGER_BORDER);
        String msg = "Faltan dependencias: "
                + (!BinaryLocator.isYtdlpAvailable() ? "[yt-dlp] " : "")
                + (!BinaryLocator.isFfmpegAvailable() ? "[ffmpeg] " : "")
                + "- Revisa 'Ajustes' o la guía del proyecto.";
        ModernUI.drawCentered(context, this.font, ModernUI.truncate(this.font, msg, bannerW - 16),
                bx + bannerW / 2, by + 5, 0xFFFCA5A5);
    }

    private void renderPlaylistPickerModal(GuiGraphicsExtractor context, int mouseX, int mouseY, int activeAccent) {
        context.fill(0, 0, this.width, this.height, 0xA005060A);
        int mw = Math.min(280, this.width - 20);
        int mh = Math.min(186, this.height - 16);
        int mx = (this.width - mw) / 2;
        int my = (this.height - mh) / 2;

        ModernUI.drawRoundedCard(context, mx, my, mw, mh, 7, ModernUI.BG_OUTER, ModernUI.withAlpha(activeAccent, 180));
        ModernUI.drawBold(context, this.font, "Añadir pista a playlist", mx + 14, my + 12, ModernUI.TEXT_PRIMARY, false);
        ModernUI.drawText(context, this.font,
                ModernUI.truncate(this.font, pickerTargetTrack.title(), mw - 52),
                mx + 14, my + 24, ModernUI.TEXT_SECONDARY, false);

        drawModalIconClose(context, mx + mw - 28, my + 10, mouseX, mouseY, () -> this.showingPlaylistPicker = false);

        List<SavedPlaylist> playlists = SavedPlaylistsManager.getPlaylists();
        int listY = my + 42;
        int visible = Math.max(2, Math.min(4, (mh - 76) / 28));
        int maxScroll = Math.max(0, playlists.size() - visible);
        pickerScrollOffset = Math.max(0, Math.min(pickerScrollOffset, maxScroll));

        for (int i = 0; i < visible; i++) {
            int idx = i + pickerScrollOffset;
            if (idx >= playlists.size()) break;
            SavedPlaylist pl = playlists.get(idx);
            int ry = listY + i * 28;
            boolean hovered = mouseX >= mx + 12 && mouseX < mx + mw - 12 && mouseY >= ry && mouseY < ry + 24;
            ModernUI.drawRoundedCard(context, mx + 12, ry, mw - 24, 24, 4,
                    hovered ? ModernUI.BG_HOVER : ModernUI.BG_SURFACE,
                    hovered ? ModernUI.withAlpha(activeAccent, 160) : ModernUI.BORDER_SUBTLE);
            boolean isFav = SavedPlaylistsManager.FAVORITES_ID.equals(pl.id);
            PlayCraftIcons.draw(context, isFav ? PlayCraftIcons.Icon.STAR_FILLED : PlayCraftIcons.Icon.PLAYLIST,
                    mx + 20, ry + 7, 10, isFav ? activeAccent : ModernUI.TEXT_SECONDARY);
            ModernUI.drawText(context, this.font, ModernUI.truncate(this.font, pl.name, mw - 106),
                    mx + 35, ry + 8, ModernUI.TEXT_PRIMARY, false);
            String count = pl.tracks.size() + " p.";
            ModernUI.drawText(context, this.font, count, mx + mw - 22 - ModernUI.width(this.font, count),
                    ry + 8, ModernUI.TEXT_MUTED, false);

            modalClickZones.add(new ClickRow(mx + 12, ry, mw - 24, 24, () -> {
                boolean added = SavedPlaylistsManager.addTrackToPlaylist(pl.id, pickerTargetTrack);
                MusicHudOverlay.showNotification(added
                        ? "Pista añadida a \"" + pl.name + "\"."
                        : "La pista ya se encontraba en esa playlist.");
                this.showingPlaylistPicker = false;
                updateLiveControls();
            }));
        }

        int cancelW = 90;
        int cancelX = mx + (mw - cancelW) / 2;
        int cancelY = my + mh - 28;
        boolean cancelHover = mouseX >= cancelX && mouseX < cancelX + cancelW && mouseY >= cancelY && mouseY < cancelY + 20;
        ModernUI.drawRoundedCard(context, cancelX, cancelY, cancelW, 20, 4,
                cancelHover ? ModernUI.BG_HOVER : ModernUI.BG_ELEVATED, ModernUI.BORDER_SUBTLE);
        ModernUI.drawCentered(context, this.font, "Cancelar", cancelX + cancelW / 2, cancelY + 6, ModernUI.TEXT_PRIMARY);
        modalClickZones.add(new ClickRow(cancelX, cancelY, cancelW, 20, () -> this.showingPlaylistPicker = false));
    }

    private void renderChangelogModal(GuiGraphicsExtractor context, int mouseX, int mouseY, int activeAccent) {
        context.fill(0, 0, this.width, this.height, 0xAA05060A);
        int mw = Math.min(396, this.width - 20);
        int mh = Math.min(218, this.height - 16);
        int mx = (this.width - mw) / 2;
        int my = (this.height - mh) / 2;

        ModernUI.drawRoundedCard(context, mx, my, mw, mh, 8, ModernUI.BG_OUTER, ModernUI.withAlpha(activeAccent, 185));

        ModernUI.drawRoundedCard(context, mx + 14, my + 10, 134, 15, 3,
                ModernUI.withAlpha(activeAccent, 38), ModernUI.withAlpha(activeAccent, 150));
        ModernUI.drawBold(context, this.font, "NOVEDADES  |  v" + PlayCraftConfig.CURRENT_MOD_VERSION,
                mx + 20, my + 13, activeAccent, false);
        ModernUI.drawBold(context, this.font, "PlayCraftMusic 2.0 — Rediseño Moderno",
                mx + 14, my + 30, ModernUI.TEXT_PRIMARY, false);

        drawModalIconClose(context, mx + mw - 30, my + 10, mouseX, mouseY, this::dismissChangelogModal);
        context.fill(mx + 14, my + 44, mx + mw - 14, my + 45, ModernUI.BORDER_SUBTLE);

        PlayCraftIcons.Icon[] icons = {
                PlayCraftIcons.Icon.DISC,
                PlayCraftIcons.Icon.EDIT,
                PlayCraftIcons.Icon.PLAY,
                PlayCraftIcons.Icon.STAR_FILLED,
                PlayCraftIcons.Icon.PLAYLIST,
                PlayCraftIcons.Icon.AUTOPLAY
        };
        String[] notes = {
                "Tema Dinámico Adaptativo: extrae el color dominante de cada carátula.",
                "Tipografía moderna Noto Sans alternable al instante desde Ajustes.",
                "Barra lateral vertical, tarjetas oscuras y mini-reproductor siempre activo.",
                "Set de 28 iconos vectoriales HD en controles, HUD y menús.",
                "Playlists con banner panorámico de fondo, carátulas 16:9 y edición rápida.",
                "Motor de Audio v2, Autoplay Mix y verificador multiplataforma de yt-dlp."
        };

        int ny = my + 52;
        int okY = my + mh - 30;
        int rowStep = Math.max(16, Math.min(20, (okY - ny - 8) / notes.length));
        for (int i = 0; i < notes.length; i++) {
            int rowY = ny + i * rowStep;
            PlayCraftIcons.draw(context, icons[i], mx + 16, rowY, 11, activeAccent);
            ModernUI.drawText(context, this.font, ModernUI.truncate(this.font, notes[i], mw - 50),
                    mx + 34, rowY + 1, ModernUI.TEXT_SECONDARY, false);
        }

        int okW = 114;
        int okH = 22;
        int okX = mx + mw - okW - 14;
        boolean okHover = mouseX >= okX && mouseX < okX + okW && mouseY >= okY && mouseY < okY + okH;
        int fill = okHover ? ModernUI.brighten(activeAccent, 0.16f) : activeAccent;
        int fg = ModernUI.isBrightColor(activeAccent) ? ModernUI.TEXT_DARK : ModernUI.TEXT_PRIMARY;
        ModernUI.drawRoundedCard(context, okX, okY, okW, okH, 4, fill, ModernUI.brighten(activeAccent, 0.25f));
        PlayCraftIcons.draw(context, PlayCraftIcons.Icon.CHECK, okX + 16, okY + 5, 11, fg);
        ModernUI.drawBold(context, this.font, "Entendido", okX + 33, okY + 7, fg, false);
        modalClickZones.add(new ClickRow(okX, okY, okW, okH, this::dismissChangelogModal));
    }

    private void drawModalIconClose(GuiGraphicsExtractor context, int x, int y, int mouseX, int mouseY, Runnable onCloseAction) {
        boolean hovered = mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;
        ModernUI.drawRoundedCard(context, x, y, 18, 18, 4,
                hovered ? ModernUI.BG_HOVER : ModernUI.BG_ELEVATED,
                hovered ? ModernUI.BORDER_HOVER : ModernUI.BORDER_SUBTLE);
        PlayCraftIcons.draw(context, PlayCraftIcons.Icon.CLOSE, x + 4, y + 4, 10,
                hovered ? ModernUI.TEXT_PRIMARY : ModernUI.TEXT_SECONDARY);
        modalClickZones.add(new ClickRow(x, y, 18, 18, onCloseAction));
    }

    // ==================== HELPERS DE RENDERIZADO Y DATOS ====================
    private void drawFullThumbnailBox(GuiGraphicsExtractor context, String url, int x, int y, int w, int h, int accent) {
        Identifier fullThumb = ThumbnailTextureManager.getFullThumbnail(url);
        if (fullThumb != null) {
            context.blit(RenderPipelines.GUI_TEXTURED, fullThumb, x, y, 0.0f, 0.0f, w, h, w, h, 0xFFFFFFFF);
            context.outline(x, y, w, h, ModernUI.withAlpha(accent, 90));
        } else {
            ModernUI.drawRoundedCard(context, x, y, w, h, 3, ModernUI.BG_ELEVATED, ModernUI.BORDER_SUBTLE);
            PlayCraftIcons.draw(context, PlayCraftIcons.Icon.DISC, x + (w - 10) / 2, y + (h - 10) / 2, 10, ModernUI.TEXT_MUTED);
        }
    }

    private void drawInlineIconButton(GuiGraphicsExtractor context, int x, int y, int w, int h,
                                      int mouseX, int mouseY, PlayCraftIcons.Icon icon, int iconColor, int bgColor,
                                      Runnable action) {
        drawInlineIconButton(context, x, y, w, h, mouseX, mouseY, icon, iconColor, bgColor, null, action);
    }

    private void drawInlineIconButton(GuiGraphicsExtractor context, int x, int y, int w, int h,
                                      int mouseX, int mouseY, PlayCraftIcons.Icon icon, int iconColor, int bgColor,
                                      String tooltip, Runnable action) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int finalBg = hovered ? ModernUI.brighten(bgColor, 0.14f) : bgColor;
        int border = hovered ? ModernUI.BORDER_HOVER : ModernUI.withAlpha(iconColor, 65);
        ModernUI.drawRoundedCard(context, x, y, w, h, 3, finalBg, border);
        int iconSize = Math.min(11, Math.min(w, h) - 6);
        PlayCraftIcons.draw(context, icon, x + (w - iconSize) / 2, y + (h - iconSize) / 2, iconSize, iconColor);
        if (hovered && tooltip != null && !tooltip.isEmpty()) {
            this.pendingTooltipText = tooltip;
            this.pendingTooltipX = mouseX;
            this.pendingTooltipY = mouseY;
        }
        dynamicClickZones.add(new ClickRow(x, y, w, h, action));
    }

    private void drawInlinePillButton(GuiGraphicsExtractor context, int x, int y, int w, int h,
                                      int mouseX, int mouseY, PlayCraftIcons.Icon icon, String label,
                                      int fgColor, int bgColor, String tooltip, Runnable action) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int finalBg = hovered ? ModernUI.brighten(bgColor, 0.14f) : bgColor;
        ModernUI.drawRoundedCard(context, x, y, w, h, 3, finalBg, ModernUI.withAlpha(fgColor, 60));
        PlayCraftIcons.draw(context, icon, x + 5, y + (h - 9) / 2, 9, fgColor);
        ModernUI.drawBold(context, this.font, label, x + 17, y + (h - 9) / 2, fgColor, false);
        if (hovered && tooltip != null && !tooltip.isEmpty()) {
            this.pendingTooltipText = tooltip;
            this.pendingTooltipX = mouseX;
            this.pendingTooltipY = mouseY;
        }
        dynamicClickZones.add(new ClickRow(x, y, w, h, action));
    }

    private void renderFloatingTooltip(GuiGraphicsExtractor context, String text, int mouseX, int mouseY, int accent) {
        int textW = ModernUI.width(this.font, text);
        int boxW = textW + 12;
        int boxH = 16;
        int tx = Math.max(6, Math.min(this.width - boxW - 6, mouseX + 8));
        int ty = Math.max(6, mouseY - boxH - 6);
        ModernUI.drawRoundedCard(context, tx, ty, boxW, boxH, 3, 0xF50B0D13, ModernUI.withAlpha(accent, 175));
        ModernUI.drawText(context, this.font, text, tx + 6, ty + 4, ModernUI.TEXT_PRIMARY, false);
    }

    private static boolean isWaitingForAutoplay(ClientAudioEngine engine) {
        return engine.getCurrentTrack() == null
                && engine.isAutoplayEnabled()
                && !engine.getHistory().isEmpty()
                && engine.getQueue().isEmpty()
                && (System.currentTimeMillis() - lastNonNullTrackTimestamp) < 28000L;
    }

    private static boolean isTrackRecentlyEnqueued(String streamUrl) {
        if (streamUrl == null || streamUrl.isEmpty()) return false;
        Long ts = recentlyEnqueuedTracks.get(streamUrl);
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts > 2200L) {
            recentlyEnqueuedTracks.remove(streamUrl);
            return false;
        }
        return true;
    }

    private void renderVerticalScrollbar(GuiGraphicsExtractor context, int x, int y, int h,
                                         int offset, int total, int visible, int accent) {
        if (total <= visible || h <= 10) return;
        ModernUI.fillRoundedRect(context, x, y, 3, h, 1, 0xFF1F2430);
        int thumbH = Math.max(16, (int) Math.round(h * ((double) visible / total)));
        int maxOff = total - visible;
        int thumbY = y + (maxOff > 0 ? (int) Math.round((h - thumbH) * ((double) offset / maxOff)) : 0);
        ModernUI.fillRoundedRect(context, x, thumbY, 3, thumbH, 1, accent);
    }

    private void renderStatusBannerIfAny(GuiGraphicsExtractor context, int y, int maxW) {
        if (statusBanner.isEmpty()) return;
        if (System.currentTimeMillis() - statusBannerTimestamp > 8000L && !isSearching) return;
        ModernUI.drawText(context, this.font, ModernUI.truncate(this.font, statusBanner, maxW),
                contentX, y, statusBannerColor, false);
    }

    private void enqueueTrackFromUi(TrackInfo item, boolean trackAddedSet) {
        if (item == null || item.streamUrl() == null || item.streamUrl().isEmpty()) return;
        ClientAudioEngine.getInstance().prefetchStreamUrl(item.streamUrl());
        ThumbnailTextureManager.getThumbnail(item.thumbnailUrl());
        ClientPlayNetworking.send(new AddTrackPayload(
                item.streamUrl(), item.title(), item.author(), item.thumbnailUrl(), item.durationMs()
        ));
        recentlyEnqueuedTracks.put(item.streamUrl(), System.currentTimeMillis());
        if (trackAddedSet) {
            addedSearchUrls.add(item.streamUrl());
        }
        MusicHudOverlay.showNotification("Añadida a la cola: " + item.title());
        setStatusFeedback("Pista añadida a la cola: " + item.title(), ModernUI.STATUS_ONLINE);
    }

    private static boolean isTrackInFavorites(TrackInfo track) {
        if (track == null || track.streamUrl() == null || track.streamUrl().isEmpty()) return false;
        SavedPlaylist fav = SavedPlaylistsManager.getOrCreateFavorites();
        for (SavedTrack st : fav.tracks) {
            if (track.streamUrl().equals(st.streamUrl)) return true;
        }
        return false;
    }

    private static void toggleFavoriteTrack(TrackInfo track) {
        if (track == null || track.streamUrl() == null || track.streamUrl().isEmpty()) return;
        SavedPlaylist fav = SavedPlaylistsManager.getOrCreateFavorites();
        for (int i = 0; i < fav.tracks.size(); i++) {
            if (track.streamUrl().equals(fav.tracks.get(i).streamUrl)) {
                SavedPlaylistsManager.removeTrackFromPlaylist(SavedPlaylistsManager.FAVORITES_ID, i);
                MusicHudOverlay.showNotification("Pista eliminada de Favoritos.");
                setStatusFeedback("Pista eliminada de Favoritos.", ModernUI.TEXT_SECONDARY);
                return;
            }
        }
        boolean added = SavedPlaylistsManager.addTrackToPlaylist(SavedPlaylistsManager.FAVORITES_ID, track);
        if (added) {
            MusicHudOverlay.showNotification("Pista añadida a Favoritos.");
            setStatusFeedback("Pista añadida a Favoritos.", ModernUI.STATUS_ONLINE);
        }
    }

    private static String getLoopLabel(int ordinal) {
        return switch (ordinal) {
            case 1 -> "Pista";
            case 2 -> "Cola";
            default -> "Off";
        };
    }

    private static String getShortStatusText(ClientAudioEngine engine) {
        return switch (engine.getPlaybackStatus()) {
            case LOADING -> "Cargando...";
            case RECONNECTING -> "Reconectando...";
            case ERROR -> "Reintentando...";
            case IDLE -> "Detenido";
            case PAUSED -> "En pausa";
            case PLAYING -> engine.isPlaying() ? "En vivo" : "En pausa";
        };
    }

    private static String getLongStatusText(ClientAudioEngine engine) {
        return switch (engine.getPlaybackStatus()) {
            case LOADING -> "Cargando flujo de audio...";
            case RECONNECTING -> "Reconectando audio local...";
            case ERROR -> "Reintentando conexión de audio...";
            case IDLE -> "Reproducción detenida";
            case PAUSED -> "En pausa";
            case PLAYING -> engine.isPlaying() ? "Reproduciendo en vivo" : "En pausa";
        };
    }

    private static int getStatusColor(ClientAudioEngine engine) {
        return switch (engine.getPlaybackStatus()) {
            case LOADING -> 0xFFFBBF24;
            case RECONNECTING -> 0xFFFB923C;
            case ERROR -> ModernUI.STATUS_ERROR;
            case IDLE -> ModernUI.TEXT_MUTED;
            case PAUSED -> ModernUI.STATUS_PAUSED;
            case PLAYING -> engine.isPlaying() ? ModernUI.STATUS_ONLINE : ModernUI.STATUS_PAUSED;
        };
    }

    private static long computePlaylistDurationMs(SavedPlaylist pl) {
        long total = 0L;
        for (SavedTrack t : pl.tracks) {
            if (t.durationMs > 0L) total += t.durationMs;
        }
        return total;
    }

    private static String formatDuration(long ms) {
        if (ms <= 0) return "00:00";
        long sec = ms / 1000;
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    private static String formatTotalDuration(long ms) {
        if (ms <= 0) return "00:00";
        long sec = ms / 1000;
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    // ==================== EVENTOS DE RATÓN Y TECLADO ====================
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (this.showingChangelog || this.showingPlaylistPicker) {
            for (ClickRow row : modalClickZones) {
                if (row.contains(event.x(), event.y())) {
                    row.action().run();
                    return true;
                }
            }
            return true;
        }

        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }

        if (event.x() >= miniPlayerX && event.x() < miniPlayerX + miniPlayerW
                && event.y() >= miniPlayerY && event.y() < miniPlayerY + miniPlayerH) {
            if (currentTab != Tab.NOW_PLAYING) {
                currentTab = Tab.NOW_PLAYING;
                this.renamingPlaylist = false;
                this.rebuildWidgets();
            }
            return true;
        }

        for (ClickRow row : dynamicClickZones) {
            if (row.contains(event.x(), event.y())) {
                row.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.showingChangelog) return true;

        int step = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
        if (step == 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);

        if (this.showingPlaylistPicker) {
            int max = Math.max(0, SavedPlaylistsManager.getPlaylists().size() - 4);
            pickerScrollOffset = Math.max(0, Math.min(max, pickerScrollOffset + step));
            return true;
        }

        if (currentTab == Tab.SEARCH && isSearchMode && !searchResults.isEmpty()) {
            int max = Math.max(0, searchResults.size() - Math.max(1, lastSearchVisibleRows));
            searchScrollOffset = Math.max(0, Math.min(max, searchScrollOffset + step));
            return true;
        }
        if (currentTab == Tab.QUEUE) {
            if (!isHistorySubTab) {
                int max = Math.max(0, ClientAudioEngine.getInstance().getQueue().size() - Math.max(1, lastQueueVisibleRows));
                queueScrollOffset = Math.max(0, Math.min(max, queueScrollOffset + step));
            } else {
                int max = Math.max(0, ClientAudioEngine.getInstance().getHistory().size() - Math.max(1, lastHistoryVisibleRows));
                historyScrollOffset = Math.max(0, Math.min(max, historyScrollOffset + step));
            }
            return true;
        }
        if (currentTab == Tab.PLAYLISTS) {
            if (selectedPlaylistId == null) {
                int max = Math.max(0, SavedPlaylistsManager.getPlaylists().size() - Math.max(1, lastPlaylistsVisibleRows));
                playlistsScrollOffset = Math.max(0, Math.min(max, playlistsScrollOffset + step));
            } else {
                SavedPlaylist pl = SavedPlaylistsManager.getPlaylistById(selectedPlaylistId);
                int max = pl != null ? Math.max(0, pl.tracks.size() - Math.max(1, lastPlaylistDetailVisibleRows)) : 0;
                playlistDetailScrollOffset = Math.max(0, Math.min(max, playlistDetailScrollOffset + step));
            }
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private boolean isTypingInAnyInput() {
        return (this.searchInput != null && this.searchInput.isFocused())
                || (this.playlistNameInput != null && this.playlistNameInput.isFocused())
                || (this.renameInput != null && this.renameInput.isFocused());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.showingChangelog) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                dismissChangelogModal();
                return true;
            }
            return true;
        }

        if (this.showingPlaylistPicker) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                this.showingPlaylistPicker = false;
                return true;
            }
            return true;
        }

        boolean isEnter = event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER;
        if (isEnter && currentTab == Tab.SEARCH && this.searchInput != null && this.searchInput.isFocused()) {
            executeSearchOrAdd();
            return true;
        }
        if (isEnter && currentTab == Tab.PLAYLISTS && selectedPlaylistId == null
                && this.playlistNameInput != null && this.playlistNameInput.isFocused()) {
            createNewPlaylistFromInput();
            return true;
        }
        if (isEnter && currentTab == Tab.PLAYLISTS && selectedPlaylistId != null
                && this.renamingPlaylist && this.renameInput != null && this.renameInput.isFocused()) {
            confirmPlaylistRename(SavedPlaylistsManager.getPlaylistById(selectedPlaylistId));
            return true;
        }

        if (!isTypingInAnyInput() && PlayCraftClient.openGuiKey != null && PlayCraftClient.openGuiKey.matches(event)) {
            this.onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (this.renamingPlaylist) {
                this.renamingPlaylist = false;
                this.rebuildWidgets();
                return true;
            }
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
