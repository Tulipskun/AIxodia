# AIxodia

Android chat client (Kotlin + Compose) for the `Tulipskun/aixodia-engine` daemon. History
lives **on the engine** (its own store) and the phone reads it through the
daemon's `/v1/...` HTTP API over the quick tunnel; Room keeps the copy the
screens draw. Cloudflare D1 is read directly by the phone for one thing only:
the daemon's own row in `system_state`, which says where that tunnel is. There
is no Cloudflare Worker in this project (D-011).

- **One socket for everything live**: authenticated WebSocket to the engine's `/room` — JSON chat frames plus multiplexed terminal streams and HTTP-over-socket rpcs. The engine serves it in `io/relay` (transport) on top of the `io/mobile` hub; frames mirror the daemon's `io/mobile` wire types.
- **History from the engine, paged**: `data/remote/HistoryApi.kt` calls the daemon's `/v1/sessions` and `/v1/sessions/{id}/turns` over the tunnel URL, so the chat list, old turns and the session cost strip are the engine's own numbers.
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
2. **Daemon**: run `Tulipskun/aixodia-engine` (`main.go`) — it serves `/room`,
   opens the quick tunnel, writes its URL and heartbeat into the D1
   `system_state` rows and stores the chat history itself.
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

One credential, three transports:

- **`/room` WebSocket** — the live tail, terminal streams and every HTTP call
  made as an rpc frame (`RelayClient`). One handshake, one token, one reconnect
  loop (`RelayClient.loop`), which redials with a capped backoff that only
  resets after a socket stayed up (see `nextBackoffMs`).
- **HTTP over the tunnel** — history paging, session create/rename/delete, the
  provider catalogue, provider health, per-chat model pins and the agent
  settings (`HistoryApi`). The daemon's router validates those, which is why
  they are not read from the database.
- **D1 REST** — only discovery: the daemon's tunnel URL, version and heartbeat
  (`D1Api.node()`). Nothing the phone edits lives in D1.

## Authentication — one token, two steps

1. The daemon is only reachable through a random `*.trycloudflare.com` quick
   tunnel (nothing published, no port forwarding).
2. The app sends the **Cloudflare API token** as `Authorization: Bearer …` — in
   the WebSocket handshake for `/room` and in a header on every `/v1/...` HTTP
   call. The daemon checks it before upgrading or answering and keeps it in
   memory only.

An engine that cannot verify its `CF_TOKEN` against Cloudflare D1 at startup
exits instead of serving (`db.Verify`). After that, every request — the `/room`
handshake and each `/v1/...` call — compares the bearer to that token and
answers `401` when it is missing or wrong, so a tunnel with a rotated token
does not let a stranger in. There is **no second token** and none may be added
without approval; the daemon owns no credential of its own.

## Reconnecting

One loop owns the socket (`RelayClient.loop`); every trigger below only asks it
to dial now, so there is never a second connection racing the first.

- **Failure or close** — redial with a capped, jittered backoff (1s → 8s). The
  delay resets to 1s only after a socket stayed up 10s (`nextBackoffMs`), so a
  server that accepts and immediately drops is not retried once a second.
- **Engine absent while the socket is up** (`{"ctl":"presence","engine":false}`)
  — the state drops to CONNECTING and the socket is kept: the room is the
  phone's, the engine is what left. The hello is sent again when presence
  returns, which is when the streamed answer resumes arriving.
- **Foreground or network change** — `reconnectIfDown()` redials only when the
  link is not ONLINE, so coming back to the app never resets a healthy socket.
- **Daemon moved** — discovery adopts the new tunnel URL (`Adopt`), or sees the
  same URL with a new `started` (`Keep` + replaced). In both cases the socket
  and the terminal are redialed, because every stream died with the old process.
- **Terminal** — a dropped stream reattaches on the same socket; a stream the
  engine never answered is redialed with a new stream id, and the socket itself
  is replaced only when it is not ONLINE, so a swallowed terminal open cannot
  kill a chat turn that is streaming on it.

## Production DB (runtime config, nothing hardcoded)

- Cloudflare D1 `aixodia` (id `e8e746ea-…f29a1`) holds provider config and the
  daemon's `system_state` keys. Chat history is the engine's own store, not a
  D1 table.
- One credential does the work: the Cloudflare API token typed in Settings reads
  the daemon row out of D1, and the same token authenticates both the `/room`
  WebSocket and the `/v1/...` HTTP calls.
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
against that commit), numbers the build one past the latest release, signs with
the stable key (`app/aixodia-debug.keystore`), checks the signature really is
that key, and creates the `v0.1.<n>` GitHub Release. It then deletes every
older release, so exactly one download is ever current. The shared key plus a
rising versionCode means an install updates over the previous APK — no
uninstall, cache kept.

The key is not in this repository: it lives in the private
`Tulipskun/aixodia-keys` repo, whose `fetch.sh` installs `app/aixodia-debug.keystore`
(alias `aixodia-debug`, password `aixodiadebug`, SHA-1
`A5:5B:56:94:85:81:23:4D:83:58:86:C1:E7:90:98:95:FA:C3:2B:41`).

## Test it right now

Start the daemon (`Tulipskun/aixodia-engine`), paste the Cloudflare API token in
the app's settings, and send a message: discovery finds the tunnel URL and the
socket dials `/room` without any further setup. The provider, key and per-agent
model are set from the phone (AX-083..094).

## Terminal transport: WebRTC P2P (planned)

The current terminal stream is carried as a stream inside the app's one WebSocket to the engine (`/room`, reached through the engine's Cloudflare quick tunnel). Terminal input and output therefore share that socket with chat and with the HTTP rpc calls for the entire session.

The target design is to move **terminal bytes only** to a WebRTC DataChannel:

- Cloudflare Durable Objects are signaling only: pair the phone and engine, then forward SDP offers/answers and ICE candidates. They must not proxy terminal input or output.
- Android uses libwebrtc; the Linux engine uses Pion WebRTC.
- Prefer a direct ICE path with STUN. Configure TURN as a fallback for networks where direct connectivity fails.
- Once the DataChannel is open, terminal input, PTY output, resize events, and terminal control messages use that channel. Keep the existing PTY session and terminal UI semantics.
- If the P2P connection fails, reconnect through the existing socket transport so terminal access remains available.
- Authenticate signaling and session attachment with short-lived, scoped credentials. Do not expose an unauthenticated shell or send the Cloudflare API token to an arbitrary peer.

**Status:** design target only. The current TerminalSocket still uses RelayClient; P2P is not active until both the Android and engine implementations, signaling protocol, TURN fallback, and tests are merged. Chat and HTTP RPC continue using the existing transport unless separately redesigned.

