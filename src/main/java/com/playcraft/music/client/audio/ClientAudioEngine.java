package com.playcraft.music.client.audio;

import com.playcraft.music.model.TrackInfo;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Motor de audio de alta fidelidad que extrae el audio directamente usando yt-dlp + ffmpeg
 * decodificando a PCM estéreo 48000Hz 16-bit hacia OpenAL en Minecraft.
 * Incluye Pre-fetching de la siguiente pista en cola y auto-recuperación ante fallos de stream.
 */
public class ClientAudioEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("PlayCraft-Audio");
    private static final ClientAudioEngine INSTANCE = new ClientAudioEngine();

    private final ExecutorService streamExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PlayCraft-Native-Streamer");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PlayCraft-Prefetcher");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, String> resolvedStreamCache = new ConcurrentHashMap<>();
    private final Set<String> pendingPrefetches = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private final AtomicInteger streamSessionId = new AtomicInteger(0);
    private Process currentFfmpegProcess = null;
    private Process currentYtdlpProcess = null;

    private int sourceId = 0;
    private final int[] buffers = new int[8]; // 8 buffers rotativos (~800ms)
    private TrackInfo currentTrack = null;
    private boolean isPlaying = false;
    private float volume = 0.8f;
    private String lastPlayedTrackId = "";
    private boolean openALInitialized = false;
    private volatile boolean isActualSoundPlaying = false;
    private volatile long lastAudioFlowTimestamp = 0;

    private ClientAudioEngine() {
    }

    public static ClientAudioEngine getInstance() {
        return INSTANCE;
    }

    public synchronized void resetOpenAL() {
        openALInitialized = false;
        try {
            if (sourceId != 0) {
                if (AL10.alIsSource(sourceId)) {
                    AL10.alSourceStop(sourceId);
                    AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
                    AL10.alDeleteSources(sourceId);
                }
                sourceId = 0;
            }
            for (int i = 0; i < buffers.length; i++) {
                if (buffers[i] != 0) {
                    if (AL10.alIsBuffer(buffers[i])) {
                        AL10.alDeleteBuffers(buffers[i]);
                    }
                    buffers[i] = 0;
                }
            }
            AL10.alGetError(); // Limpiar errores residuales
        } catch (Throwable t) {
            LOGGER.warn("[PlayCraft] Aviso al liberar canales OpenAL: {}", t.getMessage());
        }
    }

    private synchronized void ensureOpenAL() {
        if (openALInitialized && sourceId != 0 && AL10.alIsSource(sourceId)) {
            return;
        }
        resetOpenAL();
        try {
            sourceId = AL10.alGenSources();
            for (int i = 0; i < buffers.length; i++) {
                buffers[i] = AL10.alGenBuffers();
            }
            if (AL10.alGetError() == AL10.AL_NO_ERROR && AL10.alIsSource(sourceId)) {
                AL10.alSourcef(sourceId, AL10.AL_GAIN, isMuted ? 0.0f : volume);
                AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
                openALInitialized = true;
                LOGGER.info("[PlayCraft] Canales OpenAL inicializados con éxito.");
            } else {
                LOGGER.warn("[PlayCraft] Error asignando canales OpenAL.");
                openALInitialized = false;
            }
        } catch (Throwable e) {
            LOGGER.error("[PlayCraft] Error en OpenAL: ", e);
            openALInitialized = false;
        }
    }

    private java.util.List<TrackInfo> currentQueue = new java.util.ArrayList<>();

    public synchronized void syncTrack(TrackInfo track, long positionMs, boolean playing, java.util.List<TrackInfo> queue) {
        this.currentTrack = track;
        this.isPlaying = playing;
        this.currentQueue = queue != null ? queue : new java.util.ArrayList<>();

        // Pre-resolver en segundo plano la próxima canción de la cola para que el cambio sea instantáneo
        if (!this.currentQueue.isEmpty()) {
            TrackInfo nextTrack = this.currentQueue.get(0);
            if (nextTrack != null && nextTrack.streamUrl() != null && !nextTrack.streamUrl().trim().isEmpty()) {
                prefetchStreamUrl(nextTrack.streamUrl());
            }
        }

        if (track == null) {
            this.lastPlayedTrackId = "";
            stopCurrentStream();
            return;
        }

        if (!playing) {
            pauseOrStop();
            return;
        }

        String query = track.streamUrl();
        if (query == null || query.trim().isEmpty()) return;

        // Si es una pista nueva, si el stream anterior murió prematuramente, o si OpenAL perdió el source
        if (!track.id().equals(lastPlayedTrackId) || !isStreaming.get() || sourceId == 0 || !AL10.alIsSource(sourceId)) {
            this.lastPlayedTrackId = track.id();
            startPlayback(query, positionMs);
        } else {
            if (sourceId != 0 && AL10.alIsSource(sourceId)) {
                int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
                int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
                if (state != AL10.AL_PLAYING && queued > 0) {
                    AL10.alSourcef(sourceId, AL10.AL_GAIN, isMuted ? 0.0f : volume);
                    AL10.alSourcePlay(sourceId);
                }

                // Watchdog: Si el audio ya había empezado pero lleva más de 5 segundos sin recibir ni procesar datos
                if (isActualSoundPlaying && lastAudioFlowTimestamp > 0 && (System.currentTimeMillis() - lastAudioFlowTimestamp > 5000)) {
                    LOGGER.warn("[PlayCraft] Watchdog detectó estancamiento de audio. Reiniciando stream...");
                    resolvedStreamCache.remove(query);
                    startPlayback(query, positionMs);
                }
            } else {
                this.lastPlayedTrackId = track.id();
                startPlayback(query, positionMs);
            }
        }
    }

    public void prefetchStreamUrl(String queryOrUrl) {
        if (queryOrUrl == null || queryOrUrl.trim().isEmpty()) return;
        if (resolvedStreamCache.containsKey(queryOrUrl) || !pendingPrefetches.add(queryOrUrl)) {
            return;
        }
        prefetchExecutor.submit(() -> {
            try {
                String url = resolveDirectStreamUrl(queryOrUrl, -1);
                if (url != null && !url.isEmpty()) {
                    resolvedStreamCache.put(queryOrUrl, url);
                    LOGGER.info("[PlayCraft] Pre-cargada URL de audio con éxito: {}", queryOrUrl);
                }
            } catch (Exception ignored) {
            } finally {
                pendingPrefetches.remove(queryOrUrl);
            }
        });
    }

    private String resolveDirectStreamUrl(String queryOrUrl, int sessionId) {
        String target = queryOrUrl;
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            target = "ytsearch1:" + queryOrUrl;
        }

        String directStreamUrl = null;
        int attempts = 0;
        while (attempts < 2 && (directStreamUrl == null || directStreamUrl.trim().isEmpty())) {
            if (sessionId != -1 && sessionId != streamSessionId.get()) {
                return null;
            }
            attempts++;
            Process ytdlpProc = null;
            try {
                String format = attempts == 1 ? "bestaudio/best" : "ba/b";
                ProcessBuilder ytdlpPb = new ProcessBuilder(
                        com.playcraft.music.util.BinaryLocator.getYtdlp(),
                        "-g",
                        "-f", format,
                        "--no-playlist",
                        "--no-warnings",
                        "--no-check-certificates",
                        target
                );
                ytdlpPb.redirectError(ProcessBuilder.Redirect.DISCARD);
                ytdlpProc = ytdlpPb.start();
                if (sessionId != -1) {
                    synchronized (this) {
                        if (sessionId != streamSessionId.get()) {
                            ytdlpProc.destroyForcibly();
                            return null;
                        }
                        currentYtdlpProcess = ytdlpProc;
                    }
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(ytdlpProc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("http://") || line.startsWith("https://")) {
                            directStreamUrl = line.trim();
                            break;
                        }
                    }
                }
                boolean finished = ytdlpProc.waitFor(12, TimeUnit.SECONDS);
                if (!finished) {
                    ytdlpProc.destroyForcibly();
                }
            } catch (Exception e) {
                LOGGER.warn("[PlayCraft] Intento {} de resolver audio falló: {}", attempts, e.getMessage());
            } finally {
                if (ytdlpProc != null && ytdlpProc.isAlive()) {
                    ytdlpProc.destroyForcibly();
                }
                if (sessionId != -1) {
                    synchronized (this) {
                        if (currentYtdlpProcess == ytdlpProc) {
                            currentYtdlpProcess = null;
                        }
                    }
                }
            }
        }
        return directStreamUrl;
    }

    private synchronized void startPlayback(String queryOrUrl, long startMs) {
        stopCurrentStream();
        int sessionId = streamSessionId.incrementAndGet();
        ensureOpenAL();

        isStreaming.set(true);
        streamExecutor.submit(() -> {
            // Permitir hasta 2 intentos completos de reproducción (en caso de que la URL pre-cargada haya expirado o fallado)
            for (int streamTry = 1; streamTry <= 2; streamTry++) {
                if (sessionId != streamSessionId.get()) return;

                Process ffmpegProc = null;
                try {
                    String directStreamUrl = resolvedStreamCache.get(queryOrUrl);
                    if (directStreamUrl == null || directStreamUrl.isEmpty()) {
                        LOGGER.info("[PlayCraft] Obteniendo stream de YouTube (intento {}) para: {}", streamTry, queryOrUrl);
                        directStreamUrl = resolveDirectStreamUrl(queryOrUrl, sessionId);
                        if (directStreamUrl != null && !directStreamUrl.isEmpty()) {
                            resolvedStreamCache.put(queryOrUrl, directStreamUrl);
                        }
                    } else {
                        LOGGER.info("[PlayCraft] Usando stream pre-cargado instantáneo para: {}", queryOrUrl);
                    }

                    if (directStreamUrl == null || directStreamUrl.trim().isEmpty()) {
                        LOGGER.error("[PlayCraft] No se pudo obtener la URL de streaming para: {}", queryOrUrl);
                        continue;
                    }

                    if (sessionId != streamSessionId.get()) {
                        return;
                    }

                    LOGGER.info("[PlayCraft] Conectando reproductor PCM a través de OpenAL...");

                    int sampleRate = 48000;
                    int channels = 2;
                    int alFormat = AL10.AL_FORMAT_STEREO16;
                    int bufferSize = 19200; // ~100ms de audio por buffer

                    String startOffsetSec = String.format(java.util.Locale.US, "%.2f", startMs / 1000.0);

                    ProcessBuilder ffmpegPb = new ProcessBuilder(
                            com.playcraft.music.util.BinaryLocator.getFfmpeg(),
                            "-ss", startOffsetSec,
                            "-reconnect", "1",
                            "-reconnect_streamed", "1",
                            "-reconnect_delay_max", "5",
                            "-i", directStreamUrl,
                            "-f", "s16le",
                            "-ac", String.valueOf(channels),
                            "-ar", String.valueOf(sampleRate),
                            "-v", "quiet",
                            "pipe:1"
                    );
                    ffmpegPb.redirectError(ProcessBuilder.Redirect.DISCARD);

                    ffmpegProc = ffmpegPb.start();
                    synchronized (this) {
                        if (sessionId != streamSessionId.get()) {
                            ffmpegProc.destroyForcibly();
                            return;
                        }
                        currentFfmpegProcess = ffmpegProc;
                    }

                    InputStream audioStream = ffmpegProc.getInputStream();
                    byte[] rawChunk = new byte[bufferSize];
                    ByteBuffer directBuffer = ByteBuffer.allocateDirect(bufferSize);

                    // Limpiar buffers residuales anteriores y desasociar completamente
                    synchronized (this) {
                        if (sourceId != 0 && AL10.alIsSource(sourceId)) {
                            AL10.alSourceStop(sourceId);
                            AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
                            int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
                            while (queued > 0) {
                                AL10.alSourceUnqueueBuffers(sourceId);
                                queued--;
                            }
                        }
                    }

                    // Precargar todos los buffers rotativos disponibles
                    int initialFilled = 0;
                    for (int buffer : buffers) {
                        if (sessionId != streamSessionId.get()) break;
                        int read = readFully(audioStream, rawChunk);
                        if (read > 0) {
                            directBuffer.clear();
                            directBuffer.put(rawChunk, 0, read);
                            directBuffer.flip();
                            AL10.alBufferData(buffer, alFormat, directBuffer, sampleRate);
                            AL10.alSourceQueueBuffers(sourceId, buffer);
                            initialFilled++;
                        }
                    }

                    if (sessionId != streamSessionId.get()) {
                        return;
                    }

                    // Si ffmpeg no devolvió ni un solo byte de audio (ej. URL expirada o error 403 de YouTube),
                    // limpiar caché y pasar al intento 2 inmediatamente
                    if (initialFilled == 0) {
                        LOGGER.warn("[PlayCraft] ffmpeg devolvió 0 bytes en la carga inicial (intento {}). Re-resolviendo URL...", streamTry);
                        resolvedStreamCache.remove(queryOrUrl);
                        ffmpegProc.destroyForcibly();
                        continue;
                    }

                    if (isPlaying) {
                        AL10.alSourcef(sourceId, AL10.AL_GAIN, isMuted ? 0.0f : volume);
                        AL10.alSourcePlay(sourceId);
                        isActualSoundPlaying = true;
                        lastAudioFlowTimestamp = System.currentTimeMillis();
                        com.playcraft.music.client.gui.MusicHudOverlay.onLocalStreamStarted();
                        try {
                            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                            if (mc != null && mc.getConnection() != null) {
                                mc.execute(() -> {
                                    if (mc.getConnection() != null) {
                                        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                                                new com.playcraft.music.network.ControlTrackPayload(com.playcraft.music.network.ControlTrackPayload.Action.STREAM_READY)
                                        );
                                    }
                                });
                            }
                        } catch (Exception ignored) {}
                    }

                    // Bucle de reproducción en vivo
                    while (sessionId == streamSessionId.get() && isStreaming.get()) {
                        if (!isPlaying) {
                            int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
                            if (state == AL10.AL_PLAYING) {
                                AL10.alSourcePause(sourceId);
                            }
                            lastAudioFlowTimestamp = System.currentTimeMillis();
                            Thread.sleep(50);
                            continue;
                        }

                        // Comprobar si el dispositivo de audio del sistema cambió o se desconectó invalidando el source
                        if (sourceId == 0 || !AL10.alIsSource(sourceId)) {
                            LOGGER.warn("[PlayCraft] Canal de audio OpenAL invalidado. Reinicializando audio...");
                            resetOpenAL();
                            ensureOpenAL();
                            if (sourceId != 0 && AL10.alIsSource(sourceId)) {
                                for (int buffer : buffers) {
                                    int read = readFully(audioStream, rawChunk);
                                    if (read > 0) {
                                        directBuffer.clear();
                                        directBuffer.put(rawChunk, 0, read);
                                        directBuffer.flip();
                                        AL10.alBufferData(buffer, alFormat, directBuffer, sampleRate);
                                        AL10.alSourceQueueBuffers(sourceId, buffer);
                                    }
                                }
                                AL10.alSourcef(sourceId, AL10.AL_GAIN, isMuted ? 0.0f : volume);
                                AL10.alSourcePlay(sourceId);
                            }
                            Thread.sleep(100);
                            continue;
                        }

                        int processed = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);
                        boolean eofReached = false;
                        while (processed > 0 && sessionId == streamSessionId.get() && isPlaying) {
                            int unqueued = AL10.alSourceUnqueueBuffers(sourceId);
                            int read = readFully(audioStream, rawChunk);
                            if (read > 0) {
                                directBuffer.clear();
                                directBuffer.put(rawChunk, 0, read);
                                directBuffer.flip();
                                AL10.alBufferData(unqueued, alFormat, directBuffer, sampleRate);
                                AL10.alSourceQueueBuffers(sourceId, unqueued);
                                lastAudioFlowTimestamp = System.currentTimeMillis();
                            } else {
                                eofReached = true;
                            }
                            processed--;
                        }

                        int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
                        int currentQueued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);

                        if (state == AL10.AL_PLAYING) {
                            lastAudioFlowTimestamp = System.currentTimeMillis();
                        } else if (isPlaying && currentQueued > 0) {
                            AL10.alSourcef(sourceId, AL10.AL_GAIN, isMuted ? 0.0f : volume);
                            AL10.alSourcePlay(sourceId);
                            lastAudioFlowTimestamp = System.currentTimeMillis();
                        }

                        // Si ffmpeg ya terminó y además ya se reprodujeron todos los buffers restantes en cola, salir limpiamente
                        if ((eofReached || !ffmpegProc.isAlive()) && currentQueued == 0 && state != AL10.AL_PLAYING) {
                            break;
                        }

                        Thread.sleep(20);
                    }

                    // Si llegamos aquí, la reproducción fue exitosa y terminó normalmente
                    break;

                } catch (Exception e) {
                    if (sessionId == streamSessionId.get() && isStreaming.get()) {
                        LOGGER.error("[PlayCraft] Error durante el streaming (intento {}): ", streamTry, e);
                        resolvedStreamCache.remove(queryOrUrl);
                        synchronized (this) {
                            resetOpenAL();
                            ensureOpenAL();
                        }
                    }
                } finally {
                    if (ffmpegProc != null) {
                        ffmpegProc.destroyForcibly();
                    }
                }
            }

            if (sessionId == streamSessionId.get()) {
                isStreaming.set(false);
            }
        });
    }

    private int readFully(InputStream in, byte[] buffer) {
        int totalRead = 0;
        try {
            while (totalRead < buffer.length) {
                int read = in.read(buffer, totalRead, buffer.length - totalRead);
                if (read == -1) break;
                totalRead += read;
            }
        } catch (Exception ignored) {}
        return totalRead;
    }

    public synchronized void stopCurrentStream() {
        streamSessionId.incrementAndGet();
        isStreaming.set(false);
        isActualSoundPlaying = false;
        lastAudioFlowTimestamp = 0;
        if (currentYtdlpProcess != null) {
            currentYtdlpProcess.destroyForcibly();
            currentYtdlpProcess = null;
        }
        if (currentFfmpegProcess != null) {
            currentFfmpegProcess.destroyForcibly();
            currentFfmpegProcess = null;
        }
        if (sourceId != 0 && AL10.alIsSource(sourceId)) {
            AL10.alSourceStop(sourceId);
            AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
            int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
            while (queued > 0) {
                AL10.alSourceUnqueueBuffers(sourceId);
                queued--;
            }
        }
    }

    public synchronized void pauseOrStop() {
        if (sourceId != 0 && AL10.alIsSource(sourceId)) {
            AL10.alSourcePause(sourceId);
        }
    }

    private float volumeBeforeMute = 0.8f;
    private boolean isMuted = false;

    public synchronized void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        if (this.volume > 0.0f) {
            this.isMuted = false;
        }
        if (sourceId != 0 && AL10.alIsSource(sourceId)) {
            AL10.alSourcef(sourceId, AL10.AL_GAIN, this.isMuted ? 0.0f : this.volume);
        }
    }

    public synchronized void toggleMute() {
        if (isMuted) {
            isMuted = false;
            setVolume(volumeBeforeMute > 0.0f ? volumeBeforeMute : 0.8f);
        } else {
            volumeBeforeMute = this.volume > 0.0f ? this.volume : 0.8f;
            isMuted = true;
            this.volume = 0.0f;
            if (sourceId != 0 && AL10.alIsSource(sourceId)) {
                AL10.alSourcef(sourceId, AL10.AL_GAIN, 0.0f);
            }
        }
    }

    public boolean isMuted() {
        return isMuted;
    }

    public float getVolume() {
        return volume;
    }

    public TrackInfo getCurrentTrack() {
        return currentTrack;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isActualSoundPlaying() {
        return isActualSoundPlaying;
    }

    public java.util.List<TrackInfo> getQueue() {
        return currentQueue;
    }
}
