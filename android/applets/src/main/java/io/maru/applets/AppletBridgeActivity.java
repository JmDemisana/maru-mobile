package io.maru.applets;

import android.content.Intent;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.getcapacitor.BridgeActivity;

/**
 * Base activity for all standalone Maru applet APKs.
 * Blocks launch if Maru Link (io.maru.helper) is not installed.
 */
public abstract class AppletBridgeActivity extends BridgeActivity {
    private static final String LINK_SETTINGS_PANEL_ACCOUNT_SYNC = "account-sync";
    private static final String LINK_AUTHORITY = "io.maru.helper.sharedstate";
    private static final Uri LINK_SHARED_STATE_URI = Uri.parse("content://" + LINK_AUTHORITY);
    private static final String LINK_SHARED_AUTH_METHOD = "getSharedAuthUser";
    private static final String LINK_SERVER_ORIGIN_METHOD = "getServerOrigin";
    private static final String LINK_RESULT_KEY = "value";
    private static final String LINK_SCHEME = "maruhelper";
    private static final String LINK_HOST = "helper";
    private static final String LINK_PACKAGE = "io.maru.helper";

    protected abstract String getAppletDisplayName();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (!isLinkInstalled()) {
            showLinkGate();
            return;
        }
        super.onCreate(savedInstanceState);
        attachAppletBridge();
    }

    private boolean isLinkInstalled() {
        try {
            getPackageManager().getPackageInfo(LINK_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void attachAppletBridge() {
        if (getBridge() == null || getBridge().getWebView() == null) {
            return;
        }
        getBridge().getWebView().addJavascriptInterface(
            new AppletNativeBridge(),
            "AppletNativeBridge"
        );
    }

    private String callLinkSharedState(String method) {
        try {
            ContentResolver resolver = getContentResolver();
            Bundle result = resolver.call(LINK_SHARED_STATE_URI, method, null, null);
            if (result == null) {
                return "";
            }
            String value = result.getString(LINK_RESULT_KEY, "");
            return value == null ? "" : value;
        } catch (Exception ignored) {
            return "";
        }
    }

    private final class AppletNativeBridge {
        @JavascriptInterface
        public String getSharedAuthUser() {
            return callLinkSharedState(LINK_SHARED_AUTH_METHOD);
        }

        @JavascriptInterface
        public String getServerOrigin() {
            return callLinkSharedState(LINK_SERVER_ORIGIN_METHOD);
        }

        @JavascriptInterface
        public void openLinkAccountSync() {
            String serverOrigin = getServerOrigin();
            Uri.Builder builder = new Uri.Builder()
                .scheme(LINK_SCHEME)
                .authority(LINK_HOST)
                .appendQueryParameter("action", "open-settings")
                .appendQueryParameter("panel", LINK_SETTINGS_PANEL_ACCOUNT_SYNC);

            if (serverOrigin != null && !serverOrigin.trim().isEmpty()) {
                builder.appendQueryParameter("siteOrigin", serverOrigin.trim());
            }

            Intent launchIntent = new Intent(Intent.ACTION_VIEW, builder.build());
            launchIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    startActivity(launchIntent);
                } catch (Exception ignored) {
                    // Ignore handoff failures and leave the current app visible.
                }
            });
        }

        @JavascriptInterface
        public void setSharedAuthUser(String rawAuthUser) {
            // Standalone applets mirror shared auth from Maru Link, but never own it.
        }

        @JavascriptInterface
        public void clearSharedAuthUser() {
            // Standalone applets mirror shared auth from Maru Link, but never own it.
        }
    }

    private void showLinkGate() {
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setBackgroundColor(0xFF0A0A0A);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        int pad = dp(28);
        layout.setPadding(pad, pad, pad, pad);
        layout.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("Maru Link Required");
        title.setTextSize(22);
        title.setTextColor(0xFFFFFFFF);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(14));

        TextView body = new TextView(this);
        body.setText(getAppletDisplayName() + " needs Maru Link to run. Maru Link handles downloads, updates, account sync, and cross-app communication for all Maru apps.");
        body.setTextSize(15);
        body.setTextColor(0xFFB0B0B0);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, 0, 0, dp(28));
        body.setLineSpacing(0, 1.45f);

        Button installBtn = new Button(this);
        installBtn.setText("Get Maru Link");
        installBtn.setTextSize(16);
        installBtn.setTextColor(0xFFFFFFFF);
        installBtn.setBackgroundColor(0xFF3B82F6);
        int bp = dp(14);
        installBtn.setPadding(bp, bp, bp, bp);
        installBtn.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        installBtn.setAllCaps(false);
        installBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/JmDemisana/maru-mobile/releases/latest")));
            } catch (Exception ignored) {}
        });

        TextView note = new TextView(this);
        note.setText("Install it, then reopen this app.");
        note.setTextSize(13);
        note.setTextColor(0xFF888888);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(18), 0, 0);

        layout.addView(title);
        layout.addView(body);
        layout.addView(installBtn);
        layout.addView(note);
        scroll.addView(layout);
        setContentView(scroll);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
