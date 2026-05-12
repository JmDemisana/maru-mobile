package io.maru.helper;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class HelperElevationManager {
    private static final String TAG = "HelperElevationManager";
    private static final String ACTION_COMPLETE_NATIVE_HANDOFF =
        "complete_native_elevation_app_handoff";
    private static final String ACTION_GET_HELPER_DEVICES = "get_helper_devices";
    private static final String ACTION_START_HELPER_LINK = "start_helper_link";
    private static final String ACTION_UPDATE_LASTFM = "update_helper_lastfm_notifications";
    private static final String HELPER_REALM_ELEVATION = "elevation";

    private HelperElevationManager() {
    }

    public static String buildElevationAuthUrl(Context context) {
        String serverOrigin = HelperStorage.resolvePublicSiteOrigin(context);
        String installationId = HelperStorage.ensureInstallationId(context);
        if (serverOrigin == null || serverOrigin.isEmpty() || installationId.isEmpty()) {
            return "";
        }

        try {
            return serverOrigin +
                "/elevation?helperAuth=1&installationId=" +
                URLEncoder.encode(installationId, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return "";
        }
    }

    public static boolean hasElevationToken(Context context) {
        return !HelperStorage.getElevationToken(context).isEmpty();
    }

    public static boolean isElevationLastFmEnabled(Context context) {
        return HelperStorage.isElevationLastFmEnabled(context);
    }

    public static boolean hasSecureStorage(Context context) {
        return HelperStorage.canUseSecureElevationStorage(context);
    }

    public static void clearElevationState(Context context) {
        HelperStorage.clearElevationState(context);
        HelperLastFmDetectorController.sync(context);
    }

    public static JSONObject getStemModelState(Context context) {
        JSONObject payload = new JSONObject();
        try {
            boolean downloadedOverride = HelperStemVocalReducer.hasDownloadedModel(context);
            boolean bundled = true;
            long overrideSizeBytes = 0L;
            if (HelperStemVocalReducer.getDownloadedModelFile(context).exists()) {
                overrideSizeBytes = HelperStemVocalReducer.getDownloadedModelFile(context).length();
            } else if (HelperStemVocalReducer.getLegacyDownloadedModelFile(context).exists()) {
                overrideSizeBytes =
                    HelperStemVocalReducer.getLegacyDownloadedModelFile(context).length();
            }

            payload.put("bundled", bundled);
            payload.put("downloadedOverride", downloadedOverride);
            payload.put("sizeBytes", overrideSizeBytes > 0L ? overrideSizeBytes : JSONObject.NULL);
            payload.put("installed", bundled || downloadedOverride);
            payload.put(
                "label",
                downloadedOverride
                    ? "Downloaded override ready"
                    : "Bundled with this helper build"
            );
            payload.put(
                "note",
                downloadedOverride
                    ? "The downloaded stem file is overriding the bundled karaoke model."
                    : "This helper build already carries its karaoke stem model locally."
            );
        } catch (Exception error) {
            try {
                payload.put("installed", false);
                payload.put("label", "Unavailable");
                payload.put(
                    "note",
                    error.getMessage() == null || error.getMessage().trim().isEmpty()
                        ? "Stem model status could not be read right now."
                        : error.getMessage().trim()
                );
            } catch (Exception ignored) {
                // Best effort payload.
            }
        }
        return payload;
    }

    public static JSONObject completeNativeElevationHandoff(
        Context context,
        String rawHandoffToken
    ) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("action", ACTION_COMPLETE_NATIVE_HANDOFF);
            payload.put("handoffToken", trimToEmpty(rawHandoffToken));
            payload.put("installationId", HelperStorage.ensureInstallationId(context));
        } catch (Exception ignored) {
            // Best effort payload.
        }

        JSONObject response = postSubscriptionRoute(
            context,
            payload,
            null
        );
        if (response.optBoolean("success", false)) {
            String token = optText(response, "token");
            if (!token.isEmpty() && HelperStorage.persistElevationToken(context, token)) {
                HelperStorage.persistElevationLastFmEnabled(context, false);
                HelperLastFmDetectorController.sync(context);
            } else {
                response = ensureFailure(
                    response,
                    "Secure storage is unavailable on this helper right now."
                );
            }
        }
        return response;
    }

    public static JSONObject fetchElevationDevices(Context context) {
        String authToken = HelperStorage.getElevationToken(context);
        if (authToken.isEmpty()) {
            return buildFailure("Open Elevation first.");
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("action", ACTION_GET_HELPER_DEVICES);
            payload.put("helperRealm", HELPER_REALM_ELEVATION);
        } catch (Exception ignored) {
            // Best effort payload.
        }

        JSONObject response = postSubscriptionRoute(context, payload, authToken);
        if (!response.has("success") && response.has("devices")) {
            try {
                response.put("success", true);
            } catch (Exception ignored) {
                // Best effort payload.
            }
        }
        handleAuthFailureIfNeeded(context, response);
        return response;
    }

    public static JSONObject updateElevationLastFmNotifications(
        Context context,
        boolean enabled
    ) {
        String authToken = HelperStorage.getElevationToken(context);
        if (authToken.isEmpty()) {
            return buildFailure("Open Elevation first.");
        }

        JSONObject linkResponse = ensureElevationHelperLinked(context, authToken);
        if (!linkResponse.optBoolean("success", false)) {
            return linkResponse;
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("action", ACTION_UPDATE_LASTFM);
            payload.put("enabled", enabled);
            payload.put("helperRealm", HELPER_REALM_ELEVATION);
            payload.put("installationId", HelperStorage.ensureInstallationId(context));
        } catch (Exception ignored) {
            // Best effort payload.
        }

        JSONObject response = postSubscriptionRoute(context, payload, authToken);
        handleAuthFailureIfNeeded(context, response);
        if (response.optBoolean("success", false)) {
            HelperStorage.persistElevationLastFmEnabled(context, enabled);
            HelperLastFmDetectorController.sync(context);
        }
        return response;
    }

    private static JSONObject ensureElevationHelperLinked(Context context, String authToken) {
        JSONObject currentDevices = fetchElevationDevices(context);
        if (!currentDevices.optBoolean("success", false)) {
            return currentDevices;
        }

        if (hasCurrentInstallation(currentDevices, context)) {
            return currentDevices;
        }

        String pushToken;
        try {
            pushToken = fetchNativePushToken();
        } catch (Exception error) {
            return buildFailure(
                error.getMessage() == null || error.getMessage().trim().isEmpty()
                    ? "Allow notifications first so this helper can finish linking."
                    : error.getMessage().trim()
            );
        }

        JSONObject startPayload = new JSONObject();
        try {
            startPayload.put("action", ACTION_START_HELPER_LINK);
            startPayload.put("helperRealm", HELPER_REALM_ELEVATION);
        } catch (Exception ignored) {
            // Best effort payload.
        }

        JSONObject startResponse = postSubscriptionRoute(context, startPayload, authToken);
        handleAuthFailureIfNeeded(context, startResponse);
        if (!startResponse.optBoolean("success", false) &&
            !startResponse.has("launchUrl")) {
            return ensureFailure(startResponse, "Could not prepare the elevated helper lane.");
        }

        String launchToken = extractLinkToken(optText(startResponse, "launchUrl"));
        if (launchToken.isEmpty()) {
            return buildFailure("Could not prepare the elevated helper lane.");
        }

        JSONObject completePayload = new JSONObject();
        try {
            completePayload.put("route", "companion/link-complete");
            completePayload.put("installationId", HelperStorage.ensureInstallationId(context));
            completePayload.put("platform", "android");
            completePayload.put("pushToken", pushToken);
            completePayload.put("token", launchToken);
            completePayload.put("appVersion", getAppVersion(context));
        } catch (Exception ignored) {
            // Best effort payload.
        }

        JSONObject completeResponse = postAuthRoute(context, completePayload);
        if (!completeResponse.optBoolean("success", false)) {
            return ensureFailure(completeResponse, "Could not finish helper linking right now.");
        }
        return fetchElevationDevices(context);
    }

    private static boolean hasCurrentInstallation(JSONObject response, Context context) {
        JSONArray devices = response.optJSONArray("devices");
        if (devices == null) {
            return false;
        }

        String installationId = HelperStorage.ensureInstallationId(context);
        for (int index = 0; index < devices.length(); index += 1) {
            JSONObject device = devices.optJSONObject(index);
            if (device == null) {
                continue;
            }
            if (installationId.equals(optText(device, "installationId"))) {
                return true;
            }
        }
        return false;
    }

    private static String fetchNativePushToken() throws Exception {
        String token = Tasks.await(
            FirebaseMessaging.getInstance().getToken(),
            15,
            TimeUnit.SECONDS
        );
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalStateException(
                "Allow notifications first so this helper can finish linking."
            );
        }
        return token.trim();
    }

    private static String getAppVersion(Context context) {
        try {
            return context
                .getPackageManager()
                .getPackageInfo(context.getPackageName(), 0)
                .versionName;
        } catch (Exception ignored) {
            return "0.0.0";
        }
    }

    private static JSONObject postSubscriptionRoute(
        Context context,
        JSONObject payload,
        String authToken
    ) {
        return postJsonRoute(
            context,
            "/api/subscription",
            payload,
            authToken
        );
    }

    private static JSONObject postAuthRoute(Context context, JSONObject payload) {
        return postJsonRoute(context, "/api/auth", payload, null);
    }

    private static JSONObject postJsonRoute(
        Context context,
        String path,
        JSONObject payload,
        String authToken
    ) {
        JSONObject failure = new JSONObject();
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        OutputStream outputStream = null;

        try {
            String serverOrigin = HelperStorage.resolveDetectorServerOrigin(context);
            if (serverOrigin == null || serverOrigin.isEmpty()) {
                throw new IllegalStateException("No helper backend is saved on this phone yet.");
            }

            URL url = new URL(serverOrigin + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            if (authToken != null && !authToken.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + authToken.trim());
            }

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
            Log.d(
                TAG,
                "POST " + path + " -> " + statusCode + " @ " + serverOrigin
            );
            if (statusCode >= 200 && statusCode < 300) {
                response.put("success", response.optBoolean("success", true));
                return response;
            }

            if (!response.has("success")) {
                response.put("success", false);
            }
            if (!response.has("error")) {
                response.put("error", "This helper could not finish that request right now.");
            }
            return response;
        } catch (Exception error) {
            Log.e(TAG, "POST " + path + " failed.", error);
            try {
                failure.put("success", false);
                failure.put(
                    "error",
                    error.getMessage() == null || error.getMessage().trim().isEmpty()
                        ? "This helper could not finish that request right now."
                        : error.getMessage().trim()
                );
            } catch (Exception ignored) {
                // Best effort payload.
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

    private static void handleAuthFailureIfNeeded(Context context, JSONObject response) {
        int statusCode = response.optInt("_httpStatus", 0);
        if (statusCode == 401 || statusCode == 403) {
            clearElevationState(context);
        }
    }

    private static JSONObject ensureFailure(JSONObject response, String fallbackError) {
        try {
            response.put("success", false);
            if (!response.has("error") || optText(response, "error").isEmpty()) {
                response.put("error", fallbackError);
            }
        } catch (Exception ignored) {
            // Best effort payload.
        }
        return response;
    }

    private static JSONObject buildFailure(String error) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("success", false);
            payload.put("error", error);
        } catch (Exception ignored) {
            // Best effort payload.
        }
        return payload;
    }

    private static String extractLinkToken(String rawLaunchUrl) {
        String launchUrl = trimToEmpty(rawLaunchUrl);
        if (launchUrl.isEmpty()) {
            return "";
        }

        try {
            Uri parsed = Uri.parse(launchUrl);
            return trimToEmpty(parsed.getQueryParameter("token"));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readStream(InputStream inputStream) throws Exception {
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

    private static String optText(JSONObject object, String key) {
        String value = object.optString(key, "").trim();
        if (value.isEmpty()) {
            return "";
        }
        String normalized = value.toLowerCase();
        return "null".equals(normalized) || "undefined".equals(normalized) ? "" : value;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
