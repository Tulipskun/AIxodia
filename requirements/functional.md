# Functional Requirements (AX-xxx)

## Transport — direct WebSocket (user chose: WebSocket direct)

- AX-001 — App opens an authenticated WebSocket to the `ai` daemon mobile
  endpoint (`ai/transport/mobile/`). Frames are JSON `Input` (client→server)
  and `Output` (server→client) mirroring `sdk/io.go`, with `Turn`/`ContentPart`/
  `Response`/`TraceEvent` shapes from `sdk/types.go` + `sdk/trace.go`.
- AX-002 — Client sends `{"type":"hello","token":...,"session_id":...,
  "last_seq":N}` on connect for resume; server replays missed `Output`s.
- AX-003 — Streaming: server may send `trace` frames
  (`response_text`/`tool_running`/...) then one final `message` frame per turn.
  App renders streaming text + "thinking / using tool…" indicators.
- AX-004 — Reconnect with exponential backoff (1s,2s,4s…max 30s, jitter),
  like `transport/discord/gateway_liveness.go`. Connection state is always
  visible (online / connecting / offline).
- AX-005 — Send path posts canonical `Input{source:"mobile", session_id, turn,
  metadata}` and optimistically inserts the user bubble before ack.

## History — Cloudflare D1 via Worker + Room cache

- AX-010 — Cloud source of truth is Cloudflare D1 (tables in `worker/schema.sql`,
  mirrored from `sdk/session_db.go`: `sessions` + `turns`). The app never talks
  to D1 directly; it calls Worker REST in `worker/src/jsMain/kotlin/aixodia/Worker.kt`.
- AX-011 — `GET /api/sessions` → session list. `GET /api/sessions/:id/turns?
  before_seq=&limit=` → paged history (newest-first, default 50).
- AX-012 — Merge order per session open, with reconcile instead of blind append: (1) render Room cache instantly,
  (2) fetch D1 pages, upsert into Room, (3) attach WebSocket live tail.
  No duplicate seqs; `(session_id, seq)` is unique.
- AX-013 — Offline-first: airplane mode still shows cached threads; send
  while offline queues in Room (`pending`) and flushes on reconnect.
- AX-014 — `POST /api/sessions/:id/turns` ingest path exists so the daemon
  bridge can mirror every finished turn into D1 (fire-and-forget, never blocks
  the live display).

## UI — message/discord/telegram-like

- AX-020 — Session drawer (Discord-like channel list: name, model, unread dot,
  last snippet, time) + chat thread (Telegram-like bubbles: me-right,
  model-left, timestamps, day dividers).
- AX-021 — Composer with send button, streaming lock (disable while awaiting),
  retry button on error frames, pull-to-refresh = fetch older D1 page.
- AX-022 — Status row: connection dot, `tool_running` indicator, token usage
  footer on final message (small, muted).
- AX-023 — Material 3 dynamic color, dark/light, Thai+English text, minSdk 26.

## Security / config

- AX-030 — Settings screen (implemented as ui/settings/SettingsScreen): daemon WS URL, Worker base URL, auth token
  (stored encrypted), session picker. No hardcoded secrets in git.

## Update — install over, keep data (AX-04x)

- AX-040 — Every CI build signs with the same stable key and bumps
  `versionCode` (`v0.1.<RUN_NUMBER>`). Same `applicationId` + same signature +
  higher `versionCode` = Android installs OVER the old APK; Room cache,
  DataStore settings and backups survive. Uninstall is never required.
- AX-041 — In-app "ตรวจอัปเดต" (drawer): checks latest GitHub Release with the
  stored token, downloads the APK to private storage, fires the installer via
  FileProvider. Old Releases are kept on GitHub (no delete step) for rollback.

## Tunnel + stateless ai (AX-05x)

- AX-050 — Discovery: ai announces its random trycloudflare URL with
  `POST /api/node/heartbeat` every 30s; app resolves it with `GET /api/node`.
  Stale heartbeat (>90s) renders "ai ออฟไลน์". Hardcoded daemon IP is fallback.
- AX-051 — Secret flow: app sends its scoped Worker token in the WS hello
  frame; ai keeps it in memory only and uses it for Worker REST (history
  ingest + `/api/state` load/save). Raw Cloudflare API tokens never leave
  Cloudflare/operator. Tunnel frames without a valid token are rejected.
- AX-052 — Stateless state: `GET/PUT /api/state/:key` stores opaque JSON
  (≤500KB, key charset `[A-Za-z0-9:_-]`), e.g. `config/provider`,
  `sessions/<id>`. Local disk on the ai host is a pure cache, safe to wipe.

## Mock-first testing + agent attribution (AX-06x)

- AX-060 — Retired 2026-09-25: the Go mock stack is no longer part of this
  repository. The app is tested against the real daemon and the real Worker
  (`wrangler dev` for a local D1), so a second implementation of the contract
  cannot drift from the one that ships.
- AX-061 — Jobs are server-side: the agent persists each step to the DB before
  broadcasting it, and the job continues after the client disconnects. The app
  pulls the newest rows on open/reconnect and merges them with Room, so closing
  the app never loses or freezes work.
- AX-062 — Every turn carries `agent` (main | sub | worker | system), `job_id`,
  `stage` and optional tool name/args; the UI badges them so main vs sub
  activity is visible, both live and after a restart.
- AX-063 — Multiple sessions: the DB owns the session list (`POST /api/sessions`
  creates), the drawer switches without touching connection settings, and
  messages never cross session boundaries.
- AX-064 — Offline sends queue in Room (`pending`) and flush on reconnect;
  an ack frame clears the marker.

## Connection auth — one token, two steps (AX-07x)

- AX-070 — Step 1 is the quick-tunnel hostname: the daemon is only reachable
  through a random `*.trycloudflare.com` URL, so nothing is published.
- AX-071 — Step 2 is the D1 access token, sent as
  `Authorization: Bearer <token>` on the WebSocket handshake and verified
  against the Worker (`GET /api/ping`) BEFORE the socket is upgraded. A missing
  or malformed header is rejected with 401 and is not counted as a login.
- AX-072 — Progressive lockout keyed by client address (CF-Connecting-IP, then
  X-Forwarded-For, then peer address — behind a tunnel the peer is always the
  local cloudflared process). Five failures lock that address for 30s, then
  60s, 120s, 240s, 300s (cap). A successful handshake resets it. The key never
  includes the token, so rotating credentials cannot dodge the lockout.
- AX-073 — Fail closed: if the Worker cannot be reached the handshake returns
  503 and is not counted as a failed login. Wrong tokens return 401 and are
  counted; the daemon owns no credential of its own.
- AX-074 — Exactly one token exists in the system: the D1 access token. It is
  created by the operator, entered at runtime (app Settings / daemon env), and
  kept in the daemon only in memory. No node token, no state-encryption key, no
  per-device token may be added without explicit approval. State values
  (`config/provider` with API keys included) are stored as written.

## Streaming display and per-chat model choice (AX-08x)

- AX-080 — Every turn streams: the daemon asks the provider for a stream
  (`Request.Stream`), and the mobile frames are `delta` (one streamed chunk),
  `trace` (progress: request, provider_ready, thinking, retry_wait, tool_call,
  tool_running, tool_result), `message` (the authoritative answer, sent only
  when it did not already arrive as deltas) and `done`. The phone appends
  deltas into one live answer, draws tool steps as they run with name, status,
  args excerpt and recorded duration, shows reasoning only as a transient
  thinking row with its recorded time (never as stored/transcribed prose), and
  replaces the live answer with the stored row when the turn closes.
- AX-081 — The phone can pick the provider and model for a chat: `GET /api/models`
  returns what the router can actually reach right now, `PATCH /api/sessions/:id`
  with `{provider, model}` validates the pair against the router, applies it to
  the live session and stores it on the chat, so the choice survives a restart
  and follows the session on any phone. A chat that was never configured keeps
  following the global agent defaults, and an explicitly pinned chat can be
  unpinned with `{"clear_model":true}`; the daemon then forgets the cached live
  route, so the next turn uses the current defaults instead of the old pin.
  The session sheet is session scope only and says so: it shows a stored pin or
  "ใช้ค่าของ agent", plus the picked provider's global status, and never edits
  global defaults or key pools.
- AX-082 — The streaming bubble is never written to the local database: the
  daemon mirrors exactly one row per turn into D1 and the app pulls it when the
  turn closes, so a stream can never become a duplicate history row.

## Provider administration, per-agent models and stop (AX-08x)

- AX-083 — The settings screen owns the provider list: every provider shows its
  status and last error, the operator can add a provider (id, name, adapter,
  base URL, first key) and delete one, and a refresh button probes each provider
  with a single real request so the screen shows the actual reason (`401`
  invalid key, `403` free tier, `402` out of credit, `503` upstream) instead of
  a silent failure. The list is a plain `GET /api/providers` over the tunnel.
- AX-084 — Each provider has a key pool the operator can edit from the phone:
  add one key, remove one key by pool position, or replace the whole pool. The
  app only ever sends key material; it displays the count and never a key value,
  because the daemon's admin API is write-only for keys. Since a value can never
  be read back, there is no in-place key edit: fixing one key means adding the
  replacement and removing the old position, or replacing the pool. Removing a
  key or replacing a pool is destructive and needs its own confirmation naming the
  provider and position; changing the pool returns the provider to untested.
- AX-085 — The main and the sub agent each get their own global default provider +
  model, chosen in the settings screen and stored with `PUT /api/settings`; the
  daemon validates the pair against its router, applies it to the next turn, and
  re-reads it after a restart. These defaults apply to every chat without its own
  pin; they never edit a single chat's stored route.
- AX-086 — A running turn can be stopped from the phone: while the turn is live
  the send button becomes a stop button, tapping it sends the `cancel` frame,
  the bubble shows the stop state (`cancelled`, or that the turn had already
  finished) and the button only returns to send when `done` arrives — an `ack`
  must not clear the busy state, or the button would vanish before the turn was
  actually stopped.

## Visual system and pickers (AX-087)

- AX-087 — The app is not allowed to ship the stock Material defaults: it
  defines its own dark and light colour roles (ink surfaces with a mint primary,
  amber tertiary, tonal error colour), a type scale, and one shape ramp, and
  every screen composes those semantic roles instead of raw colours. The
  settings surface is a scroll of titled sections (connection, providers and
  keys, per-agent models, app) with one status banner for the result of the last
  action, and provider actions wrap instead of clipping. Every agent model is
  chosen from a bottom sheet with a search field and per-model capability tags,
  and the provider list and the model catalogue are loaded when the screen
  opens — a model picker must never be empty because nobody pressed refresh.
- AX-088 — Provider status is three-valued: `ยังไม่ทดสอบ` until a probe has
  answered for that provider, then `ใช้ได้` or `ใช้ไม่ได้` with the provider's
  own reason. A provider that has never been probed must not be shown as
  healthy, and changing its key pool puts it back to untested.

## Task flow and recovery (AX-089)

- AX-089 — The chat header names the model the chat actually uses (the route
  stored for it), never a session id; tapping it opens the provider/model sheet,
  pre-set to that stored route, and a chat with no route says it follows the
  agent default instead of showing an invented choice. Losing the daemon is a
  banner above the input with a retry, not grey text. A message can be copied
  with a long press and   confirmed. In settings, a key can be pasted from the
  clipboard, the operator chooses *which* key to drop (keys are never sent
  back, so position is the only honest handle), removal asks again by provider
  and position before deleting, and one provider can be tested
  on its own instead of waiting for the whole list.


## Provider truth (AX-090)

- AX-090 — A provider card shows the model that actually answered for it
  (`ตอบได้จริง: <model>`, from the daemon's `working_model`), and the result of
  a provider test names that model instead of only a model count. A catalogue
  the gateway lists is not proof that anything answers, so the phone must show
  the one request that came back, and a refusal that is temporary (a free tier
  that is out of quota) must read as a refusal rather than as a broken
  provider.

- AX-091 — A settings screen that cannot read the daemon says so and keeps what
  it had: a failed or timed-out read must never empty the provider list, and a
  summary must never claim `ทุก provider ใช้งานได้` when the daemon returned no
  providers at all. The REST client waits long enough for a real provider check
  (a gateway can take tens of seconds to answer), and a provider test that fails
  is reported, not thrown.

## Live turn view (AX-092, AX-093)

- AX-092 — The chat is a live view of the turn, not a transcript that fills in
  at the end. The thread uses the whole window (edge to edge, the app bar and
  the input bar own their own insets), an indeterminate progress bar and a
  breathing "กำลังตอบ" label say the agent is working, and a footer under the
  input names the model, the tokens it used, how long the turn has taken and the
  rate in tokens per second. While the answer streams the token count and the
  rate are an estimate and are marked `≈`; when the provider reports its usage
  the footer switches to those real numbers without changing anything else.
- AX-094 — The model sheet leads with the model a health check actually got an
  answer from (the daemon's default) and tags it `ตอบได้จริง`, because a
  catalogue says nothing about what this key can use. The footer's route is the
  route that turn used: a chat with no route of its own says it follows the
  agent, so the numbers are never labelled with a model from a later turn.
- AX-093 — Sub agent work is visible and stoppable per worker. Each sub agent the
  daemon reports gets a row with what it is doing and how long it has been at
  it, and its own stop button that stops that worker and leaves the turn
  running; the turn's own stop button keeps stopping everything. A row changes
  state only when the daemon says so: `subagent_stopping`, then the worker's own
  final report, or `subagent_not_found` when the job had already ended.

## The thread is the document (AX-095, AX-096)

- AX-095 — Every answer carries its own footer, not the turn. A model message is
  drawn with the model that produced it, the tokens it used (`out`, input as
  `↑in`, and cached usage when the provider reported it), how long it took and
  how fast it wrote. The
  numbers come from the closing frame of that message — the model and the
  duration the daemon measured from the request it sent to the event it
  received — and they are stored with the turn, so a chat reopened tomorrow
  still shows them. A message with no numbers shows no footer rather than zeros
  dressed up as a measurement. While an answer is still streaming its footer
  marks the counts with ≈.
- AX-096A — A pulled page that matches nothing local at all means the session id
  was reborn server-side (deleted and recreated, so D1 holds a new conversation
  under an old id). Merging would only stack two threads on the same seqs, so
  D1 is adopted wholesale instead: stale non-pending rows go, pending rows stay
  only while their text is nowhere in the page, and a pending row D1 already has
  is dropped in favour of D1's copy. A page that does match goes through the
  per-row reconcile (insert when absent, adopt when identical, D1-wins only with
  the twin in the same page).

- AX-096 — The thread has no bubbles. A message runs the full width of the
  screen with no card around it, an answer is drawn as Markdown (headings,
  emphasis, inline and fenced code, lists, quotes, rules) and half-written
  Markdown has to render as something, because the text arrives a few
  characters at a time. Long-press still copies a message.

## Navigation, scope and display integrity (AX-097..AX-099)

- AX-097 — Settings is a branch of the chat screen, not a way out of the app:
  both the toolbar back arrow and the Android system back button return to the
  chat that was open.
- AX-098 — Configuration has exactly two scopes and the UI names them: a chat
  sheet pins or unpins one chat, while settings owns global providers, their
  write-only key pools and the global main/sub defaults. Provider identity is
  immutable after creation; keys are only added, removed by position with a
  second confirmation, or replaced wholesale, and changing a pool returns that
  provider to untested. The session sheet always shows whether the chat is
  pinned or following the agent defaults, with the picked provider's global
  status beside the choice.
- AX-099 — Numbers and work shown on the phone must match what the daemon
  recorded: provider-reported input/output/cache tokens, exact output over the
  daemon-measured duration for token/s, streaming estimates marked `≈`, tool
  rows with name/status/args excerpt/duration, and reasoning as a transient
  thinking timer only — never stored prose.
