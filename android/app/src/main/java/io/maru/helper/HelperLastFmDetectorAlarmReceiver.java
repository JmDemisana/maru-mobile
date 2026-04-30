package io.maru.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class HelperLastFmDetectorAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        HelperLastFmForegroundService.start(context);
        HelperLastFmDetectorWorker.schedule(context);
        HelperLastFmDetectorAlarmScheduler.schedule(context);
    }
}
