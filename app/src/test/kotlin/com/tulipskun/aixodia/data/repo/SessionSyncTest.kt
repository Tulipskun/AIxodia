package com.tulipskun.aixodia.data.repo

import com.tulipskun.aixodia.data.local.SessionEntity
import com.tulipskun.aixodia.data.remote.SessionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSyncTest {
    private fun local(
        id: String = "s1",
        title: String = "old title",
        model: String = "old-model",
        lastAt: Long = 1_000L,
    ) = SessionEntity(
        id = id,
        title = title,
        model = model,
        lastSnippet = "local preview",
        lastAt = lastAt,
        unread = 4,
    )

    @Test fun serverMetadataReplacesStaleLocalRowButKeepsUnreadAndPreview() {
        val merged = mergeSessionRows(
            listOf(local()),
            listOf(SessionRow(id = "s1", title = "new title", model = "new-model", updatedAt = 200)),
            pending = emptySet(),
        ).single()

        assertEquals("new title", merged.title)
        assertEquals("new-model", merged.model)
        assertEquals(200_000L, merged.lastAt)
        assertEquals(4, merged.unread)
        assertEquals("local preview", merged.lastSnippet)
        assertTrue(merged.remote)
    }

    @Test fun queuedMessageKeepsItsChatAtTheTopUntilStored() {
        val merged = mergeSessionRows(
            listOf(local(lastAt = 900_000L)),
            listOf(SessionRow(id = "s1", title = "server", model = "m", updatedAt = 200)),
            pending = setOf("s1"),
        ).single()

        assertEquals(900_000L, merged.lastAt)
    }

    @Test fun blankServerTitleDoesNotEraseTheNameOnThePhone() {
        val merged = mergeSessionRows(
            listOf(local(title = "named here")),
            listOf(SessionRow(id = "s1", updatedAt = 200)),
            pending = emptySet(),
        ).single()

        assertEquals("named here", merged.title)
    }
}
