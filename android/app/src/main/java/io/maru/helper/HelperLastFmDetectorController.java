package io.maru.helper;

import android.content.Context;

public final class HelperLastFmDetectorController {
    private HelperLastFmDetectorController() {
    }

    public static boolean shouldRun(Context context) {
        if (context == null) {
            return false;
        }

        return HelperStorage.isElevationLastFmEnabled(context) &&
            !HelperStorage.getElevationToken(context).isEmpty() &&
            !HelperStorage.getInstallationId(context).isEmpty() &&
            !HelperStorage.resolveDetectorServerOrigin(context).isEmpty();
    }

    public static void sync(Context context) {
        if (context == null) {
            return;
        }

        Context appContext = context.getApplicationContext();
        if (!shouldRun(appContext)) {
            HelperLastFmDetectorWorker.cancel(appContext);
            HelperLastFmDetectorAlarmScheduler.cancel(appContext);
            HelperLastFmForegroundService.stop(appContext);
            return;
        }

        HelperLastFmForegroundService.start(appContext);
        HelperLastFmDetectorWorker.schedule(appContext);
        HelperLastFmDetectorAlarmScheduler.schedule(appContext);
    }

    public static void disable(Context context) {
        if (context == null) {
            return;
        }

        Context appContext = context.getApplicationContext();
        HelperStorage.persistElevationLastFmEnabled(appContext, false);
        sync(appContext);
    }
}
