> **Update (worker removal):** The dashboard Cloudflare Worker is gone; both clients
> talk to the engine directly. The engine now issues short-lived opaque session
> tokens via `POST /v1/auth/token` in exchange for a GitHub token checked against
> `ALLOWED_GITHUB_USERS`. This is an operator-approved exception to any
> "no second token" wording below: `CF_TOKEN` never reaches a client. Android
> now uses the same GitHub Device Flow and Engine-issued session token as the dashboard.

# AIxodia

Android chat client (Kotlin + Compose) for the `Tulipskun/aixodia-engine` daemon. History
lives in **Cloudflare D1** (`chat_sessions`/`chat_turns`, owned by the Engine) and the
phone reads it through the daemon's `/v1/...` HTTP API over the Cloudflare Named Tunnel;
Room keeps the synchronized copy the screens draw and serves it offline. The Android
app does not access D1 or Workers KV directly. The stable daemon endpoint and Named
Tunnel credentials live in Workers KV (`AIXODIA_CONFIG`). There is no Cloudflare Worker
in this project (D-011).

- **Chat is a run**: `POST /v1/runs` starts a turn and returns immediately; its events arrive over SSE (`/v1/runs/{id}/events?after=seq`), numbered and replayable. Closing the app does not stop the agent, and reopening resumes from the last applied seq. `RunSocket` is the transport.
- **Terminal is native and its own WebSocket**: the app's own `TerminalEmulator` draws every cell in `TerminalView`, and every open tab keeps its own `/terminal` socket with a single-use 90-second ticket. There is no JavaScript terminal or external terminal library. Chat uses its own transport, so terminal bytes and HTTP rpc calls never share its socket. tmux keeps shells alive across engine restarts.
- **History from the engine, paged**: `data/remote/HistoryApi.kt` calls the daemon's `/v1/sessions` and `/v1/sessions/{id}/turns` over the tunnel URL. Room keeps synchronized history available offline; the Android client does not call D1 directly.
- **Daemon discovery**: the app defaults to the stable Named Tunnel URL and checks the Engine's `/healthz` endpoint directly. A changed URL can still be adopted from a daemon announcement on the live socket. No heartbeat is stored.
- **Cached**: Room (`AppDatabase`) — offline-first, `(session_id, seq)` unique; the drawer and the open chat are drawn from Room before any network call. Open = cached newest page → background refresh → WS live tail.
- **Chunked history**: the thread holds one page of messages (60 rows), not the whole
  transcript; scrolling to the top asks for the next page and keeps the reader's
  place. Pages come from Room when cached and from
  `GET /v1/sessions/{id}/turns?before_seq=…&limit=…` only when they are not. The
  session's cost strip comes from the engine's session metadata (summed by the
  engine over every stored turn and cached in Room with the session row), so it
  counts the turns below the loaded window without the app adding them up.
- **Messenger UX**: Discord-like session drawer + Telegram-like bubbles, typing/tool status, connection dot, retry, pull-older.

## Layout

```text
app/            Android client (.kt, Compose Material3, Room, OkHttp WS, Engine API only)
db/             Cloudflare D1 schema + migrations (applied with wrangler, no Worker)
```

## Setup

1. **D1**: `wrangler d1 create aixodia`, then
   `wrangler d1 execute aixodia --file=db/schema.sql` — both go straight to the
   Cloudflare API, nothing is deployed. D1 stores chat history and runtime state,
   not daemon discovery. See `db/README.md`.
2. **Workers KV**: create namespace `AIXODIA_CONFIG` and set `tunnel_token` to the
   Named Tunnel connector token and `tunnel_url` to `https://aixodia.dpdns.org`.
   The engine writes `daemon/url` and `daemon/version` only when their values change.
3. **Daemon**: run `Tulipskun/aixodia-engine` — it serves the run API, the terminal
   WebSocket and `/v1/...`, starts the Named Tunnel from Workers KV, publishes its stable endpoint to KV after
   edge registration, and keeps chat history in D1.
4. **App**: open in Android Studio and run `app`. Sign in with GitHub using the
   Device Flow (the same OAuth App client id as the dashboard). The app exchanges
   the OAuth token at `POST /v1/auth/token`; it does not ask for a Cloudflare
   token or access D1/KV directly. The Engine URL defaults to
   `https://aixodia.dpdns.org`. Providers, write-only key pools and per-agent
   model routes remain in Settings.

## Architecture

`ai` serves the mobile WS on localhost only and opens a Cloudflare **Named Tunnel**.
The daemon reads its connector token and stable hostname from Workers KV, then writes
`daemon/url` and `daemon/version` to KV after edge registration. The app uses the
stable hostname and checks `/healthz` directly — no Worker, KV read or `/api/node`
round trip. Its status dot reflects the actual socket connection, not a stored
heartbeat. `ai` keeps no local state: config and sessions live in D1, and `CF_TOKEN`
remains an Engine-side credential that is never sent to the Android app.

One credential, three paths:

- **Run SSE** — `RunSocket` POSTs a turn, reads numbered events, and reconnects with
  `Last-Event-ID`. The agent runs on the engine, not in the app.
- **HTTP over the tunnel** — history paging, session create/rename/delete, the
  provider catalogue, provider health, per-chat model pins, run control and
  terminal tickets (`HistoryApi`).
- **Terminal WebSocket** — one dedicated `/terminal` socket per open tab,
  opened by the native terminal and authenticated with a short-lived ticket
  rather than the long-lived API token.
- **Offline history** — Room retains the pages already synchronized from the Engine. The Android client makes no direct D1 or Workers KV REST calls. Nothing the phone edits lives in D1; chat writes always go to the Engine.

## Authentication — GitHub Device Flow

1. The app requests a device code from GitHub using the same public OAuth App
   client id as `aixodia-dashboard` (`Ov23lieKSrKCRs5YKwdO`). It opens
   `https://github.com/login/device` and displays the user code while polling.
2. After approval, the app sends the GitHub OAuth access token once to
   `POST /v1/auth/token`. The Engine verifies `GET https://api.github.com/user`,
   checks `ALLOWED_GITHUB_USERS`, and returns a short-lived opaque session token
   (default TTL 900 seconds).
3. All API and terminal-ticket requests use only the Engine-issued session token.
   Android Keystore protects that session token and its expiry. The GitHub OAuth
   access token exists only in memory during the exchange; it is not written to
   DataStore, Room or disk. When the Engine session expires, the user signs in
   again. Sign out clears the stored Engine session.

The app uses `https://aixodia.dpdns.org` by default. The Engine must have
`ALLOWED_GITHUB_USERS` configured and expose `/v1/auth/token`. No PAT or
Cloudflare API token is entered in the Android app.

## Reconnecting

- **Run stream** — `RunSocket` probes `/v1/sessions` while offline (5s), then
  resumes the remembered run. A dropped SSE connection reconnects with a
  capped backoff and `after=<lastSeq>`, so no token is applied twice in the same
  process.
- **App closed** — the engine keeps working. The run id is remembered per chat;
  on reopen the status is checked and an active run is streamed from zero (the
  live UI is empty, so replay is safe), while a finished run is left to the
  normal history refresh.
- **Foreground** — `MainActivity` reconnects when the transport is offline or
  the app was away 30s. A long background trip can hide a dead tunnel.
- **Daemon moved** — discovery adopts the new URL or notices a new `started` and
  calls `RunSocket.reconnect()`; the terminal socket reconnects with a fresh
  ticket.
- **Terminal** — a dropped socket asks for a new ticket and redraws from the
  engine's ring. While the terminal has tabs open a foreground service keeps
  every tab's WebSocket attached across app switches and across a trip back to
  the chat: Back only hides the window, so returning lands on the same tab with
  the same screen instead of redialing. The terminal's own Close action ends its
  tabs — every shell is closed on the engine and the saved tab list is cleared,
  so reopening the terminal starts on a fresh shell instead of handing back tabs
  that were already closed. Removing the app from recents closes the sockets
  explicitly. Closing a single tab sends `close`, so that shell and its tmux slot
  are freed, and the tab is not reattached if a list poll still reports it for a
  moment.

## Production DB (runtime config, nothing hardcoded)

- Cloudflare D1 `aixodia` holds chat history (`chat_sessions`/`chat_turns`), runs and
  runtime state. The engine is the only client of D1; the Android app reads history
  through the Engine API and keeps its offline copy in Room.
- Only the Engine-issued app session is sealed with AES-GCM under a key in Android
  Keystore (`TokenCipher`) and excluded from auto-backup
  (`res/xml/backup_rules.xml`). The temporary GitHub OAuth token is never written
  to disk; the Android app has no Cloudflare API credential and makes no direct
  D1/KV calls.
- The token used for `wrangler` stays in the shell (`CLOUDFLARE_API_TOKEN`); the
  repo contains config (`config/aixodia.example.json`) and no secrets. Only the
  Engine needs Cloudflare D1 and Workers KV permissions; the Android app needs none.
- A fresh app install has empty settings and opens a sign-in screen that asks
  for GitHub sign-in instead of silently pointing somewhere.

## Releasing

Every push to `main` runs `.github/workflows/release.yml`, which runs
`scripts/release.sh` on a GitHub runner (it can also be started by hand from
the Actions tab or with `gh workflow run "Release APK"`). The same script works
on a machine with an Android SDK, where a cold build is minutes and a warm one
seconds:

    scripts/release.sh

In CI the key comes from the `AIXODIA_KEYSTORE_B64` repository secret
(`base64 -w0 app/aixodia-debug.keystore | gh secret set AIXODIA_KEYSTORE_B64`);
without it the job fails instead of signing with another key. Locally the
script refuses a dirty tree, pushes the current branch (the release is created
against that commit), signs with the stable key (`app/aixodia-debug.keystore`),
checks the signature really is that key, and updates the single rolling
`latest` GitHub Release. The tag never changes; the APK and
`AIxodia-version.json` are replaced in place. That metadata carries the exact
versionCode burned into the APK, which is what the in-app checker compares —
the tag itself has no version to parse. The shared key plus a rising versionCode
means an install updates over the previous APK — no uninstall, cache kept.

The key is not in this repository: it lives in the private
`Tulipskun/aixodia-keys` repo, whose `fetch.sh` installs `app/aixodia-debug.keystore`
(alias `aixodia-debug`, password `aixodiadebug`, SHA-1
`A5:5B:56:94:85:81:23:4D:83:58:86:C1:E7:90:98:95:FA:C3:2B:41`).

## Test it right now

Start the daemon (`Tulipskun/aixodia-engine`), sign in with GitHub, and send a
message: the app exchanges the GitHub credential for an Engine session token,
then POSTs a run and follows its SSE events. The terminal button opens the native
ticket-backed terminal. The provider, key and per-agent model are set from the
phone (AX-083..094).

## Terminal transport

There is no P2P/WebRTC/TURN path. The terminal is a dedicated WebSocket through
the same Cloudflare Named Tunnel. The app owns both halves of the screen —
`TerminalEmulator` parses VT/ANSI and `TerminalView` paints the grid — and sends
binary input; output is replayed from a 4 MiB ring preceded by a reset and the
saved terminal modes, with no external terminal library involved.
Authentication is a single-use 90-second ticket obtained over authenticated
HTTP. Shells live in tmux (`aix-<id>`) when tmux is available, so a terminal
survives an app or engine restart and can be reattached from the session list.
