import AgoraRTC, {
  IAgoraRTCClient,
  IAgoraRTCRemoteUser,
  IMicrophoneAudioTrack,
  NetworkQuality,
  UID
} from "agora-rtc-sdk-ng";

export type CallConnectionState = "idle" | "joining" | "connected";

export interface RemoteAudioParticipant {
  uid: UID;
  hasAudio: boolean;
}

export interface VoiceCallConfig {
  appId: string;
  channelName: string;
  tokenServerUrl: string;
}

export interface NetworkQualitySnapshot {
  uplink: number;
  downlink: number;
  bars: number;
  label: string;
  tone: "unknown" | "good" | "fair" | "poor";
}

export interface VoiceClientCallbacks {
  onStateChange: (state: CallConnectionState, activeChannel: string, activeUid: UID | null) => void;
  onMuteChange: (muted: boolean) => void;
  onRemoteParticipantsChange: (participants: RemoteAudioParticipant[]) => void;
  onNetworkQualityChange?: (quality: NetworkQualitySnapshot) => void;
  onError?: (message: string) => void;
  onLog: (message: string, level?: "info" | "warn" | "error") => void;
}

export function mapNetworkQuality(uplink: number, downlink: number): NetworkQualitySnapshot {
  const effective =
    uplink <= 0 && downlink <= 0
      ? 0
      : uplink <= 0
        ? downlink
        : downlink <= 0
          ? uplink
          : Math.max(uplink, downlink);

  switch (effective) {
    case 1:
      return { uplink, downlink, bars: 4, label: "Excellent", tone: "good" };
    case 2:
      return { uplink, downlink, bars: 3, label: "Good", tone: "good" };
    case 3:
      return { uplink, downlink, bars: 2, label: "Fair", tone: "fair" };
    case 4:
    case 5:
      return { uplink, downlink, bars: 1, label: "Weak", tone: "poor" };
    case 6:
      return { uplink, downlink, bars: 0, label: "Unstable", tone: "poor" };
    default:
      return { uplink, downlink, bars: 0, label: "Checking…", tone: "unknown" };
  }
}

/**
 * Audio-only Agora RTC Web SDK 4.x client using the Communication profile (`mode: "rtc"`).
 * Fetches tokens strictly from `${TOKEN_SERVER}?channel=${CHANNEL_NAME}` and joins with `uid = null`.
 */
export class AgoraWebVoiceClient {
  private readonly client: IAgoraRTCClient;
  private readonly callbacks: VoiceClientCallbacks;
  private localAudioTrack: IMicrophoneAudioTrack | null = null;
  private remoteParticipants = new Map<UID, RemoteAudioParticipant>();
  private activeConfig: VoiceCallConfig | null = null;
  private joinedUid: UID | null = null;
  private isMuted = false;
  private isRejoining = false;

  constructor(callbacks: VoiceClientCallbacks) {
    this.callbacks = callbacks;
    this.client = AgoraRTC.createClient({ mode: "rtc", codec: "vp8" });
    this.registerEvents();
  }

  private reportError(message: string): void {
    this.callbacks.onLog(message, "error");
    this.callbacks.onError?.(message);
  }

  private registerEvents(): void {
    AgoraRTC.onAutoplayFailed = () => {
      console.warn("Remote audio autoplay blocked by browser policy.");
      this.callbacks.onLog(
        "Browser blocked remote audio autoplay. Click anywhere on the page and rejoin or interact to enable audio.",
        "warn"
      );
    };

    this.client.on("user-published", async (user: IAgoraRTCRemoteUser, mediaType: "audio" | "video") => {
      if (mediaType !== "audio") return;

      try {
        await this.client.subscribe(user, mediaType);
        if (user.audioTrack) {
          user.audioTrack.play();
        }
        this.remoteParticipants.set(user.uid, { uid: user.uid, hasAudio: true });
        this.emitRemoteParticipants();
        this.callbacks.onLog(`Subscribed to remote audio from UID ${user.uid}.`, "info");
      } catch (err) {
        const reason = err instanceof Error ? err.message : String(err);
        this.reportError(`Failed to subscribe to remote UID ${user.uid}: ${reason}`);
      }
    });

    this.client.on("user-unpublished", (user: IAgoraRTCRemoteUser, mediaType: "audio" | "video") => {
      if (mediaType !== "audio") return;
      user.audioTrack?.stop();
      if (this.remoteParticipants.has(user.uid)) {
        this.remoteParticipants.set(user.uid, { uid: user.uid, hasAudio: false });
        this.emitRemoteParticipants();
      }
      this.callbacks.onLog(`Remote UID ${user.uid} stopped publishing audio.`, "info");
    });

    this.client.on("user-left", (user: IAgoraRTCRemoteUser) => {
      user.audioTrack?.stop();
      this.remoteParticipants.delete(user.uid);
      this.emitRemoteParticipants();
      this.callbacks.onLog(`Remote UID ${user.uid} left the channel.`, "info");
    });

    this.client.on("network-quality", (stats: NetworkQuality) => {
      const snapshot = mapNetworkQuality(stats.uplinkNetworkQuality, stats.downlinkNetworkQuality);
      this.callbacks.onNetworkQualityChange?.(snapshot);
    });

    this.client.on("token-privilege-will-expire", async () => {
      this.callbacks.onLog("token-privilege-will-expire fired; fetching fresh token...", "warn");
      const config = this.activeConfig;
      if (!config) return;
      try {
        const newToken = await this.fetchTokenFromServer(config.tokenServerUrl, config.channelName);
        await this.client.renewToken(newToken);
        this.callbacks.onLog("Renewed RTC token via client.renewToken(newToken).", "info");
      } catch (err) {
        const reason = err instanceof Error ? err.message : String(err);
        this.reportError(`Token renewal failed (will-expire): ${reason}`);
      }
    });

    this.client.on("token-privilege-did-expire", async () => {
      if (this.isRejoining) return;
      const config = this.activeConfig;
      if (!config) return;
      this.isRejoining = true;
      this.callbacks.onLog("token-privilege-did-expire fired; fetching new token and rejoining...", "warn");
      this.callbacks.onStateChange("joining", config.channelName, this.joinedUid);

      try {
        const newToken = await this.fetchTokenFromServer(config.tokenServerUrl, config.channelName);
        if (this.client.connectionState !== "DISCONNECTED") {
          try {
            await this.client.leave();
          } catch {
            // Ignore leave errors if already disconnected.
          }
        }
        this.remoteParticipants.clear();
        this.emitRemoteParticipants();

        const rejoinedUid = await this.client.join(config.appId, config.channelName, newToken, null);
        this.joinedUid = rejoinedUid;

        if (!this.localAudioTrack) {
          this.localAudioTrack = await AgoraRTC.createMicrophoneAudioTrack();
          await this.localAudioTrack.setMuted(this.isMuted);
        }
        await this.client.publish([this.localAudioTrack]);
        this.callbacks.onStateChange("connected", config.channelName, this.joinedUid);
        this.callbacks.onLog(`Rejoined channel "${config.channelName}" as UID ${rejoinedUid}.`, "info");
      } catch (err) {
        const reason = err instanceof Error ? err.message : String(err);
        this.reportError(`Token expired rejoin failed (did-expire): ${reason}`);
        await this.cleanupTracksAndReset();
      } finally {
        this.isRejoining = false;
      }
    });
  }

  public async join(config: VoiceCallConfig): Promise<void> {
    const cleanAppId = config.appId.trim();
    const cleanChannel = config.channelName.trim();
    const cleanTokenServer = config.tokenServerUrl.trim();
    if (!cleanAppId) {
      const msg = "Missing APP_ID secret.";
      this.reportError(msg);
      throw new Error(msg);
    }
    if (!cleanChannel) {
      const msg = "Missing CHANNEL_NAME secret.";
      this.reportError(msg);
      throw new Error(msg);
    }
    if (!cleanTokenServer) {
      const msg = "Missing TOKEN_SERVER secret.";
      this.reportError(msg);
      throw new Error(msg);
    }

    this.activeConfig = {
      appId: cleanAppId,
      channelName: cleanChannel,
      tokenServerUrl: cleanTokenServer
    };
    this.callbacks.onStateChange("joining", cleanChannel, null);

    try {
      const token = await this.fetchTokenFromServer(cleanTokenServer, cleanChannel);
      const uid = await this.client.join(cleanAppId, cleanChannel, token, null);
      this.joinedUid = uid;
      this.callbacks.onLog(`Joined channel "${cleanChannel}" as UID ${uid}.`, "info");

      this.localAudioTrack = await AgoraRTC.createMicrophoneAudioTrack();
      this.isMuted = false;
      this.callbacks.onMuteChange(false);

      await this.client.publish([this.localAudioTrack]);
      this.callbacks.onLog("Published local microphone audio track.", "info");
      this.callbacks.onStateChange("connected", cleanChannel, this.joinedUid);
    } catch (err) {
      await this.cleanupTracksAndReset();
      const reason = err instanceof Error ? err.message : String(err);
      this.reportError(`Join failed: ${reason}`);
      throw err;
    }
  }

  public async toggleMute(): Promise<boolean> {
    if (!this.localAudioTrack) {
      return this.isMuted;
    }
    const nextMuted = !this.isMuted;
    await this.localAudioTrack.setMuted(nextMuted);
    this.isMuted = nextMuted;
    this.callbacks.onMuteChange(nextMuted);
    this.callbacks.onLog(nextMuted ? "Microphone muted." : "Microphone unmuted.", "info");
    return nextMuted;
  }

  public async leave(): Promise<void> {
    try {
      if (this.localAudioTrack) {
        this.localAudioTrack.stop();
        this.localAudioTrack.close();
        this.localAudioTrack = null;
      }
      await this.client.leave();
      this.callbacks.onLog("Left channel cleanly.", "info");
    } finally {
      this.activeConfig = null;
      this.joinedUid = null;
      this.isMuted = false;
      this.isRejoining = false;
      this.remoteParticipants.clear();
      this.emitRemoteParticipants();
      this.callbacks.onNetworkQualityChange?.(mapNetworkQuality(0, 0));
      this.callbacks.onMuteChange(false);
      this.callbacks.onStateChange("idle", "", null);
    }
  }

  private async cleanupTracksAndReset(): Promise<void> {
    if (this.localAudioTrack) {
      this.localAudioTrack.stop();
      this.localAudioTrack.close();
      this.localAudioTrack = null;
    }
    try {
      await this.client.leave();
    } catch {
      // Ignore cleanup errors if join never completed.
    }
    this.activeConfig = null;
    this.joinedUid = null;
    this.isMuted = false;
    this.isRejoining = false;
    this.remoteParticipants.clear();
    this.emitRemoteParticipants();
    this.callbacks.onNetworkQualityChange?.(mapNetworkQuality(0, 0));
    this.callbacks.onMuteChange(false);
    this.callbacks.onStateChange("idle", "", null);
  }

  private async fetchTokenFromServer(baseUrl: string, channelName: string): Promise<string> {
    const cleanServer = baseUrl.trim();
    const cleanChannel = channelName.trim();
    const separator = cleanServer.includes("?") ? "&" : "?";
    const requestUrl = `${cleanServer}${separator}channel=${encodeURIComponent(cleanChannel)}`;

    let response: Response;
    try {
      response = await fetch(requestUrl, { method: "GET", cache: "no-store" });
    } catch (networkErr) {
      const msg = networkErr instanceof Error ? networkErr.message : String(networkErr);
      throw new Error(`Fetch failed (${requestUrl}): ${msg}`);
    }

    const bodyText = await response.text();
    let parsed: { token?: unknown; error?: unknown; message?: unknown };
    try {
      parsed = JSON.parse(bodyText) as { token?: unknown; error?: unknown; message?: unknown };
    } catch {
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${bodyText.trim() || response.statusText}`);
      }
      throw new Error(`Invalid JSON from token server: ${bodyText.slice(0, 160) || "empty response"}`);
    }

    if (!response.ok) {
      const errMsg =
        typeof parsed.error === "string"
          ? parsed.error
          : typeof parsed.message === "string"
            ? parsed.message
            : `Token server returned HTTP ${response.status}.`;
      throw new Error(errMsg);
    }

    const token = typeof parsed.token === "string" ? parsed.token.trim() : "";
    if (!token) {
      throw new Error("Token server response returned an empty token.");
    }
    return token;
  }

  private emitRemoteParticipants(): void {
    this.callbacks.onRemoteParticipantsChange(Array.from(this.remoteParticipants.values()));
  }
}
