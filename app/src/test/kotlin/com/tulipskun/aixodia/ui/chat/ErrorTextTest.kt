package com.tulipskun.aixodia.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorTextTest {
    @Test fun blankGivesGenericMessage() =
        assertEquals("เกิดข้อผิดพลาดที่ไม่ทราบสาเหตุ ลองส่งอีกครั้ง", friendlyError("  "))

    @Test fun rateLimitIsBusyNotError() =
        assertEquals(
            "ผู้ให้บริการกำลังยุ่ง ระบบจะลองใหม่ให้ หรือกดส่งอีกครั้งในไม่กี่วินาที",
            friendlyError("upstream_error: 429 temporarily at capacity upstream"),
        )

    @Test fun insufficientFundsSuggestsOtherModel() =
        assertEquals(
            "ผู้ให้บริการโมเดลนี้เครดิตหมด ลองเปลี่ยนไปใช้โมเดลอื่นในการตั้งค่า",
            friendlyError("upstream: insufficient funds"),
        )

    @Test fun missingKeyPointsToSettings() =
        assertEquals(
            "ยังไม่ได้ใส่คีย์ API ของผู้ให้บริการนี้ เพิ่มคีย์ในหน้าตั้งค่า",
            friendlyError("provider Zen has no API keys"),
        )

    @Test fun unknownModelIsExplained() =
        assertEquals(
            "ไม่พบโมเดลหรือผู้ให้บริการที่เลือก ตรวจสอบการตั้งค่าโมเดล",
            friendlyError("unknown model \"foo\": prefix it with a provider"),
        )

    @Test fun unknownErrorFallsBack() =
        assertEquals("ส่งข้อความไม่สำเร็จ ลองอีกครั้ง", friendlyError("something odd"))
}
