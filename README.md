# AIxodia

Private Android chat client (Kotlin + Compose) for `Tulipskun/ai`.

- **Direct live display**: authenticated WebSocket to the `ai` daemon mobile endpoint — JSON `Input`/`Output` frames mirroring `ai/sdk/io.go` (+ `types.go`, `trace.go`). See `bridge/mobile_ws.go`.
- **Old history from Cloudflare**: Cloudflare D1 via Worker REST (`worker/`), never direct D1 from the phone.
- **Cached**: Room (`AppDatabase`) — offline-first, `(session_id, seq)` unique; open = cache → D1 backfill → WS live tail.
- **Messenger UX**: Discord-like session drawer + Telegram-like bubbles, typing/tool status, connection dot, retry, pull-older.

## Layout

```text
app/            Android client (.kt, Compose Material3, Room, OkHttp WS, Worker REST)
worker/         Cloudflare Worker + D1 schema (wrangler.toml, schema.sql, src/index.ts)
bridge/         Go drop-in mobile WS transport for the ai daemon
requirements/   spec source of truth (read before code)
```

## Setup

1. **D1 + Worker**: `wrangler d1 create aixodia` → put id in `worker/wrangler.toml` → `wrangler d1 execute aixodia --file=worker/schema.sql` → `wrangler secret put AIXODIA_TOKEN` → `wrangler deploy`.
2. **Daemon**: wire `bridge/mobile_ws.go` into `ai` (see `bridge/README.md`), expose `:18789/ws`, mirror turns to Worker ingest.
3. **App**: open in Android Studio, run `app`. Settings (gear icon): WS URL, Worker URL, token, session + Worker test. D1 first: `cd worker && ./setup.sh`. Needs reachable daemon (LAN/Tailscale) or Worker `/ws` proxy.

## Architecture (tunnel + stateless ai)

`ai` serves the mobile WS on localhost only and opens a Cloudflare **quick
tunnel** (`bridge/tunnel.go`); the random trycloudflare URL is heartbeat-announced
to D1, and the app discovers it via `GET /api/node` (Settings → "ค้นหา ai" →
"ใช้ URL นี้"). `ai` keeps no local state: config/sessions live as JSON blobs
in D1 (`/api/state`), and the phone's scoped Worker token (sent in the WS hello,
memory-only) is its DB credential. Raw Cloudflare API tokens never leave your account.

## Build / update (no uninstall needed)

CI (`.github/workflows/android.yml`, same shape as Droid-SSH) signs every build
with the same stable key (`app/aixodia-debug.keystore.b64`) and tags
`v0.1.<RUN_NUMBER>` with `versionCode=<RUN_NUMBER>`. Installing the new
`AIxodia.apk` goes OVER the old one — chat cache and settings are kept.
Unlike Droid-SSH, old Releases are never deleted (rollback possible).
In-app: drawer → "ตรวจอัปเดต".

Spec: `requirements/` (AX-xxx). Changes: `requirements/changes.md` (AXCH-001/002).
