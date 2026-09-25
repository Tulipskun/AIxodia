# AIxodia

Android chat client (Kotlin + Compose) for the `Tulipskun/ai` daemon, with the
Cloudflare Worker that stores its history also written in Kotlin.

- **Direct live display**: authenticated WebSocket to the `ai` daemon mobile endpoint — JSON `Input`/`Output` frames mirroring `ai/sdk/io.go` (+ `types.go`, `trace.go`). The daemon serves it itself in `ai/transport/mobile/`.
- **Old history from Cloudflare**: Cloudflare D1 via Worker REST (`worker/`), never direct D1 from the phone.
- **Cached**: Room (`AppDatabase`) — offline-first, `(session_id, seq)` unique; open = cache → D1 backfill → WS live tail.
- **Messenger UX**: Discord-like session drawer + Telegram-like bubbles, typing/tool status, connection dot, retry, pull-older.

## Layout

```text
app/            Android client (.kt, Compose Material3, Room, OkHttp WS, Worker REST)
worker/         Cloudflare Worker + D1 schema (wrangler.toml, schema.sql, src/jsMain/kotlin/aixodia/Worker.kt)
requirements/   spec source of truth (read before code)
```

## Setup

1. **D1 + Worker**: `wrangler d1 create aixodia` → put id in `worker/wrangler.toml` → `wrangler d1 execute aixodia --file=worker/schema.sql` → `wrangler secret put AIXODIA_TOKEN` → `wrangler deploy`.
2. **Daemon**: build and run `Tulipskun/ai` (`cmd/ai`) with mobile enabled — it serves the WebSocket, opens the quick tunnel and mirrors turns into D1 by itself.
3. **App**: open in Android Studio, run `app`. Settings (gear icon): the tunnel URL, the D1 token, plus provider/agent configuration. D1 first: `cd worker && ./setup.sh`.

## Architecture (tunnel + stateless ai)

`ai` serves the mobile WS on localhost only and opens a Cloudflare **quick
tunnel** (`ai/transport/mobile/tunnel.go`); the random trycloudflare URL is heartbeat-announced
to D1, and the app discovers it via `GET /api/node` (Settings → "ค้นหา ai" →
"ใช้ URL นี้"). `ai` keeps no local state: config/sessions live as JSON blobs
in D1 (`/api/state`), and the phone's scoped Worker token (sent in the WS hello,
memory-only) is its DB credential. Raw Cloudflare API tokens never leave your account.

## Authentication — one token, two steps

1. The daemon is only reachable through a random `*.trycloudflare.com` quick
   tunnel (nothing published, no port forwarding).
2. The app sends the **D1 access token** in the WebSocket handshake header
   (`Authorization: Bearer …`); the daemon verifies it against the Worker
   before upgrading and keeps it in memory only.

Missing header → `401` (not counted). Wrong token five times → `429` and a
30s lockout for that address, then 60/120/240/300s. Worker unreachable →
`503` (fail closed, not counted). There is **no second token** and none may be
added without approval; the daemon owns no credential of its own.

## Production DB (runtime config, nothing hardcoded)

- Cloudflare D1 `aixodia` (id `e8e746ea-…f29a1`) holds sessions/turns/state.
- The Worker is the only public door; the app authenticates with the Worker
  secret `AIXODIA_TOKEN` typed in Settings at runtime.
- The Cloudflare API token used for deploys lives only in the shell
  (`CLOUDFLARE_API_TOKEN`); the repo contains config (`wrangler.toml`,
  `config/aixodia.example.json`) and examples (`.dev.vars.example`), no secrets.
- A fresh app install has empty settings and shows a setup screen instead of
  silently pointing somewhere.

## Test it right now

Start the daemon (`Tulipskun/ai`, mobile enabled), copy the tunnel URL it prints
into the app's settings together with the D1 token, and send a message. The
provider, key and per-agent model are set from the phone (AX-083..094).
