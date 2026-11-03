package com.tulipskun.aixodia.data.repo

import com.tulipskun.aixodia.data.local.AppDatabase
import com.tulipskun.aixodia.data.local.MessageDao
import com.tulipskun.aixodia.data.local.MessageEntity
import com.tulipskun.aixodia.data.local.SessionDao
import com.tulipskun.aixodia.data.local.SessionEntity
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.remote.ChatTransport
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.remote.HistoryApi
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Cancellation is not an empty cache, a finished turn or the end of history. */
class RepositoryCancellationTest {
    private val db: AppDatabase = mock()
    private val messages: MessageDao = mock()
    private val sessions: SessionDao = mock()
    private val history: HistoryApi = mock()
    private val socket: ChatTransport = mock()
    private val cancelled = CancellationException("the reader left this chat")

    private fun repositoryTest(test: suspend TestScope.(ChatRepository) -> Unit) = runTest {
        whenever(db.messages()).thenReturn(messages)
        whenever(db.sessions()).thenReturn(sessions)
        whenever(socket.state).thenReturn(MutableStateFlow(ConnState.OFFLINE))
        whenever(socket.lastError).thenReturn(MutableStateFlow(0))
        whenever(socket.frames).thenReturn(MutableSharedFlow<AiOutput>())
        test(ChatRepository(db, history, socket, backgroundScope))
    }

    private fun row() = MessageEntity("A", 100, role = "user", text = "question", createdAt = 1_000)

    @Test
    fun `a cancelled latest-page read is not an empty page`() = repositoryTest { repo ->
        whenever(messages.recent("A", CHAT_PAGE)).thenThrow(cancelled)
        assertSame(cancelled, runCatching { repo.latestWindow("A") }.exceptionOrNull())
    }

    @Test
    fun `a cancelled oldest-sequence read cannot finish a latest page`() = repositoryTest { repo ->
        whenever(messages.recent("A", CHAT_PAGE)).thenReturn(listOf(row()))
        whenever(messages.minSeq("A")).thenThrow(cancelled)
        assertSame(cancelled, runCatching { repo.latestWindow("A") }.exceptionOrNull())
    }

    @Test
    fun `a cancelled older cache read never falls through to the server`() = repositoryTest { repo ->
        whenever(messages.before("A", 100, CHAT_PAGE)).thenThrow(cancelled)
        assertSame(cancelled, runCatching { repo.loadOlderWindow("A", 100) }.exceptionOrNull())
        verify(history, never()).turns(any(), any(), any())
    }

    @Test
    fun `a cancelled older server read is not the end of history`() = repositoryTest { repo ->
        whenever(messages.before("A", 100, CHAT_PAGE)).thenReturn(emptyList())
        whenever(history.turns("A", 100, CHAT_PAGE)).thenThrow(cancelled)
        assertSame(cancelled, runCatching { repo.loadOlderWindow("A", 100) }.exceptionOrNull())
    }

    @Test
    fun `a failed older server read is not an exhausted transcript`() = repositoryTest { repo ->
        val failure = IOException("tunnel down")
        whenever(messages.before("A", 100, CHAT_PAGE)).thenReturn(emptyList())
        whenever(history.turns("A", 100, CHAT_PAGE)).doSuspendableAnswer { throw failure }
        assertSame(failure, runCatching { repo.loadOlderWindow("A", 100) }.exceptionOrNull())
    }

    @Test
    fun `a cancelled turn-base read cannot invent sequence zero`() = repositoryTest { repo ->
        whenever(messages.maxSeq("A")).thenThrow(cancelled)
        assertSame(cancelled, runCatching { repo.maxSeq("A") }.exceptionOrNull())
    }

    @Test
    fun `a cancelled draft check cannot claim a remote chat`() = repositoryTest { repo ->
        whenever(sessions.get("A")).thenThrow(cancelled)
        assertSame(cancelled, runCatching { repo.isDraft("A") }.exceptionOrNull())
    }

    @Test
    fun `a cancelled recovery read cannot claim the answer finished`() = repositoryTest { repo ->
        whenever(messages.allNow("A")).thenThrow(cancelled)
        assertSame(cancelled, runCatching { repo.hasUnfinishedTurn("A") }.exceptionOrNull())
    }

    @Test
    fun `a cancelled question-age read cannot invent an old question`() = repositoryTest { repo ->
        whenever(messages.allNow("A")).thenThrow(cancelled)
        assertSame(cancelled, runCatching { repo.lastUserCreatedAt("A") }.exceptionOrNull())
    }

    @Test
    fun `a cancelled route read does not claim an unpinned chat`() = repositoryTest { repo ->
        whenever(history.sessions()).thenThrow(cancelled)
        assertSame(cancelled, runCatching { repo.sessionRoute("A") }.exceptionOrNull())
    }

    @Test
    fun `a cancelled goals read does not claim an absent card`() = repositoryTest { repo ->
        whenever(history.objectives("A")).thenThrow(cancelled)
        assertSame(cancelled, runCatching { repo.objectives("A") }.exceptionOrNull())
    }

    @Test
    fun `a cancelled session read does not switch the transport`() = repositoryTest { repo ->
        whenever(sessions.get("A")).thenThrow(cancelled)
        assertSame(cancelled, runCatching { repo.openSession("A") }.exceptionOrNull())
        assertEquals("default", repo.activeSession.value)
        verify(socket, never()).switchSession(any())
        verify(socket, never()).open(any(), any())
    }

    @Test
    fun `a cancelled pending-message read never prunes those chats`() = repositoryTest { repo ->
        whenever(history.sessionsOrNull()).thenReturn(emptyList())
        whenever(sessions.observeAll()).thenReturn(listOf(SessionEntity("A")))
        whenever(messages.allPending()).thenThrow(cancelled)
        assertSame(cancelled, runCatching { repo.syncSessions() }.exceptionOrNull())
        verify(messages, never()).deleteSession(any())
        verify(sessions, never()).delete(any())
    }
}
