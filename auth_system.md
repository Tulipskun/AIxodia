# Auth System: Device Authorization (แบบ gh CLI)

สถานะ: ร่างสเปค ยังไม่ได้ implement

## เป้าหมาย

ให้ Android App ได้ token ไปใช้เรียก Engine โดยผู้ใช้แค่ยืนยันบนเว็บครั้งเดียว ไม่ต้องกรอก token หรือรหัสในแอป
**Server (web) เป็นผู้ออก token เท่านั้น** แอปไม่ได้สร้างหรือเห็น secret ใดนอกจาก token ที่ server ส่งให้

## หลักการ

- ตัวตนจริงมาจาก **GitHub ผ่าน Cloudflare Access** บนหน้าเว็บเท่านั้น
- แอปไม่ส่งอีเมลหรือข้อมูลผู้ใช้ใด ๆ ไปยัง server ตอนขอ device code
- Token ไม่มีวันหมดอายุ ยกเว้นผู้ใช้ revoke

## ส่วนประกอบ

| ส่วน | หน้าที่ |
|---|---|
| แอป Android | ขอ device code, แสดง user code, เปิดเบราว์เซอร์, poll จนได้ token, เก็บ token |
| Auth Worker (`workers/auth/`) | ออก device code และ user code, ออก token หลังอนุมัติ, เก็บ hash ของ token ใน D1, ตอบ `/auth/verify` |
| Cloudflare Access | ล็อกหน้าอนุมัติด้วย GitHub, กำหนดว่า GitHub account ไหนเข้าหน้านี้ได้ |
| D1 `aixodia` | เก็บ device code และ session (เก็บ token แบบไม่ hash ตามที่ตัดสินใจ ดูหัวข้อความเสี่ยง) |

## ลำดับการทำงาน (เหมือน `gh auth login`)

1. แอปเรียก `POST /auth/device/code` (ไม่ต้องส่งอีเมล)
2. Worker สร้าง:
   - `device_code`: ค่าสุ่มยาว ส่งให้แอปเท่านั้น เก็บเป็น hash
   - `user_code`: รหัสสั้นสำหรับผู้ใช้ เช่น `WXYZ-2041` ใช้ตัวอักษรที่ไม่สับสน (ไม่มี 0/O, 1/I)
   - อายุ 10 นาที, `interval` 5 วินาที
3. Worker ตอบ `device_code`, `user_code`, `verification_uri`, `expires_in`, `interval`
4. แอปแสดง `user_code` และเปิด `verification_uri?code=WXYZ-2041` ในเบราว์เซอร์
5. ผู้ใช้ผ่าน Cloudflare Access (GitHub login)
6. หน้าอนุมัติแสดง user code, เวลาที่ขอ, รุ่นเครื่อง และ IP โดยประมาณ ให้ผู้ใช้กด **Approve** หรือ **Deny**
   - ฟอร์มต้องมี CSRF token
7. เมื่อ Approve Worker บันทึกว่า device code นี้อนุมัติแล้ว และผูกกับ GitHub identity ที่ยืนยันจาก Access JWT
8. แอป poll `POST /auth/device/token` ด้วย `device_code` ทุก `interval` วินาที
   - `authorization_pending`: รอต่อ
   - `slow_down`: เพิ่ม interval
   - `access_denied`: ผู้ใช้กด Deny
   - `expired_token`: หมดเวลา ให้เริ่มใหม่
   - สำเร็จ: ได้ `access_token` **ครั้งเดียว** Worker เก็บเฉพาะ hash
9. แอปเก็บ token แล้วส่ง `Authorization: Bearer <token>` ทุก request
10. Engine ตรวจ token จากชุด hash ที่ active ซึ่งซิงก์มาจาก Worker (ดูหัวข้อการซิงก์) ไม่เรียก Worker ต่อ request

## Token

- ค่าสุ่ม 256 บิต ออกโดย Worker เท่านั้น
- เก็บ token ตรง ๆ ใน D1 ไม่ hash (เป็นการตัดสินใจที่ยอมรับความเสี่ยง ดูหัวข้อความเสี่ยง)
- **ไม่มี `expires_at`** สถานะเปลี่ยนได้ทางเดียวคือ `active → revoked` โดยผู้ใช้
- ลบ `ACTIVE_TTL_SECS` ออกจาก `workers/auth/src/index.js` และไม่ตรวจอายุของ active session
- Device code และ user code หมดอายุได้และใช้ซ้ำไม่ได้ (เป็นเวลาของการยืนยัน ไม่ใช่ของ token)

## การซิงก์กับ Engine

- Worker push การเปลี่ยนแปลงไปที่ engine ทันที: `activate(token)` เมื่อ approve และ `revoke(token)` เมื่อผู้ใช้กด revoke
- Engine เก็บชุด token ที่ active ไว้ในหน่วยความจำ และตรวจ token จากชุดนี้
- ถ้า engine offline ตอน Worker push ไม่ต้อง retry หรือเก็บคิว
- ตอน engine เริ่มทำงาน ต้องดึงชุดเต็มจาก Worker ก่อนรับ request และเช็ค token ทั้งหมดกับชุดนี้ก่อนเสมอ
- ระหว่างที่ยังดึงชุดเต็มไม่สำเร็จ engine ปฏิเสธทุก token แม้ token นั้นเคย active มาก่อน และลองดึงใหม่ได้ตามปกติ

## Endpoints

| Method | Path | ใครเรียก | หมายเหตุ |
|---|---|---|---|
| `POST` | `/auth/device/code` | แอป | ไม่อยู่หลัง Access, rate limit ต่อ IP |
| `POST` | `/auth/device/token` | แอป | ไม่อยู่หลัง Access, poll ด้วย device_code |
| `GET` | `/auth/device` | เบราว์เซอร์ | อยู่หลัง Access, แสดงหน้าอนุมัติ |
| `POST` | `/auth/device/approve` | เบราว์เซอร์ | อยู่หลัง Access, ต้องมี CSRF token, body: user_code และ action |
| `GET` | `/auth/status` | แอป | Bearer, คืน `active` หรือ `revoked` |
| `POST` | `/v1/auth/sessions/sync` (engine) | Worker | master token, body: `{ "op": "activate" \| "revoke", "token": "..." }` |
| `GET` | `/auth/sessions/active` | engine | master token, คืนรายการ token ที่ active ทั้งหมด สำหรับตอน engine เริ่มทำงาน |
| `GET` | `/auth/sessions` | เว็บ | อยู่หลัง Access, รายการเครื่องทั้งหมด |
| `POST` | `/auth/revoke` | เว็บ หรือแอป | เว็บ revoke ได้ทุกเครื่อง, แอป revoke ได้เฉพาะตัวเอง |

ทั้งหมดอยู่ใต้ namespace `/auth/` ให้ตรงกับ `functions/engine/[[path]].js`

## Cloudflare Access

- Access application สำหรับ `/auth/device` และ `/auth/sessions` ใช้ GitHub เป็น identity provider
- Policy กำหนดว่า GitHub account ไหนเข้าได้ (แนะนำใช้ GitHub user ID ตัวเลข)
- **Bypass** สำหรับ `/auth/device/code`, `/auth/device/token`, `/auth/status`, `/auth/verify` เพราะแอปเรียกตรงและใช้ Bearer แทน
- Worker ตรวจลายเซ็นของ `CF-Access-Jwt-Assertion` หรือ cookie `CF_Authorization` ทุกครั้งบน path ที่อยู่หลัง Access และตรวจ audience ไม่ใช้ค่า email จาก body

## ที่เก็บ token ในแอป

- `EncryptedSharedPreferences` โดยคีย์อยู่ใน Android Keystore
- `android:allowBackup="false"` หรือ exclude ไฟล์นี้จาก backup
- ห้าม log header `Authorization`
- Logout เรียก `/auth/revoke` แล้วลบ token ในเครื่องเสมอ แม้เรียกไม่สำเร็จ

## ความเสี่ยงที่ยอมรับ: เก็บ token ไม่ hash

- ถ้า D1 อ่านได้โดยไม่มีสิทธิ์ เช่น dump หลุด ผู้โจมตีใช้ token ที่ active ได้ทันที
- ถ้าผู้โจมตีเขียน D1 ได้ เขาสร้าง session `active` ได้เอง ดังนั้นต้องคุม credential ของ Cloudflare ให้เข้มงวด (ดูหัวข้อ Cloudflare account)
- การป้องกันที่ยังคงอยู่คือ Access policy, การ Approve บนเว็บ, และ token ที่สุ่มยาว 256 บิต

## ความปลอดภัย

- **การ Approve บนเว็บเป็นด่านหลัก** ผู้ใช้ต้องตรวจรายละเอียดเครื่องและ user code ให้ตรงกับแอปก่อนกดเสมอ เพื่อกัน device flow phishing
- **Token เป็นค่าสุ่ม 256 บิต** ความเสี่ยงของการเก็บ token ไม่ hash: ถ้า D1 หรือ dump ของตารางรั่ว ผู้ได้ไปใช้ token ได้ทันที และ token จริงอยู่ในหน่วยความจำของ engine ด้วย (ดูหัวข้อความเสี่ยง)
- **Device code ใช้ได้ครั้งเดียว** และ user code หมดอายุ 10 นาที
- **Rate limit** ที่ `/auth/device/code` ต่อ IP และที่ `/auth/device/token` ต่อ device code
- **Revoke มีผลทันที** เมื่อ engine online เพราะ Worker push `revoke` ทันทีที่ผู้ใช้กด ถ้า engine offline ตอนนั้น ผลจะมีเมื่อ engine เริ่มทำงานและซิงก์ชุดเต็มเสร็จ engine ไม่รับ token ที่ไม่อยู่ในชุด active
- **Token ที่ไม่หมดอายุ** หมายความว่าเครื่องที่หลุดจะใช้ได้จนกว่าจะ revoke จึงต้องมีหน้า `/auth/sessions` ให้เห็นรายการและวันที่ใช้ล่าสุด
- ห้าม log token หรือ device code

## ปัญหาที่ต้องแก้จากโค้ดปัจจุบันใน `aixodia-web`

- `workers/auth/README.md` ระบุ `/v1/auth/request` และ `/v1/auth/confirm` แต่โค้ดจริงมีแค่ `/auth/verify`, `/auth/identity`, `/auth/start`
- `ACTIVE_TTL_SECS = 720 * 60 * 60` ใน `src/index.js` ต้องลบออก
- `functions/engine/[[path]].js` ต้องเพิ่ม path `/auth/device*` และ `/auth/sessions` ให้ไปที่ Worker
- Dashboard ยังมี GitHub OAuth + PKCE กับ engine อยู่ ต้องตัดสินว่าคงไว้หรือไม่

## ยังต้องตัดสินใจ

1. ใครเป็นเจ้าของ GitHub account ที่อนุมัติได้ ตั้งใน Access policy ที่ไหน (แนะนำ user ID ตัวเลข)
2. คง GitHub OAuth + PKCE ของ dashboard ไว้ด้วยหรือไม่
3. หน้า `/auth/sessions` จะอยู่ใน `aixodia-web` หรือแอป
