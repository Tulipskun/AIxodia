-- Cloudflare D1 schema for AIxodia (current names). Mirrors the live database:
-- apply with wrangler (safe to re-run: every statement is IF NOT EXISTS).
-- Provisioning source of truth; migrate.py is the create-if-missing fallback.
CREATE TABLE IF NOT EXISTS chat_sessions (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL DEFAULT '',
  provider TEXT NOT NULL DEFAULT '',
  model TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL DEFAULT (unixepoch()),
  updated_at INTEGER NOT NULL DEFAULT (unixepoch()),
  sub_provider TEXT DEFAULT '',
  sub_model TEXT DEFAULT '',
  sub_enabled INTEGER DEFAULT '',
  reasoning INTEGER NOT NULL DEFAULT 0,
  temperature REAL,
  top_p REAL,
  max_tokens INTEGER
);

CREATE TABLE IF NOT EXISTS chat_turns (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
  seq INTEGER NOT NULL,
  role TEXT NOT NULL,
  agent TEXT NOT NULL DEFAULT '',
  job_id TEXT NOT NULL DEFAULT '',
  text TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL DEFAULT (unixepoch()),
  model TEXT NOT NULL DEFAULT '',
  input_tokens INTEGER NOT NULL DEFAULT 0,
  output_tokens INTEGER NOT NULL DEFAULT 0,
  duration_ms INTEGER NOT NULL DEFAULT 0,
  cache_read_tokens INTEGER NOT NULL DEFAULT 0,
  cache_write_tokens INTEGER NOT NULL DEFAULT 0,
  reasoning_tokens INTEGER NOT NULL DEFAULT 0,
  input_includes_cache INTEGER NOT NULL DEFAULT 0,
  generation_ms INTEGER NOT NULL DEFAULT 0,
  reasoning_content TEXT NOT NULL DEFAULT '',
  block TEXT NOT NULL DEFAULT '',
  UNIQUE(session_id, seq)
);

CREATE TABLE IF NOT EXISTS llm_providers (
  "index" INTEGER PRIMARY KEY,
  provider TEXT NOT NULL DEFAULT '',
  adapter TEXT NOT NULL DEFAULT '',
  endpoint TEXT NOT NULL DEFAULT '',
  keys TEXT NOT NULL DEFAULT '[]'
);

CREATE TABLE IF NOT EXISTS system_state (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_updated ON chat_sessions(updated_at);
CREATE INDEX IF NOT EXISTS idx_chat_turns_session_seq ON chat_turns(session_id, seq);

CREATE TABLE IF NOT EXISTS engine_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  level TEXT NOT NULL DEFAULT 'info',
  message TEXT NOT NULL DEFAULT '',
  at INTEGER NOT NULL DEFAULT (unixepoch())
);
CREATE INDEX IF NOT EXISTS idx_engine_logs_at ON engine_logs(at);
