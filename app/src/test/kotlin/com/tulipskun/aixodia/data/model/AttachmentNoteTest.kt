package com.tulipskun.aixodia.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attachment note the daemon appends is aimed at the agent, not at the
 * reader: it carries a server path and a MIME type. These tests pin the split
 * so the bubble shows the user's own words and a file name, not that note.
 */
class AttachmentNoteTest {

    private val note = "\n\n[ไฟล์แนบ]\n" +
        "- photo.jpg → /kaggle/working/uploads/abc/photo.jpg (image/jpeg)\n" +
        "- notes.txt → /kaggle/working/uploads/abc/notes.txt (text/plain)\n" +
        "เปิดอ่านได้ด้วย tool read/bash ตามพาธด้านบน"

    @Test fun splitsTextFromAttachments() {
        val parts = splitAttachmentNote("ภาพนี้คืออะไรรู้มั้ย$note")
        assertEquals("ภาพนี้คืออะไรรู้มั้ย", parts.text)
        assertEquals(2, parts.attachments.size)
        assertEquals("photo.jpg", parts.attachments[0].name)
        assertEquals("/kaggle/working/uploads/abc/photo.jpg", parts.attachments[0].path)
        assertEquals("image/jpeg", parts.attachments[0].mime)
        assertEquals("notes.txt", parts.attachments[1].name)
    }

    @Test fun plainMessageIsUntouched() {
        val raw = "แค่ข้อความธรรมดา"
        val parts = splitAttachmentNote(raw)
        assertEquals(raw, parts.text)
        assertTrue(parts.attachments.isEmpty())
    }

    @Test fun fileWithoutMimeStillReadsAsAnAttachment() {
        val parts = splitAttachmentNote("ดูหน่อย\n\n[ไฟล์แนบ]\n- a.bin → /kaggle/working/uploads/abc/a.bin")
        assertEquals("ดูหน่อย", parts.text)
        assertEquals(1, parts.attachments.size)
        assertEquals("", parts.attachments[0].mime)
    }
}