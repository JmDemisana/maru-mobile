package io.maru.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class HelperStorage {
    private static final String DEFAULT_SITE_ORIGIN = "https://maru-website.onrender.com";
    private static final String KEY_INSTALLATION_ID = "helper-installation-id";
    private static final String KEY_LAST_RENDER_NUDGE_CHECK_AT =
        "helper-last-render-nudge-check-at";
    private static final String KEY_SHARED_AUTH_USER = "helper-shared-auth-user";
    private static final String KEY_SERVER_ORIGIN = "helper-server-origin";
    private static final String PREFS_NAME = "maru-helper-native";

    private HelperStorage() {
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String getInstallationId(Context context) {
        return getPrefs(context).getString(KEY_INSTALLATION_ID, "").trim();
    }

    public static void persistInstallationId(Context context, String rawInstallationId) {
        String installationId = rawInstallationId == null ? "" : rawInstallationId.trim();
        if (installationId.isEmpty()) {
            getPrefs(context)
                .edit()
                .remove(KEY_INSTALLATION_ID)
                .remove(KEY_LAST_RENDER_NUDGE_CHECK_AT)
                .apply();
            return;
        }

        getPrefs(context).edit().putString(KEY_INSTALLATION_ID, installationId).apply();
    }

    public static String getStoredServerOrigin(Context context) {
        return normalizeServerOrigin(getPrefs(context).getString(KEY_SERVER_ORIGIN, ""));
    }

    public static void persistServerOrigin(Context context, String rawServerOrigin) {
        String serverOrigin = normalizeServerOrigin(rawServerOrigin);
        if (serverOrigin == null || serverOrigin.isEmpty()) {
            getPrefs(context).edit().remove(KEY_SERVER_ORIGIN).apply();
            return;
        }

        getPrefs(context).edit().putString(KEY_SERVER_ORIGIN, serverOrigin).apply();
    }

    public static String getSharedAuthUser(Context context) {
        String stored = getPrefs(context).getString(KEY_SHARED_AUTH_USER, "");
        return stored == null ? "" : stored.trim();
    }

    public static void persistSharedAuthUser(Context context, String rawAuthUser) {
        String authUser = rawAuthUser == null ? "" : rawAuthUser.trim();
        if (authUser.isEmpty()) {
            getPrefs(context).edit().remove(KEY_SHARED_AUTH_USER).apply();
            return;
        }

        getPrefs(context).edit().putString(KEY_SHARED_AUTH_USER, authUser).apply();
    }

    public static long getLastRenderNudgeCheckAt(Context context) {
        return Math.max(0L, getPrefs(context).getLong(KEY_LAST_RENDER_NUDGE_CHECK_AT, 0L));
    }

    public static void persistLastRenderNudgeCheckAt(Context context, long rawTimestampMs) {
        if (rawTimestampMs <= 0L) {
            getPrefs(context).edit().remove(KEY_LAST_RENDER_NUDGE_CHECK_AT).apply();
            return;
        }

        getPrefs(context)
            .edit()
            .putLong(KEY_LAST_RENDER_NUDGE_CHECK_AT, rawTimestampMs)
            .apply();
    }

    public static String resolveDetectorServerOrigin(Context context) {
        String storedOrigin = getStoredServerOrigin(context);
        if (storedOrigin == null || storedOrigin.isEmpty()) {
            return DEFAULT_SITE_ORIGIN;
        }

        return shouldUseProductionFallback(storedOrigin)
            ? DEFAULT_SITE_ORIGIN
            : storedOrigin;
    }

    private static String normalizeServerOrigin(String rawServerOrigin) {
        if (rawServerOrigin == null) {
            return null;
        }

        String trimmedOrigin = rawServerOrigin.trim();
        if (trimmedOrigin.isEmpty()) {
            return null;
        }

        try {
            Uri parsed = Uri.parse(trimmedOrigin);
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            if (scheme == null || host == null) {
                return null;
            }

            String normalizedScheme = scheme.trim().toLowerCase();
            if (!normalizedScheme.equals("http") && !normalizedScheme.equals("https")) {
                return null;
            }

            int port = parsed.getPort();
            String normalizedHost = host.trim();
            if (port > 0) {
                return normalizedScheme + "://" + normalizedHost + ":" + port;
            }

            return normalizedScheme + "://" + normalizedHost;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean shouldUseProductionFallback(String serverOrigin) {
        try {
            Uri parsed = Uri.parse(serverOrigin);
            String host = parsed.getHost();
            if (host == null) {
                return true;
            }

            String normalizedHost = host.trim().toLowerCase();
            if (normalizedHost.equals("localhost") ||
                normalizedHost.equals("::1") ||
                normalizedHost.startsWith("127.")) {
                return true;
            }
        } catch (Exception ignored) {
            return true;
        }

        return false;
    }
}
