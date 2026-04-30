package io.maru.helper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class MarucastCaptureForegroundService extends Service {
    private static final String ACTION_START = "io.maru.helper.action.START_MARUCAST_CAPTURE";
    private static final String ACTION_STOP = "io.maru.helper.action.STOP_MARUCAST_CAPTURE";
    private static final String EXTRA_RESULT_CODE = "resultCode";
    private static final String EXTRA_RESULT_DATA = "resultData";
    private static final String CHANNEL_ID = "maru_helper_marucast_capture";
    private static final int NOTIFICATION_ID = 12044;

    public static void start(Context context, int resultCode, Intent resultData) {
        if (context == null || resultData == null) {
            return;
        }

        Intent intent = new Intent(context, MarucastCaptureForegroundService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(EXTRA_RESULT_DATA, resultData);

        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception ignored) {
            // Ignore foreground start failures and let the UI surface the manager error.
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, MarucastCaptureForegroundService.class);
        intent.setAction(ACTION_STOP);

        try {
            context.startService(intent);
        } catch (Exception ignored) {
            context.stopService(new Intent(context, MarucastCaptureForegroundService.class));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            MarucastSenderManager.getInstance().stopPlaybackCapture();
            stopForegroundAndSelf();
            return START_NOT_STICKY;
        }
        int resultCode = intent == null ? 0 : intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent == null ? null : intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == 0 || resultData == null) {
            MarucastSenderManager.getInstance().stopPlaybackCapture();
            stopForegroundAndSelf();
            return START_NOT_STICKY;
        }

        startForegroundInternal();
        MarucastSenderManager.getInstance().startPlaybackCapture(
            getApplicationContext(),
            resultCode,
            resultData
        );

        if (!MarucastSenderManager.getInstance().isPlaybackCaptureRunning()) {
            stopForegroundAndSelf();
            return START_NOT_STICKY;
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundInternal() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(
                NOTIFICATION_ID,
                notification,
                serviceTypes
            );
            return;
        }

        startForeground(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification() {
        Intent launchIntent = new Intent(
            Intent.ACTION_VIEW,
            Uri.parse(
                getString(R.string.custom_url_scheme) +
                    "://helper?target=/helper%3Fpanel%3Dmarucast"
            )
        );
        launchIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, MarucastCaptureForegroundService.class);
        stopIntent.setAction(ACTION_STOP);

        PendingIntent stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String expandedText =
            "Marucast is broadcasting.\n" +
            "Open Marucast on another screen, show its QR, then scan it with this phone.";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_legacy)
            .setContentTitle("Marucast is broadcasting.")
            .setContentText("Phone audio relay active")
            .setStyle(new NotificationCompat.BigTextStyle().bigText(expandedText))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .addAction(0, "End Broadcast", stopPendingIntent)
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
            "Marucast relay",
            NotificationManager.IMPORTANCE_MIN
        );
        channel.setDescription("Keeps the local Marucast playback relay alive.");
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private void stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty() ||
            "null".equalsIgnoreCase(trimmed) ||
            "undefined".equalsIgnoreCase(trimmed)) {
            return null;
        }

        return trimmed;
    }
}
