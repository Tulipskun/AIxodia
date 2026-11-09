# task.md — Handoff tracker

> **กฎเหล็กจาก operator:** ทุกครั้งที่มีการเปลี่ยนแปลงไฟล์ใดๆ (รวมไฟล์นี้) ต้อง `git add` → `commit` → `push` **ทันที**
> เหตุผล: อาจมี AI ตัวอื่นมารับช่วงต่อหาก session นี้ติดลิมิต — ห้ามมี work ที่ยังไม่ถูก push ค้างอยู่
> **MAINTAIN:** อัปเดตไฟล์นี้ทุก checkpoint ให้บอกสถานะล่าสุด / สิ่งที่ทำไปแล้ว / ขั้นต่อไป

## Handoff (EN — read this first)

**Status:** `PARTIAL DECISION RECEIVED / WORKING ASSUMPTION PENDING CONFIRMATION / NO CODE CHANGED`
**Decisions:** both clients direct; engine issues a short-lived token instead of exposing `CF_TOKEN`. Exact worker scope still unconfirmed — see *Operator decisions*.
**Goal:** Remove the worker middleman so engine and client talk directly.
**Hard constraint:** commit + push after EVERY change; no unpushed local work, ever.
**Repos (all `main`, all public, owner `Tulipskun`):**
`Tulipskun/aixodia-engine`, `Tulipskun/AIxodia`, `Tulipskun/aixodia-dashboard`
**Local clones:** `/workspace/aixodia-work/<repo-name>`
**Auth:** `gh auth status` OK as `Tulipskun` (OAuth `gho_…`, scopes repo/read:org/gist); per-repo git identity set to `Tulipskun <Tulipskun@users.noreply.github.com>`.
**Blocker:** "worker" is ambiguous — see *Decision needed*. Do NOT guess; ask the operator.
**Repo conventions:** `AGENTS.md` requires updating `MAIN.md` in the SAME turn as any non-read/search tool call. `MAIN.md` must stay under 60 lines.

## เป้าหมาย (จาก operator)

ใน `aixodia-engine`, `AIxodia`, `aixodia-dashboard`: เอา **worker** ออก ให้ **engine กับ client คุยกันโดยตรง** และสร้าง `task.md` ลง repo พร้อม **commit + push ทุกครั้ง** เพื่อให้ AI ตัวอื่นสานต่อได้หากติดลิมิต

## ผลสำรวจ (ภาพรวม 3 repo)

| ความหมายของ "worker" | ที่อยู่ | สถานะในเส้นทางจริง |
| --- | --- | --- |
| A. Cloudflare Worker + Durable Object (stale) | `aixodia-engine/relay/` | ไม่ได้ deploy แล้ว (`rust/MILESTONES.md:34-36`) |
| B. Cloudflare Worker ที่เป็นตัวกลางจริง | `aixodia-dashboard/worker/` | **อยู่ในเส้นทาง**: browser → `/api/*` → Worker → engine |
| C. ผลิตภัณฑ์ Cloudflare ที่ไม่ใช่ Worker script | Workers KV `AIXODIA_CONFIG`, D1 `aixodia`, Named Tunnel | พึ่งพาอยู่ทุกเส้นทาง |
| D. OS thread / agent label | backup crates, `agent == "worker"` ใน UI | ไม่ใช่ network ไม่ต้องแตะ |

**ข้อค้นพบที่ขัดสมมติฐาน:** ฝั่ง Android **ไม่ได้ผ่าน Worker อยู่แล้ว** — `AIxodia/README.md:9` (D-011), `PRODUCT.md:25,46` ยืนยันว่า client ค้น URL จาก Workers KV แล้วยิง `https://aixodia.dpdns.org` (Named Tunnel) ไป engine `127.0.0.1:8787` ตรงๆ

## Repo-specific findings — AIxodia (Android client)

- stack: Kotlin + Jetpack Compose, Room, OkHttp/Retrofit/Moshi, Gradle, `namespace com.tulipskun.aixodia`, minSdk 26 / targetSdk 35
- entry: `AndroidManifest.xml:19-27` → `MainActivity`; `AixodiaApp.kt:29-42` ประกอบ `RunSocket`, `HistoryApi`, `DaemonDiscovery`, `ChatRepository` (คอมเมนต์ `:35-38`: "No shared socket: chat is POST + SSE, terminal is its own WebSocket")
- **เส้นทางปัจจุบัน (ตรงอยู่แล้ว ไม่มี Worker):**
  `CF_TOKEN` (AES-GCM/Keystore) → `D1Api.kt:243-309` อ่าน KV `AIXODIA_CONFIG` คีย์ `daemon/url`, `daemon/version` → `DaemonDiscovery.kt:62-86` poll 30s → base `https://aixodia.dpdns.org` → Named Tunnel → engine `127.0.0.1:8787`
- endpoints: `POST /v1/runs` (`RunSocket.kt:351`), `GET /v1/runs/{id}/events?after=&poll=1` (`:442`, polling ไม่ใช่ SSE เพราะ tunnel buffer), `POST /v1/runs/{id}/cancel` (`:204`), `/v1/sessions`, `/v1/terminals/ticket` → `wss://…/terminal?ticket=` (`TerminalSocket.kt:1187-1199`), `/api/files*`, `/api/backup*`
- auth: `Authorization: Bearer CF_TOKEN` ทุก call; terminal ใช้ single-use ticket 90s
- คำว่า "worker" ที่เจอใน repo นี้เป็น **agent label ใน UI** (`ChatModels.kt:232,311`, `MessageComponents.kt:162,518`, tests) — **ไม่ใช่ network อย่าลบ**
- ถ้าตัด Cloudflare ทั้งระบบ: ต้อง rewrite `D1Api.kt` + `DaemonDiscovery.kt` (discovery), `db/` (D1 history → local DB), และ `PRODUCT.md:46` ห้ามเพิ่ม token ที่สองโดยไม่ได้รับอนุมัติ
- เอกสาร/CI: `README.md:8-9,34-43,53-86`, `PRODUCT.md:25,46`, `db/README.md:1,18-21`, `.github/workflows/release.yml:48-66`

## Decision needed (ห้ามเดา)

| ทางเลือก | ขอบเขต | ผลกระทบ |
| --- | --- | --- |
| 1 | ลบเฉพาะ `aixodia-engine/relay/` | ต่ำมาก — เป็น stale code/docs |
| 2 | ลบ `aixodia-dashboard/worker/` แล้วให้ browser ยิง engine ตรง | สูง — ต้องออกแบบใหม่: SPA hosting, GitHub OAuth/session, CSRF, `CF_TOKEN` ไม่ให้หลุดไป browser, route allowlist, CORS ที่ engine, MCP gateway |
| 3 | ลบทั้งสอง Worker script | รวม 1+2 |
| 4 | ตัด Cloudflare ทั้งระบบ (KV + D1 + Tunnel) | รุนแรงสุด — engine start ไม่ติด, discovery/history/providers หาย, ต้อง rewrite |

ยังต้องระบุด้วยว่า **client** หมายถึง Android อย่างเดียว / dashboard อย่างเดียว / ทั้งคู่

## Operator decisions (received)

- **Client เป้าหมาย:** ทั้งคู่ — Android + Dashboard ต้องชี้ไป engine โดยตรง
- **Auth เมื่อตัด dashboard Worker:** ให้ **engine ออก short-lived token** แทนการเปิดเผย `CF_TOKEN` ต่อ browser (ต้องเพิ่ม endpoint ออก session token ที่จำกัดอายุ/scope ฝั่ง engine)
- **ยังไม่มีคำตอบชัดเจน:** ขอบเขตว่า "worker" คือ `dashboard/worker/` / `engine/relay/` / ทั้งสอง / ตัด Cloudflare ทั้งระบบ
  - **Working assumption (รอยืนยัน):** เริ่มที่ `aixodia-dashboard/worker/` (ตัวกลางจริง) + ลบ stale `aixodia-engine/relay/` เป็น cleanup; **ไม่แตะ** Workers KV / D1 / Named Tunnel
  - ถ้า operator เลือกต่างจากนี้ ให้แก้ assumption นี้ใน 3 repo ก่อน implement ต่อ

## Checkpoint log

| # | สถานะ | สิ่งที่ทำ | Commit |
| --- | --- | --- | --- |
| 1 | ✅ | clone 3 repo, สำรวจสถาปัตยกรรม, สร้าง `task.md` (+ `MAIN.md`), push ทั้ง 3 repo | `engine afd4f94` / `AIxodia ad939f9` / `dashboard e9ffba7` |
| 2 | ✅ | บันทึกคำตอบ operator (client = ทั้งคู่, auth = engine short-lived token) ลง `task.md` + `MAIN.md` | (this commit) |
| 3 | ⏳ | ยืนยันขอบเขต worker กับ operator | — |
| 4 | ⏳ | implement ทีละขั้น + commit/push ทุกขั้น | — |

## Next steps สำหรับ AI ที่รับช่วง

1. อ่าน `task.md` ใน **ทั้ง 3 repo** (เนื้อหาเหมือนกันต่างกันเฉพาะ findings)
2. ถาม operator ให้ชัดเรื่อง *Decision needed* ด้านบน ถ้ายังไม่มีคำตอบ
3. ทำทีละขั้นเล็กๆ: แก้ → update `task.md` (checkpoint) → `git add -A` → `commit` → `git push origin main` **ทุกขั้น**; ใน engine/AIxodia ต้อง update `MAIN.md` พร้อมกันตาม `AGENTS.md`
4. ตรวจทดสอบ: `./gradlew :app:testDebugUnitTest --console=plain` (run under `unshare -Cm` ถ้าเจอ jmod cgroup warning; SDK ที่ `/opt/android-sdk`)
5. อย่า deploy/release โดยไม่มีคำสั่งชัดเจน

## Useful commands

```bash
cd /workspace/aixodia-work/AIxodia
git status --short --branch
git add -A && git commit -m "task: <step>" && git push origin main
```
