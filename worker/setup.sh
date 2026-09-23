#!/usr/bin/env bash
# AIxodia Cloudflare D1 + Worker setup (run where `wrangler login` works).
# Creates the D1 database (once), applies schema.sql, sets the token secret,
# optionally points /ws proxy at your ai daemon, then deploys.
set -euo pipefail
cd "$(dirname "$0")"

DB_NAME="${DB_NAME:-aixodia}"
need() { command -v "$1" >/dev/null || { echo "missing: $1"; exit 1; }; }
need wrangler; need python3

echo "==> wrangler whoami"
wrangler whoami

echo "==> ensure D1 database '$DB_NAME'"
if ! wrangler d1 list 2>/dev/null | grep -q "$DB_NAME"; then
  wrangler d1 create "$DB_NAME"
  echo "--- copy database_id above into wrangler.toml, then re-run ---"
  exit 0
fi

echo "==> apply schema.sql"
wrangler d1 execute "$DB_NAME" --file=schema.sql

if ! grep -q "REPLACE_WITH_D1_ID" wrangler.toml; then
  echo "==> wrangler.toml already has a database_id"
else
  ID=$(wrangler d1 list | awk -v n="$DB_NAME" '$0 ~ n {found=1} found && /[0-9a-f-]{36}/ {print; exit}' | grep -oE "[0-9a-f-]{36}" | head -n1 || true)
  if [ -n "${ID:-}" ]; then
    python3 - "$ID" <<'PY'
import sys
from pathlib import Path
p = Path("wrangler.toml")
p.write_text(p.read_text().replace("REPLACE_WITH_D1_ID", sys.argv[1]))
print("wrote database_id into wrangler.toml")
PY
  else
    echo "--- put database_id into wrangler.toml manually, then re-run ---"
    exit 0
  fi
fi

echo "==> secret AIXODIA_TOKEN (you will be prompted)"
wrangler secret put AIXODIA_TOKEN

if [ -n "${AI_DAEMON_WS:-}" ]; then
  echo "==> vars AI_DAEMON_WS=$AI_DAEMON_WS (Worker /ws proxy)"
  wrangler deploy --var "AI_DAEMON_WS:$AI_DAEMON_WS"
else
  echo "==> deploy (skip /ws proxy; set AI_DAEMON_WS=wss://... to enable)"
  wrangler deploy
fi

echo "==> verify"
wrangler tail --help >/dev/null && echo "deployed. Test: GET /api/sessions with 'Authorization: Bearer <token>'"
