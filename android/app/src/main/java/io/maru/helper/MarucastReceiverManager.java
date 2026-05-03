package io.maru.helper;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MarucastReceiverManager {
    private static final String TAG = "MarucastReceiver";
    private static final String SERVICE_TYPE = "_marucast._tcp.";
    private static final String HEADER_CONTENT_TYPE = "Content-Type:";
    private static final String HEADER_CONTENT_LENGTH = "Content-Length:";
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_COUNT = 2;
    private static final int PCM_BYTES_PER_SAMPLE = 2;
    private static final int BUFFER_SIZE_BYTES = AudioTrack.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_16BIT
    );
    private static final int SOCKET_TIMEOUT_MS = 5000;
    private static final int READ_CHUNK_SIZE = 8192;

    private static MarucastReceiverManager instance;

    public static synchronized MarucastReceiverManager getInstance() {
        if (instance == null) {
            instance = new MarucastReceiverManager();
        }
        return instance;
    }

    private final Object lock = new Object();
    private Context appContext;
    private NsdManager nsdManager;

    /* Discovery state */
    private boolean discovering = false;
    private NsdManager.DiscoveryListener discoveryListener;
    private final List<DiscoveredSender> discoveredSenders = new ArrayList<>();
    private final List<ResolveListenerWrapper> pendingResolves = new ArrayList<>();

    /* Connection state */
    private boolean connected = false;
    private String connectedSenderName = null;
    private String connectedSenderHost = null;
    private int connectedSenderPort = 0;
    private String connectedPairingCode = null;
    private Socket receiverSocket;
    private AudioTrack audioTrack;
    private Thread receiverThread;
    private String lastError = null;
    private float volume = 1.0f;

    private MarucastReceiverManager() {}

    public void bind(Context context) {
        appContext = context.getApplicationContext();
    }

    public void startDiscovery() {
        synchronized (lock) {
            if (discovering) return;
            if (appContext == null) return;

            discoveredSenders.clear();
            discovering = true;
            lastError = null;

            if (nsdManager == null) {
                nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
            }
            if (nsdManager == null) {
                discovering = false;
                lastError = "Network service discovery unavailable.";
                return;
            }

            discoveryListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String serviceType) {}

                @Override
                public void onServiceFound(NsdServiceInfo serviceInfo) {
                    if (!SERVICE_TYPE.equals(serviceInfo.getServiceType())) return;
                    ResolveListenerWrapper wrapper = new ResolveListenerWrapper(serviceInfo);
                    synchronized (lock) {
                        pendingResolves.add(wrapper);
                    }
                    try {
                        nsdManager.resolveService(serviceInfo, wrapper);
                    } catch (Exception ignored) {}
                }

                @Override
                public void onServiceLost(NsdServiceInfo serviceInfo) {
                    String name = serviceInfo != null ? serviceInfo.getServiceName() : null;
                    if (name != null) {
                        synchronized (lock) {
                            discoveredSenders.removeIf(s -> s.serviceName.equals(name));
                        }
                    }
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {}

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    synchronized (lock) {
                        discovering = false;
                        lastError = "Failed to discover Marucast senders on this network.";
                    }
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                    synchronized (lock) {
                        discovering = false;
                    }
                }
            };

            try {
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            } catch (Exception error) {
                discovering = false;
                lastError = "Could not start Marucast discovery.";
            }
        }
    }

    public void stopDiscovery() {
        synchronized (lock) {
            if (!discovering) return;
            discovering = false;
            if (nsdManager != null && discoveryListener != null) {
                try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) {}
            }
            pendingResolves.clear();
        }
    }

    public String getDiscoveredSendersJson() {
        JSONArray arr = new JSONArray();
        synchronized (lock) {
            for (DiscoveredSender sender : discoveredSenders) {
                JSONObject obj = new JSONObject();
                try {
                    obj.put("name", sender.displayName);
                    obj.put("host", sender.host);
                    obj.put("port", sender.port);
                    obj.put("code", sender.pairingCode != null ? sender.pairingCode : "");
                    obj.put("mode", sender.relayMode != null ? sender.relayMode : "");
                    obj.put("title", sender.title != null ? sender.title : "");
                } catch (Exception ignored) {}
                arr.put(obj);
            }
        }
        return arr.toString();
    }

    public void connectToSender(String host, int port, String senderName) {
        synchronized (lock) {
            if (connected) disconnectLocked();
            lastError = null;
            connectedSenderHost = host;
            connectedSenderPort = port;
            connectedSenderName = senderName;
            connected = true;
        }

        receiverThread = new Thread(this::runReceiverLoop, "MarucastReceiver");
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    public void disconnect() {
        synchronized (lock) {
            if (!connected) return;
            disconnectLocked();
        }
    }

    public void setVolume(float rawVolume) {
        synchronized (lock) {
            volume = Math.max(0f, Math.min(1f, rawVolume));
            if (audioTrack != null) {
                try { audioTrack.setVolume(volume); } catch (Exception ignored) {}
            }
        }
    }

    public String getStatusJson() {
        JSONObject payload = new JSONObject();
        synchronized (lock) {
            try {
                payload.put("connected", connected);
                payload.put("senderName", connectedSenderName != null ? connectedSenderName : "");
                payload.put("senderHost", connectedSenderHost != null ? connectedSenderHost : "");
                payload.put("senderPort", connectedSenderPort);
                payload.put("pairingCode", connectedPairingCode != null ? connectedPairingCode : "");
                payload.put("volume", volume);
                payload.put("discovering", discovering);
                payload.put("lastError", lastError != null ? lastError : "");
            } catch (Exception ignored) {}
        }
        return payload.toString();
    }

    private void disconnectLocked() {
        connected = false;
        connectedSenderName = null;
        connectedSenderHost = null;
        connectedSenderPort = 0;
        connectedPairingCode = null;

        if (receiverThread != null) {
            receiverThread.interrupt();
            try { receiverThread.join(2000L); } catch (InterruptedException ignored) {}
            receiverThread = null;
        }

        if (audioTrack != null) {
            try { audioTrack.stop(); audioTrack.release(); } catch (Exception ignored) {}
            audioTrack = null;
        }

        if (receiverSocket != null) {
            try { receiverSocket.close(); } catch (Exception ignored) {}
            receiverSocket = null;
        }
    }

    private void runReceiverLoop() {
        String host;
        int port;
        synchronized (lock) {
            host = connectedSenderHost;
            port = connectedSenderPort;
        }

        if (host == null || port <= 0) {
            synchronized (lock) {
                lastError = "No sender selected.";
                connected = false;
            }
            return;
        }

        Socket socket = null;
        AudioTrack track = null;

        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            /* Send HTTP GET for /live.pcm */
            String request = "GET /live.pcm HTTP/1.1\r\n" +
                "Host: " + host + ":" + port + "\r\n" +
                "Accept: application/octet-stream\r\n" +
                "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            /* Parse HTTP response headers */
            InputStream input = socket.getInputStream();
            readHttpResponseHeaders(input);

            /* Prepare AudioTrack */
            int bufferSize = Math.max(BUFFER_SIZE_BYTES, 65536);
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
            AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
            track = new AudioTrack(
                attrs,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            );

            synchronized (lock) {
                audioTrack = track;
                receiverSocket = socket;
                track.setVolume(volume);
            }

            track.play();

            byte[] buffer = new byte[READ_CHUNK_SIZE];
            while (!Thread.currentThread().isInterrupted()) {
                synchronized (lock) {
                    if (!connected) break;
                }
                int read = input.read(buffer);
                if (read <= 0) {
                    if (read < 0) break;
                    continue;
                }
                track.write(buffer, 0, read);
            }
        } catch (SocketTimeoutException error) {
            synchronized (lock) {
                if (connected) lastError = "Connection to sender timed out.";
            }
        } catch (IOException error) {
            synchronized (lock) {
                if (connected) lastError = "Lost connection to Marucast sender.";
            }
        } catch (Exception error) {
            synchronized (lock) {
                if (connected) lastError = "Receiver error: " + error.getMessage();
            }
        } finally {
            synchronized (lock) {
                audioTrack = null;
                receiverSocket = null;
            }
            if (track != null) {
                try { track.stop(); track.release(); } catch (Exception ignored) {}
            }
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
            synchronized (lock) {
                connected = false;
            }
        }
    }

    private void readHttpResponseHeaders(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        boolean headersComplete = false;

        while (!headersComplete) {
            b = input.read();
            if (b < 0) throw new IOException("Unexpected end of stream while reading headers.");
            if (b == '\r') continue;
            if (b == '\n') {
                String headerLine = line.toString();
                line.setLength(0);
                if (headerLine.isEmpty()) {
                    headersComplete = true;
                    break;
                }
            } else {
                line.append((char) b);
            }
        }
    }

    private final class ResolveListenerWrapper implements NsdManager.ResolveListener {
        private final NsdServiceInfo serviceInfo;

        ResolveListenerWrapper(NsdServiceInfo info) {
            serviceInfo = info;
        }

        @Override
        public void onResolveFailed(NsdServiceInfo info, int errorCode) {
            synchronized (lock) {
                pendingResolves.remove(this);
            }
        }

        @Override
        public void onServiceResolved(NsdServiceInfo info) {
            synchronized (lock) {
                pendingResolves.remove(this);
                if (info == null) return;

                String name = info.getServiceName();
                String host = info.getHost() != null ? info.getHost().getHostAddress() : null;
                int port = info.getPort();

                if (name == null || host == null || port <= 0) return;

                /* Skip if already known */
                for (DiscoveredSender existing : discoveredSenders) {
                    if (existing.serviceName.equals(name)) return;
                }

                Map<String, byte[]> attrs = info.getAttributes();
                String code = attrs.containsKey("code") ? new String(attrs.get("code"), StandardCharsets.UTF_8) : null;
                String title = attrs.containsKey("title") ? new String(attrs.get("title"), StandardCharsets.UTF_8) : null;
                String mode = attrs.containsKey("mode") ? new String(attrs.get("mode"), StandardCharsets.UTF_8) : null;

                String displayName = title != null && !title.isEmpty() ? title : name;

                discoveredSenders.add(new DiscoveredSender(
                    name, displayName, host, port, code, mode
                ));
            }
        }
    }

    private static final class DiscoveredSender {
        final String serviceName;
        final String displayName;
        final String host;
        final int port;
        final String pairingCode;
        final String relayMode;
        final String title;

        DiscoveredSender(String serviceName, String displayName, String host, int port,
                         String pairingCode, String relayMode) {
            this.serviceName = serviceName;
            this.displayName = displayName;
            this.host = host;
            this.port = port;
            this.pairingCode = pairingCode;
            this.relayMode = relayMode;
            this.title = null;
        }
    }
}
