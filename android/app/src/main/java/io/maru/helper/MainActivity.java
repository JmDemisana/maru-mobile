package io.maru.helper;

import android.app.DownloadManager;
import android.app.UiModeManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.Manifest;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

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
    private boolean isTvDevice = false;
    private boolean hasRemoteInput = false;
    private ActivityResultLauncher<String[]> marucastAudioPicker;
    private ActivityResultLauncher<String> marucastRecordAudioPermissionRequest;
    private ActivityResultLauncher<Intent> marucastPlaybackCaptureLauncher;
    private String pendingMarucastAudioPermissionAction = null;
    private final Object stemInstallLock = new Object();
    private volatile boolean stemModelInstalling = false;
    private volatile String stemModelLastMessage = "";
    private volatile String stemModelLastError = "";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        detectTvDevice();

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

    private void detectTvDevice() {
        UiModeManager uiModeManager = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null) {
            isTvDevice = uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        }
        if (!isTvDevice) {
            PackageManager pm = getPackageManager();
            isTvDevice = pm.hasSystemFeature("android.software.leanback");
        }
        if (!isTvDevice) {
            PackageManager pm = getPackageManager();
            isTvDevice = pm.hasSystemFeature("android.hardware.type.television");
        }
    }

    private boolean isRemoteInputDevice(InputDevice device) {
        if (device == null) return false;
        int sources = device.getSources();
        boolean hasDpad = (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
        boolean hasGamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        boolean hasKeyboard = (sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
        return hasDpad || hasGamepad || hasKeyboard;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        InputDevice device = event.getDevice();
        if (isRemoteInputDevice(device)) {
            hasRemoteInput = true;
            if (getBridge() != null && getBridge().getWebView() != null) {
                getBridge().getWebView().post(() -> {
                    if (getBridge() != null) {
                        getBridge().getWebView().evaluateJavascript("window.__tvRemoteKeyPressed && window.__tvRemoteKeyPressed(" + keyCode + ")", null);
                    }
                });
            }
        }
        return super.onKeyDown(keyCode, event);
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

    private JSONObject buildStemModelStatusJson() {
        JSONObject payload = new JSONObject();
        File downloadedModel = HelperStemVocalReducer.getDownloadedModelFile(this);
        File legacyModel = HelperStemVocalReducer.getLegacyDownloadedModelFile(this);
        boolean installed = downloadedModel.exists() || legacyModel.exists();
        File activeFile = downloadedModel.exists() ? downloadedModel : legacyModel;

        try {
            payload.put("installed", installed);
            payload.put("installing", stemModelInstalling);
            payload.put("sizeBytes", installed ? activeFile.length() : 0L);
            payload.put("path", installed ? activeFile.getAbsolutePath() : JSONObject.NULL);
            payload.put("ready", MarucastSenderManager.getInstance().getStatusJson());
            payload.put("lastMessage", stemModelLastMessage == null || stemModelLastMessage.isEmpty() ? JSONObject.NULL : stemModelLastMessage);
            payload.put("lastError", stemModelLastError == null || stemModelLastError.isEmpty() ? JSONObject.NULL : stemModelLastError);
        } catch (Exception ignored) {
            // Best effort JSON payload.
        }

        return payload;
    }

    private void installStemModelInBackground(String rawUrl) {
        synchronized (stemInstallLock) {
            if (stemModelInstalling) {
                stemModelLastMessage = "";
                stemModelLastError = "Karaoke Stem is already installing.";
                return;
            }
            stemModelInstalling = true;
            stemModelLastMessage = "Installing Karaoke Stem…";
            stemModelLastError = "";
        }

        new Thread(() -> {
            HttpURLConnection connection = null;
            InputStream inputStream = null;
            FileOutputStream outputStream = null;
            File tempFile = null;

            try {
                URL url = new URL(rawUrl.trim());
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setRequestProperty("Accept", "application/octet-stream");
                connection.connect();

                int statusCode = connection.getResponseCode();
                if (statusCode < 200 || statusCode >= 300) {
                    throw new IllegalStateException("Stem download failed (" + statusCode + ").");
                }

                inputStream = connection.getInputStream();
                File targetFile = HelperStemVocalReducer.getDownloadedModelFile(MainActivity.this);
                File parentDir = targetFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                tempFile = new File(targetFile.getAbsolutePath() + ".tmp");
                outputStream = new FileOutputStream(tempFile, false);

                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
                outputStream.flush();

                File legacyFile = HelperStemVocalReducer.getLegacyDownloadedModelFile(MainActivity.this);
                if (legacyFile.exists()) {
                    legacyFile.delete();
                }

                if (targetFile.exists() && !targetFile.delete()) {
                    throw new IllegalStateException("Could not replace the old stem file.");
                }
                if (!tempFile.renameTo(targetFile)) {
                    throw new IllegalStateException("Could not finish saving the stem file.");
                }

                MarucastSenderManager.getInstance().refreshStemModel();
                stemModelLastMessage = "Karaoke Stem installed. Marucast will use it now.";
                stemModelLastError = "";
            } catch (Exception error) {
                stemModelLastMessage = "";
                String message = error.getMessage();
                stemModelLastError =
                    message == null || message.trim().isEmpty()
                        ? "Could not install Karaoke Stem right now."
                        : message.trim();
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
            } finally {
                try {
                    if (outputStream != null) {
                        outputStream.close();
                    }
                } catch (Exception ignored) {}
                try {
                    if (inputStream != null) {
                        inputStream.close();
                    }
                } catch (Exception ignored) {}
                if (connection != null) {
                    connection.disconnect();
                }
                synchronized (stemInstallLock) {
                    stemModelInstalling = false;
                }
            }
        }, "MaruStemInstaller").start();
    }

    private void uninstallStemModel() {
        synchronized (stemInstallLock) {
            if (stemModelInstalling) {
                stemModelLastMessage = "";
                stemModelLastError = "Wait for Karaoke Stem to finish installing first.";
                return;
            }
        }

        boolean removed = false;
        File currentFile = HelperStemVocalReducer.getDownloadedModelFile(this);
        if (currentFile.exists()) {
            removed = currentFile.delete() || removed;
        }
        File legacyFile = HelperStemVocalReducer.getLegacyDownloadedModelFile(this);
        if (legacyFile.exists()) {
            removed = legacyFile.delete() || removed;
        }

        if (removed) {
            MarucastSenderManager.getInstance().refreshStemModel();
            stemModelLastMessage = "Karaoke Stem removed. Space freed.";
            stemModelLastError = "";
            return;
        }

        stemModelLastMessage = "";
        stemModelLastError = "There was no installed Karaoke Stem to remove.";
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
        public String getSharedAuthUser() {
            return HelperStorage.getSharedAuthUser(MainActivity.this);
        }

        @JavascriptInterface
        public void setSharedAuthUser(String rawAuthUser) {
            HelperStorage.persistSharedAuthUser(MainActivity.this, rawAuthUser);
        }

        @JavascriptInterface
        public void clearSharedAuthUser() {
            HelperStorage.persistSharedAuthUser(MainActivity.this, "");
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

        @JavascriptInterface
        public boolean openInstalledApp(String packageName) {
            if (packageName == null || packageName.trim().isEmpty()) {
                return false;
            }

            Intent launchIntent =
                getPackageManager().getLaunchIntentForPackage(packageName.trim());
            if (launchIntent == null) {
                return false;
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            runOnUiThread(() -> {
                try {
                    startActivity(launchIntent);
                } catch (Exception ignored) {
                    // Keep the current helper visible if the package launch fails.
                }
            });
            return true;
        }

        @JavascriptInterface
        public void openLinkSettings(String panel) {
            String safePanel = panel == null ? "" : panel.trim();
            String serverOrigin = HelperStorage.getStoredServerOrigin(MainActivity.this);
            Uri.Builder builder = new Uri.Builder()
                .scheme("maruhelper")
                .authority("helper")
                .appendQueryParameter("action", "open-settings")
                .appendQueryParameter("panel", safePanel.isEmpty() ? "settings" : safePanel);

            if (serverOrigin != null && !serverOrigin.isEmpty()) {
                builder.appendQueryParameter("siteOrigin", serverOrigin);
            }

            Intent launchIntent = new Intent(Intent.ACTION_VIEW, builder.build());
            launchIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            runOnUiThread(() -> {
                try {
                    startActivity(launchIntent);
                } catch (Exception ignored) {
                    // Ignore deep-link failures and keep the current app visible.
                }
            });
        }

        @JavascriptInterface
        public void downloadApk(String rawUrl, String rawFilename) {
            if (rawUrl == null || rawUrl.trim().isEmpty()) {
                return;
            }

            String filename = rawFilename == null || rawFilename.trim().isEmpty()
                ? "maru-app.apk"
                : rawFilename.trim();

            Uri parsedUrl = Uri.parse(rawUrl.trim());
            DownloadManager downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (downloadManager == null) return;

            DownloadManager.Request request = new DownloadManager.Request(parsedUrl);
            request.setTitle("Maru APK Download");
            request.setDescription("Downloading " + filename);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(
                android.os.Environment.DIRECTORY_DOWNLOADS,
                filename
            );
            request.setMimeType("application/vnd.android.package-archive");
            request.setVisibleInDownloadsUi(true);

            downloadManager.enqueue(request);
        }

        @JavascriptInterface
        public void downloadStemModel(String rawUrl) {
            if (rawUrl == null || rawUrl.trim().isEmpty()) {
                return;
            }
            installStemModelInBackground(rawUrl);
        }

        @JavascriptInterface
        public String getStemModelState() {
            return buildStemModelStatusJson().toString();
        }

        @JavascriptInterface
        public void removeStemModel() {
            uninstallStemModel();
        }

        @JavascriptInterface
        public boolean canInstallUnknownApps() {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                return true;
            }
            return getPackageManager().canRequestPackageInstalls();
        }

        @JavascriptInterface
        public String getDeviceFormFactor() {
            if (isTvDevice) return "tv";
            if (hasRemoteInput) return "tv-remote";
            return "mobile";
        }

        @JavascriptInterface
        public boolean canInstallApks() {
            return !isTvDevice;
        }

        @JavascriptInterface
        public String getMarucastReceiverStatus() {
            return MarucastReceiverManager.getInstance().getStatusJson();
        }

        @JavascriptInterface
        public String startMarucastReceiverDiscovery() {
            MarucastReceiverManager.getInstance().startDiscovery();
            return MarucastReceiverManager.getInstance().getStatusJson();
        }

        @JavascriptInterface
        public String stopMarucastReceiverDiscovery() {
            MarucastReceiverManager.getInstance().stopDiscovery();
            return MarucastReceiverManager.getInstance().getStatusJson();
        }

        @JavascriptInterface
        public String getMarucastDiscoveredSenders() {
            return MarucastReceiverManager.getInstance().getDiscoveredSendersJson();
        }

        @JavascriptInterface
        public String connectMarucastReceiver(String host, int port, String senderName) {
            MarucastReceiverManager.getInstance().connectToSender(host, port, senderName);
            return MarucastReceiverManager.getInstance().getStatusJson();
        }

        @JavascriptInterface
        public String disconnectMarucastReceiver() {
            MarucastReceiverManager.getInstance().disconnect();
            return MarucastReceiverManager.getInstance().getStatusJson();
        }

        @JavascriptInterface
        public String setMarucastReceiverVolume(float volume) {
            MarucastReceiverManager.getInstance().setVolume(volume);
            return MarucastReceiverManager.getInstance().getStatusJson();
        }
    }
}
