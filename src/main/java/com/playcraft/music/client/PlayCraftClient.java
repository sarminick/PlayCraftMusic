package com.playcraft.music.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.playcraft.music.PlayCraftMod;
import com.playcraft.music.client.audio.ClientAudioEngine;
import com.playcraft.music.client.gui.MusicHudOverlay;
import com.playcraft.music.client.gui.MusicQuickControlsScreen;
import com.playcraft.music.client.gui.YouTubeSearchScreen;
import com.playcraft.music.network.SyncPlaybackPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlayCraftClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("playcraft-client");

    public static KeyMapping openGuiKey;
    public static KeyMapping openQuickControlsKey;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[PlayCraftMusic] Inicializando cliente...");

        // Cargar configuración guardada del jugador (posición, escala, tema, volumen y avisos)
        PlayCraftConfig.load();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(PlayCraftMod.MOD_ID, "music")
        );

        // Tecla '.' (Punto): Abre y cierra la pantalla principal para buscar o añadir canciones
        openGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.playcraft.open_gui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_PERIOD,
                category
        ));

        // Tecla ',' (Coma): Abre y cierra la bandeja rápida interactiva debajo del contenedor de la canción
        openQuickControlsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.playcraft.quick_controls",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_COMMA,
                category
        ));

        // Eventos de escucha de teclas y detención al salir al menú principal
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null && client.player == null) {
                if (ClientAudioEngine.getInstance().isPlaying()) {
                    ClientAudioEngine.getInstance().stopCurrentStream();
                }
            }

            while (openGuiKey.consumeClick()) {
                if (client.player != null) {
                    if (client.gui.screen() instanceof YouTubeSearchScreen) {
                        client.gui.setScreen(null);
                    } else {
                        client.gui.setScreen(new YouTubeSearchScreen());
                    }
                }
            }

            while (openQuickControlsKey.consumeClick()) {
                if (client.player != null) {
                    if (client.gui.screen() instanceof MusicQuickControlsScreen) {
                        client.gui.setScreen(null);
                    } else {
                        client.gui.setScreen(new MusicQuickControlsScreen());
                    }
                }
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientAudioEngine.getInstance().stopCurrentStream();
        });

        // Registro del renderizado del HUD flotante en pantalla
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(PlayCraftMod.MOD_ID, "music_hud"),
                new MusicHudOverlay()
        );

        // Receptor S2C: Sincronización de música enviada por el servidor
        ClientPlayNetworking.registerGlobalReceiver(SyncPlaybackPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                ClientAudioEngine.getInstance().syncTrack(
                        payload.track(),
                        payload.positionMs(),
                        payload.isPlaying(),
                        payload.queue()
                );
                MusicHudOverlay.updatePlaybackState(payload.positionMs(), payload.isPlaying());
                if (payload.notificationMessage() != null && !payload.notificationMessage().isEmpty()) {
                    MusicHudOverlay.showNotification(payload.notificationMessage());
                }
                if (context.client().gui.screen() instanceof YouTubeSearchScreen screen) {
                    screen.onSyncUpdate();
                } else if (context.client().gui.screen() instanceof MusicQuickControlsScreen screen) {
                    screen.onSyncUpdate();
                }
            });
        });

        LOGGER.info("[PlayCraftMusic] Cliente listo. Teclas: '.' (Buscar/Añadir) y ',' (Bandeja de Controles)");
    }
}
