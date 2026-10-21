package com.tulipskun.aixodia.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPagingTest {
    private fun turns(count: Int) = (1..count).map { TurnRow(seq = it.toLong()) }

    @Test fun openingALongChatLoadsItsNewestTurns() {
        val page = pageTurns(turns(500), beforeSeq = 0, limit = 200)
        assertEquals(301L, page.first().seq)
        assertEquals(500L, page.last().seq)
    }

    @Test fun olderPageEndsJustBeforeTheOldestLoadedTurn() {
        val page = pageTurns(turns(500), beforeSeq = 301, limit = 100)
        assertEquals(201L, page.first().seq)
        assertEquals(300L, page.last().seq)
    }

    @Test fun shortChatLoadsWhole() {
        assertEquals(turns(7), pageTurns(turns(7), beforeSeq = 0, limit = 200))
    }

    @Test fun noBoundMeansTheWholeCatchUp() {
        // The reconnect read asks for everything after its last seq; capping it
        // at a page would hide turns that arrived while the phone was away.
        assertEquals(turns(250), pageTurns(turns(250), beforeSeq = 0, limit = 0))
    }

    @Test fun anEmptyPageIsAnEmptyThread() {
        assertEquals(emptyList<TurnRow>(), pageTurns(emptyList(), beforeSeq = 0, limit = 60))
    }
}
