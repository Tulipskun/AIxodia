package com.tulipskun.aixodia.ui.sessions

import com.tulipskun.aixodia.data.model.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionOrganizeTest {
    @Test fun equalTimestampsUseTheSameIdTiebreakAsRoom() {
        val now = 1_700_000_000_000L
        val groups = groupSessionsByDate(
            listOf(
                ChatSession("a", "A", lastAt = now),
                ChatSession("c", "C", lastAt = now),
                ChatSession("b", "B", lastAt = now),
            ),
            nowMs = now,
        )
        assertEquals(listOf("c", "b", "a"), groups.single().sessions.map { it.id })
    }
}
