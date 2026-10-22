# TASK: finish the relay cleanup (AIxodia app)

Decision (owner, 2026-10-23): the terminal stays on the plain WebSocket (/terminal). No WebRTC, no P2P, no TURN. Do not add a WebRTC dependency or any rtc-* frames. TASK-P2P.md and the branch feat/terminal-webrtc-p2p have been deleted. If you have a local branch p2p-terminal or a checkout of feat/terminal-webrtc-p2p, delete it.

Already done: RelayClient/AiDirectSocket and their tests removed (fc6da77), relay-era README wording fixed (59ff333), Gradle wrapper added (d780bfc).

Remaining
1. Stale comments still talk about the relay. Reword them to match today's design (engine behind a cloudflared quick tunnel, URL discovered from D1). No behaviour change.
   - SettingsStore.kt around line 251
   - DaemonDiscovery.kt around lines 64-66
   - ChatScreen.kt around line 760
2. SettingsStore.kt around line 251 mentions "the room" path. Grep whether any setting or DataStore key for a room/relay path is still read or written. If nothing uses it, remove its accessor. Do not break existing installs: leaving the old stored key untouched is fine.
3. Find strings in res/values that only the removed relay code used (grep each name) and delete the unreferenced ones.

Keep: DaemonDiscovery, D1Api, HistoryApi, RunSocket, TerminalSocket.

Rules
- Before each commit: ./gradlew testDebugUnitTest assembleDebug
- One commit per item. Commit only, do NOT push or release.
- Report what you changed and what you left.
- Final commit: delete this TASK.md.
