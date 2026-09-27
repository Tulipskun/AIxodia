# AIxodia

Android chat client (Kotlin + Compose) for the `Tulipskun/ai-engine` daemon. History
lives in Cloudflare D1 and the phone reads and writes it **directly** over the
D1 REST API (D-011) — there is no Cloudflare Worker in this project.

- **Direct live display**: authenticated WebSocket to the `ai` daemon mobile endpoint — JSON `Input`/`Output` frames mirroring `ai/sdk/io.go` (+ `types.go`, `trace.go`). The daemon serves it itself in `ai/transport/mobile/`.
- **History from Cloudflare D1, direct**: `data/remote/D1Api.kt` calls `POST https://api.cloudflare.com/client/v4/accounts/{account}/d1/database/{database}/query` with the operator's Cloudflare API token, so the chat list and old turns stay readable and editable **while the daemon is stopped**.
- **Cached**: Room (`AppDatabase`) — offline-first, `(session_id, seq)` unique; open = cache → D1 backfill → WS live tail.
- **Messenger UX**: Discord-like session drawer + Telegram-like bubbles, typing/tool status, connection dot, retry, pull-older.

## Layout

```text
app/            Android client (.kt, Compose Material3, Room, OkHttp WS, D1 REST)
db/             Cloudflare D1 schema + migrations (applied with wrangler, no Worker)
requirements/   spec source of truth (read before code)
```

## Setup

1. **D1**: `wrangler d1 create aixodia`, then
   `wrangler d1 execute aixodia --file=db/schema.sql` — both go straight to the
   Cloudflare API, nothing is deployed. See `db/README.md`.
2. **Daemon**: build and run `Tulipskun/ai-engine` (`cmd/ai-engine`) with mobile enabled — it
   serves the WebSocket, opens the quick tunnel, writes its URL into the D1
   `nodes` row and mirrors every finished turn into D1 itself.
3. **App**: open in Android Studio, run `app`. Settings (gear icon): paste the
   **Cloudflare API token** — the account and database ids are resolved from it
   and cached, so no id is ever typed — then press "ค้นหา ai" to pick up the
   daemon's current tunnel URL for the live socket. Providers, their write-only
   key pools and the per-agent model routes live on the same screen.

## Architecture

`ai` serves the mobile WS on localhost only and opens a Cloudflare **quick
tunnel** (`ai/transport/mobile/tunnel.go`); the random trycloudflare URL is
written into D1 by the daemon itself. The app reads that row directly — no
Worker and no `/api/node` round trip — and shows "ai ออฟไลน์" once the heartbeat
is older than 90s. `ai` keeps no local state: config and sessions live in D1, and
the Cloudflare API token (sent in the WS hello, memory-only) is the daemon's
credential too.

Two back ends, one credential:

- **D1 REST** — chat list, paging, session create/rename/delete (`D1Api`).
- **Daemon over the tunnel** — the live socket, the provider catalogue, provider
  health, per-chat model pins and the agent settings, because validating those
  needs the running router rather than the database.

## Authentication — one token, two steps

1. The daemon is only reachable through a random `*.trycloudflare.com` quick
   tunnel (nothing published, no port forwarding).
2. The app sends the **Cloudflare API token** in the WebSocket handshake header
   (`Authorization: Bearer …`); the daemon verifies it against Cloudflare
   (`GET /user/tokens/verify`) before upgrading and keeps it in memory only.

Missing header → `401` (not counted). Wrong token five times → `429` and a 30s
lockout for that address, then 60/120/240/300s. Cloudflare unreachable → `503`
(fail closed, not counted). There is **no second token** and none may be added
without approval; the daemon owns no credential of its own.

## Production DB (runtime config, nothing hardcoded)

- Cloudflare D1 `aixodia` (id `e8e746ea-…f29a1`) holds sessions/turns/nodes/state.
- One credential does the work: the Cloudflare API token typed in Settings reads
  and writes D1, and the same token authenticates the daemon handshake.
- The token used for `wrangler` stays in the shell (`CLOUDFLARE_API_TOKEN`); the
  repo contains config (`config/aixodia.example.json`) and no secrets. It needs
  Account → **D1: Edit**, which is what the app and the daemon both use it for.
- A fresh app install has empty settings and shows a setup screen instead of
  silently pointing somewhere.

## Test it right now

Start the daemon (`Tulipskun/ai-engine`, mobile enabled), paste the Cloudflare API
token in the app's settings, press "ค้นหา ai" to fill the tunnel URL, and send a
message. The provider, key and per-agent model are set from the phone
(AX-083..094).
