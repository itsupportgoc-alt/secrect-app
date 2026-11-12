# Secret Chat

An Android real-time messaging app backed by Supabase, with a Rust offline
outbox and a Python push-notification service.

```
┌────────────────────────── Android app (Kotlin + Jetpack Compose) ─────────────────────────┐
│  Auth (email/password)        Conversation list + chat UI                                  │
│         │                              │                                                  │
│         │ Supabase auth                │ PostgREST RPC + Realtime WebSocket               │
│         ▼                              ▼                                                  │
│  ┌──────────────┐             ┌────────────────────┐                                      │
│  │ Rust outbox  │◄─ enqueue ──│ ChatRepository     │── send_message RPC ─┐                 │
│  │ (JNI + disk) │── retry ───►│                    │                     │                 │
│  └──────────────┘             └────────────────────┘                     │                 │
└──────────────────────────────────────────────────────────────────────────┼─────────────────┘
                                                                           ▼
                                                        ┌───────────────────────────────┐
                                                        │ Supabase                      │
                                                        │ auth · Postgres + RLS         │
                                                        │ Realtime (postgres_changes)   │
                                                        └───────────────┬───────────────┘
                                                                        │ database webhook
                                                                        ▼
                                                        ┌───────────────────────────────┐
                                                        │ Python FastAPI service        │
                                                        │ webhook → FCM HTTP v1 push    │
                                                        └───────────────────────────────┘
```

## What works

- Email/password sign up and sign in; a profile row is created automatically.
- 1:1 conversations started by username.
- Messages delivered live to open chats over Supabase Realtime.
- Sends are written to a **persisted Rust outbox** first. If the network fails,
  the message is retried every 15 seconds with exponential backoff while the
  chat is open, and replayed with the same client UUID, so it cannot duplicate.
- Conversation inbox previews, queued-message indicators, dark mode.
- Row Level Security: users can only read conversations, members and messages
  they belong to.
- Optional Android push notifications through a Python webhook service.

## Repository layout

```
supabase/migrations/20261112000000_init.sql   schema, RLS, RPCs, realtime, triggers
rust-core/                                    offline outbox crate + JNI bridge + tests
android/                                      Kotlin/Compose app (Gradle project)
server/                                       FastAPI push-notification service + tests
```

## Prerequisites

- Android Studio (or Android SDK 35 + JDK 17)
- Rust with Android targets, Android NDK, and `cargo-ndk` (for the native outbox)
- Python 3.11+
- A Supabase project
- Optional: a Firebase project for push notifications

## 1. Set up Supabase

1. Create a project at <https://supabase.com/dashboard>.
2. Open **SQL Editor** and run `supabase/migrations/20261112000000_init.sql`.
   With the Supabase CLI you can instead run `supabase db push` after linking.
3. In **Authentication → Providers → Email**, keep email enabled. For quick
   local testing, turn **Confirm email** off so sign-up returns a session
   immediately. With confirmation on, the app tells the user to check their
   inbox.
4. Copy **Project URL** and the **anon public key** from **Project Settings → API**.

The migration also adds `messages` and `conversations` to the
`supabase_realtime` publication, so no extra dashboard toggle is needed.

## 2. Configure and build the Android app

```bash
cd android
cp local.properties.example local.properties   # fill in SUPABASE_URL / SUPABASE_ANON_KEY
```

Build the Rust core into the app (requires the NDK; set `ANDROID_NDK_HOME`):

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk          # once
rust-core/build-android.sh
```

This writes `libsecretchat_core.so` for `arm64-v8a`, `armeabi-v7a` and `x86_64`
into `android/app/src/main/jniLibs/`.

```bash
cd android
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

If you skip the Rust step the app still builds and runs; `OutboxFactory` falls
back to an in-memory queue, and only then do queued messages not survive a
process restart.

Install on a connected device/emulator:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use two accounts (e.g. one device and one emulator) to chat with yourself.

## 3. Run the push service (optional)

Push is separate from realtime: Supabase Realtime updates the open app,
while this service handles notifications when the app is in the background.

```bash
cd server
python -m venv .venv
source .venv/bin/activate          # Windows: .\.venv\Scripts\activate
pip install -e ".[dev]"
cp .env.example .env                # fill in values, never commit .env
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Expose it publicly (any host or tunnel) and add a Supabase **Database Webhook**:

- Table: `messages`, event: `INSERT`
- Type: HTTP POST, URL: `https://<your-host>/webhooks/new-message`
- Header: `x-webhook-secret: <same value as WEBHOOK_SECRET>`

To enable notifications on Android:

1. Create a Firebase Android app with package `com.secretchat.app`.
2. Put `google-services.json` in `android/app/` (the Gradle build applies the
   Google Services plugin only when that file exists).
3. Create a service account with the Firebase Cloud Messaging API enabled and
   set `FIREBASE_PROJECT_ID` plus `GOOGLE_APPLICATION_CREDENTIALS` (or
   `FCM_SERVICE_ACCOUNT_JSON`) for the Python service.
4. Request notification permission is already declared and prompted on
   Android 13+.

The webhook returns `{"sent": n, "tokens": m}`. It skips the sender's own
devices and truncates previews to 140 characters. Without Firebase configured
it still answers `200` when there are no tokens, and `503` if tokens exist but
push is disabled, so misconfiguration is visible in the Supabase webhook logs.

## 4. Tests

```bash
cd rust-core && cargo test          # 6 outbox tests
cd server && pytest                 # 7 webhook tests
```

The Android project compiles with `./gradlew :app:assembleDebug`.

## How sending works

1. `ChatRepository.sendMessage` asks the Rust core to enqueue the message and
   returns a client UUID.
2. The message is posted to the `send_message` RPC. The RPC checks membership
   and inserts with `on conflict (conversation_id, client_id) do nothing`, then
   returns the existing row if the retry already landed.
3. On success the outbox entry is removed; on failure it stays on disk with a
   backoff (1s, 2s, 4s … capped at 60s).
4. A trigger bumps `conversations.last_message_at`; the open chat receives the
   insert over `postgres_changes`, and a Supabase database webhook invokes the
   Python service for push notifications.

## Security notes

- The anon key in the app is public by design; RLS is what protects data.
  Never put the service role key in the Android app.
- The service role key lives only in the Python service's environment.
- Messages are **not end-to-end encrypted**, despite the app name. They are
  encrypted in transit and at rest by Supabase, but the database (and the push
  service) can read them. Adding E2EE would require key exchange and storing
  ciphertext only.
- Webhook calls are authenticated with a constant-time compared shared secret.

## Known limitations / next steps

- Only 1:1 chats; group conversations exist in the schema (`kind = 'group'`)
  but have no UI.
- No media, reactions, typing indicators, read receipts, or message search.
- Message history is capped at the most recent 200 rows per open chat.
- Device tokens are never pruned when FCM rejects them.
- No CI pipeline or release signing configuration.
