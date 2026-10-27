GOAL:
[COMPLETED] chat AI works: message appears fast, AI answer arrives
[COMPLETED] app shows chats that live in D1 even without the tunnel
[COMPLETED] switching chat session does not reconnect the websocket

PLAN:
[COMPLETED] D1Api: typed binds (numbers as numbers) + chatSessions/chatTurns reads
[COMPLETED] HistoryApi: sessions()/turns() fall back to D1 when the tunnel is down
[COMPLETED] unit tests: D1 row -> SessionRow/TurnRow mapping (D1ChatReadTest)
[COMPLETED] docs: README/db README say history lives in D1, phone reads it fallback
[COMPLETED] build, test (206 green), commit, push

NOTE:
Do not regress terminal fix cebcbc0.
