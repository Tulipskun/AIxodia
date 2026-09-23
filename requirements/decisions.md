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
