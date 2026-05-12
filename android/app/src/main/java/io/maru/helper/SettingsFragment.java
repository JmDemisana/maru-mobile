package io.maru.helper;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SettingsFragment extends Fragment {
    private static final String SCHEDEDIT_PACKAGE = "io.maru.schededit";
    private static final String SCHEDEDIT_APK = "maru-schededit.apk";

    private MainActivity activity;

    private TextView versionText;
    private TextView originText;
    private TextView installationIdText;
    private TextView secureStorageText;
    private TextView helperUpdateStatusText;
    private TextView helperUpdateNoteText;
    private TextView sharedAccountStatusText;
    private TextView sharedAccountNoteText;
    private TextView stemStatusText;
    private TextView stemNoteText;
    private TextView schedEditNoteText;
    private TextView elevationStatusText;
    private TextView elevationNoteText;
    private TextView elevationMessageText;

    private Button checkHelperUpdateButton;
    private Button downloadHelperUpdateButton;
    private Button notificationButton;
    private Button sharedAccountButton;
    private Button schedEditButton;
    private Button openElevationButton;
    private Button toggleElevationLastFmButton;
    private Button removeElevationButton;

    private boolean helperUpdateChecking = false;
    private String helperUpdateDownloadUrl = "";
    private String helperUpdateVersion = "";
    private String helperUpdateError = "";

    private boolean schedEditLookupLoading = false;
    private String schedEditDownloadUrl = "";
    private String schedEditLookupError = "";

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        activity = (MainActivity) requireActivity();

        versionText = view.findViewById(R.id.version_text);
        originText = view.findViewById(R.id.origin_text);
        installationIdText = view.findViewById(R.id.installation_id_text);
        secureStorageText = view.findViewById(R.id.secure_storage_text);
        helperUpdateStatusText = view.findViewById(R.id.helper_update_status_text);
        helperUpdateNoteText = view.findViewById(R.id.helper_update_note_text);
        sharedAccountStatusText = view.findViewById(R.id.shared_account_status_text);
        sharedAccountNoteText = view.findViewById(R.id.shared_account_note_text);
        stemStatusText = view.findViewById(R.id.stem_status_text);
        stemNoteText = view.findViewById(R.id.stem_note_text);
        schedEditNoteText = view.findViewById(R.id.schededit_note_text);
        elevationStatusText = view.findViewById(R.id.elevation_status_text);
        elevationNoteText = view.findViewById(R.id.elevation_note_text);
        elevationMessageText = view.findViewById(R.id.elevation_message_text);

        checkHelperUpdateButton = view.findViewById(R.id.btn_check_helper_update);
        downloadHelperUpdateButton = view.findViewById(R.id.btn_download_helper_update);
        notificationButton = view.findViewById(R.id.btn_open_notification_settings);
        sharedAccountButton = view.findViewById(R.id.btn_open_shared_account);
        schedEditButton = view.findViewById(R.id.btn_schededit);
        openElevationButton = view.findViewById(R.id.btn_open_elevation);
        toggleElevationLastFmButton = view.findViewById(R.id.btn_toggle_elevation_lastfm);
        removeElevationButton = view.findViewById(R.id.btn_remove_elevation);

        bindStaticDeviceInfo();
        bindActions();
        renderState();
        loadHelperUpdate(false);
        loadSchedEditReleaseIfNeeded(false);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (activity != null) {
            bindStaticDeviceInfo();
            renderState();
        }
    }

    private void bindStaticDeviceInfo() {
        versionText.setText(resolveVersion());
        originText.setText(firstNonEmpty(activity.getServerOrigin(), "Not saved yet"));
        installationIdText.setText(activity.getInstallationId());

        boolean secureStorageReady = activity.hasSecureElevationStorage();
        bindStatusValue(
            secureStorageText,
            secureStorageReady ? "Ready" : "Unavailable",
            secureStorageReady ? R.color.accent_green : R.color.accent_yellow
        );
    }

    private void bindActions() {
        notificationButton.setOnClickListener(view -> activity.openNotificationListenerSettings());
        sharedAccountButton.setOnClickListener(view -> activity.openSharedAccountSite());
        checkHelperUpdateButton.setOnClickListener(view -> loadHelperUpdate(true));
        downloadHelperUpdateButton.setOnClickListener(view -> {
            if (helperUpdateDownloadUrl.isEmpty()) {
                loadHelperUpdate(true);
                return;
            }

            activity.downloadApk(helperUpdateDownloadUrl, "maru-link.apk");
            Toast.makeText(
                view.getContext(),
                "Downloading Maru Link update...",
                Toast.LENGTH_SHORT
            ).show();
        });
        schedEditButton.setOnClickListener(view -> handleSchedEditClick());
        openElevationButton.setOnClickListener(view -> activity.openElevationAuth());
        toggleElevationLastFmButton.setOnClickListener(view -> activity.toggleElevationLastFm());
        removeElevationButton.setOnClickListener(view -> activity.clearElevationAccess());
    }

    private void handleSchedEditClick() {
        if (activity.isPackageInstalled(SCHEDEDIT_PACKAGE)) {
            boolean opened = activity.openInstalledApp(SCHEDEDIT_PACKAGE);
            if (!opened) {
                Toast.makeText(
                    requireContext(),
                    "SchedEdit could not open right now.",
                    Toast.LENGTH_SHORT
                ).show();
            }
            return;
        }

        if (schedEditLookupLoading) {
            return;
        }

        if (!schedEditDownloadUrl.isEmpty()) {
            activity.downloadApk(schedEditDownloadUrl, SCHEDEDIT_APK);
            Toast.makeText(
                requireContext(),
                "Downloading SchedEdit...",
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        loadSchedEditReleaseIfNeeded(true);
    }

    private void loadHelperUpdate(boolean manualRefresh) {
        if (helperUpdateChecking) {
            return;
        }

        helperUpdateChecking = true;
        helperUpdateError = "";
        if (manualRefresh) {
            helperUpdateDownloadUrl = "";
            helperUpdateVersion = "";
        }
        renderState();

        new Thread(() -> {
            String latestVersion = "";
            String downloadUrl = "";
            String errorMessage = "";

            try {
                HelperReleaseManager.ReleaseAssetInfo asset =
                    HelperReleaseManager.fetchLatestHelperReleaseAsset();
                String currentVersion = resolveVersion();
                latestVersion = asset.getReleaseVersion();
                if (HelperReleaseManager.compareVersions(latestVersion, currentVersion) > 0) {
                    downloadUrl = asset.downloadUrl;
                }
            } catch (Exception error) {
                errorMessage =
                    error.getMessage() == null || error.getMessage().trim().isEmpty()
                        ? "Could not check the latest helper release."
                        : error.getMessage().trim();
            }

            final String latestVersionFinal = latestVersion;
            final String downloadUrlFinal = downloadUrl;
            final String errorMessageFinal = errorMessage;
            if (!isAdded()) {
                return;
            }

            requireActivity().runOnUiThread(() -> {
                helperUpdateChecking = false;
                helperUpdateVersion = latestVersionFinal;
                helperUpdateDownloadUrl = downloadUrlFinal;
                helperUpdateError = errorMessageFinal;
                renderState();
            });
        }).start();
    }

    private void loadSchedEditReleaseIfNeeded(boolean manualRefresh) {
        if (activity.isPackageInstalled(SCHEDEDIT_PACKAGE) || schedEditLookupLoading) {
            return;
        }

        if (!manualRefresh && !schedEditDownloadUrl.isEmpty()) {
            return;
        }

        schedEditLookupLoading = true;
        schedEditLookupError = "";
        if (manualRefresh) {
            schedEditDownloadUrl = "";
        }
        renderState();

        new Thread(() -> {
            String resolvedUrl = "";
            String errorMessage = "";
            try {
                List<String> assetNames = new ArrayList<>();
                assetNames.add(SCHEDEDIT_APK);
                Map<String, HelperReleaseManager.ReleaseAssetInfo> assets =
                    HelperReleaseManager.fetchLatestAssetsByName(assetNames);
                HelperReleaseManager.ReleaseAssetInfo asset =
                    assets.get(SCHEDEDIT_APK.toLowerCase());
                if (asset == null || asset.downloadUrl.isEmpty()) {
                    errorMessage = "SchedEdit is not in the recent mobile releases right now.";
                } else {
                    resolvedUrl = asset.downloadUrl;
                }
            } catch (Exception error) {
                errorMessage =
                    error.getMessage() == null || error.getMessage().trim().isEmpty()
                        ? "Could not find the SchedEdit download right now."
                        : error.getMessage().trim();
            }

            final String resolvedUrlFinal = resolvedUrl;
            final String errorMessageFinal = errorMessage;
            if (!isAdded()) {
                return;
            }

            requireActivity().runOnUiThread(() -> {
                schedEditLookupLoading = false;
                schedEditDownloadUrl = resolvedUrlFinal;
                schedEditLookupError = errorMessageFinal;
                renderState();
            });
        }).start();
    }

    private void renderState() {
        bindHelperUpdateState();
        bindSharedAccountState();
        bindStemState();
        bindSchedEditState();
        bindElevationState();
    }

    private void bindHelperUpdateState() {
        String currentVersion = resolveVersion();
        int publishedComparison = helperUpdateVersion.isEmpty()
            ? 0
            : HelperReleaseManager.compareVersions(helperUpdateVersion, currentVersion);
        if (helperUpdateChecking) {
            bindStatusValue(
                helperUpdateStatusText,
                "Checking GitHub release...",
                R.color.accent_yellow
            );
            helperUpdateNoteText.setText("Looking for a newer Maru Link APK on the mobile repo.");
        } else if (!helperUpdateError.isEmpty()) {
            bindStatusValue(
                helperUpdateStatusText,
                "Could not check right now",
                R.color.accent_yellow
            );
            helperUpdateNoteText.setText(helperUpdateError);
        } else if (!helperUpdateDownloadUrl.isEmpty()) {
            bindStatusValue(
                helperUpdateStatusText,
                "Update ready: " + helperUpdateVersion,
                R.color.accent_green
            );
            helperUpdateNoteText.setText(
                "This phone is on " + currentVersion + ". The mobile repo has a newer helper APK ready."
            );
        } else if (!helperUpdateVersion.isEmpty() && publishedComparison == 0) {
            bindStatusValue(
                helperUpdateStatusText,
                "Up to date",
                R.color.accent_green
            );
            helperUpdateNoteText.setText(
                "This helper already matches the latest mobile repo release (" +
                    helperUpdateVersion +
                    ")."
            );
        } else if (!helperUpdateVersion.isEmpty()) {
            bindStatusValue(
                helperUpdateStatusText,
                "Local build is newer",
                R.color.accent_green
            );
            helperUpdateNoteText.setText(
                "This phone is on " + currentVersion +
                    ", which is newer than the latest published mobile repo release (" +
                    helperUpdateVersion +
                    ")."
            );
        } else {
            bindStatusValue(
                helperUpdateStatusText,
                "Check for updates",
                R.color.text_secondary
            );
            helperUpdateNoteText.setText(
                "Maru Link checks the mobile repo release feed for a newer helper APK."
            );
        }

        checkHelperUpdateButton.setText(helperUpdateChecking ? "Checking..." : "Check For Update");
        checkHelperUpdateButton.setEnabled(!helperUpdateChecking);
        downloadHelperUpdateButton.setVisibility(
            helperUpdateDownloadUrl.isEmpty() ? View.GONE : View.VISIBLE
        );
        downloadHelperUpdateButton.setEnabled(!helperUpdateChecking);
    }

    private void bindSharedAccountState() {
        JSONObject sharedAccount = parseJsonObject(activity.getSharedAuthUserJson());
        String sharedFullName = optText(sharedAccount, "fullName");
        String sharedEmail = optText(sharedAccount, "email");
        boolean hasSharedAccount = !sharedFullName.isEmpty() || !sharedEmail.isEmpty();

        bindStatusValue(
            sharedAccountStatusText,
            hasSharedAccount ? "Website account ready" : "Website sign-in",
            hasSharedAccount ? R.color.accent_green : R.color.text_secondary
        );
        sharedAccountNoteText.setText(
            hasSharedAccount
                ? firstNonEmpty(sharedFullName, sharedEmail) + (
                    !sharedFullName.isEmpty() && !sharedEmail.isEmpty()
                        ? "  |  " + sharedEmail
                        : ""
                )
                : "Use Account Options on the website for sign-in. The helper keeps this section visible, but it does not own that login flow."
        );
    }

    private void bindStemState() {
        JSONObject stemState = parseJsonObject(activity.getStemModelStateJson());
        boolean stemInstalled = stemState.optBoolean("installed", false);
        bindStatusValue(
            stemStatusText,
            firstNonEmpty(
                optText(stemState, "label"),
                stemInstalled ? "Installed" : "Unavailable"
            ),
            stemInstalled ? R.color.accent_green : R.color.text_secondary
        );
        stemNoteText.setText(
            firstNonEmpty(
                optText(stemState, "note"),
                "Marucast Karaoke checks the helper's local stem model here."
            )
        );
    }

    private void bindSchedEditState() {
        boolean installed = activity.isPackageInstalled(SCHEDEDIT_PACKAGE);
        if (installed) {
            schedEditButton.setText("Open SchedEdit");
            schedEditButton.setEnabled(true);
            schedEditNoteText.setText(
                "SchedEdit is installed. Maru Link follows its synced reminders here."
            );
            return;
        }

        if (schedEditLookupLoading) {
            schedEditButton.setText("Checking SchedEdit...");
            schedEditButton.setEnabled(false);
            schedEditNoteText.setText("Looking for the latest SchedEdit APK in the mobile repo.");
            return;
        }

        if (!schedEditDownloadUrl.isEmpty()) {
            schedEditButton.setText("Get SchedEdit");
            schedEditButton.setEnabled(true);
            schedEditNoteText.setText("SchedEdit is ready to download from the mobile repo.");
            return;
        }

        if (!schedEditLookupError.isEmpty()) {
            schedEditButton.setText("Retry SchedEdit");
            schedEditButton.setEnabled(true);
            schedEditNoteText.setText(schedEditLookupError);
            return;
        }

        schedEditButton.setText("Get SchedEdit");
        schedEditButton.setEnabled(true);
        schedEditNoteText.setText(
            "Maru Link follows reminders from the separate SchedEdit app."
        );
    }

    private void bindElevationState() {
        boolean secureStorageReady = activity.hasSecureElevationStorage();
        boolean hasElevationAccess = activity.hasElevationAccess();
        boolean elevationBusy = activity.isElevationBusy();
        boolean lastFmEnabled = activity.isElevationLastFmEnabled();
        String elevationError = activity.getElevationStatusError();
        String elevationMessage = activity.getElevationStatusMessage();

        String elevationStatusLabel;
        int elevationStatusColor;
        if (!secureStorageReady) {
            elevationStatusLabel = "Encrypted storage needed";
            elevationStatusColor = R.color.accent_yellow;
        } else if (elevationBusy) {
            elevationStatusLabel = "Finishing in Maru Link";
            elevationStatusColor = R.color.accent_yellow;
        } else if (hasElevationAccess && lastFmEnabled) {
            elevationStatusLabel = "Last.fm alerts on";
            elevationStatusColor = R.color.accent_green;
        } else if (hasElevationAccess) {
            elevationStatusLabel = "Ready on this phone";
            elevationStatusColor = R.color.accent_green;
        } else {
            elevationStatusLabel = "PIN needed";
            elevationStatusColor = R.color.accent_yellow;
        }
        bindStatusValue(elevationStatusText, elevationStatusLabel, elevationStatusColor);

        elevationNoteText.setText(
            !secureStorageReady
                ? "This phone could not open encrypted helper storage, so elevated settings stay locked."
                : hasElevationAccess
                    ? (
                        lastFmEnabled
                            ? "Site Admin access is ready here, and Last.fm scrobble alerts can mirror to this phone."
                            : "Site Admin access is ready here. Turn on Last.fm alerts if you want scrobble mirroring on this device."
                    )
                    : "Open Elevation on the website, enter the Site Admin PIN there, then Maru Link will catch the handoff back here."
        );

        bindMessage(
            elevationMessageText,
            !elevationError.isEmpty() ? elevationError : elevationMessage,
            !elevationError.isEmpty()
        );

        openElevationButton.setText(hasElevationAccess ? "Reopen Elevation" : "Open Elevation");
        openElevationButton.setEnabled(secureStorageReady && !elevationBusy);

        toggleElevationLastFmButton.setText(
            hasElevationAccess
                ? lastFmEnabled
                    ? "Turn Last.fm Alerts Off"
                    : "Turn Last.fm Alerts On"
                : "Last.fm Alerts Need Elevation"
        );
        toggleElevationLastFmButton.setEnabled(
            secureStorageReady && hasElevationAccess && !elevationBusy
        );

        removeElevationButton.setVisibility(hasElevationAccess ? View.VISIBLE : View.GONE);
        removeElevationButton.setEnabled(!elevationBusy);
    }

    private String resolveVersion() {
        try {
            return requireContext()
                .getPackageManager()
                .getPackageInfo(requireContext().getPackageName(), 0)
                .versionName;
        } catch (Exception ignored) {
            return "Unknown";
        }
    }

    private JSONObject parseJsonObject(String rawJson) {
        try {
            String json = rawJson == null ? "" : rawJson.trim();
            return json.isEmpty() ? new JSONObject() : new JSONObject(json);
        } catch (Exception ignored) {
            return new JSONObject();
        }
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

    private void bindStatusValue(TextView view, String text, int colorResId) {
        view.setText(text);
        view.setTextColor(ContextCompat.getColor(requireContext(), colorResId));
    }

    private void bindMessage(TextView view, String text, boolean isError) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            view.setVisibility(View.GONE);
            view.setText("");
            return;
        }

        view.setVisibility(View.VISIBLE);
        view.setText(value);
        view.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                isError ? R.color.red_status : R.color.text_secondary
            )
        );
    }
}
