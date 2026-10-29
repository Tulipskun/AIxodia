# AIxodia

Android chat client (Kotlin + Compose) for the `Tulipskun/aixodia-engine` daemon. History
lives in **Cloudflare D1** (`chat_sessions`/`chat_turns` — the engine keeps no local
database) and the phone reads it through the daemon's `/v1/...` HTTP API over the quick
tunnel; Room keeps the copy the screens draw. When the tunnel has nothing to dial — a
moved trycloudflare URL, a stopped engine — the phone reads the chat list and turn pages
straight out of D1 with the same API token: reads only, writes always travel to the
engine. D1's `system_state` row also says where the tunnel is and whether the engine is
alive. There is no Cloudflare Worker in this project (D-011).

- **Chat is a run**: `POST /v1/runs` starts a turn and returns immediately; its events arrive over SSE (`/v1/runs/{id}/events?after=seq`), numbered and replayable. Closing the app does not stop the agent, and reopening resumes from the last applied seq. `RunSocket` is the transport.
- **Terminal is native and its own WebSocket**: the app's own `TerminalEmulator` draws every cell in `TerminalView`, and every open tab keeps its own `/terminal` socket with a single-use 90-second ticket. There is no JavaScript terminal or external terminal library. Chat uses its own transport, so terminal bytes and HTTP rpc calls never share its socket. tmux keeps shells alive across engine restarts.
- **History from the engine, paged**: `data/remote/HistoryApi.kt` calls the daemon's `/v1/sessions` and `/v1/sessions/{id}/turns` over the tunnel URL, so the chat list, old turns and the session cost strip are the engine's own numbers. When the tunnel cannot be dialled, the same reads fall back to D1 directly (`D1Api.chatSessions()` / `D1Api.chatTurns()`), so the drawer and the thread stay filled with the history D1 already holds.
- **Daemon discovery from D1**: `data/remote/D1Api.kt` runs one `SELECT` against `system_state` (`daemon/url`, `daemon/version`, `daemon/heartbeat`) with the operator's Cloudflare API token. That is how the phone learns the current quick-tunnel URL and whether the daemon is alive without asking anyone.
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
app/            Android client (.kt, Compose Material3, Room, OkHttp WS, D1 REST)
db/             Cloudflare D1 schema + migrations (applied with wrangler, no Worker)
```

## Setup

1. **D1**: `wrangler d1 create aixodia`, then
   `wrangler d1 execute aixodia --file=db/schema.sql` — both go straight to the
   Cloudflare API, nothing is deployed. The schema is provider config plus the
   `system_state` keys the daemon writes and the phone reads. See `db/README.md`.
2. **Daemon**: run `Tulipskun/aixodia-engine` — it serves the run API, the terminal
   WebSocket and `/v1/...`, opens the quick tunnel, writes its URL and heartbeat into
   the D1 `system_state` rows and keeps the chat history in the same D1 database.
3. **App**: open in Android Studio, run `app`. Settings (gear icon): paste the
   **Cloudflare API token** — the account and database ids are resolved from it
   and cached, so no id is ever typed. Discovery then finds the daemon's tunnel
   URL by itself (one `system_state` read every 30s, `DaemonDiscovery`), so
   there is no address to paste. Providers, their write-only key pools and the
   per-agent model routes live on the same screen.

## Architecture

`ai` serves the mobile WS on localhost only and opens a Cloudflare **quick
tunnel** (`ai/transport/mobile/tunnel.go`); the random trycloudflare URL is
written into D1 by the daemon itself. The app reads that row directly — no
Worker and no `/api/node` round trip — and shows "ai ออฟไลน์" once the heartbeat
is older than 90s. `ai` keeps no local state: config and sessions live in D1, and
the Cloudflare API token (sent in the WS hello, memory-only) is the daemon's
credential too.

One credential, three paths:

- **Run SSE** — `RunSocket` POSTs a turn, reads numbered events, and reconnects with
  `Last-Event-ID`. The agent runs on the engine, not in the app.
- **HTTP over the tunnel** — history paging, session create/rename/delete, the
  provider catalogue, provider health, per-chat model pins, run control and
  terminal tickets (`HistoryApi`).
- **Terminal WebSocket** — one dedicated `/terminal` socket per open tab,
  opened by the native terminal and authenticated with a short-lived ticket
  rather than the long-lived API token.
- **D1 REST** — discovery (`D1Api.node()`: the daemon's tunnel URL, version and heartbeat) and the history fallback: `D1Api.chatSessions()` / `D1Api.chatTurns()` read `chat_sessions`/`chat_turns` when the tunnel is down. Nothing the phone edits lives in D1; the app's chat writes always go to the engine.

## Authentication — one token, two steps

1. The daemon is only reachable through a random `*.trycloudflare.com` quick
   tunnel (nothing published, no port forwarding).
2. The app sends the **Cloudflare API token** as `Authorization: Bearer …` on
   every run/terminal-ticket `/v1/...` call. The terminal WebSocket carries only
   a single-use ticket in its URL, so the long-lived token never appears in a
   socket URL or proxy log. The daemon keeps the token in memory only.

An engine that cannot verify its `CF_TOKEN` against Cloudflare D1 at startup
exits instead of serving (`db.Verify`). After that, every `/v1/...` call and
the terminal upgrade compare the bearer (or a consumed ticket) to that token
and answer `401` when it is missing or wrong, so a tunnel with a rotated token
does not let a stranger in. There is **no second token** and none may be added
without approval; the daemon owns no credential of its own.

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

- Cloudflare D1 `aixodia` (id `e8e746ea-…f29a1`) holds provider config, the daemon's
  `system_state` keys and the chat history (`chat_sessions`/`chat_turns`) — the engine
  keeps no local database, so the phone reading D1 directly reads the same history the
  engine serves on `/v1/...`.
- One credential does the work: the Cloudflare API token typed in Settings reads
  the daemon row out of D1, and the same token authenticates the `/v1/...`
  calls (the terminal WebSocket uses a short-lived ticket derived from it).
- The token used for `wrangler` stays in the shell (`CLOUDFLARE_API_TOKEN`); the
  repo contains config (`config/aixodia.example.json`) and no secrets. It needs
  Account → **D1: Edit**, which is what the app and the daemon both use it for.
- A fresh app install has empty settings and opens a sign-in screen that asks
  for the Cloudflare API token instead of silently pointing somewhere.

## Releasing

There is no CI. `scripts/release.sh` builds here — a cold build is minutes and
a warm one seconds — and publishes the APK it just signed and verified:

    scripts/release.sh

It refuses a dirty tree, pushes the current branch (the release is created
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

Start the daemon (`Tulipskun/aixodia-engine`), paste the Cloudflare API token in
the app's settings, and send a message: discovery finds the tunnel URL, the app
POSTs a run and follows its SSE events. The terminal button opens the native
ticket-backed terminal. The provider, key and per-agent model are set from the
phone (AX-083..094).

## Terminal transport

There is no P2P/WebRTC/TURN path. The terminal is a dedicated WebSocket through
the same Cloudflare quick tunnel. The app owns both halves of the screen —
`TerminalEmulator` parses VT/ANSI and `TerminalView` paints the grid — and sends
binary input; output is replayed from a 4 MiB ring preceded by a reset and the
saved terminal modes, with no external terminal library involved.
Authentication is a single-use 90-second ticket obtained over authenticated
HTTP. Shells live in tmux (`aix-<id>`) when tmux is available, so a terminal
survives an app or engine restart and can be reattached from the session list.
