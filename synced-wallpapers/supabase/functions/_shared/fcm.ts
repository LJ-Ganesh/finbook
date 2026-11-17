// Firebase Cloud Messaging HTTP v1 (free on the Spark plan). Authenticates with the
// service account JSON stored in the FCM_SERVICE_ACCOUNT function secret.

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
}

let cached: { token: string; exp: number } | null = null;

function b64url(data: ArrayBuffer | Uint8Array | string): string {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : new Uint8Array(data);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToDer(pem: string): ArrayBuffer {
  const body = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const bin = atob(body);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}

export function serviceAccount(): ServiceAccount {
  const raw = Deno.env.get("FCM_SERVICE_ACCOUNT");
  if (!raw) throw new Error("FCM_SERVICE_ACCOUNT secret is not set");
  return JSON.parse(raw) as ServiceAccount;
}

async function accessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.exp - 60 > now) return cached.token;
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const key = await crypto.subtle.importKey(
    "pkcs8", pemToDer(sa.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`));
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${header}.${claims}.${b64url(sig)}`,
    }),
  });
  if (!res.ok) throw new Error(`OAuth token request failed: ${res.status}`);
  const body = await res.json();
  cached = { token: body.access_token, exp: now + (body.expires_in ?? 3600) };
  return cached.token;
}

export type SendResult = "ok" | "dead_token" | "retry";

/** Sends a data-only, high-priority message. Data values must be strings. */
export async function sendData(sa: ServiceAccount, token: string, data: Record<string, string>): Promise<SendResult> {
  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${await accessToken(sa)}` },
    body: JSON.stringify({ message: { token, data, android: { priority: "high", ttl: "86400s" } } }),
  });
  if (res.ok) return "ok";
  const text = await res.text();
  // UNREGISTERED (404) or an invalid token (400 INVALID_ARGUMENT mentioning the token) → drop it.
  if (res.status === 404 || (res.status === 400 && /registration token|UNREGISTERED|INVALID_ARGUMENT/i.test(text))) {
    return "dead_token";
  }
  return "retry"; // 429, 5xx, transient OAuth problems
}
