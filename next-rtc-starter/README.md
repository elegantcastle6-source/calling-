# Next.js 1-to-1 Audio & Video RTC Starter

Companion Next.js 15 (App Router, TypeScript) starter using Agora Web SDK 4.x (`agora-rtc-sdk-ng`) and a server-side AccessToken2 endpoint (`/api/rtc-token` via `agora-token`).

## 1. Check Agora Project Readiness via Agora CLI

Run these commands outside any existing `.agora`-bound workspace:

```bash
# Install Agora CLI if not already installed (macOS / Linux)
curl -fsSL https://dl.agora.io/cli/install.sh | sh -s -- --add-to-path

# Optional: install/update Agora Skills
npx skills add github:AgoraIO/skills

# Authenticate (global region)
agora login

# List projects and confirm which project ID to use
agora project list

# Select your confirmed project and check RTC control-plane readiness
agora project use <CONFIRMED_PROJECT_ID>
agora project doctor --feature rtc --json
```

If you prefer to scaffold directly via the Agora CLI into a fresh directory instead of using this companion folder:

```bash
agora init my-video-demo --template nextjs --scenario video-call
```

## 2. Configure & Run Locally

```bash
cp .env.example .env.local
```

Set your confirmed Agora project values in `.env.local` (never commit `.env.local`):

- `NEXT_PUBLIC_AGORA_APP_ID` — your confirmed project's App ID
- `AGORA_APP_CERTIFICATE` — your project's Primary Certificate (server-only; leave blank only if the project is in Testing Mode / App ID only)
- `NEXT_PUBLIC_DEFAULT_CHANNEL` — optional default room name

Install and start:

```bash
npm install
npm run dev
```

## 3. Verify Two-Way Audio & Video

1. Open `http://127.0.0.1:3000` in your first browser tab or device, allow camera + microphone access, and click **Join 1-to-1 Call**.
2. Click **Copy 2nd Participant Link** and open that URL in a second browser tab or a second device (same channel name, different numeric UID), allow camera + microphone access, and join.
3. Confirm both participants can **see** each other's video and **hear** each other's audio (use headphones if testing in the same room to avoid echo), and verify **Mute Mic**, **Turn Camera Off/On**, and **Leave Call**.
