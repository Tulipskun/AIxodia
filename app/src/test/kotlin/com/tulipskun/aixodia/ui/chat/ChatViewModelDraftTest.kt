package com.tulipskun.aixodia.ui.chat

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.AttachmentRef
import com.tulipskun.aixodia.data.model.Block
import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.repo.CHAT_PAGE
import com.tulipskun.aixodia.data.repo.ChatRepository
import com.tulipskun.aixodia.data.repo.MessageWindow
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelDraftTest {
    private val dispatcher = StandardTestDispatcher()
    private val connection = MutableStateFlow(ConnState.OFFLINE)
    private val frames = MutableSharedFlow<AiOutput>()
    private lateinit var repo: ChatRepository
    private lateinit var settings: SettingsStore
    private lateinit var clock: MockedStatic<SystemClock>

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = mock()
        settings = mock()
        clock = mockStatic(SystemClock::class.java)
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
    }

    @After fun tearDown() {
        clock.close()
        Dispatchers.resetMain()
    }

    private fun page(sid: String) = MessageWindow(listOf(ChatMessage("$sid:100", sid, 100, "user", "question", 1_000)), true)

    private fun modelTest(
        savedState: SavedStateHandle = SavedStateHandle(),
        initialSession: String = "A",
        beforeStart: suspend () -> Unit = {},
        test: suspend TestScope.(ChatViewModel) -> Unit,
    ) = runTest(dispatcher) {
        whenever(repo.connState).thenReturn(connection)
        whenever(repo.socketError).thenReturn(MutableStateFlow(0))
        whenever(repo.liveFrames).thenReturn(frames)
        whenever(repo.observeSessions()).thenReturn(flowOf(emptyList()))
        whenever(repo.observeMessages(any(), any())).thenAnswer { flowOf(page(it.getArgument(0)).messages) }
        whenever(repo.latestWindow(any(), any())).thenAnswer { page(it.getArgument(0)) }
        whenever(repo.ensureSession(any())).thenReturn("A")
        whenever(repo.isDraft(any())).thenReturn(false)
        whenever(repo.maxSeq(any())).thenReturn(100L)
        whenever(repo.send(any(), any(), any())).thenReturn(true)
        whenever(repo.setSessionModel(any(), any(), any())).thenReturn(true)
        whenever(settings.sessionFlow).thenReturn(flowOf("A"))
        whenever(settings.daemonStartedAtFlow).thenReturn(flowOf(0L))
        whenever(settings.endpointFlow).thenReturn(flowOf(""))
        beforeStart()
        val vm = ChatViewModel(repo, settings, initialSession, savedStateHandle = savedState)
        try {
            runCurrent()
            test(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun `drafts stay with their chat across switches`() = modelTest { vm ->
        vm.changeDraft("A draft")
        vm.openChat("B")
        runCurrent()
        assertEquals(null, vm.drafts.value["B"])
        vm.changeDraft("B draft")
        vm.openChat("A")
        runCurrent()
        assertEquals(mapOf("A" to "A draft", "B" to "B draft"), vm.drafts.value)
        verify(repo, never()).send(any(), any(), any())
    }

    @Test fun `clearing a draft never clears another chat`() = modelTest { vm ->
        vm.changeDraft("A draft")
        vm.changeDraft("B draft", "B")
        vm.changeDraft("")
        assertEquals(mapOf("B" to "B draft"), vm.drafts.value)
    }

    @Test fun `accepted offline text is queued and clears its draft`() = modelTest { vm ->
        vm.changeDraft(" hello ")
        vm.sendDraft()
        runCurrent()
        assertEquals(emptyMap<String, String>(), vm.drafts.value)
        assertFalse(vm.busy.value)
        assertTrue(vm.notice.value.contains("Queued"))
        verify(repo).send(eq("A"), eq("hello"), any())
    }

    @Test fun `a busy rejection retains the next draft`() = modelTest { vm ->
        connection.value = ConnState.ONLINE
        vm.send("first")
        runCurrent()
        vm.changeDraft("second")
        vm.sendDraft()
        runCurrent()
        assertEquals("second", vm.drafts.value["A"])
        verify(repo, never()).send(eq("A"), eq("second"), any())
    }

    @Test fun `uploads block acceptance without losing the draft`() = modelTest { vm ->
        connection.value = ConnState.ONLINE
        vm.uploading.value = true
        vm.changeDraft("hello")
        vm.sendDraft()
        runCurrent()
        assertEquals("hello", vm.drafts.value["A"])
        assertFalse(vm.busy.value)
        verify(repo, never()).send(any(), any(), any())
    }

    @Test fun `offline files retain both their draft and attachment`() = modelTest { vm ->
        val file = AttachmentRef(name = "a.txt", path = "/a.txt")
        vm.pendingAttachments.value = listOf(file)
        vm.changeDraft("hello")
        vm.sendDraft()
        runCurrent()
        assertEquals("hello", vm.drafts.value["A"])
        assertEquals(listOf(file), vm.pendingAttachments.value)
        verify(repo, never()).send(any(), any(), any())
    }

    @Test fun `a storage failure retains unsent text and files`() = modelTest { vm ->
        whenever(repo.send(eq("A"), any(), any())).doSuspendableAnswer { throw IOException("cache unavailable") }
        connection.value = ConnState.ONLINE
        val file = AttachmentRef(name = "a.txt", path = "/a.txt")
        vm.pendingAttachments.value = listOf(file)
        vm.changeDraft("hello")
        vm.sendDraft()
        runCurrent()
        assertEquals("hello", vm.drafts.value["A"])
        assertEquals(listOf(file), vm.pendingAttachments.value)
        assertFalse(vm.busy.value)
        assertTrue(vm.errorCard.value.isNotBlank())
    }

    @Test fun `failed remote creation never accepts or clears the draft`() = modelTest { vm ->
        whenever(repo.isDraft("A")).thenReturn(true)
        whenever(repo.ensureRemoteSession(any(), any())).thenReturn(false)
        connection.value = ConnState.ONLINE
        vm.changeDraft("hello")
        vm.sendDraft()
        runCurrent()
        assertEquals("hello", vm.drafts.value["A"])
        assertFalse(vm.busy.value)
        verify(repo, never()).send(any(), any(), any())
    }

    @Test fun `a late acceptance clears only the originating chat's submitted draft`() = modelTest { vm ->
        val accepted = CompletableDeferred<Boolean>()
        whenever(repo.send(eq("A"), any(), any())).doSuspendableAnswer { accepted.await() }
        connection.value = ConnState.ONLINE
        vm.changeDraft("A draft")
        vm.sendDraft()
        runCurrent()
        vm.openChat("B")
        runCurrent()
        vm.changeDraft("B draft")
        accepted.complete(true)
        runCurrent()
        assertEquals(mapOf("B" to "B draft"), vm.drafts.value)
        assertEquals("", vm.notice.value)
    }

    @Test fun `a late acceptance cannot clear later edits in the same chat`() = modelTest { vm ->
        val accepted = CompletableDeferred<Boolean>()
        whenever(repo.send(any(), any(), any())).doSuspendableAnswer { accepted.await() }
        connection.value = ConnState.ONLINE
        vm.changeDraft("first")
        vm.sendDraft()
        runCurrent()
        vm.changeDraft("edited")
        accepted.complete(true)
        runCurrent()
        assertEquals("edited", vm.drafts.value["A"])
    }

    @Test fun `cancelling preparation keeps the draft and releases the busy state`() = modelTest { vm ->
        whenever(repo.isDraft("A")).thenThrow(CancellationException("cancelled preparation"))
        connection.value = ConnState.ONLINE
        vm.changeDraft("hello")
        vm.sendDraft()
        runCurrent()
        assertEquals("hello", vm.drafts.value["A"])
        assertFalse(vm.busy.value)
        verify(repo, never()).send(any(), any(), any())
    }

    @Test fun `drafts restore from the saved-state handle`() = modelTest(
        savedState = SavedStateHandle(mapOf("chat_drafts" to hashMapOf("A" to "A draft", "B" to "B draft"))),
    ) { vm ->
        assertEquals(mapOf("A" to "A draft", "B" to "B draft"), vm.drafts.value)
        vm.openChat("B")
        runCurrent()
        vm.changeDraft("changed B")
        assertEquals("A draft", vm.drafts.value["A"])
        assertEquals("changed B", vm.drafts.value["B"])
    }

    @Test fun `a failed model pin must be retried after promotion before the first send`() = modelTest { vm ->
        var draft = true
        whenever(repo.isDraft("A")).thenAnswer { draft }
        whenever(repo.ensureRemoteSession(eq("A"), any())).doSuspendableAnswer { draft = false; true }
        whenever(repo.setSessionModel(eq("A"), any(), any())).thenReturn(false)
        connection.value = ConnState.ONLINE
        vm.selectedProvider.value = "provider"
        vm.selectedModel.value = "model"
        vm.saveModel()
        runCurrent()
        vm.changeDraft("hello")
        vm.sendDraft()
        runCurrent()
        assertEquals("hello", vm.drafts.value["A"])
        verify(repo, never()).send(any(), any(), any())
        whenever(repo.setSessionModel(eq("A"), any(), any())).thenReturn(true)
        vm.sendDraft()
        runCurrent()
        verify(repo, times(2)).setSessionModel("A", "provider", "model")
        verify(repo).send(eq("A"), eq("hello"), any())
        assertEquals(emptyMap<String, String>(), vm.drafts.value)
    }

    @Test fun `a persisted selection echo never cancels a newer navigation`() = modelTest { vm ->
        vm.openChat("B")
        runCurrent()
        val next = CompletableDeferred<MessageWindow>()
        whenever(repo.latestWindow("C", CHAT_PAGE)).doSuspendableAnswer { next.await() }
        vm.openChat("C")
        runCurrent()
        vm.openSavedChat("B")
        next.complete(page("C"))
        runCurrent()
        assertEquals("C", vm.sessionId)
    }

    @Test fun `startup reads the saved session when composition has not emitted it yet`() = modelTest(initialSession = "") { vm ->
        assertEquals("A", vm.sessionId)
        verify(repo).ensureSession("A")
    }

    @Test fun `queued live mutations cannot survive stop and repaint the next turn`() = modelTest { vm ->
        val mutex = ChatViewModel::class.java.getDeclaredField("liveBlocksMutex")
            .apply { isAccessible = true }.get(vm) as Mutex
        mutex.lock()
        try {
            connection.value = ConnState.ONLINE
            vm.send("first")
            runCurrent()
            frames.emit(AiOutput(sessionId = "A", kind = "trace", block = Block(id = "old-tool", type = Block.TOOL, name = "shell", state = "running")))
            runCurrent()
            vm.stop()
            vm.send("second")
            runCurrent()
        } finally {
            mutex.unlock()
        }
        runCurrent()
        assertEquals(1, vm.liveBlocks.value.filterIsInstance<ChatViewModel.LiveBlock.Think>().size)
        assertTrue(vm.liveBlocks.value.none { it is ChatViewModel.LiveBlock.Act })
    }
}
