package io.maru.helper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

public final class HelperLastFmDetectorAlarmScheduler {
    private static final long DETECTOR_ALARM_INTERVAL_MS = 15 * 1000;
    private static final int PENDING_INTENT_FLAGS =
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

    private HelperLastFmDetectorAlarmScheduler() {
    }

    public static void cancel(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        alarmManager.cancel(buildPendingIntent(context));
    }

    public static void schedule(Context context) {
        String installationId = HelperStorage.getInstallationId(context);
        String serverOrigin = HelperStorage.resolveDetectorServerOrigin(context);
        if (installationId.isEmpty() || serverOrigin.isEmpty() ||
            !HelperLastFmDetectorController.shouldRun(context)) {
            return;
        }

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        long triggerAtMs = SystemClock.elapsedRealtime() + DETECTOR_ALARM_INTERVAL_MS;
        PendingIntent pendingIntent = buildPendingIntent(context);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMs,
                pendingIntent
            );
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMs,
                pendingIntent
            );
            return;
        }

        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAtMs,
            pendingIntent
        );
    }

    private static PendingIntent buildPendingIntent(Context context) {
        Intent intent = new Intent(context, HelperLastFmDetectorAlarmReceiver.class);
        intent.setAction("io.maru.helper.action.LASTFM_DETECTOR_ALARM");
        return PendingIntent.getBroadcast(context, 0, intent, PENDING_INTENT_FLAGS);
    }
}
