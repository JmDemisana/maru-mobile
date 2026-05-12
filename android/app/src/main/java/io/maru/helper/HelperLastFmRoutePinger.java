package io.maru.helper;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

public final class HelperLastFmRoutePinger {
    private static final long EXTERNAL_BACKEND_NUDGE_INTERVAL_MS = 15L * 60L * 1000L;
    private static final int REQUEST_TIMEOUT_MS = 15000;

    private HelperLastFmRoutePinger() {
    }

    public static boolean pingNowPlayingRoute(Context context) throws Exception {
        HelperMediaSessionMonitor mediaSessionMonitor = HelperMediaSessionMonitor.getInstance();
        String localTrackKey = mediaSessionMonitor.getCurrentTrackKey();
        if (mediaSessionMonitor.isPlaybackActive() && !localTrackKey.isEmpty()) {
            // Local playback already drives the smart-band alert, so don't let the
            // Last.fm fallback path double-post the same song from the server.
            return true;
        }

        String installationId = HelperStorage.getInstallationId(context);
        String serverOrigin = HelperStorage.resolveDetectorServerOrigin(context);

        if (installationId.isEmpty() || serverOrigin.isEmpty()) {
            return false;
        }

        String encodedInstallationId = URLEncoder.encode(
            installationId,
            StandardCharsets.UTF_8.toString()
        );
        URL requestUrl = new URL(
            serverOrigin
                + "/api/auth?route=companion/lastfm-detector-ping&installationId="
                + encodedInstallationId
                + "&ts="
                + System.currentTimeMillis()
        );

        JSONObject detectorPayload = readJsonResponse(requestUrl);
        if (detectorPayload == null || !detectorPayload.optBoolean("active", false)) {
            return false;
        }

        maybeNudgeExternalBackend(context, serverOrigin);
        return true;
    }

    private static void maybeNudgeExternalBackend(Context context, String serverOrigin)
        throws Exception {
        long now = System.currentTimeMillis();
        long lastCheckAt = HelperStorage.getLastRenderNudgeCheckAt(context);
        if (now - lastCheckAt < EXTERNAL_BACKEND_NUDGE_INTERVAL_MS) {
            return;
        }

        HelperStorage.persistLastRenderNudgeCheckAt(context, now);

        JSONObject routingPayload = readJsonResponse(
            new URL(
                serverOrigin
                    + "/api/auth?route=runtime/api-backend-config&ts="
                    + now
            )
        );
        if (routingPayload == null) {
            return;
        }

        if (!"external".equals(routingPayload.optString("activeMode", ""))) {
            return;
        }

        if (!routingPayload.optBoolean("externalAvailable", false)) {
            return;
        }

        String externalOrigin = normalizeExternalOrigin(
            routingPayload.optString("externalOrigin", "")
        );
        if (externalOrigin.isEmpty()) {
            return;
        }

        drainResponse(new URL(externalOrigin + "/healthz"));
    }

    private static String normalizeExternalOrigin(String rawOrigin) {
        if (rawOrigin == null) {
            return "";
        }

        String trimmedOrigin = rawOrigin.trim();
        if (trimmedOrigin.isEmpty()) {
            return "";
        }

        try {
            URL parsed = new URL(trimmedOrigin);
            String protocol = parsed.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                return "";
            }

            int port = parsed.getPort();
            return port > 0
                ? protocol + "://" + parsed.getHost() + ":" + port
                : protocol + "://" + parsed.getHost();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static JSONObject readJsonResponse(URL requestUrl) throws Exception {
        String responseText = drainResponse(requestUrl);
        if (responseText == null || responseText.trim().isEmpty()) {
            return null;
        }

        return new JSONObject(responseText);
    }

    private static String drainResponse(URL requestUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) requestUrl.openConnection();
        connection.setConnectTimeout(REQUEST_TIMEOUT_MS);
        connection.setReadTimeout(REQUEST_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-store");

        try {
            int statusCode = connection.getResponseCode();
            InputStream stream =
                statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();

            if (stream == null) {
                return null;
            }

            StringBuilder responseBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBuilder.append(line);
                }
            }

            return responseBuilder.toString();
        } finally {
            connection.disconnect();
        }
    }
}
