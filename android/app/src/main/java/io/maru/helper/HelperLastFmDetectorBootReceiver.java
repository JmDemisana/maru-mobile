package io.maru.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class HelperLastFmDetectorBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
            !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        HelperLastFmForegroundService.start(context);
        HelperLastFmDetectorWorker.schedule(context);
        HelperLastFmDetectorAlarmScheduler.schedule(context);
    }
}
