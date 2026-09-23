# worker/ — Cloudflare D1 + Worker for AIxodia history

The phone never talks to D1 directly. This Worker is the HTTPS front:
`GET /api/sessions`, `GET /api/sessions/:id/turns`, `POST .../turns` (daemon
ingest mirror), `GET /ws` (optional proxy to the ai daemon when the phone
can't reach it directly).

## One-time setup (needs your Cloudflare account)

```bash
cd worker
wrangler login
./setup.sh
# prompts: paste nothing (D1 id auto-filled when possible), type AIXODIA_TOKEN,
# then it applies schema.sql and deploys.
# Optional WS proxy: AI_DAEMON_WS=wss://your-host/ws ./setup.sh
```

Manual equivalent:

```bash
wrangler d1 create aixodia            # put database_id into wrangler.toml
wrangler d1 execute aixodia --file=schema.sql
wrangler secret put AIXODIA_TOKEN
wrangler deploy
```

## App side

Settings screen → Worker URL = `https://aixodia.<you>.workers.dev`,
same token as `AIXODIA_TOKEN` → "ทดสอบ Worker" must print
`Worker OK — เจอ N sessions ใน D1`. `401` = token ผิด, `404` = ยังไม่ deploy.
