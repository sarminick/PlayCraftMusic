package com.playcraft.music.client.audio;

import com.playcraft.music.client.gui.MusicHudOverlay;
import com.playcraft.music.model.TrackInfo;
import com.playcraft.music.network.ControlTrackPayload;
import com.playcraft.music.util.BinaryLocator;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Motor de audio v2 de alta fidelidad:
 * - Decodificación PCM estéreo 48kHz 16-bit hacia OpenAL con anillo de 24 buffers (~2.4s).
 * - Sincronización estricta en OpenAL (alLock) sin mantener bloqueos durante I/O.
 * - Pre-fetching de la siguiente pista (en cola o sugerencia Autoplay) con caché TTL de 2h.
 * - Auto-recuperación multinivel: hasta 2 intentos por URL directa con timeout/reintentos HTTP
 *   y 3er intento automático vía pipe (yt-dlp --http-chunk-size 10M -o - | ffmpeg) para
 *   esquivar throttling y errores 403/SABR de YouTube.
 * - Watchdog tanto en arranque inicial como durante reproducción activa.
 */
public class ClientAudioEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("PlayCraft-Audio");
    private static final ClientAudioEngine INSTANCE = new ClientAudioEngine();
    private static final long STREAM_CACHE_TTL_MS = 2L * 60L * 60L * 1000L; // 2 horas

    public enum PlaybackStatus {
        IDLE,
        LOADING,
        PLAYING,
        PAUSED,
        RECONNECTING,
        ERROR
    }

    private static final class CachedStream {
        final String url;
        final long resolvedAtMs;

        CachedStream(String url) {
            this.url = url;
            this.resolvedAtMs = System.currentTimeMillis();
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - resolvedAtMs) > STREAM_CACHE_TTL_MS;
        }
    }

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

    private final Map<String, CachedStream> resolvedStreamCache = new ConcurrentHashMap<>();
    private final Set<String> pendingPrefetches = ConcurrentHashMap.newKeySet();

    private final Object alLock = new Object();
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private final AtomicInteger streamSessionId = new AtomicInteger(0);
    private Process currentFfmpegProcess = null;
    private Process currentYtdlpProcess = null;
    private Process currentPipeYtdlpProcess = null;

    private int sourceId = 0;
    private final int[] buffers = new int[24]; // 24 buffers rotativos (~2.4 segundos)
    private boolean openALInitialized = false;

    private volatile TrackInfo currentTrack = null;
    private volatile boolean isPlaying = false;
    private volatile PlaybackStatus playbackStatus = PlaybackStatus.IDLE;
    private float volume = 0.8f;
    private float volumeBeforeMute = 0.8f;
    private boolean isMuted = false;

    private String lastPlayedTrackId = "";
    private volatile boolean isActualSoundPlaying = false;
    private volatile long lastAudioFlowTimestamp = 0;
    private volatile long streamAttemptStartTimestamp = 0;
    private volatile long lastFailureTimestamp = 0;
    private volatile boolean reachedNaturalEnd = false;
    private volatile long lastServerPositionMs = 0;

    // Estado sincronizado desde el servidor
    private volatile List<TrackInfo> currentQueue = new ArrayList<>();
    private volatile List<TrackInfo> history = new ArrayList<>();
    private volatile boolean autoplayEnabled = false;
    private volatile int loopModeOrdinal = 0;
    private volatile TrackInfo autoplayNextTrack = null;

    private ClientAudioEngine() {
    }

    public static ClientAudioEngine getInstance() {
        return INSTANCE;
    }

    public void resetOpenAL() {
        synchronized (alLock) {
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
                AL10.alGetError();
            } catch (Throwable t) {
                LOGGER.warn("[PlayCraft] Aviso al liberar canales OpenAL: {}", t.getMessage());
            }
        }
    }

    private void ensureOpenAL() {
        synchronized (alLock) {
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
                    LOGGER.info("[PlayCraft] Canales OpenAL inicializados con éxito (24 buffers).");
                } else {
                    LOGGER.warn("[PlayCraft] Error asignando canales OpenAL.");
                    openALInitialized = false;
                }
            } catch (Throwable e) {
                LOGGER.error("[PlayCraft] Error en OpenAL: ", e);
                openALInitialized = false;
            }
        }
    }

    public synchronized void syncTrack(
            TrackInfo track,
            long positionMs,
            boolean playing,
            List<TrackInfo> queue,
            boolean autoplayEnabled,
            int loopModeOrdinal,
            TrackInfo autoplayNextTrack,
            List<TrackInfo> history
    ) {
        this.currentTrack = track;
        this.isPlaying = playing;
        this.lastServerPositionMs = Math.max(0L, positionMs);
        this.currentQueue = queue != null ? queue : new ArrayList<>();
        this.autoplayEnabled = autoplayEnabled;
        this.loopModeOrdinal = loopModeOrdinal;
        this.autoplayNextTrack = autoplayNextTrack;
        this.history = history != null ? history : new ArrayList<>();

        // Pre-resolver en segundo plano la próxima canción (primero cola, si no, sugerencia de Autoplay)
        if (!this.currentQueue.isEmpty()) {
            TrackInfo nextTrack = this.currentQueue.get(0);
            if (nextTrack != null && nextTrack.streamUrl() != null && !nextTrack.streamUrl().trim().isEmpty()) {
                prefetchStreamUrl(nextTrack.streamUrl());
            }
        } else if (autoplayEnabled && autoplayNextTrack != null
                && autoplayNextTrack.streamUrl() != null && !autoplayNextTrack.streamUrl().trim().isEmpty()) {
            prefetchStreamUrl(autoplayNextTrack.streamUrl());
        }

        if (track == null) {
            this.lastPlayedTrackId = "";
            this.reachedNaturalEnd = false;
            this.playbackStatus = PlaybackStatus.IDLE;
            stopCurrentStream();
            return;
        }

        if (!playing) {
            this.playbackStatus = PlaybackStatus.PAUSED;
            pauseOrStop();
            return;
        }

        String query = track.streamUrl();
        if (query == null || query.trim().isEmpty()) return;

        long now = System.currentTimeMillis();

        // Pista nueva: iniciar reproducción desde posición del servidor
        if (!track.id().equals(lastPlayedTrackId)) {
            this.lastPlayedTrackId = track.id();
            this.reachedNaturalEnd = false;
            this.lastFailureTimestamp = 0L;
            this.playbackStatus = PlaybackStatus.LOADING;
            startPlayback(query, positionMs);
            return;
        }

        if (isStreaming.get()) {
            boolean sourceValid;
            synchronized (alLock) {
                sourceValid = sourceId != 0 && AL10.alIsSource(sourceId);
                if (sourceValid && isActualSoundPlaying) {
                    int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
                    int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
                    if (state != AL10.AL_PLAYING && queued > 0) {
                        AL10.alSourcef(sourceId, AL10.AL_GAIN, isMuted ? 0.0f : volume);
                        AL10.alSourcePlay(sourceId);
                        this.playbackStatus = PlaybackStatus.PLAYING;
                    }
                }
            }

            if (!sourceValid) {
                LOGGER.warn("[PlayCraft] Source OpenAL inválido. Reconectando audio...");
                this.playbackStatus = PlaybackStatus.RECONNECTING;
                startPlayback(query, positionMs);
                return;
            }

            // Watchdog de arranque inicial: si lleva >18s intentando cargar sin sonar todavía
            if (!isActualSoundPlaying && streamAttemptStartTimestamp > 0 && (now - streamAttemptStartTimestamp > 18000L)) {
                LOGGER.warn("[PlayCraft] Watchdog detectó carga inicial estancada (>18s). Reintentando stream...");
                resolvedStreamCache.remove(query);
                this.playbackStatus = PlaybackStatus.RECONNECTING;
                startPlayback(query, positionMs);
                return;
            }

            // Watchdog en vivo: si ya estaba sonando pero lleva >5s sin fluir audio
            if (isActualSoundPlaying && lastAudioFlowTimestamp > 0 && (now - lastAudioFlowTimestamp > 5000L)) {
                LOGGER.warn("[PlayCraft] Watchdog detectó estancamiento de audio (>5s). Reconectando stream...");
                resolvedStreamCache.remove(query);
                this.playbackStatus = PlaybackStatus.RECONNECTING;
                startPlayback(query, positionMs);
            }
        } else {
            // El stream no está activo y no terminó naturalmente (falló o murió antes de tiempo)
            if (!reachedNaturalEnd && (now - lastFailureTimestamp > 8000L)) {
                LOGGER.warn("[PlayCraft] Stream inactivo antes del fin de la pista. Reintentando reproducción...");
                this.playbackStatus = PlaybackStatus.RECONNECTING;
                startPlayback(query, positionMs);
            }
        }
    }

    public synchronized void reconnectCurrentTrack() {
        TrackInfo track = this.currentTrack;
        if (track == null || track.streamUrl() == null || track.streamUrl().trim().isEmpty()) {
            return;
        }
        String query = track.streamUrl();
        long resumeMs = Math.max(0L, MusicHudOverlay.getLivePositionMs());
        LOGGER.info("[PlayCraft] Reconexión manual solicitada para '{}' en {}ms", track.title(), resumeMs);
        resolvedStreamCache.remove(query);
        this.reachedNaturalEnd = false;
        this.lastFailureTimestamp = 0L;
        stopCurrentStream();
        resetOpenAL();
        this.playbackStatus = PlaybackStatus.RECONNECTING;
        if (this.isPlaying) {
            startPlayback(query, resumeMs);
        }
    }

    public void prefetchStreamUrl(String queryOrUrl) {
        if (queryOrUrl == null || queryOrUrl.trim().isEmpty()) return;
        CachedStream existing = resolvedStreamCache.get(queryOrUrl);
        if (existing != null && !existing.isExpired()) {
            return;
        }
        if (!pendingPrefetches.add(queryOrUrl)) {
            return;
        }
        prefetchExecutor.submit(() -> {
            try {
                String url = resolveDirectStreamUrl(queryOrUrl, -1);
                if (url != null && !url.isEmpty()) {
                    resolvedStreamCache.put(queryOrUrl, new CachedStream(url));
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
                String format = attempts == 1 ? "bestaudio[protocol^=http]/bestaudio/best" : "ba/b";
                ProcessBuilder ytdlpPb = new ProcessBuilder(
                        BinaryLocator.getYtdlp(),
                        "-g",
                        "-f", format,
                        "--force-ipv4",
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
                boolean finished = ytdlpProc.waitFor(15, TimeUnit.SECONDS);
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
        streamAttemptStartTimestamp = System.currentTimeMillis();
        isActualSoundPlaying = false;
        isStreaming.set(true);

        streamExecutor.submit(() -> {
            // Intentos 1 y 2: URL directa hacia ffmpeg con timeout de red y reconexión HTTP
            // Intento 3 (fallback): pipe yt-dlp --http-chunk-size 10M -o - -> ffmpeg
            for (int streamTry = 1; streamTry <= 3; streamTry++) {
                if (sessionId != streamSessionId.get()) return;

                boolean usePipeFallback = (streamTry == 3);
                long effectiveStartMs = streamTry == 1 ? startMs : Math.max(startMs, lastServerPositionMs);
                Process ffmpegProc = null;
                Process pipeYtdlpProc = null;
                Thread pumpThread = null;

                try {
                    streamAttemptStartTimestamp = System.currentTimeMillis();
                    if (streamTry > 1) {
                        this.playbackStatus = PlaybackStatus.RECONNECTING;
                    }

                    int sampleRate = 48000;
                    int channels = 2;
                    int alFormat = AL10.AL_FORMAT_STEREO16;
                    int bufferSize = 19200; // ~100ms PCM 48kHz stereo 16-bit

                    String startOffsetSec = String.format(Locale.US, "%.2f", effectiveStartMs / 1000.0);

                    if (!usePipeFallback) {
                        String directStreamUrl = null;
                        CachedStream cached = resolvedStreamCache.get(queryOrUrl);
                        if (cached != null) {
                            if (cached.isExpired()) {
                                resolvedStreamCache.remove(queryOrUrl);
                            } else {
                                directStreamUrl = cached.url;
                            }
                        }

                        if (directStreamUrl == null || directStreamUrl.isEmpty()) {
                            LOGGER.info("[PlayCraft] Resolviendo stream de YouTube (intento {}) para: {}", streamTry, queryOrUrl);
                            directStreamUrl = resolveDirectStreamUrl(queryOrUrl, sessionId);
                            if (directStreamUrl != null && !directStreamUrl.isEmpty()) {
                                resolvedStreamCache.put(queryOrUrl, new CachedStream(directStreamUrl));
                            }
                        } else {
                            LOGGER.info("[PlayCraft] Usando stream en caché para: {}", queryOrUrl);
                        }

                        if (directStreamUrl == null || directStreamUrl.trim().isEmpty()) {
                            LOGGER.warn("[PlayCraft] No se obtuvo URL directa en intento {} para: {}", streamTry, queryOrUrl);
                            continue;
                        }
                        if (sessionId != streamSessionId.get()) return;

                        List<String> cmd = new ArrayList<>();
                        cmd.add(BinaryLocator.getFfmpeg());
                        cmd.add("-rw_timeout");
                        cmd.add("10000000");
                        cmd.add("-reconnect");
                        cmd.add("1");
                        cmd.add("-reconnect_streamed");
                        cmd.add("1");
                        cmd.add("-reconnect_on_network_error");
                        cmd.add("1");
                        cmd.add("-reconnect_on_http_error");
                        cmd.add("4xx,5xx");
                        cmd.add("-reconnect_delay_max");
                        cmd.add("5");
                        cmd.add("-analyzeduration");
                        cmd.add("2000000");
                        cmd.add("-probesize");
                        cmd.add("1000000");
                        if (effectiveStartMs > 1500) {
                            cmd.add("-ss");
                            cmd.add(startOffsetSec);
                        }
                        cmd.add("-i");
                        cmd.add(directStreamUrl);
                        cmd.add("-f");
                        cmd.add("s16le");
                        cmd.add("-ac");
                        cmd.add(String.valueOf(channels));
                        cmd.add("-ar");
                        cmd.add(String.valueOf(sampleRate));
                        cmd.add("-v");
                        cmd.add("quiet");
                        cmd.add("pipe:1");

                        ProcessBuilder ffmpegPb = new ProcessBuilder(cmd);
                        ffmpegPb.redirectError(ProcessBuilder.Redirect.DISCARD);
                        ffmpegProc = ffmpegPb.start();
                    } else {
                        LOGGER.info("[PlayCraft] Activando modo fallback por pipe (yt-dlp -> ffmpeg) para: {}", queryOrUrl);
                        String target = queryOrUrl;
                        if (!target.startsWith("http://") && !target.startsWith("https://")) {
                            target = "ytsearch1:" + queryOrUrl;
                        }
                        ProcessBuilder ytdlpPipePb = new ProcessBuilder(
                                BinaryLocator.getYtdlp(),
                                "-f", "bestaudio/best",
                                "--http-chunk-size", "10M",
                                "--force-ipv4",
                                "--no-playlist",
                                "--no-warnings",
                                "--no-check-certificates",
                                "-o", "-",
                                target
                        );
                        ytdlpPipePb.redirectError(ProcessBuilder.Redirect.DISCARD);
                        pipeYtdlpProc = ytdlpPipePb.start();

                        List<String> ffCmd = new ArrayList<>();
                        ffCmd.add(BinaryLocator.getFfmpeg());
                        ffCmd.add("-i");
                        ffCmd.add("pipe:0");
                        if (effectiveStartMs > 1500) {
                            ffCmd.add("-ss");
                            ffCmd.add(startOffsetSec);
                        }
                        ffCmd.add("-f");
                        ffCmd.add("s16le");
                        ffCmd.add("-ac");
                        ffCmd.add(String.valueOf(channels));
                        ffCmd.add("-ar");
                        ffCmd.add(String.valueOf(sampleRate));
                        ffCmd.add("-v");
                        ffCmd.add("quiet");
                        ffCmd.add("pipe:1");

                        ProcessBuilder ffmpegPb = new ProcessBuilder(ffCmd);
                        ffmpegPb.redirectError(ProcessBuilder.Redirect.DISCARD);
                        ffmpegProc = ffmpegPb.start();

                        final Process srcProc = pipeYtdlpProc;
                        final Process dstProc = ffmpegProc;
                        pumpThread = new Thread(() -> {
                            byte[] copyBuf = new byte[16384];
                            try (InputStream in = srcProc.getInputStream();
                                 OutputStream out = dstProc.getOutputStream()) {
                                int n;
                                while (sessionId == streamSessionId.get() && (n = in.read(copyBuf)) != -1) {
                                    out.write(copyBuf, 0, n);
                                }
                                out.flush();
                            } catch (Exception ignored) {
                            }
                        }, "PlayCraft-PipePump");
                        pumpThread.setDaemon(true);
                        pumpThread.start();
                    }

                    synchronized (this) {
                        if (sessionId != streamSessionId.get()) {
                            ffmpegProc.destroyForcibly();
                            if (pipeYtdlpProc != null) pipeYtdlpProc.destroyForcibly();
                            return;
                        }
                        currentFfmpegProcess = ffmpegProc;
                        currentPipeYtdlpProcess = pipeYtdlpProc;
                    }

                    InputStream audioStream = ffmpegProc.getInputStream();
                    byte[] rawChunk = new byte[bufferSize];
                    ByteBuffer directBuffer = ByteBuffer.allocateDirect(bufferSize);

                    // Desasociar y limpiar cualquier buffer residual bajo alLock
                    synchronized (alLock) {
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

                    // Precarga inicial rápida: hasta 4 buffers (400ms) para arrancar de inmediato
                    // y el bucle principal rellena hasta los 24 buffers (~2.4s de colchón).
                    int initialFilled = 0;
                    boolean initialEof = false;
                    for (int i = 0; i < 4; i++) {
                        if (sessionId != streamSessionId.get()) break;
                        int read = readFully(audioStream, rawChunk);
                        if (read > 0) {
                            synchronized (alLock) {
                                if (sourceId != 0 && AL10.alIsSource(sourceId) && buffers[i] != 0) {
                                    directBuffer.clear();
                                    directBuffer.put(rawChunk, 0, read);
                                    directBuffer.flip();
                                    AL10.alBufferData(buffers[i], alFormat, directBuffer, sampleRate);
                                    AL10.alSourceQueueBuffers(sourceId, buffers[i]);
                                    initialFilled++;
                                }
                            }
                        } else {
                            initialEof = true;
                            break;
                        }
                    }

                    if (sessionId != streamSessionId.get()) {
                        return;
                    }

                    if (initialFilled == 0) {
                        LOGGER.warn("[PlayCraft] Carga inicial vacía en intento {} (ffmpeg devolvió 0 bytes). Reintentando...", streamTry);
                        resolvedStreamCache.remove(queryOrUrl);
                        continue;
                    }

                    int nextUnusedBuffer = initialFilled;

                    if (isPlaying) {
                        synchronized (alLock) {
                            if (sourceId != 0 && AL10.alIsSource(sourceId)) {
                                AL10.alSourcef(sourceId, AL10.AL_GAIN, isMuted ? 0.0f : volume);
                                AL10.alSourcePlay(sourceId);
                            }
                        }
                        isActualSoundPlaying = true;
                        playbackStatus = PlaybackStatus.PLAYING;
                        lastAudioFlowTimestamp = System.currentTimeMillis();
                        MusicHudOverlay.onLocalStreamStarted();
                        sendStreamReadyToServer();
                    } else {
                        playbackStatus = PlaybackStatus.PAUSED;
                    }

                    // Bucle principal de alimentación a OpenAL (sin mantener alLock durante I/O)
                    boolean eofReached = initialEof;
                    boolean prematureCut = false;

                    while (sessionId == streamSessionId.get() && isStreaming.get()) {
                        if (!isPlaying) {
                            synchronized (alLock) {
                                if (sourceId != 0 && AL10.alIsSource(sourceId)) {
                                    int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
                                    if (state == AL10.AL_PLAYING) {
                                        AL10.alSourcePause(sourceId);
                                    }
                                }
                            }
                            playbackStatus = PlaybackStatus.PAUSED;
                            lastAudioFlowTimestamp = System.currentTimeMillis();
                            Thread.sleep(50);
                            continue;
                        }

                        boolean sourceInvalid;
                        synchronized (alLock) {
                            sourceInvalid = (sourceId == 0 || !AL10.alIsSource(sourceId));
                        }
                        if (sourceInvalid) {
                            LOGGER.warn("[PlayCraft] Canal de audio OpenAL invalidado. Reinicializando...");
                            resetOpenAL();
                            ensureOpenAL();
                            nextUnusedBuffer = 0;
                        }

                        // 1. Seguir ampliando el colchón hasta 24 buffers mientras no haya EOF
                        int topUpPerCycle = 0;
                        while (!eofReached && nextUnusedBuffer < buffers.length && topUpPerCycle < 2
                                && sessionId == streamSessionId.get() && isPlaying) {
                            int read = readFully(audioStream, rawChunk);
                            if (read > 0) {
                                synchronized (alLock) {
                                    if (sourceId != 0 && AL10.alIsSource(sourceId) && buffers[nextUnusedBuffer] != 0) {
                                        directBuffer.clear();
                                        directBuffer.put(rawChunk, 0, read);
                                        directBuffer.flip();
                                        AL10.alBufferData(buffers[nextUnusedBuffer], alFormat, directBuffer, sampleRate);
                                        AL10.alSourceQueueBuffers(sourceId, buffers[nextUnusedBuffer]);
                                        nextUnusedBuffer++;
                                        lastAudioFlowTimestamp = System.currentTimeMillis();
                                    }
                                }
                                topUpPerCycle++;
                            } else {
                                eofReached = true;
                            }
                        }

                        // 2. Desencolar buffers ya reproducidos y rellenarlos sin bloquear alLock en readFully
                        int processedCount;
                        synchronized (alLock) {
                            processedCount = (sourceId != 0 && AL10.alIsSource(sourceId))
                                    ? AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED)
                                    : 0;
                        }

                        while (processedCount > 0 && sessionId == streamSessionId.get() && isPlaying) {
                            int unqueuedBufferId = 0;
                            synchronized (alLock) {
                                if (sourceId != 0 && AL10.alIsSource(sourceId)
                                        && AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED) > 0) {
                                    unqueuedBufferId = AL10.alSourceUnqueueBuffers(sourceId);
                                }
                            }
                            if (unqueuedBufferId == 0) break;

                            if (!eofReached) {
                                int read = readFully(audioStream, rawChunk);
                                if (read > 0) {
                                    synchronized (alLock) {
                                        if (sourceId != 0 && AL10.alIsSource(sourceId)) {
                                            directBuffer.clear();
                                            directBuffer.put(rawChunk, 0, read);
                                            directBuffer.flip();
                                            AL10.alBufferData(unqueuedBufferId, alFormat, directBuffer, sampleRate);
                                            AL10.alSourceQueueBuffers(sourceId, unqueuedBufferId);
                                            lastAudioFlowTimestamp = System.currentTimeMillis();
                                        }
                                    }
                                } else {
                                    eofReached = true;
                                }
                            }
                            processedCount--;
                        }

                        int state;
                        int currentQueued;
                        synchronized (alLock) {
                            if (sourceId != 0 && AL10.alIsSource(sourceId)) {
                                state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
                                currentQueued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
                                if (state != AL10.AL_PLAYING && isPlaying && currentQueued > 0) {
                                    AL10.alSourcef(sourceId, AL10.AL_GAIN, isMuted ? 0.0f : volume);
                                    AL10.alSourcePlay(sourceId);
                                    state = AL10.AL_PLAYING;
                                }
                            } else {
                                state = AL10.AL_STOPPED;
                                currentQueued = 0;
                            }
                        }

                        if (state == AL10.AL_PLAYING) {
                            playbackStatus = PlaybackStatus.PLAYING;
                            lastAudioFlowTimestamp = System.currentTimeMillis();
                        }

                        if ((eofReached || !ffmpegProc.isAlive()) && currentQueued == 0 && state != AL10.AL_PLAYING) {
                            TrackInfo ct = this.currentTrack;
                            if (ct != null && ct.durationMs() > 0 && lastServerPositionMs < ct.durationMs() - 5000L) {
                                prematureCut = true;
                                LOGGER.warn("[PlayCraft] Corte prematuro detectado en {}ms / {}ms. Reintentando siguiente método...",
                                        lastServerPositionMs, ct.durationMs());
                            } else {
                                reachedNaturalEnd = true;
                            }
                            break;
                        }

                        Thread.sleep(20);
                    }

                    if (!prematureCut) {
                        // Salida limpia (fin natural o cambio de pista / stop externo)
                        break;
                    } else {
                        resolvedStreamCache.remove(queryOrUrl);
                    }

                } catch (Exception e) {
                    if (sessionId == streamSessionId.get() && isStreaming.get()) {
                        LOGGER.error("[PlayCraft] Error durante el streaming (intento {}): ", streamTry, e);
                        resolvedStreamCache.remove(queryOrUrl);
                        resetOpenAL();
                        ensureOpenAL();
                    }
                } finally {
                    if (ffmpegProc != null) {
                        ffmpegProc.destroyForcibly();
                    }
                    if (pipeYtdlpProc != null) {
                        pipeYtdlpProc.destroyForcibly();
                    }
                    if (pumpThread != null && pumpThread.isAlive()) {
                        pumpThread.interrupt();
                    }
                    synchronized (this) {
                        if (currentFfmpegProcess == ffmpegProc) {
                            currentFfmpegProcess = null;
                        }
                        if (currentPipeYtdlpProcess == pipeYtdlpProc) {
                            currentPipeYtdlpProcess = null;
                        }
                    }
                }
            }

            if (sessionId == streamSessionId.get()) {
                isStreaming.set(false);
                if (!reachedNaturalEnd) {
                    lastFailureTimestamp = System.currentTimeMillis();
                    playbackStatus = PlaybackStatus.ERROR;
                }
            }
        });
    }

    private void sendStreamReadyToServer() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getConnection() != null) {
                mc.execute(() -> {
                    if (mc.getConnection() != null) {
                        ClientPlayNetworking.send(new ControlTrackPayload(ControlTrackPayload.Action.STREAM_READY));
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    private int readFully(InputStream in, byte[] buffer) {
        int totalRead = 0;
        try {
            while (totalRead < buffer.length) {
                int read = in.read(buffer, totalRead, buffer.length - totalRead);
                if (read == -1) break;
                totalRead += read;
            }
        } catch (Exception ignored) {
        }
        return totalRead;
    }

    public synchronized void stopCurrentStream() {
        streamSessionId.incrementAndGet();
        isStreaming.set(false);
        isActualSoundPlaying = false;
        lastAudioFlowTimestamp = 0;
        streamAttemptStartTimestamp = 0;
        if (currentYtdlpProcess != null) {
            currentYtdlpProcess.destroyForcibly();
            currentYtdlpProcess = null;
        }
        if (currentPipeYtdlpProcess != null) {
            currentPipeYtdlpProcess.destroyForcibly();
            currentPipeYtdlpProcess = null;
        }
        if (currentFfmpegProcess != null) {
            currentFfmpegProcess.destroyForcibly();
            currentFfmpegProcess = null;
        }
        synchronized (alLock) {
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
    }

    public void pauseOrStop() {
        synchronized (alLock) {
            if (sourceId != 0 && AL10.alIsSource(sourceId)) {
                AL10.alSourcePause(sourceId);
            }
        }
    }

    public synchronized void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        if (this.volume > 0.0f) {
            this.isMuted = false;
        }
        synchronized (alLock) {
            if (sourceId != 0 && AL10.alIsSource(sourceId)) {
                AL10.alSourcef(sourceId, AL10.AL_GAIN, this.isMuted ? 0.0f : this.volume);
            }
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
            synchronized (alLock) {
                if (sourceId != 0 && AL10.alIsSource(sourceId)) {
                    AL10.alSourcef(sourceId, AL10.AL_GAIN, 0.0f);
                }
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

    public PlaybackStatus getPlaybackStatus() {
        return playbackStatus;
    }

    public List<TrackInfo> getQueue() {
        return currentQueue;
    }

    public List<TrackInfo> getHistory() {
        return history;
    }

    public boolean isAutoplayEnabled() {
        return autoplayEnabled;
    }

    public int getLoopModeOrdinal() {
        return loopModeOrdinal;
    }

    public TrackInfo getAutoplayNextTrack() {
        return autoplayNextTrack;
    }
}
