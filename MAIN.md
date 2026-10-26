GOAL:
[IN_PROCESS] chat AI works: message appears fast, AI answer arrives
[IN_PROCESS] switching chat session does not reconnect the websocket

PLAN:
[IN_PROCESS] fix engine D1 reader (results field) + unit test
[PENDING] rebuild/install/restart engine, verify via curl
[PENDING] commit+push engine fix
[PENDING] app: stable ChatViewModel (no key = sessId)
[PENDING] app: optimistic insert before remote session creation
[PENDING] app: RunSocket.open/switchSession no reconnect when online
[PENDING] build, test, commit, push, release app

NOTE:
Do not regress terminal fix cebcbc0.
