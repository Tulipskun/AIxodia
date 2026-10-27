GOAL:
[COMPLETED] chat AI works: message appears fast, AI answer arrives
[COMPLETED] app shows chats that live in D1 even without the tunnel
[COMPLETED] switching chat session does not reconnect the websocket
[IN_PROCESS] terminal: Shift+Tab usable from the key chips

PLAN:
[COMPLETED] D1Api: typed binds (numbers as numbers) + chatSessions/chatTurns reads
[COMPLETED] HistoryApi: sessions()/turns() fall back to D1 when the tunnel is down
[COMPLETED] unit tests: D1 row -> SessionRow/TurnRow mapping (D1ChatReadTest)
[COMPLETED] docs: README/db README say history lives in D1, phone reads it fallback
[COMPLETED] build, test (206 green), commit, push
[COMPLETED] release via scripts/release.sh: keystore from aixodia-keys, sign, verify, publish latest
[COMPLETED] bootstrap local toolchain: Android SDK 35 + JDK 17 for builds
[IN_PROCESS] TerminalKeys gets a Shift chip that latches like Ctrl/Alt; Tab
with Shift latched sends the backtab ESC[Z; TerminalView spends the Shift
latch on Tab (chips, hardware keys, typed text) the same one-shot way
[IN_PROCESS] build, test, commit, push, release via scripts/release.sh

NOTE:
- AGENTS.md rule: every non-reading tool call must be accompanied by a MAIN.md update.
- Token hygiene: the PAT lives only in the gh keyring; never commit or echo it.
- Release is rolling: push to main rebuilds and clobbers the `latest` GitHub release.
- The script refuses a dirty tree: MAIN.md is committed before releasing.
- Keystore fetched from Tulipskun/aixodia-keys (md5-verified), gitignored here.
- Fresh environment: no Android SDK — bootstrapped to /opt/android-sdk again.
- JDK 17 (apt openjdk-17) is what AGP needs; 21 breaks JdkImageTransform.
- Unit tests green again (testDebugUnitTest) with the Shift chip.
- SDK on PATH for release.sh; release uses scripts/release.sh, then verify and push.
- Push needs the gh credential helper — configure git credential.helper before pushing.
- Commit cdddcbf pushed to Tulipskun/AIxodia main.
- release.sh refused a dirty tree on itself; MAIN.md notes committed before it runs.
- Kotlin compiles clean with the Shift chip (compileDebugKotlin green).
