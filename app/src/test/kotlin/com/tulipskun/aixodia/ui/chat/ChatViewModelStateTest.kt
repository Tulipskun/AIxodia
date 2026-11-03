package com.tulipskun.aixodia.ui.chat

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.AttachmentRef
import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.remote.ObjectiveRow
import com.tulipskun.aixodia.data.remote.ObjectivesPage
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.repo.CHAT_PAGE
import com.tulipskun.aixodia.data.repo.ChatRepository
import com.tulipskun.aixodia.data.repo.MessageWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

/** Suspend the actual view-model operations at the point a reader switches chats. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelStateTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: ChatRepository
    private lateinit var settings: SettingsStore
    private lateinit var clock: MockedStatic<SystemClock>
    private val connection = MutableStateFlow(ConnState.OFFLINE)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = mock()
        settings = mock()
        clock = mockStatic(SystemClock::class.java)
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
    }

    @After
    fun tearDown() {
        clock.close()
        Dispatchers.resetMain()
    }

    private fun page(sid: String) = MessageWindow(
        listOf(ChatMessage("$sid:100", sid, 100, "user", "question", 1_000L)),
        hasOlder = true,
    )

    private fun modelTest(
        beforeStart: suspend () -> Unit = {},
        test: suspend TestScope.(ChatViewModel) -> Unit,
    ) = runTest(dispatcher) {
        whenever(repo.connState).thenReturn(connection)
        whenever(repo.socketError).thenReturn(MutableStateFlow(0))
        whenever(repo.activeSession).thenReturn(MutableStateFlow("A"))
        whenever(repo.liveFrames).thenReturn(MutableSharedFlow<AiOutput>())
        whenever(repo.observeSessions()).thenReturn(flowOf(emptyList()))
        whenever(repo.observeMessages(any(), any())).thenAnswer { flowOf(page(it.getArgument(0)).messages) }
        whenever(repo.latestWindow(any(), any())).thenAnswer { page(it.getArgument(0)) }
        whenever(repo.ensureSession(any())).thenReturn("A")
        whenever(repo.sessionRoute(any())).thenAnswer { "${it.getArgument<String>(0)}-provider" to "${it.getArgument<String>(0)}-model" }
        whenever(repo.isDraft(any())).thenReturn(false)
        whenever(repo.maxSeq(any())).thenReturn(100L)
        whenever(repo.send(any(), any(), any())).thenReturn(true)
        whenever(repo.setSessionModel(any(), any(), any())).thenReturn(true)
        whenever(repo.clearSessionModel(any())).thenReturn(true)
        whenever(repo.retryTurn(any())).thenReturn(true)
        whenever(repo.createDraftSession(any())).thenReturn("draft")
        whenever(settings.daemonStartedAtFlow).thenReturn(flowOf(0L))
        whenever(settings.endpointFlow).thenReturn(flowOf(""))
        beforeStart()
        val vm = ChatViewModel(repo, settings, "A")
        try {
            runCurrent()
            test(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `a cancelled cache read never adopts its chat`() = modelTest { vm ->
        val next = CompletableDeferred<MessageWindow>()
        whenever(repo.latestWindow("B", CHAT_PAGE)).doSuspendableAnswer { awaitCancellation() }
        whenever(repo.latestWindow("C", CHAT_PAGE)).doSuspendableAnswer { next.await() }
        vm.openChat("B")
        runCurrent()
        vm.openChat("C")
        runCurrent()
        assertEquals("A", vm.sessionId)
        assertFalse(vm.busy.value)
        next.complete(page("C"))
        runCurrent()
        assertEquals("C", vm.sessionId)
    }

    @Test
    fun `tapping the visible chat cancels a pending switch away from it`() = modelTest { vm ->
        val next = CompletableDeferred<MessageWindow>()
        whenever(repo.latestWindow("B", CHAT_PAGE)).doSuspendableAnswer { next.await() }
        vm.openChat("B")
        runCurrent()
        vm.openChat("A")
        next.complete(page("B"))
        runCurrent()
        assertEquals("A", vm.sessionId)
        verify(repo, never()).selectSession("B")
    }

    @Test
    fun `a late route read cannot replace another chat's model`() = modelTest { vm ->
        val route = CompletableDeferred<Pair<String, String>>()
        whenever(repo.sessionRoute("B")).doSuspendableAnswer { withContext(NonCancellable) { route.await() } }
        vm.openChat("B")
        runCurrent()
        vm.openChat("C")
        runCurrent()
        route.complete("late-provider" to "late-model")
        runCurrent()
        assertEquals("C", vm.sessionId)
        assertEquals("C-provider", vm.selectedProvider.value)
        assertEquals("C-model", vm.selectedModel.value)
    }

    @Test
    fun `send keeps its originating chat across preparation reads`() = modelTest { vm ->
        val draft = CompletableDeferred<Boolean>()
        whenever(repo.isDraft("A")).doSuspendableAnswer { draft.await() }
        connection.value = ConnState.ONLINE
        vm.send("hello A")
        runCurrent()
        vm.openChat("B")
        runCurrent()
        draft.complete(false)
        runCurrent()
        verify(repo).send(eq("A"), eq("hello A"), any())
        verify(repo, never()).send(eq("B"), eq("hello A"), any())
    }

    @Test
    fun `an old send failure cannot clear the new chat's running answer`() = modelTest { vm ->
        val sent = CompletableDeferred<Boolean>()
        whenever(repo.send(eq("A"), any(), any())).doSuspendableAnswer { sent.await() }
        connection.value = ConnState.ONLINE
        vm.send("hello A")
        runCurrent()
        vm.openChat("B")
        runCurrent()
        vm.send("hello B")
        runCurrent()
        sent.completeExceptionally(IllegalStateException("old send failed"))
        runCurrent()
        assertTrue(vm.busy.value)
        assertEquals("", vm.errorCard.value)
    }

    @Test
    fun `an old failure cannot clear a newer turn after returning to the same chat`() = modelTest { vm ->
        val sent = CompletableDeferred<Boolean>()
        whenever(repo.send(eq("A"), eq("first"), any())).doSuspendableAnswer { sent.await() }
        connection.value = ConnState.ONLINE
        vm.send("first")
        runCurrent()
        vm.openChat("B")
        runCurrent()
        vm.openChat("A")
        runCurrent()
        vm.send("second")
        runCurrent()
        sent.completeExceptionally(IllegalStateException("first failed"))
        runCurrent()
        assertTrue(vm.busy.value)
        assertEquals("", vm.errorCard.value)
    }

    @Test
    fun `model saves stay with the chat whose selection was saved`() = modelTest { vm ->
        val draft = CompletableDeferred<Boolean>()
        whenever(repo.isDraft("A")).doSuspendableAnswer { draft.await() }
        vm.saveModel()
        runCurrent()
        vm.openChat("B")
        runCurrent()
        draft.complete(false)
        runCurrent()
        verify(repo).setSessionModel("A", "A-provider", "A-model")
        assertEquals("B-model", vm.selectedModel.value)
        assertEquals("", vm.notice.value)
    }

    @Test
    fun `clearing a model lock stays with the originating chat`() = modelTest { vm ->
        val draft = CompletableDeferred<Boolean>()
        whenever(repo.isDraft("A")).doSuspendableAnswer { draft.await() }
        vm.clearModel()
        runCurrent()
        vm.openChat("B")
        runCurrent()
        draft.complete(false)
        runCurrent()
        verify(repo).clearSessionModel("A")
        assertEquals("B-model", vm.selectedModel.value)
        assertTrue(vm.hasStoredRoute.value)
    }

    @Test
    fun `a paging failure from another chat cannot finish the current page request`() = modelTest { vm ->
        val olderA = CompletableDeferred<MessageWindow>()
        val olderB = CompletableDeferred<MessageWindow>()
        whenever(repo.loadOlderWindow("A", 100, CHAT_PAGE)).doSuspendableAnswer { olderA.await() }
        whenever(repo.loadOlderWindow("B", 100, CHAT_PAGE)).doSuspendableAnswer { olderB.await() }
        vm.loadOlder()
        runCurrent()
        vm.openChat("B")
        runCurrent()
        vm.loadOlder()
        runCurrent()
        olderA.completeExceptionally(IllegalStateException("old page failed"))
        runCurrent()
        assertTrue(vm.loadingOlder.value)
        assertTrue(vm.hasOlder.value)
        assertEquals("", vm.notice.value)
    }

    @Test
    fun `retry cannot reset an answer already running`() = modelTest { vm ->
        connection.value = ConnState.ONLINE
        vm.send("hello")
        runCurrent()
        vm.liveText.value = "answer so far"
        vm.retryLast()
        runCurrent()
        assertEquals("answer so far", vm.liveText.value)
        verify(repo, never()).retryTurn(any())
    }

    @Test
    fun `cold startup cannot reopen its chat after a newer selection`() {
        val synced = CompletableDeferred<Unit>()
        modelTest(beforeStart = {
            whenever(repo.syncSessions()).doSuspendableAnswer { synced.await() }
        }) { vm ->
            vm.openChat("B")
            runCurrent()
            synced.complete(Unit)
            runCurrent()
            assertEquals("B", vm.sessionId)
        }
    }

    @Test
    fun `a late route cannot overwrite a newer choice after returning to the same chat`() = modelTest { vm ->
        val route = CompletableDeferred<Pair<String, String>>()
        whenever(repo.sessionRoute("B")).doSuspendableAnswer { withContext(NonCancellable) { route.await() } }
        vm.openChat("B")
        runCurrent()
        vm.openChat("C")
        runCurrent()
        doReturn("B-provider" to "B-model").whenever(repo).sessionRoute("B")
        vm.openChat("B")
        runCurrent()
        vm.chooseModel("newer-model")
        route.complete("old-provider" to "old-model")
        runCurrent()
        assertEquals("B", vm.sessionId)
        assertEquals("newer-model", vm.selectedModel.value)
    }

    @Test
    fun `a successful old send cannot remove the new chat's attachments`() = modelTest { vm ->
        val sent = CompletableDeferred<Boolean>()
        whenever(repo.send(eq("A"), any(), any())).doSuspendableAnswer { sent.await() }
        connection.value = ConnState.ONLINE
        vm.send("hello A")
        runCurrent()
        vm.openChat("B")
        runCurrent()
        val attachment = AttachmentRef(name = "B.txt", path = "/B.txt")
        vm.pendingAttachments.value = listOf(attachment)
        sent.complete(true)
        runCurrent()
        assertEquals(listOf(attachment), vm.pendingAttachments.value)
        assertEquals("", vm.notice.value)
    }

    @Test
    fun `an old paging success cannot exhaust history after returning to the same chat`() = modelTest { vm ->
        val older = CompletableDeferred<MessageWindow>()
        whenever(repo.loadOlderWindow("A", 100, CHAT_PAGE)).doSuspendableAnswer {
            withContext(NonCancellable) { older.await() }
        }
        vm.loadOlder()
        runCurrent()
        vm.openChat("B")
        runCurrent()
        vm.openChat("A")
        runCurrent()
        older.complete(MessageWindow(emptyList(), hasOlder = false))
        runCurrent()
        assertTrue(vm.hasOlder.value)
        assertFalse(vm.loadingOlder.value)
    }

    @Test
    fun `a failed page remains retryable and releases only its own loading state`() = modelTest { vm ->
        whenever(repo.loadOlderWindow("A", 100, CHAT_PAGE)).doSuspendableAnswer { throw IOException("page unavailable") }
        vm.loadOlder()
        runCurrent()
        assertFalse(vm.loadingOlder.value)
        assertTrue(vm.hasOlder.value)
        assertTrue(vm.notice.value.contains("page unavailable"))
        doReturn(MessageWindow(emptyList(), hasOlder = false)).whenever(repo).loadOlderWindow("A", 100, CHAT_PAGE)
        vm.loadOlder()
        runCurrent()
        assertFalse(vm.loadingOlder.value)
        assertFalse(vm.hasOlder.value)
    }

    @Test
    fun `retry retains its originating chat across preparation reads`() = modelTest { vm ->
        val draft = CompletableDeferred<Boolean>()
        whenever(repo.isDraft("A")).doSuspendableAnswer { draft.await() }
        connection.value = ConnState.ONLINE
        vm.retryLast()
        runCurrent()
        vm.openChat("B")
        runCurrent()
        draft.complete(false)
        runCurrent()
        verify(repo).retryTurn("A")
        verify(repo, never()).retryTurn("B")
        assertFalse(vm.busy.value)
        assertEquals("", vm.notice.value)
    }

    @Test
    fun `a failed recovery read cannot escape or clear the running answer`() = modelTest { vm ->
        whenever(repo.hasUnfinishedTurn("A")).doSuspendableAnswer { throw IOException("cache unavailable") }
        connection.value = ConnState.ONLINE
        vm.send("hello")
        runCurrent()
        vm.liveText.value = "answer so far"
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(vm.busy.value)
        assertEquals("answer so far", vm.liveText.value)
        verify(repo, never()).retryTurn(any())
    }

    @Test
    fun `a failed recovery refresh is not permission to judge the cached turn`() = modelTest { _ ->
        whenever(repo.refreshLatest("A")).doSuspendableAnswer { throw IOException("history unavailable") }
        advanceTimeBy(4_000)
        runCurrent()
        verify(repo, never()).hasUnfinishedTurn(any())
        verify(repo, never()).retryTurn(any())
    }

    @Test
    fun `a late recovery completion cannot clear another chat's new turn`() = modelTest { vm ->
        val unfinished = CompletableDeferred<Boolean>()
        whenever(repo.hasUnfinishedTurn("A")).doSuspendableAnswer {
            withContext(NonCancellable) { unfinished.await() }
        }
        advanceTimeBy(4_000)
        runCurrent()
        vm.openChat("B")
        runCurrent()
        connection.value = ConnState.ONLINE
        vm.send("hello B")
        runCurrent()
        vm.liveText.value = "B is answering"
        unfinished.complete(false)
        runCurrent()
        assertTrue(vm.busy.value)
        assertEquals("B is answering", vm.liveText.value)
    }

    @Test
    fun `a late recovery completion cannot clear a newer turn in the same chat`() = modelTest { vm ->
        val unfinished = CompletableDeferred<Boolean>()
        whenever(repo.hasUnfinishedTurn("A")).doSuspendableAnswer {
            withContext(NonCancellable) { unfinished.await() }
        }
        advanceTimeBy(4_000)
        runCurrent()
        vm.openChat("B")
        runCurrent()
        vm.openChat("A")
        runCurrent()
        connection.value = ConnState.ONLINE
        vm.send("new A question")
        runCurrent()
        vm.liveText.value = "new A answer"
        unfinished.complete(false)
        runCurrent()
        assertTrue(vm.busy.value)
        assertEquals("new A answer", vm.liveText.value)
    }

    @Test
    fun `a stale recovery age read cannot restart the turn in another chat`() = modelTest { vm ->
        val age = CompletableDeferred<Long>()
        whenever(repo.hasUnfinishedTurn("A")).thenReturn(true)
        whenever(repo.lastUserCreatedAt("A")).doSuspendableAnswer {
            withContext(NonCancellable) { age.await() }
        }
        advanceTimeBy(4_000)
        runCurrent()
        vm.openChat("B")
        runCurrent()
        connection.value = ConnState.ONLINE
        vm.send("hello B")
        runCurrent()
        vm.liveText.value = "B answer"
        age.complete(1_000L)
        runCurrent()
        assertEquals("B answer", vm.liveText.value)
        verify(repo, never()).retryTurn("A")
    }

    @Test
    fun `old cached answers cannot finish a newly preparing send`() {
        val rows = MutableStateFlow(listOf(
            ChatMessage("A:99", "A", 99, "user", "old question", 1_000L),
            ChatMessage("A:100", "A", 100, "model", "old answer", 1_000L, model = "model"),
        ))
        val draft = CompletableDeferred<Boolean>()
        modelTest(beforeStart = {
            whenever(repo.observeMessages(eq("A"), any())).thenReturn(rows)
            whenever(repo.isDraft("A")).doSuspendableAnswer { draft.await() }
        }) { vm ->
            connection.value = ConnState.ONLINE
            vm.send("new question")
            runCurrent()
            rows.value = rows.value.map { it.copy(text = it.text + " updated") }
            runCurrent()
            assertTrue(vm.busy.value)
            draft.complete(false)
            runCurrent()
            assertTrue(vm.busy.value)
        }
    }

    @Test
    fun `a late goals response cannot repaint a later visit to the same chat`() = modelTest { vm ->
        val old = CompletableDeferred<ObjectivesPage>()
        whenever(repo.objectives("A")).doSuspendableAnswer { withContext(NonCancellable) { old.await() } }
        advanceTimeBy(400)
        runCurrent()
        vm.openChat("B")
        runCurrent()
        val current = ObjectivesPage(goals = listOf(ObjectiveRow("new", "current goal")))
        doReturn(current).whenever(repo).objectives("A")
        vm.openChat("A")
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        old.complete(ObjectivesPage(goals = listOf(ObjectiveRow("old", "obsolete goal"))))
        runCurrent()
        assertEquals(current, vm.objectives.value)
    }

    @Test
    fun `a slower new-chat creation cannot overwrite the newer new-chat selection`() = modelTest { vm ->
        val old = CompletableDeferred<String>()
        whenever(repo.createDraftSession(any())).doSuspendableAnswer { withContext(NonCancellable) { old.await() } }
        vm.newChat()
        runCurrent()
        doReturn("newer-draft").whenever(repo).createDraftSession(any())
        vm.newChat()
        runCurrent()
        old.complete("older-draft")
        runCurrent()
        assertEquals("newer-draft", vm.sessionId)
        assertEquals("", vm.notice.value)
    }
}
