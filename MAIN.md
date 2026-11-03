GOAL:
[COMPLETED] Terminal shows what a finger and a keyboard asked for (with engine v1.171): a drag pages the pane's own history locally instead of waiting on a multiplexer's repaint, a resize holds the row being read, and a Thai composition appears on the line while it is being built — checked by replaying the real pane stream and by rendering.

PLAN:
(no active plan)

ARCHIVE:
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
- Where a backup's minutes go, measured on an idle box (53.4 s for 48,692 files / 4.6 GB): walk+plan 6.8 s, **packing 37.9 s**, asking the bucket and uploading 517 MB 5.5 s (~94 MB/s), manifest 2.7 s, publish 0.7 s. The wire is not the cost; reading the tree and rebuilding containers is.
- A terminal's history is usually not the app's. Replaying 3 s of the live pane stream left the emulator holding **1** scrollback row while tmux held **1,510**: an inline TUI repaints its viewport, so nothing ever scrolls off our screen to drag through. The engine answers this by replaying `capture-pane -p -e -S -2000` at attach, which puts the pane's lines in the app's scrollback where a drag costs no round trip.
- A preedit cannot be sent as a cursor movement. `\e[nD` is a *key* to readline, not a terminal control: it moves within the line's buffer, so the characters after it are inserted rather than written over and the syllable doubles. Only a delete means the same thing to a shell, to a line discipline and to a raw-mode program — so a composition update is N backspaces plus the new tail, counted in code points because that is what a line editor deletes (a Thai tone mark draws on its letter and is still a character).
- Speed as measured on v1.168 this box: warm backup 109.6 s (48,354 files / 4.58 GB, 607 MB uploaded, 46,107 files reused); restore 82.2 s (47,925 files, `failed: 0`) — that restore is the box coming back from its own backup, so the path is proven end to end, not just in tests.
- UI is not done until it has been looked at: `./gradlew :app:testDebugUnitTest --tests "*Snapshots*"` writes a PNG per composable under `app/build/reports/paparazzi/debug/images/`. Read it. `recordPaparazziDebug` rewrites only what it is pointed at, and a golden that moves is a finding to look at, not churn to revert — the two that looked unrelated last round were this resize bug's own footprint.
- Phone app builds from `main` branch (`latest` tag).
- The Android SDK lives outside the backed-up tree, so a restored box has no `/opt/android-sdk`. `scripts/setup-build-box.sh` puts it back in one command.
- Building here: `export ANDROID_HOME=/opt/android-sdk` and run gradle under `unshare -Cm`. Without the cgroup namespace every JVM prints a warning to stderr, AGP feeds it to `jmod --module-version`, and `compileDebugJavaWithJavac` dies with "Version string does not start with a number".
- Scope, root, browse and preview need an engine that reports `includes`; an older one falls back to the read-only root and the raw pattern textarea.
