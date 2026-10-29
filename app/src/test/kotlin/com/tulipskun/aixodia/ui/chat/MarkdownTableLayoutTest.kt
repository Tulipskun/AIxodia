package com.tulipskun.aixodia.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableLayoutTest {
    @Test
    fun `every column gap and both side paddings belong to the content width`() {
        assertEquals(320.dp, tableNaturalWidth(listOf(80.dp, 90.dp, 90.dp), 20.dp, 10.dp))
    }

    @Test
    fun `gaps can make a table wider than a narrow viewport`() {
        val width = tableNaturalWidth(List(4) { 80.dp }, 20.dp, 10.dp)
        assertEquals(400.dp, width)
        assertTrue(width > 360.dp)
    }

    @Test
    fun `one column needs borders but no gap and no columns need no space`() {
        assertEquals(100.dp, tableNaturalWidth(listOf(80.dp), 20.dp, 10.dp))
        assertEquals(0.dp, tableNaturalWidth(emptyList(), 20.dp, 10.dp))
    }
}
