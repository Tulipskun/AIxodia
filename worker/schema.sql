-- Cloudflare D1 schema. Mirrors ai sdk/session_db.go (sessions + turns),
-- flattened so the Android client can page history with simple REST.
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  provider TEXT NOT NULL DEFAULT '',
  model TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL DEFAULT (unixepoch()),
  updated_at INTEGER NOT NULL DEFAULT (unixepoch())
);
CREATE TABLE IF NOT EXISTS turns (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  seq INTEGER NOT NULL,
  role TEXT NOT NULL,          -- user | model | tool_call | tool_result
  text TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL DEFAULT (unixepoch()),
  UNIQUE(session_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_turns_session_seq ON turns(session_id, seq);

-- AXCH-004: quick-tunnel discovery + stateless ai + device registry.
-- ai re-announces its random trycloudflare URL here every 30s; the phone
-- discovers it via GET /api/node instead of hardcoding an IP/port.
CREATE TABLE IF NOT EXISTS nodes (
  id TEXT PRIMARY KEY,            -- single row 'ai' (v1)
  tunnel_url TEXT NOT NULL DEFAULT '',
  version TEXT NOT NULL DEFAULT '',
  heartbeat INTEGER NOT NULL DEFAULT 0
);
-- Opaque JSON blobs so ai can run stateless (config/sessions live in D1,
-- ai keeps only the scoped token in memory). Values are app-level JSON text.
CREATE TABLE IF NOT EXISTS state (
  key TEXT PRIMARY KEY,           -- e.g. config/provider, sessions/<id>
  value TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL DEFAULT (unixepoch())
);
-- Future per-device tokens (v1 uses the single AIXODIA_TOKEN; register here
-- to prepare revocation per phone without rotating the shared secret).
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  label TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL DEFAULT (unixepoch()),
  revoked INTEGER NOT NULL DEFAULT 0
);
