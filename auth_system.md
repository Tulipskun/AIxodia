# Auth System: Email Gate + Cloudflare Access Session Confirmation

## เป้าหมาย

ให้ Android App ขอ Token จาก Engine ได้เฉพาะเมื่อผู้ใช้กรอก Email ที่อยู่ในรายการ Email ที่อนุญาตไว้แล้ว จากนั้นให้ผู้ใช้เปิดหน้าเว็บผ่าน Cloudflare Access และกด Confirm เพื่อเปิดใช้งาน Token ที่ Engine ออกให้

## Flow

1. Android App แสดงช่องให้ผู้ใช้กรอก Email
2. App ส่ง Email ไปยัง Engine เพื่อขอ Token
3. Engine ตรวจสอบ Email กับรายการ Email ที่อนุญาต (Allowlist)
4. หาก Email ว่าง, รูปแบบไม่ถูกต้อง หรือไม่อยู่ใน Allowlist:
   - ปฏิเสธคำขอ
   - ไม่ออก Token และไม่สร้าง Pending Session
   - ไม่อนุญาตให้ App เข้าถึง API ที่ต้องยืนยันตัวตน
5. หาก Email ผ่านการตรวจสอบ:
   - Engine ออก Token ให้ทันทีในสถานะ `pending`
   - Engine สร้างรายการ Session ที่รอการยืนยัน
   - Engine ส่ง Token และ URL สำหรับยืนยันกลับให้ App
6. ผู้ใช้เปิด URL ใน Browser ซึ่งหน้าเว็บถูกป้องกันด้วย Cloudflare Access
7. หน้าเว็บแสดง Session ที่รอยืนยัน และให้ผู้ใช้กด **Confirm**
8. เมื่อผู้ใช้กด Confirm หน้าเว็บส่งคำขอยืนยันไปยัง Engine
9. Engine เปลี่ยน Token เดิมจาก `pending` เป็น `active` โดยไม่ต้องออก Token ใหม่
10. App ตรวจสอบสถานะ Session จนเป็น `active` แล้วจึงใช้ Token เข้าถึง API ได้

## ขอบเขตหน้าที่

### Android App
- รับ Email จากผู้ใช้และส่งให้ Engine
- เก็บ Token ที่ได้รับอย่างปลอดภัย
- เปิด URL ยืนยันใน Browser
- รอผล Session และอนุญาตการเรียก API เฉพาะเมื่อ Session เป็น `active`

### Engine
- เป็นผู้ตรวจสอบ Email กับ Allowlist ก่อนออก Token
- ห้ามออก Token หรือสร้าง Pending Session เมื่อ Email ไม่ผ่าน
- ออก Token แบบสุ่มที่คาดเดาไม่ได้ในสถานะ `pending` เมื่อ Email ผ่าน
- ผูก Token กับ Pending Session ที่สร้างขึ้น
- แสดง/จัดเตรียมข้อมูล Session สำหรับหน้าเว็บยืนยัน
- รับคำสั่ง Confirm และเปลี่ยนสถานะ Token เดิมเป็น `active`
- ปฏิเสธ Token ที่ยังเป็น `pending`, หมดอายุ หรือถูกเพิกถอน

### Cloudflare Access
- ป้องกันหน้าเว็บยืนยัน Session
- บังคับให้ผู้ใช้ผ่าน Cloudflare Access ก่อนเข้าหน้ายืนยัน
- ใช้เป็นด่านควบคุมการเข้าถึงหน้าเว็บเท่านั้น ไม่ใช่การตรวจ Email ซ้ำกับ Email ที่กรอกใน App

### หน้าเว็บยืนยัน
- แสดง Session ที่รอการยืนยัน
- ให้ผู้ใช้กด Confirm อย่างชัดเจน
- ส่งคำขอ Confirm ไปยัง Engine
- แสดงผลว่ายืนยันสำเร็จหรือไม่

## สถานะ Session

- `pending`: Engine ออก Token แล้ว แต่ยังใช้เข้าถึง API ไม่ได้
- `active`: ผู้ใช้กด Confirm บนหน้าเว็บแล้ว ใช้ Token เข้าถึง API ได้
- `expired`: หมดเวลารอยืนยันหรือ Token หมดอายุ
- `revoked`: Token ถูกเพิกถอนและใช้ไม่ได้อีก

## เงื่อนไขความปลอดภัย

- ตรวจสอบ Allowlist ที่ Engine ทุกครั้งก่อนออก Token
- Email ว่างหรือไม่อยู่ใน Allowlist ต้องไม่ทำให้เกิด Token หรือ Pending Session
- Token ต้องสุ่มอย่างปลอดภัยและคาดเดาไม่ได้
- Token สถานะ `pending` ต้องใช้เรียก API ที่ต้องยืนยันตัวตนไม่ได้
- URL ยืนยันต้องมีรหัสอ้างอิงแบบสุ่มและหมดอายุได้ ห้ามใช้ Token สำหรับเรียก API เป็นรหัสยืนยันใน URL
- การกด Confirm ต้องเปลี่ยนสถานะของ Token เดิม ไม่ออก Token ใหม่
- คำขอ Confirm ต้องตรวจสอบสถานะและอายุของ Pending Session ก่อนเปลี่ยนเป็น `active`
- จำกัดอายุของ Pending Session และป้องกันการยืนยันซ้ำ
- ห้ามบันทึก Token หรือข้อมูลลับลง Log

## ข้อกำหนดที่ห้ามเปลี่ยน

- ต้องกรอก Email ก่อน Engine จึงจะออก Token
- Email ไม่ผ่าน Allowlist = ไม่ออก Token และปฏิเสธการเข้าถึง
- เมื่อ Email ผ่าน Engine ออก Token ให้ทันที แต่ Token ยังเป็น `pending`
- ผู้ใช้ต้องเปิดหน้าเว็บผ่าน Cloudflare Access และกด Confirm เพื่อเปิดใช้งาน
- ไม่มีการตรวจ Email เป็นด่านที่สองในขั้นตอน Confirm
- เมื่อ Confirm สำเร็จ ต้องเปิดใช้งาน Token เดิมทันที
