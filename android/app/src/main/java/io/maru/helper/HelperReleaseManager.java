package io.maru.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HelperReleaseManager {
    private static final long CACHE_TTL_MS = 5L * 60L * 1000L;
    private static final String GITHUB_RELEASES_API =
        "https://api.github.com/repos/JmDemisana/maru-mobile/releases?per_page=12";
    private static final String GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/JmDemisana/maru-mobile/releases/latest";
    private static final String HELPER_APK_FILENAME = "maru-link.apk";
    private static final String USER_AGENT = "MaruLink/1.0";

    private static JSONArray cachedReleases = null;
    private static long cachedReleasesAtMs = 0L;
    private static JSONObject cachedLatestRelease = null;
    private static long cachedLatestReleaseAtMs = 0L;

    public static final class ReleaseAssetInfo {
        public final String assetName;
        public final String downloadUrl;
        public final String releaseTag;
        public final long sizeBytes;

        ReleaseAssetInfo(String assetName, String downloadUrl, String releaseTag, long sizeBytes) {
            this.assetName = assetName == null ? "" : assetName.trim();
            this.downloadUrl = downloadUrl == null ? "" : downloadUrl.trim();
            this.releaseTag = releaseTag == null ? "" : releaseTag.trim();
            this.sizeBytes = Math.max(0L, sizeBytes);
        }

        public String getReleaseVersion() {
            return normalizeReleaseVersion(releaseTag);
        }

        public String getSizeLabel() {
            return formatBytes(sizeBytes);
        }
    }

    private HelperReleaseManager() {
    }

    public static ReleaseAssetInfo fetchLatestHelperReleaseAsset() throws Exception {
        JSONObject release = getLatestRelease();
        String tagName = optText(release, "tag_name");
        JSONArray assets = release.optJSONArray("assets");
        ReleaseAssetInfo asset = findAssetInRelease(tagName, assets, HELPER_APK_FILENAME);
        if (asset == null) {
            throw new IllegalStateException("The latest helper release does not include maru-link.apk.");
        }
        return asset;
    }

    public static Map<String, ReleaseAssetInfo> fetchLatestAssetsByName(
        Collection<String> rawAssetNames
    ) throws Exception {
        Set<String> desiredAssetNames = new HashSet<>();
        if (rawAssetNames != null) {
            for (String rawAssetName : rawAssetNames) {
                String assetName = normalizeAssetName(rawAssetName);
                if (!assetName.isEmpty()) {
                    desiredAssetNames.add(assetName);
                }
            }
        }

        Map<String, ReleaseAssetInfo> results = new HashMap<>();
        if (desiredAssetNames.isEmpty()) {
            return results;
        }

        JSONArray releases = getReleases();
        for (int releaseIndex = 0; releaseIndex < releases.length(); releaseIndex += 1) {
            JSONObject release = releases.optJSONObject(releaseIndex);
            if (release == null) {
                continue;
            }

            String tagName = optText(release, "tag_name");
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) {
                continue;
            }

            for (int assetIndex = 0; assetIndex < assets.length(); assetIndex += 1) {
                JSONObject asset = assets.optJSONObject(assetIndex);
                if (asset == null) {
                    continue;
                }

                String assetName = normalizeAssetName(optText(asset, "name"));
                if (assetName.isEmpty() ||
                    !desiredAssetNames.contains(assetName) ||
                    results.containsKey(assetName)) {
                    continue;
                }

                String downloadUrl = optText(asset, "browser_download_url");
                if (downloadUrl.isEmpty()) {
                    continue;
                }

                results.put(
                    assetName,
                    new ReleaseAssetInfo(
                        optText(asset, "name"),
                        downloadUrl,
                        tagName,
                        asset.optLong("size", 0L)
                    )
                );
                if (results.size() >= desiredAssetNames.size()) {
                    return results;
                }
            }
        }

        return results;
    }

    public static int compareVersions(String left, String right) {
        List<Integer> leftParts = parseVersionParts(left);
        List<Integer> rightParts = parseVersionParts(right);
        int length = Math.max(leftParts.size(), rightParts.size());
        for (int index = 0; index < length; index += 1) {
            int leftValue = index < leftParts.size() ? leftParts.get(index) : 0;
            int rightValue = index < rightParts.size() ? rightParts.get(index) : 0;
            if (leftValue != rightValue) {
                return leftValue - rightValue;
            }
        }
        return 0;
    }

    public static String normalizeReleaseVersion(String rawVersion) {
        List<Integer> parts = parseVersionParts(rawVersion);
        if (parts.isEmpty()) {
            return "0";
        }

        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parts.size(); index += 1) {
            if (index > 0) {
                builder.append('.');
            }
            builder.append(parts.get(index));
        }
        return builder.toString();
    }

    private static synchronized JSONArray getReleases() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedReleases != null && (now - cachedReleasesAtMs) < CACHE_TTL_MS) {
            return new JSONArray(cachedReleases.toString());
        }

        JSONArray releases = new JSONArray(readUrl(GITHUB_RELEASES_API));
        cachedReleases = releases;
        cachedReleasesAtMs = now;
        return new JSONArray(releases.toString());
    }

    private static synchronized JSONObject getLatestRelease() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedLatestRelease != null && (now - cachedLatestReleaseAtMs) < CACHE_TTL_MS) {
            return new JSONObject(cachedLatestRelease.toString());
        }

        JSONObject release = new JSONObject(readUrl(GITHUB_LATEST_RELEASE_API));
        cachedLatestRelease = release;
        cachedLatestReleaseAtMs = now;
        return new JSONObject(release.toString());
    }

    private static ReleaseAssetInfo findAssetInRelease(
        String releaseTag,
        JSONArray assets,
        String rawAssetName
    ) {
        String desiredAssetName = normalizeAssetName(rawAssetName);
        if (assets == null || desiredAssetName.isEmpty()) {
            return null;
        }

        for (int index = 0; index < assets.length(); index += 1) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset == null) {
                continue;
            }

            String assetName = normalizeAssetName(optText(asset, "name"));
            if (!desiredAssetName.equals(assetName)) {
                continue;
            }

            String downloadUrl = optText(asset, "browser_download_url");
            if (downloadUrl.isEmpty()) {
                continue;
            }

            return new ReleaseAssetInfo(
                optText(asset, "name"),
                downloadUrl,
                releaseTag,
                asset.optLong("size", 0L)
            );
        }

        return null;
    }

    private static List<Integer> parseVersionParts(String rawVersion) {
        List<Integer> parts = new ArrayList<>();
        String normalized = rawVersion == null ? "" : rawVersion.trim();
        if (normalized.isEmpty()) {
            parts.add(0);
            return parts;
        }

        String[] segments = normalized.split("[^0-9]+");
        for (String segment : segments) {
            if (segment == null || segment.isEmpty()) {
                continue;
            }
            try {
                parts.add(Integer.parseInt(segment));
            } catch (NumberFormatException ignored) {
                // Skip malformed chunks.
            }
        }

        if (parts.isEmpty()) {
            parts.add(0);
        }
        return parts;
    }

    private static String normalizeAssetName(String rawAssetName) {
        return rawAssetName == null ? "" : rawAssetName.trim().toLowerCase();
    }

    private static String optText(JSONObject object, String key) {
        String value = object.optString(key, "").trim();
        if (value.isEmpty()) {
            return "";
        }
        String normalized = value.toLowerCase();
        return "null".equals(normalized) || "undefined".equals(normalized) ? "" : value;
    }

    private static String readUrl(String rawUrl) throws Exception {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        try {
            URL url = new URL(rawUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", USER_AGENT);

            int statusCode = connection.getResponseCode();
            inputStream =
                statusCode >= 200 && statusCode < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String responseBody = readStream(inputStream);
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException(
                    responseBody.isEmpty()
                        ? "GitHub did not answer cleanly."
                        : responseBody
                );
            }
            return responseBody;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {
                    // Best effort close.
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readStream(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static String formatBytes(long rawBytes) {
        long bytes = Math.max(0L, rawBytes);
        if (bytes <= 0L) {
            return "";
        }
        if (bytes < 1024L * 1024L) {
            return Math.max(1L, Math.round(bytes / 1024.0d)) + " KB";
        }
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0d * 1024.0d));
    }
}
