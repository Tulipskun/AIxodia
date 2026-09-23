# bridge/ — drop-in mobile transport for `Tulipskun/ai`

`mobile_ws.go` is a reference WebSocket server speaking the same JSON frames as
`AIxodia/data/model/ChatModels.kt` (`AiInput`/`AiOutput`).

To wire into `ai` (Go daemon):

1. Copy `mobile_ws.go` to `ai/transport/mobile/mobile.go` (rename package).
2. In `cmd/ai`, start `hub := mobile.NewHub()` on `:18789/ws` behind your
   existing token check (`config/entry.json`).
3. In your Harness display func, call `hub.Publish(output.SessionID, ...)` for
   every `sdk.Output`, and POST the finished turn to the Worker
   (`POST /api/sessions/:id/turns`) so D1 history stays in sync.
4. Point the app Settings (WS URL) at `ws://<daemon-host>:18789/ws`
   or at the Worker `/ws` proxy when the phone cannot reach the daemon.
