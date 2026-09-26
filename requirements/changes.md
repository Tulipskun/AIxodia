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

- AXCH-009 (2026-09-24) — UX token: จอตั้งค่ามีปุ่มวาง token จากคลิปบอร์ด และปุ่ม
  "บันทึก (ตรวจก่อน)" ที่ยิง `/api/sessions` ยืนยันคู่ Worker URL + token ก่อน
 บันทึก (ไม่ผ่าน = ไม่บันทึก พร้อมบอกว่า 401 หรือ 404). ยังไม่มี token อื่นเพิ่ม
 และไม่ฝังค่าใด ๆ ในแอป.

- AXCH-010 (2026-09-24) — ตั้งค่าแอปเหลือ 2 ช่อง: **ที่อยู่ + D1 token** (ไม่มี account id,
  ไม่มี provider/ค่าอื่น). ถ้าที่อยู่เป็น tunnel → ประวัติวิ่งผ่าน tunnel เดียวกัน
  (daemon proxy `/api/sessions`, `/api/sessions/:id/turns`, `/api/node`, `/api/ping`
  → Worker ด้วย header ที่แอปส่งมา; `/api/state` ไม่ถูก proxy จึงไม่มีทางอ่าน API keys
  ผ่าน tunnel) และสดที่ `wss://<host>/ws`; ถ้าเป็น Worker URL → ประวัติตรง และค้นหา
  tunnel ผ่าน `/api/node` ให้เอง. เก็บค่าขั้นสูง (แยก WS/Worker, session) ไว้ใต้ปุ่ม
  "ตั้งค่าขั้นสูง" สำหรับกรณีพิเศษ. ทดสอบจริงบนมือถือ: ใส่ URL tunnel เดียว + token
  แล้วต่อได้ และดึงประวัติจาก D1 จริงผ่าน tunnel (MAIN/SUB ครบ, ไม่ crash).

- AXCH-011 (2026-09-24) — session เป็นรายการแชทในแอปเหมือน Gemini/ChatGPT:
  ไม่มีช่อง session id ให้ผู้ใช้กรอกอีก (แอปเปิดแชทล่าสุด หรือสร้างใหม่ให้เอง),
  เมนูแสดงรายการแชทจาก D1, กด = เปิดแชท, long-press = เปลี่ยนชื่อ/ลบ (ลบทั้งเครื่องและ
  D1 ผ่าน `DELETE /api/sessions/:id` ซึ่งลบ turn ด้วย), และ **ลบหน้าตั้งค่าขั้นสูงทิ้ง** —
  เหลือแค่ "ที่อยู่ + D1 token" ตามที่ผู้ใช้ต้องการ. เพิ่ม Worker/mock endpoints
  `PATCH|DELETE /api/sessions/:id` และแก้ allowlist ของ proxy ให้ผ่านได้แต่ยังไม่ให้
  `/api/state`; ทดสอบจริงบนมือถือผ่าน public tunnel: เห็น 4 แชทจาก D1, เปลี่ยนชื่อและ
  ลบแล้ว D1 เปลี่ยนตามจริง.

- AXCH-012 (2026-09-25) — "เพิ่มให้ตั้งค่า provider/model ได้ ปรับการแสดงผลให้ถูกต้อง ปรับเป็นระบบ stream ทั้งหมดเท่าที่เป็นไปได้":
  daemon ส่ง `delta` ต่อชิ้นข้อความจริง (ไม่ใช่ `TraceResponseContent` ที่เป็นทั้งก้อน), ส่งข้อความ authoritative เฉพาะตอนที่ไม่ได้ stream มาแล้ว, `trace` มี tool call/result และ `done` ปิด turn เดียว; harness สั่ง `Stream: true` ทุก turn; adapter `openai` เลือก dialect ตาม endpoint (custom base = gateway → `/chat/completions` SSE, มิฉะนั้น `/responses`) พร้อม fallback; เพิ่ม `GET /api/models` + `PATCH /api/sessions/:id {provider, model}` และ session manager ใช้ค่าที่บันทึกไว้เมื่อเปิดแชท; D1 mirror มี turnMirror กันเขียนซ้ำ. แอป: bubble สด + รายการ tool steps, ตัวเลือก provider/model ในหน้าตั้งค่า, ดึงประวัติจาก D1 เมื่อ turn จบ. ทดสอบจริง  ผ่าน tunnel: 32 delta → done, D1 มี user+model turn พอดี 1 ครั้ง, `/api/models` คืน 6 provider พร้อม model จริง.

- AXCH-013 (2026-09-25) — "การแสดงผลบัค, แก้ไข provider opencode ที่ใช้งานไม่ได้, ทำให้สามารถเพิ่ม provider ได้, ตั้ง api pool / model สำหรับ main agent/sub agent, เพิ่มปุ่มหยุดการทำงาน":
  เพิ่ม AX-083..086 — หน้าตั้งค่าแสดงรายการ provider พร้อมสถานะ/เหตุผลล่าสุด, เพิ่ม provider, ลบ provider, เพิ่ม/ลบ/แทนที่ key ใน pool (ส่งได้แต่ไม่มีทางอ่าน key กลับ), เลือก provider+model ของ MAIN และ SUB แยกกันผ่าน `PUT /api/settings`, และปุ่มหยุดการทำงานบนหน้าแชทที่ส่งเฟรม `cancel` แล้วรอ `done` (ไม่ล้าง busy ตอน `ack`) พร้อม `POST /api/providers/refresh` ที่ probe จริง 1 token เพื่อแสดงเหตุผลจริง. ฝั่ง daemon ตรงกับ CHANGE-066/REQ-048. ผลการรันจริงบนมือถือ: provider ที่ใช้ไม่ได้รายงาน `Invalid API key` (401) และ `FreeTierError` (403) ให้เห็นจริง, NousResearch ผ่าน, AgentRouter 503, cavoti/tokenharbor 402, เพิ่ม-ลบ key ผ่าน REST เดียวกับแอปแล้วจำนวน key กลับมาถูกต้อง, ปุ่มหยุดเปลี่ยนเป็น ▶ และสถานะเป็น "หยุดแล้ว", bubble ไม่ชนแถบพิมพ์; CI เขียว (APK v0.1.38).

- AXCH-014 (2026-09-25) — "UI/UX อย่างแย่ … แล้วก็ตั้ง provider ให้ main/sub ได้ แต่ตั้ง model/api ไม่ได้?":
  เพิ่ม AX-087..088 — แอปมีธีมของตัวเองแทนค่า default ของ Material (สี ink + mint, type scale, shape ramp), หน้าตั้งค่าเป็นหัวข้อย่อยพร้อม status banner เดียว, ตัวเลือก model เป็น bottom sheet พร้อมช่องค้นหาและแท็กความสามารถของโมเดล, provider/โมเดล/ค่า agent ถูกโหลดทันทีที่เปิดหน้า (ก่อนหน้านี้ต้องกดปุ่ม “โหลด provider” ก่อน ไม่งั้น dropdown model ว่างและกดไม่ได้ ทำให้ “ตั้ง provider ได้แต่ตั้ง model ไม่ได้”), ปุ่มของ provider wrap ไม่ล้นบรรทัด, และสถานะ provider เป็น 3 สถานะ (`ยังไม่ทดสอบ` / `ใช้ได้` / `ใช้ไม่ได้` + เหตุผลจริง) แทนการรายงานว่า provider ที่ยังไม่เคยทดสอบว่า “ใช้ได้”. ฝั่ง daemon ตรงกับ CHANGE-067 (`probed` ใน `GET /api/providers`, เปลี่ยน key แล้วกลับเป็นยังไม่ทดสอบ). ผลจริงบนเครื่องจริง (CI เขียว, APK v0.1.44): หน้าแชกับหน้าตั้งค่าใช้ธีมใหม่, โหลด 6 provider/8 key เอง, เลือก model ของ main/sub ได้จากชีตที่ค้นหาได้, กด “ทดสอบใหม่” แล้วขึ้น “5 provider ใช้ไม่ได้” พร้อมเหตุผลจริงจาก provider (เช่น 503 ของ AgentRouter).

- AXCH-015 (2026-09-25) — ผู้ใช้ชี้ว่า “ไม่ได้บอกให้เปลี่ยน theme” — ปัญหาคือ UX ไม่ใช่สี:
  เพิ่ม AX-089 และแก้ตามจริงบนเครื่อง — หัวแชทแสดงโมเดลที่แชทนั้นใช้จริง (แตะเพื่อเปิดชีต provider/model, preset จากค่าที่บันทึกไว้, แชทที่ไม่ล็อกโมเดลบอกว่า “ใช้ค่าของ agent” แทนการโชว์ค่าที่ไม่ได้เลือก), ต่อ daemon ไม่ได้เป็นแบนเนอร์พร้อมปุ่ม “ลองใหม่” (เคยเป็นตัวอักษรเล็ก ๆ ที่กู้เองไม่ได้), กดค้างข้อความเพื่อคัดลอกพร้อมยืนยัน, ช่องเพิ่ม key มีปุ่มวางจากคลิปบอร์ด, “ลบ key” ให้เลือกว่าตัวไหนแทนการเดาว่าท้ายสุด (key ไม่เคยถูกส่งกลับ จึงเลือกได้แค่ลำดับ), และทดสอบ provider เดี่ยวได้โดยไม่ต้องรอทั้งลิสต์ (`POST /api/providers/{id}/refresh`). ผลจริง (CI เขียว, APK v0.1.50): banner ต่อไม่ได้ขึ้นพร้อมปุ่มลองใหม่และกดแล้วกลับมาออนไลน์จริง, ชีตโมเดลเปิดได้จากหัวแชทโดยไม่ตีความว่าเลือกไว้แล้ว.

- AXCH-016 (2026-09-25) — "ทำต่อให้เสร็จ" (ต่อจาก AX-089): เพิ่ม AX-090 — การ์ด provider
  แสดง `ตอบได้จริง: <model>` จาก `working_model` ที่ daemon รายงาน และข้อความผลกด
  "ทดสอบ" บอกชื่อโมเดลที่ตอบ (ก่อนหน้านี้บอกแค่จำนวนโมเดลในแคตตาล็อก ซึ่ง gateway
  บอกมาเองและไม่ได้แปลว่ามีอะไรตอบได้จริง) พร้อมกันนี้ daemon เปลี่ยนข้อความ 401/403/429
  ให้เป็น "ถูกปฏิเสธ/โควตาหมด" และหยุดถามโมเดลถัดไปทันที (ตรงกับ CHANGE-069/REQ-048(9)):
  ผลจริงบนเครื่องจริง — กดทดสอบ Opencode แล้วขึ้น "ผู้ให้บริการปฏิเสธคำขอนี้ (403) — เช่น
  free tier ที่ใช้ได้เฉพาะในตัว client ของผู้ให้บริการ" แทนการเดินทดสอบ 3 โมเดลแล้ว
  สรุปว่าใช้ไม่ได้ เพราะ OpenCode Zen ปฏิเสธทันทีเมื่อคำขอถูกนับเต็มโควตา.

- AXCH-017 (2026-09-25) — ผู้ใช้กด "ทดสอบใหม่" แล้วเห็น "ทุก provider ใช้งานได้" ทั้งที่รายการ
  provider ว่างสนิท: เพิ่ม AX-091 — `load()` ไม่กลืน error แล้วเปลี่ยนรายการเป็นว่างอีกต่อไป
  แต่คงของเดิมไว้และขึ้นข้อความว่าโหลดไม่สำเร็จพร้อมเหตุผล, ปุ่ม "ทดสอบใหม่" ไม่สรุปว่า
  "ทุก provider ใช้งานได้" ถ้า daemon ไม่ได้รายงาน provider เลย, ปุ่มทดสอบ provider เดี่ยว
  จับ error แล้วรายงานแทนที่จะหลุดออกจาก coroutine, และ OkHttp ใช้ timeout ที่รอ provider
  check ได้จริง (connect 20s / read 90s / call 150s) เพราะค่า default read 10s ตัดคำขอ
  ทิ้งกลางคัน. ฝั่ง daemon ตรงกับ CHANGE-070/REQ-048(10) (ตรวจ provider พร้อมกันทีละ 4 ตัว
  ภายใต้งบ 25 วินาทีต่อตัว) ผลจริงบนเครื่องจริง: ก่อนแก้ กดแล้วรายการหายและข้อความโกหก;
  หลังแก้ รายการยังอยู่และข้อความตรงกับคำตอบของ daemon.

- AXCH-018 (2026-09-25) — "การแสดงผลแชทควรแสดงเต็มหน้าจอ แสดง footer สำหรับ
  token/model/เวลาที่ใช้/การแสดง Animation/การแสดงการทำงานของ sub agent/การหยุด sub agent
  และเพิ่มแสดง token/s แบบ realtime": เพิ่ม AX-092/AX-093 — แชทเต็มจอ (Scaffold ไม่ inset
  ซ้ำ, thread ใช้ทั้งหน้าต่าง), progress bar + ป้าย "กำลังตอบ" ที่เต้นเบา ๆ ระหว่างทำงาน,
  footer ใต้ช่องพิมพ์แสดง `provider · model · token · เวลา · tok/s` (ตัวเลขที่กำลัง stream
  เป็นค่าประมาณและขึ้น `≈` แต่พอ provider รายงาน usage จริงก็เปลี่ยนเป็นตัวเลขจริงทันที), และแผง
  "sub agent" หนึ่งแถวต่อ worker พร้อมปุ่ม "หยุด" เฉพาะตัว (ส่ง `cancel` ที่มี `job_id`;
  ปุ่มหยุดเดิมยังหยุดทั้ง turn). ฝั่ง daemon ตรงกับ CHANGE-072/REQ-048(11): `cancel` รับ
  `job_id`, `sdk.Agent.StopSubAgent` ยกเลิกเฉพาะ job นั้นโดยไม่หยุด turn และ adapter อ่าน
  `usage` จาก stream (ขอ `stream_options.include_usage` + รับ chunk ปิดท้ายของ
  chat/completions และ `response.completed` ของ Responses API) เพื่อให้ตัวเลข token เป็น
  ของจริง ไม่ใช่การเดา.

- AXCH-019 (2026-09-25) — เพิ่ม AX-094: ชีตโมเดลยกโมเดลที่ `default_model` (ตัวที่
  health check ได้คำตอบจริง) ขึ้นหัวสุดพร้อมแท็ก "ตอบได้จริง" และ footer ของแชทที่ไม่ได้ล็อก
  โมเดลเขียนว่า "ตามค่าของ agent" แทนการไปแสดงค่า route ปัจจุบัน (ซึ่งอาจเปลี่ยนไปแล้ว
  หลัง turn จบ แล้วเอาไปติดกับตัวเลข token ของ turn ก่อนหน้า). เจอตอนทดสอบจริงบนเครื่อง:
  เลือก provider แล้วรายการโมเดลยังเรียงตามแคตตาล็อก ทำให้ต้องเลื่อนหาโมเดลที่ใช้ได้จริง.

- AXCH-020 (2026-09-25) — "ไม่ต้อง mock แล้ว ลบออกไปเลย": ลบ `mock/` (mock DB REST +
  mock agent WS + `aiclient` CLI, Go) และ `bridge/` (สำเนา `mobile_ws.go`/`tunnel.go`
  ของ daemon ที่มีอยู่จริงใน `Tulipskun/ai-engine/transport/mobile/` แล้ว) ออกจากโปรเจค พร้อม
  ตัด Go toolchain + 2 test steps ออกจาก CI, แก้ README/worker README/AX-001/AX-060
  ให้ชี้ที่ daemon ตัวจริง และเปลี่ยนวิธีทดสอบเป็น daemon ตัวจริง + `wrangler dev`
  (เหตุผล: โปรเจคนี้คือ frontend + Worker ที่เขียน Kotlin ทั้งหมด การมี implementation
  ที่สองของสัญญาเดียวกันแยกภาษาคือแหล่งที่ drift และไม่ได้ใช้งานจริงแล้ว).

## CHANGE-075: footer อยู่ใต้ข้อความ และ thread เป็นเอกสารเต็มจอ
New: ย้ายตัวเลขจากแถบ global เหนือช่องพิมพ์ไปอยู่ใต้ข้อความคำตอบของแต่ละข้อความ (AX-095)
และเลิกใช้กล่อง bubble — ข้อความเต็มความกว้าง ตัวอักษรเป็น Markdown (AX-096) ของ
model message รับ `model`/`input_tokens`/`output_tokens`/`duration_ms` จากเฟรมปิด
ของตัวเอง ซึ่ง daemon อ่านจาก trace (ตาม REQ-033 ไม่ใช่เวลาที่วาด) และเก็บลง D1 ผ่าน
คอลัมน์ใหม่ `turns.model/input_tokens/output_tokens/duration_ms`
(`worker/migrations/0002_turn_footer.sql` สำหรับฐานที่มีอยู่แล้ว) Room เป็น v3
(`MIGRATION_2_3` เพิ่ม `model`, `durationMs`) และเขียน `MarkdownText.kt` เองเพราะ
โปรเจคไม่มี dependency ด้าน markdown และต้องรับข้อความที่ยังพิมพ์ไม่จบ
(`**` ที่ยังไม่มีคู่, ``` ที่ยังไม่ปิด) ให้แสดงผลได้ ไม่กะพริบ พร้อมแก้ model sheet ที่ราย
โมเดลแรกถูก nav bar บังจนกดไม่โดน
Reason: ผู้ใช้ต้องการอ่าน thread เป็นเอกสาร และ footer ต่อ turn เดียวทำให้โมเดลที่ตอบ
จริงกับตัวเลขไม่ตรงกันเมื่อคุยกันหลายโมเดล
Impact: app `ui/chat/ChatScreen.kt` (MessageBlock, MessageFooter, TurnStatsLine,
LiveAnswer, ToolSteps, model sheet), `ui/chat/MarkdownText.kt` (ใหม่),
`ui/chat/ChatViewModel.kt` (recordUsage ใช้ model/duration ของเฟรม),
`data/model/ChatModels.kt`, `data/local/AppDatabase.kt` (v3), `data/repo/
ChatRepository.kt`, `data/remote/HistoryApi.kt`, `worker/schema.sql`,
`worker/migrations/0002_turn_footer.sql`, `worker/src/jsMain/kotlin/aixodia/
Worker.kt`, requirements/functional.md (AX-095, AX-096)
Validation: `gradle assembleDebug` ใน CI; จริงบนมือถือ: ส่ง "ping" ด้วย nemotron-3-ultra-free
แล้วเห็น footer ผูกกับข้อความนั้น และเปิดแชทใหม่แล้วตัวเลขยังอยู่
Status: accepted

- AXCH-021 (2026-09-26) — "กด back จากตั้งค่ากลับหน้าแชท ไม่ใช่ออกจากแอป + จัดหมวด provider/model session/global + key add/remove/edit + token/cache/stream/tool/reasoning ให้ถูก":
  เพิ่ม AX-097..AX-099 และแก้ AX-080/081/084/085/089/095 — หน้าตั้งค่าเป็นสาขาของหน้าแชท
  (ปุ่มย้อนบน toolbar และปุ่ม back ของระบบกลับหน้าแชทเสมอ), ชีตแชทล็อก/ล้างโมเดลเฉพาะแชท
  (`PATCH {"clear_model":true}` ล้าง pin ใน D1 และลืม session สดให้ turn ถัดไปใช้ค่าเริ่มต้นสากล),
  หน้าตั้งค่าเป็นเจ้าของ provider/key pool/ค่าเริ่มต้น main/sub สากล, key เป็น write-only จึงไม่มี
  edit ค่าเดิม (เพิ่ม/ลบตามลำดับพร้อมยืนยันซ้ำ/แทนที่ทั้ง pool) และ provider สร้างแล้วเปลี่ยนตัวตนไม่ได้;
  footer เก็บ `cache_read/cache_write` พร้อม in/out/duration, streaming ประมาณมี `≈`, tool มี
  ชื่อ/สถานะ/args/เวลา, reasoning เป็นตัวจับเวลาชั่วคราวโดยไม่บันทึกเนื้อหา. ฝั่ง daemon ตรงกับ
  CHANGE-077/D-012.

- AXCH-022 (2026-09-26) — "turn ok แต่คำตอบไม่ขึ้น thread": เจอสาเหตุสองชั้น —
  (1) user mirror วิ่งแข่งกับ answer mirror ทำให้ D1 ได้ seq สลับกันได้ (แก้ฝั่ง daemon
  ด้วย gate ต่อ session ใน CHANGE-081) และ (2) กรณีนี้ session id `warm4` ถูกใช้ซ้ำ
  (ลบแล้วสร้างใหม่) ทำให้ประวัติเก่าในเครื่องกับประวัติใหม่ใน D1 ชนกันทุก seq —
  reconcile แบบเดิมปฏิเสธการ merge อย่างถูกต้อง (กันข้อมูลหาย) แต่ผลคือคำตอบใหม่ไม่ขึ้น
  จอ วิธีแก้: ถ้าหน้าที่ pull มาไม่ตรงกับของในเครื่องเลยสักแถว ถือว่า session เกิดใหม่
  แล้วรับของ D1 ทั้งชุด (เก็บเฉพาะ pending ที่ D1 ยังไม่มี) พร้อมกันนี้พิสูจน์บนเครื่องจริงว่า
  footer cache ทำงานครบสาย (`· cache 63424` จาก `cached_tokens` ของ provider) และ
  per-message footer/model/markdown เต็มจอถูกต้องทุกแถว

- AXCH-023 (2026-09-26) — "ฉันไม่ได้ต้องการใช้ cloudflare worker แต่จะยิง api อ่าน/เขียน โดยตรง":
  ลบ Cloudflare Worker ออกจากโปรเจค (โฟลเดอร์ `worker/` ทั้งหมด รวม Worker secret
  `AIXODIA_TOKEN`, `wrangler.toml`, Worker ที่เขียนด้วย Kotlin) แล้วให้แอปอ่าน/เขียน
  Cloudflare D1 ผ่าน **D1 REST API ตรง**
  (`POST https://api.cloudflare.com/client/v4/accounts/{account}/d1/database/{database}/query`)
  ด้วย Cloudflare API token ตัวเดียวกับที่แอปถืออยู่แล้ว — account id และ database id
  resolve จาก token เอง (`GET /user/tokens/verify` → `GET /accounts` →
  `GET /accounts/{id}/d1/database`) ไม่ต้องพิมพ์ ยกเว้นกรณีที่ token มองเห็นหลาย
  account/database หน้า Settings จึงเปิดให้แก้ด้วยมือ.
  Reason: daemon ยิง Cloudflare API ตรงอยู่แล้วตั้งแต่ CHANGE-081/REQ-046 จึงเหลือ Worker
  เป็นชั้นกลางที่มีหน้าที่แค่ history + discovery ผลคือประวัติอ่าน/เขียนไม่ได้เมื่อ daemon ปิด
  และมี secret เพิ่มอีกตัวที่ต้อง rotate; ผู้ใช้เลือกตัด Worker ออกและให้มือถือคุย D1 ตรง (D-011,
  supersede D-008).
  Impact: app — `data/remote/D1Api.kt` (ใหม่: Cloudflare D1 REST client + SQL ของ
  sessions/turns/node), `data/remote/HistoryApi.kt` (sessions/create/rename/delete/turns/ping/node
  เปลี่ยนไป D1 ตรง ส่วน models/providers/settings/key pool ยังผ่าน tunnel ไป daemon เพราะต้องใช้
  router ที่รันอยู่), `SettingsStore.kt` (เพิ่ม `account_id`/`database_id`; `worker_url` →
  `daemon_url`; resolve/describe ใหม่เป็น D1 + tunnel), `ui/settings/SettingsScreen.kt`
  (ช่อง token + account/database ที่ค้นหาอัตโนมัติ + ปุ่มค้นหา ai จากแถว `nodes` ใน D1),
  `data/repo/ChatRepository.kt` (คอมเมนต์แหล่งข้อมูล), `README.md`, `.gitignore`,
  `config/aixodia.example.json`, `.github/workflows/worker.yml` (ลบ CI ของ Worker),
  `db/schema.sql` + `db/migrations/0002_turn_footer.sql` + `db/README.md`
  (ย้าย/เพิ่มจาก `worker/`),
  requirements — AX-010/011/014/030/050/051/052/060/071/073, AXC-003, product.md, decisions.md (D-011);
  ฝั่ง daemon `Tulipskun/ai-engine`: CHANGE-082 (แก้เอกสารกับคอมเมนต์ที่ยังบอกว่าตรวจ token กับ Worker).
  Validation: `gradle assembleDebug` ใน CI; ยืนยันว่าไม่มี Worker เหลือในรีพ;
  เปิดแชทเก่า + โหลด page ของ turns ได้ด้วย token ตัวเดียวขณะ daemon ปิด;
  และ `wrangler d1 execute aixodia --file=db/schema.sql` ยังใช้ตั้ง schema ได้โดยไม่ต้องมี Worker
  Status: accepted

- AXCH-024 (2026-09-26) — "ทำให้ปุ่มอัพเดทใช้งานได้จริง": ปุ่ม "ตรวจอัปเดต" รายงาน
  "ล่าสุดแล้ว" ตลอดทั้งที่มี release ใหม่ เพราะ `UpdateRow` ส่ง Cloudflare API token
  (credential ตัวเดียวของแอปตั้งแต่ D-011) เป็น `Authorization: Bearer` ไปหา
  `api.github.com` — GitHub ตอบ 401, `UpdateManager.check()` คืน null.
  พิสูจน์ด้วย curl: ไม่ส่ง auth ได้ 200 (repo เป็น public แล้ว), ส่ง Bearer มั่วได้ 401.
  New: `check()`/`download()` ไม่ส่ง auth เลย, เอา parameter token ออกทั้งสองจุดเรียก
  (Settings + drawer), ลบ import `flow.first` ที่ไม่ใช้, แก้คอมเมนต์ "repo is private"
  ที่ตกรุ่น และ AX-041.
  Impact: `update/UpdateManager.kt`, `ui/settings/SettingsScreen.kt`,
  `ui/chat/ChatScreen.kt`, requirements (functional AX-041 + changes).
  Validation: `./gradlew assembleDebug`; เทสต์บนเครื่องจริง — ติดตั้ง release เก่า
  (stable signature) แล้วกดปุ่ม ต้องเจอ release ล่าสุด โหลด APK และติดตั้งทับได้
  ข้อมูลแชทไม่หาย.
  Status: accepted
