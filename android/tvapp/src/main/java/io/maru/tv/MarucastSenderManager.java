package io.maru.tv;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MarucastSenderManager {
    private static final String SERVICE_TYPE = "_marucast._tcp.";
    private static final String SERVICE_PREFIX = "Marucast";
    private static final String RELAY_MODE_CAPTURE = "playback-capture";
    private static final String LIVE_PCM_PATH = "/live.pcm";
    private static final String LIVE_PCM_MIME_TYPE = "application/octet-stream";
    private static final String LIVE_CAPTURE_LABEL = "Current TV playback";
    private static final String LIVE_APP_LABEL = "TV broadcast";
    private static final int LIVE_CHANNEL_COUNT = 2;
    private static final int LIVE_SAMPLE_RATE = 48000;
    private static final int FIXED_RELAY_PORT = 48543;
    private static final MarucastSenderManager INSTANCE = new MarucastSenderManager();

    private final Object lock = new Object();
    private final List<LivePcmClient> livePcmClients = new ArrayList<>();

    private Context appContext;
    private String pairingCode = null;
    private String registeredServiceName = null;
    private String lastError = null;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private int listeningPort = -1;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private Intent playbackCaptureData;
    private int playbackCaptureResultCode = 0;
    private MediaProjection mediaProjection;
    private MediaProjection.Callback mediaProjectionCallback;
    private VirtualDisplay projectionKeepaliveDisplay;
    private ImageReader projectionKeepaliveImageReader;
    private AudioRecord captureAudioRecord;
    private Thread captureThread;

    public static MarucastSenderManager getInstance() {
        return INSTANCE;
    }

    private MarucastSenderManager() {}

    public void bind(Context context) {
        if (context == null) {
            return;
        }

        synchronized (lock) {
            appContext = context.getApplicationContext();
            if (nsdManager == null && appContext != null) {
                nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
            }
            ensurePairingCodeLocked();
        }
    }

    public void startPlaybackCapture(Context context, int resultCode, Intent resultData) {
        bind(context);
        synchronized (lock) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                lastError = "TV broadcasting needs Android 10 or newer.";
                return;
            }
            if (resultCode == 0 || resultData == null) {
                lastError = "TV broadcasting could not start because Android did not return capture access.";
                return;
            }

            playbackCaptureResultCode = resultCode;
            playbackCaptureData = new Intent(resultData);
            ensurePairingCodeLocked();
            lastError = null;
            restartLocked();
        }
    }

    public void stopPlaybackCapture() {
        synchronized (lock) {
            playbackCaptureResultCode = 0;
            playbackCaptureData = null;
            stopLocked();
            lastError = null;
        }
    }

    public boolean isPlaybackCaptureRunning() {
        synchronized (lock) {
            return captureAudioRecord != null &&
                captureThread != null &&
                captureThread.isAlive();
        }
    }

    public void setLastError(String message) {
        synchronized (lock) {
            lastError = message;
        }
    }

    public String getStatusJson() {
        synchronized (lock) {
            return buildStatusJsonLocked().toString();
        }
    }

    private void restartLocked() {
        stopLocked();

        if (appContext == null || playbackCaptureResultCode == 0 || playbackCaptureData == null) {
            return;
        }

        try {
            startServerLocked();
            startPlaybackCaptureLocked();
        } catch (Exception error) {
            lastError = "Couldn't start the TV Marucast playback relay.";
            stopLocked();
        }
    }

    private void stopLocked() {
        stopPlaybackCaptureLocked();

        if (registrationListener != null && nsdManager != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (Exception ignored) {
                // Ignore stale NSD listener cleanup failures.
            }
        }

        registrationListener = null;
        registeredServiceName = null;

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Ignore socket shutdown failures.
            }
        }

        serverSocket = null;
        serverThread = null;
        listeningPort = -1;
    }

    private void startServerLocked() throws IOException {
        serverSocket = new ServerSocket(FIXED_RELAY_PORT);
        listeningPort = serverSocket.getLocalPort();
        serverThread = new Thread(this::runServerLoop, "TvMarucastSenderServer");
        serverThread.setDaemon(true);
        serverThread.start();
        registerNsdServiceLocked();
    }

    private void startPlaybackCaptureLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || appContext == null) {
            throw new IllegalStateException("Playback capture is not available.");
        }

        MediaProjectionManager projectionManager =
            (MediaProjectionManager) appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (projectionManager == null || playbackCaptureData == null || playbackCaptureResultCode == 0) {
            throw new IllegalStateException("Playback capture permission is missing.");
        }

        mediaProjection = projectionManager.getMediaProjection(
            playbackCaptureResultCode,
            playbackCaptureData
        );
        if (mediaProjection == null) {
            throw new IllegalStateException("Playback capture session could not start.");
        }

        try {
            int densityDpi = Math.max(
                1,
                appContext.getResources().getDisplayMetrics().densityDpi
            );
            projectionKeepaliveImageReader = ImageReader.newInstance(
                1,
                1,
                PixelFormat.RGBA_8888,
                2
            );
            projectionKeepaliveDisplay = mediaProjection.createVirtualDisplay(
                "TvMarucastProjectionKeepalive",
                1,
                1,
                densityDpi,
                0,
                projectionKeepaliveImageReader.getSurface(),
                null,
                null
            );
        } catch (Exception ignored) {
            if (projectionKeepaliveDisplay != null) {
                try {
                    projectionKeepaliveDisplay.release();
                } catch (Exception ignoredRelease) {}
            }
            projectionKeepaliveDisplay = null;
            if (projectionKeepaliveImageReader != null) {
                try {
                    projectionKeepaliveImageReader.close();
                } catch (Exception ignoredClose) {}
            }
            projectionKeepaliveImageReader = null;
        }

        AudioPlaybackCaptureConfiguration captureConfiguration =
            new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();

        AudioFormat audioFormat = new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(LIVE_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build();

        int minimumBufferSize = AudioRecord.getMinBufferSize(
            LIVE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        );
        if (minimumBufferSize <= 0) {
            throw new IllegalStateException("Android would not allocate the TV relay audio buffer.");
        }

        captureAudioRecord = new AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfiguration)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(Math.max(minimumBufferSize * 2, 16 * 1024))
            .build();
        captureAudioRecord.startRecording();

        mediaProjectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                synchronized (lock) {
                    lastError = "TV broadcasting ended because Android stopped the capture session.";
                    stopPlaybackCaptureLocked();
                }
            }
        };
        mediaProjection.registerCallback(mediaProjectionCallback, null);

        captureThread = new Thread(this::runPlaybackCaptureLoop, "TvMarucastPlaybackCapture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    private void stopPlaybackCaptureLocked() {
        for (LivePcmClient client : new ArrayList<>(livePcmClients)) {
            removeLivePcmClient(client);
        }

        if (captureAudioRecord != null) {
            try {
                captureAudioRecord.stop();
            } catch (Exception ignored) {}
            try {
                captureAudioRecord.release();
            } catch (Exception ignored) {}
        }
        captureAudioRecord = null;

        if (projectionKeepaliveDisplay != null) {
            try {
                projectionKeepaliveDisplay.release();
            } catch (Exception ignored) {}
        }
        projectionKeepaliveDisplay = null;

        if (projectionKeepaliveImageReader != null) {
            try {
                projectionKeepaliveImageReader.close();
            } catch (Exception ignored) {}
        }
        projectionKeepaliveImageReader = null;

        if (mediaProjection != null && mediaProjectionCallback != null) {
            try {
                mediaProjection.unregisterCallback(mediaProjectionCallback);
            } catch (Exception ignored) {}
        }
        mediaProjectionCallback = null;

        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Exception ignored) {}
        }
        mediaProjection = null;

        if (captureThread != null) {
            captureThread.interrupt();
        }
        captureThread = null;
    }

    private void registerNsdServiceLocked() {
        if (nsdManager == null || listeningPort <= 0) {
            return;
        }

        final String code = ensurePairingCodeLocked();
        final NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setServiceName(SERVICE_PREFIX + "-" + code);
        serviceInfo.setPort(listeningPort);
        serviceInfo.setAttribute("code", code);
        serviceInfo.setAttribute("title", truncateAttributeValue(LIVE_CAPTURE_LABEL));
        serviceInfo.setAttribute("mode", RELAY_MODE_CAPTURE);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                synchronized (lock) {
                    registeredServiceName = null;
                    lastError = "Marucast couldn't advertise this TV relay on local Wi-Fi.";
                }
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}

            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                synchronized (lock) {
                    registeredServiceName =
                        serviceInfo == null ? null : serviceInfo.getServiceName();
                }
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                synchronized (lock) {
                    registeredServiceName = null;
                }
            }
        };

        try {
            nsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            );
        } catch (Exception error) {
            registrationListener = null;
            lastError = "Marucast couldn't advertise this TV relay on local Wi-Fi.";
        }
    }

    private void runPlaybackCaptureLoop() {
        byte[] buffer = new byte[16 * 1024];

        while (true) {
            final AudioRecord activeRecorder;
            synchronized (lock) {
                activeRecorder = captureAudioRecord;
            }

            if (activeRecorder == null) {
                break;
            }

            int read;
            try {
                read = activeRecorder.read(buffer, 0, buffer.length);
            } catch (Exception error) {
                synchronized (lock) {
                    lastError = "Marucast lost access to the TV playback stream.";
                }
                break;
            }

            if (read <= 0) {
                continue;
            }

            broadcastLivePcm(buffer, read);
        }
    }

    private void runServerLoop() {
        final ServerSocket activeServer;

        synchronized (lock) {
            activeServer = serverSocket;
        }

        if (activeServer == null) {
            return;
        }

        while (!activeServer.isClosed()) {
            try {
                final Socket socket = activeServer.accept();
                Thread clientThread = new Thread(
                    () -> handleClient(socket),
                    "TvMarucastSenderClient"
                );
                clientThread.setDaemon(true);
                clientThread.start();
            } catch (SocketException error) {
                break;
            } catch (IOException error) {
                synchronized (lock) {
                    lastError = "The TV Marucast sender hit a local network error.";
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try (Socket activeSocket = socket) {
            activeSocket.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(activeSocket.getInputStream(), StandardCharsets.ISO_8859_1)
            );
            OutputStream output = activeSocket.getOutputStream();

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) {
                writeSimpleResponse(output, 400, "text/plain; charset=utf-8", "Bad Request");
                return;
            }

            String[] requestParts = requestLine.split(" ");
            String method = requestParts.length > 0 ? requestParts[0].trim().toUpperCase(Locale.US) : "";
            String requestTarget = requestParts.length > 1 ? requestParts[1].trim() : "/";
            String requestPath = requestTarget.split("\\?", 2)[0];
            Map<String, String> headers = new LinkedHashMap<>();

            String headerLine;
            while ((headerLine = reader.readLine()) != null) {
                if (headerLine.isEmpty()) {
                    break;
                }

                int separatorIndex = headerLine.indexOf(':');
                if (separatorIndex <= 0) {
                    continue;
                }

                String headerName = headerLine.substring(0, separatorIndex).trim().toLowerCase(Locale.US);
                String headerValue = headerLine.substring(separatorIndex + 1).trim();
                headers.put(headerName, headerValue);
            }

            if ("OPTIONS".equals(method)) {
                writeHeaders(output, 204, "No Content", null, 0L, true);
                return;
            }

            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                writeSimpleResponse(output, 405, "text/plain; charset=utf-8", "Method Not Allowed");
                return;
            }

            if ("/status".equals(requestPath)) {
                byte[] payload = getStatusJson().getBytes(StandardCharsets.UTF_8);
                writeHeaders(
                    output,
                    200,
                    "OK",
                    "application/json; charset=utf-8",
                    payload.length,
                    true
                );
                if ("GET".equals(method)) {
                    output.write(payload);
                }
                return;
            }

            if ("/control".equals(requestPath)) {
                writeSimpleResponse(
                    output,
                    409,
                    "application/json; charset=utf-8",
                    "{\"success\":false,\"message\":\"TV broadcast controls are unavailable.\"}"
                );
                return;
            }

            if (LIVE_PCM_PATH.equals(requestPath)) {
                streamLivePlayback(output, method, activeSocket);
                return;
            }

            writeSimpleResponse(output, 404, "text/plain; charset=utf-8", "Not Found");
        } catch (IOException ignored) {
            // Ignore per-client disconnects.
        }
    }

    private void streamLivePlayback(
        OutputStream output,
        String method,
        Socket socket
    ) throws IOException {
        synchronized (lock) {
            if (captureAudioRecord == null) {
                writeSimpleResponse(
                    output,
                    409,
                    "text/plain; charset=utf-8",
                    "TV playback relay is not active"
                );
                return;
            }
        }

        writeLivePcmHeaders(output);
        if ("HEAD".equals(method)) {
            return;
        }

        LivePcmClient client = new LivePcmClient(socket, output);
        addLivePcmClient(client);

        try {
            synchronized (client.monitor) {
                while (!socket.isClosed()) {
                    try {
                        client.monitor.wait(1000L);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            removeLivePcmClient(client);
        }
    }

    private void writeSimpleResponse(
        OutputStream output,
        int statusCode,
        String contentType,
        String body
    ) throws IOException {
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        writeHeaders(
            output,
            statusCode,
            getReasonPhrase(statusCode),
            contentType,
            payload.length,
            false
        );
        if (payload.length > 0) {
            output.write(payload);
        }
    }

    private void writeHeaders(
        OutputStream output,
        int statusCode,
        String reasonPhrase,
        String contentType,
        long contentLength,
        boolean allowCors
    ) throws IOException {
        writeAsciiLine(output, "HTTP/1.1 " + statusCode + " " + reasonPhrase);
        writeAsciiLine(output, "Connection: close");
        writeAsciiLine(output, "Cache-Control: no-store");
        if (contentType != null && !contentType.isEmpty()) {
            writeAsciiLine(output, "Content-Type: " + contentType);
        }
        if (contentLength >= 0) {
            writeAsciiLine(output, "Content-Length: " + contentLength);
        }
        if (allowCors) {
            writeAsciiLine(output, "Access-Control-Allow-Origin: *");
            writeAsciiLine(output, "Access-Control-Allow-Methods: GET, HEAD, OPTIONS");
            writeAsciiLine(output, "Access-Control-Allow-Headers: *");
            writeAsciiLine(output, "Access-Control-Allow-Private-Network: true");
        }
        writeAsciiLine(output, "");
    }

    private void writeLivePcmHeaders(OutputStream output) throws IOException {
        writeAsciiLine(output, "HTTP/1.1 200 OK");
        writeAsciiLine(output, "Connection: close");
        writeAsciiLine(output, "Cache-Control: no-store");
        writeAsciiLine(output, "Content-Type: " + LIVE_PCM_MIME_TYPE);
        writeAsciiLine(output, "X-Maru-Relay-Mode: " + RELAY_MODE_CAPTURE);
        writeAsciiLine(output, "X-Maru-Sample-Rate: " + LIVE_SAMPLE_RATE);
        writeAsciiLine(output, "X-Maru-Channel-Count: " + LIVE_CHANNEL_COUNT);
        writeAsciiLine(output, "X-Maru-Pcm-Encoding: s16le");
        writeAsciiLine(output, "Access-Control-Allow-Origin: *");
        writeAsciiLine(output, "Access-Control-Allow-Methods: GET, HEAD, OPTIONS");
        writeAsciiLine(output, "Access-Control-Allow-Headers: *");
        writeAsciiLine(output, "Access-Control-Allow-Private-Network: true");
        writeAsciiLine(output, "");
    }

    private void writeAsciiLine(OutputStream output, String value) throws IOException {
        output.write((value + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    private void addLivePcmClient(LivePcmClient client) {
        synchronized (lock) {
            livePcmClients.add(client);
        }
    }

    private void removeLivePcmClient(LivePcmClient client) {
        synchronized (lock) {
            livePcmClients.remove(client);
        }

        try {
            client.socket.close();
        } catch (IOException ignored) {}

        synchronized (client.monitor) {
            client.monitor.notifyAll();
        }
    }

    private void broadcastLivePcm(byte[] buffer, int length) {
        List<LivePcmClient> clients;
        synchronized (lock) {
            if (livePcmClients.isEmpty()) {
                return;
            }
            clients = new ArrayList<>(livePcmClients);
        }

        for (LivePcmClient client : clients) {
            try {
                client.output.write(buffer, 0, length);
                client.output.flush();
            } catch (IOException error) {
                removeLivePcmClient(client);
            }
        }
    }

    private JSONObject buildStatusJsonLocked() {
        JSONObject payload = new JSONObject();
        String localIp = getLocalIpAddressLocked();
        String liveStreamUrl = buildLiveStreamUrlLocked(localIp);
        String loopbackLiveStreamUrl = buildLoopbackLiveStreamUrlLocked();

        putJson(payload, "available", true);
        putJson(payload, "running", isRunningLocked());
        putJson(payload, "relayMode", RELAY_MODE_CAPTURE);
        putJson(payload, "captureActive", captureAudioRecord != null);
        putJson(payload, "pairingCode", ensurePairingCodeLocked());
        putJson(payload, "serviceName", registeredServiceName == null ? JSONObject.NULL : registeredServiceName);
        putJson(payload, "deviceName", getDeviceNameLocked());
        putJson(payload, "localIp", localIp == null ? JSONObject.NULL : localIp);
        putJson(payload, "port", listeningPort > 0 ? listeningPort : JSONObject.NULL);
        putJson(payload, "selectedTitle", LIVE_CAPTURE_LABEL);
        putJson(payload, "selectedMimeType", LIVE_PCM_MIME_TYPE);
        putJson(payload, "sampleRate", LIVE_SAMPLE_RATE);
        putJson(payload, "channelCount", LIVE_CHANNEL_COUNT);
        putJson(payload, "mediaAccessEnabled", false);
        putJson(payload, "mediaAppLabel", LIVE_APP_LABEL);
        putJson(payload, "mediaPlaying", captureAudioRecord != null);
        putJson(payload, "transportAvailable", false);
        putJson(
            payload,
            "lastError",
            lastError == null || lastError.trim().isEmpty() ? JSONObject.NULL : lastError
        );
        putJson(payload, "streamUrl", JSONObject.NULL);
        putJson(payload, "liveStreamUrl", liveStreamUrl == null ? JSONObject.NULL : liveStreamUrl);
        putJson(
            payload,
            "loopbackLiveStreamUrl",
            loopbackLiveStreamUrl == null ? JSONObject.NULL : loopbackLiveStreamUrl
        );

        JSONArray capabilities = new JSONArray();
        capabilities.put("nsd-advertising");
        capabilities.put("playback-capture-relay");
        capabilities.put("background-relay");
        putJson(payload, "capabilities", capabilities);
        return payload;
    }

    private String buildLiveStreamUrlLocked(String localIp) {
        return localIp != null && listeningPort > 0
            ? "http://" + localIp + ":" + listeningPort + LIVE_PCM_PATH
            : null;
    }

    private String buildLoopbackLiveStreamUrlLocked() {
        return listeningPort > 0
            ? "http://127.0.0.1:" + listeningPort + LIVE_PCM_PATH
            : null;
    }

    private String ensurePairingCodeLocked() {
        if (pairingCode == null || pairingCode.trim().isEmpty()) {
            pairingCode = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 6)
                .toUpperCase(Locale.US);
        }
        return pairingCode;
    }

    private boolean isRunningLocked() {
        return serverSocket != null && !serverSocket.isClosed() && listeningPort > 0;
    }

    private String getDeviceNameLocked() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (manufacturer.isEmpty()) {
            return model.isEmpty() ? "Android TV" : model;
        }
        if (model.isEmpty()) {
            return manufacturer;
        }
        if (model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US))) {
            return model;
        }
        return manufacturer + " " + model;
    }

    private String getLocalIpAddressLocked() {
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }

                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String hostAddress = address.getHostAddress();
                        if (hostAddress != null && !hostAddress.trim().isEmpty()) {
                            return hostAddress;
                        }
                    }
                }
            }
        } catch (SocketException ignored) {
            // Ignore local IP detection failures.
        }

        return null;
    }

    private String truncateAttributeValue(String rawValue) {
        String trimmed = rawValue == null ? "" : rawValue.trim();
        return trimmed.length() <= 96 ? trimmed : trimmed.substring(0, 96);
    }

    private void putJson(JSONObject payload, String key, Object value) {
        try {
            payload.put(key, value);
        } catch (Exception ignored) {
            // Ignore best-effort status serialization failures.
        }
    }

    private String getReasonPhrase(int statusCode) {
        switch (statusCode) {
            case 200:
                return "OK";
            case 204:
                return "No Content";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 409:
                return "Conflict";
            default:
                return "Error";
        }
    }

    private static final class LivePcmClient {
        final Socket socket;
        final OutputStream output;
        final Object monitor = new Object();

        LivePcmClient(Socket socket, OutputStream output) {
            this.socket = socket;
            this.output = output;
        }
    }
}
