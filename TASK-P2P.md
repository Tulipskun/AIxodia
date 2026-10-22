# TASK-P2P: optional direct DataChannel for the terminal (app side)

Prerequisite: the transport cleanup in TASK.md is done. The engine side is specified in aixodia-engine/TASK-P2P.md; its protocol is repeated here so this file stands alone.
The old branch `feat/terminal-webrtc-p2p` is built on the deleted RelayClient: do not merge it. It also uses the deprecated `org.webrtc:google-webrtc`; do not reuse that dependency.

Goal: after the terminal WebSocket is attached, try a direct WebRTC DataChannel to the engine and move the terminal onto it. WebSocket stays the default and the fallback. Terminal only: chat (RunSocket) and HTTP are untouched.

Engine protocol (text control frames on the existing /terminal WebSocket)
- phone -> engine: `{"type":"rtc-offer","sdp":...}`
- engine -> phone: `{"type":"rtc-answer","sdp":...}` or `{"type":"rtc-error","message":...}`
- DataChannel `term`, ordered, reliable. Same framing as the WebSocket: text = control JSON, binary = raw bytes.
- Over the DataChannel: `{"type":"ping","t":N}` is answered with `{"type":"pong","t":N}`.

Design
- Introduce a small terminal-transport interface used by TerminalSocket. The OkHttp WebSocket becomes one implementation, the DataChannel another. Keep the existing ticket, ready and redial logic for WebSocket.
- Only after the WebSocket is OPEN (ready received) and only while the terminal screen is visible: create a PeerConnection (STUN only, `stun:stun.cloudflare.com:3478`, no TURN), DataChannel `term`, create the offer, wait for ICE gathering up to 3s, send `rtc-offer` over the WebSocket, apply `rtc-answer`. On `rtc-error` give up for this connection.
- When the DataChannel opens: ping/pong 3 times to measure RTT. If it works, attach the same shell id over the DataChannel (same open/attach handshake and replay as the WebSocket), then close the WebSocket attach. The user stays on the same shell; one repaint is acceptable.
- Dead path: ping every 3s, no pong for 8s, or PeerConnection `failed`, or `disconnected` for 5s, or a network change: drop the DataChannel and reconnect through the normal WebSocket path. Never resend input.
- Retry: after a failure wait 10s, 30s, 120s, then stop for this app session on the same network; reset on network change. Never attempt while the terminal screen is closed or the app is in the background, and close the PeerConnection when the screen closes. No background wake-ups (battery).
- Setting "Direct connection (experimental)", default off. When off, no WebRTC code runs and PeerConnectionFactory is not initialized.
- Show state in the existing terminal detail line: path (WS or direct), candidate-pair type (host/srflx/prflx/relay) from getStats, last RTT in ms.
- Library: a maintained prebuilt libwebrtc, for example `io.getstream:stream-webrtc-android`. Add the needed proguard rules. If the project already restricts ABIs keep that; report the APK size delta.
- Tests: pure-Kotlin state-machine tests (path selection, retry backoff, dead-path detection) with fake transports, no device needed. Keep TerminalSocketTest passing.

Rules
- Before each commit: ./gradlew testDebugUnitTest assembleDebug
- Work on branch `p2p-terminal`. Commit only, do NOT push or release.

Manual checks for the owner (not the agent), results go in the final commit message
1. Phone and engine on the same Wi-Fi: expect a host/srflx path.
2. Phone on mobile data, engine elsewhere: probably stays on WebSocket, which is fine.
3. Switch Wi-Fi <-> mobile data mid-session: falls back to WebSocket and the screen recovers.
4. Leave the app: no WebRTC network activity.
Ship it only if check 1 works reliably; otherwise keep the setting off and say so.
