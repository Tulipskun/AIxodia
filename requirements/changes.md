# Changes (AXCH-xxx, append-only)

- AXCH-001 (2026-09-23) — Initial spec from user request (Thai): "Android app .kt
  แสดงผลจากโปรเจกต์ ai โดยรับข้อมูลจาก ai โดยตรง + โหลดข้อมูลเก่าจาก database
  cloudflare + cache ไว้ คล้าย message/discord/telegram". Surveyed GitHub first:
  no AIxodia repo (name free), `ai` has only CLI+Discord transports and SQLite
  per-session DB, no Cloudflare D1/KV/R2 anywhere, Droid-MCP is the Kotlin
  reference. User then chose private + D1+Worker + WebSocket direct. This spec
  and scaffold implement exactly that. No conflicts (greenfield repo).

- AXCH-002 (2026-09-23) — Build/release แบบ Droid-SSH แต่เก็บ release เก่าทุกตัว:
  stable keystore ตัวเดียว (`app/aixodia-debug.keystore.b64`, PKCS12 CN=AIxodia,
  decode ใน CI) เซ็นทั้ง debug/release; version `v0.1.<RUN_NUMBER>` +
  `versionCode=<RUN_NUMBER>` ทุก build; ติดตั้งทับตัวเดิมได้ (applicationId เดิม
  + ลายเซ็นเดิม + versionCode สูงขึ้น) Room cache/settings ไม่หาย ไม่ต้องลบแอป.
  ต่างจาก Droid-SSH ตรงที่ไม่มีขั้นลบ release/tag เก่า (keep all, rollback ได้).
  เพิ่ม AX-040/AX-041: `UpdateManager` (เช็ค latest release → โหลด APK ผ่าน OkHttp
  ด้วย token → ติดตั้งผ่าน FileProvider) + ปุ่ม "ตรวจอัปเดต" ใน drawer.

- AXCH-003 (2026-09-23) — Fix user-reported issues: (1) composer โดน NavBar บัง:
  `enableEdgeToEdge()` + bottomBar `navigationBarsPadding().imePadding()`;
  (2) ไม่มีจอตั้งค่า + บั๊กสลับ session ล้างค่าเชื่อมต่อ (`save("",..)`):
  เพิ่ม SettingsScreen (WS URL / Worker URL / token / session + ปุ่มบันทึก +
  ปุ่มทดสอบ Worker + สถานะ WS) และ `saveSession()`/`saveConnection()`;
  (3) D1 ยังไม่ setup: เพิ่ม `worker/setup.sh` + `worker/README.md`
  (d1 create → schema → secret → deploy) และปุ่มทดสอบในแอป. AX-030 ขยายเป็นจอจริง.

- AXCH-004 (2026-09-23) — Architecture: ai เปิด quick tunnel (cloudflared,
  ไม่ต้อง forward port), ai stateless (config/state เป็น JSON blob ใน D1 ผ่าน
  Worker `/api/state`), โทรศัพท์ส่ง scoped token ให้ ai ใน WS hello frame
  (in-memory เท่านั้น). Tradeoffs ที่ยอมรับ: (1) quick tunnel URL สุ่มทุกครั้ง
  ที่ restart จึงต้องมี discovery ผ่าน `GET /api/node` (heartbeat 30s, stale
  >90s = offline) — ไม่ใช่ optional; (2) ส่งเฉพาะ scoped Worker token ที่ revoke
  ได้ ห้ามส่ง raw Cloudflare API token (oq pass ผ่าน TLS แต่ URL เป็น public
  ใครเดาเจอก็ต่อได้ จึงต้องตรวจ token ทุก frame); (3) token ชุดเดียว v1
  (AIXODIA_TOKEN) + ตาราง devices เตรียม per-device; (4) provider keys ใน D1 =
  ใครมี token อ่านได้ รับได้เฉพาะ personal use. Upgrade path: named tunnel +
  per-device tokens. เพิ่ม AX-050..052, bridge/tunnel.go, NodeCard ใน Settings.

- AXCH-005 (2026-09-23) — ทดสอบได้จริงก่อน setup Cloudflare: เพิ่ม `mock/`
  (Go: mock DB + mock agent + `aiclient` + 6 tests) ที่พูด protocol เดียวกับ
  Worker และ bridge. ความต้องการที่บังคับให้แก้โค้ดจริง: (1) **ปิดแอพแล้ว agent
  ต้องทำงานต่อ** → job ถูก detach จาก connection, ทุก step `persist → broadcast`
  (DB เป็นผู้ชี้ขาด, WS แค่ส่งซ้ำให้คนดู) — เทสต์ `TestJobContinuesAfterClientDisconnectAndIsReadableFromDB`
  ตัดกลางงานแล้วยืนยันว่า main/sub turns อยู่ใน DB ครบ; (2) **หลาย session** →
  `POST /api/sessions` + session แยก Room/DB, ไม่มีข้อความรั่วข้าม session
  (เทสต์ `TestMultipleSessionsAreIsolated`); (3) **แสดง main/sub agent** →
  เพิ่ม `agent` + `job_id` + `stage` + `tool` ใน turn/frame ทั้ง Worker, D1,
  Room (migration 1→2 ติดตั้งทับได้ไม่หายประวัติเดิม) และ badge ใน UI;
  (4) **เปิดแอปกลับมาแล้วดึงล่าสุดจาก DB** → `syncSessions()` + `refreshLatest()`
  ทำทั้งตอนเปิด, ตอนสลับ session และทุกครั้งที่ reconnect; (5) ส่งขณะออฟไลน์
  ค้างเป็น pending แล้ว flush ตอน reconnect; (6) บั๊กจริงที่เจอตอนเทสต์ tunnel:
  cloudflared ต้องการ `--metrics 127.0.0.1:0` ไม่งั้น resolve `localhost`
  ไม่ได้แล้ว tunnel ตายเงียบ (error 1033) — แก้ใน `bridge/tunnel.go` + mock.

- AXCH-006 (2026-09-23) — "ใช้ DB จริง + ห้ามฝังของลับในโค้ด": (1) D1 จริงถูก
  provision แล้ว (`aixodia`, account 3b953d1c…, id e8e746ea-…f29a1) และลง
  schema ครบ 5 ตารางผ่าน wrangler — ไม่มี mock เป็น production store; (2) ถอน
  ค่า default ทั้งหมดออกจากแอป (DB URL/WS URL/token/session ว่างเปล่า แอปใหม่
  ขึ้นหน้า "ยังไม่ได้ตั้งค่า") และให้ผู้ใช้กรอกเป็น runtime config; (3) secret อยู่
  นอก git เท่านั้น: `AIXODIA_TOKEN` = Worker secret, Cloudflare API token =
  env var ของ shell, `config/aixodia.example.json` + `.dev.vars.example` เป็น
  ตัวอย่างเท่านั้น (gitignore ครอบไฟล์จริง); (4) daemon อ่านค่าจาก
  `bridge.LoadConfig()` (ไฟล์ JSON/env, มีเทสต์) แทน hardcode; (5) CI เพิ่ม
  `go test` ของ bridge. สิทธิ์ token ที่ยังขาดและต้องเพิ่ม: Account →
  Workers Scripts: Edit (deploy/secret) + Account Settings: Read (workers.dev).

- AXCH-007 (2026-09-24) — Production DB live: จอง workers.dev subdomain
  `aixodia` + deploy Worker `aixodia` (DB binding → D1 `aixodia`) + ตั้ง
  `AIXODIA_TOKEN` เป็น Worker secret; ทดสอบจริงผ่าน public URL (401 ไม่มี token,
  create/list session, เขียน-อ่าน turn ที่มี `agent`/`job_id`, heartbeat ผ่าน
  `/api/node`). เพิ่ม `-worker-base` / `-worker-token` ให้ mockai เขียนลง
  D1 จริงแบบ FIFO (พบบั๊กจริง: mirror แบบ goroutine ต่อ turn ทำให้ seq ซ้ำกัน
  แอปแสดงผิดลำดับ — แก้เป็นคิวเดียว + เทสต์ยืนยันลำดับ user → main → sub →
  main ใน D1 จริง) และแยก credential: local WS token ≠ Worker token.

- AXCH-008 (2026-09-24) — "ไม่เอา token ใดๆเพิ่ม" + verify 2 ขั้น: ระบบมี
  token อันเดียวคือ D1 access token แอปส่งเป็น `Authorization` header ตอน
  handshake (ไม่มี token ใน frame แล้ว) daemon ตรวจกับ Worker `GET /api/ping`
  ก่อน upgrade จึงไม่มี socket ให้คนไม่มีสิทธิ์; ตัด `AIXODIA_NODE_TOKEN` +
  `AIXODIA_STATE_KEY` + โค้ดเข้ารหัสออกทั้งหมด (stateless = daemon ไม่มี
  credential ติดตัว ใช้ token ที่มือถือส่งมาใน RAM อย่างเดียว). lockout:
  ไม่มี header → 401 ไม่นับ, token ผิด 5 ครั้ง → 429 + Retry-After 30 วิ
  แล้ว 60/120/240/300 (เพดาน) นับตาม CF-Connecting-IP ไม่ใช่ hash(token)
  (เทสต์จับช่องโหว่: สลับ token หลุดล็อก), Worker ล่ม → 503 ไม่นับ (fail
  closed). ทดสอบจริงกับ Worker/D1 production แล้ว: 401 → 401×4 → 429 → รอ
  30 วิ → ผ่าน → turn เข้า D1 ครบ 7 แถวเรียงลำดับถูก.
