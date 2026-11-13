# Auth System

สถานะ: implement แล้ว (Worker `aixodia-auth`, engine sync, แอป `DeviceFlow.kt`)
ขอบเขต: flow นี้ใช้กับทุกทางเข้า — แอปและเว็บคอนโซลออก token จากชุดเดียวกัน
(token ที่เว็บได้จาก `/auth/token` หลัง Access ก็ผ่าน `activate` เข้า engine เหมือนกัน)
flow PKCE เดิม (`/v1/auth/authorize|exchange`) กลายเป็น legacy ลบทิ้งเมื่อ flow นี้ขึ้นแทน

## Flow

1. แอปเรียก `POST /auth/device/code` ได้ `device_code` (ลับ), `user_code` (เช่น `WXYZ-2041`), `interval`, `expires_in` 10 นาที
2. แอปแสดง `user_code` และเปิดเว็บ `/auth/device?code=...`
3. เว็บอยู่หลัง Cloudflare Access (GitHub) ผู้ใช้ตรวจรายละเอียดเครื่องแล้วกด Approve
4. แอป poll `POST /auth/device/token` จนได้ `access_token` ครั้งเดียว
5. แอปส่ง `Authorization: Bearer <token>` ทุก request

## กฎ

- Server (Worker) เป็นผู้ออก token เท่านั้น
- Token สุ่ม 256 บิต **ไม่มีวันหมดอายุ** หมดได้เฉพาะ revoke
- อีเมลไม่ถูกส่งหรือใช้ใน login
- Device code ใช้ได้ครั้งเดียว หมดใน 10 นาที
- เก็บ token ใน D1 **แบบ hash (SHA-256)** — ค้นหาด้วย hash ต้นทุนเท่าเดิม
  และ Worker มี pattern นี้อยู่แล้ว ไม่มีเหตุผลต้องเก็บ plaintext
  (approve page ไม่มีโอกาสต้องแสดง token ซ้ำ)
- `/auth/device/token` จำกัดต่อ IP — `user_code` มีพื้นที่ค้นหาไม่กี่ล้าน
  ไม่งั้น brute force ได้; ตอบ `authorization_pending` / `slow_down`
  ตาม RFC 8628 ให้แอปถอย backoff
- Engine ↔ Worker ยืนยันกันด้วย shared secret ใน header
  (จุดสิ้นสุด sync ต้องป้องกัน ไม่ใช่ public)

## Sync กับ Engine

- Worker push `activate(token)` และ `revoke(token)` ไปที่ engine ทันที
- push ไม่สำเร็จ (engine offline) → ระบุแถวว่า `sync_pending`
  **engine ต้อง re-sync เป็นระยะ (เช่นทุก 60 วิ)** — ไม่งั้น token ที่ออก
  ตอน engine ล่มจะใช้ไม่ได้ตลอดกาล ทั้งที่ Worker บอกว่า active
- ตอน engine เริ่มทำงาน ดึงชุด token ที่ active ทั้งหมดจาก Worker ก่อนรับ request
- ระหว่างยังดึงไม่สำเร็จ engine ปฏิเสธทุก token (fail closed)
- revoke ตอน engine offline ไม่ต้อง retry — startup pull ตัดทิ้งเอง

## Endpoints

| Method | Path | ใครเรียก |
|---|---|---|
| POST | `/auth/device/code` | แอป |
| POST | `/auth/device/token` | แอป |
| GET | `/auth/device` | เว็บ (Access) |
| POST | `/auth/device/approve` | เว็บ (Access, CSRF) |
| GET | `/auth/sessions` | เว็บ (Access) |
| POST | `/auth/revoke` | เว็บ หรือแอป |
| POST | `/auth/sessions/sync` | Worker → engine |
| GET | `/auth/sessions/active` | engine → Worker |

## ความเสี่ยงที่ยอมรับ

- ถ้าเขียน D1 ได้ สร้าง session ได้เอง คุม credential ของ Cloudflare ให้เข้มงวด
- Token อยู่ในหน่วยความจำ engine ด้วย — dump หน่วยความจำ = token รั่ว
- Token ไม่มีวันหมดอายุ — revoke คือกำแพงเดียว หน้า sessions ต้องหาใช้ง่าย

## ที่เก็บในแอป

- `EncryptedSharedPreferences` คีย์ใน Keystore
- ไม่ backup, ไม่ log header
