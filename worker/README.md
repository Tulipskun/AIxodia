# worker/ — Cloudflare Worker + D1 (production history store)

The Worker is Kotlin: `src/jsMain/kotlin/aixodia/Worker.kt` compiles to an ES
module with Gradle (`./gradlew buildWorker`) and `build/worker/index.mjs` is the
entry wrangler loads. There is no TypeScript in this project any more — the app
is the product, so the whole repo is Kotlin.

## Build

```bash
cd worker
gradle buildWorker          # writes build/worker/index.mjs
npx wrangler deploy         # needs CLOUDFLARE_API_TOKEN in the shell
```

The phone never talks to D1 directly. This Worker is the HTTPS/WSS front:
`GET/POST /api/sessions`, `GET/POST /api/sessions/:id/turns`,
`POST /api/node/heartbeat` + `GET /api/node` (quick-tunnel discovery),
`GET/PUT /api/state/:key` (stateless-ai blobs), `GET /ws` (optional proxy).

## Current deployment

| What | Value |
|---|---|
| Account | `3b953d1c…b37b` (config, not a secret) |
| D1 database | `aixodia` / `e8e746ea-…f29a1` — schema applied (5 tables) |
| Worker | `aixodia` → **https://aixodia.aixodia.workers.dev** (deployed) |
| App auth | Worker secret `AIXODIA_TOKEN` — set per environment, never in git |

## Runtime config only (no secrets in code)

- `wrangler.toml` — non-secret config (name, D1 binding, optional `AI_DAEMON_WS`).
- `AIXODIA_TOKEN` — a **Worker secret**: `wrangler secret put AIXODIA_TOKEN`.
  It is the token the app types in Settings; the daemon uses it for REST.
- Local `wrangler dev` → copy `.dev.vars.example` → `.dev.vars` (gitignored).
- The Cloudflare **API token** is only ever an env var (`CLOUDFLARE_API_TOKEN`)
  in the shell that runs wrangler. It is never stored in the repo.
- The Android app reads Worker URL + token from DataStore at runtime (gear
  icon). A fresh install has empty values and shows a setup screen.

## Deployed (2026-09-24)

- workers.dev subdomain `aixodia` registered; Worker `aixodia` deployed with
  the D1 binding `DB` → `aixodia`.
- `AIXODIA_TOKEN` set as a Worker secret (value lives only in the operator's
  shell / 600-mode file, never in git). The app takes it at runtime in
  Settings; `mockai -worker-token` takes it for daemon-side writes.
- Verified live: 401 without token, session create/list, turns with
  `agent` + `job_id`, and tunnel heartbeat via `GET /api/node`.

## Token permissions needed

Minimum for provisioning/deploy:

- Account → **D1: Edit**
- Account → **Workers Scripts: Edit** (deploy + `secrets`)
- Account → **Account Settings: Read** (workers.dev subdomain)
- Account → **Account Settings: Edit** (only if workers.dev must be enabled)

A user token without these returns 403 (`No access to the specified resource`).

## Provisioning by hand (if you prefer)

```bash
export CLOUDFLARE_API_TOKEN=…        # shell only
export CLOUDFLARE_ACCOUNT_ID=…
npx wrangler d1 execute aixodia --remote --file=schema.sql
npx wrangler secret put AIXODIA_TOKEN
npx wrangler deploy
```

## Mock parity

`mock/mockdb` implements the same endpoints in Go, so the app can be tested
without any of the above. Keep the two in sync: schema + contract changes land
in `worker/schema.sql`, `worker/src/jsMain/kotlin/aixodia/Worker.kt`, and `mock/mockdb/db.go`.
