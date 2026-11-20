import { RtcRole, RtcTokenBuilder } from "agora-token";
import { NextResponse } from "next/server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

function isPlaceholder(value: string): boolean {
  const cleaned = value.trim();
  return (
    cleaned.length === 0 ||
    cleaned.startsWith("YOUR_") ||
    cleaned.startsWith("OPTIONAL_") ||
    (cleaned.startsWith("<") && cleaned.endsWith(">"))
  );
}

/**
 * Server-side Agora AccessToken2 generator for 1-to-1 audio/video RTC sessions.
 * Reads AGORA_APP_CERTIFICATE exclusively on the server so it is never exposed to browsers.
 */
export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const channelName = (searchParams.get("channelName") ?? "").trim();

  if (!channelName || channelName.length > 64 || !/^[a-zA-Z0-9_-]+$/.test(channelName)) {
    return NextResponse.json(
      { error: "A valid channelName (1-64 alphanumeric, underscore, or hyphen chars) is required." },
      { status: 400 }
    );
  }

  const rawUid = (searchParams.get("uid") ?? "").trim();
  let uid = 0;
  if (rawUid.length > 0) {
    const parsed = Number(rawUid);
    if (!Number.isInteger(parsed) || parsed < 0 || parsed > 0xffffffff) {
      return NextResponse.json(
        { error: "uid must be an unsigned 32-bit integer." },
        { status: 400 }
      );
    }
    uid = parsed;
  }

  const appId = (process.env.AGORA_APP_ID ?? process.env.NEXT_PUBLIC_AGORA_APP_ID ?? "").trim();
  const appCertificate = (process.env.AGORA_APP_CERTIFICATE ?? "").trim();

  if (isPlaceholder(appId)) {
    return NextResponse.json(
      { error: "NEXT_PUBLIC_AGORA_APP_ID is not configured in .env.local." },
      { status: 500 }
    );
  }

  // If App Certificate is omitted, return null token so Agora Testing Mode projects still work.
  if (isPlaceholder(appCertificate)) {
    return NextResponse.json({
      token: null,
      appId,
      channelName,
      uid,
      mode: "testing",
      expiresIn: 0
    });
  }

  const tokenExpirationInSeconds = 3600;
  const privilegeExpirationInSeconds = 3600;

  try {
    const token = RtcTokenBuilder.buildTokenWithUid(
      appId,
      appCertificate,
      channelName,
      uid,
      RtcRole.PUBLISHER,
      tokenExpirationInSeconds,
      privilegeExpirationInSeconds
    );

    return NextResponse.json({
      token,
      appId,
      channelName,
      uid,
      mode: "secured",
      expiresIn: tokenExpirationInSeconds
    });
  } catch {
    return NextResponse.json(
      { error: "Failed to generate RTC token on server." },
      { status: 500 }
    );
  }
}
