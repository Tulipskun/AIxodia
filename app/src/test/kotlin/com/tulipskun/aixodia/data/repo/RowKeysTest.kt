package com.tulipskun.aixodia.data.repo

import com.tulipskun.aixodia.data.local.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keys the stored thread draws its rows under.
 *
 * A lazy list throws on a repeated key rather than drawing it twice, so a
 * transcript that names two rows the same thing is a transcript that dies on
 * screen. The engine's message id is the name a row is drawn under, and
 * repairing a seq collision can hand one id to two rows: the local copy is
 * moved to a number the phone invented while the engine's own copy arrives at
 * its real seq.
 */
class RowKeysTest {
    private fun row(seq: Long, messageId: Long = 0L, session: String = "s", role: String = "model") =
        MessageEntity(sessionId = session, seq = seq, role = role, text = "t", createdAt = 1000, messageId = messageId)

    @Test fun theEngineIdNamesTheRowWhenItIsTheOnlyOneWearingIt() {
        assertEquals(listOf("42", "43"), distinctRowIds(listOf(row(1, 42), row(2, 43))))
    }

    @Test fun aRowWithoutAnEngineIdIsNamedByItsSessionAndSeq() {
        assertEquals(listOf("s:7"), distinctRowIds(listOf(row(7))))
    }

    @Test fun aDuplicatedEngineIdFallsBackToTheSeqTheTableMakesUnique() {
        val engineCopy = row(3, 42)
        val moved = row(9, 42)
        assertEquals(listOf("42", "s:9"), distinctRowIds(listOf(engineCopy, moved)))
    }

    @Test fun twoSessionsNeverShareAKey() {
        assertEquals(
            listOf("42", "b:1"),
            distinctRowIds(listOf(row(1, 42, session = "a"), row(1, 0, session = "b"))),
        )
    }

    @Test fun anUncommittedRowAndAnEngineRowDoNotClaimTheSameName() {
        assertEquals(listOf("s:5", "5"), distinctRowIds(listOf(row(5), row(8, 5))))
    }

    @Test fun aNameTakenTwiceIsNumberedRatherThanShared() {
        // The last line of defence. Whatever made one name belong to three rows,
        // each still gets a key of its own and the read does not kill the app.
        val keys = distinctRowIds(
            listOf(row(5, 42), row(6, 42), row(6, 42)),
        )
        assertEquals(listOf("42", "s:6", "s:6#2"), keys)
    }
}
