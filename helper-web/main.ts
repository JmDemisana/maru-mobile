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
import {
  HELPER_APP_SCHEME,
  HELPER_APP_VERSION,
} from "../shared/helperAppMetadata";
import "./style.css";

const HELPER_INSTALLATION_ID_KEY = "helper-app-installation-id-v1";
const HELPER_SERVER_ORIGIN_KEY = "helper-app-server-origin-v1";
const DEFAULT_HELPER_SITE_ORIGIN_FALLBACK = "https://maruchansquigle.vercel.app";
const NATIVE_NOTIFICATION_SETTINGS_URL = "helper-native://notification-listener-settings";
const MARUCAST_IDLE_AUTO_STOP_MS = 2 * 60 * 1000;
const MARUCAST_STATUS_POLL_MS = 320;

type NativePushPermission = "granted" | "denied" | "prompt";

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
    };
  }
}

const helperContentElement =
  document.querySelector<HTMLElement>("#helper-content");

let isLinking = false;
let lastProcessedLinkToken: string | null = null;
let marucastStatusPollId: number | null = null;
let marucastIdleWithoutReceiverStartedAt: number | null = null;
let activeHelperPanel: "default" | "marucast" = "default";
let marucastPanelContext: {
  openUrl: string;
  receiverToken: string | null;
  serverOrigin: string | null;
} | null = null;
let marucastPanelMessage = "";
let marucastPanelError = "";
let marucastLinkedReceiverSurface: "desktop-web" | "tv-app" | null = null;
let marucastLinkedReceiverSurfaceRequestKey: string | null = null;

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

type HelperReceiverStatusResponse = {
  answerPayload?: string | null;
  error?: string;
  receiverSurface?: "desktop-web" | "tv-app" | null;
  status?: "pending" | "ready" | "expired";
};

type HelperReceiverCommandResponse = {
  command?: "tv-volume-down" | "tv-volume-up" | null;
  commandNonce?: number | null;
  error?: string;
  receiverSurface?: "desktop-web" | "tv-app" | null;
  success?: boolean;
};

function normalizeStatusText(value: string | null | undefined) {
  const trimmed = value?.trim();
  if (!trimmed || trimmed.toLowerCase() === "null" || trimmed.toLowerCase() === "undefined") {
    return null;
  }
  return trimmed;
}

function normalizeReceiverSurface(
  value: "desktop-web" | "tv-app" | null | undefined,
) {
  return value === "tv-app" || value === "desktop-web" ? value : null;
}

function parseBridgeJson<T>(raw: string | null | undefined) {
  if (!raw) {
    return null;
  }

  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

function escapeHtml(value: string) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function renderDefaultLayout() {
  if (!helperContentElement) {
    return;
  }

  helperContentElement.innerHTML = `
    <div class="helper-kicker">Maru Helper</div>
    <h1 id="helper-title">Waiting for Maru</h1>
    <p id="helper-status">This tiny helper only handles Android notification setup and delivery.</p>
    <p id="helper-detail" class="helper-detail">Open the Maru website and press Connect Helper App when you want to refresh notification access.</p>
    <div class="helper-actions">
      <a
        id="helper-open-link"
        class="helper-button helper-button-subtle helper-hidden"
        target="_blank"
        rel="noreferrer"
      >
        Open Maru
      </a>
      <button id="helper-close-button" class="helper-button" type="button">
        Close Helper
      </button>
    </div>
  `;
}

function setViewState(input: {
  title: string;
  status: string;
  detail?: string;
  openLabel?: string;
  openUrl?: string | null;
  closeLabel?: string;
}) {
  if (!helperContentElement) {
    return;
  }

  activeHelperPanel = "default";
  renderDefaultLayout();
  const titleElement =
    helperContentElement.querySelector<HTMLHeadingElement>("#helper-title");
  const statusElement =
    helperContentElement.querySelector<HTMLParagraphElement>("#helper-status");
  const detailElement =
    helperContentElement.querySelector<HTMLParagraphElement>("#helper-detail");
  const closeButton =
    helperContentElement.querySelector<HTMLButtonElement>("#helper-close-button");
  const openLink =
    helperContentElement.querySelector<HTMLAnchorElement>("#helper-open-link");

  if (titleElement) {
    titleElement.textContent = input.title;
  }
  if (statusElement) {
    statusElement.textContent = input.status;
  }
  if (detailElement) {
    detailElement.textContent =
      input.detail ??
      "This helper stays small on purpose and only exists for Android notifications.";
  }
  if (closeButton) {
    closeButton.textContent = input.closeLabel ?? "Close Helper";
  }
  if (openLink) {
    if (input.openUrl) {
      openLink.href = input.openUrl;
      openLink.textContent = input.openLabel ?? "Open Maru";
      openLink.classList.remove("helper-hidden");
    } else {
      openLink.removeAttribute("href");
      openLink.textContent = "Open Maru";
      openLink.classList.add("helper-hidden");
    }
  }
}

function formatMarucastDelayLabel(value: number | null | undefined) {
  if (typeof value !== "number" || !Number.isFinite(value) || value === 0) {
    return "0 ms";
  }

  return `${value > 0 ? "+" : ""}${Math.round(value)} ms`;
}

function formatMarucastMixPercent(
  value: number | null | undefined,
  fallback: number,
) {
  const safeValue =
    typeof value === "number" && Number.isFinite(value) ? value : fallback;
  return `${Math.round(safeValue * 100)}%`;
}

function getMarucastBroadcastModeLabel(status: HelperMarucastStatus | null) {
  if (status?.relayMode === "playback-capture") {
    return "Phone audio";
  }
  if (status?.relayMode === "sync-clock") {
    return "Lyric timing";
  }
  if (status?.relayMode === "local-file" || status?.running) {
    return "Chosen song";
  }
  return "Idle";
}

function getMarucastStemStatusLabel(status: HelperMarucastStatus | null) {
  if (status?.vocalStemModelReady) {
    return status.vocalMode === "remove" ? "Karaoke on" : "Karaoke ready";
  }
  return status?.vocalMode === "remove" ? "Karaoke starting" : "Full song";
}

function getMarucastSourceTitle(status: HelperMarucastStatus | null) {
  return (
    normalizeStatusText(status?.mediaTitle) ||
    normalizeStatusText(status?.selectedTitle) ||
    "No source selected yet"
  );
}

function getMarucastSourceSubtitle(status: HelperMarucastStatus | null) {
  if (status?.relayMode === "sync-clock") {
    return "Phone stays local while the helper shares native lyric timing for the other screen.";
  }
  if (status?.relayMode === "local-file" || status?.running) {
    return (
      normalizeStatusText(status?.mediaArtist) ||
      normalizeStatusText(status?.mediaAppLabel) ||
      "Ready to play the chosen song from this phone."
    );
  }
  return (
    normalizeStatusText(status?.mediaArtist) ||
    normalizeStatusText(status?.mediaAppLabel) ||
    "Play something on this phone, then tap Phone Audio."
  );
}

function renderHelperIcon(name: string, compact = false) {
  const className = compact
    ? "helper-icon helper-icon-compact"
    : "helper-icon";
  switch (name) {
    case "broadcast":
      return `<span class="${className}" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><path d="M10 7.25v5.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/><path d="M13.3 5.2a6 6 0 0 1 0 9.6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/><path d="M6.7 5.2a6 6 0 0 0 0 9.6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg></span>`;
    case "song":
      return `<span class="${className}" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><path d="M12.2 4.5v7.1a2.3 2.3 0 1 1-1.5-2.16V6.3l5-1.3v5.1a2.3 2.3 0 1 1-1.5-2.16V4.5l-2 .5Z" stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round"/></svg></span>`;
    case "file":
      return `<span class="${className}" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><path d="M6 3.8h5.3l3.2 3.2v8.7a1.5 1.5 0 0 1-1.5 1.5H6a1.5 1.5 0 0 1-1.5-1.5V5.3A1.5 1.5 0 0 1 6 3.8Z" stroke="currentColor" stroke-width="1.45" stroke-linejoin="round"/><path d="M11.3 3.8v3.1h3.2" stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round"/></svg></span>`;
    case "play":
      return `<span class="${className}" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><path d="M7 5.3 14 10l-7 4.7V5.3Z" fill="currentColor"/></svg></span>`;
    case "stop":
      return `<span class="${className}" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><rect x="5.8" y="5.8" width="8.4" height="8.4" rx="1.4" fill="currentColor"/></svg></span>`;
    case "karaoke":
      return `<span class="${className}" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><path d="M8.5 5.2a2.6 2.6 0 1 1 3.68 3.68l-1.4 1.4" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="m11.1 9.9 3.7 3.7" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="m5.3 14.6 2.9-2.9" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="m13.9 4.2.45 1.1 1.1.45-1.1.45-.45 1.1-.45-1.1-1.1-.45 1.1-.45.45-1.1Z" fill="currentColor"/></svg></span>`;
    case "mic":
      return `<span class="${className}" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><rect x="7.2" y="3.5" width="5.6" height="8.2" rx="2.8" stroke="currentColor" stroke-width="1.45"/><path d="M5.6 9.9a4.4 4.4 0 0 0 8.8 0" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="M10 14.3v2.2" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="M7.7 16.5h4.6" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/></svg></span>`;
    case "sync":
      return `<span class="${className}" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><path d="M15.2 7.5A5.5 5.5 0 0 0 6 5.6" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="M14.8 4.5v3.6h-3.6" stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round"/><path d="M4.8 12.5A5.5 5.5 0 0 0 14 14.4" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="M5.2 15.5v-3.6h3.6" stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round"/></svg></span>`;
    case "wifi":
      return `<span class="${className}" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><path d="M4.7 8.2a8.2 8.2 0 0 1 10.6 0" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="M7.2 10.8a4.7 4.7 0 0 1 5.6 0" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="M9.1 13.4a1.8 1.8 0 0 1 1.8 0" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><circle cx="10" cy="15.5" r="1" fill="currentColor"/></svg></span>`;
    case "link":
      return `<span class="${className}" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><path d="M8.1 11.9 6.7 13.3a2.6 2.6 0 0 1-3.7-3.7l2-2a2.6 2.6 0 0 1 3.7 0" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="m11.9 8.1 1.4-1.4a2.6 2.6 0 0 1 3.7 3.7l-2 2a2.6 2.6 0 0 1-3.7 0" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="m7.7 12.3 4.6-4.6" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/></svg></span>`;
    case "delay":
      return `<span class="${className}" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="11" r="5.3" stroke="currentColor" stroke-width="1.45"/><path d="M10 8.1v3.2l2.2 1.4" stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round"/><path d="M8.4 3.7h3.2" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/></svg></span>`;
    case "volume":
      return `<span class="${className}" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><path d="M5.3 11.9H3.8V8.1h1.5L8.8 5.2v9.6l-3.5-2.9Z" stroke="currentColor" stroke-width="1.45" stroke-linejoin="round"/><path d="M12.4 7.3a3.5 3.5 0 0 1 0 5.4" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="M14.9 5.3a6 6 0 0 1 0 9.4" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/></svg></span>`;
    case "open":
      return `<span class="${className}" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><path d="M11.2 4.8h4v4" stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round"/><path d="m8.1 11.9 7.1-7.1" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="M15 10.6v4a1.4 1.4 0 0 1-1.4 1.4H5.4A1.4 1.4 0 0 1 4 14.6V6.4A1.4 1.4 0 0 1 5.4 5H9.3" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/></svg></span>`;
    default:
      return "";
  }
}

function renderHelperStatusChip(input: {
  icon: string;
  label: string;
  tone?: "default" | "good" | "warn";
}) {
  const toneClass =
    input.tone === "good"
      ? " helper-status-chip-good"
      : input.tone === "warn"
        ? " helper-status-chip-warn"
        : "";
  return `<span class="helper-status-chip${toneClass}">${renderHelperIcon(input.icon, true)}<span>${escapeHtml(input.label)}</span></span>`;
}

function renderHelperActionButton(input: {
  action?: string;
  active?: boolean;
  href?: string;
  icon: string;
  id?: string;
  label: string;
  note?: string;
  subtle?: boolean;
}) {
  const className = [
    "helper-button",
    input.subtle ? "helper-button-subtle" : "",
    "helper-button-compact",
    "helper-action-button",
    input.active ? "helper-button-active" : "",
  ]
    .filter(Boolean)
    .join(" ");
  const content = `
    <span class="helper-button-stack">
      ${renderHelperIcon(input.icon)}
      <span class="helper-button-label-group">
        <span class="helper-button-label">${escapeHtml(input.label)}</span>
        ${input.note ? `<span class="helper-button-note">${escapeHtml(input.note)}</span>` : ""}
      </span>
    </span>
  `;

  if (input.href) {
    return `<a href="${escapeHtml(input.href)}" ${input.id ? `id="${escapeHtml(input.id)}"` : ""} class="${className}" target="_blank" rel="noreferrer">${content}</a>`;
  }

  return `<button type="button" class="${className}" ${input.id ? `id="${escapeHtml(input.id)}"` : ""} ${input.action ? `data-helper-action="${escapeHtml(input.action)}"` : ""}>${content}</button>`;
}

function renderHelperMicLevelMeter(level: number, activeNetworkMicClients: number) {
  const safeLevel = Math.max(0, Math.min(1, Number.isFinite(level) ? level : 0));
  const barCount = 10;
  const activeBars = Math.max(1, Math.round(safeLevel * barCount));
  const bars = Array.from({ length: barCount }, (_, index) => {
    const active = safeLevel > 0.02 && index < activeBars;
    return `<span class="helper-meter-bar${active ? " helper-meter-bar-active" : ""}"></span>`;
  }).join("");

  return `
    <div class="helper-mic-meter">
      <div class="helper-adjust-head">
        <div class="helper-adjust-title">${renderHelperIcon("mic", true)}<span>Mic Activity</span></div>
        <div class="helper-adjust-value">${Math.round(safeLevel * 100)}%</div>
      </div>
      <div class="helper-meter-strip" aria-hidden="true">${bars}</div>
      <div class="helper-adjust-note">
        ${
          activeNetworkMicClients > 0
            ? activeNetworkMicClients === 1
              ? "The separate mic lane is open on 1 receiver."
              : `The separate mic lane is open on ${activeNetworkMicClients} receivers.`
            : "The phone mic is active, but no receiver has opened the separate mic lane yet."
        }
      </div>
    </div>
  `;
}

async function refreshLinkedReceiverSurface() {
  const receiverToken = marucastPanelContext?.receiverToken?.trim();
  const serverOrigin = marucastPanelContext?.serverOrigin?.trim();
  if (!receiverToken || !serverOrigin) {
    marucastLinkedReceiverSurface = null;
    marucastLinkedReceiverSurfaceRequestKey = null;
    renderMarucastPanel();
    return;
  }

  const requestKey = `${serverOrigin}|${receiverToken}`;
  if (marucastLinkedReceiverSurfaceRequestKey === requestKey) {
    return;
  }
  marucastLinkedReceiverSurfaceRequestKey = requestKey;

  try {
    const response = await CapacitorHttp.post({
      url: `${serverOrigin}/api/auth`,
      headers: {
        "Content-Type": "application/json",
      },
      data: {
        route: "marucast/receiver-status",
        token: receiverToken,
      },
      responseType: "json",
    });

    const responseData =
      response.data && typeof response.data === "object"
        ? (response.data as HelperReceiverStatusResponse)
        : null;
    marucastLinkedReceiverSurface = normalizeReceiverSurface(
      responseData?.receiverSurface,
    );
  } catch {
    marucastLinkedReceiverSurface = null;
    marucastLinkedReceiverSurfaceRequestKey = null;
  }

  renderMarucastPanel();
}

function activateMarucastPanel(input: {
  openUrl: string;
  receiverToken?: string | null;
  serverOrigin?: string | null;
}) {
  activeHelperPanel = "marucast";
  marucastPanelContext = {
    openUrl: input.openUrl,
    receiverToken: input.receiverToken?.trim() || null,
    serverOrigin: input.serverOrigin?.trim() || null,
  };
  marucastLinkedReceiverSurface = null;
  marucastLinkedReceiverSurfaceRequestKey = null;
  renderMarucastPanel();
  void refreshLinkedReceiverSurface();

  if (marucastStatusPollId !== null) {
    return;
  }

  marucastStatusPollId = window.setInterval(() => {
    if (activeHelperPanel !== "marucast") {
      stopMarucastStatusPolling();
      return;
    }
    renderMarucastPanel();
  }, MARUCAST_STATUS_POLL_MS);
}

function renderMarucastPanel() {
  if (!helperContentElement || !marucastPanelContext) {
    return;
  }

  const status = getMarucastStatus();
  const sourceTitle = getMarucastSourceTitle(status);
  const sourceSubtitle = getMarucastSourceSubtitle(status);
  const pairingCode = normalizeStatusText(status?.pairingCode) || "";
  const receiverConnected =
    getActiveNetworkRelayClients(status) > 0 ||
    (typeof status?.activeLoopbackRelayClients === "number" &&
      Math.max(0, Math.trunc(status.activeLoopbackRelayClients)) > 0);
  const receiverStatusLabel = receiverConnected
    ? "Receiver connected"
    : marucastPanelContext.receiverToken
      ? "Receiver ready"
      : pairingCode
        ? `Pair ${pairingCode}`
        : "No receiver yet";
  const wifiWarning =
    normalizeStatusText(status?.sameWifiRequiredMessage) || "";
  const responseMessage =
    normalizeStatusText(status?.message) ||
    normalizeStatusText(status?.lastError) ||
    normalizeStatusText(status?.micLastError) ||
    "";
  const detailMessage =
    marucastPanelMessage || (responseMessage && !marucastPanelError ? responseMessage : "");
  const errorMessage =
    marucastPanelError ||
    normalizeStatusText(status?.lastError) ||
    normalizeStatusText(status?.micLastError) ||
    "";
  const openUrl = marucastPanelContext.openUrl;
  const micLevel =
    typeof status?.micLevel === "number" && Number.isFinite(status.micLevel)
      ? Math.max(0, Math.min(1, status.micLevel))
      : 0;
  const activeNetworkMicClients =
    typeof status?.activeNetworkMicClients === "number" &&
    Number.isFinite(status.activeNetworkMicClients)
      ? Math.max(0, Math.trunc(status.activeNetworkMicClients))
      : 0;
  const tvVolumeState = (() => {
    if (!marucastPanelContext.receiverToken) {
      return {
        enabled: false,
        note: "Link a receiver first.",
        value: "No receiver",
      };
    }

    if (marucastLinkedReceiverSurface === "tv-app") {
      return {
        enabled: receiverConnected,
        note: receiverConnected
          ? "Use Maru TV's native volume so the helper controls the actual TV loudness."
          : "TV app found. Connect it first, then the helper can nudge the TV's own volume.",
        value: receiverConnected ? "TV app" : "TV waiting",
      };
    }

    if (marucastLinkedReceiverSurface === "desktop-web") {
      return {
        enabled: false,
        note: "Desktop receivers only expose local browser speaker volume here.",
        value: "Desktop",
      };
    }

    return {
      enabled: false,
      note: "Checking whether this linked receiver is Maru TV or a desktop browser.",
      value: "Checking",
    };
  })();
  const statusChips = [
    renderHelperStatusChip({
      icon: "wifi",
      label: status?.wifiConnected === false ? "Wi-Fi needed" : "Same Wi-Fi",
      tone: status?.wifiConnected === false ? "warn" : "good",
    }),
    renderHelperStatusChip({
      icon: "broadcast",
      label: getMarucastBroadcastModeLabel(status),
    }),
    renderHelperStatusChip({
      icon: receiverConnected ? "broadcast" : "link",
      label: receiverStatusLabel,
      tone: receiverConnected ? "good" : undefined,
    }),
    status?.micCaptureActive
      ? renderHelperStatusChip({
          icon: "mic",
          label: "Phone mic on",
          tone: "good",
        })
      : "",
    status?.vocalMode === "remove" || status?.vocalStemModelReady
      ? renderHelperStatusChip({
          icon: "karaoke",
          label: getMarucastStemStatusLabel(status),
          tone: status?.vocalMode === "remove" ? "good" : undefined,
        })
      : "",
  ]
    .filter(Boolean)
    .join("");

  helperContentElement.innerHTML = `
    <div class="helper-kicker">Maru Helper</div>
    <div class="helper-panel-head">
      <div>
        <h1>Marucast Controls</h1>
        <p class="helper-panel-copy">
          Start the song here, then tune karaoke and mic from the phone. Same Wi-Fi only.
        </p>
      </div>
    </div>

    <section class="helper-panel-card helper-panel-card-hero">
      <div class="helper-card-topline">
        <div class="helper-inline-heading">
          ${renderHelperIcon("song", true)}
          <span>Now sending</span>
        </div>
      </div>
      <div class="helper-source-title">${escapeHtml(sourceTitle)}</div>
      <div class="helper-source-meta">${escapeHtml(sourceSubtitle)}</div>
      <div class="helper-status-row">
        ${statusChips}
      </div>
    </section>

    ${wifiWarning ? `<div class="helper-message helper-message-warning">${escapeHtml(wifiWarning)}</div>` : ""}
    ${errorMessage ? `<div class="helper-message helper-message-error">${escapeHtml(errorMessage)}</div>` : ""}
    ${detailMessage && !errorMessage ? `<div class="helper-message helper-message-success">${escapeHtml(detailMessage)}</div>` : ""}

    <div class="helper-grid">
      <section class="helper-panel-card">
        <div class="helper-inline-heading">
          ${renderHelperIcon("broadcast", true)}
          <span>Quick actions</span>
        </div>
        <div class="helper-action-grid">
          ${renderHelperActionButton({
            action: "live-broadcast",
            active: status?.relayMode === "playback-capture",
            icon: "broadcast",
            label: "Phone Audio",
            note: "Use what's playing now",
          })}
          ${renderHelperActionButton({
            action: "pick-audio",
            icon: "file",
            label: "Choose Song",
            note: "Pick a local audio file",
            subtle: true,
          })}
          ${renderHelperActionButton({
            action: "start-selected-audio",
            active: status?.relayMode === "local-file" || status?.running,
            icon: "play",
            label: "Play Chosen",
            note: "Start the selected file",
            subtle: !(status?.relayMode === "local-file" || status?.running),
          })}
          ${renderHelperActionButton({
            action: "end-broadcast",
            icon: "stop",
            label: "Stop",
            note: "End the current broadcast",
            subtle: true,
          })}
          ${renderHelperActionButton({
            action: "toggle-vocal",
            active: status?.vocalMode === "remove",
            icon: "karaoke",
            label: status?.vocalMode === "remove" ? "Full Song" : "Karaoke",
            note: status?.vocalMode === "remove" ? "Bring vocals back" : "Remove vocals",
            subtle: status?.vocalMode !== "remove",
          })}
          ${renderHelperActionButton({
            action: "toggle-mic",
            active: status?.micCaptureActive,
            icon: "mic",
            label: status?.micCaptureActive ? "Mic Off" : "Phone Mic",
            note: status?.micCaptureActive ? "Stop the live mic lane" : "Send the phone mic too",
            subtle: status?.micCaptureActive !== true,
          })}
          ${renderHelperActionButton({
            action: "sync-clock",
            active: status?.relayMode === "sync-clock",
            icon: "sync",
            label: "Native Lyric Timing",
            note: "Start lyric timing without audio",
            subtle: status?.relayMode !== "sync-clock",
          })}
          ${renderHelperActionButton({
            action: "clear-audio",
            icon: "file",
            label: "Clear Song",
            note: "Forget the chosen file",
            subtle: true,
          })}
        </div>
      </section>

      <section class="helper-panel-card">
        <div class="helper-inline-heading">
          ${renderHelperIcon("volume", true)}
          <span>Sound</span>
        </div>
        ${status?.micCaptureActive ? renderHelperMicLevelMeter(micLevel, activeNetworkMicClients) : ""}
        <div class="helper-adjust-grid">
          <div class="helper-adjust-row">
            <div class="helper-adjust-head">
              <div class="helper-adjust-title">${renderHelperIcon("delay", true)}<span>Song Delay</span></div>
              <div class="helper-adjust-value">${escapeHtml(formatMarucastDelayLabel(status?.relayDelayMs))}</div>
            </div>
            <div class="helper-adjust-actions">
              <button type="button" class="helper-button helper-button-subtle helper-button-mini" data-helper-action="delay-down">-250</button>
              <button type="button" class="helper-button helper-button-subtle helper-button-mini" data-helper-action="delay-reset">0</button>
              <button type="button" class="helper-button helper-button-subtle helper-button-mini" data-helper-action="delay-up">+250</button>
            </div>
          </div>

          <div class="helper-adjust-row">
            <div class="helper-adjust-head">
              <div class="helper-adjust-title">${renderHelperIcon("mic", true)}<span>Mic Level</span></div>
              <div class="helper-adjust-value">${escapeHtml(formatMarucastMixPercent(status?.micMixGain, 1.28))}</div>
            </div>
            <div class="helper-adjust-actions helper-adjust-actions-two">
              <button type="button" class="helper-button helper-button-subtle helper-button-mini" data-helper-action="mic-gain-down">Lower</button>
              <button type="button" class="helper-button helper-button-subtle helper-button-mini" data-helper-action="mic-gain-up">Raise</button>
            </div>
          </div>

          <div class="helper-adjust-row">
            <div class="helper-adjust-head">
              <div class="helper-adjust-title">${renderHelperIcon("song", true)}<span>Music Level</span></div>
              <div class="helper-adjust-value">${escapeHtml(formatMarucastMixPercent(status?.micMusicBedLevel, 0.8))}</div>
            </div>
            <div class="helper-adjust-actions helper-adjust-actions-two">
              <button type="button" class="helper-button helper-button-subtle helper-button-mini" data-helper-action="music-bed-down">Lower</button>
              <button type="button" class="helper-button helper-button-subtle helper-button-mini" data-helper-action="music-bed-up">Raise</button>
            </div>
          </div>

          <div class="helper-adjust-row">
            <div class="helper-adjust-head">
              <div class="helper-adjust-title">${renderHelperIcon("volume", true)}<span>TV Volume</span></div>
              <div class="helper-adjust-value">${escapeHtml(tvVolumeState.value)}</div>
            </div>
            <div class="helper-adjust-actions helper-adjust-actions-two">
              <button type="button" class="helper-button helper-button-subtle helper-button-mini" data-helper-action="tv-volume-down" ${tvVolumeState.enabled ? "" : "disabled"}>Lower</button>
              <button type="button" class="helper-button helper-button-subtle helper-button-mini" data-helper-action="tv-volume-up" ${tvVolumeState.enabled ? "" : "disabled"}>Raise</button>
            </div>
            <div class="helper-adjust-note">${escapeHtml(tvVolumeState.note)}</div>
          </div>
        </div>
      </section>
    </div>

    <div class="helper-actions helper-actions-panel">
      ${renderHelperActionButton({
        href: openUrl,
        icon: "open",
        id: "helper-open-link",
        label: "Open Maru",
        note: "Return to the website",
        subtle: true,
      })}
      <button id="helper-close-button" class="helper-button" type="button">
        Close Helper
      </button>
    </div>
  `;
}

function applyMarucastStatusResult(
  nextStatus: HelperMarucastStatus | null,
  fallbackMessage: string,
  fallbackError: string,
) {
  if (!nextStatus) {
    marucastPanelError = fallbackError;
    marucastPanelMessage = "";
    renderMarucastPanel();
    return;
  }

  const responseMessage =
    normalizeStatusText(nextStatus.message) ||
    normalizeStatusText(nextStatus.sameWifiRequiredMessage) ||
    normalizeStatusText(nextStatus.lastError) ||
    normalizeStatusText(nextStatus.micLastError) ||
    "";

  if (nextStatus.success === false) {
    marucastPanelError = responseMessage || fallbackError;
    marucastPanelMessage = "";
    renderMarucastPanel();
    return;
  }

  marucastPanelError = "";
  marucastPanelMessage = responseMessage || fallbackMessage;
  renderMarucastPanel();
}

function dispatchNativeMarucastCommand(
  command: string,
  fallbackMessage: string,
  fallbackError: string,
) {
  const nextStatus = parseBridgeJson<HelperMarucastStatus>(
    window.HelperNativeBridge?.dispatchMarucastControlCommand?.(command),
  );
  applyMarucastStatusResult(nextStatus, fallbackMessage, fallbackError);
}

async function dispatchHelperReceiverCommand(command: "tv-volume-down" | "tv-volume-up") {
  const receiverToken = marucastPanelContext?.receiverToken?.trim();
  const serverOrigin = marucastPanelContext?.serverOrigin?.trim();
  if (!receiverToken || !serverOrigin) {
    marucastPanelError = "Link a receiver first before trying to control TV volume.";
    marucastPanelMessage = "";
    renderMarucastPanel();
    return;
  }

  marucastPanelError = "";
  marucastPanelMessage =
    command === "tv-volume-up"
      ? "Asking Maru TV to raise the TV's own volume..."
      : "Asking Maru TV to lower the TV's own volume...";
  renderMarucastPanel();

  try {
    const response = await CapacitorHttp.post({
      url: `${serverOrigin}/api/auth`,
      headers: {
        "Content-Type": "application/json",
      },
      data: {
        command,
        route: "marucast/receiver-command",
        token: receiverToken,
      },
      responseType: "json",
    });

    const responseData =
      response.data && typeof response.data === "object"
        ? (response.data as HelperReceiverCommandResponse)
        : null;

    if (response.status < 200 || response.status >= 300 || responseData?.success !== true) {
      throw new Error(
        normalizeStatusText(responseData?.error) ||
          "This helper could not reach that Marucast receiver right now.",
      );
    }

    marucastLinkedReceiverSurface = normalizeReceiverSurface(
      responseData?.receiverSurface,
    );
    marucastPanelError = "";
    marucastPanelMessage =
      command === "tv-volume-up"
        ? "Asked Maru TV to raise the TV's own volume."
        : "Asked Maru TV to lower the TV's own volume.";
    renderMarucastPanel();
  } catch (error) {
    marucastPanelError =
      error instanceof Error && error.message.trim()
        ? error.message.trim()
        : "This helper could not reach that Marucast receiver right now.";
    marucastPanelMessage = "";
    renderMarucastPanel();
  }
}

function readHelperTargetReceiverToken(target: string | null | undefined) {
  const trimmedTarget = target?.trim();
  if (!trimmedTarget) {
    return null;
  }

  try {
    const parsed = new URL(trimmedTarget, "https://maru.local");
    return parsed.searchParams.get("receiverToken")?.trim() || null;
  } catch {
    return null;
  }
}

function helperTargetWantsMarucastPanel(target: string | null | undefined) {
  const trimmedTarget = target?.trim();
  if (!trimmedTarget) {
    return false;
  }

  try {
    const parsed = new URL(trimmedTarget, "https://maru.local");
    return (
      parsed.pathname === "/helper" &&
      parsed.searchParams.get("panel")?.trim() === "marucast"
    );
  } catch {
    return false;
  }
}

function getMarucastStatus() {
  return parseBridgeJson<HelperMarucastStatus>(
    window.HelperNativeBridge?.getMarucastStatus?.(),
  );
}

function getPreferredHelperRelayUrl(status: HelperMarucastStatus | null) {
  return (
    normalizeStatusText(status?.loopbackLiveStreamUrl) ||
    normalizeStatusText(status?.liveStreamUrl)
  );
}

function getReceiverFacingHelperRelayUrl(status: HelperMarucastStatus | null) {
  return (
    normalizeStatusText(status?.liveStreamUrl) ||
    normalizeStatusText(status?.loopbackLiveStreamUrl)
  );
}

function buildMarucastSenderMetadata(status: HelperMarucastStatus | null) {
  return {
    artworkUrl:
      normalizeStatusText(status?.artworkUrl) ||
      normalizeStatusText(status?.loopbackArtworkUrl),
    mediaAppLabel: normalizeStatusText(status?.mediaAppLabel),
    mediaArtist: normalizeStatusText(status?.mediaArtist),
    mediaDurationMs:
      typeof status?.mediaDurationMs === "number" && Number.isFinite(status.mediaDurationMs)
        ? Math.max(0, Math.round(status.mediaDurationMs))
        : null,
    mediaPlaying:
      typeof status?.mediaPlaying === "boolean" ? status.mediaPlaying : null,
    mediaPlaybackSpeed:
      typeof status?.mediaPlaybackSpeed === "number" &&
      Number.isFinite(status.mediaPlaybackSpeed)
        ? status.mediaPlaybackSpeed
        : null,
    mediaPositionCapturedAtMs:
      typeof status?.mediaPositionCapturedAtMs === "number" &&
      Number.isFinite(status.mediaPositionCapturedAtMs)
        ? Math.round(status.mediaPositionCapturedAtMs)
        : null,
    mediaPositionMs:
      typeof status?.mediaPositionMs === "number" && Number.isFinite(status.mediaPositionMs)
        ? Math.max(0, Math.round(status.mediaPositionMs))
        : null,
    title:
      normalizeStatusText(status?.mediaTitle) ||
      normalizeStatusText(status?.selectedTitle) ||
      "Current phone playback",
    karaokeEnabled: status?.karaokeEnabled === true,
    transportAvailable: status?.transportAvailable === true,
    vocalProcessingKind: normalizeStatusText(status?.vocalProcessingKind),
    vocalStemModelReady: status?.vocalStemModelReady === true,
    vocalMode: status?.vocalMode === "remove" ? "remove" : "normal",
  };
}

function openNotificationListenerSettings() {
  try {
    window.HelperNativeBridge?.openNotificationListenerSettings?.();
    return true;
  } catch {
    return false;
  }
}

function buildMarucastPrimaryAction(
  status: HelperMarucastStatus | null,
  openUrl: string | null,
) {
  if (status?.mediaAccessEnabled === false) {
    return {
      openLabel: "Open Notification Access",
      openUrl: NATIVE_NOTIFICATION_SETTINGS_URL,
    };
  }

  return {
    openLabel: "Open Maru",
    openUrl,
  };
}

function stopMarucastStatusPolling() {
  if (marucastStatusPollId !== null) {
    window.clearInterval(marucastStatusPollId);
    marucastStatusPollId = null;
  }
}

function buildMarucastDetail(
  status: HelperMarucastStatus | null,
  fallback: string,
) {
  const detailParts = [fallback];
  const mediaTitle =
    normalizeStatusText(status?.mediaTitle) ||
    normalizeStatusText(status?.selectedTitle) ||
    "";
  const mediaArtist = normalizeStatusText(status?.mediaArtist) || "";
  const mediaAppLabel = normalizeStatusText(status?.mediaAppLabel) || "";

  if (mediaTitle) {
    detailParts.push(`Now playing: ${mediaTitle}`);
  }
  if (mediaArtist) {
    detailParts.push(`Artist: ${mediaArtist}`);
  }
  if (mediaAppLabel) {
    detailParts.push(`Source app: ${mediaAppLabel}`);
  }
  if (status?.mediaAccessEnabled === false) {
    detailParts.push(
      "Android notification access is still off, so Marucast cannot read the app name or track details yet.",
    );
  }

  return detailParts.join("\n");
}

const MARUCAST_DIRECT_RELAY_CONNECTED_ACK = "__marucast_direct_relay_connected__";

function wait(ms: number) {
  return new Promise<void>((resolve) => {
    window.setTimeout(resolve, ms);
  });
}

async function waitForMarucastReceiverLink(input: {
  allowDirectRelay: boolean;
  initialActiveNetworkRelayClients: number;
  serverOrigin: string;
  token: string;
}) {
  const startedAt = Date.now();

  while (Date.now() - startedAt < 30000) {
    const response = await CapacitorHttp.post({
      url: `${input.serverOrigin}/api/auth`,
      headers: {
        "Content-Type": "application/json",
      },
      data: {
        route: "marucast/receiver-status",
        token: input.token,
      },
      responseType: "json",
    });

    const responseData =
      response.data && typeof response.data === "object"
        ? (response.data as HelperReceiverStatusResponse)
        : null;

    if (response.status >= 200 && response.status < 300) {
      const answerPayload = normalizeStatusText(responseData?.answerPayload);
      if (answerPayload === MARUCAST_DIRECT_RELAY_CONNECTED_ACK) {
        return {
          answerPayload: null,
          kind: "direct-relay" as const,
        };
      }
      if (answerPayload) {
        return {
          answerPayload,
          kind: "browser-answer" as const,
        };
      }

      if (responseData?.status === "expired") {
        throw new Error("That receiver QR expired before the browser handoff finished.");
      }
    } else if (responseData?.error?.trim()) {
      throw new Error(responseData.error.trim());
    }

    if (
      input.allowDirectRelay &&
      getActiveNetworkRelayClients(getMarucastStatus()) > input.initialActiveNetworkRelayClients
    ) {
      return {
        answerPayload: null,
        kind: "direct-relay" as const,
      };
    }

    await wait(700);
  }

  if (
    input.allowDirectRelay &&
    getActiveNetworkRelayClients(getMarucastStatus()) > input.initialActiveNetworkRelayClients
  ) {
    return {
      answerPayload: null,
      kind: "direct-relay" as const,
    };
  }

  throw new Error("The receiver did not answer the Marucast browser handoff in time.");
}

function startMarucastStatusPolling(openUrl: string) {
  stopMarucastStatusPolling();
  marucastPanelError = "";
  marucastPanelMessage =
    "Approve Android's playback capture prompt on this phone.";
  activateMarucastPanel({
    openUrl,
    receiverToken: marucastPanelContext?.receiverToken ?? null,
    serverOrigin: marucastPanelContext?.serverOrigin ?? null,
  });
}

function isMarucastRelayRunning(status: HelperMarucastStatus | null) {
  return Boolean(status?.captureActive || status?.running);
}

function getActiveNetworkRelayClients(status: HelperMarucastStatus | null) {
  return typeof status?.activeNetworkRelayClients === "number"
    ? Math.max(0, Math.trunc(status.activeNetworkRelayClients))
    : 0;
}

async function waitForMarucastRelayReady(openUrl: string) {
  activateMarucastPanel({
    openUrl,
    receiverToken: marucastPanelContext?.receiverToken ?? null,
    serverOrigin: marucastPanelContext?.serverOrigin ?? null,
  });
  const startedAt = Date.now();

  while (Date.now() - startedAt < 45000) {
    const status = getMarucastStatus();
    const senderRelayUrl = getPreferredHelperRelayUrl(status);
    const receiverRelayUrl = getReceiverFacingHelperRelayUrl(status);

    if (status?.lastError?.trim()) {
      throw new Error(status.lastError.trim());
    }

    if (senderRelayUrl && isMarucastRelayRunning(status)) {
      return {
        relayStatus: status,
        receiverRelayUrl,
        senderRelayUrl,
      };
    }

    marucastPanelError = "";
    marucastPanelMessage =
      "The helper is starting Marucast for this QR handoff. Once Android allows capture, the receiver link will continue automatically.";
    renderMarucastPanel();

    await wait(900);
  }

  throw new Error("Android took too long to start the Marucast broadcast for this handoff.");
}

function readReceiverTokenFromTarget(target: string | null | undefined) {
  const trimmedTarget = target?.trim();
  if (!trimmedTarget) {
    return null;
  }

  try {
    const parsed = new URL(trimmedTarget, "https://maru.local");
    const rawToken = parsed.searchParams.get("receiverToken");
    const trimmedToken = rawToken?.trim();
    return trimmedToken || null;
  } catch {
    return null;
  }
}

async function shareMarucastRelayToWaitingReceiver(input: {
  serverOrigin: string | null;
  target: string;
}) {
  stopMarucastStatusPolling();
  const receiverToken = readReceiverTokenFromTarget(input.target);
  let relayStatus = getMarucastStatus();
  let relayUrl = getPreferredHelperRelayUrl(relayStatus);
  let receiverRelayUrl = getReceiverFacingHelperRelayUrl(relayStatus);
  let initialActiveNetworkRelayClients = getActiveNetworkRelayClients(relayStatus);
  const openUrl = buildHelperSiteUrl(input.serverOrigin, "/marucast");

  activateMarucastPanel({
    openUrl,
    receiverToken,
    serverOrigin: input.serverOrigin,
  });

  if (!receiverToken) {
    marucastPanelError =
      "This Marucast handoff did not include a waiting receiver code.";
    marucastPanelMessage =
      "Scan the receiver QR code again from the screen that should become the speaker.";
    renderMarucastPanel();
    return true;
  }

  if (!input.serverOrigin) {
    marucastPanelError =
      "This helper could not tell which Maru site should receive the handoff.";
    marucastPanelMessage = "Open Maru first, then try the QR handoff again.";
    renderMarucastPanel();
    return true;
  }

  if (!relayUrl || !isMarucastRelayRunning(relayStatus)) {
    marucastPanelError = "";
    marucastPanelMessage =
      "Approve Android's playback capture prompt on this phone.";
    renderMarucastPanel();
    try {
      window.HelperNativeBridge?.startMarucastPlaybackRelay?.();
    } catch {
      // Native bridge failures are reflected through status polling below.
    }

    const readyRelay = await waitForMarucastRelayReady(openUrl);
    relayStatus = readyRelay.relayStatus;
    relayUrl = readyRelay.senderRelayUrl;
    receiverRelayUrl = readyRelay.receiverRelayUrl;
    initialActiveNetworkRelayClients = getActiveNetworkRelayClients(relayStatus);
  }

  marucastPanelError = "";
  marucastPanelMessage =
    "Preparing the browser-safe Marucast relay for the waiting receiver now.";
  renderMarucastPanel();

  try {
    await prepareMarucastBrowserLivePcmSender(
      relayUrl,
      buildMarucastSenderMetadata(relayStatus),
    );
    const browserRelayState = getMarucastBrowserState();
    const offerPayload = normalizeStatusText(browserRelayState.offerPayload);
    if (!offerPayload) {
      throw new Error(
        normalizeStatusText(browserRelayState.lastError) ||
          "The helper could not prepare the browser-safe Marucast relay.",
      );
    }

    const response = await CapacitorHttp.post({
      url: `${input.serverOrigin}/api/auth`,
      headers: {
        "Content-Type": "application/json",
      },
      data: {
        route: "marucast/receiver-complete",
        token: receiverToken,
        mediaAccessEnabled: relayStatus?.mediaAccessEnabled === true,
        deviceName: normalizeStatusText(relayStatus?.deviceName),
        serviceName: normalizeStatusText(relayStatus?.serviceName),
        mediaAppLabel: normalizeStatusText(relayStatus?.mediaAppLabel),
        mediaArtist: normalizeStatusText(relayStatus?.mediaArtist),
        mediaTitle:
          normalizeStatusText(relayStatus?.mediaTitle) ||
          normalizeStatusText(relayStatus?.selectedTitle),
        offerPayload,
        relayUrl: receiverRelayUrl,
        relayMode: "webrtc-live",
        sampleRate:
          typeof relayStatus?.sampleRate === "number" ? relayStatus.sampleRate : null,
        channelCount:
          typeof relayStatus?.channelCount === "number" ? relayStatus.channelCount : null,
        vocalProcessingKind: normalizeStatusText(relayStatus?.vocalProcessingKind),
        vocalStemModelReady: relayStatus?.vocalStemModelReady === true,
      },
      responseType: "json",
    });

    const responseData =
      response.data && typeof response.data === "object"
        ? (response.data as { error?: string; success?: boolean })
        : null;

    if (response.status < 200 || response.status >= 300 || !responseData?.success) {
      throw new Error(
        responseData?.error?.trim() ||
          "Maru could not finish handing this broadcast to the receiver.",
      );
    }

    marucastPanelMessage =
      "The other screen is preparing its Marucast speaker lane now.";
    renderMarucastPanel();

    const receiverLink = await waitForMarucastReceiverLink({
      allowDirectRelay: Boolean(receiverRelayUrl),
      initialActiveNetworkRelayClients,
      serverOrigin: input.serverOrigin,
      token: receiverToken,
    });
    if (receiverLink.kind === "browser-answer" && receiverLink.answerPayload) {
      await applyMarucastBrowserSenderAnswer(receiverLink.answerPayload);
    } else if (receiverLink.kind === "direct-relay") {
      stopMarucastBrowserSession();
    }

    marucastPanelError = "";
    marucastPanelMessage =
      receiverLink.kind === "direct-relay"
        ? "Receiver linked. The same-Wi-Fi receiver is already listening directly."
        : "Receiver linked. The waiting screen can now listen through the helper relay.";
    renderMarucastPanel();
  } catch (error) {
    marucastPanelError =
      error instanceof Error && error.message.trim()
        ? error.message.trim()
        : "Maru could not hand this broadcast to the waiting receiver right now.";
    marucastPanelMessage =
      "Keep broadcasting on this phone, then show the receiver QR code again and retry.";
    renderMarucastPanel();
  }

  return true;
}

function handleMarucastAction(input: {
  action: string;
  serverOrigin: string | null;
  target: string;
}) {
  const openUrl = buildHelperSiteUrl(input.serverOrigin, input.target || "/marucast");
  const action = input.action.trim();

  if (action === "start-live-relay") {
    activateMarucastPanel({
      openUrl,
      receiverToken: readHelperTargetReceiverToken(input.target),
      serverOrigin: input.serverOrigin,
    });
    marucastPanelError = "";
    marucastPanelMessage =
      "Approve Android's playback capture prompt on this phone.";
    try {
      window.HelperNativeBridge?.startMarucastPlaybackRelay?.();
    } catch {
      // Native bridge failures are reflected through status polling below.
    }

    startMarucastStatusPolling(openUrl);
    return true;
  }

  if (action === "stop-live-relay") {
    try {
      window.HelperNativeBridge?.stopMarucastPlaybackRelay?.();
    } catch {
      // Ignore bridge failures and just show the fallback copy.
    }

    stopMarucastBrowserSession();
    stopMarucastStatusPolling();
    activateMarucastPanel({
      openUrl,
      receiverToken: readHelperTargetReceiverToken(input.target),
      serverOrigin: input.serverOrigin,
    });
    marucastPanelError = "";
    marucastPanelMessage = "The helper stopped broadcasting on this phone.";
    renderMarucastPanel();
    return true;
  }

  if (action === "share-live-relay") {
    void shareMarucastRelayToWaitingReceiver(input);
    return true;
  }

  return false;
}

function buildRandomInstallationId() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }

  return `helper-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function getHelperInstallationId() {
  const stored = localStorage.getItem(HELPER_INSTALLATION_ID_KEY)?.trim();
  if (stored) {
    try {
      window.HelperNativeBridge?.persistInstallationId?.(stored);
    } catch {
      // Ignore native bridge failures.
    }
    return stored;
  }

  const nextValue = buildRandomInstallationId();
  localStorage.setItem(HELPER_INSTALLATION_ID_KEY, nextValue);
  try {
    window.HelperNativeBridge?.persistInstallationId?.(nextValue);
  } catch {
    // Ignore native bridge failures.
  }
  return nextValue;
}

function normalizeServerOrigin(rawOrigin: string | null | undefined) {
  const trimmedOrigin = rawOrigin?.trim();
  if (!trimmedOrigin) {
    return null;
  }

  try {
    const parsed = new URL(trimmedOrigin);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
      return null;
    }
    return parsed.origin.replace(/\/+$/, "");
  } catch {
    return null;
  }
}

const DEFAULT_HELPER_SITE_ORIGIN =
  normalizeServerOrigin(import.meta.env.VITE_HELPER_SITE_ORIGIN) ??
  DEFAULT_HELPER_SITE_ORIGIN_FALLBACK;

function persistServerOrigin(rawOrigin: string | null | undefined) {
  const normalized = normalizeServerOrigin(rawOrigin);
  if (normalized) {
    localStorage.setItem(HELPER_SERVER_ORIGIN_KEY, normalized);
    try {
      window.HelperNativeBridge?.persistServerOrigin?.(normalized);
    } catch {
      // Ignore native bridge failures.
    }
    return normalized;
  }

  const storedOrigin =
    normalizeServerOrigin(localStorage.getItem(HELPER_SERVER_ORIGIN_KEY)) ?? null;
  if (storedOrigin) {
    try {
      window.HelperNativeBridge?.persistServerOrigin?.(storedOrigin);
    } catch {
      // Ignore native bridge failures.
    }
  }
  return storedOrigin;
}

function parseHelperUrl(rawUrl: string | null | undefined) {
  if (!rawUrl) {
    return null;
  }

  try {
    const parsed = new URL(rawUrl);
    if (parsed.protocol !== `${HELPER_APP_SCHEME}:`) {
      return null;
    }

    return {
      action: parsed.searchParams.get("action")?.trim() ?? "",
      token: parsed.searchParams.get("token")?.trim() ?? "",
      siteOrigin: parsed.searchParams.get("siteOrigin")?.trim() ?? "",
      target:
        parsed.searchParams.get("target")?.trim() ??
        parsed.searchParams.get("url")?.trim() ??
        "",
    };
  } catch {
    return null;
  }
}

function openExternalUrl(rawUrl: string | null | undefined) {
  const trimmedUrl = rawUrl?.trim();
  if (!trimmedUrl) {
    return false;
  }

  try {
    if (typeof window.HelperNativeBridge?.openExternalUrl === "function") {
      window.HelperNativeBridge.openExternalUrl(trimmedUrl);
      return true;
    }
  } catch {
    // Fall through.
  }

  try {
    window.open(trimmedUrl, "_blank", "noopener,noreferrer");
    return true;
  } catch {
    return false;
  }
}

function openHelperLink(rawUrl: string | null | undefined) {
  const trimmedUrl = rawUrl?.trim();
  if (!trimmedUrl) {
    return false;
  }

  if (trimmedUrl === NATIVE_NOTIFICATION_SETTINGS_URL) {
    return openNotificationListenerSettings();
  }

  return openExternalUrl(trimmedUrl);
}

function buildHelperSiteUrl(
  serverOrigin: string | null,
  target: string | null | undefined,
) {
  return (
    buildOpenUrl(target, serverOrigin ?? DEFAULT_HELPER_SITE_ORIGIN) ??
    `${DEFAULT_HELPER_SITE_ORIGIN}/helper`
  );
}

function handleMarucastPanelAction(rawAction: string | null | undefined) {
  if (activeHelperPanel !== "marucast" || !marucastPanelContext) {
    return;
  }

  const action = rawAction?.trim() || "";
  const status = getMarucastStatus();

  switch (action) {
    case "live-broadcast":
      marucastPanelError = "";
      marucastPanelMessage =
        "Approve Android's playback capture prompt on this phone.";
      renderMarucastPanel();
      try {
        window.HelperNativeBridge?.startMarucastPlaybackRelay?.();
      } catch {
        marucastPanelError =
          "This helper could not open Android's live capture prompt right now.";
        marucastPanelMessage = "";
        renderMarucastPanel();
      }
      return;
    case "pick-audio":
      marucastPanelError = "";
      marucastPanelMessage = "Choose an audio file on this phone for Marucast.";
      renderMarucastPanel();
      try {
        window.HelperNativeBridge?.pickMarucastAudioFile?.();
      } catch {
        marucastPanelError = "This helper could not open Android's audio picker.";
        marucastPanelMessage = "";
        renderMarucastPanel();
      }
      return;
    case "start-selected-audio":
      applyMarucastStatusResult(
        parseBridgeJson<HelperMarucastStatus>(
          window.HelperNativeBridge?.startMarucastSender?.(),
        ),
        "Local audio broadcast started.",
        "Pick a local audio file first.",
      );
      return;
    case "end-broadcast":
      applyMarucastStatusResult(
        status?.captureActive || status?.relayMode === "playback-capture"
          ? parseBridgeJson<HelperMarucastStatus>(
              window.HelperNativeBridge?.stopMarucastPlaybackRelay?.(),
            )
          : parseBridgeJson<HelperMarucastStatus>(
              window.HelperNativeBridge?.stopMarucastSender?.(),
            ),
        "Marucast stopped on this helper.",
        "This helper could not stop Marucast right now.",
      );
      stopMarucastBrowserSession();
      return;
    case "clear-audio":
      applyMarucastStatusResult(
        parseBridgeJson<HelperMarucastStatus>(
          window.HelperNativeBridge?.clearMarucastAudioSelection?.(),
        ),
        "Cleared the selected local audio file.",
        "This helper could not clear the selected local file right now.",
      );
      return;
    case "toggle-vocal":
      dispatchNativeMarucastCommand(
        status?.vocalMode === "remove" ? "vocal-normal" : "vocal-remove",
        status?.vocalMode === "remove"
          ? "Normal mix restored."
          : "Karaoke mode enabled.",
        "This helper could not switch Karaoke mode right now.",
      );
      return;
    case "toggle-mic":
      dispatchNativeMarucastCommand(
        status?.micCaptureActive ? "mic-stop" : "mic-start",
        status?.micCaptureActive
          ? "Phone mic lane stopped."
          : "Phone mic lane is live now.",
        "This helper could not switch the phone mic lane right now.",
      );
      return;
    case "sync-clock":
      applyMarucastStatusResult(
        parseBridgeJson<HelperMarucastStatus>(
          window.HelperNativeBridge?.startMarucastClockSync?.(),
        ),
        "Native lyric timing is live on this phone.",
        "This helper could not start native lyric timing right now.",
      );
      return;
    case "delay-down":
    case "delay-up":
    case "delay-reset":
    case "mic-gain-down":
    case "mic-gain-up":
    case "music-bed-down":
    case "music-bed-up":
      dispatchNativeMarucastCommand(
        action,
        "Marucast controls updated on this helper.",
        "This helper could not apply that Marucast adjustment right now.",
      );
      return;
    case "tv-volume-down":
    case "tv-volume-up":
      void dispatchHelperReceiverCommand(action);
      return;
    default:
      return;
  }
}

function buildLauncherIconCallbackUrl(
  serverOrigin: string | null,
  target: string | null | undefined,
  nextState: string,
) {
  const openUrl = buildHelperSiteUrl(serverOrigin, target || "/helper");

  try {
    const parsed = new URL(openUrl);
    parsed.searchParams.set("helperLauncherIcon", nextState);
    return parsed.toString();
  } catch {
    return openUrl;
  }
}

function getLauncherIconState() {
  try {
    if (typeof window.HelperNativeBridge?.getLauncherIconState === "function") {
      return window.HelperNativeBridge.getLauncherIconState().trim();
    }
  } catch {
    // Ignore bridge failures and fall through.
  }

  return "";
}

function toggleLauncherIconAndGetState() {
  try {
    if (
      typeof window.HelperNativeBridge?.toggleLauncherIconAndGetState ===
      "function"
    ) {
      return window.HelperNativeBridge.toggleLauncherIconAndGetState().trim();
    }
  } catch {
    // Ignore bridge failures and fall through.
  }

  return "";
}

function ensureNativeLastFmDetectorScheduled() {
  try {
    window.HelperNativeBridge?.ensureLastFmDetectorScheduled?.();
  } catch {
    // Ignore native bridge failures.
  }
}

function buildOpenUrl(target: string | null | undefined, serverOrigin: string | null) {
  if (!serverOrigin) {
    return null;
  }

  const trimmedTarget = target?.trim();
  if (!trimmedTarget) {
    return serverOrigin;
  }

  try {
    const parsed = new URL(trimmedTarget, serverOrigin);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
      return serverOrigin;
    }
    return parsed.toString();
  } catch {
    return serverOrigin;
  }
}

async function closeHelperApp() {
  stopMarucastStatusPolling();

  try {
    await CapacitorApp.minimizeApp();
    return;
  } catch {
    // Fall through.
  }

  try {
    await CapacitorApp.exitApp();
  } catch {
    // Ignore close failures.
  }
}

async function registerNativePushToken(): Promise<{
  permission: NativePushPermission;
  token: string | null;
}> {
  const currentPermissions = await PushNotifications.checkPermissions();
  let permission = currentPermissions.receive as NativePushPermission;

  if (permission === "prompt") {
    const nextPermissions = await PushNotifications.requestPermissions();
    permission = nextPermissions.receive as NativePushPermission;
  }

  if (permission !== "granted") {
    return {
      permission,
      token: null,
    };
  }

  return await new Promise((resolve, reject) => {
    let settled = false;
    const listenerHandles: Array<Promise<{ remove: () => Promise<void> }>> = [];

    const finish = (callback: () => void) => {
      if (settled) {
        return;
      }

      settled = true;
      for (const handle of listenerHandles) {
        void handle.then((listener) => listener.remove()).catch(() => { });
      }
      callback();
    };

    listenerHandles.push(
      PushNotifications.addListener("registration", (token) => {
        finish(() =>
          resolve({
            permission,
            token: token.value,
          }),
        );
      }),
    );

    listenerHandles.push(
      PushNotifications.addListener("registrationError", (error) => {
        finish(() =>
          reject(
            new Error(error.error?.trim() || "Native push registration failed."),
          ),
        );
      }),
    );

    PushNotifications.register().catch((error) => {
      finish(() =>
        reject(
          error instanceof Error
            ? error
            : new Error("Native push registration failed."),
        ),
      );
    });
  });
}

async function completeLinkFlow(rawUrl: string) {
  if (isLinking) {
    return;
  }

  const parsed = parseHelperUrl(rawUrl);
  const action = parsed?.action ?? "";
  const token = parsed?.token ?? "";
  const serverOrigin = persistServerOrigin(parsed?.siteOrigin);

  if (token && token === lastProcessedLinkToken) {
    return;
  }

  if (action === "toggle_launcher_icon") {
    const nextState = toggleLauncherIconAndGetState();
    const callbackUrl = buildLauncherIconCallbackUrl(
      serverOrigin,
      parsed?.target,
      nextState === "shown" ? "shown" : "hidden",
    );

    setViewState({
      title: "Helper icon updated",
      status:
        nextState === "shown"
          ? "The helper app icon is visible now."
          : "The helper app icon is hidden now.",
      detail:
        "Returning to Maru so you can keep managing helper settings there.",
      openUrl: callbackUrl,
      closeLabel: "Close Helper",
    });

    if (openHelperLink(callbackUrl)) {
      window.setTimeout(() => {
        void closeHelperApp();
      }, 160);
      return;
    }

    return;
  }

  if (!token && action) {
    const handledMarucastAction = handleMarucastAction({
      action,
      serverOrigin,
      target: parsed?.target || "/marucast",
    });

    if (handledMarucastAction) {
      return;
    }
  }

  if (!token) {
    if (helperTargetWantsMarucastPanel(parsed?.target)) {
      activateMarucastPanel({
        openUrl: buildHelperSiteUrl(serverOrigin, "/marucast"),
        receiverToken: readHelperTargetReceiverToken(parsed?.target),
        serverOrigin,
      });
      marucastPanelError = "";
      marucastPanelMessage =
        "Control this phone's Marucast broadcast here.";
      renderMarucastPanel();
      return;
    }

    const openUrl = buildHelperSiteUrl(serverOrigin, parsed?.target || "/helper");
    setViewState({
      title: "Opening Maru",
      status: "This helper app only exists for Android notifications.",
      detail:
        "Sending you back to the Maru website now.",
      openUrl,
      closeLabel: "Close Helper",
    });

    if (openHelperLink(openUrl)) {
      window.setTimeout(() => {
        void closeHelperApp();
      }, 160);
    }

    return;
  }

  if (!serverOrigin) {
    setViewState({
      title: "Missing site link",
      status: "This helper launch did not include a valid Maru site origin.",
      detail: "Go back to the Maru website and press Connect Helper App again.",
      closeLabel: "Close Helper",
    });
    return;
  }

  lastProcessedLinkToken = token;
  isLinking = true;
  setViewState({
    title: "Connecting helper",
    status: "Allow Android notifications if it asks.",
    detail: "The helper is registering this device so Maru can send background pushes later.",
    closeLabel: "Close Helper",
  });

  try {
    const registration = await registerNativePushToken();
    const appInfo = await CapacitorApp.getInfo().catch(() => null);
    const appVersion =
      typeof appInfo?.version === "string" && appInfo.version.trim()
        ? appInfo.version.trim()
        : HELPER_APP_VERSION;

    if (registration.permission !== "granted" || !registration.token) {
      setViewState({
        title: "Notifications blocked",
        status: "Android notification access is still needed for the helper.",
        detail:
          "Allow notifications for Maru Helper, then start the connection again from the website.",
        openUrl: serverOrigin,
        closeLabel: "Close Helper",
      });
      return;
    }

    const response = await CapacitorHttp.post({
      url: `${serverOrigin}/api/auth`,
      headers: {
        "Content-Type": "application/json",
      },
      data: {
        route: "companion/link-complete",
        installationId: getHelperInstallationId(),
        platform: "android",
        pushToken: registration.token,
        token,
        appVersion,
      },
      responseType: "json",
    });

    const data =
      response.data && typeof response.data === "object"
        ? (response.data as { error?: string; success?: boolean })
        : null;

    if (response.status < 200 || response.status >= 300 || !data?.success) {
      setViewState({
        title: "Helper not linked",
        status: data?.error || "Maru could not finish linking this helper yet.",
        detail: "Go back to the website and try Connect Helper App again in a moment.",
        openUrl: serverOrigin,
        closeLabel: "Close Helper",
      });
      return;
    }

    getHelperInstallationId();
    persistServerOrigin(serverOrigin);
    ensureNativeLastFmDetectorScheduled();

    setViewState({
      title: "Helper connected",
      status: "Android push is ready for this device.",
      detail:
        "You can go back to Maru now. The helper can keep checking for elevated Last.fm changes in the background too.",
      openUrl: serverOrigin,
      closeLabel: "Close Helper",
    });
  } catch (error) {
    const message =
      error instanceof Error && error.message.trim()
        ? error.message.trim()
        : "The helper could not talk to Maru right now.";

    setViewState({
      title: "Connection failed",
      status: message,
      detail:
        "If you launched this from a local PC site, make sure the phone can actually reach that site. Otherwise retry from the deployed Maru website.",
      openUrl: serverOrigin,
      closeLabel: "Close Helper",
    });
  } finally {
    isLinking = false;
  }
}

async function initializeHelper() {
  document.body.dataset.platform = Capacitor.getPlatform();
  getHelperInstallationId();
  persistServerOrigin(null);
  ensureNativeLastFmDetectorScheduled();

  helperContentElement?.addEventListener("click", (event) => {
    const target = event.target as HTMLElement | null;
    if (!target) {
      return;
    }

    const closeTrigger = target.closest<HTMLElement>("#helper-close-button");
    if (closeTrigger) {
      event.preventDefault();
      void closeHelperApp();
      return;
    }

    const openTrigger = target.closest<HTMLAnchorElement>("#helper-open-link");
    if (openTrigger) {
      const href = openTrigger.getAttribute("href");
      if (!href) {
        return;
      }
      event.preventDefault();
      openHelperLink(href);
      return;
    }

    const actionTrigger = target.closest<HTMLElement>("[data-helper-action]");
    if (!actionTrigger) {
      return;
    }

    event.preventDefault();
    handleMarucastPanelAction(
      actionTrigger.getAttribute("data-helper-action"),
    );
  });

  window.setInterval(() => {
    const browserState = getMarucastBrowserState();
    const helperStatus = getMarucastStatus();

    if (browserState.mode === "webrtc_sender") {
      updateMarucastBrowserSenderMetadata(
        buildMarucastSenderMetadata(helperStatus),
      );
    }

    const relayRunning = isMarucastRelayRunning(helperStatus);
    const browserSafeReceiverConnected =
      browserState.mode === "webrtc_sender" &&
      browserState.connectionState === "connected";
    const directReceiverConnected = getActiveNetworkRelayClients(helperStatus) > 0;

    if (!relayRunning || browserSafeReceiverConnected || directReceiverConnected) {
      marucastIdleWithoutReceiverStartedAt = null;
      return;
    }

    if (marucastIdleWithoutReceiverStartedAt === null) {
      marucastIdleWithoutReceiverStartedAt = Date.now();
      return;
    }

    if (Date.now() - marucastIdleWithoutReceiverStartedAt < MARUCAST_IDLE_AUTO_STOP_MS) {
      return;
    }

    marucastIdleWithoutReceiverStartedAt = null;
    stopMarucastBrowserSession();
    try {
      window.HelperNativeBridge?.stopMarucastPlaybackRelay?.();
    } catch {
      // Ignore native stop failures and still show the timeout copy below.
    }
    stopMarucastStatusPolling();
    const openUrl = buildHelperSiteUrl(persistServerOrigin(null), "/marucast");
    activateMarucastPanel({
      openUrl,
      receiverToken: marucastPanelContext?.receiverToken ?? null,
      serverOrigin: marucastPanelContext?.serverOrigin ?? persistServerOrigin(null),
    });
    marucastPanelError = "";
    marucastPanelMessage =
      "No Marucast receiver stayed connected for 2 minutes, so the helper stopped broadcasting automatically.";
    renderMarucastPanel();
  }, 1000);

  setViewState({
    title: "Helper ready",
    status: "This helper only handles Android notifications.",
    detail:
      "Open the Maru website and press Connect Helper App whenever you want to register this device.",
    closeLabel: "Close Helper",
  });

  const launchUrl = await CapacitorApp.getLaunchUrl().catch(() => null);
  if (launchUrl?.url) {
    await completeLinkFlow(launchUrl.url);
    return;
  }

  const currentLauncherState = getLauncherIconState();
  if (currentLauncherState === "shown") {
    const openUrl = buildHelperSiteUrl(
      persistServerOrigin(null),
      "/helper",
    );

    setViewState({
      title: "Opening Maru",
      status: "The helper app icon is only a shortcut back to Maru.",
      detail: "Sending you to the helper setup page in the browser now.",
      openUrl,
      closeLabel: "Close Helper",
    });

    if (openHelperLink(openUrl)) {
      window.setTimeout(() => {
        void closeHelperApp();
      }, 160);
      return;
    }
  }

  await CapacitorApp.addListener("appUrlOpen", (event) => {
    void completeLinkFlow(event.url);
  });

  await PushNotifications.addListener("pushNotificationActionPerformed", (event) => {
    const storedOrigin = persistServerOrigin(null);
    const rawTarget =
      event.notification?.data &&
        typeof event.notification.data.url === "string"
        ? event.notification.data.url
        : "";
    const openUrl = buildOpenUrl(rawTarget, storedOrigin);

    setViewState({
      title: "Opening Maru",
      status: "The helper received your alert.",
      detail:
        "Sending you to the matching Maru page now.",
      openUrl,
      closeLabel: "Close Helper",
    });

    if (openHelperLink(openUrl)) {
      window.setTimeout(() => {
        void closeHelperApp();
      }, 160);
    }
  });
}

void initializeHelper();
