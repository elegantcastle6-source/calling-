import { useRef, useEffect } from "react";

export function useRingtone(src: string) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const playingRef = useRef(false);

  useEffect(() => {
    const a = new Audio(src);
    a.loop = true;
    audioRef.current = a;
    return () => {
      a.pause();
      a.src = "";
      audioRef.current = null;
      playingRef.current = false;
    };
  }, [src]);

  const start = () => {
    const a = audioRef.current;
    if (!a || playingRef.current) return; // pehle se baj rahi ho to dobara nahi
    playingRef.current = true;
    a.currentTime = 0;
    a.play().catch(() => { playingRef.current = false; });
  };

  const stop = () => {
    const a = audioRef.current;
    if (!a) return;
    a.pause();
    a.currentTime = 0;
    playingRef.current = false;
  };

  return { start, stop };
}
