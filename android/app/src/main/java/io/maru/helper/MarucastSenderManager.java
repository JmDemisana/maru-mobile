package io.maru.helper;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.provider.OpenableColumns;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MarucastSenderManager {
    private static final String SERVICE_TYPE = "_marucast._tcp.";
    private static final String SERVICE_PREFIX = "Marucast";
    private static final String RELAY_MODE_CAPTURE = "playback-capture";
    private static final String RELAY_MODE_FILE = "local-file";
    private static final String RELAY_MODE_SYNC_CLOCK = "sync-clock";
    private static final String LIVE_PCM_PATH = "/live.pcm";
    private static final String LIVE_MIC_PCM_PATH = "/live-mic.pcm";
    private static final String ARTWORK_PATH = "/artwork";
    private static final String VOCAL_MODE_NORMAL = "normal";
    private static final String VOCAL_MODE_REMOVE = "remove";
    private static final String LIVE_PCM_MIME_TYPE = "application/octet-stream";
    private static final String LIVE_CAPTURE_LABEL = "Current phone playback";
    private static final int LIVE_CHANNEL_COUNT = 2;
    private static final int LIVE_SAMPLE_RATE = 48000;
    private static final int LIVE_MIC_CHANNEL_COUNT = 1;
    private static final int[] LIVE_MIC_SAMPLE_RATE_CANDIDATES =
        new int[] { 48000, 44100, 16000 };
    private static final int[] LIVE_MIC_SOURCE_CANDIDATES =
        new int[] {
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        };
    private static final int FIXED_RELAY_PORT = 48543;
    private static final int PCM_BYTES_PER_SAMPLE = 2;
    private static final double PCM_16_NORMALIZED_SCALE = 32768.0d;
    private static final double VOCAL_BAND_HIGHPASS_HZ = 180.0d;
    private static final double VOCAL_BAND_LOWPASS_HZ = 5200.0d;
    private static final double VOCAL_BAND_HIGHPASS_ALPHA =
        computeHighPassAlpha(VOCAL_BAND_HIGHPASS_HZ);
    private static final double VOCAL_BAND_LOWPASS_ALPHA =
        computeLowPassAlpha(VOCAL_BAND_LOWPASS_HZ);
    private static final long AUTO_STOP_IDLE_MS = 2L * 60L * 1000L;
    private static final long MIC_AUTO_STOP_IDLE_MS = 45_000L;
    private static final int RELAY_DELAY_STEP_MS = 250;
    private static final int RELAY_DELAY_LIMIT_MS = 4000;
    private static final double DEFAULT_MIC_MIX_GAIN = 1.28d;
    private static final double MIC_MIX_GAIN_STEP = 0.1d;
    private static final double MIC_MIX_GAIN_MAX = 1.6d;
    private static final double DEFAULT_MIC_MUSIC_BED_LEVEL = 0.8d;
    private static final double MIC_MUSIC_BED_STEP = 0.05d;
    private static final double MIC_MUSIC_BED_MIN = 0.15d;
    private static final double MIC_MUSIC_BED_MAX = 1.0d;
    private static final double MIC_LEVEL_ATTACK_SMOOTHING = 0.48d;
    private static final double MIC_LEVEL_RELEASE_SMOOTHING = 0.18d;
    private static final MarucastSenderManager INSTANCE = new MarucastSenderManager();

    private final Object lock = new Object();

    private Context appContext;
    private Uri selectedAudioUri;
    private String selectedDisplayName = null;
    private String selectedMimeType = null;
    private long selectedSize = -1L;
    private String relayMode = RELAY_MODE_FILE;
    private String vocalMode = VOCAL_MODE_NORMAL;
    private String pairingCode = null;
    private String registeredServiceName = null;
    private String lastError = null;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private int listeningPort = -1;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private Intent playbackCaptureData;
    private int playbackCaptureResultCode = 0;
    private MediaProjection mediaProjection;
    private MediaProjection.Callback mediaProjectionCallback;
    private VirtualDisplay projectionKeepaliveDisplay;
    private ImageReader projectionKeepaliveImageReader;
    private AudioRecord captureAudioRecord;
    private Thread captureThread;
    private AudioRecord microphoneAudioRecord;
    private AcousticEchoCanceler microphoneEchoCanceler;
    private AutomaticGainControl microphoneAutomaticGainControl;
    private Thread microphoneCaptureThread;
    private NoiseSuppressor microphoneNoiseSuppressor;
    private HelperStemVocalReducer stemVocalReducer;
    private final List<LivePcmClient> livePcmClients = new ArrayList<>();
    private final List<LivePcmClient> liveMicClients = new ArrayList<>();
    private long relayIdleSinceMs = 0L;
    private long micRelayIdleSinceMs = 0L;
    private String micLastError = null;
    private boolean microphoneEchoCancelerActive = false;
    private boolean microphoneAutomaticGainControlActive = false;
    private boolean microphoneNoiseSuppressorActive = false;
    private int liveMicSampleRate = LIVE_MIC_SAMPLE_RATE_CANDIDATES[0];
    private int relayDelayMs = 0;
    private double micLevel = 0.0d;
    private double micMixGain = DEFAULT_MIC_MIX_GAIN;
    private double micMusicBedLevel = DEFAULT_MIC_MUSIC_BED_LEVEL;
    private int vocalProcessingStateVersion = 1;
    private int appliedVocalProcessingStateVersion = -1;
    private String appliedVocalMode = VOCAL_MODE_NORMAL;
    private double vocalBandHighPassState = 0.0d;
    private double vocalBandHighPassPreviousInput = 0.0d;
    private double vocalBandLowPassState = 0.0d;
    private double vocalBandEnvelope = 0.0d;

    public static MarucastSenderManager getInstance() {
        return INSTANCE;
    }

    private MarucastSenderManager() {}

    private static double computeHighPassAlpha(double cutoffHz) {
        double dt = 1.0d / LIVE_SAMPLE_RATE;
        double rc = 1.0d / (2.0d * Math.PI * cutoffHz);
        return rc / (rc + dt);
    }

    private static double computeLowPassAlpha(double cutoffHz) {
        double dt = 1.0d / LIVE_SAMPLE_RATE;
        double rc = 1.0d / (2.0d * Math.PI * cutoffHz);
        return dt / (rc + dt);
    }

    public void bind(Context context) {
        if (context == null) {
            return;
        }

        synchronized (lock) {
            appContext = context.getApplicationContext();
            if (nsdManager == null && appContext != null) {
                nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
            }
        }
    }

    public void selectAudio(Context context, Uri uri) {
        if (uri == null) {
            return;
        }

        bind(context);
        synchronized (lock) {
            relayMode = RELAY_MODE_FILE;
            selectedAudioUri = uri;
            querySelectedAudioMetadataLocked();
            ensurePairingCodeLocked();
            lastError = null;

            if (isRunningLocked()) {
                restartLocked();
            }
        }
    }

    public void clearSelectedAudio() {
        synchronized (lock) {
            stopLocked();
            selectedAudioUri = null;
            selectedDisplayName = null;
            selectedMimeType = null;
            selectedSize = -1L;
            relayMode = RELAY_MODE_FILE;
            lastError = null;
        }
    }

    public void start(Context context) {
        bind(context);
        synchronized (lock) {
            if (selectedAudioUri == null) {
                lastError = "Pick a local audio file first.";
                return;
            }

            relayMode = RELAY_MODE_FILE;
            ensurePairingCodeLocked();
            lastError = null;
            restartLocked();
        }
    }

    public void startSyncClock(Context context) {
        bind(context);
        synchronized (lock) {
            relayMode = RELAY_MODE_SYNC_CLOCK;
            ensurePairingCodeLocked();
            lastError = null;
            restartLocked();
        }
    }

    public void startPlaybackCapture(Context context, int resultCode, Intent resultData) {
        bind(context);
        synchronized (lock) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                lastError = "Playback relay needs Android 10 or newer on the helper.";
                return;
            }
            if (resultCode == 0 || resultData == null) {
                lastError = "Playback relay could not start because Android did not return capture access.";
                return;
            }

            relayMode = RELAY_MODE_CAPTURE;
            playbackCaptureResultCode = resultCode;
            playbackCaptureData = new Intent(resultData);
            selectedAudioUri = null;
            selectedDisplayName = LIVE_CAPTURE_LABEL;
            selectedMimeType = LIVE_PCM_MIME_TYPE;
            selectedSize = -1L;
            ensurePairingCodeLocked();
            lastError = null;
            restartLocked();
        }
    }

    public void stopPlaybackCapture() {
        synchronized (lock) {
            relayMode = RELAY_MODE_FILE;
            playbackCaptureResultCode = 0;
            playbackCaptureData = null;
            stopLocked();
            lastError = null;
        }
    }

    public boolean isPlaybackCaptureRunning() {
        synchronized (lock) {
            return RELAY_MODE_CAPTURE.equals(relayMode) &&
                captureAudioRecord != null &&
                captureThread != null &&
                captureThread.isAlive();
        }
    }

    public void setLastError(String message) {
        synchronized (lock) {
            lastError = message;
        }
    }

    public void setMicLastError(String message) {
        synchronized (lock) {
            micLastError = message;
        }
    }

    public void stop() {
        synchronized (lock) {
            relayMode = RELAY_MODE_FILE;
            playbackCaptureResultCode = 0;
            playbackCaptureData = null;
            stopLocked();
            lastError = null;
        }
    }

    public String getStatusJson() {
        synchronized (lock) {
            return buildStatusJsonLocked().toString();
        }
    }

    public String dispatchControlCommand(String rawCommand) {
        synchronized (lock) {
            return dispatchControlCommandLocked(rawCommand).toString();
        }
    }

    public String getActiveRelayUrl() {
        synchronized (lock) {
            return getActiveRelayUrlLocked();
        }
    }

    public String getActivePairingCode() {
        synchronized (lock) {
            return pairingCode;
        }
    }

    private void restartLocked() {
        stopLocked();

        if (appContext == null) {
            return;
        }
        if (!enforceWifiRequirementLocked()) {
            return;
        }

        boolean shouldStartFileRelay =
            RELAY_MODE_FILE.equals(relayMode) && selectedAudioUri != null;
        boolean shouldStartCaptureRelay =
            RELAY_MODE_CAPTURE.equals(relayMode) &&
                playbackCaptureResultCode != 0 &&
                playbackCaptureData != null;
        boolean shouldStartSyncClockRelay = RELAY_MODE_SYNC_CLOCK.equals(relayMode);

        if (!shouldStartFileRelay && !shouldStartCaptureRelay && !shouldStartSyncClockRelay) {
            return;
        }

        try {
            startServerLocked();
            if (shouldStartCaptureRelay) {
                startPlaybackCaptureLocked();
            }
        } catch (Exception error) {
            lastError = RELAY_MODE_CAPTURE.equals(relayMode)
                ? "Couldn't start the live Marucast playback relay."
                : "Couldn't start the local Marucast sender.";
            stopLocked();
        }
    }

    private void stopLocked() {
        stopPlaybackCaptureLocked();

        if (registrationListener != null && nsdManager != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (Exception ignored) {
                // Ignore stale NSD listener cleanup failures.
            }
        }

        registrationListener = null;
        registeredServiceName = null;

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Ignore socket shutdown failures.
            }
        }

        serverSocket = null;
        serverThread = null;
        listeningPort = -1;
    }

    private String getWifiRequirementMessageLocked() {
        return "Marucast needs this phone connected to the same Wi-Fi as the receiver.";
    }

    private boolean enforceWifiRequirementLocked() {
        if (isWifiConnectedLocked()) {
            return true;
        }

        if (isRunningLocked()) {
            stopLocked();
        }

        lastError = getWifiRequirementMessageLocked();
        micLastError = getWifiRequirementMessageLocked();
        return false;
    }

    private void startServerLocked() throws IOException {
        serverSocket = new ServerSocket(FIXED_RELAY_PORT);
        listeningPort = serverSocket.getLocalPort();
        serverThread = new Thread(this::runServerLoop, "MarucastSenderServer");
        serverThread.setDaemon(true);
        serverThread.start();
        registerNsdServiceLocked();
    }

    private void startPlaybackCaptureLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || appContext == null) {
            throw new IllegalStateException("Playback capture is not available.");
        }

        MediaProjectionManager projectionManager =
            (MediaProjectionManager) appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (projectionManager == null || playbackCaptureData == null || playbackCaptureResultCode == 0) {
            throw new IllegalStateException("Playback capture permission is missing.");
        }

        mediaProjection = projectionManager.getMediaProjection(
            playbackCaptureResultCode,
            playbackCaptureData
        );
        if (mediaProjection == null) {
            throw new IllegalStateException("Playback capture session could not start.");
        }

        try {
            int densityDpi = Math.max(
                1,
                appContext.getResources().getDisplayMetrics().densityDpi
            );
            projectionKeepaliveImageReader = ImageReader.newInstance(
                1,
                1,
                PixelFormat.RGBA_8888,
                2
            );
            projectionKeepaliveDisplay = mediaProjection.createVirtualDisplay(
                "MarucastProjectionKeepalive",
                1,
                1,
                densityDpi,
                0,
                projectionKeepaliveImageReader.getSurface(),
                null,
                null
            );
        } catch (Exception ignored) {
            if (projectionKeepaliveDisplay != null) {
                try {
                    projectionKeepaliveDisplay.release();
                } catch (Exception ignoredRelease) {}
            }
            projectionKeepaliveDisplay = null;
            if (projectionKeepaliveImageReader != null) {
                try {
                    projectionKeepaliveImageReader.close();
                } catch (Exception ignoredClose) {}
            }
            projectionKeepaliveImageReader = null;
        }

        AudioPlaybackCaptureConfiguration captureConfiguration =
            new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();

        AudioFormat audioFormat = new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(LIVE_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build();

        int minimumBufferSize = AudioRecord.getMinBufferSize(
            LIVE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        );
        if (minimumBufferSize <= 0) {
            throw new IllegalStateException("Android would not allocate the relay audio buffer.");
        }

        captureAudioRecord = new AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfiguration)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(Math.max(minimumBufferSize * 2, 16 * 1024))
            .build();
        captureAudioRecord.startRecording();

        mediaProjectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                synchronized (lock) {
                    lastError = "Playback relay ended because Android stopped the capture session.";
                    stopPlaybackCaptureLocked();
                }
            }
        };
        mediaProjection.registerCallback(mediaProjectionCallback, null);

        prepareStemReducerLocked();
        markVocalProcessingStateChangedLocked();
        captureThread = new Thread(this::runPlaybackCaptureLoop, "MarucastPlaybackCapture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    private void stopPlaybackCaptureLocked() {
        stopMicrophoneCaptureLocked();
        relayIdleSinceMs = 0L;
        for (LivePcmClient client : new ArrayList<>(livePcmClients)) {
            removeLivePcmClient(client);
        }

        if (stemVocalReducer != null) {
            stemVocalReducer.shutdown();
        }
        stemVocalReducer = null;

        if (captureAudioRecord != null) {
            try {
                captureAudioRecord.stop();
            } catch (Exception ignored) {}
            try {
                captureAudioRecord.release();
            } catch (Exception ignored) {}
        }
        captureAudioRecord = null;

        if (projectionKeepaliveDisplay != null) {
            try {
                projectionKeepaliveDisplay.release();
            } catch (Exception ignored) {}
        }
        projectionKeepaliveDisplay = null;

        if (projectionKeepaliveImageReader != null) {
            try {
                projectionKeepaliveImageReader.close();
            } catch (Exception ignored) {}
        }
        projectionKeepaliveImageReader = null;

        if (mediaProjection != null && mediaProjectionCallback != null) {
            try {
                mediaProjection.unregisterCallback(mediaProjectionCallback);
            } catch (Exception ignored) {}
        }
        mediaProjectionCallback = null;

        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Exception ignored) {}
        }
        mediaProjection = null;

        if (captureThread != null) {
            captureThread.interrupt();
        }
        captureThread = null;
    }

    private void startMicrophoneCaptureLocked() {
        if (!enforceWifiRequirementLocked()) {
            throw new IllegalStateException(getWifiRequirementMessageLocked());
        }
        if (appContext == null) {
            throw new IllegalStateException("The helper app context is missing.");
        }
        if (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            throw new SecurityException(
                "Open Maru Helper on the phone and allow microphone access first."
            );
        }
        if (
            microphoneAudioRecord != null &&
                microphoneCaptureThread != null &&
                microphoneCaptureThread.isAlive()
        ) {
            micLastError = null;
            return;
        }
        if (microphoneAudioRecord != null || microphoneCaptureThread != null) {
            stopMicrophoneCaptureLocked();
        }

        AudioRecord nextRecord = null;
        int resolvedSampleRate = LIVE_MIC_SAMPLE_RATE_CANDIDATES[0];

        for (int audioSource : LIVE_MIC_SOURCE_CANDIDATES) {
            for (int candidateSampleRate : LIVE_MIC_SAMPLE_RATE_CANDIDATES) {
                AudioRecord candidateRecord = null;
                try {
                    int minimumBufferSize = AudioRecord.getMinBufferSize(
                        candidateSampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    );
                    if (minimumBufferSize <= 0) {
                        continue;
                    }

                    AudioFormat audioFormat = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(candidateSampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build();

                    candidateRecord = new AudioRecord.Builder()
                        .setAudioSource(audioSource)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(Math.max(minimumBufferSize * 2, 8 * 1024))
                        .build();
                    if (candidateRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        try {
                            candidateRecord.release();
                        } catch (Exception ignored) {}
                        continue;
                    }

                    candidateRecord.startRecording();
                    if (
                        candidateRecord.getRecordingState() !=
                            AudioRecord.RECORDSTATE_RECORDING
                    ) {
                        try {
                            candidateRecord.stop();
                        } catch (Exception ignored) {}
                        try {
                            candidateRecord.release();
                        } catch (Exception ignored) {}
                        continue;
                    }
                    byte[] probeBuffer = new byte[1024];
                    int probeRead = candidateRecord.read(
                        probeBuffer,
                        0,
                        probeBuffer.length
                    );
                    if (probeRead < 0) {
                        try {
                            candidateRecord.stop();
                        } catch (Exception ignored) {}
                        try {
                            candidateRecord.release();
                        } catch (Exception ignored) {}
                        continue;
                    }

                    nextRecord = candidateRecord;
                    resolvedSampleRate = candidateSampleRate;
                    if (probeRead > 0) {
                        micLevel = smoothMicLevel(
                            0.0d,
                            computeNormalizedMicPeak(probeBuffer, probeRead)
                        );
                    } else {
                        micLevel = 0.0d;
                    }
                    break;
                } catch (Exception ignored) {
                    if (candidateRecord != null) {
                        try {
                            candidateRecord.stop();
                        } catch (Exception ignoredStop) {}
                        try {
                            candidateRecord.release();
                        } catch (Exception ignoredRelease) {}
                    }
                }
            }

            if (nextRecord != null) {
                break;
            }
        }

        if (nextRecord == null) {
            throw new IllegalStateException(
                "Marucast couldn't open the phone microphone right now."
            );
        }

        microphoneAudioRecord = nextRecord;
        configureMicrophoneFeedbackReductionLocked(nextRecord);
        liveMicSampleRate = resolvedSampleRate;
        micLastError = null;
        micRelayIdleSinceMs = 0L;
        microphoneCaptureThread = new Thread(
            this::runMicrophoneCaptureLoop,
            "MarucastMicrophoneCapture"
        );
        microphoneCaptureThread.setDaemon(true);
        microphoneCaptureThread.start();
    }

    private void stopMicrophoneCaptureLocked() {
        micRelayIdleSinceMs = 0L;
        micLevel = 0.0d;
        for (LivePcmClient client : new ArrayList<>(liveMicClients)) {
            removeLiveMicClient(client);
        }

        releaseMicrophoneFeedbackReductionLocked();

        if (microphoneAudioRecord != null) {
            try {
                microphoneAudioRecord.stop();
            } catch (Exception ignored) {}
            try {
                microphoneAudioRecord.release();
            } catch (Exception ignored) {}
        }
        microphoneAudioRecord = null;

        if (microphoneCaptureThread != null) {
            microphoneCaptureThread.interrupt();
        }
        microphoneCaptureThread = null;
        liveMicSampleRate = LIVE_MIC_SAMPLE_RATE_CANDIDATES[0];
    }

    private void configureMicrophoneFeedbackReductionLocked(AudioRecord audioRecord) {
        releaseMicrophoneFeedbackReductionLocked();

        if (audioRecord == null) {
            return;
        }

        int audioSessionId = audioRecord.getAudioSessionId();
        if (audioSessionId <= 0) {
            return;
        }

        microphoneEchoCancelerActive = enableMicrophoneEchoCancelerLocked(audioSessionId);
        microphoneNoiseSuppressorActive = enableMicrophoneNoiseSuppressorLocked(audioSessionId);
        microphoneAutomaticGainControlActive =
            enableMicrophoneAutomaticGainControlLocked(audioSessionId);
    }

    private boolean enableMicrophoneEchoCancelerLocked(int audioSessionId) {
        if (!AcousticEchoCanceler.isAvailable()) {
            return false;
        }

        try {
            AcousticEchoCanceler effect = AcousticEchoCanceler.create(audioSessionId);
            if (effect == null) {
                return false;
            }
            effect.setEnabled(true);
            microphoneEchoCanceler = effect;
            return effect.getEnabled();
        } catch (Exception ignored) {
            if (microphoneEchoCanceler != null) {
                try {
                    microphoneEchoCanceler.release();
                } catch (Exception ignoredRelease) {}
            }
            microphoneEchoCanceler = null;
            return false;
        }
    }

    private boolean enableMicrophoneNoiseSuppressorLocked(int audioSessionId) {
        if (!NoiseSuppressor.isAvailable()) {
            return false;
        }

        try {
            NoiseSuppressor effect = NoiseSuppressor.create(audioSessionId);
            if (effect == null) {
                return false;
            }
            effect.setEnabled(true);
            microphoneNoiseSuppressor = effect;
            return effect.getEnabled();
        } catch (Exception ignored) {
            if (microphoneNoiseSuppressor != null) {
                try {
                    microphoneNoiseSuppressor.release();
                } catch (Exception ignoredRelease) {}
            }
            microphoneNoiseSuppressor = null;
            return false;
        }
    }

    private boolean enableMicrophoneAutomaticGainControlLocked(int audioSessionId) {
        if (!AutomaticGainControl.isAvailable()) {
            return false;
        }

        try {
            AutomaticGainControl effect = AutomaticGainControl.create(audioSessionId);
            if (effect == null) {
                return false;
            }
            effect.setEnabled(true);
            microphoneAutomaticGainControl = effect;
            return effect.getEnabled();
        } catch (Exception ignored) {
            if (microphoneAutomaticGainControl != null) {
                try {
                    microphoneAutomaticGainControl.release();
                } catch (Exception ignoredRelease) {}
            }
            microphoneAutomaticGainControl = null;
            return false;
        }
    }

    private void releaseMicrophoneFeedbackReductionLocked() {
        if (microphoneEchoCanceler != null) {
            try {
                microphoneEchoCanceler.release();
            } catch (Exception ignored) {}
        }
        microphoneEchoCanceler = null;

        if (microphoneNoiseSuppressor != null) {
            try {
                microphoneNoiseSuppressor.release();
            } catch (Exception ignored) {}
        }
        microphoneNoiseSuppressor = null;

        if (microphoneAutomaticGainControl != null) {
            try {
                microphoneAutomaticGainControl.release();
            } catch (Exception ignored) {}
        }
        microphoneAutomaticGainControl = null;

        microphoneEchoCancelerActive = false;
        microphoneNoiseSuppressorActive = false;
        microphoneAutomaticGainControlActive = false;
    }

    private void prepareStemReducerLocked() {
        if (appContext == null || !BuildConfig.MARUCAST_KARAOKE_ENABLED) {
            return;
        }
        if (stemVocalReducer == null) {
            stemVocalReducer = new HelperStemVocalReducer(appContext);
        }
        if (!stemVocalReducer.prepare()) {
            String reducerError = stemVocalReducer.getLastPrepareErrorMessage();
            lastError =
                reducerError == null || reducerError.trim().isEmpty()
                    ? "Marucast Karaoke couldn't load its local stem model."
                    : reducerError;
        }
    }

    private void registerNsdServiceLocked() {
        if (nsdManager == null || listeningPort <= 0) {
            return;
        }

        final String code = ensurePairingCodeLocked();
        final NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setServiceName(SERVICE_PREFIX + "-" + code);
        serviceInfo.setPort(listeningPort);

        if (code != null && !code.isEmpty()) {
            serviceInfo.setAttribute("code", code);
        }

        if (selectedDisplayName != null && !selectedDisplayName.trim().isEmpty()) {
            serviceInfo.setAttribute("title", truncateAttributeValue(selectedDisplayName.trim()));
        }

        if (selectedMimeType != null && !selectedMimeType.trim().isEmpty()) {
            serviceInfo.setAttribute("mime", truncateAttributeValue(selectedMimeType.trim()));
        }
        serviceInfo.setAttribute("mode", relayMode);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                synchronized (lock) {
                    registeredServiceName = null;
                    lastError = "Marucast couldn't advertise on local Wi-Fi.";
                }
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}

            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                synchronized (lock) {
                    registeredServiceName = serviceInfo == null
                        ? null
                        : serviceInfo.getServiceName();
                }
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                synchronized (lock) {
                    registeredServiceName = null;
                }
            }
        };

        try {
            nsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            );
        } catch (Exception error) {
            registrationListener = null;
            lastError = "Marucast couldn't advertise on local Wi-Fi.";
        }
    }

    private void runPlaybackCaptureLoop() {
        byte[] buffer = new byte[16 * 1024];

        while (true) {
            final AudioRecord activeRecorder;
            synchronized (lock) {
                activeRecorder = captureAudioRecord;
            }

            if (activeRecorder == null) {
                break;
            }

            int read;
            try {
                read = activeRecorder.read(buffer, 0, buffer.length);
            } catch (Exception error) {
                synchronized (lock) {
                    lastError = "Marucast lost access to the phone's playback stream.";
                }
                break;
            }

            if (read <= 0) {
                continue;
            }

            String activeVocalMode = prepareLiveVocalProcessingState();
            if (VOCAL_MODE_REMOVE.equals(activeVocalMode)) {
                if (routeStemSeparatedOutput(buffer, read)) {
                    continue;
                }

                synchronized (lock) {
                    if (VOCAL_MODE_REMOVE.equals(vocalMode)) {
                        vocalMode = VOCAL_MODE_NORMAL;
                        markVocalProcessingStateChangedLocked();
                        String reducerError =
                            stemVocalReducer == null
                                ? null
                                : stemVocalReducer.getLastPrepareErrorMessage();
                        lastError =
                            reducerError == null || reducerError.trim().isEmpty()
                                ? "Marucast switched back to the normal mix because the local karaoke model was unavailable."
                                : reducerError;
                    }
                }
            }
            broadcastLivePcm(buffer, read);
        }
    }

    private void runMicrophoneCaptureLoop() {
        byte[] buffer = new byte[8 * 1024];

        while (true) {
            final AudioRecord activeRecorder;
            synchronized (lock) {
                activeRecorder = microphoneAudioRecord;
            }

            if (activeRecorder == null) {
                break;
            }

            int read;
            try {
                read = activeRecorder.read(buffer, 0, buffer.length);
            } catch (Exception error) {
                synchronized (lock) {
                    micLastError = "Marucast lost access to the phone microphone.";
                }
                break;
            }

            if (read <= 0) {
                if (read < 0) {
                    synchronized (lock) {
                        if (microphoneAudioRecord == activeRecorder) {
                            micLastError = getMicrophoneReadErrorMessage(read);
                            stopMicrophoneCaptureLocked();
                        }
                    }
                    break;
                }
                continue;
            }

            synchronized (lock) {
                micLevel = smoothMicLevel(
                    micLevel,
                    computeNormalizedMicPeak(buffer, read)
                );
            }
            broadcastLiveMicPcm(buffer, read);
        }
    }

    private void runServerLoop() {
        final ServerSocket activeServer;

        synchronized (lock) {
            activeServer = serverSocket;
        }

        if (activeServer == null) {
            return;
        }

        while (!activeServer.isClosed()) {
            try {
                final Socket socket = activeServer.accept();
                Thread clientThread = new Thread(
                    () -> handleClient(socket),
                    "MarucastSenderClient"
                );
                clientThread.setDaemon(true);
                clientThread.start();
            } catch (SocketException error) {
                break;
            } catch (IOException error) {
                synchronized (lock) {
                    lastError = "Marucast sender hit a local network error.";
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try (Socket activeSocket = socket) {
            activeSocket.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(activeSocket.getInputStream(), StandardCharsets.ISO_8859_1)
            );
            OutputStream output = activeSocket.getOutputStream();

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) {
                writeSimpleResponse(output, 400, "text/plain; charset=utf-8", "Bad Request");
                return;
            }

            String[] requestParts = requestLine.split(" ");
            String method = requestParts.length > 0 ? requestParts[0].trim().toUpperCase(Locale.US) : "";
            String requestTarget = requestParts.length > 1 ? requestParts[1].trim() : "/";
            String requestPath = requestTarget.split("\\?", 2)[0];
            Map<String, String> headers = new LinkedHashMap<>();

            String headerLine;
            while ((headerLine = reader.readLine()) != null) {
                if (headerLine.isEmpty()) {
                    break;
                }

                int separatorIndex = headerLine.indexOf(':');
                if (separatorIndex <= 0) {
                    continue;
                }

                String headerName = headerLine.substring(0, separatorIndex).trim().toLowerCase(Locale.US);
                String headerValue = headerLine.substring(separatorIndex + 1).trim();
                headers.put(headerName, headerValue);
            }

            if ("OPTIONS".equals(method)) {
                writeHeaders(output, 204, "No Content", null, 0L, false, null, true);
                return;
            }

            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                writeSimpleResponse(output, 405, "text/plain; charset=utf-8", "Method Not Allowed");
                return;
            }

            if ("/status".equals(requestPath)) {
                byte[] payload = getStatusJson().getBytes(StandardCharsets.UTF_8);
                writeHeaders(
                    output,
                    200,
                    "OK",
                    "application/json; charset=utf-8",
                    payload.length,
                    false,
                    null,
                    true
                );
                if ("GET".equals(method)) {
                    output.write(payload);
                }
                return;
            }

            if (ARTWORK_PATH.equals(requestPath)) {
                streamArtwork(output, method);
                return;
            }

            if ("/control".equals(requestPath)) {
                handleControl(output, requestTarget);
                return;
            }

            if (LIVE_PCM_PATH.equals(requestPath)) {
                streamLivePlayback(output, method, activeSocket);
                return;
            }

            if (LIVE_MIC_PCM_PATH.equals(requestPath)) {
                streamLiveMicrophone(output, method, activeSocket);
                return;
            }

            if (!"/stream".equals(requestPath)) {
                writeSimpleResponse(output, 404, "text/plain; charset=utf-8", "Not Found");
                return;
            }

            streamSelectedAudio(output, method, headers);
        } catch (IOException ignored) {
            // Ignore per-client disconnects.
        }
    }

    private void streamSelectedAudio(
        OutputStream output,
        String method,
        Map<String, String> headers
    ) throws IOException {
        final Uri audioUri;
        final Context context;
        final String mimeType;
        final long declaredSize;

        synchronized (lock) {
            audioUri = selectedAudioUri;
            context = appContext;
            mimeType = selectedMimeType == null || selectedMimeType.trim().isEmpty()
                ? "application/octet-stream"
                : selectedMimeType;
            declaredSize = selectedSize;
        }

        if (audioUri == null || context == null) {
            writeSimpleResponse(output, 404, "text/plain; charset=utf-8", "No audio selected");
            return;
        }

        ContentResolver resolver = context.getContentResolver();
        AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(audioUri, "r");
        if (descriptor == null) {
            writeSimpleResponse(output, 404, "text/plain; charset=utf-8", "Audio unavailable");
            return;
        }

        try (AssetFileDescriptor activeDescriptor = descriptor;
             InputStream input = activeDescriptor.createInputStream()) {
            long totalLength = declaredSize >= 0 ? declaredSize : activeDescriptor.getLength();
            RangeRequest rangeRequest = parseRangeRequest(headers.get("range"), totalLength);

            if (rangeRequest == null && headers.containsKey("range") && totalLength > 0) {
                writeHeaders(
                    output,
                    416,
                    "Range Not Satisfiable",
                    "text/plain; charset=utf-8",
                    0L,
                    false,
                    "bytes */" + totalLength,
                    false
                );
                return;
            }

            long start = rangeRequest == null ? 0L : rangeRequest.start;
            long end = rangeRequest == null
                ? (totalLength > 0 ? totalLength - 1 : -1L)
                : rangeRequest.end;
            long contentLength = totalLength > 0
                ? Math.max(0L, end - start + 1L)
                : -1L;

            skipFully(input, start);

            writeHeaders(
                output,
                rangeRequest == null ? 200 : 206,
                rangeRequest == null ? "OK" : "Partial Content",
                mimeType,
                contentLength,
                true,
                rangeRequest == null || totalLength <= 0
                    ? null
                    : "bytes " + start + "-" + end + "/" + totalLength,
                true
            );

            if ("HEAD".equals(method)) {
                return;
            }

            copyRange(input, output, contentLength);
        }
    }

    private void streamLivePlayback(
        OutputStream output,
        String method,
        Socket socket
    ) throws IOException {
        synchronized (lock) {
            if (!RELAY_MODE_CAPTURE.equals(relayMode) || captureAudioRecord == null) {
                writeSimpleResponse(
                    output,
                    409,
                    "text/plain; charset=utf-8",
                    "Playback relay is not active"
                );
                return;
            }
        }

        writeLivePcmHeaders(output, RELAY_MODE_CAPTURE, LIVE_SAMPLE_RATE, LIVE_CHANNEL_COUNT);
        if ("HEAD".equals(method)) {
            return;
        }

        LivePcmClient client = new LivePcmClient(
            socket,
            output,
            socket.getInetAddress() != null && socket.getInetAddress().isLoopbackAddress()
        );
        addLivePcmClient(client);

        try {
            synchronized (client.monitor) {
                while (!socket.isClosed()) {
                    try {
                        client.monitor.wait(1000L);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            removeLivePcmClient(client);
        }
    }

    private void streamLiveMicrophone(
        OutputStream output,
        String method,
        Socket socket
    ) throws IOException {
        final int activeMicSampleRate;
        synchronized (lock) {
            if (microphoneAudioRecord == null) {
                writeSimpleResponse(
                    output,
                    409,
                    "text/plain; charset=utf-8",
                    "Phone microphone relay is not active"
                );
                return;
            }
            activeMicSampleRate = liveMicSampleRate;
        }

        writeLivePcmHeaders(
            output,
            "phone-microphone",
            activeMicSampleRate,
            LIVE_MIC_CHANNEL_COUNT
        );
        if ("HEAD".equals(method)) {
            return;
        }

        LivePcmClient client = new LivePcmClient(
            socket,
            output,
            socket.getInetAddress() != null && socket.getInetAddress().isLoopbackAddress()
        );
        addLiveMicClient(client);

        try {
            synchronized (client.monitor) {
                while (!socket.isClosed()) {
                    try {
                        client.monitor.wait(1000L);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            removeLiveMicClient(client);
        }
    }

    private void handleControl(OutputStream output, String requestTarget) throws IOException {
        Uri requestUri = Uri.parse("http://localhost" + requestTarget);
        JSONObject payload;
        synchronized (lock) {
            payload = dispatchControlCommandLocked(requestUri.getQueryParameter("command"));
        }
        byte[] responseBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        writeHeaders(
            output,
            payload.optBoolean("success", false) ? 200 : 409,
            payload.optBoolean("success", false) ? "OK" : "Conflict",
            "application/json; charset=utf-8",
            responseBytes.length,
            false,
            null,
            true
        );
        output.write(responseBytes);
    }

    private JSONObject dispatchControlCommandLocked(String rawCommand) {
        String normalizedCommand = normalizeControlCommand(rawCommand);
        boolean success = false;
        String message = "Marucast could not reach the requested live control.";

        if (normalizedCommand != null) {
            switch (normalizedCommand) {
                case "previous":
                case "next":
                case "pause":
                case "play":
                    success = HelperMediaSessionMonitor.getInstance().dispatchTransportCommand(
                        normalizedCommand
                    );
                    message = success
                        ? "Transport command sent."
                        : "Marucast could not reach the current media controls.";
                    break;
                case "vocal-remove":
                    if (!BuildConfig.MARUCAST_KARAOKE_ENABLED) {
                        success = false;
                        message = "This helper build doesn't include Karaoke.";
                        break;
                    }
                    if (stemVocalReducer == null || !stemVocalReducer.isModelReady()) {
                        String reducerError =
                            stemVocalReducer == null
                                ? null
                                : stemVocalReducer.getLastPrepareErrorMessage();
                        success = false;
                        message =
                            reducerError == null || reducerError.trim().isEmpty()
                                ? "This helper couldn't start Karaoke because the local stem model isn't ready yet."
                                : reducerError;
                        lastError = message;
                        break;
                    }
                    if (!VOCAL_MODE_REMOVE.equals(vocalMode)) {
                        vocalMode = VOCAL_MODE_REMOVE;
                        markVocalProcessingStateChangedLocked();
                    }
                    lastError = null;
                    success = true;
                    int karaokeDelayMs =
                        stemVocalReducer == null ? 0 : stemVocalReducer.getEstimatedOutputDelayMs();
                    int karaokeDelaySeconds = Math.max(1, Math.round(karaokeDelayMs / 1000.0f));
                    message =
                        "Karaoke mode is warming up. Expect about " +
                            karaokeDelaySeconds +
                            " seconds of delay while the helper pre-buffers a cleaner accompaniment mix.";
                    break;
                case "vocal-normal":
                    if (!BuildConfig.MARUCAST_KARAOKE_ENABLED) {
                        if (!VOCAL_MODE_NORMAL.equals(vocalMode)) {
                            vocalMode = VOCAL_MODE_NORMAL;
                            markVocalProcessingStateChangedLocked();
                        }
                        lastError = null;
                        success = true;
                        message = "This helper build uses the normal mix only.";
                        break;
                    }
                    if (!VOCAL_MODE_NORMAL.equals(vocalMode)) {
                        vocalMode = VOCAL_MODE_NORMAL;
                        markVocalProcessingStateChangedLocked();
                    }
                    lastError = null;
                    success = true;
                    message = "Normal mix restored.";
                    break;
                case "mic-start":
                    try {
                        startMicrophoneCaptureLocked();
                        success = true;
                        micLastError = null;
                        message =
                            "Phone mic lane is live. It uses its own lower-latency relay so karaoke delay stays on the music path.";
                    } catch (SecurityException error) {
                        success = false;
                        micLastError = error.getMessage();
                        message =
                            error.getMessage() == null || error.getMessage().trim().isEmpty()
                                ? "Open Maru Helper on the phone and allow microphone access first."
                                : error.getMessage();
                    } catch (Exception error) {
                        success = false;
                        micLastError =
                            "Marucast couldn't start the phone mic lane right now.";
                        message = micLastError;
                    }
                    break;
                case "mic-stop":
                    stopMicrophoneCaptureLocked();
                    micLastError = null;
                    success = true;
                    message = "Phone mic lane stopped.";
                    break;
                case "delay-down":
                    relayDelayMs = Math.max(
                        -RELAY_DELAY_LIMIT_MS,
                        relayDelayMs - RELAY_DELAY_STEP_MS
                    );
                    success = true;
                    message =
                        relayDelayMs == 0
                            ? "Global lyrics delay reset."
                            : "Global lyrics delay set to " + relayDelayMs + " ms.";
                    break;
                case "delay-up":
                    relayDelayMs = Math.min(
                        RELAY_DELAY_LIMIT_MS,
                        relayDelayMs + RELAY_DELAY_STEP_MS
                    );
                    success = true;
                    message =
                        relayDelayMs == 0
                            ? "Global lyrics delay reset."
                            : "Global lyrics delay set to " + relayDelayMs + " ms.";
                    break;
                case "delay-reset":
                    relayDelayMs = 0;
                    success = true;
                    message = "Global lyrics delay reset.";
                    break;
                case "mic-gain-down":
                    micMixGain = clampMicMixGain(micMixGain - MIC_MIX_GAIN_STEP);
                    success = true;
                    message =
                        "Phone mic boost is now " +
                            Math.round(micMixGain * 100.0d) +
                            "%.";
                    break;
                case "mic-gain-up":
                    micMixGain = clampMicMixGain(micMixGain + MIC_MIX_GAIN_STEP);
                    success = true;
                    message =
                        "Phone mic boost is now " +
                            Math.round(micMixGain * 100.0d) +
                            "%.";
                    break;
                case "music-bed-down":
                    micMusicBedLevel = clampMicMusicBedLevel(
                        micMusicBedLevel - MIC_MUSIC_BED_STEP
                    );
                    success = true;
                    message =
                        "Music under the mic is now " +
                            Math.round(micMusicBedLevel * 100.0d) +
                            "%.";
                    break;
                case "music-bed-up":
                    micMusicBedLevel = clampMicMusicBedLevel(
                        micMusicBedLevel + MIC_MUSIC_BED_STEP
                    );
                    success = true;
                    message =
                        "Music under the mic is now " +
                            Math.round(micMusicBedLevel * 100.0d) +
                            "%.";
                    break;
                default:
                    break;
            }
        }

        JSONObject payload = buildStatusJsonLocked();
        putJson(payload, "success", success);
        putJson(payload, "command", normalizedCommand == null ? JSONObject.NULL : normalizedCommand);
        putJson(payload, "message", message);
        return payload;
    }

    private void streamArtwork(OutputStream output, String method) throws IOException {
        byte[] artworkBytes = HelperMediaSessionMonitor.getInstance().getArtworkJpegBytes();
        if (artworkBytes == null || artworkBytes.length == 0) {
            byte[] payload = "Artwork unavailable".getBytes(StandardCharsets.UTF_8);
            writeHeaders(
                output,
                404,
                "Not Found",
                "text/plain; charset=utf-8",
                payload.length,
                false,
                null,
                true
            );
            if ("GET".equals(method)) {
                output.write(payload);
            }
            return;
        }

        writeHeaders(
            output,
            200,
            "OK",
            "image/jpeg",
            artworkBytes.length,
            false,
            null,
            true
        );
        if ("GET".equals(method)) {
            output.write(artworkBytes);
        }
    }

    private void writeSimpleResponse(
        OutputStream output,
        int statusCode,
        String contentType,
        String body
    ) throws IOException {
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        writeHeaders(
            output,
            statusCode,
            getReasonPhrase(statusCode),
            contentType,
            payload.length,
            false,
            null,
            false
        );
        if (payload.length > 0) {
            output.write(payload);
        }
    }

    private void writeHeaders(
        OutputStream output,
        int statusCode,
        String reasonPhrase,
        String contentType,
        long contentLength,
        boolean acceptRanges,
        String contentRange,
        boolean allowCors
    ) throws IOException {
        writeAsciiLine(output, "HTTP/1.1 " + statusCode + " " + reasonPhrase);
        writeAsciiLine(output, "Connection: close");
        writeAsciiLine(output, "Cache-Control: no-store");
        if (contentType != null && !contentType.isEmpty()) {
            writeAsciiLine(output, "Content-Type: " + contentType);
        }
        if (contentLength >= 0) {
            writeAsciiLine(output, "Content-Length: " + contentLength);
        }
        if (acceptRanges) {
            writeAsciiLine(output, "Accept-Ranges: bytes");
        }
        if (contentRange != null && !contentRange.isEmpty()) {
            writeAsciiLine(output, "Content-Range: " + contentRange);
        }
        if (allowCors) {
            writeAsciiLine(output, "Access-Control-Allow-Origin: *");
            writeAsciiLine(output, "Access-Control-Allow-Methods: GET, HEAD, OPTIONS");
            writeAsciiLine(output, "Access-Control-Allow-Headers: *");
            writeAsciiLine(output, "Access-Control-Allow-Private-Network: true");
        }
        writeAsciiLine(output, "");
    }

    private void writeLivePcmHeaders(
        OutputStream output,
        String relayMode,
        int sampleRate,
        int channelCount
    ) throws IOException {
        writeAsciiLine(output, "HTTP/1.1 200 OK");
        writeAsciiLine(output, "Connection: close");
        writeAsciiLine(output, "Cache-Control: no-store");
        writeAsciiLine(output, "Content-Type: " + LIVE_PCM_MIME_TYPE);
        writeAsciiLine(output, "X-Maru-Relay-Mode: " + relayMode);
        writeAsciiLine(output, "X-Maru-Sample-Rate: " + sampleRate);
        writeAsciiLine(output, "X-Maru-Channel-Count: " + channelCount);
        writeAsciiLine(output, "X-Maru-Pcm-Encoding: s16le");
        writeAsciiLine(output, "Access-Control-Allow-Origin: *");
        writeAsciiLine(output, "Access-Control-Allow-Methods: GET, HEAD, OPTIONS");
        writeAsciiLine(output, "Access-Control-Allow-Headers: *");
        writeAsciiLine(output, "Access-Control-Allow-Private-Network: true");
        writeAsciiLine(output, "");
    }

    private void writeAsciiLine(OutputStream output, String value) throws IOException {
        output.write((value + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    private RangeRequest parseRangeRequest(String headerValue, long totalLength) {
        if (headerValue == null || totalLength <= 0) {
            return null;
        }

        String normalized = headerValue.trim().toLowerCase(Locale.US);
        if (!normalized.startsWith("bytes=")) {
            return null;
        }

        String spec = normalized.substring(6).split(",", 2)[0].trim();
        int dashIndex = spec.indexOf('-');
        if (dashIndex < 0) {
            return null;
        }

        String rawStart = spec.substring(0, dashIndex).trim();
        String rawEnd = spec.substring(dashIndex + 1).trim();

        try {
            if (rawStart.isEmpty()) {
                long suffixLength = Long.parseLong(rawEnd);
                if (suffixLength <= 0) {
                    return null;
                }

                long start = Math.max(0L, totalLength - suffixLength);
                return new RangeRequest(start, totalLength - 1L);
            }

            long start = Long.parseLong(rawStart);
            long end = rawEnd.isEmpty() ? totalLength - 1L : Long.parseLong(rawEnd);
            if (start < 0 || start >= totalLength || end < start) {
                return null;
            }

            return new RangeRequest(start, Math.min(end, totalLength - 1L));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private void skipFully(InputStream input, long amount) throws IOException {
        long remaining = amount;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }

            int nextByte = input.read();
            if (nextByte == -1) {
                break;
            }
            remaining -= 1L;
        }
    }

    private void copyRange(InputStream input, OutputStream output, long maxBytes) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long remaining = maxBytes;

        while (remaining != 0) {
            int requested = remaining > 0 && remaining < buffer.length
                ? (int) remaining
                : buffer.length;
            int read = input.read(buffer, 0, requested);
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
            if (remaining > 0) {
                remaining -= read;
            }
        }
    }

    private void addLivePcmClient(LivePcmClient client) {
        synchronized (lock) {
            relayIdleSinceMs = 0L;
            livePcmClients.add(client);
        }
    }

    private void removeLivePcmClient(LivePcmClient client) {
        synchronized (lock) {
            livePcmClients.remove(client);
        }

        try {
            client.socket.close();
        } catch (IOException ignored) {}

        synchronized (client.monitor) {
            client.monitor.notifyAll();
        }
    }

    private void addLiveMicClient(LivePcmClient client) {
        synchronized (lock) {
            micRelayIdleSinceMs = 0L;
            liveMicClients.add(client);
        }
    }

    private void removeLiveMicClient(LivePcmClient client) {
        synchronized (lock) {
            liveMicClients.remove(client);
        }

        try {
            client.socket.close();
        } catch (IOException ignored) {}

        synchronized (client.monitor) {
            client.monitor.notifyAll();
        }
    }

    private void broadcastLivePcm(byte[] buffer, int length) {
        List<LivePcmClient> clients;
        synchronized (lock) {
            if (livePcmClients.isEmpty()) {
                handleRelayIdleWithoutClientsLocked();
                return;
            }
            relayIdleSinceMs = 0L;
            clients = new ArrayList<>(livePcmClients);
        }

        for (LivePcmClient client : clients) {
            try {
                client.output.write(buffer, 0, length);
                client.output.flush();
            } catch (IOException error) {
                removeLivePcmClient(client);
            }
        }
    }

    private void broadcastLiveMicPcm(byte[] buffer, int length) {
        List<LivePcmClient> clients;
        synchronized (lock) {
            if (liveMicClients.isEmpty()) {
                handleMicRelayIdleWithoutClientsLocked();
                return;
            }
            micRelayIdleSinceMs = 0L;
            clients = new ArrayList<>(liveMicClients);
        }

        for (LivePcmClient client : clients) {
            try {
                client.output.write(buffer, 0, length);
                client.output.flush();
            } catch (IOException error) {
                removeLiveMicClient(client);
            }
        }
    }

    private String prepareLiveVocalProcessingState() {
        final String activeVocalMode;
        final int activeProcessingStateVersion;
        final HelperStemVocalReducer activeStemVocalReducer;
        synchronized (lock) {
            activeVocalMode = vocalMode;
            activeProcessingStateVersion = vocalProcessingStateVersion;
            activeStemVocalReducer = stemVocalReducer;
        }

        if (
            activeProcessingStateVersion != appliedVocalProcessingStateVersion ||
            !activeVocalMode.equals(appliedVocalMode)
        ) {
            resetVocalProcessingState();
            if (activeStemVocalReducer != null) {
                activeStemVocalReducer.reset();
            }
            appliedVocalProcessingStateVersion = activeProcessingStateVersion;
            appliedVocalMode = activeVocalMode;
        }

        return activeVocalMode;
    }

    private boolean routeStemSeparatedOutput(byte[] buffer, int length) {
        final HelperStemVocalReducer activeStemVocalReducer;
        synchronized (lock) {
            activeStemVocalReducer = stemVocalReducer;
        }

        if (activeStemVocalReducer == null || !activeStemVocalReducer.isModelReady()) {
            return false;
        }
        if (!activeStemVocalReducer.enqueuePcm16(buffer, length)) {
            return false;
        }

        byte[] processedChunk = activeStemVocalReducer.dequeueProcessedPcm16(length);
        if (processedChunk != null && processedChunk.length > 0) {
            broadcastLivePcm(processedChunk, processedChunk.length);
        }
        return true;
    }

    private void applyLiveVocalMode(byte[] buffer, int length) {
        int frameSizeBytes = LIVE_CHANNEL_COUNT * PCM_BYTES_PER_SAMPLE;
        if (buffer == null || length < frameSizeBytes) {
            return;
        }

        int alignedLength = length - (length % frameSizeBytes);
        for (int offset = 0; offset < alignedLength; offset += frameSizeBytes) {
            double leftSample =
                readLittleEndianPcm16(buffer, offset) / PCM_16_NORMALIZED_SCALE;
            double rightSample =
                readLittleEndianPcm16(buffer, offset + PCM_BYTES_PER_SAMPLE) /
                    PCM_16_NORMALIZED_SCALE;
            double mid = (leftSample + rightSample) * 0.5d;
            double side = (leftSample - rightSample) * 0.5d;
            double vocalBand = extractVocalBandSample(mid);
            double bandEnergy = Math.abs(vocalBand);
            double envelopeBlend = bandEnergy > vocalBandEnvelope ? 0.24d : 0.012d;
            vocalBandEnvelope += (bandEnergy - vocalBandEnvelope) * envelopeBlend;
            double centeredness =
                clampUnit(
                    Math.abs(mid) /
                        (Math.abs(mid) + Math.abs(side) + 1.0e-6d)
                );
            double centerWeight = clampUnit((centeredness - 0.52d) / 0.34d);
            double bandWeight = clampUnit((vocalBandEnvelope - 0.006d) / 0.11d);
            double suppression =
                centerWeight * (0.18d + (0.82d * bandWeight));
            double bandReduction = 0.28d + (0.68d * suppression);
            double midReduction = 0.06d + (0.74d * suppression);
            double processedMid =
                (mid - vocalBand) + (vocalBand * (1.0d - bandReduction));
            processedMid *= 1.0d - midReduction;
            double processedSide = side;
            double processedLeft = softClip(processedMid + processedSide) * 0.92d;
            double processedRight = softClip(processedMid - processedSide) * 0.92d;
            writeLittleEndianPcm16(buffer, offset, clampNormalizedPcm16(processedLeft));
            writeLittleEndianPcm16(
                buffer,
                offset + PCM_BYTES_PER_SAMPLE,
                clampNormalizedPcm16(processedRight)
            );
        }
    }

    private void handleRelayIdleWithoutClientsLocked() {
        if (!RELAY_MODE_CAPTURE.equals(relayMode) || captureAudioRecord == null) {
            relayIdleSinceMs = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (relayIdleSinceMs <= 0L) {
            relayIdleSinceMs = now;
            return;
        }

        if (now - relayIdleSinceMs < AUTO_STOP_IDLE_MS) {
            return;
        }

        lastError = "Marucast stopped because no receiver stayed connected for 2 minutes.";
        stopPlaybackCaptureLocked();
    }

    private void handleMicRelayIdleWithoutClientsLocked() {
        if (microphoneAudioRecord == null) {
            micRelayIdleSinceMs = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (micRelayIdleSinceMs <= 0L) {
            micRelayIdleSinceMs = now;
            return;
        }

        if (now - micRelayIdleSinceMs < MIC_AUTO_STOP_IDLE_MS) {
            return;
        }

        micLastError =
            "Phone mic lane stopped because no receiver kept using it for 45 seconds.";
        stopMicrophoneCaptureLocked();
    }

    private int getActiveLoopbackLiveClientCountLocked() {
        int count = 0;
        for (LivePcmClient client : livePcmClients) {
            if (client.loopback) {
                count += 1;
            }
        }
        return count;
    }

    private int getActiveNetworkLiveClientCountLocked() {
        int count = 0;
        for (LivePcmClient client : livePcmClients) {
            if (!client.loopback) {
                count += 1;
            }
        }
        return count;
    }

    private int getActiveLoopbackMicClientCountLocked() {
        int count = 0;
        for (LivePcmClient client : liveMicClients) {
            if (client.loopback) {
                count += 1;
            }
        }
        return count;
    }

    private int getActiveNetworkMicClientCountLocked() {
        int count = 0;
        for (LivePcmClient client : liveMicClients) {
            if (!client.loopback) {
                count += 1;
            }
        }
        return count;
    }

    private boolean isMicrophoneCaptureRunningLocked() {
        if (
            microphoneAudioRecord == null ||
                microphoneCaptureThread == null ||
                !microphoneCaptureThread.isAlive()
        ) {
            return false;
        }

        try {
            return microphoneAudioRecord.getRecordingState() ==
                AudioRecord.RECORDSTATE_RECORDING;
        } catch (Exception ignored) {
            return false;
        }
    }

    private JSONObject buildStatusJsonLocked() {
        JSONObject payload = new JSONObject();
        boolean wifiConnected = enforceWifiRequirementLocked();
        String wifiRequirementMessage = wifiConnected ? null : getWifiRequirementMessageLocked();
        boolean karaokeEnabled = BuildConfig.MARUCAST_KARAOKE_ENABLED;
        Integer karaokeDelayMs =
            karaokeEnabled && stemVocalReducer != null
                ? stemVocalReducer.getEstimatedOutputDelayMs()
                : null;
        boolean stemModelReady =
            karaokeEnabled && stemVocalReducer != null && stemVocalReducer.isModelReady();
        String localIp = wifiConnected ? getLocalIpAddressLocked() : null;
        String fileStreamUrl = buildFileStreamUrlLocked(localIp);
        String liveStreamUrl = buildLiveStreamUrlLocked(localIp);
        String liveMicStreamUrl = buildLiveMicStreamUrlLocked(localIp);
        String loopbackFileStreamUrl = buildLoopbackFileStreamUrlLocked();
        String loopbackLiveStreamUrl = buildLoopbackLiveStreamUrlLocked();
        String loopbackLiveMicStreamUrl = buildLoopbackLiveMicStreamUrlLocked();
        JSONObject mediaStatus = HelperMediaSessionMonitor.getInstance().getStatusJson();
        String artworkCacheKey = readJsonString(mediaStatus, "mediaArtworkKey");
        String artworkUrl = buildArtworkUrlLocked(localIp, artworkCacheKey);
        String loopbackArtworkUrl = buildLoopbackArtworkUrlLocked(artworkCacheKey);
        String mediaTitle = readJsonString(mediaStatus, "mediaTitle");
        String mediaArtist = readJsonString(mediaStatus, "mediaArtist");
        String mediaAppLabel = readJsonString(mediaStatus, "mediaAppLabel");
        Long mediaDurationMs = readJsonLong(mediaStatus, "mediaDurationMs");
        Long mediaPositionMs = readJsonLong(mediaStatus, "mediaPositionMs");
        Long mediaPositionCapturedAtMs = readJsonLong(mediaStatus, "mediaPositionCapturedAtMs");
        Double mediaPlaybackSpeed = readJsonDouble(mediaStatus, "mediaPlaybackSpeed");
        boolean microphoneCaptureRunning = wifiConnected && isMicrophoneCaptureRunningLocked();
        putJson(payload, "available", true);
        putJson(payload, "running", wifiConnected && isRunningLocked());
        putJson(payload, "wifiConnected", wifiConnected);
        putJson(
            payload,
            "sameWifiRequiredMessage",
            wifiRequirementMessage == null ? JSONObject.NULL : wifiRequirementMessage
        );
        putJson(payload, "relayMode", relayMode);
        putJson(
            payload,
            "captureActive",
            wifiConnected && RELAY_MODE_CAPTURE.equals(relayMode) && captureAudioRecord != null
        );
        putJson(payload, "pairingCode", ensurePairingCodeLocked());
        putJson(payload, "serviceName", registeredServiceName == null ? JSONObject.NULL : registeredServiceName);
        putJson(payload, "deviceName", getDeviceNameLocked());
        putJson(payload, "localIp", localIp == null ? JSONObject.NULL : localIp);
        putJson(payload, "port", listeningPort > 0 ? listeningPort : JSONObject.NULL);
        putJson(payload, "selectedTitle", buildVisibleTitleLocked(mediaTitle));
        putJson(payload, "selectedMimeType", selectedMimeType == null ? JSONObject.NULL : selectedMimeType);
        putJson(payload, "selectedSize", selectedSize >= 0 ? selectedSize : JSONObject.NULL);
        putJson(payload, "sampleRate", LIVE_SAMPLE_RATE);
        putJson(payload, "channelCount", LIVE_CHANNEL_COUNT);
        putJson(payload, "relayDelayMs", relayDelayMs);
        putJson(payload, "karaokeEnabled", karaokeEnabled);
        putJson(payload, "karaokeDelayMs", karaokeDelayMs == null ? JSONObject.NULL : karaokeDelayMs);
        putJson(payload, "vocalMode", vocalMode);
        putJson(payload, "vocalStemModelReady", stemModelReady);
        putJson(
            payload,
            "vocalProcessingKind",
            VOCAL_MODE_REMOVE.equals(vocalMode) && stemModelReady
                ? "stem-model"
                : "normal"
        );
        putJson(payload, "activeLoopbackRelayClients", getActiveLoopbackLiveClientCountLocked());
        putJson(payload, "activeNetworkRelayClients", getActiveNetworkLiveClientCountLocked());
        putJson(payload, "activeLoopbackMicClients", getActiveLoopbackMicClientCountLocked());
        putJson(payload, "activeNetworkMicClients", getActiveNetworkMicClientCountLocked());
        putJson(payload, "mediaStatus", mediaStatus);
        putJson(payload, "mediaTitle", mediaTitle == null ? JSONObject.NULL : mediaTitle);
        putJson(payload, "mediaArtist", mediaArtist == null ? JSONObject.NULL : mediaArtist);
        putJson(payload, "mediaAppLabel", mediaAppLabel == null ? JSONObject.NULL : mediaAppLabel);
        putJson(payload, "mediaDurationMs", mediaDurationMs == null ? JSONObject.NULL : mediaDurationMs);
        putJson(payload, "mediaPlaybackSpeed", mediaPlaybackSpeed == null ? JSONObject.NULL : mediaPlaybackSpeed);
        putJson(
            payload,
            "mediaPositionCapturedAtMs",
            mediaPositionCapturedAtMs == null ? JSONObject.NULL : mediaPositionCapturedAtMs
        );
        putJson(payload, "mediaPositionMs", mediaPositionMs == null ? JSONObject.NULL : mediaPositionMs);
        putJson(payload, "artworkUrl", artworkUrl == null ? JSONObject.NULL : artworkUrl);
        putJson(
            payload,
            "loopbackArtworkUrl",
            loopbackArtworkUrl == null ? JSONObject.NULL : loopbackArtworkUrl
        );
        putJson(payload, "mediaPlaying", mediaStatus.optBoolean("mediaPlaying", false));
        putJson(payload, "mediaAccessEnabled", mediaStatus.optBoolean("mediaAccessEnabled", false));
        putJson(
            payload,
            "micAvailable",
            wifiConnected && isRunningLocked() && !RELAY_MODE_SYNC_CLOCK.equals(relayMode)
        );
        putJson(payload, "micCaptureActive", microphoneCaptureRunning);
        putJson(
            payload,
            "micLevel",
            microphoneCaptureRunning ? micLevel : 0.0d
        );
        putJson(
            payload,
            "micSampleRate",
            microphoneCaptureRunning ? liveMicSampleRate : JSONObject.NULL
        );
        putJson(
            payload,
            "micChannelCount",
            microphoneCaptureRunning ? LIVE_MIC_CHANNEL_COUNT : JSONObject.NULL
        );
        putJson(payload, "micStreamUrl", liveMicStreamUrl == null ? JSONObject.NULL : liveMicStreamUrl);
        putJson(payload, "micMixGain", micMixGain);
        putJson(payload, "micMusicBedLevel", micMusicBedLevel);
        putJson(payload, "micEchoCancelerActive", microphoneEchoCancelerActive);
        putJson(payload, "micNoiseSuppressorActive", microphoneNoiseSuppressorActive);
        putJson(payload, "micAutoGainActive", microphoneAutomaticGainControlActive);
        putJson(
            payload,
            "loopbackMicStreamUrl",
            loopbackLiveMicStreamUrl == null ? JSONObject.NULL : loopbackLiveMicStreamUrl
        );
        putJson(
            payload,
            "micLastError",
            micLastError == null || micLastError.trim().isEmpty() ? JSONObject.NULL : micLastError
        );
        putJson(payload, "transportAvailable", wifiConnected);
        putJson(
            payload,
            "lastError",
            lastError == null || lastError.trim().isEmpty()
                ? (wifiRequirementMessage == null ? JSONObject.NULL : wifiRequirementMessage)
                : lastError
        );
        putJson(payload, "syncClockOnly", RELAY_MODE_SYNC_CLOCK.equals(relayMode));
        putJson(payload, "streamUrl", fileStreamUrl == null ? JSONObject.NULL : fileStreamUrl);
        putJson(payload, "liveStreamUrl", liveStreamUrl == null ? JSONObject.NULL : liveStreamUrl);
        putJson(
            payload,
            "loopbackStreamUrl",
            loopbackFileStreamUrl == null ? JSONObject.NULL : loopbackFileStreamUrl
        );
        putJson(
            payload,
            "loopbackLiveStreamUrl",
            loopbackLiveStreamUrl == null ? JSONObject.NULL : loopbackLiveStreamUrl
        );

        JSONArray capabilities = new JSONArray();
        capabilities.put("local-file-stream");
        capabilities.put("nsd-advertising");
        capabilities.put("playback-capture-relay");
        if (karaokeEnabled) {
            capabilities.put("helper-vocal-processing");
        }
        if (stemModelReady) {
            capabilities.put("helper-vocal-stem-model");
        }
        capabilities.put("helper-phone-mic-relay");
        capabilities.put("lyrics-sync-clock");
        putJson(payload, "capabilities", capabilities);
        return payload;
    }

    private void markVocalProcessingStateChangedLocked() {
        if (vocalProcessingStateVersion == Integer.MAX_VALUE) {
            vocalProcessingStateVersion = 1;
            return;
        }
        vocalProcessingStateVersion += 1;
    }

    private void resetVocalProcessingState() {
        vocalBandHighPassState = 0.0d;
        vocalBandHighPassPreviousInput = 0.0d;
        vocalBandLowPassState = 0.0d;
        vocalBandEnvelope = 0.0d;
    }

    private double extractVocalBandSample(double sample) {
        vocalBandHighPassState =
            VOCAL_BAND_HIGHPASS_ALPHA *
                (vocalBandHighPassState + sample - vocalBandHighPassPreviousInput);
        vocalBandHighPassPreviousInput = sample;
        vocalBandLowPassState +=
            VOCAL_BAND_LOWPASS_ALPHA * (vocalBandHighPassState - vocalBandLowPassState);
        return vocalBandLowPassState;
    }

    private String getActiveRelayUrlLocked() {
        if (!isWifiConnectedLocked()) {
            return null;
        }
        String localIp = getLocalIpAddressLocked();
        if (RELAY_MODE_CAPTURE.equals(relayMode)) {
            return buildLiveStreamUrlLocked(localIp);
        }
        if (RELAY_MODE_SYNC_CLOCK.equals(relayMode)) {
            return null;
        }

        return buildFileStreamUrlLocked(localIp);
    }

    private String buildFileStreamUrlLocked(String localIp) {
        return RELAY_MODE_FILE.equals(relayMode) && localIp != null && listeningPort > 0
            ? "http://" + localIp + ":" + listeningPort + "/stream"
            : null;
    }

    private String buildLoopbackFileStreamUrlLocked() {
        return RELAY_MODE_FILE.equals(relayMode) && listeningPort > 0
            ? "http://127.0.0.1:" + listeningPort + "/stream"
            : null;
    }

    private String buildLiveStreamUrlLocked(String localIp) {
        return RELAY_MODE_CAPTURE.equals(relayMode) && localIp != null && listeningPort > 0
            ? "http://" + localIp + ":" + listeningPort + LIVE_PCM_PATH
            : null;
    }

    private String buildLiveMicStreamUrlLocked(String localIp) {
        return localIp != null && listeningPort > 0 && isMicrophoneCaptureRunningLocked()
            ? "http://" + localIp + ":" + listeningPort + LIVE_MIC_PCM_PATH
            : null;
    }

    private String buildLoopbackLiveStreamUrlLocked() {
        return RELAY_MODE_CAPTURE.equals(relayMode) && listeningPort > 0
            ? "http://127.0.0.1:" + listeningPort + LIVE_PCM_PATH
            : null;
    }

    private String buildLoopbackLiveMicStreamUrlLocked() {
        return listeningPort > 0 && isMicrophoneCaptureRunningLocked()
            ? "http://127.0.0.1:" + listeningPort + LIVE_MIC_PCM_PATH
            : null;
    }

    private String buildArtworkUrlLocked(String localIp, String artworkCacheKey) {
        if (localIp == null || listeningPort <= 0 || artworkCacheKey == null || artworkCacheKey.trim().isEmpty()) {
            return null;
        }

        return "http://" + localIp + ":" + listeningPort + ARTWORK_PATH + "?v=" + artworkCacheKey;
    }

    private String buildLoopbackArtworkUrlLocked(String artworkCacheKey) {
        if (listeningPort <= 0 || artworkCacheKey == null || artworkCacheKey.trim().isEmpty()) {
            return null;
        }

        return "http://127.0.0.1:" + listeningPort + ARTWORK_PATH + "?v=" + artworkCacheKey;
    }

    private String getDeviceNameLocked() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (manufacturer.isEmpty()) {
            return model.isEmpty() ? "Android device" : model;
        }
        if (model.isEmpty()) {
            return manufacturer;
        }
        if (model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US))) {
            return model;
        }
        return manufacturer + " " + model;
    }

    private Object buildVisibleTitleLocked(String mediaTitle) {
        String trimmedMediaTitle = mediaTitle == null ? null : mediaTitle.trim();
        if (
            (RELAY_MODE_CAPTURE.equals(relayMode) || RELAY_MODE_SYNC_CLOCK.equals(relayMode)) &&
            trimmedMediaTitle != null &&
            !trimmedMediaTitle.isEmpty()
        ) {
            return trimmedMediaTitle;
        }

        return selectedDisplayName == null ? JSONObject.NULL : selectedDisplayName;
    }

    private String readJsonString(JSONObject payload, String key) {
        if (payload == null || key == null || key.trim().isEmpty() || payload.isNull(key)) {
            return null;
        }

        String value = payload.optString(key, null);
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) || "undefined".equalsIgnoreCase(trimmed)) {
            return null;
        }

        return trimmed;
    }

    private Long readJsonLong(JSONObject payload, String key) {
        if (payload == null || key == null || key.trim().isEmpty() || payload.isNull(key)) {
            return null;
        }

        Object value = payload.opt(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }

        return null;
    }

    private Double readJsonDouble(JSONObject payload, String key) {
        if (payload == null || key == null || key.trim().isEmpty() || payload.isNull(key)) {
            return null;
        }

        Object value = payload.opt(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

        return null;
    }

    private void querySelectedAudioMetadataLocked() {
        if (appContext == null || selectedAudioUri == null) {
            selectedDisplayName = null;
            selectedMimeType = null;
            selectedSize = -1L;
            return;
        }

        selectedDisplayName = null;
        selectedMimeType = appContext.getContentResolver().getType(selectedAudioUri);
        selectedSize = -1L;

        Cursor cursor = null;
        try {
            cursor = appContext.getContentResolver().query(
                selectedAudioUri,
                new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE },
                null,
                null,
                null
            );

            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0) {
                    selectedDisplayName = cursor.getString(nameIndex);
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    selectedSize = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception ignored) {
            // Keep going with best-effort metadata.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        if ((selectedDisplayName == null || selectedDisplayName.trim().isEmpty()) &&
            selectedAudioUri.getLastPathSegment() != null) {
            selectedDisplayName = selectedAudioUri.getLastPathSegment();
        }

        if (selectedMimeType == null || selectedMimeType.trim().isEmpty()) {
            selectedMimeType = "audio/*";
        }
    }

    private String ensurePairingCodeLocked() {
        if (pairingCode == null || pairingCode.trim().isEmpty()) {
            pairingCode = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.US);
        }
        return pairingCode;
    }

    private boolean isRunningLocked() {
        return serverSocket != null && !serverSocket.isClosed() && listeningPort > 0;
    }

    @SuppressWarnings("deprecation")
    private boolean isWifiConnectedLocked() {
        if (appContext == null) {
            return false;
        }

        ConnectivityManager connectivityManager =
            (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                return false;
            }
            NetworkCapabilities capabilities =
                connectivityManager.getNetworkCapabilities(activeNetwork);
            return capabilities != null &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }

        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null &&
            activeNetworkInfo.isConnected() &&
            activeNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI;
    }

    private String getLocalIpAddressLocked() {
        try {
            String bestAddress = null;
            int bestScore = Integer.MIN_VALUE;
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (
                    !networkInterface.isUp() ||
                    networkInterface.isLoopback() ||
                    networkInterface.isVirtual() ||
                    networkInterface.isPointToPoint()
                ) {
                    continue;
                }

                int interfaceScore = scoreNetworkInterface(networkInterface);
                if (interfaceScore <= Integer.MIN_VALUE / 8) {
                    continue;
                }

                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }

                    Inet4Address ipv4Address = (Inet4Address) address;
                    String hostAddress = ipv4Address.getHostAddress();
                    if (hostAddress == null || hostAddress.trim().isEmpty()) {
                        continue;
                    }

                    int candidateScore = interfaceScore + scoreIpv4Address(ipv4Address);
                    if (candidateScore > bestScore) {
                        bestScore = candidateScore;
                        bestAddress = hostAddress.trim();
                    }
                }
            }
            return bestAddress;
        } catch (SocketException ignored) {
            // Ignore local IP detection failures.
        }

        return null;
    }

    private int scoreNetworkInterface(NetworkInterface networkInterface) {
        String interfaceName =
            networkInterface == null || networkInterface.getName() == null
                ? ""
                : networkInterface.getName().trim().toLowerCase(Locale.US);
        if (
            interfaceName.startsWith("tun") ||
            interfaceName.startsWith("tap") ||
            interfaceName.startsWith("ppp") ||
            interfaceName.contains("vpn")
        ) {
            return Integer.MIN_VALUE / 4;
        }
        if (
            interfaceName.startsWith("wlan") ||
            interfaceName.startsWith("wifi") ||
            interfaceName.startsWith("swlan")
        ) {
            return 220;
        }
        if (
            interfaceName.startsWith("eth") ||
            interfaceName.startsWith("en") ||
            interfaceName.startsWith("ap")
        ) {
            return 180;
        }
        if (interfaceName.startsWith("rndis") || interfaceName.startsWith("usb")) {
            return 130;
        }
        if (
            interfaceName.startsWith("rmnet") ||
            interfaceName.startsWith("ccmni") ||
            interfaceName.startsWith("pdp") ||
            interfaceName.startsWith("cell") ||
            interfaceName.startsWith("radio")
        ) {
            return 40;
        }
        return 100;
    }

    private int scoreIpv4Address(Inet4Address address) {
        if (address == null || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
            return Integer.MIN_VALUE / 4;
        }

        byte[] octets = address.getAddress();
        if (octets == null || octets.length != 4) {
            return 0;
        }

        int first = octets[0] & 0xFF;
        int second = octets[1] & 0xFF;
        if (first == 192 && second == 168) {
            return 120;
        }
        if (first == 10) {
            return 115;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return 110;
        }
        if (address.isSiteLocalAddress()) {
            return 100;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return 25;
        }
        return 50;
    }

    private String truncateAttributeValue(String rawValue) {
        String trimmed = rawValue == null ? "" : rawValue.trim();
        return trimmed.length() <= 96 ? trimmed : trimmed.substring(0, 96);
    }

    private String normalizeControlCommand(String rawCommand) {
        String trimmed = rawCommand == null ? "" : rawCommand.trim().toLowerCase(Locale.US);
        switch (trimmed) {
            case "previous":
            case "next":
            case "pause":
            case "play":
            case "vocal-remove":
            case "vocal-normal":
            case "mic-start":
            case "mic-stop":
            case "delay-down":
            case "delay-up":
            case "delay-reset":
            case "mic-gain-down":
            case "mic-gain-up":
            case "music-bed-down":
            case "music-bed-up":
                return trimmed;
            default:
                return null;
        }
    }

    private int readLittleEndianPcm16(byte[] buffer, int offset) {
        int low = buffer[offset] & 0xFF;
        int high = buffer[offset + 1];
        return (short) (low | (high << 8));
    }

    private int clampNormalizedPcm16(double value) {
        return clampPcm16((int) Math.round(value * Short.MAX_VALUE));
    }

    private double clampUnit(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double clampMicMixGain(double value) {
        return Math.max(0.0d, Math.min(MIC_MIX_GAIN_MAX, value));
    }

    private double clampMicMusicBedLevel(double value) {
        return Math.max(MIC_MUSIC_BED_MIN, Math.min(MIC_MUSIC_BED_MAX, value));
    }

    private double computeNormalizedMicPeak(byte[] buffer, int length) {
        if (buffer == null || length <= 1) {
            return 0.0d;
        }

        double peak = 0.0d;
        int frameLength = length - (length % PCM_BYTES_PER_SAMPLE);
        for (int offset = 0; offset < frameLength; offset += PCM_BYTES_PER_SAMPLE) {
            double sampleLevel =
                Math.abs(readLittleEndianPcm16(buffer, offset)) / PCM_16_NORMALIZED_SCALE;
            if (sampleLevel > peak) {
                peak = sampleLevel;
            }
        }
        return clampUnit(peak);
    }

    private double smoothMicLevel(double previousLevel, double nextPeak) {
        double smoothing =
            nextPeak >= previousLevel
                ? MIC_LEVEL_ATTACK_SMOOTHING
                : MIC_LEVEL_RELEASE_SMOOTHING;
        return clampUnit(previousLevel + ((nextPeak - previousLevel) * smoothing));
    }

    private String getMicrophoneReadErrorMessage(int errorCode) {
        switch (errorCode) {
            case AudioRecord.ERROR_DEAD_OBJECT:
                return "Marucast lost the phone mic session. Turn Phone Mic on again.";
            case AudioRecord.ERROR_INVALID_OPERATION:
                return "Android opened the phone mic, but the audio source never started cleanly.";
            case AudioRecord.ERROR_BAD_VALUE:
                return "Android rejected the phone mic buffer settings for this session.";
            default:
                return "Marucast couldn't keep reading from the phone microphone.";
        }
    }

    private double softClip(double value) {
        return Math.tanh(value * 1.35d) / Math.tanh(1.35d);
    }

    private void writeLittleEndianPcm16(byte[] buffer, int offset, int value) {
        int clamped = clampPcm16(value);
        buffer[offset] = (byte) (clamped & 0xFF);
        buffer[offset + 1] = (byte) ((clamped >> 8) & 0xFF);
    }

    private int clampPcm16(int value) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    private void putJson(JSONObject payload, String key, Object value) {
        try {
            payload.put(key, value);
        } catch (Exception ignored) {
            // Ignore best-effort status serialization failures.
        }
    }

    private String getReasonPhrase(int statusCode) {
        switch (statusCode) {
            case 200:
                return "OK";
            case 204:
                return "No Content";
            case 206:
                return "Partial Content";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 416:
                return "Range Not Satisfiable";
            default:
                return "Error";
        }
    }

    private static final class RangeRequest {
        final long start;
        final long end;

        RangeRequest(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class LivePcmClient {
        final Socket socket;
        final OutputStream output;
        final Object monitor = new Object();
        final boolean loopback;

        LivePcmClient(Socket socket, OutputStream output, boolean loopback) {
            this.socket = socket;
            this.output = output;
            this.loopback = loopback;
        }
    }
}
