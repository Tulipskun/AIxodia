package com.tulipskun.aixodia.data.repo

import com.tulipskun.aixodia.data.local.AppDatabase
import com.tulipskun.aixodia.data.local.MessageDao
import com.tulipskun.aixodia.data.local.SessionDao
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.remote.ChatTransport
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.remote.HistoryApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Where a repair read starts when the thread holds a row the phone numbered
 * itself. The engine answers before D1 keeps the turn, so that row sits on a
 * guessed seq and the store's copy of the same turn — with the steps that came
 * between — sits further up. Reading only above the guess is what leaves a
 * reopened thread showing one answer twice.
 */
class InventedRowRepairTest {
    private val db: AppDatabase = mock()
    private val messages: MessageDao = mock()
    private val sessions: SessionDao = mock()
    private val history: HistoryApi = mock()
    private val socket: ChatTransport = mock()

    private fun repairTest(test: suspend TestScope.(ChatRepository) -> Unit) = runTest {
        whenever(db.messages()).thenReturn(messages)
        whenever(db.sessions()).thenReturn(sessions)
        whenever(socket.state).thenReturn(MutableStateFlow(ConnState.OFFLINE))
        whenever(socket.lastError).thenReturn(MutableStateFlow(0))
        whenever(socket.frames).thenReturn(MutableSharedFlow<AiOutput>())
        // The page a repair read gets is not what these tests are about, and an
        // empty one stops the read before anything is written.
        whenever(history.turnsAfter(any(), any(), any())).thenReturn(emptyList())
        whenever(history.latest(any(), any())).thenReturn(emptyList())
        test(ChatRepository(db, history, socket, backgroundScope))
    }

    @Test
    fun `the repair starts below the row the phone numbered itself`() = repairTest { repo ->
        whenever(messages.maxSeq("A")).thenReturn(3L)
        whenever(messages.inventedFloor("A")).thenReturn(2L)
        repo.verify("A")
        verify(history).turnsAfter(eq("A"), eq(1L), any())
        verify(history, never()).turnsAfter(eq("A"), eq(3L), any())
    }

    @Test
    fun `a thread of stored rows is still read from its end`() = repairTest { repo ->
        whenever(messages.maxSeq("A")).thenReturn(5L)
        whenever(messages.inventedFloor("A")).thenReturn(0L)
        repo.verify("A")
        verify(history).turnsAfter(eq("A"), eq(5L), any())
    }

    @Test
    fun `an answer guessed at the very end still pulls what the store kept`() = repairTest { repo ->
        whenever(messages.maxSeq("A")).thenReturn(2L)
        whenever(messages.inventedFloor("A")).thenReturn(2L)
        repo.verify("A")
        verify(history).turnsAfter(eq("A"), eq(1L), any())
        verify(history, never()).latest(eq("A"), any())
    }
}
