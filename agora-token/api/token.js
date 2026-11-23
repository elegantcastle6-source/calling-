const { RtcTokenBuilder, RtcRole } = require("agora-token");

const TOKEN_TTL_SECONDS = 3600;

function isPlaceholder(value) {
  const cleaned = String(value || "").trim();
  return (
    cleaned.length === 0 ||
    cleaned.startsWith("YOUR_") ||
    cleaned.startsWith("OPTIONAL_") ||
    (cleaned.startsWith("<") && cleaned.endsWith(">"))
  );
}

function readQuery(req) {
  if (req.query && typeof req.query === "object") {
    return req.query;
  }
  const url = new URL(req.url || "/", "http://localhost");
  return Object.fromEntries(url.searchParams.entries());
}

/**
 * CommonJS Serverless Agora RTC token handler (Vercel / Node.js).
 * Reads AGORA_APP_ID and AGORA_APP_CERT (or AGORA_APP_CERTIFICATE) strictly from process.env.
 */
module.exports = (req, res) => {
  const allowedOrigin = (process.env.ALLOWED_ORIGIN || "*").trim() || "*";
  res.setHeader("Access-Control-Allow-Origin", allowedOrigin);
  res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
  res.setHeader("Cache-Control", "no-store");

  if (req.method === "OPTIONS") {
    return res.status(200).end();
  }
  if (req.method !== "GET") {
    return res.status(405).json({ error: "Method not allowed. Use GET." });
  }

  const appId = (process.env.AGORA_APP_ID || "").trim();
  const appCert = (
    process.env.AGORA_APP_CERT ||
    process.env.AGORA_APP_CERTIFICATE ||
    ""
  ).trim();

  if (isPlaceholder(appId) || isPlaceholder(appCert)) {
    return res.status(500).json({
      error: "AGORA_APP_ID and AGORA_APP_CERT (or AGORA_APP_CERTIFICATE) must be set in environment variables."
    });
  }

  const query = readQuery(req);
  const channel = String(query.channel || query.channelName || "").trim();
  if (!channel || channel.length > 64 || !/^[a-zA-Z0-9_-]+$/.test(channel)) {
    return res.status(400).json({ error: "channel required (1-64 alphanumeric, _, or - chars)" });
  }

  const rawUid = String(query.uid || "").trim();
  let uid = 0;
  if (rawUid.length > 0) {
    const parsedUid = Number(rawUid);
    if (!Number.isInteger(parsedUid) || parsedUid < 0 || parsedUid > 0xffffffff) {
      return res.status(400).json({ error: "uid must be an unsigned 32-bit integer" });
    }
    uid = parsedUid;
  }

  const roleParam = String(query.role || "publisher").trim().toLowerCase();
  const role = roleParam === "subscriber" ? RtcRole.SUBSCRIBER : RtcRole.PUBLISHER;

  try {
    // agora-token v2.x takes duration in seconds (3600 = 1 hour), not a Unix epoch timestamp.
    const token = RtcTokenBuilder.buildTokenWithUid(
      appId,
      appCert,
      channel,
      uid,
      role,
      TOKEN_TTL_SECONDS,
      TOKEN_TTL_SECONDS
    );
    return res.status(200).json({
      token,
      rtcToken: token,
      channelName: channel,
      uid,
      role: role === RtcRole.SUBSCRIBER ? "subscriber" : "publisher",
      expiresIn: TOKEN_TTL_SECONDS
    });
  } catch {
    return res.status(500).json({ error: "Failed to generate RTC token" });
  }
};
