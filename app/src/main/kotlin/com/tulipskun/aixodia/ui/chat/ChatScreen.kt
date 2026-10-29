@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.tulipskun.aixodia.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tulipskun.aixodia.BuildConfig
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.model.ChatSession
import com.tulipskun.aixodia.data.model.GenerationSettings
import com.tulipskun.aixodia.data.model.StoredTotals
import com.tulipskun.aixodia.data.remote.ChatTransport
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.remote.DaemonDiscovery
import com.tulipskun.aixodia.data.remote.HistoryApi
import com.tulipskun.aixodia.data.remote.TerminalSocket
import com.tulipskun.aixodia.data.repo.ChatRepository
import com.tulipskun.aixodia.ui.chat.ChatViewModel.LiveBlock
import com.tulipskun.aixodia.ui.display.AttachSheet
import com.tulipskun.aixodia.ui.display.ChatComposer
import com.tulipskun.aixodia.ui.display.ComposerCommand
import com.tulipskun.aixodia.ui.display.ConnDot
import com.tulipskun.aixodia.ui.display.EmptyChatState
import com.tulipskun.aixodia.ui.display.ErrorCard
import com.tulipskun.aixodia.ui.display.GoalsCard
import com.tulipskun.aixodia.ui.display.LiveAnswer
import com.tulipskun.aixodia.ui.display.LiveThink
import com.tulipskun.aixodia.ui.display.CompactMarker
import com.tulipskun.aixodia.ui.display.MessageBlock
import com.tulipskun.aixodia.ui.display.OfflineBanner
import com.tulipskun.aixodia.ui.display.SessionStrip
import com.tulipskun.aixodia.ui.display.sessionTotals
import com.tulipskun.aixodia.ui.display.SetupNeeded
import com.tulipskun.aixodia.ui.display.ThinkPhase
import com.tulipskun.aixodia.ui.display.TokenField
import com.tulipskun.aixodia.ui.display.ToolStepCard
import com.tulipskun.aixodia.ui.display.turnFooterIds
import com.tulipskun.aixodia.ui.sessions.ChatActionsSheet
import com.tulipskun.aixodia.ui.sessions.DeleteChatDialog
import com.tulipskun.aixodia.ui.sessions.RenameChatDialog
import com.tulipskun.aixodia.ui.sessions.SessionDrawerContent
import com.tulipskun.aixodia.ui.sessions.sessionTitle
import com.tulipskun.aixodia.ui.providers.ProvidersScreen
import com.tulipskun.aixodia.ui.settings.SettingsScreen
import com.tulipskun.aixodia.ui.terminal.TerminalUiState
import com.tulipskun.aixodia.ui.terminal.TerminalWindow
import com.tulipskun.aixodia.term.TerminalConnections
import com.tulipskun.aixodia.term.TerminalLiveService
import com.tulipskun.aixodia.update.UpdateManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    repo: ChatRepository,
    settings: SettingsStore,
    history: HistoryApi,
    socket: ChatTransport,
    discovery: DaemonDiscovery? = null,
) {
    val token by settings.tokenFlow.collectAsStateWithLifecycle(initialValue = "")
    val discoveryState by if (discovery != null) discovery.state.collectAsStateWithLifecycle(initialValue = com.tulipskun.aixodia.data.remote.DiscoveryState()) else remember { mutableStateOf(com.tulipskun.aixodia.data.remote.DiscoveryState()) }

    // The token alone is enough to be configured: the daemon address now arrives
    // by discovery, so demanding it here would lock a fresh install on the setup
    // screen waiting for a value the app is about to fetch for itself. History
    // reads D1 directly and works with no address at all; only the live socket
    // waits, and it waits quietly.
    // Resolve the stored token before deciding the first screen. Reading it as
    // "empty" for one frame used to flash SetupNeeded even on a configured
    // device and then jump into the chat, which looks like a crash.
    val configuredScope = rememberCoroutineScope()
    var hasResolvedToken by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        configuredScope.launch {
            runCatching { settings.tokenFlow.first() }.getOrNull()?.isNotBlank()
            hasResolvedToken = true
        }
    }
    if (!hasResolvedToken) {
        Box(Modifier.fillMaxSize()) {}
        return
    }
    val configured = token.isNotBlank()
    val sessId by settings.sessionFlow.collectAsStateWithLifecycle(initialValue = "")
    var showSettings by remember { mutableStateOf(false) }
    var showProviders by remember { mutableStateOf(false) }
    var showTerminal by remember { mutableStateOf(false) }

    // The terminal's sockets, tabs and grids belong to this screen, not to the
    // visible window. Back only hides the window: every WebSocket stays
    // attached and every emulator keeps its screen, so returning to the
    // terminal lands on the same tab with the same scrollback instead of
    // redialing or starting a new shell. Only the terminal's own Close action
    // ends the tabs (see TerminalWindow.closeWindow).
    val terminalSocket = remember(settings, history) { TerminalSocket(settings, history) }
    val terminalUi = remember(terminalSocket) { TerminalUiState(terminalSocket) }
    val terminalSessions by terminalSocket.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val terminalContext = LocalContext.current
    DisposableEffect(terminalSocket) {
        TerminalConnections.attach(terminalSocket)
        onDispose {
            // The whole chat screen is going away (activity finish, process
            // teardown): now the sockets really do hang up. Navigator changes
            // above never reach this block because ChatScreen stays composed.
            terminalSocket.close()
            TerminalConnections.detach(terminalSocket)
            TerminalLiveService.stop(terminalContext)
        }
    }
    // Keep every open tab's grid current even while the terminal window is
    // hidden. The shells keep printing behind the chat; draining here means
    // returning to the window shows the live screen without replaying a backlog
    // through the socket's channel.
    LaunchedEffect(terminalSocket, terminalSessions) {
        if (terminalSessions.isNotEmpty()) {
            terminalUi.pump(terminalSessions)
        }
    }

    // Settings is a screen branch of the chat, not a separate Android route: the
    // system back button has to return here, not leave the app.
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showProviders) { showProviders = false }
    BackHandler(enabled = showTerminal) { showTerminal = false }

    // Unconfigured installs stop here — before the ViewModel exists — so a
    // first launch cannot reach the network layer and crash. The settings
    // screen still has to open from here, or there would be no way to fix it.
    if (!configured) {
        if (showSettings) {
            SettingsScreen(
                settings = settings, history = history, socket = socket,
                sessionId = sessId, onBack = { showSettings = false },
                discovery = discovery,
            )
            return
        }
        SetupNeeded(
            onSubmit = { value -> configuredScope.launch { settings.saveD1(value) } },
            onOpenSettings = { showSettings = true },
        )
        return
    }

    // The terminal is a branch of the chat like Settings, so the system back
    // button closes the window instead of leaving the app — and it is the whole
    // screen, because a shell that shares a screen with a chat composer is a
    // shell in a letterbox. It needs no session and no view model: it talks to
    // the machine, not to the chat.
    if (showTerminal) {
        // The socket and the per-tab emulators were created above and outlive
        // this branch. Leaving the window only hides it; the shells stay on the
        // engine and every tab's WebSocket stays attached for the trip back.
        TerminalWindow(
            socket = terminalSocket,
            ui = terminalUi,
            onClose = { showTerminal = false },
        )
        return
    }

    val context = LocalContext.current
    val vm: ChatViewModel = viewModel(key = sessId) {
        ChatViewModel(repo, settings, sessId, context.applicationContext)
    }
    val messages by vm.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    val sessionUsage by vm.sessionUsage.collectAsStateWithLifecycle(initialValue = null)
    val activeSession by vm.activeSession.collectAsStateWithLifecycle()
    val hasOlder by vm.hasOlder.collectAsStateWithLifecycle()
    val loadingOlder by vm.loadingOlder.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val conn by vm.conn.collectAsStateWithLifecycle(initialValue = ConnState.OFFLINE)
    var showOfflineCard by remember { mutableStateOf(false) }
    val socketErr by vm.socketError.collectAsStateWithLifecycle(initialValue = 0)
    val status by vm.status.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val errorCard by vm.errorCard.collectAsStateWithLifecycle()
    val daemonStartedAt by vm.daemonStartedAt.collectAsStateWithLifecycle()
    val liveText by vm.liveText.collectAsStateWithLifecycle()
    val liveBlocks by vm.liveBlocks.collectAsStateWithLifecycle()
    val objectives by vm.objectives.collectAsStateWithLifecycle()
    val liveReasoning by vm.liveReasoning.collectAsStateWithLifecycle()
    val stats by vm.turnStats.collectAsStateWithLifecycle()
    val liveUsage by vm.liveUsage.collectAsStateWithLifecycle()
    // The footer format follows the provider, because what is worth printing
    // differs: Anthropic has no reasoning count to show.
    val tokenFields by vm.tokenFields.collectAsStateWithLifecycle(initialValue = TokenField.DEFAULT)
    val footerIds = remember(messages) { turnFooterIds(messages) }
    // One key per live item, handed out so no two of them can ever collide: a
    // repeated key in a lazy list is not a glitch but a crash, and the streaming
    // blocks were stamped by the millisecond they arrived.
    val liveKeys = remember(liveBlocks) { uniqueLiveKeys(liveBlocks) }
    val providers by vm.providers.collectAsStateWithLifecycle()
    val providerStatuses by vm.providerStatuses.collectAsStateWithLifecycle()
    val pickedProvider by vm.selectedProvider.collectAsStateWithLifecycle()
    val pickedModel by vm.selectedModel.collectAsStateWithLifecycle()
    val hasStoredRoute by vm.hasStoredRoute.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Back closes the chat drawer first. This handler is declared after the
    // screen-level BackHandlers, so Compose gives it priority while the drawer
    // is open; the app only leaves once the drawer is closed.
    BackHandler(
        enabled = drawer.isOpen || drawer.targetValue != DrawerValue.Closed,
    ) {
        scope.launch { drawer.close() }
    }
    var draft by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<ChatSession?>(null) }
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ChatSession?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<ChatMessage?>(null) }
    var toast by remember { mutableStateOf("") }
    var showPushNowDialog by remember { mutableStateOf(false) }
    var pushNowBusy by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(
            settings = settings, history = history, socket = socket,
            sessionId = vm.sessionId, onBack = { showSettings = false },
            discovery = discovery,
        )
        return
    }
    if (showProviders) {
        ProvidersScreen(history = history, onBack = { showProviders = false })
        return
    }

    // Two system pickers behind one "+": the photo picker for images and video,
    // the document picker for anything else. Both take a whole selection, so a
    // batch of screenshots is one trip rather than one per file. Neither needs a
    // storage permission.
    var showAttachSheet by remember { mutableStateOf(false) }
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> vm.attach(uris) }
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACH_PER_PICK),
    ) { uris -> vm.attach(uris) }
    val pendingAttachments by vm.pendingAttachments.collectAsStateWithLifecycle()
    val uploading by vm.uploading.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val clip = LocalClipboardManager.current
    // One clock for the whole screen: the footer and the rate read the same
    // instant, so their numbers agree with each other.
    //
    // It is sampled as a pair because the two readings are not interchangeable.
    // `elapsedRealtime` counts from boot, which is what a duration needs and
    // what a wall clock cannot use: formatClock turns a timestamp into a date,
    // and handed it the uptime it printed the uptime as the time of day. The
    // footer showed "19:42" beside a message sent at 05:13.
    var tick by remember { mutableStateOf(Tick.now()) }
    val nowMs = tick.elapsed
    LaunchedEffect(busy) {
        while (busy) {
            tick = Tick.now()
            kotlinx.coroutines.delay(200)
        }
    }
    // What the model button under the field reads: the model alone. The footer
    // carries the whole route because it belongs to one turn, but a button
    // squeezed in beside "CMD" has no room for a provider name, and the model
    // is the half a person recognises. Same fallback as the footer, so the two
    // name the same model for the same chat.
    val modelButtonLabel = pickedModel
        .ifBlank { providers.firstOrNull()?.defaultModel.orEmpty() }
        .ifBlank { stringResource(R.string.agent_default) }
    // Everything a chat can be asked to do that is not a message. The list has
    // a fixed shape — a command that has nothing to do is greyed rather than
    // removed, so the menu does not move under the thumb between openings.
    val copiedText = stringResource(R.string.copied)
    val copyHintText = stringResource(R.string.copy_long_press)
    // One copy action for the whole thread, made once and handed to every row.
    // Building it per row makes each row's arguments a new instance on every
    // recomposition, so Compose rebuilt the visible page for every streamed
    // token — the thread re-styled its own Markdown twice a second while an
    // answer was writing, and that is the stutter.
    val copyMessage: (ChatMessage) -> Unit = remember(clip, copiedText) {
        { m: ChatMessage ->
            clip.setText(AnnotatedString(m.text.ifBlank { m.toolArgs }))
            toast = copiedText
        }
    }
    val composerCommands = listOf(
        ComposerCommand(stringResource(R.string.new_chat)) { vm.newChat() },
        ComposerCommand(stringResource(R.string.stop), enabled = busy) { vm.stop() },
        ComposerCommand(stringResource(R.string.clear_draft), enabled = draft.isNotBlank()) { draft = "" },
        ComposerCommand(stringResource(R.string.refresh)) { vm.refresh() },
        ComposerCommand(stringResource(R.string.settings)) { showSettings = true },
    )
    // Uptime is counted on the phone from the daemon's start time, so it has to
    // advance on its own. A one-second tick is enough for a "2h 15m" readout.
    var uptimeNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(daemonStartedAt) {
        while (daemonStartedAt > 0L) {
            uptimeNow = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    LaunchedEffect(toast) {
        if (toast.isNotBlank()) {
            kotlinx.coroutines.delay(2200)
            toast = ""
        }
    }
    // The thread holds a page of rows, not the whole history. Everything below
    // is what keeps that window feeling like a chat rather than a list that
    // reloads: the top row asks for the next page, the row that was under the
    // thumb stays there when that page arrives, and the bottom follows new
    // turns only while the reader is already at the bottom.
    val showOlderHeader = hasOlder || loadingOlder
    val olderHeaderItems = if (showOlderHeader) 1 else 0
    var olderAnchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var lastScrolledTail by remember { mutableStateOf<String?>(null) }
    // The chat this list has already anchored. A boolean went wrong when the
    // screen switched chats: it was already true from the previous chat, so the
    // new one kept the old scroll position and opened at the top of its thread.
    var scrolledSession by remember { mutableStateOf<String?>(null) }
    var followTail by remember { mutableStateOf(true) }
    val currentHasOlder by rememberUpdatedState(hasOlder)
    val currentLoadingOlder by rememberUpdatedState(loadingOlder)
    val currentMessages by rememberUpdatedState(messages)
    val currentHeaderItems by rememberUpdatedState(olderHeaderItems)
    val currentSession by rememberUpdatedState(activeSession)

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount == 0 ||
                (last != null && last.index >= info.totalItemsCount - 3)
        }.distinctUntilChanged().collect { followTail = it }
    }

    // Auto-follow the tail for a new turn, and jump to the bottom once per chat
    // when its newest page first appears. A page prepended above changes the
    // item count but not the tail, so it never yanks the reader downwards.
    // Comparing the chat id rather than a one-shot boolean is what makes a
    // freshly opened (or switched-to) chat land on its newest row instead of
    // inheriting the previous chat's position at the top.
    val listTotal = listState.layoutInfo.totalItemsCount
    LaunchedEffect(activeSession, messages.lastOrNull()?.id, listTotal) {
        val tail = messages.lastOrNull()?.id ?: return@LaunchedEffect
        if (listTotal <= 0) return@LaunchedEffect
        if (scrolledSession != activeSession) {
            // On a chat switch the new rows can reach composition one frame
            // before the lazy list has laid them out, so listTotal may still be
            // the previous chat's smaller count. Mark the chat anchored only
            // once the layout actually contains every message; otherwise
            // scrollToItem would clamp to the old end and the next pass would
            // think it was already done.
            if (listTotal < messages.size) return@LaunchedEffect
            scrolledSession = activeSession
            olderAnchor = null
            followTail = true
            listState.scrollToItem(listTotal - 1)
            lastScrolledTail = tail
        } else if (tail != lastScrolledTail) {
            // The same stale-layout case for a new turn: wait until the list
            // has laid the new row out, otherwise the animation targets the
            // row before it and the turn stays below the fold.
            if (listTotal < messages.size) return@LaunchedEffect
            lastScrolledTail = tail
            if (followTail) listState.animateScrollToItem(listTotal - 1)
        }
    }

    // Loading the older page raises the window. Put the row that was at the top
    // back where it was, so the new content appears above instead of under the
    // reader's eyes.
    LaunchedEffect(messages) {
        val anchor = olderAnchor ?: return@LaunchedEffect
        val index = messages.indexOfFirst { it.id == anchor.first }
        if (index >= 0) {
            listState.scrollToItem(index + olderHeaderItems, anchor.second)
            olderAnchor = null
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                // The first frame sits at index 0 for a moment before the
                // thread jumps to its newest row; asking then would load a
                // page nobody scrolled to and pull the view off the tail.
                if (scrolledSession != currentSession) return@collect
                if (!currentHasOlder || currentLoadingOlder || olderAnchor != null) return@collect
                // The header itself plus one row of slack: the page is asked
                // for before the reader actually runs out of content.
                if (index > currentHeaderItems + 1) return@collect
                val messageIndex = (index - currentHeaderItems).coerceAtLeast(0)
                currentMessages.getOrNull(messageIndex)?.let { first ->
                    olderAnchor = first.id to listState.firstVisibleItemScrollOffset
                }
                vm.loadOlder()
            }
    }

    // A streamed answer grows without adding a row, so the thread follows the
    // live text too instead of leaving it half under the input bar.
    //
    // It follows by pixels, not by an animation to the last item: restarting an
    // animated scroll on every coalesced flush made the thread lurch after each
    // token — the shudder the reader feels as the answer writes itself. The one
    // thing being followed is the last item's bottom edge, so that is what is
    // moved up to the fold.
    LaunchedEffect(liveText.length, liveBlocks.size, followTail) {
        if (!followTail) return@LaunchedEffect
        if (liveText.isBlank() && liveBlocks.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        if (info.totalItemsCount <= 0) return@LaunchedEffect
        val last = info.visibleItemsInfo.lastOrNull()
        if (last == null || last.index < info.totalItemsCount - 1) {
            // The tail has not been laid out yet: a block that has never been
            // measured cannot be reached by pixels, so jump to its slot.
            listState.scrollToItem(info.totalItemsCount - 1)
        } else {
            val overflow = last.offset + last.size - info.viewportEndOffset
            if (overflow > 0) listState.dispatchRawDelta(overflow.toFloat())
        }
    }

    if (pending != null) {
        ChatActionsSheet(
            session = pending!!,
            onRename = {
                renameTarget = pending
                renameText = pending!!.title
                pending = null
            },
            onDelete = { deleting = pending; pending = null },
            onDismiss = { pending = null },
        )
    }
    if (renameTarget != null) {
        RenameChatDialog(
            initial = renameText,
            onConfirm = { name ->
                renameTarget?.let { vm.renameChat(it.id, name) }
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    if (showPushNowDialog) {
        AlertDialog(
            onDismissRequest = { if (!pushNowBusy) showPushNowDialog = false },
            title = { Text("Push now?") },
            text = { Text("Signals the daemon to end this session: it backs up and starts the next one. Confirm?") },
            confirmButton = {
                TextButton(
                    enabled = !pushNowBusy,
                    onClick = {
                        pushNowBusy = true
                        scope.launch {
                            val ok = history.pushNow()
                            pushNowBusy = false
                            showPushNowDialog = false
                            toast = if (ok) "Push now requested" else "Push now failed"
                        }
                    },
                ) { Text(if (pushNowBusy) "Sending…" else "Confirm") }
            },
            dismissButton = {
                TextButton(enabled = !pushNowBusy, onClick = { showPushNowDialog = false }) { Text("Cancel") }
            },
        )
    }
    if (deleting != null) {
        DeleteChatDialog(
            title = deleting!!.title,
            onConfirm = {
                vm.deleteChat(deleting!!.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
    if (showAttachSheet) {
        AttachSheet(
            onMedia = {
                showAttachSheet = false
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onFile = {
                showAttachSheet = false
                pickFiles.launch(arrayOf("*/*"))
            },
            onDismiss = { showAttachSheet = false },
        )
    }
    if (showModelPicker) {
        ChatModelSheet(
            providers = providers,
            statuses = providerStatuses.associateBy { it.id },
            pickedProvider = pickedProvider,
            pickedModel = pickedModel,
            hasStoredRoute = hasStoredRoute,
            onProvider = { vm.chooseProvider(it) },
            onModel = { vm.chooseModel(it) },
            onSave = { vm.saveModel(); showModelPicker = false },
            onClear = { vm.clearModel() },
            onDismiss = { showModelPicker = false },
        )
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            // Opening the list is the moment to pull the current chats.
            LaunchedEffect(drawer.currentValue) {
                if (drawer.currentValue != DrawerValue.Closed) vm.refresh()
            }
            SessionDrawerContent(
                sessions = sessions,
                activeId = sessId,
                onNewChat = { vm.newChat(); scope.launch { drawer.close() } },
                onOpen = { id -> vm.openChat(id); scope.launch { drawer.close() } },
                onLongPress = { pending = it },
                footer = {
                    // Settings lives in the drawer, like ChatGPT and Claude put
                    // it behind the hamburger, instead of an icon crowding the
                    // top bar next to the status dot. The footer is anchored to
                    // the bottom of the sheet, so this row sits low instead of
                    // floating under a long list.
                    UpdateRow(settings) { showSettings = true }
                },
            )
        }
    ) {
        Scaffold(
            // Edge to edge: the app bar and the input bar each handle their own
            // system-bar insets, so the thread gets the whole window instead of
            // being inset twice.
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    // The title used to carry the model chip under it. The chip
                    // is gone: a long model name under the title squeezed the
                    // title, and the model is a property of the next message
                    // rather than of the screen, so it moved down into the
                    // composer's own bottom row (see ChatComposer).
                    title = {
                        Text(
                            sessionTitle(sessions, sessId),
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showPushNowDialog = true }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Push now")
                        }
                        // Status at a glance: a coloured dot and how long the
                        // daemon has been up. No words — the colour is the state.
                        // The counter is on screen from the first frame, at zero
                        // until a start time is known, so the header is never
                        // blank while the app waits for the daemon.
                        ConnDot(conn)
                        val uptime = formatUptime(daemonStartedAt, uptimeNow, "HH:mm:ss")
                        if (uptime.isNotBlank()) {
                            Text(
                                uptime,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 2.dp, end = 6.dp),
                            )
                        }
                    },
                )
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        if (toast.isNotBlank()) {
                            Text(
                                toast,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                        if (busy) {
                            // Motion that means something: the agent is working
                            // and this is how long it has been at it.
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp)
                                    .semantics { contentDescription = "agent working" },
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            )
                        }
                        if (status.isNotEmpty()) {
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (notice.isNotEmpty()) {
                            Text(
                                notice,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (showOfflineCard) {
                            OfflineBanner(
                                messageRes = socketErr.takeIf { it != 0 } ?: R.string.offline_title,
                                onRetry = { vm.refresh() },
                            )
                        }
                        // What the session has cost, as the engine stored it on
                        // the session. The strip sits above the composer rather
                        // than in the thread because it is about the conversation,
                        // not about any one turn in it — and it is the one thing
                        // that has to stay visible while the thread scrolls.
                        val totals = remember(sessionUsage, liveUsage, messages) {
                            sessionTotals(sessionUsage ?: StoredTotals(), liveUsage, messages)
                        }
                        SessionStrip(
                            totals = totals,
                            // A turn in flight has no stored duration yet, so it
                            // adds its own elapsed time to the session's until it
                            // lands and the thread carries it.
                            liveMs = if (busy && stats.startedAtMs != 0L) {
                                stats.elapsedMs(nowMs)
                            } else {
                                0L
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        ChatComposer(
                            draft = draft,
                            onDraftChange = { draft = it },
                            busy = busy,
                            // The stop square only replaces send once the turn
                            // has said something back — a trace, a word, a tool
                            // call — or three seconds pass with nothing, in
                            // which case the turn is offered the stop anyway
                            // rather than stranding it behind a dead send.
                            // Until then the send arrow sits disabled, which is
                            // the harness's sending state.
                            // The request's own wait block is that sending
                            // state, not something said back, so it does not
                            // count here.
                            activityStarted = status.isNotEmpty() || liveReasoning.isNotEmpty() ||
                                liveText.isNotBlank() ||
                                liveBlocks.any { it !is LiveBlock.Think } ||
                                stats.elapsedMs(nowMs) > 3_000L,
                            onSend = { vm.send(draft); draft = "" },
                            connected = conn == ConnState.ONLINE,
                            onStop = { vm.stop() },
                            commands = composerCommands,
                            modelLabel = modelButtonLabel,
                            onModel = {
                                vm.loadProviders()
                                showModelPicker = true
                            },
                            onTerminal = { showTerminal = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            attachments = pendingAttachments,
                            uploading = uploading,
                            onAttach = { showAttachSheet = true },
                            onRemoveAttachment = { vm.removeAttachment(it) },
                        )
                    }
                }
            }
        ) { pad ->
            var threadLoaded by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(400); threadLoaded = true }
            LaunchedEffect(conn) {
                // The discovery row saying the daemon is alive is not proof
                // that this phone's run stream reached it: a bad token or a
                // stale tunnel can leave the dot red while D1 looks healthy.
                // Show the card whenever the live link is down.
                if (conn == ConnState.OFFLINE) {
                    kotlinx.coroutines.delay(1500L)
                    showOfflineCard = true
                } else {
                    showOfflineCard = false
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
                // A short thread sits just above the composer instead of
                // hugging the top with a screen of dead space under it.
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
                contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
            ) {
                if (threadLoaded && messages.isEmpty() && liveText.isBlank() && liveBlocks.isEmpty()) {
                    item { EmptyChatState() }
                }
                // The top of the loaded window. It is one item whether or not a
                // fetch is running, so the rows below do not shift by a slot
                // while the page is on its way; the item itself also keeps a
                // manual way to ask on a screen reader where scrolling to the
                // top is awkward.
                if (showOlderHeader) {
                    item(key = "older-page") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (loadingOlder) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                TextButton(onClick = { vm.loadOlder() }) {
                                    Text(
                                        stringResource(R.string.load_older),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                // The goals and plans this chat's turn is working through, kept
                // in step with the engine's store. It sits above the thread so
                // it reads as the turn's ledger, not one more message.
                val goalsPage = objectives?.takeIf { it.goals.isNotEmpty() || it.plans.isNotEmpty() }
                if (goalsPage != null) {
                    item(key = "goals-card") {
                        GoalsCard(goalsPage)
                    }
                }
                itemsIndexed(messages, key = { _, m -> m.id }) { index, m ->
                    MessageBlock(
                        m,
                        startsAfterUser = index > 0 && messages[index - 1].role == "user",
                        onCopy = copyMessage,
                        showFooter = m.id in footerIds,
                        tokenFields = tokenFields,
                        copyLabel = copyHintText,
                    )
                }
                // A failed turn stays visible as a retryable card until the user
                // retries or dismisses it — the question is already in D1, so
                // losing the error would lose the only way to resume.
                if (errorCard.isNotBlank()) {
                    item(key = "error-card") {
                        ErrorCard(
                            message = errorCard,
                            onRetry = { vm.retryLast() },
                            onDismiss = { vm.errorCard.value = "" },
                        )
                    }
                }
                // The live turn's words and tool calls, in the order they happened: a piece of
                // narration, then the tool it ran, then the next piece. Drawn in one
                // pass like this is what keeps the live thread from reshuffling when
                // the stored rows replace it — every call keeps its card, in the
                // same place, instead of piling at the bottom.
                liveBlocks.forEachIndexed { index, block ->
                    when (block) {
                        is LiveBlock.Say -> item(key = liveKeys[index]) {
                            MarkdownText(
                                text = block.text,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        is LiveBlock.Act -> item(key = liveKeys[index]) {
                            ToolStepCard(block.step, nowMs)
                        }
                        // Each request's own wait-and-thought, in the place it
                        // happened: a "Sending request to provider…" that becomes
                        // "Thinking…" and then a finished "Thought for …", so a
                        // turn with three tool rounds reads as three requests
                        // rather than one merged block at the bottom.
                        is LiveBlock.Think -> item(key = liveKeys[index]) {
                            val elapsed = when (block.phase) {
                                ThinkPhase.DONE ->
                                    (block.endedAtMs - block.startedAtMs).coerceAtLeast(0L)
                                else ->
                                    (nowMs - block.startedAtMs).coerceAtLeast(0L)
                            }
                            LiveThink(
                                phase = block.phase,
                                reasoning = block.reasoning,
                                elapsedMs = elapsed,
                            )
                        }
                        // Where the engine trimmed its own prompt. It is not a
                        // stored row: it marks the point in the live thread the
                        // older turns fell out of the window.
                        is LiveBlock.Compact -> item(key = liveKeys[index]) {
                            CompactMarker(
                                keptTurns = block.keptTurns,
                                droppedTurns = block.droppedTurns,
                                summary = block.summary,
                            )
                        }
                    }
                }
                // The answer being streamed right now. It is not in the
                // database yet; the stored row replaces it when the turn ends.
                if (liveText.isNotBlank()) {
                    item(key = "live-answer") {
                        LiveAnswer(liveText)
                    }
                }
            }
        }
    }
}

/** How a thinking level reads in the picker: blank is the model's own default. */
private fun thinkingLabel(level: String): String =
    if (level.isBlank()) "Default" else level.replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatModelSheet(
    providers: List<com.tulipskun.aixodia.data.model.ProviderView>,
    statuses: Map<String, com.tulipskun.aixodia.data.model.ProviderStatus>,
    pickedProvider: String,
    pickedModel: String,
    hasStoredRoute: Boolean,
    onProvider: (String) -> Unit,
    onModel: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var modelQuery by rememberSaveable { mutableStateOf("") }
    var thinkingMenu by remember { mutableStateOf(false) }
    var thinkingLevel by rememberSaveable { mutableStateOf("") }
    val provider = providers.firstOrNull { it.id == pickedProvider }
    val models = provider?.models.orEmpty()
    // The daemon's default model is the one a health check actually got an
    // answer from, so it leads the list: a catalogue of twenty models says
    // nothing about which one this key can use.
    val working = provider?.defaultModel.orEmpty()
    // Thinking levels are the daemon's per-model answer. Until it reports
    // thinking_levels, the set every provider accepts stands in so the picker
    // is usable; a daemon list always wins over it.
    val pickedModelView = models.firstOrNull { it.id == pickedModel }
    val daemonLevels = pickedModelView?.thinkingLevels.orEmpty()
    val thinkingOptions =
        if (daemonLevels.isNotEmpty()) listOf("") + daemonLevels else GenerationSettings.thinkingLevels
    val showThinking = pickedModelView?.supportsThinking == true || daemonLevels.isNotEmpty()
    val filtered = remember(models, modelQuery, working) {
        val searched = if (modelQuery.isBlank()) models else models.filter { it.id.contains(modelQuery, true) || it.name.contains(modelQuery, true) }
        if (working.isBlank() || searched.none { it.id == working }) searched
        else listOf(searched.first { it.id == working }) + searched.filter { it.id != working }
    }
    // A centred window, not a sheet: picking a model is a decision about this
    // chat, and a dialog in the middle of the screen reads as one — the sheet
    // had to be dragged up from the edge for what is a single choice, not a
    // list of actions.
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(min = 320.dp, max = 360.dp)
                .heightIn(max = 560.dp)
                .shadow(8.dp, MaterialTheme.shapes.extraLarge)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // This chat's own model: the main agent it answers with. The provider
            // is a drop-down rather than a row of chips so a long provider list
            // opens over the dialog instead of pushing the model list off screen.
            Text("Model", style = MaterialTheme.typography.titleMedium)
            // Always the picker, never a "no provider" notice: the built-in
            // keyless provider makes this list non-empty on any daemon that
            // answers, and a daemon that does not answer should show an empty
            // dropdown to pick from, not a dead-end message.
            ExposedDropdownMenuBox(
                expanded = providerMenu,
                onExpandedChange = { providerMenu = it },
            ) {
                OutlinedTextField(
                    value = pickedProvider,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenu)
                    },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = providerMenu,
                    onDismissRequest = { providerMenu = false },
                ) {
                    providers.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.id) },
                            onClick = { onProvider(p.id); providerMenu = false },
                        )
                    }
                }
            }
            val currentStatus = statuses[pickedProvider]
            val statusLine = when {
                currentStatus == null -> "No status"
                !currentStatus.probed -> "" // nothing to say until a test has run
                currentStatus.reachable -> buildString {
                    append("OK")
                    if (currentStatus.workingModel.isNotBlank()) append(" · ${currentStatus.workingModel}")
                }
                else -> "Unusable" + (if (currentStatus.lastError.isNotBlank()) " · ${currentStatus.lastError}" else "")
            }
            if (pickedProvider.isNotBlank() && statusLine.isNotBlank()) {
                Text(
                    statusLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The model is the picked provider's catalogue, so it is already
            // filtered by provider; typing narrows it further. A drop-down
            // rather than a visible list keeps the dialog from scrolling.
            ExposedDropdownMenuBox(
                expanded = modelMenu,
                onExpandedChange = { modelMenu = it },
            ) {
                OutlinedTextField(
                    value = if (modelMenu) modelQuery else pickedModel,
                    onValueChange = { modelQuery = it; modelMenu = true },
                    singleLine = true,
                    label = { Text("Model (${models.size})") },
                    placeholder = {
                        Text(if (modelMenu) "Type to search" else "Select a model")
                    },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenu)
                    },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = modelMenu,
                    onDismissRequest = { modelMenu = false },
                ) {
                    if (filtered.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No matching model") },
                            onClick = {},
                            enabled = false,
                        )
                    } else {
                        filtered.forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        m.id,
                                        fontWeight = if (m.id == pickedModel || m.id == working) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    )
                                },
                                onClick = {
                                    onModel(m.id)
                                    modelQuery = ""
                                    modelMenu = false
                                },
                            )
                        }
                    }
                }
            }
            // Chat-level thinking level. The choices are the daemon's; persisting
            // the pick per session still waits on its settings endpoint, so the
            // value lives in local state and does not change what the agent sends.
            if (showThinking) {
                ExposedDropdownMenuBox(
                    expanded = thinkingMenu,
                    onExpandedChange = { thinkingMenu = it },
                ) {
                    OutlinedTextField(
                        value = thinkingLabel(thinkingLevel),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Thinking level") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = thinkingMenu)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = thinkingMenu,
                        onDismissRequest = { thinkingMenu = false },
                    ) {
                        thinkingOptions.forEach { lvl ->
                            DropdownMenuItem(
                                text = { Text(thinkingLabel(lvl)) },
                                onClick = { thinkingLevel = lvl; thinkingMenu = false },
                            )
                        }
                    }
                }
            }
            Button(
                onClick = onSave,
                enabled = pickedProvider.isNotBlank() && pickedModel.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use for this chat") }
            TextButton(
                onClick = onClear,
                enabled = hasStoredRoute,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use agent default (clear this chat lock)") }

        }
    }
}

@Composable
private fun UpdateRow(settings: SettingsStore, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("App v" + BuildConfig.VERSION_NAME) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("App • $msg", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = {
                busy = true; msg = "Checking…"
                scope.launch {
                    try {
                        val up = UpdateManager.check()
                        if (up == null) {
                            msg = "Up to date (v" + BuildConfig.VERSION_NAME + ")"
                            return@launch
                        }
                        msg = "Found ${up.label}, downloading…"
                        val apk = UpdateManager.download(ctx, up) { p -> msg = "Loading $p% (${up.label})" }
                        msg = "Ready to install ${up.label}"
                        UpdateManager.install(ctx, apk)
                    } catch (e: Exception) {
                        msg = "Update failed: ${e.message}"
                    } finally { busy = false }
                }
            },
            enabled = !busy,
        ) { Text("Check for updates") }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }
    }
}


/**
 * Uptime as a clock-style readout, seconds included so it visibly ticks:
 * "02:15:30", "00:05:30", "00:00:45".
 */
fun formatUptime(
    startedUnix: Long,
    nowMs: Long = System.currentTimeMillis(),
    pattern: String = "HH:mm:ss",
): String {
    // No start time known yet (first open, nothing cached): a zero readout
    // rather than the epoch turned into hours.
    val secs = if (startedUnix <= 0L) 0L else (nowMs / 1000 - startedUnix).coerceAtLeast(0)
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    val sep = if (pattern.contains(":")) ":" else " "
    val parts = pattern.split(Regex("[:\\s]+")).filter { it.isNotBlank() }.mapNotNull { token ->
        when {
            token.startsWith("H") -> String.format(Locale.US, "%02d", h)
            token.startsWith("h") -> h.toString()
            token.startsWith("m") -> String.format(Locale.US, "%02d", m)
            token.startsWith("s") -> String.format(Locale.US, "%02d", s)
            else -> null
        }
    }
    return parts.joinToString(sep)
}


/** How many files one trip to the picker may bring back. */
private const val MAX_ATTACH_PER_PICK = 10


/**
 * One reading of the clock, taken once. Elapsed time counts from boot and is
 * what every duration on the screen is measured against; the wall clock is what
 * a timestamp has to be formatted from. Sampling them together keeps the two
 * from drifting apart within one frame.
 */
data class Tick(val elapsed: Long, val wall: Long) {
    companion object {
        fun now() = Tick(android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis())
    }
}
