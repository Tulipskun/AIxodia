# Decisions

- D-001 — User chose **private repo + D1 + WebSocket direct** (2026-09-23).
- D-002 — D1 over KV: history needs `WHERE session_id ORDER BY seq` paging;
  KV cannot query. R2 is for blobs, not chat rows.
- D-003 — WebSocket direct over poll-only: live display requirement needs
  push; Worker also proxies WS (`/ws`) when the phone cannot reach the daemon
  (CGNAT), same JSON frames either way.
- D-004 — Room cache over DataStore-only: threads need indexed paging +
  full offline reads; message table keyed by `(session_id, seq)`.
- D-005 — OkHttp WS + Retrofit/OkHttp REST + Room + Compose + Coroutines/Flow;
  Hilt pinned out of v1 to keep the graph small (manual DI via AppContainer).
- D-006 — Mirror `ai` session DB schema into D1 (`worker/schema.sql`) so the
  bridge can copy rows 1:1 without translation drift.
- D-007 — Quick tunnel over static IP/port-forward (2026-09-23, user proposal): no NAT/firewall work, free TLS; cost = random URL per boot (solved by D1 discovery) + extra latency via CF edge. Named tunnel later for a stable hostname.
- D-008 — Scoped Worker token (phone→ai in hello, memory-only) INSTEAD of raw Cloudflare API token: least privilege + revocable; Worker stays the sole D1 policy point. Single shared token v1, devices table reserved for per-device v2.
- D-009 (2026-09-24) — One token only: the D1 access token, verified live
  against the Worker. Chosen after review: per-device scoped tokens and a
  state-encryption key were both rejected by the operator ("no new tokens
  without approval"). Accepted consequence: anything holding the D1 token can
  read `config/provider` (API keys) — acceptable for single-operator use.
  Hardening that does NOT need a new secret: rotate the token (Worker secret)
  and redeploy; tokens never appear in frames, logs, or error bodies.
- D-010 (2026-09-24) — Lockout is keyed by client address, never by token
  hash: a token-keyed counter could be bypassed by rotating credentials, which
  would defeat the five-failure rule entirely.
- D-011 (2026-09-26) — **Drop the Cloudflare Worker.** The phone reads and
  writes Cloudflare D1 through the **D1 REST API directly**
  (`/client/v4/accounts/{account}/d1/database/{database}/query`), with the same
  Cloudflare API token it already holds; the account and database ids are
  resolved from that token instead of being typed in. Supersedes D-008 and
  narrows D-009: the Worker is no longer "the sole D1 policy point", so history
  stays readable and writable while the daemon is offline, and there is one
  fewer moving part to deploy and rotate. Accepted cost: the history SQL (which
  the Worker used to own) lives in the app, and the token the phone holds is
  account-scoped (`D1: Edit`) rather than a scoped Worker secret — the same
  single-operator tradeoff CON-012 / REQ-046 already accept for the daemon,
  which has talked to the Cloudflare API directly since CHANGE-081.
  The daemon still owns the live socket, provider/model validation and the
  agent settings, because those need the running router, not the database.
