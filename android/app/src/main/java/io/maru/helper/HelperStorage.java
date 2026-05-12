package io.maru.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.util.UUID;

public final class HelperStorage {
    private static final String DEFAULT_API_ORIGIN = "https://maru-website.onrender.com";
    private static final String DEFAULT_PUBLIC_SITE_ORIGIN =
        "https://maruchansquigle.vercel.app";
    private static final String KEY_ELEVATION_LASTFM_ENABLED =
        "helper-elevation-lastfm-enabled";
    private static final String KEY_ELEVATION_TOKEN = "helper-elevation-token";
    private static final String KEY_INSTALLATION_ID = "helper-installation-id";
    private static final String KEY_LAST_RENDER_NUDGE_CHECK_AT =
        "helper-last-render-nudge-check-at";
    private static final String KEY_SHARED_AUTH_USER = "helper-shared-auth-user";
    private static final String KEY_SERVER_ORIGIN = "helper-server-origin";
    private static final String PREFS_NAME = "maru-helper-native";
    private static final String SECURE_PREFS_NAME = "maru-helper-secure";
    private static final String TAG = "HelperStorage";

    private HelperStorage() {
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static SharedPreferences getSecurePrefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
            return EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception error) {
            Log.e(TAG, "Couldn't open secure helper storage.", error);
            return null;
        }
    }

    public static String getInstallationId(Context context) {
        return getPrefs(context).getString(KEY_INSTALLATION_ID, "").trim();
    }

    public static String ensureInstallationId(Context context) {
        String installationId = getInstallationId(context);
        if (!installationId.isEmpty()) {
            return installationId;
        }

        String nextInstallationId = UUID.randomUUID().toString();
        persistInstallationId(context, nextInstallationId);
        return nextInstallationId;
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

    public static boolean isElevationLastFmEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_ELEVATION_LASTFM_ENABLED, false);
    }

    public static void persistElevationLastFmEnabled(Context context, boolean enabled) {
        getPrefs(context)
            .edit()
            .putBoolean(KEY_ELEVATION_LASTFM_ENABLED, enabled)
            .apply();
    }

    public static String getElevationToken(Context context) {
        SharedPreferences securePrefs = getSecurePrefs(context);
        if (securePrefs == null) {
            return "";
        }

        String token = securePrefs.getString(KEY_ELEVATION_TOKEN, "");
        return token == null ? "" : token.trim();
    }

    public static boolean canUseSecureElevationStorage(Context context) {
        return getSecurePrefs(context) != null;
    }

    public static boolean persistElevationToken(Context context, String rawToken) {
        SharedPreferences securePrefs = getSecurePrefs(context);
        if (securePrefs == null) {
            return false;
        }

        String token = rawToken == null ? "" : rawToken.trim();
        if (token.isEmpty()) {
            securePrefs.edit().remove(KEY_ELEVATION_TOKEN).apply();
            return true;
        }

        securePrefs.edit().putString(KEY_ELEVATION_TOKEN, token).apply();
        return true;
    }

    public static void clearElevationState(Context context) {
        SharedPreferences securePrefs = getSecurePrefs(context);
        if (securePrefs != null) {
            securePrefs.edit().remove(KEY_ELEVATION_TOKEN).apply();
        }
        persistElevationLastFmEnabled(context, false);
    }

    public static String resolveDetectorServerOrigin(Context context) {
        String storedOrigin = getStoredServerOrigin(context);
        if (storedOrigin == null || storedOrigin.isEmpty()) {
            return DEFAULT_API_ORIGIN;
        }

        return shouldUseProductionFallback(storedOrigin) ||
            shouldUseDetectorOriginFallback(storedOrigin)
            ? DEFAULT_API_ORIGIN
            : storedOrigin;
    }

    public static String resolvePublicSiteOrigin(Context context) {
        String storedOrigin = getStoredServerOrigin(context);
        if (storedOrigin == null || storedOrigin.isEmpty()) {
            return DEFAULT_PUBLIC_SITE_ORIGIN;
        }

        if (shouldUseProductionFallback(storedOrigin) || shouldUsePublicSiteFallback(storedOrigin)) {
            return DEFAULT_PUBLIC_SITE_ORIGIN;
        }

        return storedOrigin;
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

    private static boolean shouldUsePublicSiteFallback(String serverOrigin) {
        try {
            Uri parsed = Uri.parse(serverOrigin);
            String host = parsed.getHost();
            if (host == null) {
                return true;
            }

            String normalizedHost = host.trim().toLowerCase();
            return normalizedHost.equals("maru-website.onrender.com");
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean shouldUseDetectorOriginFallback(String serverOrigin) {
        try {
            Uri parsed = Uri.parse(serverOrigin);
            String host = parsed.getHost();
            if (host == null) {
                return true;
            }

            Uri publicSite = Uri.parse(DEFAULT_PUBLIC_SITE_ORIGIN);
            String publicHost = publicSite.getHost();
            if (publicHost == null) {
                return false;
            }

            return host.trim().equalsIgnoreCase(publicHost.trim());
        } catch (Exception ignored) {
            return true;
        }
    }
}
