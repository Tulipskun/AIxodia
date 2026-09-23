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

## Mode A — direct LAN (no tunnel, same WiFi)

Use this when the phone and the ai host share one network. No Cloudflare hop,
lowest latency.

1. On the ai host, find its LAN IP: `hostname -I` (e.g. `192.168.1.50`).
2. Serve the hub on **all interfaces, not localhost**: `127.0.0.1` accepts
   only connections from the same machine, so the phone must reach
   `0.0.0.0:18789` (or the LAN IP explicitly). Example:
   `http.ListenAndServe("0.0.0.0:18789", hub)` with the hub mounted at `/ws`.
3. Open the firewall: `sudo ufw allow 18789/tcp` (or equivalent).
4. Phone on the **same WiFi** → Settings → WS URL
   `ws://192.168.1.50:18789/ws` + same token as the daemon → back to chat.
   Old history still loads via Worker/D1 (needs internet); direct replaces
   only the live WS leg.
5. Note: plain `ws://` sends the token unencrypted on the LAN — fine at home,
   never on public/cafe WiFi (use Mode B there).

## Mode B — quick tunnel (different networks, no port forward)

`tunnel.go` path (default): hub stays on `127.0.0.1:18789`, `RunQuickTunnel`
publishes it as `https://<random>.trycloudflare.com`, phone discovers it via
`GET /api/node` (Settings → "ค้นหา ai"). Free TLS (wss), works over mobile
data. Alternative with a stable address and end-to-end encryption: Tailscale
on both machines, then use Mode A with the Tailscale IP/hostname.

## Runtime config (no secrets in code)

`config/aixodia.example.json` → copy to `config/aixodia.json` (gitignored) or
point `AIXODIA_CONFIG` at your own path. `LoadConfig(path)` reads it; the node
token comes from the env var the config names (`AIXODIA_NODE_TOKEN` by
default) or from a paired phone's WS hello, and is never written to disk.

```json
{
  "worker_base": "https://aixodia.<subdomain>.workers.dev",
  "node_token_env": "AIXODIA_NODE_TOKEN",
  "mobile_ws": { "listen": "127.0.0.1:18789", "tunnel": true }
}
```

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
