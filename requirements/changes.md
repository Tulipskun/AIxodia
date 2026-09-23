# Changes (AXCH-xxx, append-only)

- AXCH-001 (2026-09-23) — Initial spec from user request (Thai): "Android app .kt
  แสดงผลจากโปรเจกต์ ai โดยรับข้อมูลจาก ai โดยตรง + โหลดข้อมูลเก่าจาก database
  cloudflare + cache ไว้ คล้าย message/discord/telegram". Surveyed GitHub first:
  no AIxodia repo (name free), `ai` has only CLI+Discord transports and SQLite
  per-session DB, no Cloudflare D1/KV/R2 anywhere, Droid-MCP is the Kotlin
  reference. User then chose private + D1+Worker + WebSocket direct. This spec
  and scaffold implement exactly that. No conflicts (greenfield repo).
