# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

One person: a technical operator who runs their own AIxodia engine on a machine they control (home server or VPS) and uses the phone as the only client. They are usually away from that machine — another room, a commute, mobile data — and they are the only account. There are no other users, no shared accounts and no admin tier.

Their job is to start agent work, watch it happen, answer it, and pick it back up later without returning to a desk. The same phone also chats directly with provider models and opens a shell on the server when a task needs one.

## Product Purpose

AIxodia is the Android client for the self-hosted AIxodia engine. The engine owns execution: agent turns, tool calls, model routing, terminal shells and the history kept in Cloudflare D1. The app is the surface where that work is started, steered, inspected and resumed.

Success means leaving the app changes nothing about the work. A turn keeps running with the screen off; reopening lands on the same chat, the same scroll position and the live events it would have seen; when the daemon is unreachable the app says so plainly and shows the history it already holds. The provider, model, key and terminal screens exist so the whole system can be operated from the phone, with no desktop session and no second tool.

## Positioning

**Chat is a run, not a request.** Every turn is a numbered, replayable run on the server: `POST /v1/runs` returns an id immediately, events arrive over SSE with sequence numbers, and a reconnect resumes at the last applied seq. Closing the app, killing it or losing the tunnel does not stop the agent, and the run is durable in D1 before an event can be missed. A chat client that executes in the app cannot copy this without moving execution out of the app and making it resumable.

The second distinguishing mechanism is the credential model: one Cloudflare API token is the only secret. Typed once in Settings, it is sealed at rest with AES-GCM under an Android-keystore key and excluded from auto-backup, resolves the account and database by itself, discovers the daemon's current quick-tunnel URL from D1, and authenticates every API call; terminal sockets trade it for single-use 90-second tickets so it never appears in a URL. There is no second token, no Worker, and no address to configure.

## Operating Context

- One phone, one engine, one Cloudflare account. The engine listens on localhost, publishes itself through a Cloudflare quick tunnel whose random URL changes, and writes its URL, version and heartbeat into D1 `system_state`. The app finds it there.
- History and settings live in Cloudflare D1 (`chat_sessions`, `chat_turns`, `system_state`, `runs`, `run_events`); providers and their key pools live in Workers KV. The engine keeps no local database.
- Room on the phone is a cache: the drawer and the open chat draw from it first, then refresh.
- Shells live in tmux on the engine host, so a terminal survives an engine or app restart and can be reattached from the terminal's session list.
- Providers are configured from the phone: catalogue, key pools, health, per-agent model routes, per-chat model pins, generation parameters.
- The app ships as a sideloaded APK from a rolling GitHub Release; the in-app checker compares the versionCode in `AIxodia-version.json` with the installed build and installs over the previous one with data kept.
- Development constraint: no Android device or KVM-capable emulator is available on the current host, so committed Paparazzi goldens are the only rendered-output evidence in the repository.

## Capabilities and Constraints

- **Chat runs**: durable turns with replayable SSE, stop and retry, typing and tool status, block-level rendering of narration, reasoning, tool steps and results, attachments (image/video, file) and markdown.
- **Thread and history**: a chat opens on one page of 60 rows from Room, pulls older pages on scroll while holding the reader's place, and reconnects the live run from its last seq. Per-session cost and token counts come from the engine (`cached` / `new` / `out` strip), and a compaction marker records when the engine trimmed context.
- **Sessions**: drawer organize (rename, delete, ordering), per-chat model pin, goals/plans card and the handover note left by the engine.
- **Terminal**: a native VT/ANSI emulator and grid view owned by the app (no xterm.js, no WebView terminal), one WebSocket per tab opened with a single-use ticket, output replayed from a 4 MiB ring with saved modes, close per tab or all, surviving sessions listed and reattachable.
- **Providers and models**: provider catalogue with write-only key pools, health checks, per-agent model routes (`main`, `worker`), model capabilities reported by the daemon; controls appear only for parameters the selected model accepts.
- **Connection honesty**: online / connecting / offline states, discovery polling, `offline` once the heartbeat is older than 90 seconds, retry, and D1 read fallback for the chat list and turns when the tunnel cannot be dialled. Writes always travel to the engine.
- **Technical requirements**: minSdk 26, targetSdk 35, arm64-v8a only, Kotlin + Compose Material3 + Room + OkHttp.
- **Not in the product**: user accounts, multi-tenancy, a web client, a Worker, P2P/WebRTC, iOS. A tablet-specific layout is not established. No second authentication token may be added without approval.

## Brand Commitments

- The name is AIxodia across the engine, app, CLI and relay.
- Voice is terse and technical: labels are short and often lowercase, identifiers and units are exact (`seq`, `pid`, `cached`/`new`/`out`), and full sentences appear only where something needs explaining. English carries the shared technical vocabulary — the default in `res/values`, with a small `values-en` subset refining the connection notices and terminal states; the operator's language may override those via the system locale.
- The interaction model is messenger-shaped by commitment, not accident: a Discord-like session drawer and Telegram-like bubbles, with the composer pinned under the thread.
- The app is a control surface for one technical operator, not a consumer product; density and precision are part of its identity.

## Evidence on Hand

- `README.md` (mechanisms, authentication, reconnecting, releasing), `MAIN.md` (decision log with per-defect operator reports in Thai), `db/README.md` (D1 schema).
- Working implementation: 44 Kotlin files under `app/src/main/kotlin`, including the native terminal, run socket, Room cache, D1 fallback and settings screens; 25 unit and snapshot test files under `app/src/test`.
- Rendered evidence: Paparazzi snapshot tests (`ui/snapshots/ScreenSnapshots.kt`, `term/TerminalViewSnapshots.kt`) with committed goldens; no device screenshots.
- Absent, and not to be fabricated: testimonials, customers, benchmarks, pricing, store-listing copy, user counts, accessibility audit results. This is a private single-operator tool.

## Product Principles

1. **The run outlives the phone.** Server state is the source of truth for work in flight; UI state is a projection that must be safe to discard and rebuild.
2. **Honest state beats optimistic state.** Connect, reconnecting and offline are shown as they are; cached history is offered rather than pretending a dead tunnel is alive.
3. **Never lose the reader's place.** Switching chats, loading older turns, backgrounding or reconnecting leaves the thread where it was, with the draft and terminal tab intact.
4. **One secret, narrowly used.** Fewer credentials, short-lived tickets for anything that ends up in a URL, and no convenience feature that widens the token's exposure.
5. **Operate the whole system from the phone.** Anything needed to run the agent — model, key, route, shell, update — is reachable on the device, not deferred to a desktop.
