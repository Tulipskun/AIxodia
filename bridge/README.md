# bridge/ — drop-in mobile + tunnel transport for `Tulipskun/ai`

Two reference files (copy to `ai/transport/mobile/`, rename package):

- `mobile_ws.go` — WebSocket server speaking the same JSON frames as
  `AIxodia/data/model/ChatModels.kt` (`AiInput`/`AiOutput`). Verify the hello
  token, then call `AcceptPhoneToken(in.Token)` so the daemon gains its
  scoped Worker token **in memory only** (the "phone gives ai its DB secret"
  step — a revocable Worker token, never a raw Cloudflare API token).
- `tunnel.go` — `RunQuickTunnel(ctx, port, workerBase, version)`:
  serves the hub on localhost only, publishes it via
  `cloudflared tunnel --url` (quick tunnel, no account/port-forward),
  parses the random `https://*.trycloudflare.com` URL and heartbeats it to
  `POST /api/node/heartbeat` every 30s so the phone discovers it with
  `GET /api/node`. Plus `LoadState`/`SaveState` for stateless operation
  (config/sessions as JSON blobs in D1, nothing required on local disk).

## Wire into `ai` (Go daemon)

1. `cloudflared` must be in `PATH` on the daemon host.
2. On boot: start hub on `127.0.0.1:18789/ws` (never `0.0.0.0` — the tunnel
   is the only public ingress), then `RunQuickTunnel`.
3. Bootstrapping the token: `AIXODIA_NODE_TOKEN` env for the first heartbeat;
   after a paired phone sends hello, its device token takes over in memory.
   On restart the env value (or a fresh pairing) is needed again — that is
   the price of stateless, and why the token must be scoped + revocable.
4. In your Harness display func: `hub.Publish(...)` for live display and POST
   finished turns to the Worker so D1 history stays in sync.

Security: the trycloudflare URL is public — the WS handler must reject any
frame whose hello token the Worker would reject. Upgrade path: named tunnel
(stable hostname, needs a Cloudflare account) + per-device tokens
(`devices` table already in schema).
