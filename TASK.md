# TASK: remove dead transport code

Context: AixodiaApp.kt uses RunSocket for chat and TerminalSocket for terminal. Code from the relay/WebView era is believed unused.

Rules
- Before each commit: ./gradlew testDebugUnitTest assembleDebug
- One commit per item. Commit only, do NOT push or release.
- Report what you kept and why.

Steps
1. Grep to prove AiDirectSocket, RelayClient and their tests (RelayBackoffTest, RelayFrameKindTest) are unreferenced, plus any strings/helpers only they used. Then delete them.
2. Keep: DaemonDiscovery, D1Api, HistoryApi, RunSocket, TerminalSocket.
3. Fix README lines that still mention relay, WebView or xterm.js.
