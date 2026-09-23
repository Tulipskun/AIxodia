# Functional Requirements (AX-xxx)

## Transport — direct WebSocket (user chose: WebSocket direct)

- AX-001 — App opens an authenticated WebSocket to the `ai` daemon mobile
  endpoint (`bridge/` reference server). Frames are JSON `Input` (client→server)
  and `Output` (server→client) mirroring `sdk/io.go`, with `Turn`/`ContentPart`/
  `Response`/`TraceEvent` shapes from `sdk/types.go` + `sdk/trace.go`.
- AX-002 — Client sends `{"type":"hello","token":...,"session_id":...,
  "last_seq":N}` on connect for resume; server replays missed `Output`s.
- AX-003 — Streaming: server may send `trace` frames
  (`response_text`/`tool_running`/...) then one final `message` frame per turn.
  App renders streaming text + "thinking / using tool…" indicators.
- AX-004 — Reconnect with exponential backoff (1s,2s,4s…max 30s, jitter),
  like `transport/discord/gateway_liveness.go`. Connection state is always
  visible (online / connecting / offline).
- AX-005 — Send path posts canonical `Input{source:"mobile", session_id, turn,
  metadata}` and optimistically inserts the user bubble before ack.

## History — Cloudflare D1 via Worker + Room cache

- AX-010 — Cloud source of truth is Cloudflare D1 (tables in `worker/schema.sql`,
  mirrored from `sdk/session_db.go`: `sessions` + `turns`). The app never talks
  to D1 directly; it calls Worker REST in `worker/src/index.ts`.
- AX-011 — `GET /api/sessions` → session list. `GET /api/sessions/:id/turns?
  before_seq=&limit=` → paged history (newest-first, default 50).
- AX-012 — Merge order per session open: (1) render Room cache instantly,
  (2) fetch D1 pages, upsert into Room, (3) attach WebSocket live tail.
  No duplicate seqs; `(session_id, seq)` is unique.
- AX-013 — Offline-first: airplane mode still shows cached threads; send
  while offline queues in Room (`pending`) and flushes on reconnect.
- AX-014 — `POST /api/sessions/:id/turns` ingest path exists so the daemon
  bridge can mirror every finished turn into D1 (fire-and-forget, never blocks
  the live display).

## UI — message/discord/telegram-like

- AX-020 — Session drawer (Discord-like channel list: name, model, unread dot,
  last snippet, time) + chat thread (Telegram-like bubbles: me-right,
  model-left, timestamps, day dividers).
- AX-021 — Composer with send button, streaming lock (disable while awaiting),
  retry button on error frames, pull-to-refresh = fetch older D1 page.
- AX-022 — Status row: connection dot, `tool_running` indicator, token usage
  footer on final message (small, muted).
- AX-023 — Material 3 dynamic color, dark/light, Thai+English text, minSdk 26.

## Security / config

- AX-030 — Settings screen (implemented as ui/settings/SettingsScreen): daemon WS URL, Worker base URL, auth token
  (stored encrypted), session picker. No hardcoded secrets in git.

## Update — install over, keep data (AX-04x)

- AX-040 — Every CI build signs with the same stable key and bumps
  `versionCode` (`v0.1.<RUN_NUMBER>`). Same `applicationId` + same signature +
  higher `versionCode` = Android installs OVER the old APK; Room cache,
  DataStore settings and backups survive. Uninstall is never required.
- AX-041 — In-app "ตรวจอัปเดต" (drawer): checks latest GitHub Release with the
  stored token, downloads the APK to private storage, fires the installer via
  FileProvider. Old Releases are kept on GitHub (no delete step) for rollback.

## Tunnel + stateless ai (AX-05x)

- AX-050 — Discovery: ai announces its random trycloudflare URL with
  `POST /api/node/heartbeat` every 30s; app resolves it with `GET /api/node`.
  Stale heartbeat (>90s) renders "ai ออฟไลน์". Hardcoded daemon IP is fallback.
- AX-051 — Secret flow: app sends its scoped Worker token in the WS hello
  frame; ai keeps it in memory only and uses it for Worker REST (history
  ingest + `/api/state` load/save). Raw Cloudflare API tokens never leave
  Cloudflare/operator. Tunnel frames without a valid token are rejected.
- AX-052 — Stateless state: `GET/PUT /api/state/:key` stores opaque JSON
  (≤500KB, key charset `[A-Za-z0-9:_-]`), e.g. `config/provider`,
  `sessions/<id>`. Local disk on the ai host is a pure cache, safe to wipe.
