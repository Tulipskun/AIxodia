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
