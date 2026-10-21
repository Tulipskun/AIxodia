# db/ — Cloudflare D1 schema (no Worker)

The daemon and the app both talk to Cloudflare D1 through the **Cloudflare API
directly** (D-011), so this directory holds only the schema. It is applied with
wrangler, which speaks the same API — nothing is deployed.

What each side uses it for: the daemon keeps provider config here and writes its
own `system_state` rows (`daemon/url`, `daemon/version`, `daemon/heartbeat`);
the app only reads `system_state` (`D1Api.node()`) to discover the current
tunnel URL and whether the daemon is alive. Chat history is the daemon's own
store and is never a D1 table.

```bash
# one-time: create the database, then keep its id where the app does not need it
npx wrangler d1 create aixodia

# apply the schema (safe to re-run: every statement is IF NOT EXISTS)
npx wrangler d1 execute aixodia --file=db/schema.sql
```

`CLOUDFLARE_API_TOKEN` must be in the shell for these commands, with Account →
**D1: Edit** (plus Account Settings: Read if `wrangler whoami` should name the
account). The app authenticates with its own Cloudflare API token, typed in
Settings; it resolves the account and database from that token, so the id above
never has to be copied into the app.

The daemon does not create tables itself; provisioning a fresh database means
running `db/schema.sql` (or `migrate.py`, which creates tables if missing as a
fallback). The tables in `schema.sql` are:

- `llm_providers` — provider keys and endpoints;
- `system_state` — opaque JSON blobs and daemon keys (daemon/url, daemon/version,
  daemon/heartbeat, kernel/*, sync/*, etc.).

`schema.sql` lists exactly these two. Databases from before the rename may
still carry a `tunnel` row (and `chat_sessions`/`chat_turns`); the app reads
`system_state` first and falls back to `tunnel` only when those keys are absent
(`D1Api.node()`).
