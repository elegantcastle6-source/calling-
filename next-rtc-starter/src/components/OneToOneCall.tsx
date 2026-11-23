"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import type {
  IAgoraRTCClient,
  IAgoraRTCRemoteUser,
  ICameraVideoTrack,
  IMicrophoneAudioTrack,
  NetworkQuality,
  UID
} from "agora-rtc-sdk-ng";

type CallStatus = "idle" | "joining" | "connected";

interface RemotePeerState {
  uid: UID;
  hasVideo: boolean;
  hasAudio: boolean;
}

interface LogEntry {
  id: number;
  time: string;
  level: "info" | "warn" | "error";
  message: string;
}

interface TokenApiResponse {
  token?: string | null;
  rtcToken?: string | null;
  appId?: string;
  mode?: "secured" | "testing";
  error?: string;
}

interface SignalQualityUi {
  bars: number;
  label: string;
  tone: "unknown" | "good" | "fair" | "poor";
  uplink: number;
  downlink: number;
}

const UNKNOWN_SIGNAL: SignalQualityUi = {
  bars: 0,
  label: "Checking…",
  tone: "unknown",
  uplink: 0,
  downlink: 0
};

function mapAgoraNetworkQuality(uplink: number, downlink: number): SignalQualityUi {
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
      return { bars: 4, label: "Excellent", tone: "good", uplink, downlink };
    case 2:
      return { bars: 3, label: "Good", tone: "good", uplink, downlink };
    case 3:
      return { bars: 2, label: "Fair", tone: "fair", uplink, downlink };
    case 4:
    case 5:
      return { bars: 1, label: "Weak", tone: "poor", uplink, downlink };
    case 6:
      return { bars: 0, label: "Unstable", tone: "poor", uplink, downlink };
    default:
      return { bars: 0, label: "Checking…", tone: "unknown", uplink, downlink };
  }
}

function SignalStrengthIcon({ signal }: { signal: SignalQualityUi }) {
  const heights = [35, 56, 78, 100];
  return (
    <span
      className={`signal-pill signal-${signal.tone}`}
      role="status"
      aria-label={`Connection stability: ${signal.label}`}
      title={`Connection: ${signal.label} (Uplink: ${signal.uplink}, Downlink: ${signal.downlink})`}
    >
      <span className="signal-bars" aria-hidden="true">
        {heights.map((pct, index) => (
          <span
            key={pct}
            className={`signal-bar ${index < signal.bars ? "signal-bar-active" : ""}`}
            style={{ height: `${pct}%` }}
          />
        ))}
      </span>
      <span>{signal.label}</span>
    </span>
  );
}

const DEFAULT_CHANNEL =
  (process.env.NEXT_PUBLIC_DEFAULT_CHANNEL ?? "").trim() || "justchat_1on1_demo";

export default function OneToOneCall() {
  const [channelName, setChannelName] = useState(DEFAULT_CHANNEL);
  const [uidInput, setUidInput] = useState("");
  const [tempTokenOverride, setTempTokenOverride] = useState("");

  const [status, setStatus] = useState<CallStatus>("idle");
  const [joinedUid, setJoinedUid] = useState<UID | null>(null);
  const [micMuted, setMicMuted] = useState(false);
  const [cameraOff, setCameraOff] = useState(false);
  const [hasLocalCamera, setHasLocalCamera] = useState(false);
  const [autoplayBlocked, setAutoplayBlocked] = useState(false);
  const [copiedLink, setCopiedLink] = useState(false);
  const [signalQuality, setSignalQuality] = useState<SignalQualityUi>(UNKNOWN_SIGNAL);
  const [remotePeers, setRemotePeers] = useState<RemotePeerState[]>([]);
  const [logs, setLogs] = useState<LogEntry[]>([]);

  const clientRef = useRef<IAgoraRTCClient | null>(null);
  const localAudioRef = useRef<IMicrophoneAudioTrack | null>(null);
  const localVideoRef = useRef<ICameraVideoTrack | null>(null);
  const remoteUsersRef = useRef<Map<string, IAgoraRTCRemoteUser>>(new Map());

  const localPlayerRef = useRef<HTMLDivElement | null>(null);
  const remoteContainerRef = useRef<HTMLDivElement | null>(null);
  const activeChannelRef = useRef<string>("");
  const activeUidRef = useRef<number>(0);
  const logSeqRef = useRef<number>(1);

  const addLog = useCallback((message: string, level: "info" | "warn" | "error" = "info") => {
    const time = new Date().toLocaleTimeString([], {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit"
    });
    const entry: LogEntry = {
      id: logSeqRef.current++,
      time,
      level,
      message
    };
    setLogs((prev) => [entry, ...prev].slice(0, 30));
  }, []);

  // Read ?channel= when shared via invite link.
  useEffect(() => {
    const urlChannel = new URLSearchParams(window.location.search).get("channel")?.trim();
    if (urlChannel) {
      setChannelName(urlChannel);
    }
  }, []);

  const fetchServerToken = useCallback(
    async (channel: string, uid: number): Promise<TokenApiResponse> => {
      const configuredEndpoint = (process.env.NEXT_PUBLIC_AGORA_TOKEN_SERVER_URL ?? "").trim();
      const baseEndpoint =
        configuredEndpoint && !configuredEndpoint.startsWith("OPTIONAL_") && !configuredEndpoint.startsWith("YOUR_")
          ? configuredEndpoint
          : "/api/rtc-token";
      const params = new URLSearchParams({
        channelName: channel,
        channel,
        uid: String(uid)
      });
      const separator = baseEndpoint.includes("?") ? "&" : "?";
      const res = await fetch(`${baseEndpoint}${separator}${params.toString()}`, {
        cache: "no-store"
      });
      const payload = (await res.json()) as TokenApiResponse;
      if (!res.ok) {
        throw new Error(payload.error || `Token route returned HTTP ${res.status}`);
      }
      const normalizedToken = payload.token ?? payload.rtcToken ?? null;
      return {
        ...payload,
        token: normalizedToken
      };
    },
    []
  );

  const cleanupCall = useCallback(
    async (logLeave: boolean) => {
      try {
        if (localAudioRef.current) {
          localAudioRef.current.stop();
          localAudioRef.current.close();
          localAudioRef.current = null;
        }
        if (localVideoRef.current) {
          localVideoRef.current.stop();
          localVideoRef.current.close();
          localVideoRef.current = null;
        }
        if (clientRef.current) {
          await clientRef.current.leave();
          clientRef.current.removeAllListeners();
          clientRef.current = null;
        }
        if (logLeave) {
          addLog("Left the 1-to-1 channel cleanly.", "info");
        }
      } catch (err) {
        const reason = err instanceof Error ? err.message : String(err);
        addLog(`Cleanup warning: ${reason}`, "warn");
      } finally {
        remoteUsersRef.current.clear();
        remoteContainerRef.current?.replaceChildren();
        activeChannelRef.current = "";
        activeUidRef.current = 0;
        setRemotePeers([]);
        setJoinedUid(null);
        setMicMuted(false);
        setCameraOff(false);
        setHasLocalCamera(false);
        setAutoplayBlocked(false);
        setSignalQuality(UNKNOWN_SIGNAL);
        setStatus("idle");
      }
    },
    [addLog]
  );

  useEffect(() => {
    const handleUnload = () => {
      void cleanupCall(false);
    };
    window.addEventListener("beforeunload", handleUnload);
    return () => {
      window.removeEventListener("beforeunload", handleUnload);
      void cleanupCall(false);
    };
  }, [cleanupCall]);

  const ensureRemotePlayerElement = useCallback((uid: UID): HTMLElement | null => {
    const elementId = `remote-${uid}`;
    let el = document.getElementById(elementId);
    if (!el) {
      const container = remoteContainerRef.current ?? document.getElementById("remote-container");
      if (!container) return null;
      el = document.createElement("div");
      el.id = elementId;
      el.className = "remote-player-slot";
      el.style.width = "100%";
      el.style.height = "100%";
      el.style.minHeight = "300px";
      container.appendChild(el);
    }
    return el;
  }, []);

  const removeRemotePlayerElement = useCallback((uid: UID) => {
    document.getElementById(`remote-${uid}`)?.remove();
  }, []);

  const handleCopyInviteLink = async () => {
    const cleanChannel = channelName.trim() || DEFAULT_CHANNEL;
    const url = new URL(window.location.href);
    url.searchParams.set("channel", cleanChannel);
    try {
      await navigator.clipboard.writeText(url.toString());
      setCopiedLink(true);
      addLog(`Copied 2nd-participant link for channel "${cleanChannel}".`, "info");
      window.setTimeout(() => setCopiedLink(false), 2500);
    } catch {
      addLog(`Open ${url.toString()} in a second browser tab or device.`, "info");
    }
  };

  const handleResumeAutoplay = () => {
    remoteUsersRef.current.forEach((remoteUser) => {
      if (remoteUser.audioTrack) {
        remoteUser.audioTrack.play();
      }
      if (remoteUser.videoTrack) {
        const el = ensureRemotePlayerElement(remoteUser.uid);
        if (el) {
          remoteUser.videoTrack.play(el);
        }
      }
    });
    setAutoplayBlocked(false);
    addLog("Resumed remote media playback after browser autoplay prompt.", "info");
  };

  const handleJoin = async () => {
    const cleanChannel = channelName.trim();
    const rawUid = uidInput.trim();
    let joinUid: number | null = null;

    if (!cleanChannel) {
      addLog("Channel name is required.", "error");
      return;
    }
    if (rawUid.length > 0) {
      const parsedUid = Number(rawUid);
      if (!Number.isInteger(parsedUid) || parsedUid < 0 || parsedUid > 0xffffffff) {
        addLog("RTC UID must be an unsigned 32-bit integer (or leave blank to auto-assign).", "error");
        return;
      }
      joinUid = parsedUid === 0 ? null : parsedUid;
    }
    const tokenRequestUid = joinUid ?? 0;

    setStatus("joining");
    setAutoplayBlocked(false);
    try {
      const manualToken = tempTokenOverride.trim();
      let appId = (process.env.NEXT_PUBLIC_AGORA_APP_ID ?? "").trim();
      let resolvedToken: string | null = manualToken.length > 0 ? manualToken : null;

      if (!resolvedToken) {
        addLog("Requesting RTC token from token endpoint...", "info");
        const tokenResp = await fetchServerToken(cleanChannel, tokenRequestUid);
        resolvedToken = tokenResp.token ?? null;
        if (tokenResp.appId) {
          appId = tokenResp.appId;
        }
        addLog(
          resolvedToken
            ? "Received server-signed RTC token (RolePublisher)."
            : "App Certificate not set; joining in Testing Mode (no token).",
          "info"
        );
      }

      if (!appId || appId.startsWith("YOUR_")) {
        throw new Error("Configure NEXT_PUBLIC_AGORA_APP_ID in next-rtc-starter/.env.local.");
      }

      const { default: AgoraRTC } = await import("agora-rtc-sdk-ng");
      AgoraRTC.onAutoplayFailed = () => {
        setAutoplayBlocked(true);
        addLog("Browser blocked audio autoplay. Click 'Resume Remote Audio' to enable sound.", "warn");
      };

      const client = AgoraRTC.createClient({ mode: "rtc", codec: "vp8" });
      clientRef.current = client;
      activeChannelRef.current = cleanChannel;
      activeUidRef.current = tokenRequestUid;

      client.on("connection-state-change", (curState, prevState, reason) => {
        addLog(
          `Connection state: ${prevState} -> ${curState}${reason ? ` (${reason})` : ""}`,
          curState === "DISCONNECTED" ? "warn" : "info"
        );
      });

      client.on("network-quality", (stats: NetworkQuality) => {
        setSignalQuality(
          mapAgoraNetworkQuality(stats.uplinkNetworkQuality, stats.downlinkNetworkQuality)
        );
      });

      client.on("user-published", async (user: IAgoraRTCRemoteUser, mediaType: "audio" | "video") => {
        console.log("Remote user published:", user.uid, mediaType);
        try {
          await client.subscribe(user, mediaType);
          console.log("subscribed", user.uid, mediaType);
          remoteUsersRef.current.set(String(user.uid), user);

          if (mediaType === "video" && user.videoTrack) {
            const el = ensureRemotePlayerElement(user.uid);
            if (el) {
              user.videoTrack.play(el);
              console.log("Remote video is playing");
            }
            addLog(`Subscribed to remote video from UID ${user.uid}.`, "info");
          }
          if (mediaType === "audio" && user.audioTrack) {
            user.audioTrack.play();
            console.log("Remote audio is playing");
            addLog(`Subscribed to remote audio from UID ${user.uid}.`, "info");
          }

          setRemotePeers((prev) => {
            const existing = prev.find((p) => p.uid === user.uid);
            const updated: RemotePeerState = {
              uid: user.uid,
              hasVideo: mediaType === "video" ? true : (existing?.hasVideo ?? false),
              hasAudio: mediaType === "audio" ? true : (existing?.hasAudio ?? false)
            };
            return existing
              ? prev.map((p) => (p.uid === user.uid ? updated : p))
              : [...prev, updated];
          });
        } catch (err) {
          const reason = err instanceof Error ? err.message : String(err);
          console.error("Failed to subscribe to remote user:", user.uid, mediaType, err);
          addLog(`Failed to subscribe to remote UID ${user.uid} (${mediaType}): ${reason}`, "error");
        }
      });

      client.on("user-unpublished", (user: IAgoraRTCRemoteUser, mediaType: "audio" | "video") => {
        console.log("Remote user unpublished:", user.uid, mediaType);
        if (mediaType === "audio") {
          user.audioTrack?.stop();
        }
        if (mediaType === "video") {
          user.videoTrack?.stop();
          removeRemotePlayerElement(user.uid);
        }
        setRemotePeers((prev) =>
          prev.map((p) =>
            p.uid !== user.uid
              ? p
              : {
                  ...p,
                  hasVideo: mediaType === "video" ? false : p.hasVideo,
                  hasAudio: mediaType === "audio" ? false : p.hasAudio
                }
          )
        );
        addLog(`Remote UID ${user.uid} muted or stopped ${mediaType}.`, "info");
      });

      client.on("user-left", (user: IAgoraRTCRemoteUser) => {
        user.audioTrack?.stop();
        user.videoTrack?.stop();
        removeRemotePlayerElement(user.uid);
        remoteUsersRef.current.delete(String(user.uid));
        setRemotePeers((prev) => prev.filter((p) => p.uid !== user.uid));
        addLog(`Remote UID ${user.uid} left the channel.`, "info");
      });

      const renewFromServer = async (eventLabel: string) => {
        if (manualToken.length > 0) {
          addLog(`${eventLabel}: temporary token needs manual replacement or server signing.`, "warn");
          return;
        }
        try {
          const fresh = await fetchServerToken(activeChannelRef.current, activeUidRef.current);
          if (fresh.token) {
            await client.renewToken(fresh.token);
            addLog(`Renewed RTC token automatically (${eventLabel}).`, "info");
          }
        } catch (err) {
          const reason = err instanceof Error ? err.message : String(err);
          addLog(`Automatic token renewal failed (${eventLabel}): ${reason}`, "error");
        }
      };

      client.on("token-privilege-will-expire", () => {
        void renewFromServer("will-expire");
      });
      client.on("token-privilege-did-expire", () => {
        void renewFromServer("did-expire");
      });

      const uid = await client.join(appId, cleanChannel, resolvedToken, joinUid);
      console.log("Local user joined:", uid);
      if (typeof uid === "number") {
        activeUidRef.current = uid;
      }
      setJoinedUid(uid);
      addLog(`Joined channel "${cleanChannel}" as UID ${uid}.`, "info");

      let micTrack: IMicrophoneAudioTrack;
      let camTrack: ICameraVideoTrack | null = null;
      try {
        const [audioTrack, videoTrack] = await AgoraRTC.createMicrophoneAndCameraTracks();
        micTrack = audioTrack;
        camTrack = videoTrack;
      } catch (mediaErr) {
        const reason = mediaErr instanceof Error ? mediaErr.message : String(mediaErr);
        addLog(
          `Camera unavailable (${reason}). Falling back to microphone-only so this tab can still test audio and watch remote video.`,
          "warn"
        );
        micTrack = await AgoraRTC.createMicrophoneAudioTrack();
      }

      localAudioRef.current = micTrack;
      localVideoRef.current = camTrack;
      setHasLocalCamera(camTrack !== null);

      if (camTrack) {
        camTrack.play(localPlayerRef.current ?? "local-video");
      }

      await client.publish(camTrack ? [micTrack, camTrack] : [micTrack]);
      console.log(camTrack ? "published mic + camera" : "published mic (audio-only)");
      setMicMuted(false);
      setCameraOff(camTrack === null);
      setStatus("connected");
      addLog(
        camTrack
          ? "Published local microphone and camera tracks."
          : "Published local microphone track (no local camera available).",
        "info"
      );
    } catch (err) {
      const reason = err instanceof Error ? err.message : String(err);
      addLog(`Unable to join call: ${reason}`, "error");
      await cleanupCall(false);
    }
  };

  const handleToggleMic = async () => {
    const track = localAudioRef.current;
    if (!track) return;
    const nextMuted = !micMuted;
    await track.setMuted(nextMuted);
    setMicMuted(nextMuted);
    addLog(nextMuted ? "Microphone muted." : "Microphone unmuted.", "info");
  };

  const handleToggleCamera = async () => {
    const track = localVideoRef.current;
    if (!track) return;
    const nextOff = !cameraOff;
    await track.setMuted(nextOff);
    setCameraOff(nextOff);
    addLog(nextOff ? "Camera turned off." : "Camera turned on.", "info");
  };

  const inCall = status === "connected";
  const inputsLocked = status !== "idle";

  return (
    <main className="container">
      <header className="header">
        <div>
          <h1>Just Chat — 1-to-1 Audio &amp; Video (Next.js)</h1>
          <p className="subtitle">Agora Web SDK 4.x · Communication profile · Server-side token route</p>
        </div>
        <div className="header-status">
          {inCall && <SignalStrengthIcon signal={signalQuality} />}
          <span className={`badge badge-${status}`}>
            {status === "idle" ? "Disconnected" : status === "joining" ? "Joining…" : `Connected · UID ${joinedUid}`}
          </span>
        </div>
      </header>

      {autoplayBlocked && (
        <div className="banner-warn">
          <span>Your browser paused remote audio autoplay.</span>
          <button type="button" className="btn btn-warn" onClick={handleResumeAutoplay}>
            Resume Remote Audio
          </button>
        </div>
      )}

      <section className="card">
        <div className="form-grid">
          <label className="field">
            <span>Channel Name</span>
            <input
              type="text"
              value={channelName}
              onChange={(e) => setChannelName(e.target.value)}
              disabled={inputsLocked}
              placeholder="justchat_1on1_demo"
            />
          </label>
          <label className="field">
            <span>Optional Numeric RTC UID (leave blank to auto-assign)</span>
            <input
              type="number"
              min={0}
              step={1}
              value={uidInput}
              onChange={(e) => setUidInput(e.target.value)}
              disabled={inputsLocked}
              placeholder="Auto-assign (null)"
            />
          </label>
          <label className="field field-full">
            <span>Optional Console Temporary Token Override (leave blank to use /api/rtc-token)</span>
            <input
              type="password"
              value={tempTokenOverride}
              onChange={(e) => setTempTokenOverride(e.target.value)}
              disabled={inputsLocked}
              placeholder="Optional: paste a Console Temp Token matching this channel + UID"
            />
          </label>
        </div>

        <div className="controls">
          <button type="button" className="btn btn-primary" onClick={handleJoin} disabled={inputsLocked}>
            Join 1-to-1 Call
          </button>
          <button
            type="button"
            className={`btn btn-secondary ${micMuted ? "btn-warn" : ""}`}
            onClick={handleToggleMic}
            disabled={!inCall}
          >
            {micMuted ? "Unmute Mic" : "Mute Mic"}
          </button>
          <button
            type="button"
            className={`btn btn-secondary ${cameraOff ? "btn-warn" : ""}`}
            onClick={handleToggleCamera}
            disabled={!inCall || !hasLocalCamera}
          >
            {cameraOff ? "Turn Camera On" : "Turn Camera Off"}
          </button>
          <button
            type="button"
            className="btn btn-danger"
            onClick={() => void cleanupCall(true)}
            disabled={status === "idle"}
          >
            Leave Call
          </button>
          <button type="button" className="btn btn-ghost" onClick={() => void handleCopyInviteLink()}>
            {copiedLink ? "Link Copied" : "Copy 2nd Participant Link"}
          </button>
        </div>
      </section>

      <section className="video-grid">
        <div className="video-tile">
          <div id="local-video" ref={localPlayerRef} className="video-canvas" />
          {(!inCall || cameraOff) && (
            <div className="video-placeholder">
              {inCall
                ? hasLocalCamera
                  ? "Camera is off"
                  : "No local camera (audio-only on this tab)"
                : "Local camera preview appears here after joining"}
            </div>
          )}
          <div className="video-label">
            You {joinedUid !== null ? `(UID ${joinedUid})` : ""} · {micMuted ? "Mic Muted" : "Mic Live"}
          </div>
        </div>

        <div className="video-tile">
          <div id="remote-container" ref={remoteContainerRef} className="remote-container" />
          {remotePeers.every((p) => !p.hasVideo) && (
            <div className="video-placeholder">
              {remotePeers.length > 0
                ? `Peer UID ${remotePeers[0].uid} connected (camera off)`
                : "Waiting for second participant to join the same channel with a different UID…"}
            </div>
          )}
          <div className="video-label">
            {remotePeers.length === 1
              ? `Peer (UID ${remotePeers[0].uid}) · ${remotePeers[0].hasAudio ? "Audio Playing" : "Audio Muted"}`
              : remotePeers.length > 1
                ? `${remotePeers.length} Remote Participants`
                : "Remote Participant"}
          </div>
        </div>
      </section>

      <section className="card">
        <h2 className="section-title">Session Events</h2>
        <ul className="log-list">
          {logs.length === 0 ? (
            <li className="log-item">Ready. Open this URL in two browser tabs or two devices with the same channel and different UIDs to verify 1-to-1 audio &amp; video.</li>
          ) : (
            logs.map((entry) => (
              <li key={entry.id} className={`log-item log-${entry.level}`}>
                [{entry.time}] {entry.message}
              </li>
            ))
          )}
        </ul>
      </section>
    </main>
  );
}
