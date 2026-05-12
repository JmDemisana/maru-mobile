package io.maru.helper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class HelperLastFmForegroundService extends Service {
    private static final String ACTION_START = "io.maru.helper.action.START_LASTFM_KEEPALIVE";
    private static final String ACTION_STOP = "io.maru.helper.action.STOP_LASTFM_KEEPALIVE";
    private static final String CHANNEL_ID = "maru_helper_lastfm_keepalive";
    private static final String ALERT_CHANNEL_ID = "maru_helper_alerts";
    private static final int NOTIFICATION_ID = 12043;
    private static final long POLL_INTERVAL_MS = 1000;
    private static final String LOG_TAG = "MaruHelperKeepAlive";

    private final AtomicBoolean pollInFlight = new AtomicBoolean(false);
    private ScheduledExecutorService pollExecutor;

    public static void start(Context context) {
        if (context == null || !HelperLastFmDetectorController.shouldRun(context)) {
            return;
        }

        Intent intent = new Intent(context, HelperLastFmForegroundService.class);
        intent.setAction(ACTION_START);

        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception ignored) {
            // Ignore start failures here so the worker/alarm backup can still try.
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, HelperLastFmForegroundService.class);
        intent.setAction(ACTION_STOP);

        try {
            context.startService(intent);
        } catch (Exception ignored) {
            context.stopService(new Intent(context, HelperLastFmForegroundService.class));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
        ensureAlertNotificationChannel();
        startForegroundInternal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopPolling();
            stopForegroundAndSelf();
            return START_NOT_STICKY;
        }

        if (!hasDetectorContext()) {
            stopForegroundAndSelf();
            return START_NOT_STICKY;
        }

        startPolling();
        HelperLastFmDetectorAlarmScheduler.schedule(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopPolling();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean hasDetectorContext() {
        return HelperLastFmDetectorController.shouldRun(this);
    }

    private void startForegroundInternal() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
            return;
        }

        startForeground(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification() {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_legacy)
            .setContentTitle("Maru helper checks active")
            .setContentText("Running background checks for time-sensitive helper alerts.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .build();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager notificationManager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Maru helper background checks",
            NotificationManager.IMPORTANCE_MIN
        );
        channel.setDescription(
            "Runs background checks so time-sensitive helper alerts can stay responsive."
        );
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private void ensureAlertNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager notificationManager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
            ALERT_CHANNEL_ID,
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

    private void startPolling() {
        if (pollExecutor != null && !pollExecutor.isShutdown()) {
            return;
        }

        pollExecutor = Executors.newSingleThreadScheduledExecutor();
        pollExecutor.scheduleWithFixedDelay(() -> {
            if (!pollInFlight.compareAndSet(false, true)) {
                return;
            }

            try {
                HelperLastFmRoutePinger.pingNowPlayingRoute(getApplicationContext());
            } catch (Exception error) {
                Log.w(LOG_TAG, "Foreground Last.fm keep-alive ping failed.", error);
            } finally {
                pollInFlight.set(false);
            }
        }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPolling() {
        if (pollExecutor == null) {
            return;
        }

        pollExecutor.shutdownNow();
        pollExecutor = null;
        pollInFlight.set(false);
    }

    private void stopForegroundAndSelf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }
}
