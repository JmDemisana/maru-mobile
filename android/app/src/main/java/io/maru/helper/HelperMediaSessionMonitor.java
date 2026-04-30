package io.maru.helper;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;

public final class HelperMediaSessionMonitor {
    private static final HelperMediaSessionMonitor INSTANCE = new HelperMediaSessionMonitor();
    private static final String MEDIA_NOTIFICATION_LISTENER_COMPONENT =
        "io.maru.helper/io.maru.helper.HelperMediaNotificationListenerService";
    private static final int MAX_ARTWORK_EDGE_PX = 420;
    private static final int ARTWORK_JPEG_QUALITY = 82;

    private final Object lock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Context appContext;
    private MediaSessionManager mediaSessionManager;
    private MediaController activeController;
    private NotificationListenerHandle listenerHandle;
    private boolean sessionsListenerRegistered = false;
    private MediaSnapshot snapshot = new MediaSnapshot();

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            refreshActiveController();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            refreshActiveController();
        }

        @Override
        public void onSessionDestroyed() {
            refreshActiveController();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener =
        (controllers) -> refreshActiveController();

    public static HelperMediaSessionMonitor getInstance() {
        return INSTANCE;
    }

    private HelperMediaSessionMonitor() {}

    public void bind(Context context) {
        if (context == null) {
            return;
        }

        synchronized (lock) {
            appContext = context.getApplicationContext();
            if (mediaSessionManager == null && appContext != null) {
                mediaSessionManager =
                    (MediaSessionManager) appContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
            }
        }
    }

    public void onListenerConnected(Context context) {
        bind(context);
        synchronized (lock) {
            listenerHandle = new NotificationListenerHandle(
                new ComponentName(appContext, HelperMediaNotificationListenerService.class)
            );
            registerSessionsListenerLocked();
        }
        refreshActiveController();
    }

    public void onListenerDisconnected() {
        synchronized (lock) {
            unregisterSessionsListenerLocked();
            listenerHandle = null;
            unregisterControllerLocked();
            snapshot = buildSnapshotLocked(null);
        }
    }

    public JSONObject getStatusJson() {
        synchronized (lock) {
            snapshot = buildSnapshotLocked(activeController);
            JSONObject payload = new JSONObject();
            putJson(payload, "mediaAccessEnabled", isNotificationListenerEnabledLocked());
            putJson(payload, "mediaAppPackage", nullable(snapshot.appPackage));
            putJson(payload, "mediaAppLabel", nullable(snapshot.appLabel));
            putJson(payload, "mediaTitle", nullable(snapshot.title));
            putJson(payload, "mediaArtist", nullable(snapshot.artist));
            putJson(payload, "mediaArtworkAvailable", snapshot.artworkJpegBytes != null);
            putJson(payload, "mediaArtworkKey", nullable(snapshot.artworkCacheKey));
            putJson(payload, "mediaDurationMs", nullable(snapshot.durationMs));
            putJson(payload, "mediaPlaying", snapshot.playing);
            putJson(payload, "mediaPlaybackSpeed", nullable(snapshot.playbackSpeed));
            putJson(payload, "mediaPositionCapturedAtMs", nullable(snapshot.positionCapturedAtMs));
            putJson(payload, "mediaPositionMs", nullable(snapshot.positionMs));
            putJson(payload, "transportAvailable", activeController != null);
            return payload;
        }
    }

    public String getMediaTitle() {
        synchronized (lock) {
            return snapshot.title;
        }
    }

    public String getMediaArtist() {
        synchronized (lock) {
            return snapshot.artist;
        }
    }

    public String getMediaAppLabel() {
        synchronized (lock) {
            return snapshot.appLabel;
        }
    }

    public byte[] getArtworkJpegBytes() {
        synchronized (lock) {
            return snapshot.artworkJpegBytes == null
                ? null
                : Arrays.copyOf(snapshot.artworkJpegBytes, snapshot.artworkJpegBytes.length);
        }
    }

    public String getArtworkCacheKey() {
        synchronized (lock) {
            return snapshot.artworkCacheKey;
        }
    }

    public boolean dispatchTransportCommand(String command) {
        final MediaController controller;
        synchronized (lock) {
            controller = activeController;
        }

        if (controller == null || command == null || command.trim().isEmpty()) {
            return false;
        }

        try {
            MediaController.TransportControls controls = controller.getTransportControls();
            String normalized = command.trim().toLowerCase(Locale.US);
            switch (normalized) {
                case "previous":
                    controls.skipToPrevious();
                    return true;
                case "next":
                    controls.skipToNext();
                    return true;
                case "pause":
                    controls.pause();
                    return true;
                case "play":
                    controls.play();
                    return true;
                case "toggle":
                    if (isPlayingState(controller.getPlaybackState())) {
                        controls.pause();
                    } else {
                        controls.play();
                    }
                    return true;
                default:
                    return false;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private void refreshActiveController() {
        mainHandler.post(() -> {
            synchronized (lock) {
                if (!isNotificationListenerEnabledLocked()) {
                    unregisterSessionsListenerLocked();
                    unregisterControllerLocked();
                    snapshot = buildSnapshotLocked(null);
                    return;
                }

                registerSessionsListenerLocked();
                MediaController nextController = pickBestControllerLocked();
                if (activeController == nextController) {
                    snapshot = buildSnapshotLocked(activeController);
                    return;
                }

                unregisterControllerLocked();
                activeController = nextController;
                if (activeController != null) {
                    try {
                        activeController.registerCallback(controllerCallback, mainHandler);
                    } catch (Exception ignored) {
                        activeController = null;
                    }
                }
                snapshot = buildSnapshotLocked(activeController);
            }
        });
    }

    private void registerSessionsListenerLocked() {
        if (sessionsListenerRegistered || mediaSessionManager == null || listenerHandle == null) {
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mediaSessionManager.addOnActiveSessionsChangedListener(
                    sessionsChangedListener,
                    listenerHandle.componentName,
                    mainHandler
                );
            } else {
                mediaSessionManager.addOnActiveSessionsChangedListener(
                    sessionsChangedListener,
                    listenerHandle.componentName,
                    mainHandler
                );
            }
            sessionsListenerRegistered = true;
        } catch (SecurityException ignored) {
            sessionsListenerRegistered = false;
        }
    }

    private void unregisterSessionsListenerLocked() {
        if (!sessionsListenerRegistered || mediaSessionManager == null) {
            return;
        }

        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener);
        } catch (Exception ignored) {
            // Ignore stale listener cleanup failures.
        }
        sessionsListenerRegistered = false;
    }

    private void unregisterControllerLocked() {
        if (activeController == null) {
            return;
        }

        try {
            activeController.unregisterCallback(controllerCallback);
        } catch (Exception ignored) {
            // Ignore stale callback cleanup failures.
        }
        activeController = null;
    }

    private MediaController pickBestControllerLocked() {
        if (mediaSessionManager == null || listenerHandle == null) {
            return null;
        }

        try {
            List<MediaController> controllers =
                mediaSessionManager.getActiveSessions(listenerHandle.componentName);
            if (controllers == null || controllers.isEmpty()) {
                return null;
            }

            MediaController bestFallback = null;
            for (MediaController controller : controllers) {
                if (controller == null) {
                    continue;
                }

                if (isPlayingState(controller.getPlaybackState())) {
                    return controller;
                }

                if (bestFallback == null && controller.getMetadata() != null) {
                    bestFallback = controller;
                }
            }

            return bestFallback != null ? bestFallback : controllers.get(0);
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private MediaSnapshot buildSnapshotLocked(MediaController controller) {
        MediaSnapshot next = new MediaSnapshot();
        next.mediaAccessEnabled = isNotificationListenerEnabledLocked();
        if (controller == null) {
            return next;
        }

        next.appPackage = controller.getPackageName();
        next.appLabel = resolveApplicationLabelLocked(next.appPackage);
        MediaMetadata metadata = controller.getMetadata();
        if (metadata != null) {
            next.title = firstNonBlank(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            );
            next.artist = firstNonBlank(
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            );
            next.artworkJpegBytes = buildArtworkBytes(metadata);
            next.artworkCacheKey = next.artworkJpegBytes == null
                ? null
                : Integer.toHexString(Arrays.hashCode(next.artworkJpegBytes));
            long rawDurationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (rawDurationMs > 0) {
                next.durationMs = rawDurationMs;
            }
        }

        PlaybackState playbackState = controller.getPlaybackState();
        if (playbackState != null) {
            long rawPositionMs = playbackState.getPosition();
            float playbackSpeed = playbackState.getPlaybackSpeed();
            if (rawPositionMs >= 0) {
                long captureWallTimeMs = System.currentTimeMillis();
                long captureElapsedTimeMs = SystemClock.elapsedRealtime();
                long positionUpdatedElapsedTimeMs = playbackState.getLastPositionUpdateTime();
                long sinceUpdateMs = positionUpdatedElapsedTimeMs > 0
                    ? Math.max(0L, captureElapsedTimeMs - positionUpdatedElapsedTimeMs)
                    : 0L;
                long adjustedPositionMs = rawPositionMs;
                if (isPlayingState(playbackState) && playbackSpeed > 0f) {
                    adjustedPositionMs += Math.round((double) sinceUpdateMs * (double) playbackSpeed);
                }
                next.positionMs = Math.max(0L, adjustedPositionMs);
                next.positionCapturedAtMs = captureWallTimeMs;
            }

            if (Float.isFinite(playbackSpeed)) {
                next.playbackSpeed = playbackSpeed;
            }
        }
        next.playing = isPlayingState(playbackState);
        return next;
    }

    private byte[] buildArtworkBytes(MediaMetadata metadata) {
        Bitmap artwork = firstNonNullBitmap(
            metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ART),
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        );
        if (artwork == null) {
            return null;
        }

        Bitmap workingBitmap = artwork;
        try {
            int width = Math.max(1, artwork.getWidth());
            int height = Math.max(1, artwork.getHeight());
            int largestEdge = Math.max(width, height);
            if (largestEdge > MAX_ARTWORK_EDGE_PX) {
                float scale = (float) MAX_ARTWORK_EDGE_PX / (float) largestEdge;
                int scaledWidth = Math.max(1, Math.round(width * scale));
                int scaledHeight = Math.max(1, Math.round(height * scale));
                workingBitmap = Bitmap.createScaledBitmap(artwork, scaledWidth, scaledHeight, true);
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            boolean compressed = workingBitmap.compress(
                Bitmap.CompressFormat.JPEG,
                ARTWORK_JPEG_QUALITY,
                output
            );
            return compressed ? output.toByteArray() : null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (workingBitmap != artwork) {
                try {
                    workingBitmap.recycle();
                } catch (Exception ignored) {
                    // Ignore temporary bitmap cleanup failures.
                }
            }
        }
    }

    private String resolveApplicationLabelLocked(String packageName) {
        if (appContext == null || packageName == null || packageName.trim().isEmpty()) {
            return null;
        }

        try {
            PackageManager packageManager = appContext.getPackageManager();
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(info);
            if (label != null && label.toString().trim().length() > 0) {
                return label.toString().trim();
            }
        } catch (Exception ignored) {
            // Ignore best-effort app label lookups.
        }

        return packageName;
    }

    private boolean isNotificationListenerEnabledLocked() {
        if (appContext == null) {
            return false;
        }

        String enabledListeners = Settings.Secure.getString(
            appContext.getContentResolver(),
            "enabled_notification_listeners"
        );
        if (enabledListeners == null || enabledListeners.trim().isEmpty()) {
            return false;
        }

        return enabledListeners.toLowerCase(Locale.US)
            .contains(MEDIA_NOTIFICATION_LISTENER_COMPONENT.toLowerCase(Locale.US));
    }

    private boolean isPlayingState(PlaybackState playbackState) {
        if (playbackState == null) {
            return false;
        }

        int state = playbackState.getState();
        return state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_CONNECTING;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (value != null && value.trim().length() > 0) {
                return value.trim();
            }
        }

        return null;
    }

    private Bitmap firstNonNullBitmap(Bitmap... bitmaps) {
        if (bitmaps == null) {
            return null;
        }

        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
                return bitmap;
            }
        }

        return null;
    }

    private Object nullable(String value) {
        return value == null || value.trim().isEmpty() ? JSONObject.NULL : value;
    }

    private Object nullable(Long value) {
        return value == null ? JSONObject.NULL : value;
    }

    private Object nullable(Float value) {
        return value == null ? JSONObject.NULL : value;
    }

    private void putJson(JSONObject payload, String key, Object value) {
        try {
            payload.put(key, value);
        } catch (Exception ignored) {
            // Ignore best-effort JSON serialization failures.
        }
    }

    private static final class NotificationListenerHandle {
        final ComponentName componentName;

        NotificationListenerHandle(ComponentName componentName) {
            this.componentName = componentName;
        }
    }

    private static final class MediaSnapshot {
        String appPackage;
        String appLabel;
        String artist;
        String artworkCacheKey;
        byte[] artworkJpegBytes;
        Long durationMs;
        boolean mediaAccessEnabled;
        Float playbackSpeed;
        boolean playing;
        Long positionCapturedAtMs;
        Long positionMs;
        String title;
    }
}
