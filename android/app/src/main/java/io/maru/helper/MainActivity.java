package io.maru.helper;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.Manifest;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

import org.json.JSONObject;

public class MainActivity extends BridgeActivity {
    private static final String LAUNCHER_ALIAS_NAME = "io.maru.helper.LauncherAlias";
    private static final String AUDIO_PERMISSION_ACTION_MICROPHONE = "microphone";
    private static final String AUDIO_PERMISSION_ACTION_PLAYBACK_CAPTURE = "playback-capture";
    private ActivityResultLauncher<String[]> marucastAudioPicker;
    private ActivityResultLauncher<String> marucastRecordAudioPermissionRequest;
    private ActivityResultLauncher<Intent> marucastPlaybackCaptureLauncher;
    private String pendingMarucastAudioPermissionAction = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MarucastSenderManager.getInstance().bind(getApplicationContext());
        HelperMediaSessionMonitor.getInstance().bind(getApplicationContext());
        marucastAudioPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            (uri) -> {
                if (uri == null) {
                    return;
                }

                try {
                    getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    );
                } catch (SecurityException ignored) {
                    // Some providers do not support persistable grants.
                }

                MarucastSenderManager.getInstance().selectAudio(
                    getApplicationContext(),
                    uri
                );
            }
        );
        marucastRecordAudioPermissionRequest = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            (granted) -> {
                String pendingAction = pendingMarucastAudioPermissionAction;
                pendingMarucastAudioPermissionAction = null;
                if (Boolean.TRUE.equals(granted)) {
                    if (AUDIO_PERMISSION_ACTION_MICROPHONE.equals(pendingAction)) {
                        MarucastSenderManager.getInstance().dispatchControlCommand("mic-start");
                        return;
                    }
                    launchMarucastPlaybackCapturePrompt();
                    return;
                }

                if (AUDIO_PERMISSION_ACTION_MICROPHONE.equals(pendingAction)) {
                    MarucastSenderManager.getInstance().setMicLastError(
                        "Marucast needs microphone access before it can send the phone mic lane."
                    );
                    return;
                }

                MarucastSenderManager.getInstance().setLastError(
                    "Marucast needs audio permission before it can relay current playback."
                );
            }
        );
        marucastPlaybackCaptureLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            (result) -> {
                if (result == null || result.getResultCode() != RESULT_OK || result.getData() == null) {
                    MarucastSenderManager.getInstance().setLastError(
                        "Playback capture was cancelled, so Marucast could not start."
                    );
                    return;
                }

                MarucastCaptureForegroundService.start(
                    getApplicationContext(),
                    result.getResultCode(),
                    result.getData()
                );
            }
        );

        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().addJavascriptInterface(
                new HelperNativeBridge(),
                "HelperNativeBridge"
            );
        }

        HelperLastFmDetectorWorker.schedule(this);
        HelperLastFmDetectorAlarmScheduler.schedule(this);
        HelperLastFmForegroundService.start(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        resumeMarucastRelayWebViewIfNeeded();
    }

    @Override
    public void onResume() {
        super.onResume();
        resumeMarucastRelayWebViewIfNeeded();
    }

    private void resumeMarucastRelayWebViewIfNeeded() {
        if (!MarucastSenderManager.getInstance().isPlaybackCaptureRunning()) {
            return;
        }
        if (getBridge() == null) {
            return;
        }

        WebView bridgeWebView = getBridge().getWebView();
        if (bridgeWebView == null) {
            return;
        }

        bridgeWebView.post(() -> {
            if (!MarucastSenderManager.getInstance().isPlaybackCaptureRunning() || getBridge() == null) {
                return;
            }

            WebView latestWebView = getBridge().getWebView();
            if (latestWebView == null) {
                return;
            }

            latestWebView.onResume();
            latestWebView.resumeTimers();
        });
    }

    private boolean isLauncherIconVisible() {
        PackageManager packageManager = getPackageManager();
        ComponentName launcherAlias = new ComponentName(this, LAUNCHER_ALIAS_NAME);
        int state = packageManager.getComponentEnabledSetting(launcherAlias);
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private void launchMarucastPlaybackCapturePrompt() {
        MediaProjectionManager mediaProjectionManager =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mediaProjectionManager == null || marucastPlaybackCaptureLauncher == null) {
            MarucastSenderManager.getInstance().setLastError(
                "This helper build could not open Android's playback capture prompt."
            );
            return;
        }

        try {
            marucastPlaybackCaptureLauncher.launch(
                mediaProjectionManager.createScreenCaptureIntent()
            );
        } catch (Exception error) {
            MarucastSenderManager.getInstance().setLastError(
                "Android would not open the playback capture prompt right now."
            );
        }
    }

    private final class HelperNativeBridge {
        @JavascriptInterface
        public String getLauncherIconState() {
            return isLauncherIconVisible() ? "shown" : "hidden";
        }

        @JavascriptInterface
        public void persistInstallationId(String rawInstallationId) {
            HelperStorage.persistInstallationId(MainActivity.this, rawInstallationId);
        }

        @JavascriptInterface
        public void persistServerOrigin(String rawServerOrigin) {
            HelperStorage.persistServerOrigin(MainActivity.this, rawServerOrigin);
        }

        @JavascriptInterface
        public void ensureLastFmDetectorScheduled() {
            HelperLastFmForegroundService.start(MainActivity.this);
            HelperLastFmDetectorWorker.schedule(MainActivity.this);
            HelperLastFmDetectorAlarmScheduler.schedule(MainActivity.this);
        }

        @JavascriptInterface
        public void cancelLastFmDetector() {
            HelperLastFmForegroundService.stop(MainActivity.this);
            HelperLastFmDetectorWorker.cancel(MainActivity.this);
            HelperLastFmDetectorAlarmScheduler.cancel(MainActivity.this);
        }

        @JavascriptInterface
        public String getMarucastStatus() {
            return MarucastSenderManager.getInstance().getStatusJson();
        }

        @JavascriptInterface
        public String dispatchMarucastControlCommand(String rawCommand) {
            if ("mic-start".equals(rawCommand == null ? "" : rawCommand.trim())) {
                if (ContextCompat.checkSelfPermission(
                    MainActivity.this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED) {
                    MarucastSenderManager.getInstance().setMicLastError(
                        "Allow microphone access on this phone to open the live mic lane."
                    );
                    runOnUiThread(() -> {
                        pendingMarucastAudioPermissionAction =
                            AUDIO_PERMISSION_ACTION_MICROPHONE;
                        if (marucastRecordAudioPermissionRequest != null) {
                            marucastRecordAudioPermissionRequest.launch(
                                Manifest.permission.RECORD_AUDIO
                            );
                        }
                    });
                    return MarucastSenderManager.getInstance().getStatusJson();
                }
            }

            return MarucastSenderManager.getInstance().dispatchControlCommand(rawCommand);
        }

        @JavascriptInterface
        public String dispatchMarucastTransportCommand(String rawCommand) {
            return dispatchMarucastControlCommand(rawCommand);
        }

        @JavascriptInterface
        public void pickMarucastAudioFile() {
            runOnUiThread(() -> {
                if (marucastAudioPicker != null) {
                    marucastAudioPicker.launch(new String[] { "audio/*" });
                }
            });
        }

        @JavascriptInterface
        public String startMarucastSender() {
            MarucastSenderManager.getInstance().start(getApplicationContext());
            return MarucastSenderManager.getInstance().getStatusJson();
        }

        @JavascriptInterface
        public String startMarucastClockSync() {
            MarucastSenderManager.getInstance().startSyncClock(getApplicationContext());
            return MarucastSenderManager.getInstance().getStatusJson();
        }

        @JavascriptInterface
        public void startMarucastPlaybackRelay() {
            runOnUiThread(() -> {
                if (ContextCompat.checkSelfPermission(
                    MainActivity.this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED) {
                    pendingMarucastAudioPermissionAction =
                        AUDIO_PERMISSION_ACTION_PLAYBACK_CAPTURE;
                    if (marucastRecordAudioPermissionRequest != null) {
                        marucastRecordAudioPermissionRequest.launch(
                            Manifest.permission.RECORD_AUDIO
                        );
                    }
                    return;
                }

                launchMarucastPlaybackCapturePrompt();
            });
        }

        @JavascriptInterface
        public String stopMarucastSender() {
            MarucastSenderManager.getInstance().stop();
            return MarucastSenderManager.getInstance().getStatusJson();
        }

        @JavascriptInterface
        public String stopMarucastPlaybackRelay() {
            MarucastCaptureForegroundService.stop(MainActivity.this);
            MarucastSenderManager.getInstance().stopPlaybackCapture();
            return MarucastSenderManager.getInstance().getStatusJson();
        }

        @JavascriptInterface
        public String clearMarucastAudioSelection() {
            MarucastSenderManager.getInstance().clearSelectedAudio();
            return MarucastSenderManager.getInstance().getStatusJson();
        }

        @JavascriptInterface
        public void openNotificationListenerSettings() {
            Intent launchIntent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            runOnUiThread(() -> {
                try {
                    startActivity(launchIntent);
                } catch (Exception ignored) {
                    // Ignore settings handoff failures and let the helper UI stay visible.
                }
            });
        }

        @JavascriptInterface
        public String toggleLauncherIconAndGetState() {
            PackageManager packageManager = getPackageManager();
            ComponentName launcherAlias = new ComponentName(
                MainActivity.this,
                LAUNCHER_ALIAS_NAME
            );
            boolean showIcon = !isLauncherIconVisible();

            packageManager.setComponentEnabledSetting(
                launcherAlias,
                showIcon
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            );

            return showIcon ? "shown" : "hidden";
        }

        @JavascriptInterface
        public void openExternalUrl(String rawUrl) {
            if (rawUrl == null || rawUrl.trim().isEmpty()) {
                return;
            }

            Uri parsedUrl = Uri.parse(rawUrl.trim());
            Intent launchIntent = new Intent(Intent.ACTION_VIEW, parsedUrl);
            launchIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            runOnUiThread(() -> {
                try {
                    startActivity(launchIntent);
                } catch (Exception ignored) {
                    // Ignore browser handoff failures and let the helper
                    // fallback UI stay visible instead.
                }
            });
        }
    }
}
