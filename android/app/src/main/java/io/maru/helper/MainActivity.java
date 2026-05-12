package io.maru.helper;

import android.Manifest;
import android.app.DownloadManager;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {
    private static final String AUDIO_PERMISSION_ACTION_MICROPHONE = "microphone";
    private static final String AUDIO_PERMISSION_ACTION_PLAYBACK_CAPTURE = "playback-capture";
    private static final String CAMERA_PERMISSION_ACTION_SCAN_RECEIVER = "scan-receiver";
    private static final String MARU_HELPER_SCHEME = "maruhelper";

    private boolean isTvDevice = false;
    private boolean hasRemoteInput = false;
    private String pendingMarucastAudioPermissionAction = null;
    private String pendingCameraPermissionAction = null;
    private String linkedMarucastReceiverToken = null;
    private String linkedMarucastServerOrigin = null;
    private String marucastPanelMessage = "";
    private String marucastPanelError = "";
    private String elevationStatusMessage = "";
    private String elevationStatusError = "";
    private boolean elevationBusy = false;
    private String lastHandledIntentUrl = null;
    private BottomNavigationView bottomNavigationView;

    private final ActivityResultLauncher<String[]> marucastAudioPicker = registerForActivityResult(
        new ActivityResultContracts.OpenDocument(),
        (uri) -> {
            if (uri == null) {
                setMarucastPanelMessage("Audio picking was cancelled.");
                return;
            }

            try {
                getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                );
            } catch (SecurityException ignored) {
                // Some content providers do not support persistable read grants.
            }

            MarucastSenderManager.getInstance().selectAudio(getApplicationContext(), uri);
            setMarucastPanelMessage("Chosen song ready. Start it from Marucast when you want.");
        }
    );

    private final ActivityResultLauncher<String> marucastRecordAudioPermissionRequest = registerForActivityResult(
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
                    "Allow microphone access on this phone to open the live mic lane."
                );
                return;
            }

            MarucastSenderManager.getInstance().setLastError(
                "Marucast needs audio permission before it can relay current playback."
            );
        }
    );

    private final ActivityResultLauncher<String> marucastCameraPermissionRequest = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(),
        (granted) -> {
            String pendingAction = pendingCameraPermissionAction;
            pendingCameraPermissionAction = null;
            if (Boolean.TRUE.equals(granted) &&
                CAMERA_PERMISSION_ACTION_SCAN_RECEIVER.equals(pendingAction)) {
                launchMarucastReceiverScanner();
                return;
            }

            if (CAMERA_PERMISSION_ACTION_SCAN_RECEIVER.equals(pendingAction)) {
                setMarucastPanelError(
                    "Allow camera access on this phone before scanning a Marucast receiver QR."
                );
            }
        }
    );

    private final ActivityResultLauncher<Intent> marucastPlaybackCaptureLauncher = registerForActivityResult(
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
            MarucastSenderManager.getInstance().startPlaybackCapture(
                getApplicationContext(),
                result.getResultCode(),
                result.getData()
            );
        }
    );

    private final ActivityResultLauncher<ScanOptions> marucastReceiverScanner = registerForActivityResult(
        new ScanContract(),
        (result) -> {
            String contents = result == null ? null : result.getContents();
            if (contents == null || contents.trim().isEmpty()) {
                setMarucastPanelMessage("Receiver scan cancelled.");
                return;
            }

            if (!applyLinkedReceiverFromRaw(contents, "Receiver QR linked. Start broadcasting when you're ready.")) {
                setMarucastPanelError(
                    "That QR was not a Marucast receiver link. Scan the QR from a waiting receiver screen."
                );
            }
        }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        detectTvDevice();
        MarucastSenderManager.getInstance().bind(getApplicationContext());
        HelperMediaSessionMonitor.getInstance().bind(getApplicationContext());

        View contentFrame = findViewById(R.id.content_frame);
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        applyWindowInsets(contentFrame, bottomNavigationView);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_apps) {
                loadFragment(new AppsFragment());
                return true;
            } else if (itemId == R.id.nav_marucast) {
                loadFragment(new MarucastFragment());
                return true;
            } else if (itemId == R.id.nav_settings) {
                loadFragment(new SettingsFragment());
                return true;
            }
            return false;
        });

        if (savedInstanceState == null) {
            loadFragment(new AppsFragment());
        }

        HelperLastFmDetectorWorker.schedule(this);
        HelperLastFmDetectorAlarmScheduler.schedule(this);

        handleIncomingIntent(getIntent());
    }

    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.content_frame, fragment)
            .commit();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        InputDevice device = event.getDevice();
        if (device != null) {
            int sources = device.getSources();
            boolean hasDpad = (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
            boolean hasGamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
            boolean hasKeyboard = (sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
            hasRemoteInput = hasDpad || hasGamepad || hasKeyboard;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void launchMarucastPlaybackCapturePrompt() {
        MediaProjectionManager mediaProjectionManager =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mediaProjectionManager == null) {
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

    private void launchMarucastReceiverScanner() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt("Scan a Marucast receiver QR");
        options.setBeepEnabled(false);
        options.setOrientationLocked(false);
        marucastReceiverScanner.launch(options);
    }

    public String getMarucastStatus() {
        return MarucastSenderManager.getInstance().getStatusJson();
    }

    public void startMarucastPlaybackRelay() {
        if (ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED) {
            pendingMarucastAudioPermissionAction = AUDIO_PERMISSION_ACTION_PLAYBACK_CAPTURE;
            marucastRecordAudioPermissionRequest.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }

        launchMarucastPlaybackCapturePrompt();
    }

    public void pickMarucastAudioFile() {
        marucastAudioPicker.launch(new String[] { "audio/*" });
    }

    public String startMarucastSender() {
        MarucastSenderManager.getInstance().start(getApplicationContext());
        return MarucastSenderManager.getInstance().getStatusJson();
    }

    public String startMarucastSyncClock() {
        MarucastSenderManager.getInstance().startSyncClock(getApplicationContext());
        return MarucastSenderManager.getInstance().getStatusJson();
    }

    public String stopMarucastSender() {
        MarucastCaptureForegroundService.stop(this);
        MarucastSenderManager.getInstance().stop();
        return MarucastSenderManager.getInstance().getStatusJson();
    }

    public String stopMarucastPlaybackRelay() {
        MarucastCaptureForegroundService.stop(this);
        MarucastSenderManager.getInstance().stopPlaybackCapture();
        return MarucastSenderManager.getInstance().getStatusJson();
    }

    public String clearMarucastAudioSelection() {
        MarucastSenderManager.getInstance().clearSelectedAudio();
        return MarucastSenderManager.getInstance().getStatusJson();
    }

    public String dispatchMarucastControlCommand(String rawCommand) {
        String normalizedCommand = rawCommand == null ? "" : rawCommand.trim();
        if ("mic-start".equals(normalizedCommand) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingMarucastAudioPermissionAction = AUDIO_PERMISSION_ACTION_MICROPHONE;
            marucastRecordAudioPermissionRequest.launch(Manifest.permission.RECORD_AUDIO);
            return MarucastSenderManager.getInstance().getStatusJson();
        }

        return MarucastSenderManager.getInstance().dispatchControlCommand(normalizedCommand);
    }

    public void startMarucastReceiverQrScan() {
        clearMarucastPanelError();
        setMarucastPanelMessage("Point the camera at a waiting Marucast receiver QR.");
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            pendingCameraPermissionAction = CAMERA_PERMISSION_ACTION_SCAN_RECEIVER;
            marucastCameraPermissionRequest.launch(Manifest.permission.CAMERA);
            return;
        }

        launchMarucastReceiverScanner();
    }

    public void clearLinkedMarucastReceiver() {
        linkedMarucastReceiverToken = null;
        linkedMarucastServerOrigin = null;
        setMarucastPanelMessage("Receiver link cleared.");
    }

    public String getLinkedMarucastReceiverToken() {
        return linkedMarucastReceiverToken == null ? "" : linkedMarucastReceiverToken;
    }

    public String getLinkedMarucastServerOrigin() {
        return linkedMarucastServerOrigin == null ? "" : linkedMarucastServerOrigin;
    }

    public String getMarucastPanelMessage() {
        return marucastPanelMessage == null ? "" : marucastPanelMessage;
    }

    public String getMarucastPanelError() {
        return marucastPanelError == null ? "" : marucastPanelError;
    }

    public void setMarucastPanelMessage(String rawMessage) {
        marucastPanelMessage = trimToEmpty(rawMessage);
        if (!marucastPanelMessage.isEmpty()) {
            marucastPanelError = "";
        }
    }

    public void setMarucastPanelError(String rawError) {
        marucastPanelError = trimToEmpty(rawError);
        if (!marucastPanelError.isEmpty()) {
            marucastPanelMessage = "";
        }
    }

    public void clearMarucastPanelError() {
        marucastPanelError = "";
    }

    public void openNotificationListenerSettings() {
        Intent launchIntent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launchIntent);
        } catch (Exception ignored) {
            // Keep the helper visible if Android blocks the handoff.
        }
    }

    public boolean isPackageInstalled(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }

        try {
            getPackageManager().getPackageInfo(packageName.trim(), 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

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
        try {
            startActivity(launchIntent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public void downloadApk(String rawUrl, String rawFilename) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return;
        }

        String filename = rawFilename == null || rawFilename.trim().isEmpty()
            ? "maru-app.apk"
            : rawFilename.trim();

        Uri parsedUrl = Uri.parse(rawUrl.trim());
        DownloadManager downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            return;
        }

        DownloadManager.Request request = new DownloadManager.Request(parsedUrl);
        request.setTitle("Maru app download");
        request.setDescription("Downloading " + filename);
        request.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        );
        request.setDestinationInExternalPublicDir(
            android.os.Environment.DIRECTORY_DOWNLOADS,
            filename
        );
        request.setMimeType("application/vnd.android.package-archive");
        request.setVisibleInDownloadsUi(true);
        downloadManager.enqueue(request);
    }

    public String getServerOrigin() {
        String storedOrigin = HelperStorage.getStoredServerOrigin(this);
        return storedOrigin == null || storedOrigin.isEmpty()
            ? HelperStorage.resolveDetectorServerOrigin(this)
            : storedOrigin;
    }

    public String getInstallationId() {
        return HelperStorage.ensureInstallationId(this);
    }

    public boolean hasElevationAccess() {
        return HelperElevationManager.hasElevationToken(this);
    }

    public boolean hasSecureElevationStorage() {
        return HelperElevationManager.hasSecureStorage(this);
    }

    public boolean isElevationBusy() {
        return elevationBusy;
    }

    public boolean isElevationLastFmEnabled() {
        return HelperElevationManager.isElevationLastFmEnabled(this);
    }

    public String getSharedAuthUserJson() {
        return HelperStorage.getSharedAuthUser(this);
    }

    public String getStemModelStateJson() {
        return HelperElevationManager.getStemModelState(this).toString();
    }

    public String getElevationStatusMessage() {
        return elevationStatusMessage == null ? "" : elevationStatusMessage;
    }

    public String getElevationStatusError() {
        return elevationStatusError == null ? "" : elevationStatusError;
    }

    public void openElevationAuth() {
        if (!HelperElevationManager.hasSecureStorage(this)) {
            setElevationStatus(
                "",
                "Encrypted helper storage is unavailable on this phone right now."
            );
            showSettingsPanel();
            return;
        }

        String authUrl = HelperElevationManager.buildElevationAuthUrl(this);
        if (authUrl.isEmpty()) {
            setElevationStatus("", "Could not open Elevation right now.");
            showSettingsPanel();
            return;
        }

        try {
            Intent launchIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
            launchIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launchIntent);
            setElevationStatus("Opening Elevation in your browser...", "");
        } catch (Exception ignored) {
            setElevationStatus("", "Could not open Elevation right now.");
        }
        showSettingsPanel();
    }

    public void clearElevationAccess() {
        HelperElevationManager.clearElevationState(this);
        setElevationStatus("Elevation removed from this phone.", "");
        showSettingsPanel();
    }

    public void openSharedAccountSite() {
        openExternalUrl(buildPublicSiteUrl("/options"));
    }

    public void toggleElevationLastFm() {
        if (elevationBusy) {
            return;
        }
        if (!HelperElevationManager.hasElevationToken(this)) {
            setElevationStatus("", "Open Elevation first.");
            showSettingsPanel();
            return;
        }

        final boolean enable = !HelperElevationManager.isElevationLastFmEnabled(this);
        elevationBusy = true;
        setElevationStatus(
            enable ? "Turning on Last.fm alerts..." : "Turning off Last.fm alerts...",
            ""
        );
        showSettingsPanel();

        new Thread(() -> {
            JSONObject response = HelperElevationManager.updateElevationLastFmNotifications(
                getApplicationContext(),
                enable
            );
            boolean success = response.optBoolean("success", false);
            String error = trimToEmpty(response.optString("error", ""));
            runOnUiThread(() -> {
                elevationBusy = false;
                if (success) {
                    setElevationStatus(
                        enable
                            ? "Last.fm alerts are on for this phone."
                            : "Last.fm alerts are off for this phone.",
                        ""
                    );
                } else {
                    setElevationStatus(
                        "",
                        error.isEmpty()
                            ? "Could not update Last.fm alerts right now."
                            : error
                    );
                }
                showSettingsPanel();
            });
        }).start();
    }

    public JSONObject fetchMarucastLinkedReceiverStatus(String receiverToken, String rawServerOrigin) {
        JSONObject request = new JSONObject();
        try {
            request.put("route", "marucast/receiver-status");
            request.put("token", trimToEmpty(receiverToken));
        } catch (Exception ignored) {
            // Best effort payload.
        }
        return postMarucastReceiverRoute(request, rawServerOrigin);
    }

    public JSONObject sendMarucastLinkedReceiverCommand(
        String command,
        String receiverToken,
        String rawServerOrigin
    ) {
        JSONObject request = new JSONObject();
        try {
            request.put("route", "marucast/receiver-command");
            request.put("command", trimToEmpty(command));
            request.put("token", trimToEmpty(receiverToken));
        } catch (Exception ignored) {
            // Best effort payload.
        }
        return postMarucastReceiverRoute(request, rawServerOrigin);
    }

    private void applyWindowInsets(View contentFrame, BottomNavigationView bottomNav) {
        final int contentLeft = contentFrame.getPaddingLeft();
        final int contentTop = contentFrame.getPaddingTop();
        final int contentRight = contentFrame.getPaddingRight();
        final int contentBottom = contentFrame.getPaddingBottom();
        final int navLeft = bottomNav.getPaddingLeft();
        final int navTop = bottomNav.getPaddingTop();
        final int navRight = bottomNav.getPaddingRight();
        final int navBottom = bottomNav.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            contentFrame.setPadding(
                contentLeft,
                contentTop + systemBars.top,
                contentRight,
                contentBottom
            );
            bottomNav.setPadding(
                navLeft,
                navTop,
                navRight,
                navBottom + systemBars.bottom
            );
            return insets;
        });
        ViewCompat.requestApplyInsets(findViewById(android.R.id.content));
    }

    private boolean applyLinkedReceiverFromRaw(String rawValue, String successMessage) {
        String receiverToken = extractMarucastReceiverToken(rawValue);
        if (receiverToken.isEmpty()) {
            return false;
        }

        String serverOrigin = extractMarucastServerOrigin(rawValue);
        if (serverOrigin == null || serverOrigin.isEmpty()) {
            serverOrigin = getServerOrigin();
        }
        if (serverOrigin != null && !serverOrigin.isEmpty()) {
            HelperStorage.persistServerOrigin(this, serverOrigin);
        }

        linkedMarucastReceiverToken = receiverToken;
        linkedMarucastServerOrigin = serverOrigin;
        setMarucastPanelMessage(successMessage);
        showMarucastPanel();
        return true;
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        Uri data = intent.getData();
        if (data == null) {
            return;
        }

        String rawUrl = trimToEmpty(data.toString());
        if (rawUrl.isEmpty() || rawUrl.equals(lastHandledIntentUrl)) {
            return;
        }

        String action = trimToEmpty(data.getQueryParameter("action"));
        String panel = trimToEmpty(data.getQueryParameter("panel"));
        String handoffToken = trimToEmpty(data.getQueryParameter("handoffToken"));
        String receiverToken = trimToEmpty(data.getQueryParameter("receiverToken"));
        String serverOrigin = normalizeServerOrigin(data.getQueryParameter("siteOrigin"));
        String target = trimToEmpty(data.getQueryParameter("target"));

        if (receiverToken.isEmpty() && !target.isEmpty()) {
            receiverToken = extractMarucastReceiverToken(target);
        }
        if ((serverOrigin == null || serverOrigin.isEmpty()) && !target.isEmpty()) {
            serverOrigin = extractMarucastServerOrigin(target);
        }

        boolean wantsElevation =
            MARU_HELPER_SCHEME.equalsIgnoreCase(data.getScheme()) &&
                "elevation-auth".equalsIgnoreCase(action);
        if (wantsElevation) {
            lastHandledIntentUrl = rawUrl;
            completeNativeElevationHandoff(handoffToken, serverOrigin);
            return;
        }

        boolean wantsSettings =
            MARU_HELPER_SCHEME.equalsIgnoreCase(data.getScheme()) &&
                "open-settings".equalsIgnoreCase(action);
        if (wantsSettings) {
            lastHandledIntentUrl = rawUrl;
            if (serverOrigin != null && !serverOrigin.isEmpty()) {
                HelperStorage.persistServerOrigin(this, serverOrigin);
            }
            showSettingsPanel();
            return;
        }

        boolean wantsMarucast =
            MARU_HELPER_SCHEME.equalsIgnoreCase(data.getScheme()) &&
                (
                    "marucast".equalsIgnoreCase(panel) ||
                    "share-live-relay".equalsIgnoreCase(action) ||
                    "start-live-relay".equalsIgnoreCase(action) ||
                    target.contains("marucast") ||
                    !receiverToken.isEmpty()
                );
        if (!wantsMarucast) {
            return;
        }

        lastHandledIntentUrl = rawUrl;

        if (!receiverToken.isEmpty()) {
            linkedMarucastReceiverToken = receiverToken;
            linkedMarucastServerOrigin =
                serverOrigin == null || serverOrigin.isEmpty()
                    ? getServerOrigin()
                    : serverOrigin;
            if (linkedMarucastServerOrigin != null &&
                !linkedMarucastServerOrigin.isEmpty()) {
                HelperStorage.persistServerOrigin(this, linkedMarucastServerOrigin);
            }
            setMarucastPanelMessage("Receiver ready. Broadcast from this phone when you want.");
        }

        showMarucastPanel();

        if ("share-live-relay".equalsIgnoreCase(action) ||
            "start-live-relay".equalsIgnoreCase(action)) {
            setMarucastPanelMessage(
                receiverToken.isEmpty()
                    ? "Opening Android's live capture prompt for Marucast..."
                    : "Opening Android's live capture prompt for the waiting receiver..."
            );
            startMarucastPlaybackRelay();
        }
    }

    private void completeNativeElevationHandoff(String handoffToken, String serverOrigin) {
        if (serverOrigin != null && !serverOrigin.isEmpty()) {
            HelperStorage.persistServerOrigin(this, serverOrigin);
        }
        if (handoffToken.isEmpty()) {
            setElevationStatus("", "Missing Elevation handoff data.");
            showSettingsPanel();
            return;
        }

        elevationBusy = true;
        setElevationStatus("Finishing Elevation in Maru Link...", "");
        showSettingsPanel();

        new Thread(() -> {
            JSONObject response = HelperElevationManager.completeNativeElevationHandoff(
                getApplicationContext(),
                handoffToken
            );
            boolean success = response.optBoolean("success", false);
            String error = trimToEmpty(response.optString("error", ""));
            runOnUiThread(() -> {
                elevationBusy = false;
                if (success) {
                    setElevationStatus("Elevation is ready on this phone.", "");
                } else {
                    setElevationStatus(
                        "",
                        error.isEmpty()
                            ? "Could not finish Elevation in Maru Link."
                            : error
                    );
                }
                showSettingsPanel();
            });
        }).start();
    }

    private void showMarucastPanel() {
        if (bottomNavigationView == null) {
            return;
        }

        if (bottomNavigationView.getSelectedItemId() != R.id.nav_marucast) {
            bottomNavigationView.setSelectedItemId(R.id.nav_marucast);
            return;
        }

        loadFragment(new MarucastFragment());
    }

    private void showSettingsPanel() {
        if (bottomNavigationView == null) {
            return;
        }

        if (bottomNavigationView.getSelectedItemId() != R.id.nav_settings) {
            bottomNavigationView.setSelectedItemId(R.id.nav_settings);
            return;
        }

        loadFragment(new SettingsFragment());
    }

    private void setElevationStatus(String message, String error) {
        elevationStatusMessage = trimToEmpty(message);
        elevationStatusError = trimToEmpty(error);
    }

    private void openExternalUrl(String rawUrl) {
        String url = trimToEmpty(rawUrl);
        if (url.isEmpty()) {
            return;
        }

        try {
            Intent launchIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            launchIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launchIntent);
        } catch (Exception ignored) {
            // Keep the helper visible if Android blocks the handoff.
        }
    }

    private String buildPublicSiteUrl(String rawPath) {
        String siteOrigin = HelperStorage.resolvePublicSiteOrigin(this);
        if (siteOrigin == null || siteOrigin.isEmpty()) {
            return "";
        }

        String path = trimToEmpty(rawPath);
        if (path.isEmpty()) {
            return siteOrigin;
        }
        if (path.startsWith("/")) {
            return siteOrigin + path;
        }
        return siteOrigin + "/" + path;
    }

    private JSONObject postMarucastReceiverRoute(JSONObject payload, String rawServerOrigin) {
        JSONObject failure = new JSONObject();
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        OutputStream outputStream = null;

        try {
            String receiverToken = trimToEmpty(payload.optString("token", ""));
            String serverOrigin = normalizeServerOrigin(rawServerOrigin);
            if (serverOrigin == null || serverOrigin.isEmpty()) {
                serverOrigin = getServerOrigin();
            }
            if (serverOrigin == null || serverOrigin.isEmpty()) {
                throw new IllegalStateException("No Marucast server origin is saved on this phone yet.");
            }
            if (receiverToken.isEmpty()) {
                throw new IllegalStateException("Link a receiver first.");
            }

            URL url = new URL(serverOrigin + "/api/auth");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");

            byte[] requestBody = payload.toString().getBytes(StandardCharsets.UTF_8);
            outputStream = connection.getOutputStream();
            outputStream.write(requestBody);
            outputStream.flush();

            int statusCode = connection.getResponseCode();
            inputStream =
                statusCode >= 200 && statusCode < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            String rawResponse = readStream(inputStream);
            JSONObject response =
                rawResponse.isEmpty()
                    ? new JSONObject()
                    : new JSONObject(rawResponse);
            response.put("_httpStatus", statusCode);
            if (statusCode < 200 || statusCode >= 300) {
                if (!response.has("success")) {
                    response.put("success", false);
                }
                if (!response.has("error")) {
                    response.put("error", "Could not reach the linked Marucast receiver.");
                }
            }
            return response;
        } catch (Exception error) {
            try {
                failure.put("success", false);
                failure.put(
                    "error",
                    error.getMessage() == null || error.getMessage().trim().isEmpty()
                        ? "Could not reach the linked Marucast receiver."
                        : error.getMessage().trim()
                );
            } catch (Exception ignored) {
                // Fallback payload is already best effort.
            }
            return failure;
        } finally {
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception ignored) {
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String extractMarucastReceiverToken(String rawValue) {
        Uri parsed = parseMarucastUri(rawValue, null);
        if (parsed == null) {
            return "";
        }

        String directToken = trimToEmpty(parsed.getQueryParameter("receiverToken"));
        if (!directToken.isEmpty()) {
            return directToken;
        }

        String siteOrigin = normalizeServerOrigin(parsed.getQueryParameter("siteOrigin"));
        String target = trimToEmpty(parsed.getQueryParameter("target"));
        if (target.isEmpty()) {
            return "";
        }

        Uri nested = parseMarucastUri(target, siteOrigin);
        return nested == null
            ? ""
            : trimToEmpty(nested.getQueryParameter("receiverToken"));
    }

    private String extractMarucastServerOrigin(String rawValue) {
        Uri parsed = parseMarucastUri(rawValue, null);
        if (parsed == null) {
            return null;
        }

        String scheme = trimToEmpty(parsed.getScheme()).toLowerCase();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return normalizeServerOrigin(scheme + "://" + trimToEmpty(parsed.getAuthority()));
        }

        String directOrigin = normalizeServerOrigin(parsed.getQueryParameter("siteOrigin"));
        if (directOrigin != null && !directOrigin.isEmpty()) {
            return directOrigin;
        }

        String target = trimToEmpty(parsed.getQueryParameter("target"));
        if (target.isEmpty()) {
            return null;
        }

        Uri nested = parseMarucastUri(target, directOrigin);
        if (nested == null) {
            return null;
        }

        String nestedScheme = trimToEmpty(nested.getScheme()).toLowerCase();
        if (!"http".equals(nestedScheme) && !"https".equals(nestedScheme)) {
            return directOrigin;
        }

        return normalizeServerOrigin(
            nestedScheme + "://" + trimToEmpty(nested.getAuthority())
        );
    }

    private Uri parseMarucastUri(String rawValue, String rawOrigin) {
        String trimmed = trimToEmpty(rawValue);
        if (trimmed.isEmpty()) {
            return null;
        }

        try {
            Uri parsed = Uri.parse(trimmed);
            if (parsed.getScheme() != null) {
                return parsed;
            }
        } catch (Exception ignored) {
            // Fall back to combining against a stored origin below.
        }

        String origin = normalizeServerOrigin(rawOrigin);
        if (origin == null || origin.isEmpty()) {
            return Uri.parse(trimmed);
        }

        if (trimmed.startsWith("/")) {
            return Uri.parse(origin + trimmed);
        }
        return Uri.parse(origin + "/" + trimmed);
    }

    private String normalizeServerOrigin(String rawOrigin) {
        if (rawOrigin == null) {
            return null;
        }

        String trimmed = rawOrigin.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        try {
            Uri parsed = Uri.parse(trimmed);
            String scheme = trimToEmpty(parsed.getScheme()).toLowerCase();
            String host = trimToEmpty(parsed.getHost());
            if (host.isEmpty() || (!"http".equals(scheme) && !"https".equals(scheme))) {
                return null;
            }

            int port = parsed.getPort();
            if (port > 0) {
                return scheme + "://" + host + ":" + port;
            }
            return scheme + "://" + host;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readStream(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
