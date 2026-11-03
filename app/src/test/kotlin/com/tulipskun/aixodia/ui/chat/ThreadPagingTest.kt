package com.tulipskun.aixodia.ui.chat

import com.tulipskun.aixodia.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadPagingTest {
    private fun row(seq: Long, sid: String = "A") = ChatMessage("$sid:$seq", sid, seq, "user", "text", 1_000)

    @Test fun noHeadersMeansNoOffset() = assertEquals(0, threadHeaderItems(false))
    @Test fun theOlderMarkerCountsAsAnItem() = assertEquals(1, threadHeaderItems(true))

    @Test fun aHeaderOffsetIsNeverBorrowedByTheMessageBelowIt() {
        val anchor = pageAnchor("A", 1, listOf(row(100)), listOf("older-page" to -20, "A:100" to 80))
        assertEquals(ThreadPageAnchor("A", 1, "A:100", -80), anchor)
    }

    @Test fun aPartlyScrolledMessageKeepsItsOwnOffset() {
        assertEquals(ThreadPageAnchor("A", 1, "A:100", 32), pageAnchor("A", 1, listOf(row(100)), listOf("A:100" to -32)))
    }

    @Test fun anotherChatsVisibleRowsCannotBecomeAnAnchor() {
        assertNull(pageAnchor("A", 1, listOf(row(100, "B")), listOf("B:100" to 0)))
    }

    // The goals card left the thread for the bar above the composer, so the
    // row a prepended page has to land on moved down by one: only the older
    // marker is still an item in this list.
    @Test fun aPrependedPageRestoresTheRowUnderTheOlderMarker() {
        val rows = listOf(row(99), row(100))
        val anchor = ThreadPageAnchor("A", 1, "A:100", 25, loadedFloor = 99)
        assertEquals(2, anchorItemIndex(anchor, "A", 1, rows, threadHeaderItems(true)))
        assertEquals(1, anchorItemIndex(anchor, "A", 1, rows, threadHeaderItems(false)))
    }

    @Test fun theOldRowsCannotCompleteANewPageAnchor() {
        val anchor = ThreadPageAnchor("A", 1, "A:100", 25, loadedFloor = 99)
        assertNull(anchorItemIndex(anchor, "A", 1, listOf(row(100)), 0))
        assertNull(anchorItemIndex(anchor.copy(loadedFloor = null), "A", 1, listOf(row(99), row(100)), 0))
    }

    @Test fun anAnchorDoesNotSurviveAChatSwitchOrALaterVisit() {
        val anchor = ThreadPageAnchor("A", 1, "A:100", 25, loadedFloor = 99)
        val rows = listOf(row(99), row(100))
        assertNull(anchorItemIndex(anchor, "B", 1, rows, 0))
        assertNull(anchorItemIndex(anchor, "A", 3, rows, 0))
    }
}
