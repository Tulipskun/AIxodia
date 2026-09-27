# AIxodia Product

## Purpose

AIxodia is the Android (Kotlin) chat client for `Tulipskun/ai-engine` (Go AI Harness).
It renders harness results on mobile with a Discord/Telegram-like messenger UX:

- Session list (like Discord channels) + chat thread (like Telegram bubbles).
- Live answers received **directly** from the `ai` daemon over WebSocket.
- Old history loaded from Cloudflare D1 over the D1 REST API and cached locally
  in Room for offline-first reads (D-011: no Worker in front of D1).

## Goals

1. Direct display of `ai` canonical `Input`/`Output` (sdk/io.go) with no
   Discord dependency on the phone.
2. History continuity: D1 is the cloud source of truth, Room is the fast
   offline cache, WebSocket is the live tail.
3. Familiar messenger UX: sessions, bubbles, typing/streaming state,
   connection status, retry.
4. Private repo; tokens stay on device (EncryptedSharedPreferences/DataStore).

## Non-goals (v1)

- No provider key management on mobile (done in `ai` daemon / Discord).
- No attachment upload/download (text-only v1, refs render as labels).
- No Worker: the app talks to Cloudflare D1 directly with the operator's
  Cloudflare API token (D-011), and to the daemon over the tunnel for the live
  socket and provider/model settings.
