package io.maru.helper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;

import java.util.Locale;

public final class HelperLastFmNowPlayingNotificationManager {
    private static final String CHANNEL_ID = "maru_helper_alerts";
    private static final String PREFS_NAME = "maru-helper-lastfm-now-playing";
    private static final String KEY_LAST_TRACK_KEY = "last-track-key";
    private static final String KEY_LAST_NOTIFIED_AT = "last-notified-at";
    private static final String NOTIFICATION_TAG = "elevation-lastfm-now-playing";
    private static final String LEGACY_NOTIFICATION_TAG_PREFIX = "elevation-lastfm-now-playing:";
    private static final int NOTIFICATION_ID = 12045;
    private static final long NOTIFICATION_TIMEOUT_MS = 60_000L;
    private static final long DUPLICATE_SUPPRESSION_MS = 90_000L;

    private HelperLastFmNowPlayingNotificationManager() {
    }

    public static void maybeShowNowPlayingNotification(
        Context context,
        String rawTitle,
        String rawArtist,
        String rawAlbum,
        boolean playing
    ) {
        if (context == null || !playing || !HelperStorage.isElevationLastFmEnabled(context)) {
            return;
        }

        String title = trimToEmpty(rawTitle);
        String artist = trimToEmpty(rawArtist);
        if (title.isEmpty() || artist.isEmpty()) {
            return;
        }

        String trackKey = buildTrackKey(title, artist, rawAlbum);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String previousTrackKey = trimToEmpty(prefs.getString(KEY_LAST_TRACK_KEY, ""));
        long lastNotifiedAt = Math.max(0L, prefs.getLong(KEY_LAST_NOTIFIED_AT, 0L));
        long now = System.currentTimeMillis();
        if (trackKey.equals(previousTrackKey) && now - lastNotifiedAt < DUPLICATE_SUPPRESSION_MS) {
            return;
        }

        ensureNotificationChannel(context);
        NotificationManager notificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }

        cancelActiveNowPlayingNotifications(notificationManager);
        Notification notification = buildNotification(context, title, artist);
        notificationManager.notify(NOTIFICATION_ID, notification);

        prefs.edit()
            .putString(KEY_LAST_TRACK_KEY, trackKey)
            .putLong(KEY_LAST_NOTIFIED_AT, now)
            .apply();
    }

    private static void cancelActiveNowPlayingNotifications(NotificationManager notificationManager) {
        notificationManager.cancel(NOTIFICATION_ID);
        notificationManager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
        notificationManager.cancel(NOTIFICATION_TAG, 0);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        try {
            StatusBarNotification[] activeNotifications = notificationManager.getActiveNotifications();
            if (activeNotifications == null || activeNotifications.length == 0) {
                return;
            }

            for (StatusBarNotification activeNotification : activeNotifications) {
                if (activeNotification == null) {
                    continue;
                }

                String tag = trimToEmpty(activeNotification.getTag());
                if (tag.isEmpty()) {
                    continue;
                }

                if (tag.equals(NOTIFICATION_TAG) || tag.startsWith(LEGACY_NOTIFICATION_TAG_PREFIX)) {
                    notificationManager.cancel(tag, activeNotification.getId());
                }
            }
        } catch (Exception ignored) {
            // Ignore best-effort cleanup failures and keep posting the fresh alert.
        }
    }

    private static Notification buildNotification(Context context, String title, String artist) {
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_legacy)
            .setContentTitle(title)
            .setContentText(artist)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setShowWhen(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .setContentIntent(contentIntent)
            .build();
    }

    private static void ensureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager notificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Maru helper alerts",
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(
            "Shows time-sensitive helper alerts such as Last.fm song changes and reminders."
        );
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.enableVibration(true);
        channel.setShowBadge(true);
        notificationManager.createNotificationChannel(channel);
    }

    public static String buildTrackKeyForComparison(
        String title,
        String artist,
        String rawAlbum
    ) {
        String album = trimToEmpty(rawAlbum);
        return normalize(title) + "::" + normalize(artist) + "::" + normalize(album);
    }

    private static String buildTrackKey(String title, String artist, String rawAlbum) {
        return buildTrackKeyForComparison(title, artist, rawAlbum);
    }

    private static String normalize(String value) {
        return trimToEmpty(value).toLowerCase(Locale.US);
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
