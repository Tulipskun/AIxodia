package com.tulipskun.aixodia.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inline layer, asserted on the spans themselves.
 *
 * [MarkdownBlocksTest] can tell you a line was read as a paragraph; it cannot
 * tell you that the `**` in it came out as bold rather than as two asterisks on
 * screen. That distinction is the whole job here, and the bug it exists for was
 * found by looking at a screenshot, not a test: bold that wrapped a code span
 * lost its markers and printed them, because the code was lifted out first and
 * left neither half of the pair a partner.
 */
class MarkdownInlineTest {

    private val code = SpanStyle(fontFamily = FontFamily.Monospace)
    private val link = SpanStyle(color = Color.Blue)

    private fun render(text: String) = inline(text, code, link)

    /** The styles in force over one character. */
    private fun AnnotatedString.stylesAt(offset: Int): List<SpanStyle> =
        spanStyles.filter { offset >= it.start && offset < it.end }.map { it.item }

    private fun AnnotatedString.hasBoldOverAll(): Boolean =
        spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 0 && it.end == length }

    @Test
    fun `bold that wraps a code span keeps the markers off the screen`() {
        // Taken from a real answer on the phone.
        val out = render("**1. สร้างไฟล์ `test.txt`**")

        assertEquals("1. สร้างไฟล์ test.txt", out.text)
        assertTrue("the whole run should be bold, spans=${out.spanStyles}", out.hasBoldOverAll())
    }

    @Test
    fun `a code span inside bold is still a code span`() {
        val out = render("**ดูที่ `test.txt` ได้**")
        val at = out.text.indexOf("test.txt")

        assertTrue(out.stylesAt(at).any { it.fontFamily == FontFamily.Monospace })
        assertTrue(out.stylesAt(at).any { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `an asterisk inside a code span stays literal`() {
        // Lifting code out first used to make this italic.
        val out = render("ดู `a*b*c` ได้")

        assertEquals("ดู a*b*c ได้", out.text)
        assertFalse(out.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `bold, italic and bold italic each keep their markers off`() {
        assertEquals("หนา", render("**หนา**").text)
        assertEquals("เอียง", render("*เอียง*").text)
        assertEquals("ทั้งสองอย่าง", render("***ทั้งสองอย่าง***").text)

        val both = render("***ทั้งสองอย่าง***")
        assertEquals(FontWeight.Bold, both.stylesAt(0).single().fontWeight)
        assertEquals(FontStyle.Italic, both.stylesAt(0).single().fontStyle)
    }

    @Test
    fun `a strike keeps its markers off`() {
        val out = render("~~เอาออก~~")
        assertEquals("เอาออก", out.text)
        assertTrue(out.stylesAt(0).any { it.textDecoration != null })
    }

    @Test
    fun `a code span on its own is untouched`() {
        val out = render("ดู `test.txt` ที่นี่")
        assertEquals("ดู test.txt ที่นี่", out.text)
        assertTrue(out.stylesAt(out.text.indexOf("test.txt")).any { it.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `a link label keeps its markdown and its link style`() {
        val plain = render("ดู [คู่มือ](https://example.com)")
        assertEquals("ดู คู่มือ", plain.text)
        assertTrue(plain.stylesAt(plain.text.indexOf("คู่มือ")).any { it.color == Color.Blue })
    }

    @Test
    fun `bold that wraps a link keeps both`() {
        val out = render("**ดู [คู่มือ](https://example.com)**")

        assertEquals("ดู คู่มือ", out.text)
        val at = out.text.indexOf("คู่มือ")
        assertTrue(out.stylesAt(at).any { it.fontWeight == FontWeight.Bold })
        assertTrue(out.stylesAt(at).any { it.color == Color.Blue })
    }

    @Test
    fun `an unpaired marker is left alone rather than eating the text`() {
        // The answer is still streaming, so half the pairs do not exist yet.
        assertEquals("**ยังไม่จบ", render("**ยังไม่จบ").text)
        assertEquals("1 * 2 * 3", render("1 * 2 * 3").text)
    }

    @Test
    fun `an asterisk that is not emphasis does not become italic`() {
        val out = render("ใช้ * หรือ - เป็นตัวคั่นก็ได้")
        assertEquals("ใช้ * หรือ - เป็นตัวคั่นก็ได้", out.text)
        assertFalse(out.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }
}