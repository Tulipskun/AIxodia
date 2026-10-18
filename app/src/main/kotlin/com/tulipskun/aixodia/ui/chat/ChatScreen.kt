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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.tulipskun.aixodia.data.remote.AiDirectSocket
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.remote.DaemonDiscovery
import com.tulipskun.aixodia.data.remote.HistoryApi
import com.tulipskun.aixodia.data.repo.ChatRepository
import com.tulipskun.aixodia.ui.chat.ChatViewModel.LiveBlock
import com.tulipskun.aixodia.ui.display.AttachSheet
import com.tulipskun.aixodia.ui.display.ChatComposer
import com.tulipskun.aixodia.ui.display.ComposerCommand
import com.tulipskun.aixodia.ui.display.ConnDot
import com.tulipskun.aixodia.ui.display.EmptyChatState
import com.tulipskun.aixodia.ui.display.ErrorCard
import com.tulipskun.aixodia.ui.display.LiveAnswer
import com.tulipskun.aixodia.ui.display.LiveThink
import com.tulipskun.aixodia.ui.display.MessageBlock
import com.tulipskun.aixodia.ui.display.OfflineBanner
import com.tulipskun.aixodia.ui.display.SessionStrip
import com.tulipskun.aixodia.ui.display.sessionTotals
import com.tulipskun.aixodia.ui.display.SetupNeeded
import com.tulipskun.aixodia.ui.display.SubAgentPanel
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
import com.tulipskun.aixodia.update.UpdateManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    repo: ChatRepository,
    settings: SettingsStore,
    history: HistoryApi,
    socket: AiDirectSocket,
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

    // Settings is a screen branch of the chat, not a separate Android route: the
    // system back button has to return here, not leave the app.
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showProviders) { showProviders = false }

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

    val context = LocalContext.current
    val vm: ChatViewModel = viewModel(key = sessId) {
        ChatViewModel(repo, settings, sessId, context.applicationContext)
    }
    val messages by vm.messages.collectAsStateWithLifecycle(initialValue = emptyList())
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
    val liveReasoning by vm.liveReasoning.collectAsStateWithLifecycle()
    val subAgents by vm.subAgents.collectAsStateWithLifecycle()
    val stats by vm.turnStats.collectAsStateWithLifecycle()
    // The footer format follows the provider, because what is worth printing
    // differs: Anthropic has no reasoning count to show.
    val tokenFields by vm.tokenFields.collectAsStateWithLifecycle(initialValue = TokenField.DEFAULT)
    val footerIds = remember(messages) { turnFooterIds(messages) }
    val providers by vm.providers.collectAsStateWithLifecycle()
    val providerStatuses by vm.providerStatuses.collectAsStateWithLifecycle()
    val pickedProvider by vm.selectedProvider.collectAsStateWithLifecycle()
    val pickedModel by vm.selectedModel.collectAsStateWithLifecycle()
    val hasStoredRoute by vm.hasStoredRoute.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
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
    // One clock for the whole screen: the footer, the rate and every sub agent
    // row read the same instant, so their numbers agree with each other.
    //
    // It is sampled as a pair because the two readings are not interchangeable.
    // `elapsedRealtime` counts from boot, which is what a duration needs and
    // what a wall clock cannot use: formatClock turns a timestamp into a date,
    // and handed it the uptime it printed the uptime as the time of day. The
    // footer showed "19:42" beside a message sent at 05:13.
    var tick by remember { mutableStateOf(Tick.now()) }
    val nowMs = tick.elapsed
    LaunchedEffect(busy, subAgents.isNotEmpty()) {
        while (busy || subAgents.any { it.running }) {
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
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    // A streamed answer grows without adding a row, so the thread follows the
    // live text too instead of leaving it half under the input bar.
    LaunchedEffect(liveText.length, liveBlocks.size) {
        if (liveText.isNotBlank() || liveBlocks.isNotEmpty()) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
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
    // The sub-agent block reads from the daemon, so load it once per sheet
    // opening instead of on every recomposition.
    LaunchedEffect(showModelPicker) {
        if (showModelPicker) vm.loadSubAgentConfig()
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
            vm = vm,
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
                        if (subAgents.isNotEmpty()) {
                            SubAgentPanel(
                                agents = subAgents,
                                nowMs = nowMs,
                                onStop = { vm.stopSubAgent(it) },
                            )
                        }
                        // What the session has cost, summed from the thread. The
                        // strip sits above the composer rather than in the thread
                        // because it is about the conversation, not about any one
                        // turn in it — and it is the one thing that has to stay
                        // visible while the thread scrolls.
                        val totals = remember(messages) { sessionTotals(messages) }
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
            LaunchedEffect(conn, discoveryState.phase) {
                if (conn == ConnState.OFFLINE && (discovery == null || discoveryState.phase != com.tulipskun.aixodia.data.remote.DiscoveryPhase.CONNECTED)) {
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
                itemsIndexed(messages, key = { _, m -> m.id }) { index, m ->
                    MessageBlock(
                        m,
                        startsAfterUser = index > 0 && messages[index - 1].role == "user",
                        onCopy = {
                            clip.setText(AnnotatedString(m.text.ifBlank { m.toolArgs }))
                            toast = copiedText
                        },
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
                        is LiveBlock.Say -> item(key = "live-say-$index") {
                            MarkdownText(
                                text = block.text,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        is LiveBlock.Act -> item(key = "live-act-$index") {
                            ToolStepCard(block.step, nowMs)
                        }
                        // Each request's own wait-and-thought, in the place it
                        // happened: a "Sending request to provider…" that becomes
                        // "Thinking…" and then a finished "Thought for …", so a
                        // turn with three tool rounds reads as three requests
                        // rather than one merged block at the bottom.
                        is LiveBlock.Think -> item(key = "live-think-$index") {
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

@Composable
private fun ChatModelSheet(
    vm: ChatViewModel,
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
    var query by rememberSaveable { mutableStateOf("") }
    val provider = providers.firstOrNull { it.id == pickedProvider }
    val models = provider?.models.orEmpty()
    // The daemon's default model is the one a health check actually got an
    // answer from, so it leads the list: a catalogue of twenty models says
    // nothing about which one this key can use.
    val working = provider?.defaultModel.orEmpty()
    val filtered = remember(models, query, working) {
        val searched = if (query.isBlank()) models else models.filter { it.id.contains(query, true) || it.name.contains(query, true) }
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
            // This chat's own model: the main agent it answers with. Titled for the main
            // agent because the section below is the sub agent's, and calling both
            // "Sub agent" left the main one looking like it had gone missing.
            Text("Model for this chat", style = MaterialTheme.typography.titleMedium)
            if (providers.isEmpty()) {
                Text(
                    "No provider yet — add or test a provider in Settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providers.forEach { p ->
                        FilterChip(
                            selected = p.id == pickedProvider,
                            onClick = { onProvider(p.id) },
                            label = { Text(p.id) },
                        )
                    }
                }
                val currentStatus = statuses[pickedProvider]
                if (pickedProvider.isNotBlank()) {
                    Text(
                        when {
                            currentStatus == null -> "No status"
                            !currentStatus.probed -> "Not tested · key ${currentStatus.keyCount}"
                            currentStatus.reachable -> buildString {
                                append("OK · key ${currentStatus.keyCount}")
                                if (currentStatus.workingModel.isNotBlank()) append(" · answers ${currentStatus.workingModel}")
                            }
                            else -> "Unusable" + (if (currentStatus.lastError.isNotBlank()) " · ${currentStatus.lastError}" else "")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search models (${models.size})") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pickedProvider.isBlank()) {
                    Text(
                        "Select a provider above first",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The list takes what is left of the sheet, so every model is
                // reachable above the navigation bar instead of the first one
                // hiding under it.
                LazyColumn(Modifier.heightIn(max = 200.dp).fillMaxWidth()) {
                    items(filtered) { m ->
                        val selected = m.id == pickedModel
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                )
                                .clickable { onModel(m.id) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                m.id,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected || m.id == working) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            if (m.id == working) {
                                Text(
                                    "Answers",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                            if (m.supportsStreaming) {
                                Text(
                                    "stream",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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

            // Per-session sub-agent settings (ACP session config pattern):
            // each chat may pin its own sub provider/model instead of
            // inheriting the global agent defaults.
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Sub agent (this chat only)", style = MaterialTheme.typography.titleSmall)
            val subPickedProvider by vm.subAgentProvider.collectAsStateWithLifecycle()
            val subPickedModel by vm.subAgentModel.collectAsStateWithLifecycle()
            val subEnabled by vm.subAgentEnabled.collectAsStateWithLifecycle()
            val subPinned by vm.subAgentPinned.collectAsStateWithLifecycle()
            Text(
                if (subPinned) "This chat has a pinned sub agent" else "Use agent default (general sub agent)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enable sub agent", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = subEnabled,
                    onCheckedChange = { enabled -> vm.setSubAgent(subPickedProvider, subPickedModel, enabled) },
                )
            }
            if (providers.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providers.forEach { p ->
                        FilterChip(
                            selected = p.id == subPickedProvider,
                            onClick = { vm.chooseSubProvider(p.id) },
                            label = { Text(p.id) },
                        )
                    }
                }
                if (subPickedProvider.isNotBlank()) {
                    val subModels = providers.firstOrNull { it.id == subPickedProvider }?.models.orEmpty()
                    val subWorking = providers.firstOrNull { it.id == subPickedProvider }?.defaultModel.orEmpty()
                    val filtered = if (subModels.isEmpty()) emptyList()
                    else if (subWorking.isBlank() || subModels.none { it.id == subWorking }) subModels
                    else listOf(subModels.first { it.id == subWorking }) + subModels.filter { it.id != subWorking }
                    LazyColumn(Modifier.heightIn(max = 160.dp).fillMaxWidth()) {
                        items(filtered) { m ->
                            val selected = m.id == subPickedModel
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                    )
                                    .clickable { vm.chooseSubModel(m.id) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(m.id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            Button(
                onClick = { vm.setSubAgent(subPickedProvider, subPickedModel, subEnabled) },
                enabled = subPickedProvider.isNotBlank() && subPickedModel.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save sub agent for this chat") }
            Text(
                "Toggle without picking a provider — this chat uses the agent sub agent",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { vm.clearSubAgent() },
                enabled = subPinned,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use agent default (clear this chat sub agent)") }
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
                        msg = "Found ${up.tag}, downloading…"
                        val apk = UpdateManager.download(ctx, up) { p -> msg = "Loading $p% (${up.tag})" }
                        msg = "Ready to install ${up.tag}"
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
