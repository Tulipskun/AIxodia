GOAL:
[IN_PROCESS] Remove the worker middleman so engine and client talk directly; track every step in task.md (v1.176)

PLAN:
[COMPLETED] Operator: both clients direct; engine issues short-lived token instead of exposing CF_TOKEN (see task.md).
[IN_PROCESS] Direct engine path shipped in engine + dashboard; Android code still on CF_TOKEN (docs updated); commit + push after EVERY change.

ARCHIVE:
v1.175 0237ea3 connection-state/reconnect fixes shipped via #24-#26 (auth-failed state, discovery gating, backoff wake, ±20% jitter).
v1.175 `0237ea3` net: 401/403 as `ConnState.AUTH_FAILED` (banner + dot + Settings), `DiscoveryPhase.ANNOUNCED` so a KV row cannot call itself connected, `wakeSignal` cutting the run stream's backoff short on reconnect, `redialWaits` growing the terminal's curve across an immediate post-ready drop, ±20% `jitteredBackoff` in both — and the release workflow on main went green, which v1.174 left unverified.
v1.174 `ab4c00e` backup/restore: round-trip scoped after a root change, restore gated on a real bucket backup, picker/browser polish — the "push main, watch the release workflow" step was left unverified by the next session
v1.172 `e2eeda2` terminal: the page is remembered by the *text* at its top, not its row number — tmux re-wraps a replayed pane at the new width, so the number is the thing a rebuild destroys; `TerminalRow.text()` was added for it
v1.172 `66ff8cb` net: `RunSocket.rediscoverIfNeeded()` reads the daemon's D1 row on a failed dial (15 s rate limit) and `pickDaemonAddress` decides; the terminal already did this, the run stream never had a way to
v1.172 `e297d54` header: `boot=` rides the version column, `SettingsStore.saveDaemonClocks` caches both, `ChatScreen` renders `uptimeSince` = boot else start
v1.171 `TerminalCompose.edit` takes a composition's changed tail back with backspaces and retypes it (`setComposingText`/`finishComposingText` were never implemented, so Thai was invisible until a space); `pansLocalHistory` no longer needs a screenful; the wheel-into-a-plain-prompt branch and its notch counter are gone
v1.169 rendered the terminal and found a blank band: a screen that had never scrolled had its new rows padded *above* the content, so the first lines slid down the view and the top of it showed nothing
v1.169 drag = wheel when the scrollback is thinner than a screen; superseded by v1.171 — the pane's history now arrives at attach, and a wheel sent into a multiplexer paints on the multiplexer's clock
v1.168 rendered the backup page and found what compiling missed: the fourth idle chip sat off-screen, folder labels sank into their rows, a wrapped banner ran under its own icon, the menu said "Backup" over a screen titled "Backup & restore"
v1.168 scope sheet as a tick list of the root's own folders, with a snapshot test that renders it
v1.168 folder browser + allowlist sheet + preview card + auto card; merged with the parallel auto-backup commits, keeping their `AutoBackupStatus`
v1.167 auto-backup card on the phone (two commits, later replaced by the card that saves itself)
2afd170 auto-backup card always visible, live status gated on engine version
3a07dd6 auto-backup config card with idle threshold and cooldown
653b4c4 backup: name the files a restore could not write
d96e605 terminal: toggle between direct typing and command box
01d2455 backup: close settings card so page compiles
d84406f backup: one backup, one restore, no version list
0766072 docs: backup screen shipped, run watchable
3233a58 backup: run gets its own screen (PR #22 merge)
74847a3 chat: restore question when tree is gone
15009ec chat: / menu drives engine backup
6d6fca3 repo: repair read starts below phone-numbered row
+~100 earlier goals: terminal fixes, streaming, drawer, keyboard, provider UI, settings, history, session store, tool cards, chat UX, a11y audit, self-update flow

NOTE:
- task.md is the cross-repo handoff tracker: update it and push in the SAME commit as any edit; operator requires zero unpushed work (another AI may take over mid-task).
- Pending operator decision (see task.md): which "worker" to remove. This app already talks to the engine directly through the Named Tunnel (D-011) — no Cloudflare Worker in its path.
- The SFTP file-manager goal from a previous session was left mid-flight in MAIN.md (API client + Files nav); its code state was not verified — check git log/diff before resuming it.
- Scope rule form: the engine stores `/`-relative paths (absolute ones fail `validate_scope`), `BackupScreen.load` unwraps old-root-prefixed rules to that form, and the browser matches ancestors at any depth. Keep all three in that form or checkboxes desync again.
- Engine contract facts (verified against aixodia-io/src/backup.rs @ HEAD): `restorable` answers token+bucket only, never whether the bucket holds a manifest — gate a restore offer on `last_backup.ok`; an omitted field on `PUT /api/backup/config` keeps its stored value; a successful restore requires a clean `failed` list before auto-backup unlocks (`mark_restore_complete`).
- Where a backup's minutes go, measured on an idle box (53.4 s for 48,692 files / 4.6 GB): walk+plan 6.8 s, **packing 37.9 s**, asking the bucket and uploading 517 MB 5.5 s (~94 MB/s), manifest 2.7 s, publish 0.7 s. The wire is not the cost; reading the tree and rebuilding containers is.
- A terminal's history is usually not the app's. Replaying 3 s of the live pane stream left the emulator holding **1** scrollback row while tmux held **1,510**: an inline TUI repaints its viewport, so nothing ever scrolls off our screen to drag through. The engine answers this by replaying `capture-pane -p -e -S -2000` at attach.
- A resize does not cost the phone its page; the **replay that follows it** does. `attach()` always answers with `RIS` + the pane's history, so the offset survives a rebuild only if something re-derives it. Hold the page by the row's *text*; open the retry window at the **shrink**, not at the finger.
- A render between two time-bounded states is not free: Paparazzi's `SystemClock.uptimeMillis()` is fake and `snapshot()` advances it. Treat "passes as a test, differs as a picture" as a clock problem, not flakiness.
- Discovery cannot be pushed over the connection it describes. Both transports now read the D1 row themselves when a dial fails; `DaemonDiscovery` remains the fast path while the socket is alive.
- The uptime chain is the version column: `daemon/version` is `… started=<unix> boot=<unix>`; an engine without `boot=` degrades to counting its own start.
- A preedit cannot be sent as a cursor movement: `\e[nD` is a *key* to readline; only a delete means the same thing everywhere, counted in code points.
- Phone app builds from `main` and publishes under a single rolling `latest` tag; deleting the draft-izes the release and `releases/latest` 404s, so `release.sh` moves the tag and passes `--draft=false`.
- The Android SDK lives outside the backed-up tree; `scripts/setup-build-box.sh` restores `/opt/android-sdk` in one command.
- Building here: `export ANDROID_HOME=/opt/android-sdk` and run gradle under `unshare -Cm` (the cgroup-namespace warning breaks `jmod`). This sandbox may deny `unshare` — fall back to plain gradle.
- Scope, root, browse and preview need an engine that reports `includes`; an older one falls back to the read-only root and the raw pattern textarea.
- v1.175 state: the repo has **no** HTTP mock server dependency (tests reach privates by reflection, per `RunSocketOwnershipTest`); `ChatTransport.lastError` had no writer before this goal. `ConnState.AUTH_FAILED` is deliberately not-ONLINE, so the `== ConnState.ONLINE` call sites stay correct; the two exhaustive `when`s (`ConnDot`, Settings About card) are the compile-time catch.
- v1.175 test bed: `FakeDaemon` (app/src/test/.../remote) hand-rolls HTTP/1.1 on `ServerSocket` — the unit-test compile classpath is android.jar, which carries no `com.sun.net.httpserver`. Rules match the **longest** path prefix, query stripped. Stub the run cursor with plain values (`getString("run:s", null)`): an `any()` on that nullable default silently skips `resumeRemembered`. And `android.util.Log` is not mocked here (no `testOptions.returnDefaultValues`), so a path that reaches a `Log` call dies mid-coroutine and leaves the state stuck where it was — read a stalled CONNECTING as "this path logs", not as a hung socket.
- v1.175 reconnect: a backoff test that sleeps a fixed time passes by luck — the natural next dial can land inside the window (one did). `afterNextPoll` catches the wait at its **start**, where no step is under 1s and a 400 ms window can only hold the wake. Run `RunSocketReconnectTest` against the old code before believing it.
- v1.175 suite flake, still open: one clean `:app:testDebugUnitTest --rerun-tasks` in three on merged main failed `InventedRowRepairTest` with `UncaughtExceptionsBeforeTest` — an exception from another class's background coroutine, blamed on whichever `runTest` started next. It did not repeat in two further clean full runs on main, nor in a full run plus three subset runs at `ab4c00e`, so it is not pinned; the sockets' failure paths call `Log.w`, which throws here. Suspect a leaked scope, not that test.
- v1.175 backoff: `jitteredBackoff(millis, draw)` (ChatTransport.kt) takes its draw rather than rolling one so a test can name both ends. It spreads the **wait**, never the curve, so a capped 10s step really can be waited 8–12s — that is the point, not a bug to fix.
