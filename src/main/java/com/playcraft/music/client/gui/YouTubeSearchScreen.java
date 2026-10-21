package com.playcraft.music.client.gui;

import com.playcraft.music.client.PlayCraftConfig;
import com.playcraft.music.client.audio.ClientAudioEngine;
import com.playcraft.music.model.TrackInfo;
import com.playcraft.music.network.AddTrackPayload;
import com.playcraft.music.network.ControlTrackPayload;
import com.playcraft.music.network.ModifyQueuePayload;
import com.playcraft.music.util.YouTubeMetadataResolver;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Pantalla modular y moderna con 4 pestañas interactivas completas:
 * 1. [Now Playing]: Información de la canción actual, carátula HD, barra de progreso y controles principales.
 * 2. [Search]: Modo URL / Modo Búsqueda con selector, buscador rápido y lista de resultados con botón (+) para añadir.
 * 3. [Playlist]: Lista completa de canciones en cola con botones para subir [▲], bajar [▼] y remover [✖].
 * 4. [Settings]: Ajuste en tiempo real de la posición (X, Y), escala, tema y avisos de actividad (con guardado automático).
 */
public class YouTubeSearchScreen extends Screen {

    public enum Tab {
        NOW_PLAYING,
        SEARCH,
        PLAYLIST,
        SETTINGS
    }

    private static Tab currentTab = Tab.NOW_PLAYING;

    private static String linkErrorMessage = "";
    private static long linkErrorTimestamp = 0;
    private static String linkSuccessMessage = "";
    private static long linkSuccessTimestamp = 0;

    // Search tab (estáticos para que si el jugador cierra y vuelve a abrir el menú con '.', no pierda su búsqueda ni los resultados)
    private EditBox searchInput;
    private static String lastSearchQuery = "";
    private static boolean isSearchMode = true; // true = búsqueda en YouTube, false = Enlace directo
    private static List<TrackInfo> searchResults = new ArrayList<>();
    private static boolean isSearching = false;
    private static int searchResultLimit = 5;
    private int searchScrollOffset = 0;
    private boolean isDraggingSearchScrollbar = false;
    private static final java.util.Set<String> addedSearchUrls = new java.util.HashSet<>();

    // Playlist tab
    private int playlistScrollOffset = 0;
    private boolean isDraggingScrollbar = false;

    // Settings tab
    private AbstractSliderButton posXSlider;
    private AbstractSliderButton posYSlider;
    private AbstractSliderButton sizeSlider;
    private AbstractSliderButton opacitySlider;

    public YouTubeSearchScreen() {
        super(Component.literal("PlayCraftMusic - Music Center"));
    }

    public boolean isSettingsTabActive() {
        return this.currentTab == Tab.SETTINGS;
    }

    @Override
    protected void init() {
        int w = this.width;
        int panelW = Math.min(540, w - 40);
        int panelX = (w - panelW) / 2;
        int panelY = 22;

        // 1. Pestañas Superiores (Header Tabs) con distribución exacta al píxel
        for (int i = 0; i < 4; i++) {
            int tX = panelX + (i * panelW / 4);
            int nextTX = panelX + ((i + 1) * panelW / 4);
            int tW = nextTX - tX;
            final Tab tabTarget = Tab.values()[i];
            String label = switch (tabTarget) {
                case NOW_PLAYING -> currentTab == Tab.NOW_PLAYING ? "§6§lNow Playing" : "Now Playing";
                case SEARCH -> currentTab == Tab.SEARCH ? "§6§lSearch" : "Search";
                case PLAYLIST -> currentTab == Tab.PLAYLIST ? "§6§lPlaylist" : "Playlist";
                case SETTINGS -> currentTab == Tab.SETTINGS ? "§6§lSettings" : "Settings";
            };
            this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
                currentTab = tabTarget;
                this.rebuildWidgets();
            }).bounds(tX, panelY, tW, 20).build());
        }

        // 2. Inicializar contenido según la pestaña activa
        switch (currentTab) {
            case NOW_PLAYING -> initNowPlayingTab(panelX, panelY, panelW);
            case SEARCH -> initSearchTab(panelX, panelY, panelW);
            case PLAYLIST -> initPlaylistTab(panelX, panelY, panelW);
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
        // 1. Slider de Posición / Búsqueda interactiva en la canción (ModernMediaSlider)
        TrackInfo track = ClientAudioEngine.getInstance().getCurrentTrack();
        boolean hasTrack = track != null;
        long trackDuration = hasTrack && track.durationMs() > 0 ? track.durationMs() : 0L;
        long livePos = hasTrack ? MusicHudOverlay.getLivePositionMs() : 0L;
        double initialPosRatio = (hasTrack && trackDuration > 0) ? Math.max(0.0, Math.min(1.0, (double) livePos / (double) trackDuration)) : 0.0;

        ModernMediaSlider posSlider = new ModernMediaSlider(
                panelX + 10,
                panelY + 116,
                panelW - 20,
                22,
                initialPosRatio,
                ModernMediaSlider.Theme.fromHudTheme(MusicHudOverlay.CURRENT_THEME),
                val -> {
                    TrackInfo t = ClientAudioEngine.getInstance().getCurrentTrack();
                    if (t == null) {
                        return Component.literal("⏱ 00:00 / 00:00");
                    }
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

        int volY = panelY + 146;

        // 2. Slider de Volumen interactivo moderno y botón de silenciar [🔇/🔊]
        float currentVol = ClientAudioEngine.getInstance().getVolume();
        boolean isMuted = ClientAudioEngine.getInstance().isMuted();
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

        // Controles de reproducción inferiores: [⏮ Atrasar] [⏯ Pausa/Play] [⏭ Saltar] [⏹ Detener]
        int ctrlY = volY + 32;
        int btnW = 90;
        int startX = panelX + (panelW - (btnW * 4 + 18)) / 2;

        this.addRenderableWidget(Button.builder(Component.literal("⏮ Prev"), b -> {
            ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.REWIND));
        }).bounds(startX, ctrlY, btnW, 20).build());

        boolean isPlaying = ClientAudioEngine.getInstance().isPlaying();
        this.addRenderableWidget(Button.builder(Component.literal(isPlaying ? "❚❚ Pause" : "▶ Resume"), b -> {
            ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.TOGGLE_PAUSE));
            this.rebuildWidgets();
        }).bounds(startX + btnW + 6, ctrlY, btnW, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("⏭ Skip"), b -> {
            ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.SKIP));
        }).bounds(startX + (btnW * 2) + 12, ctrlY, btnW, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("⏹ Stop"), b -> {
            ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.STOP));
            this.onClose();
        }).bounds(startX + (btnW * 3) + 18, ctrlY, btnW, 20).build());
    }

    /* ------------------------------------------------------------- */
    /* PESTAÑA 2: SEARCH (Buscador y Resultados en Lista)           */
    /* ------------------------------------------------------------- */
    private void initSearchTab(int panelX, int panelY, int panelW) {
        int inputY = panelY + 34;
        int btnModeW = 100;
        int btnActionW = 70;
        int inputW = panelW - btnModeW - btnActionW - 28;

        // Botón conmutador: [Mode: Search] / [Mode: Direct Link]
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

        // Campo de texto de búsqueda
        String prevQuery = lastSearchQuery;
        String placeholder = isSearchMode ? "Search song or artist..." : "Paste YouTube link here...";
        this.searchInput = new EditBox(
                this.font,
                panelX + 10 + btnModeW + 4,
                inputY,
                inputW,
                20,
                Component.literal(placeholder)
        );
        this.searchInput.setMaxLength(256);
        this.searchInput.setValue(prevQuery);
        this.searchInput.setResponder(val -> lastSearchQuery = val);
        this.addRenderableWidget(this.searchInput);

        // Botón Buscar / Añadir directo
        this.addRenderableWidget(Button.builder(
                Component.literal(isSearchMode ? "§aSearch" : "§a+ Add"),
                b -> executeSearchOrAdd()
        ).bounds(panelX + 10 + btnModeW + inputW + 8, inputY, btnActionW, 20).build());

        // Si estamos en modo búsqueda y hay resultados, generar tarjetas visibles con scroll y botón Ver más
        if (isSearchMode && !isSearching && !searchResults.isEmpty()) {
            int listY = inputY + 26;
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

                Button addBtn = Button.builder(Component.literal(alreadyAdded ? "§e✔ Added" : "§a+ Add"), b -> {
                    ThumbnailTextureManager.getThumbnail(item.thumbnailUrl()); // Precargar miniatura de inmediato
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
                }).bounds(panelX + panelW - 78, rowY + 6, 65, 20).build();
                addBtn.active = !alreadyAdded;
                this.addRenderableWidget(addBtn);
            }

            // Botón inferior "+ Ver más resultados" para cargar más canciones de la búsqueda actual
            if (searchResultLimit < 20 && !lastSearchQuery.trim().isEmpty()) {
                int moreBtnW = 150;
                int moreBtnH = 18;
                int moreBtnY = panelY + 250 - moreBtnH - 6;
                this.addRenderableWidget(Button.builder(Component.literal("§b➕ Ver más resultados"), b -> {
                    loadMoreSearchResults();
                }).bounds(panelX + (panelW - moreBtnW) / 2, moreBtnY, moreBtnW, moreBtnH).build());
            }
        }
    }

    private void executeSearchOrAdd() {
        if (this.searchInput == null) return;
        String query = searchInput.getValue().trim();
        if (query.isEmpty()) return;

        if (!isSearchMode) {
            // Validar que en modo enlace sea una URL real (http:// o https://)
            if (!query.startsWith("http://") && !query.startsWith("https://")) {
                linkErrorMessage = "¡Debes introducir un enlace válido que empiece por http:// o https://!";
                linkErrorTimestamp = System.currentTimeMillis();
                linkSuccessMessage = "";
                return;
            }
            linkErrorMessage = "";
            linkSuccessMessage = "";

            String playerName = "Player";
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                playerName = mc.player.getName().getString();
            }
            searchInput.setValue("");
            lastSearchQuery = "";
            isSearching = true;
            this.rebuildWidgets();

            if (YouTubeMetadataResolver.isPlaylistUrl(query)) {
                ClientPlayNetworking.send(new AddTrackPayload(query));
                isSearching = false;
                linkSuccessMessage = "✔ ¡Playlist enviada y añadida a la cola de reproducción!";
                linkSuccessTimestamp = System.currentTimeMillis();
                this.rebuildWidgets();
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
                            linkSuccessMessage = "✔ ¡Añadida a la playlist: " + shortTitle + "!";
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
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null) {
                    mc.execute(() -> {
                        searchResults = results;
                        isSearching = false;
                        // Pre-cargar inmediatamente el stream del primer resultado por si es la primera canción que el jugador añade
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
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
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

    private void initPlaylistTab(int panelX, int panelY, int panelW) {
        List<TrackInfo> queue = ClientAudioEngine.getInstance().getQueue();
        int panelH = 250;
        int listY = panelY + 32;
        int visibleCount = 6;

        if (queue != null && !queue.isEmpty()) {
            int maxOffset = Math.max(0, queue.size() - visibleCount);
            if (playlistScrollOffset > maxOffset) {
                playlistScrollOffset = maxOffset;
            }
            if (playlistScrollOffset < 0) {
                playlistScrollOffset = 0;
            }

            int endIdx = Math.min(queue.size(), playlistScrollOffset + visibleCount);
            for (int i = playlistScrollOffset; i < endIdx; i++) {
                final int idx = i;
                int rowIdx = i - playlistScrollOffset;
                int rowY = listY + (rowIdx * 32);

                // Botón Subir [▲]
                if (idx > 0) {
                    this.addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
                        ClientPlayNetworking.send(new ModifyQueuePayload(ModifyQueuePayload.Action.MOVE, idx, idx - 1));
                    }).bounds(panelX + panelW - 85, rowY + 4, 22, 20).build());
                }

                // Botón Bajar [▼]
                if (idx < queue.size() - 1) {
                    this.addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
                        ClientPlayNetworking.send(new ModifyQueuePayload(ModifyQueuePayload.Action.MOVE, idx, idx + 1));
                    }).bounds(panelX + panelW - 60, rowY + 4, 22, 20).build());
                }

                // Botón Remover [✖]
                this.addRenderableWidget(Button.builder(Component.literal("§c✖"), b -> {
                    ClientPlayNetworking.send(new ModifyQueuePayload(ModifyQueuePayload.Action.REMOVE, idx, 0));
                }).bounds(panelX + panelW - 35, rowY + 4, 25, 20).build());
            }

            // Botón para limpiar toda la lista de reproducción (Clear Queue)
            int clearBtnW = 95;
            int clearBtnH = 16;
            int clearBtnY = panelY + panelH - clearBtnH - 8;
            this.addRenderableWidget(Button.builder(Component.literal("§c🧹 Clear"), b -> {
                ClientPlayNetworking.send(new ModifyQueuePayload(ModifyQueuePayload.Action.CLEAR, 0, 0));
                this.rebuildWidgets();
            }).bounds(panelX + 12, clearBtnY, clearBtnW, clearBtnH).build());
        }
    }

    /* ------------------------------------------------------------- */
    /* PESTAÑA 4: SETTINGS (HUD + Avisos de Actividad + Persistencia) */
    /* ------------------------------------------------------------- */
    private void initSettingsTab(int panelX, int panelY, int panelW) {
        int startY = panelY + 38;
        int halfW = (panelW - 46) / 2;
        int leftX = panelX + 20;
        int rightX = leftX + halfW + 6;

        int scaledHudW = (int) (MusicHudOverlay.HUD_WIDTH * MusicHudOverlay.HUD_SCALE);
        int totalMenuH = (int) ((MusicHudOverlay.HUD_HEIGHT + 80) * MusicHudOverlay.HUD_SCALE);
        int maxW = Math.max(0, this.width - scaledHudW);
        int maxH = Math.max(0, this.height - totalMenuH);

        // Fila 1: Posición X e Y del Widget HUD (lado a lado)
        this.posXSlider = new AbstractSliderButton(
                leftX,
                startY,
                halfW,
                18,
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
        };
        this.addRenderableWidget(this.posXSlider);

        this.posYSlider = new AbstractSliderButton(
                rightX,
                startY,
                halfW,
                18,
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
        };
        this.addRenderableWidget(this.posYSlider);

        // Fila 2: Escala del Widget y Opacidad del Fondo (lado a lado)
        this.sizeSlider = new AbstractSliderButton(
                leftX,
                startY + 24,
                halfW,
                18,
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
        };
        this.addRenderableWidget(this.sizeSlider);

        this.opacitySlider = new AbstractSliderButton(
                rightX,
                startY + 24,
                halfW,
                18,
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
        };
        this.addRenderableWidget(this.opacitySlider);

        // Fila 3: Tema de Colores y Activación de Avisos de Actividad (Desactivados por defecto)
        Button themeBtn = Button.builder(
                Component.literal("Theme: " + MusicHudOverlay.CURRENT_THEME.displayName),
                b -> {
                    MusicHudOverlay.ColorTheme[] themes = MusicHudOverlay.ColorTheme.values();
                    int nextIdx = (MusicHudOverlay.CURRENT_THEME.ordinal() + 1) % themes.length;
                    MusicHudOverlay.CURRENT_THEME = themes[nextIdx];
                    ThumbnailTextureManager.clearCache();
                    PlayCraftConfig.save();
                    b.setMessage(Component.literal("Theme: " + MusicHudOverlay.CURRENT_THEME.displayName));
                }
        ).bounds(leftX, startY + 48, halfW, 18).build();
        this.addRenderableWidget(themeBtn);

        Button notifToggleBtn = Button.builder(
                Component.literal(MusicHudOverlay.NOTIFICATIONS_ENABLED ? "Avisos: §aActivados" : "Avisos: §cDesactivados"),
                b -> {
                    MusicHudOverlay.NOTIFICATIONS_ENABLED = !MusicHudOverlay.NOTIFICATIONS_ENABLED;
                    if (MusicHudOverlay.NOTIFICATIONS_ENABLED) {
                        MusicHudOverlay.showNotification("§6[PlayCraftMusic] §aAvisos de actividad activados");
                    }
                    PlayCraftConfig.save();
                    this.rebuildWidgets();
                }
        ).bounds(rightX, startY + 48, halfW, 18).build();
        this.addRenderableWidget(notifToggleBtn);

        // Fila 4 y 5: Posición (X, Y) y Tamaño de los Avisos de Actividad
        int maxNotifX = Math.max(0, this.width - 180);
        int maxNotifY = Math.max(0, this.height - 24);

        AbstractSliderButton notifXSlider = new AbstractSliderButton(
                leftX,
                startY + 86,
                halfW,
                18,
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
                rightX,
                startY + 86,
                halfW,
                18,
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

        AbstractSliderButton notifScaleSlider = new AbstractSliderButton(
                leftX,
                startY + 110,
                panelW - 40,
                18,
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

        // Fila 6: Botón Ocultar/Mostrar HUD y Botón Restablecer valores por defecto (Reset to Defaults)
        this.addRenderableWidget(Button.builder(
                Component.literal(MusicHudOverlay.HUD_ENABLED ? "HUD Flotante: §aVisible" : "HUD Flotante: §cOculto"),
                b -> {
                    MusicHudOverlay.HUD_ENABLED = !MusicHudOverlay.HUD_ENABLED;
                    PlayCraftConfig.save();
                    this.rebuildWidgets();
                }
        ).bounds(leftX, startY + 134, halfW, 18).build());

        this.addRenderableWidget(Button.builder(Component.literal("Reset to Defaults"), b -> {
            MusicHudOverlay.HUD_X = 12;
            MusicHudOverlay.HUD_Y = 12;
            MusicHudOverlay.HUD_WIDTH = 250;
            MusicHudOverlay.HUD_SCALE = 1.0f;
            MusicHudOverlay.HUD_OPACITY = 0.88f;
            MusicHudOverlay.HUD_ENABLED = true;
            MusicHudOverlay.CURRENT_THEME = MusicHudOverlay.ColorTheme.GOLD;
            MusicHudOverlay.NOTIFICATIONS_ENABLED = false;
            MusicHudOverlay.NOTIFICATION_X = 12;
            MusicHudOverlay.NOTIFICATION_Y = 76;
            MusicHudOverlay.NOTIFICATION_SCALE = 0.9f;
            ThumbnailTextureManager.clearCache();
            PlayCraftConfig.save();
            this.rebuildWidgets();
        }).bounds(rightX, startY + 134, halfW, 18).build());
    }

    /* ------------------------------------------------------------- */
    /* RENDERIZADO VISUAL SEGÚN LA PESTAÑA                          */
    /* ------------------------------------------------------------- */
    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Anulamos el desenfoque gaussiano (blur) por defecto
        // para que las tarjetas de búsqueda y la playlist se vean completamente nítidas.
    }

    public void onSyncUpdate() {
        if (this.currentTab == Tab.PLAYLIST || this.currentTab == Tab.NOW_PLAYING) {
            this.rebuildWidgets();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        int w = this.width;
        int panelW = Math.min(540, w - 40);
        int panelX = (w - panelW) / 2;
        int panelY = 22;
        int panelH = 250;

        // Marco del contenedor con degradado y bordes modernos
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xEE121217);
        context.outline(panelX, panelY, panelW, panelH, MusicHudOverlay.CURRENT_THEME.borderColor);

        switch (currentTab) {
            case NOW_PLAYING -> renderNowPlaying(context, panelX, panelY, panelW);
            case SEARCH -> renderSearch(context, panelX, panelY, panelW);
            case PLAYLIST -> renderPlaylist(context, panelX, panelY, panelW, mouseX, mouseY);
            case SETTINGS -> renderSettings(context, panelX, panelY, panelW);
        }

        // Si faltan binarios requeridos (yt-dlp o ffmpeg), mostrar banner de advertencia
        if (!com.playcraft.music.util.BinaryLocator.hasRequiredBinaries()) {
            renderMissingBinariesWarning(context, panelX, panelY, panelW);
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private void renderMissingBinariesWarning(GuiGraphicsExtractor context, int panelX, int panelY, int panelW) {
        int bannerH = 18;
        int bannerY = panelY + 250 - bannerH;
        context.fill(panelX, bannerY, panelX + panelW, bannerY + bannerH, 0xDD7A1111);
        context.outline(panelX, bannerY, panelW, bannerH, 0xFFFF4444);

        boolean ytdlpOk = com.playcraft.music.util.BinaryLocator.isYtdlpAvailable();
        boolean ffmpegOk = com.playcraft.music.util.BinaryLocator.isFfmpegAvailable();
        String missing = (!ytdlpOk && !ffmpegOk) ? "yt-dlp y ffmpeg" : (!ytdlpOk ? "yt-dlp" : "ffmpeg");
        context.centeredText(this.font, "§c⚠ Falta " + missing + " en tu sistema. Instálalos para reproducir música.", panelX + (panelW / 2), bannerY + 5, 0xFFFFFFFF);
    }

    private void renderNowPlaying(GuiGraphicsExtractor context, int panelX, int panelY, int panelW) {
        TrackInfo track = ClientAudioEngine.getInstance().getCurrentTrack();
        boolean isPlaying = ClientAudioEngine.getInstance().isPlaying();

        int cardY = panelY + 34;
        int thumbX = panelX + 15;
        int thumbY = cardY;

        if (track != null) {
            Identifier thumbId = ThumbnailTextureManager.getThumbnail(track.thumbnailUrl());
            int discSize = 72;
            int centerX = thumbX + discSize / 2;
            int centerY = thumbY + discSize / 2;
            int r = discSize / 2;

            context.pose().pushMatrix();
            context.pose().translate(centerX, centerY);

            float angle = MusicHudOverlay.getDiscRotationAngle();
            context.pose().rotate((float) Math.toRadians(angle));

            if (thumbId != null) {
                // Se dibuja directamente el disco de vinilo circular transparente generado
                context.blit(RenderPipelines.GUI_TEXTURED, thumbId, -r, -r, 0.0f, 0.0f, discSize, discSize, discSize, discSize);
            }

            context.pose().popMatrix();

            int metaX = thumbX + discSize + 20;
            String title = track.title().length() > 36 ? track.title().substring(0, 34) + "..." : track.title();
            context.text(this.font, "§f§l" + title, metaX, cardY + 6, 0xFFFFFFFF);
            context.text(this.font, "§7Artista: §e" + track.author(), metaX, cardY + 22, 0xFFCCCCCC);
            context.text(this.font, "§7Origen: §fYouTube", metaX, cardY + 36, 0xFFCCCCCC);

            String status = isPlaying ? "§a▶ Reproduciendo en vivo" : "§6❚❚ En pausa";
            context.text(this.font, status, metaX, cardY + 50, 0xFF88FFAA);
        } else {
            int discSize = 72;
            context.fill(thumbX, thumbY, thumbX + discSize, thumbY + discSize, 0xFF1C1C24);
            context.centeredText(this.font, "♫", thumbX + (discSize / 2), thumbY + 30, 0xFF666666);
            context.text(this.font, "§8Ninguna canción reproduciéndose actualmente", thumbX + discSize + 20, cardY + 28, 0xFF888888);
        }
    }

    private static String formatDuration(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    private void renderSearch(GuiGraphicsExtractor context, int panelX, int panelY, int panelW) {
        if (isSearching) {
            int centerX = panelX + (panelW / 2);
            int baseY = panelY + 132;
            int numBars = 7;
            int barW = 5;
            int gap = 4;
            int totalBarsW = (numBars * barW) + ((numBars - 1) * gap);
            int startX = centerX - (totalBarsW / 2);

            double time = System.currentTimeMillis() / 140.0;
            int mainColor = MusicHudOverlay.CURRENT_THEME.borderColor;
            int accentColor = MusicHudOverlay.CURRENT_THEME.accentColor;

            // Dibujar columnas de ecualizador subiendo y bajando suavemente en tiempo real
            for (int i = 0; i < numBars; i++) {
                double wave = Math.sin(time + i * 0.85) * 0.5 + 0.5;
                double wave2 = Math.cos(time * 0.7 - i * 0.5) * 0.5 + 0.5;
                double combined = (wave * 0.65) + (wave2 * 0.35);
                int barH = 6 + (int) Math.round(combined * 26.0);
                int bx = startX + i * (barW + gap);
                int by = baseY - barH;

                // Sombra base de columna y cuerpo animado con punta brillante
                context.fill(bx, baseY - 32, bx + barW, baseY, 0x332A2A38);
                context.fill(bx, by, bx + barW, baseY, mainColor);
                context.fill(bx, by, bx + barW, Math.min(baseY, by + 3), accentColor);
            }

            context.centeredText(this.font, "§eBuscando en YouTube", centerX, baseY + 10, 0xFFFFFF55);
            return;
        }

        if (isSearchMode) {
            int listY = panelY + 60;
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

                String title = item.title().length() > 38 ? item.title().substring(0, 36) + "..." : item.title();
                context.text(this.font, "§f" + title, panelX + 16, rowY + 5, 0xFFFFFFFF);
                long sec = item.durationMs() / 1000;
                context.text(this.font, "§7" + item.author() + " §8• §e" + String.format("%02d:%02d", sec / 60, sec % 60), panelX + 16, rowY + 17, 0xFFAAAAAA);
            }

            // Barra de scroll interactiva para resultados de búsqueda cuando hay más de 4 resultados
            if (maxOffset > 0) {
                int trackAreaX = panelX + panelW - 8;
                int trackAreaY = listY;
                int trackAreaHeight = visibleCount * 34 - 4;
                int trackWidth = 5;

                context.fill(trackAreaX, trackAreaY, trackAreaX + trackWidth, trackAreaY + trackAreaHeight, 0xFF1C1C24);

                int thumbHeight = Math.max(20, (int) ((double) visibleCount / (double) searchResults.size() * trackAreaHeight));
                int scrollRange = trackAreaHeight - thumbHeight;
                int thumbY = trackAreaY + (int) ((double) searchScrollOffset / (double) maxOffset * scrollRange);

                int thumbColor = isDraggingSearchScrollbar ? 0xFFFFCC33 : 0xFFFFAA00;
                context.fill(trackAreaX, thumbY, trackAreaX + trackWidth, thumbY + thumbHeight, thumbColor);
                context.outline(trackAreaX, thumbY, trackWidth, thumbHeight, 0x88FFFFFF);
            }

            if (searchResults.isEmpty()) {
                context.centeredText(this.font, "§7Introduce un término de búsqueda para ver resultados aquí", panelX + (panelW / 2), panelY + 120, 0xFF777777);
            }
        } else {
            // Modo Direct Link
            int centerX = panelX + (panelW / 2);
            context.centeredText(this.font, "§eModo Enlace Directo", centerX, panelY + 90, 0xFFFFFFDD);

            if (!linkErrorMessage.isEmpty() && (System.currentTimeMillis() - linkErrorTimestamp < 6000)) {
                context.centeredText(this.font, "§c⚠ " + linkErrorMessage, centerX, panelY + 112, 0xFFFF5555);
            } else if (!linkSuccessMessage.isEmpty() && (System.currentTimeMillis() - linkSuccessTimestamp < 6000)) {
                int boxW = Math.min(panelW - 40, this.font.width(linkSuccessMessage) + 24);
                int boxX = centerX - (boxW / 2);
                context.fill(boxX, panelY + 106, boxX + boxW, panelY + 126, 0xCC102A18);
                context.outline(boxX, panelY + 106, boxW, 20, 0xFF00E676);
                context.centeredText(this.font, "§a" + linkSuccessMessage, centerX, panelY + 112, 0xFF55FF55);
            } else {
                context.centeredText(this.font, "§7Pega un enlace de YouTube (video o playlist) arriba y pulsa §a+ Add", centerX, panelY + 112, 0xFFAAAAAA);
            }

            context.centeredText(this.font, "§8Ejemplos: https://www.youtube.com/watch?v=... o https://youtu.be/...", centerX, panelY + 136, 0xFF777777);
        }
    }

    private void renderPlaylist(GuiGraphicsExtractor context, int panelX, int panelY, int panelW, int mouseX, int mouseY) {
        List<TrackInfo> queue = ClientAudioEngine.getInstance().getQueue();
        int listY = panelY + 32;
        int visibleCount = 6;

        if (queue == null || queue.isEmpty()) {
            context.centeredText(this.font, "§7La cola de reproducción está vacía.", panelX + (panelW / 2), panelY + 110, 0xFF888888);
            context.centeredText(this.font, "§8Añade canciones desde la pestaña 'Search'.", panelX + (panelW / 2), panelY + 126, 0xFF666666);
            return;
        }

        int maxOffset = Math.max(0, queue.size() - visibleCount);
        if (playlistScrollOffset > maxOffset) {
            playlistScrollOffset = maxOffset;
        }

        int endIdx = Math.min(queue.size(), playlistScrollOffset + visibleCount);
        for (int i = playlistScrollOffset; i < endIdx; i++) {
            TrackInfo item = queue.get(i);
            int rowIdx = i - playlistScrollOffset;
            int rowY = listY + (rowIdx * 32);

            context.fill(panelX + 10, rowY, panelX + panelW - 10, rowY + 28, 0xFF181820);
            context.outline(panelX + 10, rowY, panelW - 20, 28, 0xFF282836);

            String title = item.title().length() > 32 ? item.title().substring(0, 30) + "..." : item.title();
            context.text(this.font, "§6" + (i + 1) + ". §f" + title, panelX + 16, rowY + 5, 0xFFFFFFFF);
            context.text(this.font, "§7" + item.author(), panelX + 16, rowY + 15, 0xFF888888);
        }

        // Si hay más canciones que las visibles, dibujar barra de scroll moderna e interactiva
        if (maxOffset > 0) {
            int trackAreaX = panelX + panelW - 8;
            int trackAreaY = listY;
            int trackAreaHeight = visibleCount * 32 - 4; // 188px
            int trackWidth = 5;

            // Pista del scrollbar con hover sutil
            boolean hovered = mouseX >= trackAreaX - 2 && mouseX <= trackAreaX + trackWidth + 2 && mouseY >= trackAreaY && mouseY <= trackAreaY + trackAreaHeight;
            context.fill(trackAreaX, trackAreaY, trackAreaX + trackWidth, trackAreaY + trackAreaHeight, hovered ? 0xFF282836 : 0xFF1C1C24);

            // Cursor del scrollbar (Thumb) interactivo
            int thumbHeight = Math.max(22, (int) ((double) visibleCount / (double) queue.size() * trackAreaHeight));
            int scrollRange = trackAreaHeight - thumbHeight;
            int thumbY = trackAreaY + (int) ((double) playlistScrollOffset / (double) maxOffset * scrollRange);

            int thumbColor = isDraggingScrollbar ? 0xFFFFCC33 : (hovered ? 0xFFFFB300 : 0xFFFFAA00);
            context.fill(trackAreaX, thumbY, trackAreaX + trackWidth, thumbY + thumbHeight, thumbColor);
            context.outline(trackAreaX, thumbY, trackWidth, thumbHeight, 0x88FFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (currentTab == Tab.SEARCH && isSearchMode && event.button() == 0) {
            if (searchResults != null && searchResults.size() > 4) {
                int panelW = Math.min(540, this.width - 40);
                int panelX = (this.width - panelW) / 2;
                int panelY = 22;
                int trackAreaX = panelX + panelW - 8;
                int trackAreaY = panelY + 60;
                int trackAreaHeight = 4 * 34 - 4;
                int trackWidth = 5;

                double mouseX = event.x();
                double mouseY = event.y();

                if (mouseX >= trackAreaX - 3 && mouseX <= trackAreaX + trackWidth + 3 && mouseY >= trackAreaY && mouseY <= trackAreaY + trackAreaHeight) {
                    isDraggingSearchScrollbar = true;
                    updateSearchScrollFromMouse(mouseY, searchResults.size(), trackAreaY, trackAreaHeight);
                    return true;
                }
            }
        }
        if (currentTab == Tab.PLAYLIST && event.button() == 0) {
            List<TrackInfo> queue = ClientAudioEngine.getInstance().getQueue();
            if (queue != null && queue.size() > 6) {
                int panelW = Math.min(540, this.width - 40);
                int panelX = (this.width - panelW) / 2;
                int panelY = 22;
                int listY = panelY + 32;
                int trackAreaX = panelX + panelW - 8;
                int trackAreaY = listY;
                int trackAreaHeight = 6 * 32 - 4;
                int trackWidth = 5;

                double mouseX = event.x();
                double mouseY = event.y();

                // Clic directo sobre la pista de la barra de scroll
                if (mouseX >= trackAreaX - 3 && mouseX <= trackAreaX + trackWidth + 3 && mouseY >= trackAreaY && mouseY <= trackAreaY + trackAreaHeight) {
                    isDraggingScrollbar = true;
                    updateScrollFromMouse(mouseY, queue.size(), trackAreaY, trackAreaHeight);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (currentTab == Tab.SEARCH && isSearchMode && isDraggingSearchScrollbar) {
            if (searchResults != null && searchResults.size() > 4) {
                int panelY = 22;
                int trackAreaY = panelY + 60;
                int trackAreaHeight = 4 * 34 - 4;
                updateSearchScrollFromMouse(event.y(), searchResults.size(), trackAreaY, trackAreaHeight);
                return true;
            }
        }
        if (currentTab == Tab.PLAYLIST && isDraggingScrollbar) {
            List<TrackInfo> queue = ClientAudioEngine.getInstance().getQueue();
            if (queue != null && queue.size() > 6) {
                int panelY = 22;
                int listY = panelY + 32;
                int trackAreaY = listY;
                int trackAreaHeight = 6 * 32 - 4;
                updateScrollFromMouse(event.y(), queue.size(), trackAreaY, trackAreaHeight);
                return true;
            }
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) {
            isDraggingScrollbar = false;
            isDraggingSearchScrollbar = false;
        }
        return super.mouseReleased(event);
    }

    private void updateSearchScrollFromMouse(double mouseY, int totalItems, int trackAreaY, int trackAreaHeight) {
        int maxOffset = totalItems - 4;
        int thumbHeight = Math.max(20, (int) (4.0 / (double) totalItems * trackAreaHeight));
        int scrollRange = trackAreaHeight - thumbHeight;
        if (scrollRange > 0) {
            double relativeY = mouseY - trackAreaY - (thumbHeight / 2.0);
            double ratio = Math.max(0.0, Math.min(1.0, relativeY / (double) scrollRange));
            int newOffset = (int) Math.round(ratio * maxOffset);
            if (newOffset != searchScrollOffset) {
                searchScrollOffset = newOffset;
                this.rebuildWidgets();
            }
        }
    }

    private void updateScrollFromMouse(double mouseY, int totalItems, int trackAreaY, int trackAreaHeight) {
        int maxOffset = totalItems - 6;
        int thumbHeight = Math.max(22, (int) (6.0 / (double) totalItems * trackAreaHeight));
        int scrollRange = trackAreaHeight - thumbHeight;
        if (scrollRange > 0) {
            double relativeY = mouseY - trackAreaY - (thumbHeight / 2.0);
            double ratio = Math.max(0.0, Math.min(1.0, relativeY / (double) scrollRange));
            int newOffset = (int) Math.round(ratio * maxOffset);
            if (newOffset != playlistScrollOffset) {
                playlistScrollOffset = newOffset;
                this.rebuildWidgets();
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (currentTab == Tab.SEARCH && isSearchMode) {
            if (searchResults != null && searchResults.size() > 4) {
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
            }
        }
        if (currentTab == Tab.PLAYLIST) {
            List<TrackInfo> queue = ClientAudioEngine.getInstance().getQueue();
            if (queue != null && queue.size() > 6) {
                int maxOffset = queue.size() - 6;
                if (verticalAmount > 0 && playlistScrollOffset > 0) {
                    playlistScrollOffset--;
                    this.rebuildWidgets();
                    return true;
                } else if (verticalAmount < 0 && playlistScrollOffset < maxOffset) {
                    playlistScrollOffset++;
                    this.rebuildWidgets();
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void renderSettings(GuiGraphicsExtractor context, int panelX, int panelY, int panelW) {
        context.centeredText(this.font, "§eWidget Flotante (HUD)", panelX + (panelW / 2), panelY + 28, 0xFFFFFFDD);
        context.centeredText(this.font, "§eAvisos de Actividad del Reproductor", panelX + (panelW / 2), panelY + 114, 0xFFFFFFDD);

        boolean ytdlpOk = com.playcraft.music.util.BinaryLocator.isYtdlpAvailable();
        boolean ffmpegOk = com.playcraft.music.util.BinaryLocator.isFfmpegAvailable();
        String binStatus = "§7Dependencias: " + (ytdlpOk ? "§ayt-dlp: OK" : "§cyt-dlp: No detectado") + " §8| " + (ffmpegOk ? "§affmpeg: OK" : "§cffmpeg: No detectado");
        context.centeredText(this.font, binStatus, panelX + (panelW / 2), panelY + 206, 0xFFAAAAAA);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        // Ejecutar búsqueda o añadir con la tecla ENTER (GLFW_KEY_ENTER = 257, GLFW_KEY_KP_ENTER = 335)
        if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)
                && this.currentTab == Tab.SEARCH && this.searchInput != null && this.searchInput.isFocused()) {
            executeSearchOrAdd();
            return true;
        }

        // Permitir cerrar la pantalla con la tecla '.' (Punto) o la tecla asignada si el campo de texto no tiene el foco
        boolean isOpenGuiKey = keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_PERIOD
                || (com.playcraft.music.client.PlayCraftClient.openGuiKey != null
                && com.playcraft.music.client.PlayCraftClient.openGuiKey.matches(event));
        if (isOpenGuiKey && (this.searchInput == null || !this.searchInput.isFocused())) {
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
