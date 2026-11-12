// Secret Chat push notifications.
//
// Called by the `on_message_push` database trigger with { message_id }.
// Looks the message up with the service role (so a caller can't inject
// content), finds the other members' device tokens and sends each one a
// Firebase Cloud Messaging data message. The Android app builds the
// notification itself (sender name, photo, tap-to-open).
//
// Required secret: FIREBASE_SERVICE_ACCOUNT = the full service-account JSON
// downloaded from Firebase (Project settings -> Service accounts).

import { createClient } from "jsr:@supabase/supabase-js@2";

type ServiceAccount = {
  project_id: string;
  client_email: string;
  private_key: string;
};

const MAX_MESSAGE_AGE_MS = 5 * 60 * 1000;
const PREVIEW_LIMIT = 140;

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);

let cachedToken: { value: string; expiresAt: number } | null = null;

function base64Url(input: ArrayBuffer | string): string {
  const bytes = typeof input === "string"
    ? new TextEncoder().encode(input)
    : new Uint8Array(input);
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function googleAccessToken(account: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.expiresAt - 60 > now) return cachedToken.value;

  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64Url(JSON.stringify({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const unsigned = `${header}.${claims}`;

  const pem = account.private_key
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned),
  );
  const assertion = `${unsigned}.${base64Url(signature)}`;

  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) {
    throw new Error(`Google token request failed: ${response.status} ${await response.text()}`);
  }
  const json = await response.json();
  cachedToken = { value: json.access_token, expiresAt: now + (json.expires_in ?? 3600) };
  return cachedToken.value;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  let messageId: string | undefined;
  try {
    messageId = (await req.json())?.message_id;
  } catch {
    return json({ error: "invalid JSON" }, 400);
  }
  if (!messageId) return json({ error: "message_id is required" }, 400);

  const rawAccount = Deno.env.get("FIREBASE_SERVICE_ACCOUNT");
  if (!rawAccount) return json({ skipped: "FIREBASE_SERVICE_ACCOUNT is not set" });
  let account: ServiceAccount;
  try {
    account = JSON.parse(rawAccount);
  } catch {
    return json({ error: "FIREBASE_SERVICE_ACCOUNT is not valid JSON" }, 500);
  }

  const { data: message, error: messageError } = await supabase
    .from("messages")
    .select("id, conversation_id, sender_id, body, image_path, created_at")
    .eq("id", messageId)
    .maybeSingle();
  if (messageError) return json({ error: messageError.message }, 500);
  if (!message) return json({ skipped: "message not found" });
  // Only notify about fresh messages, so an old id can't be replayed.
  if (Date.now() - new Date(message.created_at).getTime() > MAX_MESSAGE_AGE_MS) {
    return json({ skipped: "message is too old" });
  }

  const { data: members } = await supabase
    .from("conversation_members")
    .select("user_id")
    .eq("conversation_id", message.conversation_id);
  const recipients = (members ?? [])
    .map((m: { user_id: string }) => m.user_id)
    .filter((id: string) => id !== message.sender_id);
  if (recipients.length === 0) return json({ sent: 0, tokens: 0 });

  const { data: tokens } = await supabase
    .from("device_tokens")
    .select("token")
    .in("user_id", recipients);
  if (!tokens || tokens.length === 0) return json({ sent: 0, tokens: 0 });

  const { data: sender } = await supabase
    .from("profiles")
    .select("username, display_name, avatar_url")
    .eq("id", message.sender_id)
    .maybeSingle();

  const title = sender?.display_name || (sender?.username ? `@${sender.username}` : "New message");
  let preview = message.body || (message.image_path ? "📷 Photo" : "New message");
  if (preview.length > PREVIEW_LIMIT) preview = preview.slice(0, PREVIEW_LIMIT - 1) + "…";

  const accessToken = await googleAccessToken(account);
  const endpoint = `https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`;

  let sent = 0;
  const stale: string[] = [];
  await Promise.all(tokens.map(async ({ token }: { token: string }) => {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        message: {
          token,
          android: { priority: "HIGH" },
          data: {
            type: "message",
            conversation_id: message.conversation_id,
            message_id: message.id,
            title,
            body: preview,
            sender_avatar_url: sender?.avatar_url ?? "",
          },
        },
      }),
    });
    if (response.ok) {
      sent++;
    } else {
      const text = await response.text();
      // The app was uninstalled or the token rotated: forget this device.
      if (response.status === 404 && text.includes("UNREGISTERED")) stale.push(token);
      else console.error("FCM send failed", response.status, text);
    }
  }));

  if (stale.length > 0) {
    await supabase.from("device_tokens").delete().in("token", stale);
  }

  return json({ sent, tokens: tokens.length, removed: stale.length });
});
