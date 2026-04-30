package io.maru.tv;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {
    private static final String CONTROLLER_START_PATH = "/controller/applets?tvApp=1";
    private static final String CONTROLLER_APPLETS_PATH = "/controller/applets?tvApp=1";
    private static final String APPLE_MUSIC_APPLET_PATH = "/applemusic-tv";
    private static final String APPLE_MUSIC_CONTROLLER_PATH = "/controller/applemusic-tv";
    private static final String MARUCAST_APPLET_PATH = "/marucast";
    private static final String MARUCAST_CONTROLLER_PATH = "/controller/marucast";
    private static final String APPLE_MUSIC_START_URL = "https://music.apple.com/us/browse";
    private static final String APPLE_MUSIC_USERSCRIPT_PATH =
        "/downloads/maru-apple-music-tv.user.js";
    private static final String UPDATE_MANIFEST_PATH = "/downloads/maru-tv-update.json";
    private static final String USER_AGENT_SUFFIX = " MaruTV/1.1.9";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final String TV_PERFORMANCE_PROFILE = "performance";
    private static final int TV_INITIAL_SCALE_PERCENT = 110;
    private static final long UPDATE_CHECK_MIN_INTERVAL_MS = 120000L;
    private static final long REMOTE_DUPLICATE_SUPPRESSION_MS = 80L;
    private static final long MARUCAST_PLAYBACK_BASE_ACTIONS =
        PlaybackState.ACTION_PLAY |
        PlaybackState.ACTION_PAUSE |
        PlaybackState.ACTION_PLAY_PAUSE;
    private static final long MARUCAST_PLAYBACK_TRACK_ACTIONS =
        MARUCAST_PLAYBACK_BASE_ACTIONS |
        PlaybackState.ACTION_SKIP_TO_NEXT |
        PlaybackState.ACTION_SKIP_TO_PREVIOUS;

    private final MarucastReceiverManager marucastReceiverManager = new MarucastReceiverManager();
    private final MarucastSenderManager marucastSenderManager = MarucastSenderManager.getInstance();
    private final Object marucastPlaybackLock = new Object();
    private WebView webView;
    private MediaSession marucastMediaSession;
    private BroadcastReceiver updateDownloadReceiver;
    private ActivityResultLauncher<String> marucastRecordAudioPermissionRequest;
    private ActivityResultLauncher<Intent> marucastPlaybackCaptureLauncher;
    private volatile boolean updateCheckInFlight = false;
    private long lastUpdateCheckAtMs = 0L;
    private long pendingUpdateDownloadId = -1L;
    private String pendingUpdateFileName = null;
    private String pendingUpdateApkUrl = null;
    private String lastPromptedUpdateVersion = null;
    private volatile boolean appleMusicMode = false;
    private volatile boolean appleMusicInputMode = false;
    private String lastDispatchedMappedKey = null;
    private int lastDispatchedMappedKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private int lastDispatchedMappedAction = -1;
    private long lastDispatchedMappedAtMs = 0L;
    private MarucastReceiverPlaybackState marucastReceiverPlaybackState =
        new MarucastReceiverPlaybackState();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        marucastReceiverManager.bind(getApplicationContext());
        marucastSenderManager.bind(getApplicationContext());
        initializeMarucastMediaSession();
        marucastRecordAudioPermissionRequest = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            (granted) -> {
                if (Boolean.TRUE.equals(granted)) {
                    launchMarucastPlaybackCapturePrompt();
                    return;
                }

                marucastSenderManager.setLastError(
                    "Marucast needs audio permission before the TV can broadcast."
                );
            }
        );
        marucastPlaybackCaptureLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            (result) -> {
                if (result == null || result.getResultCode() != RESULT_OK || result.getData() == null) {
                    marucastSenderManager.setLastError(
                        "TV broadcast capture was cancelled, so Marucast could not start."
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

        webView = new WebView(this);
        webView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ));
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.setKeepScreenOn(true);
        webView.setBackgroundColor(Color.BLACK);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigationRequest(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                WebView view,
                WebResourceRequest request
            ) {
                if (request == null || !request.isForMainFrame()) {
                    return false;
                }

                Uri requestUri = request.getUrl();
                return handleNavigationRequest(
                    view,
                    requestUri == null ? null : requestUri.toString()
                );
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                appleMusicInputMode = false;
                hideTvKeyboard();
                clearMarucastMediaSessionState();

                if (shouldOpenAppleMusicMode(url)) {
                    openAppleMusicMode();
                    return;
                }

                if (isAppleMusicRemoteSurfaceUrl(url)) {
                    appleMusicMode = true;
                    injectAppleMusicUserscript();
                } else if (isSiteOriginUrl(url)) {
                    appleMusicMode = false;
                    injectNativeCapabilities();
                }

                updateWebViewFocusState(url);
                applyImmersiveMode();
            }
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + USER_AGENT_SUFFIX);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settings.setOffscreenPreRaster(true);
        }
        webView.setInitialScale(TV_INITIAL_SCALE_PERCENT);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }
        webView.addJavascriptInterface(new MaruTvBridge(), "MaruTvBridge");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        setContentView(webView);
        applyImmersiveMode();
        registerUpdateDownloadReceiver();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(getString(R.string.default_site_origin) + CONTROLLER_START_PATH);
        }

        maybeCheckForAppUpdate();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            webView.saveState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode();
        if (webView != null) {
            webView.onResume();
            webView.setKeepScreenOn(true);
            updateWebViewFocusState(webView.getUrl());
        }
        maybeCheckForAppUpdate();
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        marucastReceiverManager.release();
        clearMarucastMediaSessionState();
        if (marucastMediaSession != null) {
            marucastMediaSession.release();
            marucastMediaSession = null;
        }
        if (updateDownloadReceiver != null) {
            unregisterReceiver(updateDownloadReceiver);
            updateDownloadReceiver = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleMarucastMediaKeyEvent(event)) {
            return true;
        }

        if (handleAppleMusicBackKey(event)) {
            return true;
        }

        String mappedKey = mapRemoteKey(event.getKeyCode());
        if (mappedKey == null) {
            return super.dispatchKeyEvent(event);
        }

        if (shouldPassMappedKeyToWebView()) {
            return super.dispatchKeyEvent(event);
        }

        if (shouldSuppressDuplicateMappedKey(event, mappedKey)) {
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            dispatchRemoteKeyboardEvent("keydown", mappedKey, event.getRepeatCount() > 0);
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            dispatchRemoteKeyboardEvent("keyup", mappedKey, false);
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    private void applyImmersiveMode() {
        View decorView = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
            return;
        }

        decorView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    private void initializeMarucastMediaSession() {
        marucastMediaSession = new MediaSession(this, "MaruTvMarucast");
        marucastMediaSession.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
            MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        );
        marucastMediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                dispatchMarucastTransportCommand("play");
            }

            @Override
            public void onPause() {
                dispatchMarucastTransportCommand("pause");
            }

            @Override
            public void onSkipToNext() {
                dispatchMarucastTransportCommand("next");
            }

            @Override
            public void onSkipToPrevious() {
                dispatchMarucastTransportCommand("previous");
            }

            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                if (handleMarucastMediaKeyEvent(getMediaButtonKeyEvent(mediaButtonIntent))) {
                    return true;
                }

                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        });
        marucastMediaSession.setActive(false);
        applyMarucastMediaSessionState();
    }

    private void updateMarucastMediaSessionState(MarucastReceiverPlaybackState nextState) {
        synchronized (marucastPlaybackLock) {
            marucastReceiverPlaybackState =
                nextState == null ? new MarucastReceiverPlaybackState() : nextState;
        }
        applyMarucastMediaSessionState();
    }

    private void clearMarucastMediaSessionState() {
        updateMarucastMediaSessionState(new MarucastReceiverPlaybackState());
    }

    private void applyMarucastMediaSessionState() {
        if (marucastMediaSession == null) {
            return;
        }

        final MarucastReceiverPlaybackState state;
        synchronized (marucastPlaybackLock) {
            state = new MarucastReceiverPlaybackState(marucastReceiverPlaybackState);
        }

        long actions = 0L;
        if (state.connected) {
            actions = state.transportAvailable
                ? MARUCAST_PLAYBACK_TRACK_ACTIONS
                : MARUCAST_PLAYBACK_BASE_ACTIONS;
        }

        int playbackState = !state.connected
            ? PlaybackState.STATE_STOPPED
            : state.playing
                ? PlaybackState.STATE_PLAYING
                : PlaybackState.STATE_PAUSED;
        long playbackPosition = state.positionMs >= 0
            ? state.positionMs
            : PlaybackState.PLAYBACK_POSITION_UNKNOWN;
        float playbackSpeed = state.connected && state.playing ? 1f : 0f;
        marucastMediaSession.setPlaybackState(
            new PlaybackState.Builder()
                .setActions(actions)
                .setState(playbackState, playbackPosition, playbackSpeed)
                .build()
        );

        MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder();
        String metadataTitle = state.title;
        if (metadataTitle == null || metadataTitle.trim().isEmpty()) {
            metadataTitle = state.appLabel;
        }
        if (metadataTitle == null || metadataTitle.trim().isEmpty()) {
            metadataTitle = state.connected ? "Marucast playback" : null;
        }
        if (metadataTitle != null) {
            metadataBuilder.putString(MediaMetadata.METADATA_KEY_TITLE, metadataTitle);
            metadataBuilder.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, metadataTitle);
        }
        if (state.artist != null) {
            metadataBuilder.putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist);
            metadataBuilder.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, state.artist);
        }
        if (state.appLabel != null) {
            metadataBuilder.putString(MediaMetadata.METADATA_KEY_ALBUM, state.appLabel);
            metadataBuilder.putString(
                MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
                state.appLabel
            );
        }
        if (state.durationMs >= 0) {
            metadataBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs);
        }
        marucastMediaSession.setMetadata(metadataBuilder.build());
        marucastMediaSession.setActive(state.connected);
    }

    private boolean handleMarucastMediaKeyEvent(KeyEvent event) {
        if (event == null) {
            return false;
        }

        String command = mapMarucastMediaCommand(event.getKeyCode());
        if (command == null || !canDispatchMarucastTransportCommand(command)) {
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() <= 0) {
                dispatchMarucastTransportCommand(command);
            }
            return true;
        }

        return event.getAction() == KeyEvent.ACTION_UP;
    }

    private String mapMarucastMediaCommand(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return "play";
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return "pause";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                return "toggle";
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return "next";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return "previous";
            default:
                return null;
        }
    }

    private boolean canDispatchMarucastTransportCommand(String command) {
        if (command == null || command.trim().isEmpty() || webView == null) {
            return false;
        }

        if (!isMarucastSurfaceUrl(webView.getUrl())) {
            return false;
        }

        synchronized (marucastPlaybackLock) {
            if (!marucastReceiverPlaybackState.connected) {
                return false;
            }

            return (
                !"next".equals(command) &&
                !"previous".equals(command)
            ) ||
                marucastReceiverPlaybackState.transportAvailable;
        }
    }

    private void dispatchMarucastTransportCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return;
        }

        runOnUiThread(() -> {
            if (!canDispatchMarucastTransportCommand(command)) {
                return;
            }

            String quotedCommand = JSONObject.quote(command);
            String script =
                "(function(command){" +
                    "window.dispatchEvent(new CustomEvent('maru-tv-marucast-transport',{" +
                        "detail:{command:command}" +
                    "}));" +
                "})(" + quotedCommand + ");";
            webView.evaluateJavascript(script, null);
        });
    }

    private void adjustMarucastTvVolume(String rawDirection) {
        if (rawDirection == null || webView == null || !isMarucastSurfaceUrl(webView.getUrl())) {
            return;
        }

        final int adjustment;
        String direction = rawDirection.trim();
        if ("up".equals(direction) || "raise".equals(direction)) {
            adjustment = AudioManager.ADJUST_RAISE;
        } else if ("down".equals(direction) || "lower".equals(direction)) {
            adjustment = AudioManager.ADJUST_LOWER;
        } else {
            return;
        }

        synchronized (marucastPlaybackLock) {
            if (!marucastReceiverPlaybackState.connected) {
                return;
            }
        }

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }

        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            adjustment,
            AudioManager.FLAG_SHOW_UI
        );
    }

    @SuppressWarnings("deprecation")
    private KeyEvent getMediaButtonKeyEvent(Intent mediaButtonIntent) {
        if (mediaButtonIntent == null) {
            return null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
        }

        return mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
    }

    private String mapRemoteKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return "ArrowUp";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "ArrowDown";
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return "ArrowLeft";
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return "ArrowRight";
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                return "Enter";
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_ESCAPE:
                return "Backspace";
            default:
                return null;
        }
    }

    private boolean isMarucastSurfaceUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            return MARUCAST_APPLET_PATH.equals(path) || MARUCAST_CONTROLLER_PATH.equals(path);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean handleNavigationRequest(WebView view, String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        if (shouldOpenAppleMusicMode(url)) {
            openAppleMusicMode();
            return true;
        }

        if (isSiteOriginUrl(url)) {
            appleMusicMode = false;
            appleMusicInputMode = false;
            updateWebViewFocusState(url);
        }

        return false;
    }

    private boolean shouldOpenAppleMusicMode(String url) {
        if (!isSiteOriginUrl(url)) {
            return false;
        }

        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        return APPLE_MUSIC_APPLET_PATH.equals(path) ||
            APPLE_MUSIC_CONTROLLER_PATH.equals(path);
    }

    private boolean isSiteOriginUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        return url.startsWith(getString(R.string.default_site_origin));
    }

    private boolean isAppleMusicRemoteSurfaceUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host == null) {
            return false;
        }

        return "music.apple.com".equalsIgnoreCase(host) ||
            "beta.music.apple.com".equalsIgnoreCase(host);
    }

    private void openAppleMusicMode() {
        if (webView == null) {
            return;
        }

        appleMusicMode = true;
        appleMusicInputMode = false;
        hideTvKeyboard();
        updateWebViewFocusState(null);
        webView.loadUrl(APPLE_MUSIC_START_URL);
    }

    private void exitAppleMusicModeToApplets() {
        if (webView == null) {
            return;
        }

        appleMusicMode = false;
        appleMusicInputMode = false;
        hideTvKeyboard();
        updateWebViewFocusState(resolveAbsoluteUrl(CONTROLLER_APPLETS_PATH));
        webView.loadUrl(resolveAbsoluteUrl(CONTROLLER_APPLETS_PATH));
    }

    private boolean shouldPassMappedKeyToWebView() {
        if (!appleMusicMode || webView == null) {
            return false;
        }

        if (appleMusicInputMode) {
            return true;
        }

        String currentUrl = webView.getUrl();
        return currentUrl != null && !isAppleMusicRemoteSurfaceUrl(currentUrl);
    }

    private boolean isAppleMusicBackKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_BUTTON_B ||
            keyCode == KeyEvent.KEYCODE_ESCAPE;
    }

    private boolean handleAppleMusicBackKey(KeyEvent event) {
        if (!appleMusicMode || !isAppleMusicBackKey(event.getKeyCode())) {
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            return true;
        }

        if (event.getAction() != KeyEvent.ACTION_UP) {
            return true;
        }

        if (appleMusicInputMode) {
            exitAppleMusicInputMode(true);
            return true;
        }

        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }

        exitAppleMusicModeToApplets();
        return true;
    }

    private void dispatchRemoteKeyboardEvent(String eventType, String key, boolean repeat) {
        if (webView == null) {
            return;
        }

        String quotedType = JSONObject.quote(eventType);
        String quotedKey = JSONObject.quote(key);
        String script =
            "(function(type, key, repeat) {" +
                "var detail = { key: key, code: key, bubbles: true, cancelable: true, repeat: repeat };" +
                "window.dispatchEvent(new KeyboardEvent(type, detail));" +
            "})(" + quotedType + "," + quotedKey + "," + (repeat ? "true" : "false") + ");";

        webView.evaluateJavascript(script, null);
    }

    private boolean shouldSuppressDuplicateMappedKey(KeyEvent event, String mappedKey) {
        if (event == null || mappedKey == null || event.getRepeatCount() > 0) {
            return false;
        }

        boolean shouldSuppress =
            mappedKey.equals(lastDispatchedMappedKey) &&
            event.getAction() == lastDispatchedMappedAction &&
            event.getEventTime() - lastDispatchedMappedAtMs <= REMOTE_DUPLICATE_SUPPRESSION_MS;

        lastDispatchedMappedKey = mappedKey;
        lastDispatchedMappedKeyCode = event.getKeyCode();
        lastDispatchedMappedAction = event.getAction();
        lastDispatchedMappedAtMs = event.getEventTime();
        return shouldSuppress;
    }

    private void injectNativeCapabilities() {
        if (webView == null) {
            return;
        }

        String script =
            "(function(){" +
                "var caps={" +
                    "canCheckForAppUpdate:true," +
                    "canUseMarucast:true," +
                    "canBroadcastMarucast:true," +
                    "appVersion:" + JSONObject.quote(getCurrentAppVersion()) + "," +
                    "performanceProfile:" + JSONObject.quote(TV_PERFORMANCE_PROFILE) + "," +
                    "preferReducedMotion:true," +
                    "preferReducedTransparency:true," +
                    "recommendedInitialScale:" + TV_INITIAL_SCALE_PERCENT + "," +
                    "renderer:" + JSONObject.quote("hardware") +
                "};" +
                "window.__MaruTvNativeCapabilities__=caps;" +
                "window.dispatchEvent(new CustomEvent('maru-tv-native-ready',{detail:caps}));" +
            "})();";
        webView.evaluateJavascript(script, null);
    }

    private void injectAppleMusicUserscript() {
        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL scriptUrl = new URL(resolveAbsoluteUrl(APPLE_MUSIC_USERSCRIPT_PATH));
                connection = (HttpURLConnection) scriptUrl.openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setUseCaches(false);

                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
                )) {
                    StringBuilder scriptBuilder = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        scriptBuilder.append(line).append('\n');
                    }

                    String scriptPayload = scriptBuilder.toString();

                    runOnUiThread(() -> {
                        if (webView == null) {
                            return;
                        }

                        String currentUrl = webView.getUrl();
                        if (!isAppleMusicRemoteSurfaceUrl(currentUrl)) {
                            return;
                        }

                        String bootstrap =
                            "window.__MARU_APPLE_MUSIC_SITE_ORIGIN__=" +
                                JSONObject.quote(getString(R.string.default_site_origin)) +
                                ";";
                        webView.evaluateJavascript(bootstrap + "\n" + scriptPayload, null);
                    });
                }
            } catch (Exception ignored) {
                // Quiet failure keeps Apple Music usable even if the helper script is unavailable.
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private void setWebViewNavigationFocusEnabled(boolean enabled) {
        if (webView == null) {
            return;
        }

        webView.setFocusable(enabled);
        webView.setFocusableInTouchMode(enabled);

        if (!enabled) {
            webView.clearFocus();
            return;
        }

        webView.requestFocus(View.FOCUS_DOWN);
    }

    private void updateWebViewFocusState(String url) {
        boolean shouldEnableNativeFocus =
            appleMusicInputMode || (appleMusicMode && !isAppleMusicRemoteSurfaceUrl(url));
        setWebViewNavigationFocusEnabled(shouldEnableNativeFocus);
    }

    private void launchMarucastPlaybackCapturePrompt() {
        MediaProjectionManager mediaProjectionManager =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mediaProjectionManager == null || marucastPlaybackCaptureLauncher == null) {
            marucastSenderManager.setLastError(
                "This TV build could not open Android's capture prompt."
            );
            return;
        }

        try {
            marucastPlaybackCaptureLauncher.launch(
                mediaProjectionManager.createScreenCaptureIntent()
            );
        } catch (Exception error) {
            marucastSenderManager.setLastError(
                "Android would not open the TV capture prompt right now."
            );
        }
    }

    private void showTvKeyboard() {
        if (webView == null) {
            return;
        }

        webView.post(() -> {
            setWebViewNavigationFocusEnabled(true);
            InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.showSoftInput(
                    webView,
                    InputMethodManager.SHOW_IMPLICIT
                );
            }
        });
    }

    private void hideTvKeyboard() {
        if (webView == null) {
            return;
        }

        webView.post(() -> {
            InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.hideSoftInputFromWindow(webView.getWindowToken(), 0);
            }
        });
    }

    private void exitAppleMusicInputMode(boolean blurActiveElement) {
        appleMusicInputMode = false;
        hideTvKeyboard();
        updateWebViewFocusState(webView == null ? null : webView.getUrl());

        if (!blurActiveElement || webView == null) {
            return;
        }

        webView.evaluateJavascript(
            "(function(){var active=document.activeElement;" +
                "if(active&&typeof active.blur==='function'){active.blur();}}" +
            ")();",
            null
        );
    }

    private void maybeCheckForAppUpdate() {
        long now = System.currentTimeMillis();
        if (updateCheckInFlight || now - lastUpdateCheckAtMs < UPDATE_CHECK_MIN_INTERVAL_MS) {
            return;
        }

        lastUpdateCheckAtMs = now;
        checkForAppUpdate(false);
    }

    private void checkForAppUpdate(boolean userInitiated) {
        lastUpdateCheckAtMs = System.currentTimeMillis();
        updateCheckInFlight = true;

        new Thread(() -> {
            HttpURLConnection connection = null;
            String completionMessage = null;

            try {
                URL manifestUrl = new URL(resolveAbsoluteUrl(UPDATE_MANIFEST_PATH));
                connection = (HttpURLConnection) manifestUrl.openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setUseCaches(false);

                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
                )) {
                    StringBuilder payloadBuilder = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        payloadBuilder.append(line);
                    }

                    JSONObject json = new JSONObject(payloadBuilder.toString());
                    String nextVersion = json.optString("version");
                    String apkPath = json.optString("apkPath");

                    if (nextVersion.isEmpty() || apkPath.isEmpty()) {
                        if (userInitiated) {
                            completionMessage = "Maru TV could not read the update manifest.";
                        }
                        return;
                    }

                    String currentVersion = getCurrentAppVersion();
                    if (compareVersions(nextVersion, currentVersion) <= 0) {
                        if (userInitiated) {
                            completionMessage = "Maru TV is already up to date.";
                        }
                        return;
                    }

                    if (nextVersion.equals(lastPromptedUpdateVersion)) {
                        if (userInitiated) {
                            completionMessage =
                                "This TV already has the latest update prompt open.";
                        }
                        return;
                    }

                    String apkUrl = resolveAbsoluteUrl(apkPath);
                    String apkFileName = json.optString("apkFileName");
                    String releaseNotes = json.optString("releaseNotes");
                    if (apkFileName.isEmpty()) {
                        apkFileName = deriveFileNameFromUrl(apkUrl);
                    }

                    UpdateInfo updateInfo = new UpdateInfo(
                        nextVersion,
                        apkUrl,
                        apkFileName,
                        releaseNotes
                    );

                    runOnUiThread(() -> showUpdateDialog(updateInfo));
                }
            } catch (Exception ignored) {
                if (userInitiated) {
                    completionMessage = "Maru TV could not check for updates right now.";
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                updateCheckInFlight = false;

                if (completionMessage != null) {
                    String toastMessage = completionMessage;
                    runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, toastMessage, Toast.LENGTH_SHORT).show()
                    );
                }
            }
        }).start();
    }

    private void showUpdateDialog(UpdateInfo updateInfo) {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        lastPromptedUpdateVersion = updateInfo.version;

        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder
            .append("Maru TV ")
            .append(updateInfo.version)
            .append(" is ready.");

        if (!updateInfo.releaseNotes.isEmpty()) {
            messageBuilder
                .append("\n\n")
                .append(updateInfo.releaseNotes);
        }

        messageBuilder.append("\n\nInstall it now from inside the app?");

        new AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage(messageBuilder.toString())
            .setPositiveButton("Install update", (dialog, which) ->
                beginUpdateDownload(updateInfo)
            )
            .setNegativeButton("Later", null)
            .show();
    }

    private void beginUpdateDownload(UpdateInfo updateInfo) {
        if (pendingUpdateDownloadId != -1L) {
            Toast.makeText(this, "An update is already downloading.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !getPackageManager().canRequestPackageInstalls()) {
            openUnknownAppsSettings();
            return;
        }

        DownloadManager downloadManager =
            (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        File externalDownloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadManager == null || externalDownloadsDir == null) {
            openUrlExternally(updateInfo.apkUrl);
            return;
        }

        File targetFile = new File(externalDownloadsDir, updateInfo.apkFileName);
        if (targetFile.exists() && !targetFile.delete()) {
            Toast.makeText(this, "Could not prepare the update download.", Toast.LENGTH_SHORT).show();
            return;
        }

        DownloadManager.Request request =
            new DownloadManager.Request(Uri.parse(updateInfo.apkUrl));
        request.setTitle("Maru TV update");
        request.setDescription("Downloading " + updateInfo.version);
        request.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        );
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(true);
        request.setMimeType(APK_MIME_TYPE);
        request.setDestinationInExternalFilesDir(
            this,
            Environment.DIRECTORY_DOWNLOADS,
            updateInfo.apkFileName
        );

        pendingUpdateFileName = updateInfo.apkFileName;
        pendingUpdateApkUrl = updateInfo.apkUrl;
        pendingUpdateDownloadId = downloadManager.enqueue(request);
        Toast.makeText(this, "Maru TV update is downloading.", Toast.LENGTH_SHORT).show();
    }

    private void registerUpdateDownloadReceiver() {
        if (updateDownloadReceiver != null) {
            return;
        }

        updateDownloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    return;
                }

                long completedDownloadId = intent.getLongExtra(
                    DownloadManager.EXTRA_DOWNLOAD_ID,
                    -1L
                );
                if (completedDownloadId != pendingUpdateDownloadId) {
                    return;
                }

                handleCompletedUpdateDownload(completedDownloadId);
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateDownloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            return;
        }

        registerReceiver(updateDownloadReceiver, filter);
    }

    private void handleCompletedUpdateDownload(long downloadId) {
        DownloadManager downloadManager =
            (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            pendingUpdateDownloadId = -1L;
            return;
        }

        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                Toast.makeText(this, "The update download could not be verified.", Toast.LENGTH_SHORT).show();
                return;
            }

            int status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            );
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                installDownloadedUpdate();
                return;
            }

            Toast.makeText(this, "The update download did not finish.", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            Toast.makeText(this, "The update download could not be opened.", Toast.LENGTH_SHORT).show();
        } finally {
            pendingUpdateDownloadId = -1L;
        }
    }

    private void installDownloadedUpdate() {
        File externalDownloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (externalDownloadsDir == null || pendingUpdateFileName == null) {
            openUrlExternally(pendingUpdateApkUrl);
            return;
        }

        File apkFile = new File(externalDownloadsDir, pendingUpdateFileName);
        if (!apkFile.exists()) {
            openUrlExternally(pendingUpdateApkUrl);
            return;
        }

        Uri apkUri = FileProvider.getUriForFile(
            this,
            getPackageName() + ".fileprovider",
            apkFile
        );

        Intent installIntent = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(installIntent);
        } catch (ActivityNotFoundException error) {
            openUrlExternally(pendingUpdateApkUrl);
        }
    }

    private void openUnknownAppsSettings() {
        Intent settingsIntent = new Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + getPackageName())
        );
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            startActivity(settingsIntent);
            Toast.makeText(
                this,
                "Allow installs for Maru TV, then run the update again.",
                Toast.LENGTH_LONG
            ).show();
        } catch (ActivityNotFoundException error) {
            Toast.makeText(
                this,
                "This device could not open the install permission screen.",
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void openUrlExternally(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "Could not open the update link.", Toast.LENGTH_SHORT).show();
        }
    }

    private String resolveAbsoluteUrl(String pathOrUrl) {
        if (pathOrUrl.startsWith("https://") || pathOrUrl.startsWith("http://")) {
            return pathOrUrl;
        }

        String origin = getString(R.string.default_site_origin);
        if (pathOrUrl.startsWith("/")) {
            return origin + pathOrUrl;
        }

        return origin + "/" + pathOrUrl;
    }

    private String deriveFileNameFromUrl(String url) {
        Uri uri = Uri.parse(url);
        String lastPathSegment = uri.getLastPathSegment();
        if (lastPathSegment == null || lastPathSegment.isEmpty()) {
            return "maru-tv-update.apk";
        }

        return lastPathSegment;
    }

    private String getCurrentAppVersion() {
        try {
            return getPackageManager()
                .getPackageInfo(getPackageName(), 0)
                .versionName;
        } catch (Exception ignored) {
            return "0.0.0";
        }
    }

    private int compareVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int maxLength = Math.max(leftParts.length, rightParts.length);

        for (int index = 0; index < maxLength; index += 1) {
            int leftValue = parseVersionPart(leftParts, index);
            int rightValue = parseVersionPart(rightParts, index);

            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }

        return 0;
    }

    private int parseVersionPart(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }

        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private final class MaruTvBridge {
        @JavascriptInterface
        public void enterInputMode() {
            runOnUiThread(() -> {
                appleMusicInputMode = true;
                showTvKeyboard();
            });
        }

        @JavascriptInterface
        public void exitInputMode() {
            runOnUiThread(() -> exitAppleMusicInputMode(false));
        }

        @JavascriptInterface
        public void checkForAppUpdate() {
            runOnUiThread(() -> {
                if (updateCheckInFlight) {
                    Toast.makeText(
                        MainActivity.this,
                        "Maru TV is already checking for updates.",
                        Toast.LENGTH_SHORT
                    ).show();
                    return;
                }

                MainActivity.this.checkForAppUpdate(true);
            });
        }

        @JavascriptInterface
        public String getMarucastStatus() {
            return marucastReceiverManager.getStatusJson();
        }

        @JavascriptInterface
        public String getMarucastBroadcastStatus() {
            return marucastSenderManager.getStatusJson();
        }

        @JavascriptInterface
        public void startMarucastBroadcast() {
            runOnUiThread(() -> {
                if (ContextCompat.checkSelfPermission(
                    MainActivity.this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED) {
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
        public String stopMarucastBroadcast() {
            MarucastCaptureForegroundService.stop(MainActivity.this);
            marucastSenderManager.stopPlaybackCapture();
            return marucastSenderManager.getStatusJson();
        }

        @JavascriptInterface
        public void updateMarucastReceiverPlayback(String rawPayload) {
            runOnUiThread(() ->
                updateMarucastMediaSessionState(
                    MarucastReceiverPlaybackState.fromJson(rawPayload)
                )
            );
        }

        @JavascriptInterface
        public void clearMarucastReceiverPlayback() {
            runOnUiThread(() -> clearMarucastMediaSessionState());
        }

        @JavascriptInterface
        public void adjustMarucastTvVolume(String rawDirection) {
            runOnUiThread(() -> MainActivity.this.adjustMarucastTvVolume(rawDirection));
        }

        @JavascriptInterface
        public void startMarucastDiscovery() {
            marucastReceiverManager.startDiscovery();
        }

        @JavascriptInterface
        public void refreshMarucastDiscovery() {
            marucastReceiverManager.refreshDiscovery();
        }

        @JavascriptInterface
        public void stopMarucastDiscovery() {
            marucastReceiverManager.stopDiscovery();
        }
    }

    private static class UpdateInfo {
        final String version;
        final String apkUrl;
        final String apkFileName;
        final String releaseNotes;

        UpdateInfo(String version, String apkUrl, String apkFileName, String releaseNotes) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.apkFileName = apkFileName;
            this.releaseNotes = releaseNotes == null ? "" : releaseNotes;
        }
    }

    private static final class MarucastReceiverPlaybackState {
        boolean connected = false;
        boolean playing = false;
        boolean transportAvailable = false;
        String title = null;
        String artist = null;
        String appLabel = null;
        long positionMs = PlaybackState.PLAYBACK_POSITION_UNKNOWN;
        long durationMs = -1L;

        MarucastReceiverPlaybackState() {}

        MarucastReceiverPlaybackState(MarucastReceiverPlaybackState other) {
            if (other == null) {
                return;
            }

            connected = other.connected;
            playing = other.playing;
            transportAvailable = other.transportAvailable;
            title = other.title;
            artist = other.artist;
            appLabel = other.appLabel;
            positionMs = other.positionMs;
            durationMs = other.durationMs;
        }

        static MarucastReceiverPlaybackState fromJson(String rawPayload) {
            MarucastReceiverPlaybackState next = new MarucastReceiverPlaybackState();
            if (rawPayload == null || rawPayload.trim().isEmpty()) {
                return next;
            }

            try {
                JSONObject payload = new JSONObject(rawPayload);
                next.connected = payload.optBoolean("connected", false);
                next.playing = payload.optBoolean("playing", false);
                next.transportAvailable = payload.optBoolean("transportAvailable", false);
                next.title = optString(payload, "title");
                next.artist = optString(payload, "artist");
                next.appLabel = optString(payload, "appLabel");
                next.positionMs = optLong(payload, "positionMs");
                next.durationMs = optLong(payload, "durationMs");
            } catch (Exception ignored) {
                return new MarucastReceiverPlaybackState();
            }

            return next;
        }

        private static String optString(JSONObject payload, String key) {
            String value = payload.optString(key, "").trim();
            return value.isEmpty() ? null : value;
        }

        private static long optLong(JSONObject payload, String key) {
            if (!payload.has(key) || payload.isNull(key)) {
                return PlaybackState.PLAYBACK_POSITION_UNKNOWN;
            }

            long value = payload.optLong(key, PlaybackState.PLAYBACK_POSITION_UNKNOWN);
            return value >= 0 ? value : PlaybackState.PLAYBACK_POSITION_UNKNOWN;
        }
    }
}
