-- Cloudflare D1 schema for AIxodia (current names). Mirrors the live database:
-- apply with wrangler (safe to re-run: every statement is IF NOT EXISTS).
-- Provisioning source of truth; migrate.py is the create-if-missing fallback.
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


CREATE TABLE IF NOT EXISTS engine_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  level TEXT NOT NULL DEFAULT 'info',
  message TEXT NOT NULL DEFAULT '',
  at INTEGER NOT NULL DEFAULT (unixepoch())
);
CREATE INDEX IF NOT EXISTS idx_engine_logs_at ON engine_logs(at);
