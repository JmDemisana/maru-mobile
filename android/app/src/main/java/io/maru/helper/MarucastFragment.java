package io.maru.helper;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.chip.ChipGroup;

import org.json.JSONObject;

public class MarucastFragment extends Fragment {
    private static final long STATUS_POLL_MS = 1000L;
    private static final long RECEIVER_SURFACE_REFRESH_MS = 5000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusPoller = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, STATUS_POLL_MS);
        }
    };

    private ChipGroup statusChipGroup;
    private ChipGroup sourceMetaChipGroup;
    private TextView sourceTitleView;
    private TextView sourceSubtitleView;
    private TextView linkedReceiverLabelView;
    private TextView linkedReceiverDetailsView;
    private TextView linkedReceiverNoteView;
    private TextView warningView;
    private TextView messageView;
    private TextView errorView;
    private View micMeterSection;
    private TextView micMeterValueView;
    private ProgressBar micMeterProgress;
    private TextView micMeterNoteView;
    private TextView delayValueView;
    private TextView micMixValueView;
    private TextView musicBedValueView;
    private TextView tvVolumeValueView;
    private TextView tvVolumeNoteView;
    private Button phoneAudioButton;
    private Button pickAudioButton;
    private Button playChosenButton;
    private Button stopButton;
    private Button karaokeButton;
    private Button phoneMicButton;
    private Button lyricTimingButton;
    private Button clearSongButton;
    private Button scanReceiverButton;
    private Button clearReceiverButton;
    private Button mediaAccessButton;
    private Button delayDownButton;
    private Button delayResetButton;
    private Button delayUpButton;
    private Button micDownButton;
    private Button micUpButton;
    private Button musicDownButton;
    private Button musicUpButton;
    private Button tvVolumeDownButton;
    private Button tvVolumeUpButton;

    private String linkedReceiverSurface = null;
    private String linkedReceiverSurfaceError = "";
    private String lastReceiverSurfaceFetchKey = "";
    private long lastReceiverSurfaceFetchAtMs = 0L;
    private boolean receiverSurfaceLoading = false;

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        View view = inflater.inflate(R.layout.fragment_marucast, container, false);

        statusChipGroup = view.findViewById(R.id.marucast_status_chips);
        sourceMetaChipGroup = view.findViewById(R.id.marucast_source_meta_chips);
        sourceTitleView = view.findViewById(R.id.marucast_source_title);
        sourceSubtitleView = view.findViewById(R.id.marucast_source_subtitle);
        linkedReceiverLabelView = view.findViewById(R.id.marucast_linked_receiver_label);
        linkedReceiverDetailsView = view.findViewById(R.id.marucast_linked_receiver_details);
        linkedReceiverNoteView = view.findViewById(R.id.marucast_linked_receiver_note);
        warningView = view.findViewById(R.id.marucast_warning);
        messageView = view.findViewById(R.id.marucast_message);
        errorView = view.findViewById(R.id.marucast_error);
        micMeterSection = view.findViewById(R.id.marucast_mic_meter_section);
        micMeterValueView = view.findViewById(R.id.marucast_mic_meter_value);
        micMeterProgress = view.findViewById(R.id.marucast_mic_meter_progress);
        micMeterNoteView = view.findViewById(R.id.marucast_mic_meter_note);
        delayValueView = view.findViewById(R.id.marucast_delay_value);
        micMixValueView = view.findViewById(R.id.marucast_mic_mix_value);
        musicBedValueView = view.findViewById(R.id.marucast_music_bed_value);
        tvVolumeValueView = view.findViewById(R.id.marucast_tv_volume_value);
        tvVolumeNoteView = view.findViewById(R.id.marucast_tv_volume_note);
        phoneAudioButton = view.findViewById(R.id.btn_phone_audio);
        pickAudioButton = view.findViewById(R.id.btn_pick_audio);
        playChosenButton = view.findViewById(R.id.btn_play_chosen_audio);
        stopButton = view.findViewById(R.id.btn_stop_broadcast);
        karaokeButton = view.findViewById(R.id.btn_toggle_karaoke);
        phoneMicButton = view.findViewById(R.id.btn_toggle_mic);
        lyricTimingButton = view.findViewById(R.id.btn_start_sync_clock);
        clearSongButton = view.findViewById(R.id.btn_clear_audio);
        scanReceiverButton = view.findViewById(R.id.btn_scan_receiver_qr);
        clearReceiverButton = view.findViewById(R.id.btn_clear_receiver_qr);
        mediaAccessButton = view.findViewById(R.id.btn_notification_access);
        delayDownButton = view.findViewById(R.id.btn_delay_down);
        delayResetButton = view.findViewById(R.id.btn_delay_reset);
        delayUpButton = view.findViewById(R.id.btn_delay_up);
        micDownButton = view.findViewById(R.id.btn_mic_down);
        micUpButton = view.findViewById(R.id.btn_mic_up);
        musicDownButton = view.findViewById(R.id.btn_music_down);
        musicUpButton = view.findViewById(R.id.btn_music_up);
        tvVolumeDownButton = view.findViewById(R.id.btn_tv_volume_down);
        tvVolumeUpButton = view.findViewById(R.id.btn_tv_volume_up);

        styleNoticeView(
            warningView,
            Color.parseColor("#1F1710"),
            Color.parseColor("#5C3D1B"),
            ContextCompat.getColor(requireContext(), R.color.accent_yellow)
        );
        styleNoticeView(
            messageView,
            Color.parseColor("#0E1C18"),
            Color.parseColor("#214438"),
            ContextCompat.getColor(requireContext(), R.color.accent_green)
        );
        styleNoticeView(
            errorView,
            Color.parseColor("#231112"),
            Color.parseColor("#5B2528"),
            Color.parseColor("#FFD3D8")
        );
        if (micMeterProgress.getProgressDrawable() != null) {
            micMeterProgress.getProgressDrawable().setTint(
                ContextCompat.getColor(requireContext(), R.color.nav_item_active)
            );
        }

        View[] buttons = new View[] {
            phoneAudioButton,
            pickAudioButton,
            playChosenButton,
            stopButton,
            karaokeButton,
            phoneMicButton,
            lyricTimingButton,
            clearSongButton,
            scanReceiverButton,
            clearReceiverButton,
            mediaAccessButton,
            delayDownButton,
            delayResetButton,
            delayUpButton,
            micDownButton,
            micUpButton,
            musicDownButton,
            musicUpButton,
            tvVolumeDownButton,
            tvVolumeUpButton
        };
        for (View button : buttons) {
            if (button instanceof Button) {
                ((Button) button).setAllCaps(false);
            }
        }

        phoneAudioButton.setOnClickListener(v -> {
            MainActivity activity = getMainActivity();
            activity.setMarucastPanelMessage(
                activity.getLinkedMarucastReceiverToken().isEmpty()
                    ? "Approve Android's playback capture prompt."
                    : "Approve Android's playback capture prompt for the linked receiver."
            );
            activity.startMarucastPlaybackRelay();
            refreshStatus();
        });
        pickAudioButton.setOnClickListener(v -> {
            getMainActivity().setMarucastPanelMessage("Choose a local audio file on this phone.");
            getMainActivity().pickMarucastAudioFile();
            refreshStatus();
        });
        playChosenButton.setOnClickListener(v -> {
            getMainActivity().setMarucastPanelMessage("Starting the chosen song.");
            getMainActivity().startMarucastSender();
            refreshStatus();
        });
        stopButton.setOnClickListener(v -> {
            getMainActivity().setMarucastPanelMessage("Marucast stopped.");
            getMainActivity().stopMarucastSender();
            refreshStatus();
        });
        karaokeButton.setOnClickListener(v -> {
            JSONObject status = getStatusObject();
            boolean karaokeOn = "remove".equals(optText(status, "vocalMode"));
            getMainActivity().setMarucastPanelMessage(
                karaokeOn ? "Bringing the full song back." : "Turning Karaoke on."
            );
            getMainActivity().dispatchMarucastControlCommand(
                karaokeOn ? "vocal-normal" : "vocal-remove"
            );
            refreshStatus();
        });
        phoneMicButton.setOnClickListener(v -> {
            JSONObject status = getStatusObject();
            boolean micActive = status.optBoolean("micCaptureActive", false);
            getMainActivity().setMarucastPanelMessage(
                micActive ? "Stopping the phone mic lane." : "Turning the phone mic lane on."
            );
            getMainActivity().dispatchMarucastControlCommand(
                micActive ? "mic-stop" : "mic-start"
            );
            refreshStatus();
        });
        lyricTimingButton.setOnClickListener(v -> {
            getMainActivity().setMarucastPanelMessage("Native lyric timing is live.");
            getMainActivity().startMarucastSyncClock();
            refreshStatus();
        });
        clearSongButton.setOnClickListener(v -> {
            getMainActivity().setMarucastPanelMessage("Chosen song cleared.");
            getMainActivity().clearMarucastAudioSelection();
            refreshStatus();
        });
        scanReceiverButton.setOnClickListener(v -> getMainActivity().startMarucastReceiverQrScan());
        clearReceiverButton.setOnClickListener(v -> {
            linkedReceiverSurface = null;
            linkedReceiverSurfaceError = "";
            lastReceiverSurfaceFetchKey = "";
            lastReceiverSurfaceFetchAtMs = 0L;
            receiverSurfaceLoading = false;
            getMainActivity().clearLinkedMarucastReceiver();
            refreshStatus();
        });
        mediaAccessButton.setOnClickListener(v -> getMainActivity().openNotificationListenerSettings());
        delayDownButton.setOnClickListener(v -> sendMarucastCommand("delay-down", "Song delay lowered."));
        delayResetButton.setOnClickListener(v -> sendMarucastCommand("delay-reset", "Song delay reset."));
        delayUpButton.setOnClickListener(v -> sendMarucastCommand("delay-up", "Song delay raised."));
        micDownButton.setOnClickListener(v -> sendMarucastCommand("mic-gain-down", "Mic level lowered."));
        micUpButton.setOnClickListener(v -> sendMarucastCommand("mic-gain-up", "Mic level raised."));
        musicDownButton.setOnClickListener(v -> sendMarucastCommand("music-bed-down", "Music level lowered."));
        musicUpButton.setOnClickListener(v -> sendMarucastCommand("music-bed-up", "Music level raised."));
        tvVolumeDownButton.setOnClickListener(v -> sendLinkedReceiverCommand("tv-volume-down"));
        tvVolumeUpButton.setOnClickListener(v -> sendLinkedReceiverCommand("tv-volume-up"));

        refreshStatus();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(statusPoller);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(statusPoller);
    }

    private MainActivity getMainActivity() {
        return (MainActivity) requireActivity();
    }

    private JSONObject getStatusObject() {
        try {
            return new JSONObject(getMainActivity().getMarucastStatus());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private void refreshStatus() {
        if (getView() == null || !isAdded()) {
            return;
        }

        JSONObject status = getStatusObject();
        MainActivity activity = getMainActivity();
        String receiverToken = activity.getLinkedMarucastReceiverToken().trim();
        String serverOrigin = activity.getLinkedMarucastServerOrigin().trim();

        maybeRefreshLinkedReceiverSurface(receiverToken, serverOrigin);

        String relayMode = optText(status, "relayMode");
        boolean running = status.optBoolean("running", false);
        boolean captureActive = status.optBoolean("captureActive", false);
        boolean micActive = status.optBoolean("micCaptureActive", false);
        boolean wifiConnected = status.optBoolean("wifiConnected", true);
        boolean mediaAccessEnabled = status.optBoolean("mediaAccessEnabled", false);
        boolean karaokeReady = status.optBoolean("vocalStemModelReady", false);
        boolean karaokeOn = "remove".equals(optText(status, "vocalMode"));
        boolean receiverConnected =
            Math.max(0, status.optInt("activeNetworkRelayClients", 0)) > 0 ||
                Math.max(0, status.optInt("activeLoopbackRelayClients", 0)) > 0;
        int activeMicClients = Math.max(0, status.optInt("activeNetworkMicClients", 0));
        double micLevel = status.optDouble("micLevel", 0.0d);

        sourceTitleView.setText(getSourceTitle(status));
        sourceSubtitleView.setText(getSourceSubtitle(status, relayMode, running));

        rebuildChipGroup(statusChipGroup);
        addStatusChip(
            statusChipGroup,
            wifiConnected ? "Same Wi-Fi" : "Wi-Fi needed",
            wifiConnected ? Color.parseColor("#1A2A24") : Color.parseColor("#2C2011"),
            wifiConnected ? ContextCompat.getColor(requireContext(), R.color.accent_green) : ContextCompat.getColor(requireContext(), R.color.accent_yellow)
        );
        addStatusChip(
            statusChipGroup,
            getBroadcastModeLabel(relayMode, running),
            Color.parseColor("#172033"),
            ContextCompat.getColor(requireContext(), R.color.text_primary)
        );
        addStatusChip(
            statusChipGroup,
            receiverConnected
                ? "Receiver connected"
                : !receiverToken.isEmpty()
                    ? "Receiver ready"
                    : getPairingCodeLabel(status),
            receiverConnected ? Color.parseColor("#1A2A24") : Color.parseColor("#172033"),
            receiverConnected ? ContextCompat.getColor(requireContext(), R.color.accent_green) : ContextCompat.getColor(requireContext(), R.color.text_primary)
        );
        addStatusChip(
            statusChipGroup,
            getStemStatusLabel(karaokeReady, karaokeOn),
            karaokeOn ? Color.parseColor("#1E2335") : Color.parseColor("#141A2A"),
            karaokeOn ? Color.parseColor("#DDE6FF") : ContextCompat.getColor(requireContext(), R.color.text_secondary)
        );
        if (micActive) {
            addStatusChip(
                statusChipGroup,
                "Phone mic on",
                Color.parseColor("#1A2534"),
                ContextCompat.getColor(requireContext(), R.color.text_primary)
            );
        }

        rebuildChipGroup(sourceMetaChipGroup);
        String pairingCode = optText(status, "pairingCode");
        if (!receiverToken.isEmpty()) {
            addStatusChip(
                sourceMetaChipGroup,
                "Opened for a waiting receiver",
                Color.parseColor("#172033"),
                ContextCompat.getColor(requireContext(), R.color.text_secondary)
            );
        }
        String serviceName = optText(status, "serviceName");
        if (!serviceName.isEmpty()) {
            addStatusChip(
                sourceMetaChipGroup,
                serviceName,
                Color.parseColor("#172033"),
                ContextCompat.getColor(requireContext(), R.color.text_secondary)
            );
        }
        if (!pairingCode.isEmpty()) {
            addStatusChip(
                sourceMetaChipGroup,
                "Pair " + pairingCode,
                Color.parseColor("#172033"),
                ContextCompat.getColor(requireContext(), R.color.text_primary)
            );
        }

        String linkedSurfaceLabel;
        if (receiverToken.isEmpty()) {
            linkedSurfaceLabel = "";
            linkedReceiverLabelView.setText("No receiver QR linked yet");
            linkedReceiverDetailsView.setText("Scan the QR from a waiting website receiver on the same Wi-Fi.");
        } else {
            linkedSurfaceLabel = getReceiverSurfaceLabel(linkedReceiverSurface, receiverSurfaceLoading);
            linkedReceiverLabelView.setText("Receiver QR linked");
            String receiverDetails = "Token " + shortenToken(receiverToken);
            if (!serverOrigin.isEmpty()) {
                receiverDetails += "  |  " + serverOrigin;
            }
            linkedReceiverDetailsView.setText(receiverDetails);
        }

        if (receiverToken.isEmpty()) {
            linkedReceiverNoteView.setText("TV volume stays locked until a receiver QR is linked.");
        } else if (!linkedReceiverSurfaceError.isEmpty()) {
            linkedReceiverNoteView.setText(linkedReceiverSurfaceError);
        } else {
            linkedReceiverNoteView.setText(
                linkedSurfaceLabel.isEmpty()
                    ? "Checking what kind of receiver this is..."
                    : "Receiver type: " + linkedSurfaceLabel
            );
        }

        scanReceiverButton.setText(receiverToken.isEmpty() ? "Scan Receiver QR" : "Rescan Receiver QR");
        clearReceiverButton.setVisibility(receiverToken.isEmpty() ? View.GONE : View.VISIBLE);

        String warningMessage = optText(status, "sameWifiRequiredMessage");
        String activityError = activity.getMarucastPanelError().trim();
        String statusError = firstNonEmpty(
            optText(status, "lastError"),
            optText(status, "micLastError")
        );
        String resolvedError = firstNonEmpty(activityError, statusError);
        String resolvedMessage = firstNonEmpty(
            activity.getMarucastPanelMessage().trim(),
            optText(status, "message")
        );

        bindNotice(warningView, warningMessage);
        bindNotice(errorView, resolvedError);
        bindNotice(messageView, resolvedError.isEmpty() ? resolvedMessage : "");

        micMeterSection.setVisibility(micActive ? View.VISIBLE : View.GONE);
        int micPercent = (int) Math.round(clamp(micLevel, 0.0d, 1.0d) * 100.0d);
        micMeterValueView.setText(micPercent + "%");
        micMeterProgress.setProgress(micPercent);
        micMeterNoteView.setText(
            activeMicClients > 0
                ? activeMicClients + (activeMicClients == 1 ? " receiver is listening." : " receivers are listening.")
                : "Mic active, but no separate phone mic lane is open yet."
        );

        delayValueView.setText(formatDelayLabel(status.optInt("relayDelayMs", 0)));
        micMixValueView.setText(formatPercent(status.optDouble("micMixGain", 1.28d), 1.28d));
        musicBedValueView.setText(formatPercent(status.optDouble("micMusicBedLevel", 0.8d), 0.8d));

        boolean tvSurface = "tv-app".equals(linkedReceiverSurface);
        boolean tvEnabled = tvSurface && receiverConnected && !receiverToken.isEmpty();
        tvVolumeValueView.setText(getTvVolumeValue(receiverToken, linkedReceiverSurface, receiverSurfaceLoading, receiverConnected));
        tvVolumeNoteView.setText(getTvVolumeNote(receiverToken, linkedReceiverSurface, receiverSurfaceLoading, receiverConnected));

        styleActionButton(phoneAudioButton, "playback-capture".equals(relayMode), false, true);
        styleActionButton(pickAudioButton, false, true, true);
        styleActionButton(playChosenButton, "local-file".equals(relayMode) || (running && !"sync-clock".equals(relayMode) && !captureActive), false, true);
        styleActionButton(stopButton, false, true, true);
        styleActionButton(karaokeButton, karaokeOn, karaokeOn, true);
        karaokeButton.setText(karaokeOn ? "Full Song" : "Karaoke");
        styleActionButton(phoneMicButton, micActive, micActive, true);
        phoneMicButton.setText(micActive ? "Mic Off" : "Phone Mic");
        styleActionButton(lyricTimingButton, "sync-clock".equals(relayMode), "sync-clock".equals(relayMode), true);
        styleActionButton(clearSongButton, false, true, true);
        styleActionButton(scanReceiverButton, !receiverToken.isEmpty(), true, true);
        styleActionButton(clearReceiverButton, false, true, !receiverToken.isEmpty());
        styleActionButton(mediaAccessButton, mediaAccessEnabled, true, true);
        mediaAccessButton.setText(mediaAccessEnabled ? "Media Access Ready" : "Open Media Access Settings");

        styleMiniButton(delayDownButton, true);
        styleMiniButton(delayResetButton, true);
        styleMiniButton(delayUpButton, true);
        styleMiniButton(micDownButton, true);
        styleMiniButton(micUpButton, true);
        styleMiniButton(musicDownButton, true);
        styleMiniButton(musicUpButton, true);
        styleMiniButton(tvVolumeDownButton, tvEnabled);
        styleMiniButton(tvVolumeUpButton, tvEnabled);
    }

    private void sendMarucastCommand(String command, String message) {
        getMainActivity().setMarucastPanelMessage(message);
        getMainActivity().dispatchMarucastControlCommand(command);
        refreshStatus();
    }

    private void sendLinkedReceiverCommand(String command) {
        MainActivity activity = getMainActivity();
        String receiverToken = activity.getLinkedMarucastReceiverToken().trim();
        String serverOrigin = activity.getLinkedMarucastServerOrigin().trim();
        if (receiverToken.isEmpty()) {
            activity.setMarucastPanelError("Link a receiver first.");
            refreshStatus();
            return;
        }

        activity.setMarucastPanelMessage(
            "tv-volume-up".equals(command)
                ? "Asking the linked TV receiver to raise volume..."
                : "Asking the linked TV receiver to lower volume..."
        );
        refreshStatus();

        MainActivity hostActivity = getMainActivity();
        new Thread(() -> {
            JSONObject response = hostActivity.sendMarucastLinkedReceiverCommand(
                command,
                receiverToken,
                serverOrigin
            );
            String nextSurface = normalizeReceiverSurface(optText(response, "receiverSurface"));
            boolean success = response.optBoolean("success", false);
            String error = optText(response, "error");

            hostActivity.runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }

                if (!nextSurface.isEmpty()) {
                    linkedReceiverSurface = nextSurface;
                }
                linkedReceiverSurfaceError = success ? "" : error;
                lastReceiverSurfaceFetchAtMs = System.currentTimeMillis();

                if (success) {
                    getMainActivity().setMarucastPanelMessage(
                        "tv-volume-up".equals(command)
                            ? "Asked the linked TV receiver to raise volume."
                            : "Asked the linked TV receiver to lower volume."
                    );
                } else {
                    getMainActivity().setMarucastPanelError(
                        error.isEmpty()
                            ? "Could not reach the linked Marucast receiver."
                            : error
                    );
                }
                refreshStatus();
            });
        }, "MarucastReceiverCommand").start();
    }

    private void maybeRefreshLinkedReceiverSurface(String receiverToken, String serverOrigin) {
        if (receiverToken.isEmpty()) {
            linkedReceiverSurface = null;
            linkedReceiverSurfaceError = "";
            receiverSurfaceLoading = false;
            lastReceiverSurfaceFetchKey = "";
            return;
        }

        String requestKey = receiverToken + "|" + serverOrigin;
        long now = System.currentTimeMillis();
        if (requestKey.equals(lastReceiverSurfaceFetchKey) &&
            (now - lastReceiverSurfaceFetchAtMs) < RECEIVER_SURFACE_REFRESH_MS) {
            return;
        }
        if (receiverSurfaceLoading) {
            return;
        }

        if (!requestKey.equals(lastReceiverSurfaceFetchKey)) {
            linkedReceiverSurface = null;
            linkedReceiverSurfaceError = "";
        }

        lastReceiverSurfaceFetchKey = requestKey;
        receiverSurfaceLoading = true;
        MainActivity hostActivity = getMainActivity();
        new Thread(() -> {
            JSONObject response = hostActivity.fetchMarucastLinkedReceiverStatus(
                receiverToken,
                serverOrigin
            );
            String nextSurface = normalizeReceiverSurface(optText(response, "receiverSurface"));
            String error = optText(response, "error");
            boolean success = response.optBoolean("success", true);

            hostActivity.runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }

                receiverSurfaceLoading = false;
                lastReceiverSurfaceFetchAtMs = System.currentTimeMillis();
                linkedReceiverSurface = nextSurface.isEmpty() ? null : nextSurface;
                linkedReceiverSurfaceError = success ? "" : error;
                refreshStatus();
            });
        }, "MarucastReceiverStatus").start();
    }

    private void rebuildChipGroup(ChipGroup group) {
        group.removeAllViews();
    }

    private void addStatusChip(ChipGroup group, String label, int backgroundColor, int textColor) {
        if (label == null || label.trim().isEmpty()) {
            return;
        }

        TextView pill = new TextView(requireContext());
        pill.setText(label.trim());
        pill.setTextColor(textColor);
        pill.setTextSize(12f);
        pill.setPadding(dp(12), dp(7), dp(12), dp(7));

        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        background.setCornerRadius(dp(999));
        pill.setBackground(background);

        ChipGroup.LayoutParams params = new ChipGroup.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) params).setMargins(0, 0, dp(8), dp(8));
        }
        group.addView(pill, params);
    }

    private void bindNotice(TextView view, String text) {
        if (text == null || text.trim().isEmpty()) {
            view.setVisibility(View.GONE);
            view.setText("");
            return;
        }

        view.setVisibility(View.VISIBLE);
        view.setText(text.trim());
    }

    private void styleNoticeView(TextView view, int fillColor, int strokeColor, int textColor) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), strokeColor);
        view.setBackground(background);
        view.setTextColor(textColor);
        int horizontal = dp(14);
        int vertical = dp(12);
        view.setPadding(horizontal, vertical, horizontal, vertical);
    }

    private void styleActionButton(Button button, boolean active, boolean subtle, boolean enabled) {
        int fillColor;
        int strokeColor;
        int textColor = ContextCompat.getColor(requireContext(), R.color.text_primary);
        if (active) {
            fillColor = Color.parseColor("#273A66");
            strokeColor = Color.parseColor("#4965AA");
        } else if (subtle) {
            fillColor = Color.parseColor("#131C2F");
            strokeColor = Color.parseColor("#27314B");
        } else {
            fillColor = Color.parseColor("#1B2742");
            strokeColor = Color.parseColor("#32496C");
        }

        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), strokeColor);
        button.setBackground(background);
        button.setTextColor(textColor);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.46f);
    }

    private void styleMiniButton(Button button, boolean enabled) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#141D31"));
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), Color.parseColor("#2A3650"));
        button.setBackground(background);
        button.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.42f);
    }

    private String getSourceTitle(JSONObject status) {
        return firstNonEmpty(
            optText(status, "mediaTitle"),
            optText(status, "selectedTitle"),
            "No source selected yet"
        );
    }

    private String getSourceSubtitle(JSONObject status, String relayMode, boolean running) {
        if ("sync-clock".equals(relayMode)) {
            return "Phone stays local while the helper shares native lyric timing.";
        }
        if ("local-file".equals(relayMode) || running) {
            return firstNonEmpty(
                optText(status, "mediaArtist"),
                optText(status, "mediaAppLabel"),
                "Ready to play."
            );
        }
        return firstNonEmpty(
            optText(status, "mediaArtist"),
            optText(status, "mediaAppLabel"),
            "Play something, then tap Phone Audio."
        );
    }

    private String getBroadcastModeLabel(String relayMode, boolean running) {
        if ("playback-capture".equals(relayMode)) {
            return "Phone audio";
        }
        if ("sync-clock".equals(relayMode)) {
            return "Lyric timing";
        }
        if ("local-file".equals(relayMode) || running) {
            return "Chosen song";
        }
        return "Idle";
    }

    private String getStemStatusLabel(boolean karaokeReady, boolean karaokeOn) {
        if (karaokeReady) {
            return karaokeOn ? "Karaoke on" : "Karaoke ready";
        }
        return karaokeOn ? "Karaoke starting" : "Full song";
    }

    private String getPairingCodeLabel(JSONObject status) {
        String pairingCode = optText(status, "pairingCode");
        return pairingCode.isEmpty() ? "No receiver yet" : "Pair " + pairingCode;
    }

    private String getReceiverSurfaceLabel(String receiverSurface, boolean loading) {
        if (loading && (receiverSurface == null || receiverSurface.trim().isEmpty())) {
            return "";
        }
        if ("tv-app".equals(receiverSurface)) {
            return "Maru TV";
        }
        if ("desktop-web".equals(receiverSurface)) {
            return "Desktop receiver";
        }
        if (loading) {
            return "";
        }
        return "Unknown receiver";
    }

    private String getTvVolumeValue(
        String receiverToken,
        String receiverSurface,
        boolean loading,
        boolean receiverConnected
    ) {
        if (receiverToken.isEmpty()) {
            return "No receiver";
        }
        if ("tv-app".equals(receiverSurface)) {
            return receiverConnected ? "TV app" : "TV waiting";
        }
        if ("desktop-web".equals(receiverSurface)) {
            return "Desktop";
        }
        return loading ? "Checking" : "Unknown";
    }

    private String getTvVolumeNote(
        String receiverToken,
        String receiverSurface,
        boolean loading,
        boolean receiverConnected
    ) {
        if (receiverToken.isEmpty()) {
            return "Link a receiver first.";
        }
        if ("tv-app".equals(receiverSurface)) {
            return receiverConnected
                ? "Use Maru TV's native volume controls here."
                : "TV app found. Connect it first.";
        }
        if ("desktop-web".equals(receiverSurface)) {
            return "Desktop receivers only expose browser volume.";
        }
        return loading ? "Checking receiver type." : "Could not confirm the linked receiver type yet.";
    }

    private String formatDelayLabel(int value) {
        if (value == 0) {
            return "0 ms";
        }
        return (value > 0 ? "+" : "") + value + " ms";
    }

    private String formatPercent(double rawValue, double fallback) {
        double safeValue = Double.isFinite(rawValue) ? rawValue : fallback;
        return Math.round(safeValue * 100.0d) + "%";
    }

    private String shortenToken(String token) {
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.length() <= 14) {
            return trimmed;
        }
        return trimmed.substring(0, 8) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    private String normalizeReceiverSurface(String value) {
        String trimmed = value == null ? "" : value.trim();
        if ("tv-app".equals(trimmed) || "desktop-web".equals(trimmed)) {
            return trimmed;
        }
        return "";
    }

    private String optText(JSONObject object, String key) {
        String value = object.optString(key, "").trim();
        if (value.isEmpty()) {
            return "";
        }
        String normalized = value.toLowerCase();
        return "null".equals(normalized) || "undefined".equals(normalized) ? "" : value;
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
