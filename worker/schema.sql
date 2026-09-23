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
