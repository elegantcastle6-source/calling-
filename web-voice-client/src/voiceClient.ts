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
  uid: number | null;
  tempToken?: string | null;
  tokenServerUrl?: string | null;
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
 * Publishes local microphone audio, subscribes to remote audio tracks, supports mute/unmute
 * and clean leave, and never creates or subscribes to video tracks.
 */
export class AgoraWebVoiceClient {
  private readonly client: IAgoraRTCClient;
  private readonly callbacks: VoiceClientCallbacks;
  private localAudioTrack: IMicrophoneAudioTrack | null = null;
  private remoteParticipants = new Map<UID, RemoteAudioParticipant>();
  private activeConfig: VoiceCallConfig | null = null;
  private joinedUid: UID | null = null;
  private isMuted = false;

  constructor(callbacks: VoiceClientCallbacks) {
    this.callbacks = callbacks;
    // Communication profile ("rtc") for two-way voice calling where all peers publish & subscribe audio.
    this.client = AgoraRTC.createClient({ mode: "rtc", codec: "vp8" });
    this.registerEvents();
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
      console.log("Remote user published:", user.uid, mediaType);

      // Strictly audio-only: ignore video publications.
      if (mediaType !== "audio") return;

      try {
        await this.client.subscribe(user, mediaType);

        if (mediaType === "audio" && user.audioTrack) {
          user.audioTrack.play();
          console.log("Remote audio is playing");
        }

        this.remoteParticipants.set(user.uid, { uid: user.uid, hasAudio: true });
        this.emitRemoteParticipants();
        this.callbacks.onLog(`Subscribed to remote audio from UID ${user.uid}.`, "info");
      } catch (err) {
        const reason = err instanceof Error ? err.message : String(err);
        console.error("Failed to subscribe to remote user:", user.uid, mediaType, err);
        this.callbacks.onLog(`Failed to subscribe to remote UID ${user.uid}: ${reason}`, "error");
      }
    });

    this.client.on("user-unpublished", (user: IAgoraRTCRemoteUser, mediaType: "audio" | "video") => {
      console.log("Remote user unpublished:", user.uid, mediaType);
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
      this.callbacks.onLog("RTC token privilege is about to expire; attempting renewal...", "warn");
      await this.renewTokenFromServer();
    });

    this.client.on("token-privilege-did-expire", async () => {
      this.callbacks.onLog("RTC token expired. Renewing from token server or generate a fresh token.", "error");
      await this.renewTokenFromServer();
    });
  }

  public async join(config: VoiceCallConfig): Promise<void> {
    const cleanAppId = config.appId.trim();
    const cleanChannel = config.channelName.trim();
    if (!cleanAppId) {
      throw new Error("Agora App ID is required.");
    }
    if (!cleanChannel) {
      throw new Error("Channel name is required.");
    }
    if (
      config.uid !== null &&
      (!Number.isInteger(config.uid) || config.uid < 0 || config.uid > 0xffffffff)
    ) {
      throw new Error("RTC UID must be an unsigned 32-bit integer (or leave blank to auto-assign).");
    }

    const joinUid: number | null = config.uid === 0 ? null : config.uid;

    this.activeConfig = {
      ...config,
      appId: cleanAppId,
      channelName: cleanChannel,
      uid: joinUid
    };
    this.callbacks.onStateChange("joining", cleanChannel, null);

    try {
      const resolvedToken = await this.resolveJoinToken(this.activeConfig);
      const uid = await this.client.join(cleanAppId, cleanChannel, resolvedToken, joinUid);
      console.log("Local user joined:", uid);
      this.joinedUid = uid;
      this.callbacks.onLog(`Joined channel "${cleanChannel}" as UID ${uid}.`, "info");

      // Audio-only track: never request camera or create video tracks.
      this.localAudioTrack = await AgoraRTC.createMicrophoneAudioTrack();
      this.isMuted = false;
      this.callbacks.onMuteChange(false);

      await this.client.publish([this.localAudioTrack]);
      this.callbacks.onLog("Published local microphone audio track.", "info");
      this.callbacks.onStateChange("connected", cleanChannel, this.joinedUid);
    } catch (err) {
      await this.cleanupTracksAndReset();
      const reason = err instanceof Error ? err.message : String(err);
      this.callbacks.onLog(`Join failed: ${reason}`, "error");
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
    this.remoteParticipants.clear();
    this.emitRemoteParticipants();
    this.callbacks.onNetworkQualityChange?.(mapNetworkQuality(0, 0));
    this.callbacks.onMuteChange(false);
    this.callbacks.onStateChange("idle", "", null);
  }

  private async resolveJoinToken(config: VoiceCallConfig): Promise<string | null> {
    const manualToken = config.tempToken?.trim();
    if (manualToken) {
      return manualToken;
    }
    const serverUrl = config.tokenServerUrl?.trim();
    if (serverUrl) {
      this.callbacks.onLog("Requesting publisher RTC token from token server...", "info");
      return this.fetchTokenFromServer(serverUrl, config.channelName, config.uid ?? 0);
    }
    return null;
  }

  private async renewTokenFromServer(): Promise<void> {
    const config = this.activeConfig;
    const serverUrl = config?.tokenServerUrl?.trim();
    if (!config || !serverUrl) {
      this.callbacks.onLog(
        "No token server URL configured. Generate a new temporary token in Agora Console or connect a token server.",
        "warn"
      );
      return;
    }

    try {
      const renewalUid = typeof this.joinedUid === "number" ? this.joinedUid : (config.uid ?? 0);
      const freshToken = await this.fetchTokenFromServer(serverUrl, config.channelName, renewalUid);
      await this.client.renewToken(freshToken);
      this.callbacks.onLog("Renewed RTC token successfully.", "info");
    } catch (err) {
      const reason = err instanceof Error ? err.message : String(err);
      this.callbacks.onLog(`Token renewal failed: ${reason}`, "error");
    }
  }

  private async fetchTokenFromServer(
    baseUrl: string,
    channelName: string,
    uid: number
  ): Promise<string> {
    const url = new URL(baseUrl);
    url.searchParams.set("channelName", channelName);
    url.searchParams.set("channel", channelName);
    url.searchParams.set("uid", String(uid));
    url.searchParams.set("role", "publisher");

    const response = await fetch(url.toString(), { method: "GET" });
    if (!response.ok) {
      throw new Error(`Token server returned HTTP ${response.status}.`);
    }
    const bodyText = (await response.text()).trim();
    if (bodyText.startsWith("{")) {
      const parsed = JSON.parse(bodyText) as { rtcToken?: string; token?: string };
      const token = (parsed.rtcToken ?? parsed.token ?? "").trim();
      if (!token) {
        throw new Error("Token server response did not include rtcToken.");
      }
      return token;
    }
    if (!bodyText) {
      throw new Error("Token server returned an empty response.");
    }
    return bodyText;
  }

  private emitRemoteParticipants(): void {
    this.callbacks.onRemoteParticipantsChange(Array.from(this.remoteParticipants.values()));
  }
}
