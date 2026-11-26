# Next.js 1-to-1 Audio & Video RTC Starter

Companion Next.js 15 (App Router, TypeScript) starter using Agora Web SDK 4.x (`agora-rtc-sdk-ng`) and an external token server configured in `src/config.ts` (`TOKEN_SERVER_URL`).

## 1. Configure

All non-secret Agora constants (`APP_ID`, `CHANNEL_NAME`, `TOKEN_SERVER_URL`) and the public Firebase web config live in `src/config.ts`. No Agora App Certificate is stored or read in this app.

Install and start:

```bash
npm install
npm run dev
```

## 2. Verify Two-Way Audio & Video

1. Open `http://127.0.0.1:3000` in your first browser tab or device, allow camera + microphone access, and click **Start call**.
2. Open the same URL in a second browser tab or a second device and join the same channel.
3. Confirm both participants can **see** each other's video and **hear** each other's audio.
