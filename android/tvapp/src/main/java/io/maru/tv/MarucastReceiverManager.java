package io.maru.tv;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class MarucastReceiverManager {
    private static final String SERVICE_TYPE = "_marucast._tcp.";
    private static final String SERVICE_PREFIX = "Marucast";
    private static final long METADATA_REFRESH_MIN_INTERVAL_MS = 1500L;

    private final Object lock = new Object();
    private final Map<String, SenderInfo> senders = new LinkedHashMap<>();

    private Context appContext;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean discovering = false;
    private String lastError = null;

    public void bind(Context context) {
        if (context == null) {
            return;
        }

        synchronized (lock) {
            appContext = context.getApplicationContext();
            if (nsdManager == null && appContext != null) {
                nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
            }
        }
    }

    public String getStatusJson() {
        synchronized (lock) {
            for (SenderInfo sender : senders.values()) {
                maybeRefreshSenderMetadataLocked(sender);
            }

            JSONObject payload = new JSONObject();
            putJson(payload, "available", true);
            putJson(payload, "discovering", discovering);
            putJson(
                payload,
                "lastError",
                lastError == null || lastError.trim().isEmpty() ? JSONObject.NULL : lastError
            );

            JSONArray senderArray = new JSONArray();
            for (SenderInfo sender : senders.values()) {
                senderArray.put(sender.toJson());
            }
            putJson(payload, "senders", senderArray);
            return payload.toString();
        }
    }

    public void startDiscovery() {
        synchronized (lock) {
            if (discovering || nsdManager == null) {
                return;
            }

            lastError = null;
            senders.clear();

            discoveryListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String regType) {
                    synchronized (lock) {
                        discovering = true;
                    }
                }

                @Override
                public void onServiceFound(NsdServiceInfo serviceInfo) {
                    if (serviceInfo == null) {
                        return;
                    }

                    String serviceName = serviceInfo.getServiceName();
                    if (serviceName == null || !serviceName.startsWith(SERVICE_PREFIX)) {
                        return;
                    }

                    tryResolveService(serviceInfo);
                }

                @Override
                public void onServiceLost(NsdServiceInfo serviceInfo) {
                    if (serviceInfo == null || serviceInfo.getServiceName() == null) {
                        return;
                    }

                    synchronized (lock) {
                        senders.remove(serviceInfo.getServiceName());
                    }
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                    synchronized (lock) {
                        discovering = false;
                    }
                }

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    synchronized (lock) {
                        discovering = false;
                        lastError = "Marucast couldn't scan the local network.";
                    }
                    stopDiscovery();
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                    synchronized (lock) {
                        discovering = false;
                    }
                    stopDiscovery();
                }
            };

            try {
                nsdManager.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
                );
                discovering = true;
            } catch (Exception error) {
                discoveryListener = null;
                discovering = false;
                lastError = "Marucast couldn't scan the local network.";
            }
        }
    }

    public void refreshDiscovery() {
        stopDiscovery();
        startDiscovery();
    }

    public void stopDiscovery() {
        synchronized (lock) {
            if (nsdManager != null && discoveryListener != null) {
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener);
                } catch (Exception ignored) {
                    // Ignore stale discovery cleanup failures.
                }
            }

            discoveryListener = null;
            discovering = false;
        }
    }

    public void release() {
        stopDiscovery();
    }

    private void tryResolveService(NsdServiceInfo serviceInfo) {
        final NsdManager activeNsdManager;
        synchronized (lock) {
            activeNsdManager = nsdManager;
        }

        if (activeNsdManager == null) {
            return;
        }

        try {
            activeNsdManager.resolveService(
                serviceInfo,
                new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo failedServiceInfo, int errorCode) {
                        synchronized (lock) {
                            lastError = "A Marucast sender was found but couldn't be resolved.";
                        }
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo resolvedServiceInfo) {
                        if (resolvedServiceInfo == null) {
                            return;
                        }

                        InetAddress hostAddress = resolvedServiceInfo.getHost();
                        String host = hostAddress == null ? null : hostAddress.getHostAddress();
                        String serviceName = resolvedServiceInfo.getServiceName();
                        if (serviceName == null || host == null || resolvedServiceInfo.getPort() <= 0) {
                            return;
                        }

                        SenderInfo sender = new SenderInfo(serviceName);
                        sender.host = host;
                        sender.port = resolvedServiceInfo.getPort();
                        sender.title = readTxtAttribute(resolvedServiceInfo, "title");
                        sender.mimeType = readTxtAttribute(resolvedServiceInfo, "mime");
                        sender.mode = readTxtAttribute(resolvedServiceInfo, "mode");

                        synchronized (lock) {
                            senders.put(serviceName, sender);
                        }

                        refreshSenderMetadata(serviceName);
                    }
                }
            );
        } catch (Exception error) {
            synchronized (lock) {
                lastError = "A Marucast sender was found but couldn't be resolved.";
            }
        }
    }

    private void maybeRefreshSenderMetadataLocked(SenderInfo sender) {
        long now = System.currentTimeMillis();
        if (sender == null || now - sender.lastMetadataRefreshAtMs < METADATA_REFRESH_MIN_INTERVAL_MS) {
            return;
        }

        sender.lastMetadataRefreshAtMs = now;
        refreshSenderMetadata(sender.serviceName);
    }

    private void refreshSenderMetadata(String serviceName) {
        Thread metadataThread = new Thread(() -> {
            final SenderInfo currentSender;
            synchronized (lock) {
                currentSender = senders.get(serviceName);
            }

            if (currentSender == null || currentSender.host == null || currentSender.port <= 0) {
                return;
            }

            HttpURLConnection connection = null;
            try {
                URL statusUrl = new URL(currentSender.getBaseUrl() + "/status");
                connection = (HttpURLConnection) statusUrl.openConnection();
                connection.setConnectTimeout(2500);
                connection.setReadTimeout(2500);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");

                int statusCode = connection.getResponseCode();
                if (statusCode < 200 || statusCode >= 300) {
                    return;
                }

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
                );
                StringBuilder buffer = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    buffer.append(line);
                }
                reader.close();

                JSONObject payload = new JSONObject(buffer.toString());
                synchronized (lock) {
                    SenderInfo sender = senders.get(serviceName);
                    if (sender == null) {
                        return;
                    }

                    sender.title = optTrimmedString(payload, "selectedTitle", sender.title);
                    sender.mimeType = optTrimmedString(payload, "selectedMimeType", sender.mimeType);
                    sender.streamUrl = optTrimmedString(payload, "streamUrl", sender.getStreamUrl());
                    sender.liveStreamUrl = optTrimmedString(payload, "liveStreamUrl", sender.getLiveStreamUrl());
                    sender.mode = optTrimmedString(payload, "relayMode", sender.mode);
                    sender.sampleRate = payload.optInt("sampleRate", sender.sampleRate);
                    sender.channelCount = payload.optInt("channelCount", sender.channelCount);
                    sender.deviceName = optTrimmedString(payload, "deviceName", sender.deviceName);
                    sender.mediaTitle = optTrimmedString(payload, "mediaTitle", sender.mediaTitle);
                    sender.mediaArtist = optTrimmedString(payload, "mediaArtist", sender.mediaArtist);
                    sender.mediaAppLabel = optTrimmedString(payload, "mediaAppLabel", sender.mediaAppLabel);
                    sender.mediaPlaying = payload.optBoolean("mediaPlaying", sender.mediaPlaying);
                    sender.lastMetadataRefreshAtMs = System.currentTimeMillis();
                }
            } catch (Exception ignored) {
                // Keep best-effort discovery info if metadata polling fails.
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "MarucastSenderMetadata");
        metadataThread.setDaemon(true);
        metadataThread.start();
    }

    private String optTrimmedString(JSONObject payload, String key, String fallback) {
        String value = payload.optString(key, fallback);
        if (value == null) {
            return fallback;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private String readTxtAttribute(NsdServiceInfo serviceInfo, String key) {
        if (serviceInfo == null || key == null || key.trim().isEmpty()) {
            return null;
        }

        try {
            Map<String, byte[]> attributes = serviceInfo.getAttributes();
            if (attributes == null) {
                return null;
            }

            byte[] rawValue = attributes.get(key);
            if (rawValue == null || rawValue.length == 0) {
                return null;
            }

            return new String(rawValue, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void putJson(JSONObject payload, String key, Object value) {
        try {
            payload.put(key, value);
        } catch (Exception ignored) {
            // Ignore best-effort sender serialization failures.
        }
    }

    private static final class SenderInfo {
        final String serviceName;
        String deviceName;
        String host;
        int port;
        String liveStreamUrl;
        String mediaAppLabel;
        String mediaArtist;
        boolean mediaPlaying;
        String mediaTitle;
        String mimeType;
        String mode;
        int sampleRate = 48000;
        int channelCount = 2;
        String streamUrl;
        String title;
        long lastMetadataRefreshAtMs = 0L;

        SenderInfo(String serviceName) {
            this.serviceName = serviceName;
        }

        String getBaseUrl() {
            return host == null || port <= 0 ? null : "http://" + host + ":" + port;
        }

        String getStreamUrl() {
            if (streamUrl != null && !streamUrl.trim().isEmpty()) {
                return streamUrl;
            }

            String baseUrl = getBaseUrl();
            return baseUrl == null ? null : baseUrl + "/stream";
        }

        String getLiveStreamUrl() {
            if (liveStreamUrl != null && !liveStreamUrl.trim().isEmpty()) {
                return liveStreamUrl;
            }

            String baseUrl = getBaseUrl();
            return baseUrl == null ? null : baseUrl + "/live.pcm";
        }

        JSONObject toJson() {
            JSONObject payload = new JSONObject();
            try {
                payload.put("serviceName", serviceName);
                payload.put("deviceName", deviceName == null ? JSONObject.NULL : deviceName);
                payload.put("title", title == null ? JSONObject.NULL : title);
                payload.put("mimeType", mimeType == null ? JSONObject.NULL : mimeType);
                payload.put("mode", mode == null ? JSONObject.NULL : mode);
                payload.put("host", host == null ? JSONObject.NULL : host);
                payload.put("port", port > 0 ? port : JSONObject.NULL);
                payload.put("streamUrl", getStreamUrl() == null ? JSONObject.NULL : getStreamUrl());
                payload.put("liveStreamUrl", getLiveStreamUrl() == null ? JSONObject.NULL : getLiveStreamUrl());
                payload.put("sampleRate", sampleRate);
                payload.put("channelCount", channelCount);
                payload.put("mediaTitle", mediaTitle == null ? JSONObject.NULL : mediaTitle);
                payload.put("mediaArtist", mediaArtist == null ? JSONObject.NULL : mediaArtist);
                payload.put("mediaAppLabel", mediaAppLabel == null ? JSONObject.NULL : mediaAppLabel);
                payload.put("mediaPlaying", mediaPlaying);
            } catch (Exception ignored) {
                // Ignore best-effort sender serialization failures.
            }
            return payload;
        }
    }
}
