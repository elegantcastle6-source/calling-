import "./styles.css";
import {
  AgoraWebVoiceClient,
  CallConnectionState,
  NetworkQualitySnapshot,
  RemoteAudioParticipant
} from "./voiceClient";
import type { UID } from "agora-rtc-sdk-ng";

function requireElement<T extends HTMLElement>(id: string): T {
  const el = document.getElementById(id);
  if (!el) {
    throw new Error(`Missing required element #${id}`);
  }
  return el as T;
}

const appIdInput = requireElement<HTMLInputElement>("app-id-input");
const channelInput = requireElement<HTMLInputElement>("channel-input");
const tokenServerInput = requireElement<HTMLInputElement>("token-server-input");
const errorBanner = requireElement<HTMLDivElement>("error-banner");
const errorText = requireElement<HTMLSpanElement>("error-text");

const joinButton = requireElement<HTMLButtonElement>("join-btn");
const muteButton = requireElement<HTMLButtonElement>("mute-btn");
const leaveButton = requireElement<HTMLButtonElement>("leave-btn");

const statusBadge = requireElement<HTMLSpanElement>("status-badge");
const signalIndicator = requireElement<HTMLSpanElement>("signal-indicator");
const signalLabel = requireElement<HTMLSpanElement>("signal-label");
const signalBars = Array.from(signalIndicator.querySelectorAll<HTMLSpanElement>(".signal-bar"));
const localSummary = requireElement<HTMLParagraphElement>("local-summary");
const remoteList = requireElement<HTMLUListElement>("remote-list");
const logList = requireElement<HTMLUListElement>("event-log");

const envAppId = (import.meta.env.VITE_AGORA_APP_ID ?? "").trim();
if (envAppId && !envAppId.startsWith("YOUR_")) {
  appIdInput.value = envAppId;
}
const envChannel = (import.meta.env.VITE_AGORA_CHANNEL_NAME ?? "").trim();
if (envChannel) {
  channelInput.value = envChannel;
}
const envTokenServer = (import.meta.env.VITE_AGORA_TOKEN_SERVER_URL ?? "").trim();
if (envTokenServer) {
  tokenServerInput.value = envTokenServer;
}

function showErrorBanner(message: string): void {
  errorText.textContent = message;
  errorBanner.hidden = false;
}

function clearErrorBanner(): void {
  errorText.textContent = "";
  errorBanner.hidden = true;
}

function appendLog(message: string, level: "info" | "warn" | "error" = "info"): void {
  const item = document.createElement("li");
  item.className = `log-item log-${level}`;
  const time = new Date().toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  });
  item.textContent = `[${time}] ${message}`;
  logList.prepend(item);
  while (logList.children.length > 30) {
    logList.removeChild(logList.lastElementChild!);
  }
}

function renderRemoteParticipants(participants: RemoteAudioParticipant[]): void {
  remoteList.innerHTML = "";
  if (participants.length === 0) {
    const empty = document.createElement("li");
    empty.className = "remote-empty";
    empty.textContent = "No remote audio participants yet. Join in a second browser tab.";
    remoteList.appendChild(empty);
    return;
  }

  for (const participant of participants) {
    const li = document.createElement("li");
    li.className = "remote-item";
    const statusText = participant.hasAudio ? "Publishing audio (playing)" : "Connected (audio muted)";
    li.textContent = `UID ${participant.uid} — ${statusText}`;
    remoteList.appendChild(li);
  }
}

function setInputsDisabled(disabled: boolean): void {
  appIdInput.disabled = disabled;
  channelInput.disabled = disabled;
  tokenServerInput.disabled = disabled;
}

function updateSignalUi(quality: NetworkQualitySnapshot): void {
  signalIndicator.className = `signal-pill signal-${quality.tone}`;
  signalIndicator.setAttribute("aria-label", `Connection stability: ${quality.label}`);
  signalIndicator.title = `Connection: ${quality.label} (Uplink: ${quality.uplink}, Downlink: ${quality.downlink})`;
  signalLabel.textContent = quality.label;
  signalBars.forEach((bar, index) => {
    bar.classList.toggle("signal-bar-active", index < quality.bars);
  });
}

function updateStateUi(state: CallConnectionState, activeChannel: string, activeUid: UID | null): void {
  statusBadge.className = `status-badge status-${state}`;
  signalIndicator.hidden = state !== "connected";
  if (state === "idle") {
    statusBadge.textContent = "Disconnected";
    localSummary.textContent = "Uses APP_ID, CHANNEL_NAME & TOKEN_SERVER from secrets. UID is auto-assigned via null.";
    setInputsDisabled(false);
    joinButton.disabled = false;
    muteButton.disabled = true;
    leaveButton.disabled = true;
  } else if (state === "joining") {
    statusBadge.textContent = "Joining…";
    localSummary.textContent = `Fetching token and connecting to "${activeChannel}"…`;
    setInputsDisabled(true);
    joinButton.disabled = true;
    muteButton.disabled = true;
    leaveButton.disabled = false;
  } else {
    statusBadge.textContent = "Connected (Audio Only)";
    localSummary.textContent = `In channel "${activeChannel}" as UID ${activeUid} · Communication profile`;
    setInputsDisabled(true);
    joinButton.disabled = true;
    muteButton.disabled = false;
    leaveButton.disabled = false;
  }
}

function updateMuteUi(muted: boolean): void {
  muteButton.textContent = muted ? "Unmute Microphone" : "Mute Microphone";
  muteButton.setAttribute("aria-pressed", String(muted));
  muteButton.classList.toggle("btn-muted", muted);
}

const voiceClient = new AgoraWebVoiceClient({
  onStateChange: updateStateUi,
  onMuteChange: updateMuteUi,
  onRemoteParticipantsChange: renderRemoteParticipants,
  onNetworkQualityChange: updateSignalUi,
  onError: showErrorBanner,
  onLog: appendLog
});

joinButton.addEventListener("click", async () => {
  clearErrorBanner();
  try {
    await voiceClient.join({
      appId: appIdInput.value,
      channelName: channelInput.value,
      tokenServerUrl: tokenServerInput.value
    });
  } catch (err) {
    const reason = err instanceof Error ? err.message : String(err);
    showErrorBanner(reason);
  }
});

muteButton.addEventListener("click", async () => {
  try {
    await voiceClient.toggleMute();
  } catch (err) {
    const reason = err instanceof Error ? err.message : String(err);
    showErrorBanner(`Mute toggle failed: ${reason}`);
    appendLog(`Mute toggle failed: ${reason}`, "error");
  }
});

leaveButton.addEventListener("click", async () => {
  try {
    await voiceClient.leave();
  } catch (err) {
    const reason = err instanceof Error ? err.message : String(err);
    showErrorBanner(`Leave error: ${reason}`);
    appendLog(`Leave error: ${reason}`, "error");
  }
});

window.addEventListener("beforeunload", () => {
  void voiceClient.leave();
});

renderRemoteParticipants([]);
updateStateUi("idle", "", null);
updateMuteUi(false);
