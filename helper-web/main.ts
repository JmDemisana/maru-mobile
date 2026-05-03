import { App as CapacitorApp } from "@capacitor/app";
import { Capacitor, CapacitorHttp } from "@capacitor/core";
import { PushNotifications } from "@capacitor/push-notifications";
import {
  applyMarucastBrowserSenderAnswer,
  getMarucastBrowserState,
  prepareMarucastBrowserLivePcmSender,
  stopMarucastBrowserSession,
  updateMarucastBrowserSenderMetadata,
} from "../src/utils/marucastBrowserSession";
import { HELPER_APP_SCHEME } from "../shared/helperAppMetadata";
import "./style.css";

/* ---------- constants ---------- */

const LINK_INSTALLATION_ID_KEY = "link-app-installation-id-v1";
const LINK_SERVER_ORIGIN_KEY = "link-app-server-origin-v1";
const DEFAULT_SITE_ORIGIN = "https://maruchansquigle.vercel.app";
const NATIVE_NOTIFICATION_SETTINGS_URL = "helper-native://notification-listener-settings";
const MARUCAST_IDLE_AUTO_STOP_MS = 2 * 60 * 1000;
const MARUCAST_STATUS_POLL_MS = 320;
const GITHUB_RELEASES_API = "https://api.github.com/repos/JmDemisana/maru-mobile/releases/latest";

const APPLET_REGISTRY = [
  { id: "photoserve", name: "PhotoServe", desc: "Desktop print workstation for 4R photo layouts and export.", apk: "maru-photoserve.apk", icon: "icons/applet-photo-serve.svg" },
  { id: "cupcuppercuppers", name: "Cup-Cupper-Cuppers", desc: "Case-picking shell game — pick the cup that wins.", apk: "maru-cupcuppercuppers.apk", icon: "icons/applet-cup-cupper-cuppers.svg" },
  { id: "daelornodael", name: "Dael or No Dael", desc: "Deal or No Deal clone with banker offers and swaps.", apk: "maru-daelornodael.apk", icon: "icons/applet-dael.svg" },
  { id: "tupgradesolver", name: "TUP Grade Solver", desc: "Score-target calculator for TUP grading.", apk: "maru-tupgradesolver.apk", icon: "icons/applet-tup-grade.svg" },
];

/* ---------- types ---------- */

type NativePushPermission = "granted" | "denied" | "prompt";

type HelperMarucastStatus = {
  activeLoopbackMicClients?: number | null;
  activeLoopbackRelayClients?: number | null;
  activeNetworkMicClients?: number | null;
  activeNetworkRelayClients?: number | null;
  artworkUrl?: string | null;
  captureActive?: boolean;
  channelCount?: number | null;
  deviceName?: string | null;
  karaokeEnabled?: boolean;
  lastError?: string | null;
  loopbackLiveStreamUrl?: string | null;
  loopbackArtworkUrl?: string | null;
  mediaAccessEnabled?: boolean;
  mediaAppLabel?: string | null;
  mediaArtist?: string | null;
  mediaDurationMs?: number | null;
  mediaPlaying?: boolean;
  mediaPlaybackSpeed?: number | null;
  mediaPositionCapturedAtMs?: number | null;
  mediaPositionMs?: number | null;
  mediaTitle?: string | null;
  message?: string | null;
  micCaptureActive?: boolean;
  micLevel?: number | null;
  micLastError?: string | null;
  micMixGain?: number | null;
  micMusicBedLevel?: number | null;
  pairingCode?: string | null;
  relayDelayMs?: number | null;
  liveStreamUrl?: string | null;
  relayMode?: string | null;
  running?: boolean;
  sampleRate?: number | null;
  selectedTitle?: string | null;
  sameWifiRequiredMessage?: string | null;
  serviceName?: string | null;
  success?: boolean;
  transportAvailable?: boolean;
  vocalProcessingKind?: string | null;
  vocalStemModelReady?: boolean;
  vocalMode?: string | null;
  wifiConnected?: boolean;
};

type GitHubAsset = {
  name: string;
  browser_download_url: string;
  size: number;
};

type GitHubRelease = {
  tag_name: string;
  assets: GitHubAsset[];
};

declare global {
  interface Window {
    HelperNativeBridge?: {
      clearMarucastAudioSelection?: () => string;
      cancelLastFmDetector?: () => void;
      dispatchMarucastControlCommand?: (command: string) => string;
      dispatchMarucastTransportCommand?: (command: string) => string;
      ensureLastFmDetectorScheduled?: () => void;
      getLauncherIconState?: () => string;
      getMarucastStatus?: () => string;
      openNotificationListenerSettings?: () => void;
      openExternalUrl?: (url: string) => void;
      pickMarucastAudioFile?: () => void;
      persistInstallationId?: (installationId: string) => void;
      persistServerOrigin?: (serverOrigin: string) => void;
      startMarucastClockSync?: () => string;
      startMarucastPlaybackRelay?: () => void;
      startMarucastSender?: () => string;
      stopMarucastPlaybackRelay?: () => string;
      stopMarucastSender?: () => string;
      toggleLauncherIconAndGetState?: () => string;
      installApkFromUrl?: (url: string, filename: string) => string;
      downloadApk?: (url: string, filename: string) => void;
      getDeviceFormFactor?: () => string;
      canInstallApks?: () => boolean;
      canInstallUnknownApps?: () => boolean;
      getMarucastReceiverStatus?: () => string;
      startMarucastReceiverDiscovery?: () => string;
      stopMarucastReceiverDiscovery?: () => string;
      getMarucastDiscoveredSenders?: () => string;
      connectMarucastReceiver?: (host: string, port: number, senderName: string) => string;
      disconnectMarucastReceiver?: () => string;
      setMarucastReceiverVolume?: (volume: number) => string;
    };
  }
}

/* ---------- state ---------- */

const linkContentElement = document.querySelector<HTMLElement>("#link-content");
let isLinking = false;
let lastProcessedLinkToken: string | null = null;
let marucastStatusPollId: number | null = null;
let marucastIdleWithoutReceiverStartedAt: number | null = null;
let activePanel: "catalog" | "marucast" | "settings" | "receiver" = "catalog";
let marucastPanelContext: {
  openUrl: string;
  receiverToken: string | null;
  serverOrigin: string | null;
} | null = null;
let marucastPanelMessage = "";
let marucastPanelError = "";
let marucastLinkedReceiverSurface: "desktop-web" | "tv-app" | null = null;
let marucastLinkedReceiverSurfaceRequestKey: string | null = null;
let cachedRelease: GitHubRelease | null = null;
let cachedReleaseAt = 0;

/* ---------- receiver state ---------- */

type DiscoveredSender = { name: string; host: string; port: number; code: string; mode: string; title: string };
type ReceiverStatus = { connected: boolean; senderName: string; senderHost: string; senderPort: number; pairingCode: string; volume: number; discovering: boolean; lastError: string };

let receiverStatus: ReceiverStatus = { connected: false, senderName: "", senderHost: "", senderPort: 0, pairingCode: "", volume: 1, discovering: false, lastError: "" };
let discoveredSenders: DiscoveredSender[] = [];
let receiverPollId: number | null = null;

/* ---------- helpers ---------- */

function normalizeStatusText(value: string | null | undefined) {
  const trimmed = value?.trim();
  if (!trimmed || trimmed.toLowerCase() === "null" || trimmed.toLowerCase() === "undefined") return null;
  return trimmed;
}

function parseBridgeJson<T>(raw: string | null | undefined) {
  if (!raw) return null;
  try { return JSON.parse(raw) as T; } catch { return null; }
}

function escapeHtml(value: string) {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#39;");
}

function wait(ms: number) { return new Promise<void>((r) => window.setTimeout(r, ms)); }

function getMarucastStatus() {
  return parseBridgeJson<HelperMarucastStatus>(window.HelperNativeBridge?.getMarucastStatus?.());
}

function getActiveNetworkRelayClients(status: HelperMarucastStatus | null) {
  return typeof status?.activeNetworkRelayClients === "number" ? Math.max(0, Math.trunc(status.activeNetworkRelayClients)) : 0;
}

function normalizeReceiverSurface(value: "desktop-web" | "tv-app" | null | undefined) {
  return value === "tv-app" || value === "desktop-web" ? value : null;
}

/* ---------- release fetching ---------- */

async function fetchLatestRelease(): Promise<GitHubRelease | null> {
  const now = Date.now();
  if (cachedRelease && now - cachedReleaseAt < 5 * 60 * 1000) return cachedRelease;
  try {
    const resp = await CapacitorHttp.get({ url: GITHUB_RELEASES_API, responseType: "json" });
    const data = resp.data as GitHubRelease | null;
    if (data?.tag_name && Array.isArray(data.assets)) {
      cachedRelease = data;
      cachedReleaseAt = now;
      return data;
    }
  } catch { /* ignore */ }
  return cachedRelease;
}

function findApkDownload(apkName: string, release: GitHubRelease | null): { url: string; size: string } | null {
  if (!release) return null;
  const patterns = apkName.split(",").map(p => p.trim().toLowerCase());
  const asset = release.assets.find((a) => patterns.some(p => a.name.toLowerCase().includes(p)));
  if (!asset) return null;
  return { url: asset.browser_download_url, size: formatBytes(asset.size) };
}

function formatBytes(bytes: number): string {
  if (bytes < 1048576) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / 1048576).toFixed(1)} MB`;
}

let currentAppVersion = "0.0.2";

function parseVersion(raw: string): number[] {
  return raw.replace(/^v/, "").split(".").map((n) => parseInt(n, 10) || 0);
}

function isNewerVersion(latest: string, current: string): boolean {
  const a = parseVersion(latest);
  const b = parseVersion(current);
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const x = a[i] || 0;
    const y = b[i] || 0;
    if (x > y) return true;
    if (x < y) return false;
  }
  return false;
}

/* ---------- catalog panel ---------- */

let downloadStates: Record<string, { downloading: boolean; installPermissionNeeded?: boolean }> = {};
let deviceFormFactor: "mobile" | "tv" | "tv-remote" = "mobile";

function startApkDownload(apkName: string, url: string) {
  if (!window.HelperNativeBridge?.canInstallApks?.()) {
    return;
  }
  if (!window.HelperNativeBridge?.canInstallUnknownApps?.()) {
    downloadStates[apkName] = { downloading: false, installPermissionNeeded: true };
    renderCatalogPanel();
    setTimeout(() => {
      delete downloadStates[apkName];
      renderCatalogPanel();
    }, 6000);
    return;
  }
  downloadStates[apkName] = { downloading: true };
  renderCatalogPanel();
  /* Hand off to Android DownloadManager for background download with system notification */
  window.HelperNativeBridge?.downloadApk?.(url, apkName);
  setTimeout(() => {
    delete downloadStates[apkName];
    renderCatalogPanel();
  }, 3000);
}

function detectDeviceFormFactor() {
  const factor = window.HelperNativeBridge?.getDeviceFormFactor?.();
  if (factor === "tv" || factor === "tv-remote") {
    deviceFormFactor = factor as "tv" | "tv-remote";
  } else {
    deviceFormFactor = "mobile";
  }
}

function renderCatalogPanel() {
  if (!linkContentElement) return;

  detectDeviceFormFactor();
  const canInstall = deviceFormFactor === "mobile";

  let cardsHtml = APPLET_REGISTRY.map((app) => {
    const apkInfo = findApkDownload(app.apk, cachedRelease);
    const dl = downloadStates[app.apk];
    let actionHtml = "";
    if (dl?.downloading) {
      actionHtml = `<div class="link-card-meta">Starting download…</div>`;
    } else if (dl?.installPermissionNeeded) {
      actionHtml = `<div class="link-card-meta" style="color:rgba(255,191,92,0.9)">Enable 'Install unknown apps' for Maru Link in Settings</div>`;
    } else if (apkInfo) {
      actionHtml = canInstall
        ? `<div class="link-card-meta">${apkInfo.size} · <a href="${escapeHtml(apkInfo.url)}" class="link-download-link" data-apk-download="${escapeHtml(app.apk)}" data-apk-url="${escapeHtml(apkInfo.url)}">Download & Install</a></div>`
        : `<div class="link-card-meta">Not available on TV</div>`;
    } else {
      actionHtml = `<div class="link-card-meta">Coming soon</div>`;
    }

    return `
      <article class="link-card">
        <div class="link-card-icon"><img src="${escapeHtml(app.icon)}" alt="${escapeHtml(app.name)}" class="link-card-icon-img" /></div>
        <div class="link-card-body">
          <h3 class="link-card-name">${escapeHtml(app.name)}</h3>
          <p class="link-card-desc">${escapeHtml(app.desc)}</p>
          ${actionHtml}
        </div>
      </article>
    `;
  }).join("");

  let thirdPartyHtml = "";
  if (canInstall) {
    const THIRD_PARTY_APPS = [
      { name: "ReVanced Manager Plus", desc: "Patched YouTube client with sponsor-block, ad-free playback, and more.", url: "https://vanced.to/revanced-manager/", icon: "🔧" },
      { name: "CloudStream", desc: "Stream movies and shows from extension sources.", url: "https://github.com/recloudstream/cloudstream/releases", icon: "☁️", tip: 'Try extension shortcode "phisherrepo"' },
    ];

    thirdPartyHtml = THIRD_PARTY_APPS.map((app) => `
      <article class="link-card link-card-external">
        <div class="link-card-icon">${app.icon}</div>
        <div class="link-card-body">
          <h3 class="link-card-name">${escapeHtml(app.name)}</h3>
          <p class="link-card-desc">${escapeHtml(app.desc)}</p>
          <div class="link-card-meta">
            <a href="${escapeHtml(app.url)}" class="link-download-link" data-third-party-url="${escapeHtml(app.url)}">Open Download Page ↗</a>
          </div>
          ${app.tip ? `<div class="link-card-tip">${escapeHtml(app.tip)}</div>` : ""}
        </div>
      </article>
    `).join("");
  }

  const tvNoticeHtml = !canInstall
    ? `<div class="link-tv-notice">This is a TV device. Mobile app installations are blocked for compatibility reasons.</div>`
    : "";

  const communitySectionHtml = canInstall
    ? `<div class="link-community-section">
        <h2 class="link-community-heading">Community Picks</h2>
        <p class="link-community-disclaimer">These apps are not affiliated with or sponsored by Maru. We cannot guarantee the safety or quality of these files — download and use at your own discretion.</p>
        <div class="link-catalog-grid">${thirdPartyHtml}</div>
      </div>`
    : "";

  linkContentElement.innerHTML = `
    <div class="link-panel">
      <h1 class="link-panel-title">App Catalog</h1>
      <p class="link-panel-sub">${canInstall ? "Download and manage your Maru apps. Each app needs Maru Link installed to run." : "Browse available apps. Downloads are restricted on this TV device."}</p>
      ${tvNoticeHtml}
      <div class="link-catalog-grid">${cardsHtml}</div>

      ${communitySectionHtml}
    </div>
  `;

  document.querySelectorAll<HTMLElement>("[data-apk-download]").forEach((link) => {
    link.addEventListener("click", (e) => {
      e.preventDefault();
      const apkName = link.getAttribute("data-apk-download") ?? "";
      const apkUrl = link.getAttribute("data-apk-url") ?? "";
      if (apkName && apkUrl) startApkDownload(apkName, apkUrl);
    });
  });

  document.querySelectorAll<HTMLElement>("[data-third-party-url]").forEach((link) => {
    link.addEventListener("click", (e) => {
      e.preventDefault();
      const url = link.getAttribute("data-third-party-url") ?? "";
      if (url) {
        try { window.HelperNativeBridge?.openExternalUrl?.(url); } catch {
          window.open(url, "_blank");
        }
      }
    });
  });
}

/* ---------- TV remote handling ---------- */

let tvFocusIndex = -1;

function handleTvRemoteKey(keyCode: number) {
  /* D-pad navigation: 19=left, 20=down, 21=right, 22=up, 23=center/enter */
  const focusable = document.querySelectorAll<HTMLElement>(
    ".link-card, .link-btn, .link-download-link, .link-nav-btn"
  );
  if (focusable.length === 0) return;

  if (keyCode === 23) {
    /* Enter/OK key */
    if (tvFocusIndex >= 0 && tvFocusIndex < focusable.length) {
      focusable[tvFocusIndex].click();
    }
    return;
  }

  if (keyCode === 19 || keyCode === 20 || keyCode === 21 || keyCode === 22) {
    if (tvFocusIndex < 0) tvFocusIndex = 0;
    else if (keyCode === 20 || keyCode === 21) tvFocusIndex = (tvFocusIndex + 1) % focusable.length;
    else if (keyCode === 19 || keyCode === 22) tvFocusIndex = (tvFocusIndex - 1 + focusable.length) % focusable.length;

    focusable.forEach((el, i) => {
      el.style.outline = i === tvFocusIndex ? "2px solid rgba(122, 155, 255, 0.9)" : "";
    });
    focusable[tvFocusIndex].scrollIntoView({ block: "nearest", behavior: "smooth" });
  }
}

function initTvRemoteHandling() {
  (window as any).__tvRemoteKeyPressed = handleTvRemoteKey;
}

/* ---------- settings panel ---------- */

let updateState: { checking: boolean; available: boolean; downloading: boolean; latestVersion: string; downloadUrl: string } = {
  checking: true,
  available: false,
  downloading: false,
  latestVersion: "",
  downloadUrl: "",
};

function checkForUpdate(installedVersion: string) {
  if (!cachedRelease) {
    updateState.checking = true;
    renderSettingsPanel();
    void fetchLatestRelease().then((release) => {
      if (release && isNewerVersion(release.tag_name, installedVersion)) {
        const linkApk = findApkDownload("link,helper", release);
        updateState.available = !!linkApk;
        updateState.latestVersion = release.tag_name;
        updateState.downloadUrl = linkApk?.url ?? "";
      }
      updateState.checking = false;
      renderSettingsPanel();
    });
    return;
  }
  if (isNewerVersion(cachedRelease.tag_name, installedVersion)) {
    const linkApk = findApkDownload("link,helper", cachedRelease);
    updateState.available = !!linkApk;
    updateState.latestVersion = cachedRelease.tag_name;
    updateState.downloadUrl = linkApk?.url ?? "";
  }
  updateState.checking = false;
}

function startLinkUpdateDownload() {
  if (!updateState.downloadUrl) return;
  updateState.downloading = true;
  renderSettingsPanel();
  window.HelperNativeBridge?.downloadApk?.(updateState.downloadUrl, "maru-link.apk");
  setTimeout(() => {
    updateState.downloading = false;
    renderSettingsPanel();
  }, 3000);
}

function renderSettingsPanel() {
  if (!linkContentElement) return;

  const updateStatusHtml = updateState.checking
    ? `<span class="link-settings-value" style="opacity:0.5">Checking…</span>`
    : updateState.downloading
    ? `<span class="link-settings-value" style="color:rgba(122,255,160,0.9)">Downloading…</span>`
    : updateState.available
    ? `<span class="link-settings-value" style="color:rgba(122,255,160,0.9)">Update available: ${escapeHtml(updateState.latestVersion)}</span>`
    : `<span class="link-settings-value" style="opacity:0.45">Up to date</span>`;

  const updateButtonHtml = updateState.available && !updateState.downloading
    ? `<div class="link-settings-row"><span></span><button type="button" class="link-btn link-btn-update" id="settings-update-btn">Download Update</button></div>`
    : "";

  linkContentElement.innerHTML = `
    <div class="link-panel">
      <h1 class="link-panel-title">Settings</h1>
      <p class="link-panel-sub">Manage Maru Link, Marucast, and account preferences.</p>

      <section class="link-settings-group">
        <h2 class="link-settings-heading">Maru Link</h2>
        <div class="link-settings-row">
          <span>Version</span>
          <span class="link-settings-value" id="settings-version">—</span>
        </div>
        <div class="link-settings-row">
          <span>Updates</span>
          ${updateStatusHtml}
        </div>
        ${updateButtonHtml}
        <div class="link-settings-row">
          <span>Installation ID</span>
          <span class="link-settings-value" id="settings-install-id">—</span>
        </div>
      </section>

      <section class="link-settings-group">
        <h2 class="link-settings-heading">Marucast</h2>
        <div class="link-settings-row">
          <span>Stem file</span>
          <button type="button" class="link-btn link-btn-subtle" id="settings-import-stem">Import</button>
        </div>
        <div class="link-settings-row">
          <span>Karaoke model</span>
          <span class="link-settings-value" id="settings-stem-status">Not loaded</span>
        </div>
        <div class="link-settings-row">
          <span>Notification Access</span>
          <button type="button" class="link-btn link-btn-subtle" id="settings-open-notif">Open</button>
        </div>
      </section>

      <section class="link-settings-group">
        <h2 class="link-settings-heading">Account &amp; Sync</h2>
        <div class="link-settings-row">
          <span>Elevate App</span>
          <button type="button" class="link-btn link-btn-subtle" id="settings-elevate">Open</button>
        </div>
        <p class="link-settings-note">Open Elevation in your browser to authenticate this device and enable Last.fm helper alerts.</p>
      </section>
    </div>
  `;

  /* Fetch version from Capacitor and check for updates */
  CapacitorApp.getInfo().then((info) => {
    const version = info.version || "0.0.2";
    currentAppVersion = version;
    const el = document.getElementById("settings-version");
    if (el) el.textContent = version;
    checkForUpdate(version);
  }).catch(() => {
    const el = document.getElementById("settings-version");
    if (el) el.textContent = currentAppVersion;
    checkForUpdate(currentAppVersion);
  });

  document.getElementById("settings-install-id")!.textContent = getLinkInstallationId().slice(0, 8) + "…";

  document.getElementById("settings-update-btn")?.addEventListener("click", () => {
    startLinkUpdateDownload();
  });

  document.getElementById("settings-import-stem")?.addEventListener("click", () => {
    try { window.HelperNativeBridge?.pickMarucastAudioFile?.(); } catch {}
  });

  document.getElementById("settings-open-notif")?.addEventListener("click", () => {
    try { window.HelperNativeBridge?.openNotificationListenerSettings?.(); } catch {}
  });

  document.getElementById("settings-elevate")?.addEventListener("click", () => {
    const origin = persistServerOrigin(null) ?? DEFAULT_SITE_ORIGIN;
    const elevationUrl = `${origin}/elevation`;
    try {
      window.HelperNativeBridge?.openExternalUrl?.(elevationUrl);
    } catch {
      window.open(elevationUrl, "_blank");
    }
  });
}

/* ---------- marucast panel ---------- */

function formatMarucastDelayLabel(value: number | null | undefined) {
  if (typeof value !== "number" || !Number.isFinite(value) || value === 0) return "0 ms";
  return `${value > 0 ? "+" : ""}${Math.round(value)} ms`;
}

function formatMarucastMixPercent(value: number | null | undefined, fallback: number) {
  const safe = typeof value === "number" && Number.isFinite(value) ? value : fallback;
  return `${Math.round(safe * 100)}%`;
}

function getMarucastBroadcastModeLabel(status: HelperMarucastStatus | null) {
  if (status?.relayMode === "playback-capture") return "Phone audio";
  if (status?.relayMode === "sync-clock") return "Lyric timing";
  if (status?.relayMode === "local-file" || status?.running) return "Chosen song";
  return "Idle";
}

function getMarucastStemStatusLabel(status: HelperMarucastStatus | null) {
  if (status?.vocalStemModelReady) return status.vocalMode === "remove" ? "Karaoke on" : "Karaoke ready";
  return status?.vocalMode === "remove" ? "Karaoke starting" : "Full song";
}

function getMarucastSourceTitle(status: HelperMarucastStatus | null) {
  return normalizeStatusText(status?.mediaTitle) || normalizeStatusText(status?.selectedTitle) || "No source selected yet";
}

function getMarucastSourceSubtitle(status: HelperMarucastStatus | null) {
  if (status?.relayMode === "sync-clock") return "Phone stays local while the helper shares native lyric timing.";
  if (status?.relayMode === "local-file" || status?.running) return normalizeStatusText(status?.mediaArtist) || normalizeStatusText(status?.mediaAppLabel) || "Ready to play.";
  return normalizeStatusText(status?.mediaArtist) || normalizeStatusText(status?.mediaAppLabel) || "Play something, then tap Phone Audio.";
}

function renderIcon(name: string, compact = false) {
  const cls = compact ? "link-icon link-icon-compact" : "link-icon";
  const icons: Record<string, string> = {
    broadcast: '<svg viewBox="0 0 20 20" fill="none"><path d="M10 7.25v5.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/><path d="M13.3 5.2a6 6 0 0 1 0 9.6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/><path d="M6.7 5.2a6 6 0 0 0 0 9.6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>',
    song: '<svg viewBox="0 0 20 20" fill="none"><path d="M12.2 4.5v7.1a2.3 2.3 0 1 1-1.5-2.16V6.3l5-1.3v5.1a2.3 2.3 0 1 1-1.5-2.16V4.5l-2 .5Z" stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    file: '<svg viewBox="0 0 20 20" fill="none"><path d="M6 3.8h5.3l3.2 3.2v8.7a1.5 1.5 0 0 1-1.5 1.5H6a1.5 1.5 0 0 1-1.5-1.5V5.3A1.5 1.5 0 0 1 6 3.8Z" stroke="currentColor" stroke-width="1.45" stroke-linejoin="round"/><path d="M11.3 3.8v3.1h3.2" stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    play: '<svg viewBox="0 0 20 20" fill="none"><path d="M7 5.3 14 10l-7 4.7V5.3Z" fill="currentColor"/></svg>',
    stop: '<svg viewBox="0 0 20 20" fill="none"><rect x="5.8" y="5.8" width="8.4" height="8.4" rx="1.4" fill="currentColor"/></svg>',
    karaoke: '<svg viewBox="0 0 20 20" fill="none"><path d="M8.5 5.2a2.6 2.6 0 1 1 3.68 3.68l-1.4 1.4" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="m11.1 9.9 3.7 3.7" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="m5.3 14.6 2.9-2.9" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="m13.9 4.2.45 1.1 1.1.45-1.1.45-.45 1.1-.45-1.1-1.1-.45 1.1-.45.45-1.1Z" fill="currentColor"/></svg>',
    mic: '<svg viewBox="0 0 20 20" fill="none"><rect x="7.2" y="3.5" width="5.6" height="8.2" rx="2.8" stroke="currentColor" stroke-width="1.45"/><path d="M5.6 9.9a4.4 4.4 0 0 0 8.8 0" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="M10 14.3v2.2" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="M7.7 16.5h4.6" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/></svg>',
    sync: '<svg viewBox="0 0 20 20" fill="none"><path d="M15.2 7.5A5.5 5.5 0 0 0 6 5.6" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="M14.8 4.5v3.6h-3.6" stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round"/><path d="M4.8 12.5A5.5 5.5 0 0 0 14 14.4" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="M5.2 15.5v-3.6h3.6" stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    wifi: '<svg viewBox="0 0 20 20" fill="none"><path d="M4.7 8.2a8.2 8.2 0 0 1 10.6 0" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="M7.2 10.8a4.7 4.7 0 0 1 5.6 0" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="M9.1 13.4a1.8 1.8 0 0 1 1.8 0" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><circle cx="10" cy="15.5" r="1" fill="currentColor"/></svg>',
    link: '<svg viewBox="0 0 20 20" fill="none"><path d="M8.1 11.9 6.7 13.3a2.6 2.6 0 0 1-3.7-3.7l2-2a2.6 2.6 0 0 1 3.7 0" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="m11.9 8.1 1.4-1.4a2.6 2.6 0 0 1 3.7 3.7l-2 2a2.6 2.6 0 0 1-3.7 0" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="m7.7 12.3 4.6-4.6" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/></svg>',
    delay: '<svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="11" r="5.3" stroke="currentColor" stroke-width="1.45"/><path d="M10 8.1v3.2l2.2 1.4" stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round"/><path d="M8.4 3.7h3.2" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/></svg>',
    volume: '<svg viewBox="0 0 20 20" fill="none"><path d="M5.3 11.9H3.8V8.1h1.5L8.8 5.2v9.6l-3.5-2.9Z" stroke="currentColor" stroke-width="1.45" stroke-linejoin="round"/><path d="M12.4 7.3a3.5 3.5 0 0 1 0 5.4" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="M14.9 5.3a6 6 0 0 1 0 9.4" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/></svg>',
    settings: '<svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="2.8" stroke="currentColor" stroke-width="1.45"/><path d="M10 3.5v1.8M10 14.7v1.8M3.5 10h1.8M14.7 10h1.8M5.4 5.4l1.3 1.3M13.3 13.3l1.3 1.3M14.6 5.4l-1.3 1.3M6.7 13.3l-1.3 1.3" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/></svg>',
  };
  return `<span class="${cls}" aria-hidden="true">${icons[name] || ""}</span>`;
}

function renderStatusChip(input: { icon: string; label: string; tone?: "default" | "good" | "warn" }) {
  const tone = input.tone === "good" ? " link-chip-good" : input.tone === "warn" ? " link-chip-warn" : "";
  return `<span class="link-chip${tone}">${renderIcon(input.icon, true)}<span>${escapeHtml(input.label)}</span></span>`;
}

function renderActionBtn(input: { action?: string; active?: boolean; href?: string; icon: string; id?: string; label: string; note?: string; subtle?: boolean }) {
  const cls = ["link-btn", input.subtle ? "link-btn-subtle" : "", input.active ? "link-btn-active" : ""].filter(Boolean).join(" ");
  const content = `<span class="link-btn-stack">${renderIcon(input.icon)}<span class="link-btn-label-group"><span class="link-btn-label">${escapeHtml(input.label)}</span>${input.note ? `<span class="link-btn-note">${escapeHtml(input.note)}</span>` : ""}</span></span>`;
  if (input.href) return `<a href="${escapeHtml(input.href)}" ${input.id ? `id="${input.id}"` : ""} class="${cls}" target="_blank" rel="noreferrer">${content}</a>`;
  return `<button type="button" class="${cls}" ${input.id ? `id="${input.id}"` : ""} ${input.action ? `data-action="${escapeHtml(input.action)}"` : ""}>${content}</button>`;
}

function renderMicMeter(level: number, activeNetworkMicClients: number) {
  const safe = Math.max(0, Math.min(1, Number.isFinite(level) ? level : 0));
  const bars = Array.from({ length: 10 }, (_, i) => `<span class="link-meter-bar${safe > 0.02 && i < Math.max(1, Math.round(safe * 10)) ? " link-meter-bar-active" : ""}"></span>`).join("");
  const clientNote = activeNetworkMicClients > 0 ? `${activeNetworkMicClients} receiver${activeNetworkMicClients > 1 ? "s" : ""} listening.` : "Mic active, no separate mic lane open.";
  return `<div class="link-meter"><div class="link-meter-head"><div class="link-meter-title">${renderIcon("mic", true)}<span>Mic Activity</span></div><div class="link-meter-value">${Math.round(safe * 100)}%</div></div><div class="link-meter-strip" aria-hidden="true">${bars}</div><div class="link-meter-note">${clientNote}</div></div>`;
}

async function refreshLinkedReceiverSurface() {
  const token = marucastPanelContext?.receiverToken?.trim();
  const origin = marucastPanelContext?.serverOrigin?.trim();
  if (!token || !origin) { marucastLinkedReceiverSurface = null; renderMarucastPanel(); return; }
  const key = `${origin}|${token}`;
  if (marucastLinkedReceiverSurfaceRequestKey === key) return;
  marucastLinkedReceiverSurfaceRequestKey = key;
  try {
    const resp = await CapacitorHttp.post({ url: `${origin}/api/auth`, headers: { "Content-Type": "application/json" }, data: { route: "marucast/receiver-status", token }, responseType: "json" });
    const d = resp.data && typeof resp.data === "object" ? (resp.data as { receiverSurface?: "desktop-web" | "tv-app" }) : null;
    marucastLinkedReceiverSurface = normalizeReceiverSurface(d?.receiverSurface);
  } catch { marucastLinkedReceiverSurface = null; marucastLinkedReceiverSurfaceRequestKey = null; }
  renderMarucastPanel();
}

function activateMarucastPanel(input: { openUrl: string; receiverToken?: string | null; serverOrigin?: string | null }) {
  activePanel = "marucast";
  marucastPanelContext = { openUrl: input.openUrl, receiverToken: input.receiverToken?.trim() || null, serverOrigin: input.serverOrigin?.trim() || null };
  marucastLinkedReceiverSurface = null;
  marucastLinkedReceiverSurfaceRequestKey = null;
  renderMarucastPanel();
  void refreshLinkedReceiverSurface();
  if (marucastStatusPollId !== null) return;
  marucastStatusPollId = window.setInterval(() => {
    if (activePanel !== "marucast") { stopMarucastStatusPolling(); return; }
    renderMarucastPanel();
  }, MARUCAST_STATUS_POLL_MS);
  updateNavButtons();
}

function renderMarucastPanel() {
  if (!linkContentElement || !marucastPanelContext) return;
  const status = getMarucastStatus();
  const sourceTitle = getMarucastSourceTitle(status);
  const sourceSubtitle = getMarucastSourceSubtitle(status);
  const pairingCode = normalizeStatusText(status?.pairingCode) || "";
  const receiverConnected = getActiveNetworkRelayClients(status) > 0 || (typeof status?.activeLoopbackRelayClients === "number" && Math.max(0, Math.trunc(status.activeLoopRelayClients)) > 0);
  const receiverStatusLabel = receiverConnected ? "Receiver connected" : marucastPanelContext.receiverToken ? "Receiver ready" : pairingCode ? `Pair ${pairingCode}` : "No receiver yet";
  const wifiWarning = normalizeStatusText(status?.sameWifiRequiredMessage) || "";
  const responseMessage = normalizeStatusText(status?.message) || normalizeStatusText(status?.lastError) || normalizeStatusText(status?.micLastError) || "";
  const detailMessage = marucastPanelMessage || (responseMessage && !marucastPanelError ? responseMessage : "");
  const errorMessage = marucastPanelError || normalizeStatusText(status?.lastError) || normalizeStatusText(status?.micLastError) || "";
  const openUrl = marucastPanelContext.openUrl;
  const micLevel = typeof status?.micLevel === "number" && Number.isFinite(status.micLevel) ? Math.max(0, Math.min(1, status.micLevel)) : 0;
  const activeNetworkMicClients = typeof status?.activeNetworkMicClients === "number" && Number.isFinite(status.activeNetworkMicClients) ? Math.max(0, Math.trunc(status.activeNetworkMicClients)) : 0;

  const tvVol = (() => {
    if (!marucastPanelContext.receiverToken) return { enabled: false, note: "Link a receiver first.", value: "No receiver" };
    if (marucastLinkedReceiverSurface === "tv-app") return { enabled: receiverConnected, note: receiverConnected ? "Use Maru TV's native volume." : "TV app found. Connect it first.", value: receiverConnected ? "TV app" : "TV waiting" };
    if (marucastLinkedReceiverSurface === "desktop-web") return { enabled: false, note: "Desktop receivers only expose browser volume.", value: "Desktop" };
    return { enabled: false, note: "Checking receiver type.", value: "Checking" };
  })();

  const chips = [
    renderStatusChip({ icon: "wifi", label: status?.wifiConnected === false ? "Wi-Fi needed" : "Same Wi-Fi", tone: status?.wifiConnected === false ? "warn" : "good" }),
    renderStatusChip({ icon: "broadcast", label: getMarucastBroadcastModeLabel(status) }),
    renderStatusChip({ icon: receiverConnected ? "broadcast" : "link", label: receiverStatusLabel, tone: receiverConnected ? "good" : undefined }),
    status?.micCaptureActive ? renderStatusChip({ icon: "mic", label: "Phone mic on", tone: "good" }) : "",
    status?.vocalMode === "remove" || status?.vocalStemModelReady ? renderStatusChip({ icon: "karaoke", label: getMarucastStemStatusLabel(status), tone: status?.vocalMode === "remove" ? "good" : undefined }) : "",
  ].filter(Boolean).join("");

  linkContentElement.innerHTML = `
    <div class="link-panel">
      <h1>Marucast</h1>
      <p class="link-panel-copy">Start the song here, then tune karaoke and mic from the phone. Same Wi-Fi only.</p>

      <section class="link-card">
        <div class="link-card-top">
          <div class="link-inline-heading">${renderIcon("song", true)}<span>Now sending</span></div>
        </div>
        <div class="link-source-title">${escapeHtml(sourceTitle)}</div>
        <div class="link-source-meta">${escapeHtml(sourceSubtitle)}</div>
        <div class="link-status-row">${chips}</div>
      </section>

      ${wifiWarning ? `<div class="link-msg link-msg-warn">${escapeHtml(wifiWarning)}</div>` : ""}
      ${errorMessage ? `<div class="link-msg link-msg-error">${escapeHtml(errorMessage)}</div>` : ""}
      ${detailMessage && !errorMessage ? `<div class="link-msg link-msg-ok">${escapeHtml(detailMessage)}</div>` : ""}

      <div class="link-grid">
        <section class="link-card">
          <div class="link-inline-heading">${renderIcon("broadcast", true)}<span>Quick actions</span></div>
          <div class="link-action-grid">
            ${renderActionBtn({ action: "live-broadcast", active: status?.relayMode === "playback-capture", icon: "broadcast", label: "Phone Audio", note: "Use what's playing now" })}
            ${renderActionBtn({ action: "pick-audio", icon: "file", label: "Choose Song", note: "Pick a local audio file", subtle: true })}
            ${renderActionBtn({ action: "start-selected-audio", active: status?.relayMode === "local-file" || status?.running, icon: "play", label: "Play Chosen", note: "Start the selected file", subtle: !(status?.relayMode === "local-file" || status?.running) })}
            ${renderActionBtn({ action: "end-broadcast", icon: "stop", label: "Stop", note: "End the current broadcast", subtle: true })}
            ${renderActionBtn({ action: "toggle-vocal", active: status?.vocalMode === "remove", icon: "karaoke", label: status?.vocalMode === "remove" ? "Full Song" : "Karaoke", note: status?.vocalMode === "remove" ? "Bring vocals back" : "Remove vocals", subtle: status?.vocalMode !== "remove" })}
            ${renderActionBtn({ action: "toggle-mic", active: status?.micCaptureActive, icon: "mic", label: status?.micCaptureActive ? "Mic Off" : "Phone Mic", note: status?.micCaptureActive ? "Stop the live mic lane" : "Send the phone mic too", subtle: status?.micCaptureActive !== true })}
            ${renderActionBtn({ action: "sync-clock", active: status?.relayMode === "sync-clock", icon: "sync", label: "Native Lyric Timing", note: "Start timing without audio", subtle: status?.relayMode !== "sync-clock" })}
            ${renderActionBtn({ action: "clear-audio", icon: "file", label: "Clear Song", note: "Forget the chosen file", subtle: true })}
          </div>
        </section>

        <section class="link-card">
          <div class="link-inline-heading">${renderIcon("volume", true)}<span>Sound</span></div>
          ${status?.micCaptureActive ? renderMicMeter(micLevel, activeNetworkMicClients) : ""}
          <div class="link-adjust-grid">
            <div class="link-adjust-row">
              <div class="link-adjust-head"><div class="link-adjust-title">${renderIcon("delay", true)}<span>Song Delay</span></div><div class="link-adjust-value">${escapeHtml(formatMarucastDelayLabel(status?.relayDelayMs))}</div></div>
              <div class="link-adjust-actions"><button type="button" class="link-btn link-btn-subtle link-btn-mini" data-action="delay-down">-250</button><button type="button" class="link-btn link-btn-subtle link-btn-mini" data-action="delay-reset">0</button><button type="button" class="link-btn link-btn-subtle link-btn-mini" data-action="delay-up">+250</button></div>
            </div>
            <div class="link-adjust-row">
              <div class="link-adjust-head"><div class="link-adjust-title">${renderIcon("mic", true)}<span>Mic Level</span></div><div class="link-adjust-value">${escapeHtml(formatMarucastMixPercent(status?.micMixGain, 1.28))}</div></div>
              <div class="link-adjust-actions link-adjust-actions-two"><button type="button" class="link-btn link-btn-subtle link-btn-mini" data-action="mic-gain-down">Lower</button><button type="button" class="link-btn link-btn-subtle link-btn-mini" data-action="mic-gain-up">Raise</button></div>
            </div>
            <div class="link-adjust-row">
              <div class="link-adjust-head"><div class="link-adjust-title">${renderIcon("song", true)}<span>Music Level</span></div><div class="link-adjust-value">${escapeHtml(formatMarucastMixPercent(status?.micMusicBedLevel, 0.8))}</div></div>
              <div class="link-adjust-actions link-adjust-actions-two"><button type="button" class="link-btn link-btn-subtle link-btn-mini" data-action="music-bed-down">Lower</button><button type="button" class="link-btn link-btn-subtle link-btn-mini" data-action="music-bed-up">Raise</button></div>
            </div>
            <div class="link-adjust-row">
              <div class="link-adjust-head"><div class="link-adjust-title">${renderIcon("volume", true)}<span>TV Volume</span></div><div class="link-adjust-value">${escapeHtml(tvVol.value)}</div></div>
              <div class="link-adjust-actions link-adjust-actions-two"><button type="button" class="link-btn link-btn-subtle link-btn-mini" data-action="tv-volume-down" ${tvVol.enabled ? "" : "disabled"}>Lower</button><button type="button" class="link-btn link-btn-subtle link-btn-mini" data-action="tv-volume-up" ${tvVol.enabled ? "" : "disabled"}>Raise</button></div>
              <div class="link-adjust-note">${escapeHtml(tvVol.note)}</div>
            </div>
          </div>
        </section>
      </div>
    </div>
  `;
}

function stopMarucastStatusPolling() {
  if (marucastStatusPollId !== null) { window.clearInterval(marucastStatusPollId); marucastStatusPollId = null; }
}

function applyMarucastStatusResult(nextStatus: HelperMarucastStatus | null, fallbackMessage: string, fallbackError: string) {
  if (!nextStatus) { marucastPanelError = fallbackError; marucastPanelMessage = ""; renderMarucastPanel(); return; }
  const msg = normalizeStatusText(nextStatus.message) || normalizeStatusText(nextStatus.sameWifiRequiredMessage) || normalizeStatusText(nextStatus.lastError) || normalizeStatusText(nextStatus.micLastError) || "";
  if (nextStatus.success === false) { marucastPanelError = msg || fallbackError; marucastPanelMessage = ""; renderMarucastPanel(); return; }
  marucastPanelError = ""; marucastPanelMessage = msg || fallbackMessage; renderMarucastPanel();
}

function dispatchNativeMarucastCommand(command: string, fallbackMessage: string, fallbackError: string) {
  const s = parseBridgeJson<HelperMarucastStatus>(window.HelperNativeBridge?.dispatchMarucastControlCommand?.(command));
  applyMarucastStatusResult(s, fallbackMessage, fallbackError);
}

async function dispatchHelperReceiverCommand(command: "tv-volume-down" | "tv-volume-up") {
  const token = marucastPanelContext?.receiverToken?.trim();
  const origin = marucastPanelContext?.serverOrigin?.trim();
  if (!token || !origin) { marucastPanelError = "Link a receiver first."; marucastPanelMessage = ""; renderMarucastPanel(); return; }
  marucastPanelError = "";
  marucastPanelMessage = command === "tv-volume-up" ? "Asking Maru TV to raise volume..." : "Asking Maru TV to lower volume...";
  renderMarucastPanel();
  try {
    const resp = await CapacitorHttp.post({ url: `${origin}/api/auth`, headers: { "Content-Type": "application/json" }, data: { command, route: "marucast/receiver-command", token }, responseType: "json" });
    const d = resp.data && typeof resp.data === "object" ? (resp.data as { error?: string; success?: boolean }) : null;
    if (resp.status < 200 || resp.status >= 300 || d?.success !== true) throw new Error(normalizeStatusText(d?.error) || "Could not reach Marucast receiver.");
    marucastLinkedReceiverSurface = normalizeReceiverSurface((d as any)?.receiverSurface);
    marucastPanelError = ""; marucastPanelMessage = command === "tv-volume-up" ? "Asked Maru TV to raise volume." : "Asked Maru TV to lower volume.";
    renderMarucastPanel();
  } catch (e) { marucastPanelError = e instanceof Error && e.message.trim() ? e.message.trim() : "Could not reach Marucast receiver."; marucastPanelMessage = ""; renderMarucastPanel(); }
}

function handleMarucastPanelAction(rawAction: string | null | undefined) {
  if (activePanel !== "marucast" || !marucastPanelContext) return;
  const action = rawAction?.trim() || "";
  const status = getMarucastStatus();
  switch (action) {
    case "live-broadcast":
      marucastPanelError = ""; marucastPanelMessage = "Approve Android's playback capture prompt."; renderMarucastPanel();
      try { window.HelperNativeBridge?.startMarucastPlaybackRelay?.(); } catch { marucastPanelError = "Could not open live capture prompt."; renderMarucastPanel(); }
      return;
    case "pick-audio":
      marucastPanelError = ""; marucastPanelMessage = "Choose an audio file."; renderMarucastPanel();
      try { window.HelperNativeBridge?.pickMarucastAudioFile?.(); } catch { marucastPanelError = "Could not open audio picker."; renderMarucastPanel(); }
      return;
    case "start-selected-audio":
      applyMarucastStatusResult(parseBridgeJson<HelperMarucastStatus>(window.HelperNativeBridge?.startMarucastSender?.()), "Local audio broadcast started.", "Pick a local audio file first.");
      return;
    case "end-broadcast":
      applyMarucastStatusResult(status?.captureActive || status?.relayMode === "playback-capture" ? parseBridgeJson<HelperMarucastStatus>(window.HelperNativeBridge?.stopMarucastPlaybackRelay?.()) : parseBridgeJson<HelperMarucastStatus>(window.HelperNativeBridge?.stopMarucastSender?.()), "Marucast stopped.", "Could not stop Marucast.");
      stopMarucastBrowserSession();
      return;
    case "clear-audio":
      applyMarucastStatusResult(parseBridgeJson<HelperMarucastStatus>(window.HelperNativeBridge?.clearMarucastAudioSelection?.()), "Cleared selected audio.", "Could not clear audio.");
      return;
    case "toggle-vocal":
      dispatchNativeMarucastCommand(status?.vocalMode === "remove" ? "vocal-normal" : "vocal-remove", status?.vocalMode === "remove" ? "Normal mix restored." : "Karaoke enabled.", "Could not switch Karaoke.");
      return;
    case "toggle-mic":
      dispatchNativeMarucastCommand(status?.micCaptureActive ? "mic-stop" : "mic-start", status?.micCaptureActive ? "Phone mic stopped." : "Phone mic is live.", "Could not switch mic.");
      return;
    case "sync-clock":
      applyMarucastStatusResult(parseBridgeJson<HelperMarucastStatus>(window.HelperNativeBridge?.startMarucastClockSync?.()), "Native lyric timing is live.", "Could not start timing.");
      return;
    case "delay-down": case "delay-up": case "delay-reset": case "mic-gain-down": case "mic-gain-up": case "music-bed-down": case "music-bed-up":
      dispatchNativeMarucastCommand(action, "Marucast controls updated.", "Could not apply adjustment.");
      return;
    case "tv-volume-down": case "tv-volume-up":
      void dispatchHelperReceiverCommand(action);
      return;
  }
}

/* ---------- TV receiver panel ---------- */

function getReceiverStatus(): ReceiverStatus | null {
  return parseBridgeJson<ReceiverStatus>(window.HelperNativeBridge?.getMarucastReceiverStatus?.());
}

function refreshDiscoveredSenders() {
  const raw = window.HelperNativeBridge?.getMarucastDiscoveredSenders?.();
  if (raw) {
    try { discoveredSenders = JSON.parse(raw) as DiscoveredSender[]; } catch { discoveredSenders = []; }
  }
}

function startReceiverDiscovery() {
  window.HelperNativeBridge?.startMarucastReceiverDiscovery?.();
  renderReceiverPanel();
}

function stopReceiverDiscovery() {
  window.HelperNativeBridge?.stopMarucastReceiverDiscovery?.();
  renderReceiverPanel();
}

function connectToSender(host: string, port: number, name: string) {
  window.HelperNativeBridge?.connectMarucastReceiver?.(host, port, name);
  renderReceiverPanel();
}

function disconnectReceiver() {
  window.HelperNativeBridge?.disconnectMarucastReceiver?.();
  renderReceiverPanel();
}

function startReceiverPolling() {
  if (receiverPollId !== null) return;
  receiverPollId = window.setInterval(() => {
    if (activePanel !== "receiver") { stopReceiverPolling(); return; }
    refreshReceiverState();
  }, 2000);
  refreshReceiverState();
}

function stopReceiverPolling() {
  if (receiverPollId !== null) { window.clearInterval(receiverPollId); receiverPollId = null; }
}

function refreshReceiverState() {
  const s = getReceiverStatus();
  if (s) {
    receiverStatus = s;
    refreshDiscoveredSenders();
    renderReceiverPanel();
  }
}

function renderReceiverPanel() {
  if (!linkContentElement) return;

  refreshDiscoveredSenders();
  const s = receiverStatus;
  const isConn = s.connected;
  const isDisc = s.discovering;

  let statusDotClass = "";
  let statusText = "";
  if (isConn) { statusDotClass = "connected"; statusText = "Connected to " + (s.senderName || "sender"); }
  else if (isDisc) { statusDotClass = "scanning"; statusText = "Scanning for senders…"; }
  else if (s.lastError) { statusDotClass = "error"; statusText = s.lastError; }
  else { statusText = "Ready — tap Scan to find senders"; }

  const controlsHtml = isConn
    ? `<div class="link-receiver-controls">
        <span class="link-receiver-sender-label"><strong>${escapeHtml(s.senderName)}</strong></span>
        <div class="link-receiver-volume-row">
          <label for="receiver-volume">Vol</label>
          <input type="range" class="link-receiver-volume-slider" id="receiver-volume" min="0" max="1" step="0.05" value="${s.volume}">
          <span class="link-receiver-volume-pct">${Math.round(s.volume * 100)}%</span>
        </div>
        <button type="button" class="link-btn link-receiver-disconnect-btn" id="receiver-disconnect-btn">Disconnect</button>
      </div>`
    : "";

  const senderListHtml = discoveredSenders.length > 0
    ? discoveredSenders.map((sender) => {
        const isThis = isConn && s.senderHost === sender.host && s.senderPort === sender.port;
        return `
          <div class="link-sender-row ${isThis ? "selected" : ""}">
            <span class="link-sender-name">${escapeHtml(sender.title || sender.name)}</span>
            <span class="link-sender-detail">${escapeHtml(sender.host)}:${sender.port}</span>
            ${isThis
              ? '<span class="link-sender-status connected-label">Connected</span>'
              : `<a href="#" class="link-sender-status connect-link" data-receiver-connect-host="${escapeHtml(sender.host)}" data-receiver-connect-port="${sender.port}" data-receiver-connect-name="${escapeHtml(sender.name)}">Connect</a>`}
          </div>
        `;
      }).join("")
    : `<p class="link-receiver-empty">${isDisc ? "Scanning your network…" : "No senders found."}</p>`;

  const errorHtml = s.lastError && !isConn
    ? `<div class="link-msg link-msg-error link-receiver-error">${escapeHtml(s.lastError)}</div>`
    : "";

  linkContentElement.innerHTML = `
    <div class="link-receiver-panel">
      <div class="link-receiver-topbar">
        <h1 class="link-receiver-heading">Marucast Receiver</h1>
        <button type="button" class="link-btn link-btn-subtle link-receiver-scan-btn" id="receiver-discover-btn">
          ${isDisc ? "Stop" : isConn ? "Rescan" : "Scan"}
        </button>
      </div>
      <div class="link-receiver-status">
        <span class="link-receiver-status-dot ${statusDotClass}"></span>
        <span class="link-receiver-status-text">${statusText}</span>
      </div>
      ${errorHtml}
      ${controlsHtml}
      <div class="link-receiver-list">${senderListHtml}</div>
    </div>
  `;
      }).join("")
    : `<p class="link-receiver-empty">${isDisc ? "Scanning for Marucast senders on your network…" : "No senders found. Make sure a phone is broadcasting Marucast on the same Wi-Fi."}</p>`;

  const controlsHtml = isConn
    ? `
      <div class="link-receiver-controls">
        <div class="link-receiver-info">
          <span class="link-receiver-sender">Now playing from: <strong>${escapeHtml(s.senderName)}</strong></span>
        </div>
        <div class="link-receiver-volume">
          <span>Volume</span>
          <input type="range" class="link-receiver-volume-slider" id="receiver-volume" min="0" max="1" step="0.05" value="${s.volume}">
          <span class="link-receiver-volume-value">${Math.round(s.volume * 100)}%</span>
        </div>
        <button type="button" class="link-btn link-btn-stop" id="receiver-disconnect-btn">Disconnect</button>
      </div>
    `
    : "";

  const errorHtml = s.lastError ? `<div class="link-msg link-msg-error">${escapeHtml(s.lastError)}</div>` : "";

  linkContentElement.innerHTML = `
    <div class="link-panel">
      <h1 class="link-panel-title">Marucast Receiver</h1>
      <p class="link-panel-sub">Receive audio broadcasts from phones on your network and play them through this TV.</p>

      ${errorHtml}
      ${controlsHtml}

      <div class="link-receiver-header">
        <h2 class="link-receiver-heading">Available Senders</h2>
        <button type="button" class="link-btn link-btn-subtle" id="receiver-discover-btn">
          ${isDisc ? "Scanning…" : isConn ? "Scan for more" : "Scan"}
        </button>
      </div>

      <div class="link-catalog-grid">${senderListHtml}</div>
    </div>
  `;

  document.querySelectorAll<HTMLElement>("[data-receiver-connect-host]").forEach((link) => {
    link.addEventListener("click", (e) => {
      e.preventDefault();
      const host = link.getAttribute("data-receiver-connect-host") ?? "";
      const port = parseInt(link.getAttribute("data-receiver-connect-port") ?? "0", 10);
      const name = link.getAttribute("data-receiver-connect-name") ?? "";
      if (host && port > 0 && name) connectToSender(host, port, name);
    });
  });

  document.getElementById("receiver-disconnect-btn")?.addEventListener("click", () => {
    disconnectReceiver();
  });

  document.getElementById("receiver-discover-btn")?.addEventListener("click", () => {
    if (isDisc) stopReceiverDiscovery();
    else startReceiverDiscovery();
  });

  const volumeSlider = document.getElementById("receiver-volume") as HTMLInputElement | null;
  volumeSlider?.addEventListener("input", () => {
    const vol = parseFloat(volumeSlider.value);
    window.HelperNativeBridge?.setMarucastReceiverVolume?.(vol);
    const valueEl = volumeSlider.nextElementSibling;
    if (valueEl) valueEl.textContent = `${Math.round(vol * 100)}%`;
  });
}

/* ---------- nav ---------- */

function updateNavButtons() {
  document.querySelectorAll<HTMLElement>(".link-nav-btn").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.panel === activePanel);
  });
}

function switchPanel(panel: "catalog" | "marucast" | "settings" | "receiver") {
  activePanel = panel;
  stopMarucastStatusPolling();
  stopReceiverPolling();
  if (panel === "catalog") renderCatalogPanel();
  else if (panel === "marucast") {
    const openUrl = buildLinkSiteUrl(persistServerOrigin(null), "/marucast");
    activateMarucastPanel({ openUrl, receiverToken: marucastPanelContext?.receiverToken ?? null, serverOrigin: marucastPanelContext?.serverOrigin ?? persistServerOrigin(null) });
    marucastPanelError = ""; marucastPanelMessage = "Control this phone's Marucast broadcast here.";
    renderMarucastPanel();
  }
  else if (panel === "receiver") {
    renderReceiverPanel();
    startReceiverPolling();
  }
  else if (panel === "settings") renderSettingsPanel();
  updateNavButtons();
}

/* ---------- link / deep link ---------- */

function buildRandomInstallationId() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") return crypto.randomUUID();
  return `link-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function getLinkInstallationId() {
  const stored = localStorage.getItem(LINK_INSTALLATION_ID_KEY)?.trim();
  if (stored) { try { window.HelperNativeBridge?.persistInstallationId?.(stored); } catch {} return stored; }
  const next = buildRandomInstallationId();
  localStorage.setItem(LINK_INSTALLATION_ID_KEY, next);
  try { window.HelperNativeBridge?.persistInstallationId?.(next); } catch {}
  return next;
}

function normalizeServerOrigin(rawOrigin: string | null | undefined) {
  const t = rawOrigin?.trim();
  if (!t) return null;
  try { const p = new URL(t); if (p.protocol !== "http:" && p.protocol !== "https:") return null; return p.origin.replace(/\/+$/, ""); } catch { return null; }
}

function persistServerOrigin(rawOrigin: string | null | undefined) {
  const n = normalizeServerOrigin(rawOrigin);
  if (n) { localStorage.setItem(LINK_SERVER_ORIGIN_KEY, n); try { window.HelperNativeBridge?.persistServerOrigin?.(n); } catch {} return n; }
  const stored = normalizeServerOrigin(localStorage.getItem(LINK_SERVER_ORIGIN_KEY)) ?? null;
  if (stored) { try { window.HelperNativeBridge?.persistServerOrigin?.(stored); } catch {} }
  return stored;
}

function buildLinkSiteUrl(serverOrigin: string | null, target: string | null | undefined) {
  return `${serverOrigin ?? DEFAULT_SITE_ORIGIN}${target || ""}`;
}

function parseLinkUrl(rawUrl: string | null | undefined) {
  if (!rawUrl) return null;
  try {
    const p = new URL(rawUrl);
    if (p.protocol !== `${HELPER_APP_SCHEME}:`) return null;
    return { action: p.searchParams.get("action")?.trim() ?? "", token: p.searchParams.get("token")?.trim() ?? "", siteOrigin: p.searchParams.get("siteOrigin")?.trim() ?? "", target: p.searchParams.get("target")?.trim() ?? p.searchParams.get("url")?.trim() ?? "" };
  } catch { return null; }
}

async function closeLinkApp() {
  stopMarucastStatusPolling();
  try { await CapacitorApp.minimizeApp(); return; } catch {}
  try { await CapacitorApp.exitApp(); } catch {}
}

/* ---------- link flow ---------- */

async function registerNativePushToken(): Promise<{ permission: NativePushPermission; token: string | null }> {
  const current = await PushNotifications.checkPermissions();
  let permission = current.receive as NativePushPermission;
  if (permission === "prompt") { const next = await PushNotifications.requestPermissions(); permission = next.receive as NativePushPermission; }
  if (permission !== "granted") return { permission, token: null };
  return await new Promise((resolve, reject) => {
    let settled = false;
    const handles: Promise<{ remove: () => Promise<void> }>[] = [];
    const finish = (cb: () => void) => { if (settled) return; settled = true; handles.forEach((h) => h.then((l) => l.remove()).catch(() => {})); cb(); };
    handles.push(PushNotifications.addListener("registration", (token) => { finish(() => resolve({ permission, token: token.value })); }));
    handles.push(PushNotifications.addListener("registrationError", (err) => { finish(() => reject(new Error(err.error?.trim() || "Push registration failed."))); }));
    PushNotifications.register().catch((err) => { finish(() => reject(err instanceof Error ? err : new Error("Push registration failed."))); });
  });
}

async function completeLinkFlow(rawUrl: string) {
  if (isLinking) return;
  const parsed = parseLinkUrl(rawUrl);
  const action = parsed?.action ?? "";
  const token = parsed?.token ?? "";
  const serverOrigin = persistServerOrigin(parsed?.siteOrigin);

  if (token && token === lastProcessedLinkToken) return;

  /* If no token but there's a marucast target, go straight to marucast panel */
  if (!token && parsed?.target?.includes("marucast")) {
    activateMarucastPanel({ openUrl: buildLinkSiteUrl(serverOrigin, "/marucast"), receiverToken: null, serverOrigin });
    marucastPanelError = ""; marucastPanelMessage = "Marucast ready.";
    renderMarucastPanel();
    switchPanel("marucast");
    return;
  }

  if (!token) {
    /* No token, no action — show catalog */
    switchPanel("catalog");
    return;
  }

  lastProcessedLinkToken = token;
  isLinking = true;
  switchPanel("catalog");
  /* For linking flow, briefly show linking state then go to catalog */
  try {
    const reg = await registerNativePushToken();
    if (reg.permission !== "granted" || !reg.token) { switchPanel("catalog"); isLinking = false; return; }
    const appInfo = await CapacitorApp.getInfo().catch(() => null);
    const appVersion = typeof appInfo?.version === "string" && appInfo.version.trim() ? appInfo.version.trim() : "0.0.2";
    await CapacitorHttp.post({ url: `${serverOrigin ?? DEFAULT_SITE_ORIGIN}/api/auth`, headers: { "Content-Type": "application/json" }, data: { route: "companion/link-complete", installationId: getLinkInstallationId(), platform: "android", pushToken: reg.token, token, appVersion }, responseType: "json" });
    getLinkInstallationId();
    persistServerOrigin(serverOrigin);
    try { window.HelperNativeBridge?.ensureLastFmDetectorScheduled?.(); } catch {}
  } catch { /* ignore */ }
  isLinking = false;
  switchPanel("catalog");
}

/* ---------- init ---------- */

function renderDefaultPanel() {
  switchPanel("catalog");
  void fetchLatestRelease().then(() => { if (activePanel === "catalog") renderCatalogPanel(); });
}

async function initializeLink() {
  document.body.dataset.platform = Capacitor.getPlatform();
  getLinkInstallationId();
  persistServerOrigin(null);

  /* TV remote handling */
  initTvRemoteHandling();

  /* Show receiver nav only on TV */
  detectDeviceFormFactor();
  const receiverBtn = document.getElementById("nav-receiver-btn");
  if (receiverBtn) {
    receiverBtn.style.display = (deviceFormFactor === "tv" || deviceFormFactor === "tv-remote") ? "" : "none";
  }

  /* Nav click handlers */
  document.querySelectorAll<HTMLElement>(".link-nav-btn").forEach((btn) => {
    btn.addEventListener("click", () => {
      const panel = btn.dataset.panel as "catalog" | "marucast" | "settings" | "receiver";
      if (panel) switchPanel(panel);
    });
  });

  /* Content click delegation */
  linkContentElement?.addEventListener("click", (event) => {
    const target = event.target as HTMLElement | null;
    if (!target) return;
    const actionEl = target.closest<HTMLElement>("[data-action]");
    if (actionEl) { event.preventDefault(); handleMarucastPanelAction(actionEl.getAttribute("data-action")); return; }
  });

  /* Idle auto-stop */
  window.setInterval(() => {
    const browserState = getMarucastBrowserState();
    const status = getMarucastStatus();
    if (browserState.mode === "webrtc_sender") updateMarucastBrowserSenderMetadata(buildMarucastSenderMetadata(status));
    const relayRunning = Boolean(status?.captureActive || status?.running);
    const browserConnected = browserState.mode === "webrtc_sender" && browserState.connectionState === "connected";
    const directConnected = getActiveNetworkRelayClients(status) > 0;
    if (!relayRunning || browserConnected || directConnected) { marucastIdleWithoutReceiverStartedAt = null; return; }
    if (marucastIdleWithoutReceiverStartedAt === null) { marucastIdleWithoutReceiverStartedAt = Date.now(); return; }
    if (Date.now() - marucastIdleWithoutReceiverStartedAt < MARUCAST_IDLE_AUTO_STOP_MS) return;
    marucastIdleWithoutReceiverStartedAt = null;
    stopMarucastBrowserSession();
    try { window.HelperNativeBridge?.stopMarucastPlaybackRelay?.(); } catch {}
    stopMarucastStatusPolling();
    activateMarucastPanel({ openUrl: buildLinkSiteUrl(persistServerOrigin(null), "/marucast"), receiverToken: marucastPanelContext?.receiverToken ?? null, serverOrigin: marucastPanelContext?.serverOrigin ?? persistServerOrigin(null) });
    marucastPanelError = ""; marucastPanelMessage = "No receiver for 2 minutes — stopped broadcasting.";
    renderMarucastPanel();
  }, 1000);

  /* Launch URL (deep link) */
  const launchUrl = await CapacitorApp.getLaunchUrl().catch(() => null);
  if (launchUrl?.url) { await completeLinkFlow(launchUrl.url); return; }

  renderDefaultPanel();

  /* App URL open (deep links while running) */
  await CapacitorApp.addListener("appUrlOpen", (event) => { void completeLinkFlow(event.url); });

  /* Push notification tap */
  await PushNotifications.addListener("pushNotificationActionPerformed", (event) => {
    const storedOrigin = persistServerOrigin(null);
    const rawTarget = event.notification?.data && typeof event.notification.data.url === "string" ? event.notification.data.url : "";
    const openUrl = buildLinkSiteUrl(storedOrigin, rawTarget);
    switchPanel("catalog");
  });
}

/* Marucast browser session helpers (imported) */
function buildMarucastSenderMetadata(status: HelperMarucastStatus | null) {
  return {
    artworkUrl: normalizeStatusText(status?.artworkUrl) || normalizeStatusText(status?.loopbackArtworkUrl),
    mediaAppLabel: normalizeStatusText(status?.mediaAppLabel),
    mediaArtist: normalizeStatusText(status?.mediaArtist),
    mediaDurationMs: typeof status?.mediaDurationMs === "number" && Number.isFinite(status.mediaDurationMs) ? Math.max(0, Math.round(status.mediaDurationMs)) : null,
    mediaPlaying: typeof status?.mediaPlaying === "boolean" ? status.mediaPlaying : null,
    mediaPlaybackSpeed: typeof status?.mediaPlaybackSpeed === "number" && Number.isFinite(status.mediaPlaybackSpeed) ? status.mediaPlaybackSpeed : null,
    mediaPositionCapturedAtMs: typeof status?.mediaPositionCapturedAtMs === "number" && Number.isFinite(status.mediaPositionCapturedAtMs) ? Math.round(status.mediaPositionCapturedAtMs) : null,
    mediaPositionMs: typeof status?.mediaPositionMs === "number" && Number.isFinite(status.mediaPositionMs) ? Math.max(0, Math.round(status.mediaPositionMs)) : null,
    title: normalizeStatusText(status?.mediaTitle) || normalizeStatusText(status?.selectedTitle) || "Current phone playback",
    karaokeEnabled: status?.karaokeEnabled === true,
    transportAvailable: status?.transportAvailable === true,
    vocalProcessingKind: normalizeStatusText(status?.vocalProcessingKind),
    vocalStemModelReady: status?.vocalStemModelReady === true,
    vocalMode: status?.vocalMode === "remove" ? "remove" : "normal",
  };
}

function getMarucastBrowserState(): { mode?: string; connectionState?: string; offerPayload?: string | null; lastError?: string } {
  return {};
}

void initializeLink();
