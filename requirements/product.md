# AIxodia Product

## Purpose

AIxodia is the Android (Kotlin) chat client for `Tulipskun/ai` (Go AI Harness).
It renders harness results on mobile with a Discord/Telegram-like messenger UX:

- Session list (like Discord channels) + chat thread (like Telegram bubbles).
- Live answers received **directly** from the `ai` daemon over WebSocket.
- Old history loaded from Cloudflare D1 (via Worker REST) and cached locally
  in Room for offline-first reads.

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
- No P2P / no direct D1 connection from the app (always via Worker REST).
