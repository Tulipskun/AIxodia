# db/ — Cloudflare D1 schema (no Worker)

The daemon and the app both talk to Cloudflare D1 through the **Cloudflare API
directly** (D-011), so this directory holds only the schema. It is applied with
wrangler, which speaks the same API — nothing is deployed.

```bash
# one-time: create the database, then keep its id where the app does not need it
npx wrangler d1 create aixodia

# apply the schema (safe to re-run: every statement is IF NOT EXISTS)
npx wrangler d1 execute aixodia --file=db/schema.sql

# databases created before AX-095 need the per-message footer columns
npx wrangler d1 execute aixodia --file=db/migrations/0002_turn_footer.sql
```

`CLOUDFLARE_API_TOKEN` must be in the shell for these commands, with Account →
**D1: Edit** (plus Account Settings: Read if `wrangler whoami` should name the
account). The app authenticates with its own Cloudflare API token, typed in
Settings; it resolves the account and database from that token, so the id above
never has to be copied into the app.

`schema.sql` mirrors `ai/sdk/session_db.go` (`sessions`, `turns`) and adds the
transport tables:

- `nodes` — the daemon's current quick-tunnel URL, rewritten by its heartbeat;
- `state` — opaque JSON blobs (`config/*`, `sessions/<id>`) so `ai` can run
  stateless;
- `devices` — reserved for per-device tokens.
