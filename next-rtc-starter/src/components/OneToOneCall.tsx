"use client";
import { useEffect, useRef, useState } from "react";
import { APP_ID, CHANNEL_NAME, TOKEN_SERVER_URL } from "@/config";
import { useRingtone } from "@/hooks/useRingtone";

const RING_TIMEOUT_MS = 45_000;

type RemoteInfo = { video: boolean; audio: boolean };
type RingState =
  | "idle"
  | "ringing_outgoing"
  | "ringing_incoming"
  | "answered"
  | "rejected"
  | "cancelled"
  | "missed";

export default function OneToOneCall() {
  const [error, setError] = useState("");
  const [status, setStatus] = useState({
    token: false, joined: false, camera: false, published: false,
  });
  const [remotes, setRemotes] = useState<Record<string, RemoteInfo>>({});
  const [micOn, setMicOn] = useState(true);
  const [camOn, setCamOn] = useState(true);
  const [inCall, setInCall] = useState(false);
  const [ringState, setRingState] = useState<RingState>("idle");

  const { start: startRingtone, stop: stopRingtone } = useRingtone("/ringtone.wav");
  const startRingtoneRef = useRef(startRingtone);
  const stopRingtoneRef = useRef(stopRingtone);
  startRingtoneRef.current = startRingtone;
  stopRingtoneRef.current = stopRingtone;

  const clientRef = useRef<any>(null);
  const tracksRef = useRef<any>({});
  const localRef = useRef<HTMLDivElement>(null);
  const remoteBoxRef = useRef<HTMLDivElement>(null);

  const ringStateRef = useRef<RingState>("idle");
  const isRingingRef = useRef(false);
  const missedTimerRef = useRef<number | null>(null);
  const signalChannelRef = useRef<BroadcastChannel | null>(null);
  const senderIdRef = useRef<string>(
    typeof crypto !== "undefined" && "randomUUID" in crypto
      ? crypto.randomUUID()
      : String(Math.random())
  );

  const setFlag = (k: string, v: boolean) =>
    setStatus((s) => ({ ...s, [k]: v }));

  function clearMissedTimer() {
    if (missedTimerRef.current !== null) {
      window.clearTimeout(missedTimerRef.current);
      missedTimerRef.current = null;
    }
  }

  function broadcastSignal(type: "outgoing_ring" | "answered" | "rejected" | "cancelled" | "missed") {
    signalChannelRef.current?.postMessage({ type, senderId: senderIdRef.current });
  }

  function beginRinging(nextState: "ringing_outgoing" | "ringing_incoming") {
    ringStateRef.current = nextState;
    setRingState(nextState);
    if (!isRingingRef.current) {
      isRingingRef.current = true;
      startRingtoneRef.current();
    }
    clearMissedTimer();
    missedTimerRef.current = window.setTimeout(() => {
      if (
        ringStateRef.current === "ringing_outgoing" ||
        ringStateRef.current === "ringing_incoming"
      ) {
        const wasOutgoing = ringStateRef.current === "ringing_outgoing";
        endRinging("missed");
        if (wasOutgoing) {
          broadcastSignal("missed");
          void cleanupAgoraSession();
        }
      }
    }, RING_TIMEOUT_MS);
  }

  function endRinging(
    nextState: "idle" | "answered" | "rejected" | "cancelled" | "missed"
  ) {
    clearMissedTimer();
    ringStateRef.current = nextState;
    setRingState(nextState);
    isRingingRef.current = false;
    stopRingtoneRef.current();
  }

  // Ensure ringState transitions never trigger start() repeatedly and stop() always runs cleanly.
  useEffect(() => {
    const shouldRing =
      ringState === "ringing_outgoing" || ringState === "ringing_incoming";
    if (shouldRing) {
      if (!isRingingRef.current) {
        isRingingRef.current = true;
        startRingtoneRef.current();
      }
    } else if (isRingingRef.current) {
      isRingingRef.current = false;
      stopRingtoneRef.current();
    }
  }, [ringState]);

  useEffect(() => {
    if (typeof BroadcastChannel !== "undefined") {
      const bc = new BroadcastChannel(`justchat_ring_${CHANNEL_NAME}`);
      signalChannelRef.current = bc;
      bc.onmessage = (event) => {
        const msg = event.data as { type?: string; senderId?: string } | null;
        if (!msg || msg.senderId === senderIdRef.current) return;

        if (msg.type === "outgoing_ring" && !clientRef.current && ringStateRef.current !== "ringing_incoming") {
          beginRinging("ringing_incoming");
        } else if (msg.type === "cancelled" && ringStateRef.current === "ringing_incoming") {
          endRinging("cancelled");
        } else if (msg.type === "missed" && ringStateRef.current === "ringing_incoming") {
          endRinging("missed");
        } else if (msg.type === "rejected" && ringStateRef.current === "ringing_outgoing") {
          endRinging("rejected");
          void cleanupAgoraSession();
        } else if (msg.type === "answered" && ringStateRef.current === "ringing_outgoing") {
          endRinging("answered");
        }
      };
    }

    return () => {
      clearMissedTimer();
      isRingingRef.current = false;
      stopRingtoneRef.current();
      signalChannelRef.current?.close();
      signalChannelRef.current = null;
    };
  }, []);

  async function getToken() {
    const r = await fetch(`${TOKEN_SERVER_URL}?channel=${encodeURIComponent(CHANNEL_NAME)}`);
    const d = await r.json();
    if (!d.token) throw new Error("Token empty: " + JSON.stringify(d));
    setFlag("token", true);
    return d.token as string;
  }

  function removeRemoteDiv(uid: any) {
    document.getElementById("remote-" + uid)?.remove();
  }

  async function cleanupAgoraSession() {
    try {
      tracksRef.current.mic?.close();
      tracksRef.current.cam?.close();
      tracksRef.current = {};
      await clientRef.current?.leave();
    } catch {}
    clientRef.current = null;
    remoteBoxRef.current && (remoteBoxRef.current.innerHTML = "");
    setRemotes({});
    setStatus({ token: false, joined: false, camera: false, published: false });
    setInCall(false);
  }

  async function handleRemote(client: any, user: any, mediaType: "video" | "audio") {
    try {
      await client.subscribe(user, mediaType);
      setRemotes((r) => ({
        ...r,
        [user.uid]: {
          video: !!user.hasVideo || mediaType === "video" || !!r[user.uid]?.video,
          audio: !!user.hasAudio || mediaType === "audio" || !!r[user.uid]?.audio,
        },
      }));
      if (mediaType === "video" && user.videoTrack) {
        let el = document.getElementById("remote-" + user.uid);
        if (!el) {
          el = document.createElement("div");
          el.id = "remote-" + user.uid;
          el.style.width = "100%";
          el.style.height = "300px";
          el.style.background = "#000";
          el.style.marginTop = "8px";
          remoteBoxRef.current?.appendChild(el);
        }
        user.videoTrack.play(el, { fit: "cover" });
      }
      if (mediaType === "audio" && user.audioTrack) {
        user.audioTrack.play();
      }
    } catch (e: any) {
      setError("Subscribe error: " + (e?.code || "") + " " + (e?.message || e));
    }
  }

  async function startCall(isAnsweringIncoming = false) {
    if (!isAnsweringIncoming && (clientRef.current || ringStateRef.current === "ringing_outgoing")) {
      return;
    }
    setError("");
    if (isAnsweringIncoming) {
      endRinging("answered");
      broadcastSignal("answered");
    } else {
      beginRinging("ringing_outgoing");
      broadcastSignal("outgoing_ring");
    }

    try {
      const AgoraRTC = (await import("agora-rtc-sdk-ng")).default;
      const client = AgoraRTC.createClient({ mode: "rtc", codec: "vp8" });
      clientRef.current = client;

      // Handlers join se PEHLE
      client.on("user-joined", () => {
        endRinging("answered");
      });
      client.on("user-published", (user: any, mediaType: any) =>
        handleRemote(client, user, mediaType)
      );
      client.on("user-unpublished", (user: any, mediaType: any) => {
        if (mediaType === "video") removeRemoteDiv(user.uid);
        setRemotes((r) => ({
          ...r,
          [user.uid]: { ...(r[user.uid] || { video: false, audio: false }), [mediaType]: false },
        }));
      });
      client.on("user-left", (user: any) => {
        removeRemoteDiv(user.uid);
        setRemotes((r) => {
          const n = { ...r };
          delete n[user.uid];
          return n;
        });
      });
      client.on("token-privilege-will-expire", async () => {
        try { await client.renewToken(await getToken()); }
        catch (e: any) { setError("Renew token error: " + (e?.message || e)); }
      });
      client.on("token-privilege-did-expire", async () => {
        try { await client.renewToken(await getToken()); }
        catch (e: any) { setError("Token expired: " + (e?.message || e)); }
      });

      // Token lekar join
      const token = await getToken();
      await client.join(APP_ID, CHANNEL_NAME, token, null);
      setFlag("joined", true);

      // Mic + camera (Agora ke tracks)
      const [mic, cam] = await AgoraRTC.createMicrophoneAndCameraTracks();
      tracksRef.current = { mic, cam };
      setFlag("camera", true);
      if (localRef.current) cam.play(localRef.current);

      // Publish
      await client.publish([mic, cam]);
      setFlag("published", true);

      // Jo log pehle se channel me hain unhe bhi subscribe karein
      for (const user of client.remoteUsers) {
        if (user.hasVideo) await handleRemote(client, user, "video");
        if (user.hasAudio) await handleRemote(client, user, "audio");
      }
      if (client.remoteUsers.length > 0) {
        endRinging("answered");
      }
      setInCall(true);
    } catch (e: any) {
      endRinging("idle");
      setError("Error: " + (e?.code || "") + " " + (e?.message || e));
    }
  }

  async function leaveCall() {
    if (ringStateRef.current === "ringing_outgoing") {
      endRinging("cancelled");
      broadcastSignal("cancelled");
    } else {
      endRinging("idle");
    }
    await cleanupAgoraSession();
  }

  function answerIncomingCall() {
    void startCall(true);
  }

  function rejectIncomingCall() {
    endRinging("rejected");
    broadcastSignal("rejected");
  }

  async function toggleMic() {
    await tracksRef.current.mic?.setEnabled(!micOn);
    setMicOn(!micOn);
  }
  async function toggleCam() {
    await tracksRef.current.cam?.setEnabled(!camOn);
    setCamOn(!camOn);
  }

  const btn: React.CSSProperties = {
    padding: "10px 16px", borderRadius: 8, border: "none",
    background: "#4f46e5", color: "#fff", marginRight: 8,
  };

  return (
    <div style={{ padding: 12, color: "#fff", background: "#111", minHeight: "100vh" }}>
      {error && (
        <div style={{ background: "#b91c1c", padding: 10, borderRadius: 8, marginBottom: 8 }}>
          {error}
        </div>
      )}

      {ringState === "ringing_incoming" && (
        <div style={{ background: "#1e293b", padding: 12, borderRadius: 8, marginBottom: 8 }}>
          <div style={{ marginBottom: 8 }}>Incoming call on channel {CHANNEL_NAME}…</div>
          <button style={{ ...btn, background: "#16a34a" }} onClick={answerIncomingCall}>
            Answer
          </button>
          <button style={{ ...btn, background: "#dc2626" }} onClick={rejectIncomingCall}>
            Reject
          </button>
        </div>
      )}

      <div style={{ fontSize: 12, background: "#222", padding: 8, borderRadius: 8, marginBottom: 8 }}>
        token: {String(status.token)} | joined: {String(status.joined)} | camera: {String(status.camera)} | published: {String(status.published)} | ring: {ringState}
        <br />
        remote users:{" "}
        {Object.keys(remotes).length === 0
          ? "none"
          : Object.entries(remotes)
              .map(([uid, r]) => `${uid} (video:${r.video}, audio:${r.audio})`)
              .join(", ")}
      </div>

      <div style={{ marginBottom: 8 }}>
        {!inCall ? (
          <button style={btn} onClick={() => void startCall(false)}>Start call</button>
        ) : (
          <>
            <button style={btn} onClick={toggleMic}>{micOn ? "Mute" : "Unmute"}</button>
            <button style={btn} onClick={toggleCam}>{camOn ? "Camera off" : "Camera on"}</button>
            <button style={{ ...btn, background: "#dc2626" }} onClick={leaveCall}>Leave</button>
          </>
        )}
      </div>

      <div>Me</div>
      <div ref={localRef} style={{ width: "100%", height: 240, background: "#000" }} />
      <div style={{ marginTop: 8 }}>Other person</div>
      <div ref={remoteBoxRef} />
    </div>
  );
}
