package io.maru.helper;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

public class HelperLastFmDetectorWorker extends Worker {
    private static final int REQUEST_TIMEOUT_MS = 15000;
    private static final String LOG_TAG = "MaruHelperDetector";
    private static final String UNIQUE_WORK_NAME = "maru-helper-lastfm-detector";

    public HelperLastFmDetectorWorker(
        @NonNull Context context,
        @NonNull WorkerParameters params
    ) {
        super(context, params);
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME);
    }

    public static void schedule(Context context) {
        schedule(context, 0);
    }

    public static void schedule(Context context, long delayMinutes) {
        String installationId = HelperStorage.getInstallationId(context);
        String serverOrigin = HelperStorage.resolveDetectorServerOrigin(context);
        if (installationId.isEmpty() || serverOrigin.isEmpty()) {
            return;
        }

        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();

        OneTimeWorkRequest.Builder requestBuilder =
            new OneTimeWorkRequest.Builder(HelperLastFmDetectorWorker.class)
                .setConstraints(constraints);

        if (delayMinutes > 0) {
            requestBuilder.setInitialDelay(delayMinutes, TimeUnit.MINUTES);
        }

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            requestBuilder.build()
        );
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            HelperLastFmForegroundService.start(getApplicationContext());
            HelperLastFmRoutePinger.pingNowPlayingRoute(getApplicationContext());
        } catch (Exception error) {
            Log.w(LOG_TAG, "Background Last.fm detector ping failed.", error);
        }

        return Result.success();
    }
}
