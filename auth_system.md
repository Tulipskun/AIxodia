# Auth System

สถานะ: ร่าง ยังไม่ implement

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

## Sync กับ Engine

- Worker push `activate(token)` และ `revoke(token)` ไปที่ engine ทันที
- ถ้า engine offline ไม่ retry
- ตอน engine เริ่มทำงาน ดึงชุด token ที่ active ทั้งหมดจาก Worker ก่อนรับ request
- ระหว่างยังดึงไม่สำเร็จ engine ปฏิเสธทุก token

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

- เก็บ token ใน D1 ไม่ hash ถ้า D1 อ่านหลุด token ใช้ได้ทันที
- ถ้าเขียน D1 ได้ สร้าง session ได้เอง คุม credential ของ Cloudflare ให้เข้มงวด
- Token อยู่ในหน่วยความจำ engine ด้วย

## ที่เก็บในแอป

- `EncryptedSharedPreferences` คีย์ใน Keystore
- ไม่ backup, ไม่ log header
