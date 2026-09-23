# mock/ — offline dev/test stack (NOT the production database)

Production history lives in Cloudflare D1 behind `worker/` (see
`worker/README.md`). This mock exists so the app can be developed and tested
without touching Cloudflare; it speaks the same REST + WebSocket contract, so
switching is a settings change in the app (DB URL + token), never a rebuild.

`mockai` = mock DB (REST, same contract as `worker/src/index.ts`) + mock ai
daemon (WebSocket + background jobs). The app talks to it exactly like the
real thing, so the phone can be tested for real today.

## Run

```bash
cd mock
go run ./cmd/mockai                      # DB :8787, agent WS :18789
go run ./cmd/mockai -token devtoken      # require the token
go run ./cmd/mockai -tunnel              # + real Cloudflare quick tunnel
```

Flags: `-db`, `-ws`, `-token`, `-data` (JSON file = the "database"),
`-step` (agent pacing), `-tunnel`, `-cloudflared` (binary path).

Default ports are 8787/18789; if something else already owns them, pass your
own (the examples in this repo used 39117/39118 during CI-style testing).

## App settings for mock mode (runtime only, nothing compiled in)

| Field | Android emulator | Physical phone on same WiFi |
|---|---|---|
| WS URL | `ws://10.0.2.2:39118/ws` | `ws://<PC-LAN-IP>:39118/ws` |
| DB URL | `http://10.0.2.2:39117` | `http://<PC-LAN-IP>:39117` |
| Token | same as `-token` (empty in dev mode) | same |
| Session | `work-1` or create a new one | same |

## Terminal client (no phone needed)

```bash
go run ./cmd/aiclient -url ws://127.0.0.1:18789/ws -session s1 -text "hello"
go run ./cmd/aiclient -url ws://127.0.0.1:18789/ws -session s1 -text "hello" -detach
```

`-detach` closes the socket right after the ack — the job must still finish and
land in the DB. That is the automated proof of "agent keeps working when the
app is closed".

## Tests

```bash
go test ./...    # background jobs, session isolation, auth, tunnel announce
```

`TestJobContinuesAfterClientDisconnectAndIsReadableFromDB` is the requirement
test: it disconnects mid-job and asserts main/sub turns are all in the DB.
