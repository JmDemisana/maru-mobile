package io.maru.applets;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

/**
 * Base activity for all standalone Maru applet APKs.
 * Blocks launch if Maru Link (io.maru.link) is not installed.
 */
public abstract class AppletBridgeActivity extends BridgeActivity {

    private static final String LINK_PACKAGE = "io.maru.link";

    protected abstract String getAppletDisplayName();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (!isLinkInstalled()) {
            showLinkGate();
            return;
        }
        super.onCreate(savedInstanceState);
    }

    private boolean isLinkInstalled() {
        try {
            getPackageManager().getPackageInfo(LINK_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
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
