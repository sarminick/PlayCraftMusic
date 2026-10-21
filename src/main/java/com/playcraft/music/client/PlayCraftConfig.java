package com.playcraft.music.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.playcraft.music.client.audio.ClientAudioEngine;
import com.playcraft.music.client.gui.MusicHudOverlay;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Administra y guarda de forma persistente toda la configuración del jugador en
 * config/playcraftmusic.json para que nunca se pierda al cerrar Minecraft.
 */
public class PlayCraftConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("PlayCraftMusic-Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("playcraftmusic.json");

    public static class Data {
        public int hudX = 12;
        public int hudY = 12;
        public float hudScale = 1.0f;
        public float hudOpacity = 0.88f;
        public boolean hudEnabled = true;
        public String colorTheme = "GOLD";
        public float volume = 0.8f;
        public boolean isMuted = false;

        // Avisos de actividad del reproductor: DESACTIVADOS por defecto
        public boolean notificationsEnabled = false;
        public int notificationX = 12;
        public int notificationY = 76;
        public float notificationScale = 0.9f;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            Data data = GSON.fromJson(reader, Data.class);
            if (data != null) {
                MusicHudOverlay.HUD_X = Math.max(0, data.hudX);
                MusicHudOverlay.HUD_Y = Math.max(0, data.hudY);
                MusicHudOverlay.HUD_SCALE = Math.max(0.70f, Math.min(1.50f, data.hudScale));
                MusicHudOverlay.HUD_OPACITY = Math.max(0.20f, Math.min(1.00f, data.hudOpacity));
                MusicHudOverlay.HUD_ENABLED = data.hudEnabled;
                try {
                    if (data.colorTheme != null) {
                        MusicHudOverlay.CURRENT_THEME = MusicHudOverlay.ColorTheme.valueOf(data.colorTheme);
                    }
                } catch (IllegalArgumentException ignored) {
                    MusicHudOverlay.CURRENT_THEME = MusicHudOverlay.ColorTheme.GOLD;
                }

                MusicHudOverlay.NOTIFICATIONS_ENABLED = data.notificationsEnabled;
                MusicHudOverlay.NOTIFICATION_X = Math.max(0, data.notificationX);
                MusicHudOverlay.NOTIFICATION_Y = Math.max(0, data.notificationY);
                MusicHudOverlay.NOTIFICATION_SCALE = Math.max(0.60f, Math.min(1.50f, data.notificationScale));

                ClientAudioEngine.getInstance().setVolume(data.volume);
                if (data.isMuted && !ClientAudioEngine.getInstance().isMuted()) {
                    ClientAudioEngine.getInstance().toggleMute();
                }
                LOGGER.info("[PlayCraftMusic] Configuración cargada desde {}", CONFIG_PATH);
            }
        } catch (Exception e) {
            LOGGER.warn("[PlayCraftMusic] Error leyendo configuración, usando valores por defecto: {}", e.getMessage());
        }
    }

    public static void save() {
        try {
            Data data = new Data();
            data.hudX = MusicHudOverlay.HUD_X;
            data.hudY = MusicHudOverlay.HUD_Y;
            data.hudScale = MusicHudOverlay.HUD_SCALE;
            data.hudOpacity = MusicHudOverlay.HUD_OPACITY;
            data.hudEnabled = MusicHudOverlay.HUD_ENABLED;
            data.colorTheme = MusicHudOverlay.CURRENT_THEME.name();
            data.volume = ClientAudioEngine.getInstance().getVolume();
            data.isMuted = ClientAudioEngine.getInstance().isMuted();

            data.notificationsEnabled = MusicHudOverlay.NOTIFICATIONS_ENABLED;
            data.notificationX = MusicHudOverlay.NOTIFICATION_X;
            data.notificationY = MusicHudOverlay.NOTIFICATION_Y;
            data.notificationScale = MusicHudOverlay.NOTIFICATION_SCALE;

            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(data, writer);
            }
        } catch (Exception e) {
            LOGGER.warn("[PlayCraftMusic] No se pudo guardar la configuración: {}", e.getMessage());
        }
    }
}
