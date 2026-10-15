package com.tulipskun.aixodia.ui.chat

/**
 * Turns a raw error from the ai-engine daemon into a short Thai message the
 * user can act on. The raw text is never shown: it is Go error output and
 * changes with every upstream provider. Pure function, no Android types, so it
 * is unit-tested on the JVM.
 */
fun friendlyError(raw: String): String {
    val s = raw.lowercase()
    return when {
        raw.isBlank() ->
            "เกิดข้อผิดพลาดที่ไม่ทราบสาเหตุ ลองส่งอีกครั้ง"

        "insufficient" in s || "balance" in s || "credit" in s ->
            "ผู้ให้บริการโมเดลนี้เครดิตหมด ลองเปลี่ยนไปใช้โมเดลอื่นในการตั้งค่า"

        "429" in s || "rate limit" in s || "capacity" in s || "too many" in s ->
            "ผู้ให้บริการกำลังยุ่ง ระบบจะลองใหม่ให้ หรือกดส่งอีกครั้งในไม่กี่วินาที"

        // OpenCode's free tier refuses a request it does not recognise as its
        // own client's — an x-opencode-request header is enough to turn an
        // otherwise identical request from 200 into 403 FreeTierError, which is
        // why this section sits in front of the key section. For the month this
        // was misread as a rejected key, the user went to check a key that was
        // never the problem.
        "free tier" in s || "can only be used from within" in s ->
            "ผู้ให้บริการโหมดฟรีปฏิเสธคำขอ ไม่เกี่ยวกับคีย์ API ลองส่งอีกครั้งหรือเปลี่ยนโมเดล"

        "401" in s || "403" in s || "unauthorized" in s || "forbidden" in s || "invalid api key" in s ->
            "ผู้ให้บริการปฏิเสธคีย์ API ตรวจสอบคีย์ในหน้าตั้งค่า"

        "no api keys" in s ->
            "ยังไม่ได้ใส่คีย์ API ของผู้ให้บริการนี้ เพิ่มคีย์ในหน้าตั้งค่า"

        "unknown model" in s || "model is required" in s || "unknown provider" in s ->
            "ไม่พบโมเดลหรือผู้ให้บริการที่เลือก ตรวจสอบการตั้งค่าโมเดล"

        "deadline exceeded" in s || "timeout" in s || "timed out" in s ->
            "ผู้ให้บริการตอบช้าเกินกำหนด ลองส่งอีกครั้ง"

        "502" in s || "503" in s || "504" in s || "upstream" in s || "unavailable" in s ->
            "ผู้ให้บริการไม่พร้อมใช้งานชั่วคราว ลองอีกครั้งในภายหลัง"

        "tunnel" in s || "connection refused" in s || "no such host" in s ->
            "ติดต่อ daemon ไม่ได้ ตรวจว่าเครื่องที่รัน ai-engine ยังเปิดอยู่"

        else ->
            "ส่งข้อความไม่สำเร็จ ลองอีกครั้ง"
    }
}
