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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
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
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.tulipskun.aixodia.ui.LocalUiSpec
import com.tulipskun.aixodia.ui.display.AttachSheet
import com.tulipskun.aixodia.ui.display.ChatComposer
import com.tulipskun.aixodia.ui.display.ConnDot
import com.tulipskun.aixodia.ui.display.EmptyChatState
import com.tulipskun.aixodia.ui.display.ErrorCard
import com.tulipskun.aixodia.ui.display.formatClock
import com.tulipskun.aixodia.ui.display.LiveAnswer
import com.tulipskun.aixodia.ui.display.MessageBlock
import com.tulipskun.aixodia.ui.display.OfflineBanner
import com.tulipskun.aixodia.ui.display.SetupNeeded
import com.tulipskun.aixodia.ui.display.SpecScreen
import com.tulipskun.aixodia.ui.display.SubAgentPanel
import com.tulipskun.aixodia.ui.display.ThinkingLine
import com.tulipskun.aixodia.ui.display.TokenField
import com.tulipskun.aixodia.ui.display.ToolSteps
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
    // The token alone is enough to be configured: the daemon address now arrives
    // by discovery, so demanding it here would lock a fresh install on the setup
    // screen waiting for a value the app is about to fetch for itself. History
    // reads D1 directly and works with no address at all; only the live socket
    // waits, and it waits quietly.
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
        SetupNeeded(onOpen = { showSettings = true })
        return
    }

    val context = LocalContext.current
    var installing by remember { mutableStateOf(false) }
    var installNote by remember { mutableStateOf("") }
    val installScope = rememberCoroutineScope()
    val vm: ChatViewModel = viewModel(key = sessId) {
        ChatViewModel(repo, settings, sessId, context.applicationContext)
    }
    // One-tap update: the phone checks GitHub on start, so a new build is found
    // without asking anyone whether one exists.
    fun installUpdate() {
        installing = true
        installScope.launch {
            try {
                val latest = UpdateManager.check()
                if (latest == null) {
                    installNote = "เป็นเวอร์ชันล่าสุดแล้ว"
                    vm.updateAvailable.value = ""
                } else {
                    installNote = "กำลังโหลด ${latest.tag}…"
                    val apk = UpdateManager.download(context, latest) { p -> installNote = "โหลด $p%" }
                    UpdateManager.install(context, apk)
                    installNote = "พร้อมติดตั้ง ${latest.tag}"
                }
            } catch (e: Exception) {
                installNote = "อัปเดตไม่สำเร็จ: ${e.message}"
            } finally {
                installing = false
            }
        }
    }
    val messages by vm.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    val sessions by vm.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val conn by vm.conn.collectAsStateWithLifecycle(initialValue = ConnState.OFFLINE)
    val socketErr by vm.socketError.collectAsStateWithLifecycle(initialValue = 0)
    val status by vm.status.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val errorCard by vm.errorCard.collectAsStateWithLifecycle()
    val daemonStartedAt by vm.daemonStartedAt.collectAsStateWithLifecycle()
    val liveText by vm.liveText.collectAsStateWithLifecycle()
    val liveThinkingMs by vm.liveThinkingMs.collectAsStateWithLifecycle()
    val liveThinkingAgent by vm.liveThinkingAgent.collectAsStateWithLifecycle()
    val liveSteps by vm.liveSteps.collectAsStateWithLifecycle()
    val liveNarrations by vm.liveNarrations.collectAsStateWithLifecycle()
    val updateTag by vm.updateAvailable.collectAsStateWithLifecycle()
    val subAgents by vm.subAgents.collectAsStateWithLifecycle()
    val stats by vm.turnStats.collectAsStateWithLifecycle()
    // The footer format follows the provider, because what is worth printing
    // differs: Anthropic has no reasoning count to show.
    val tokenFields by vm.tokenFields.collectAsStateWithLifecycle(initialValue = TokenField.DEFAULT)
    val providers by vm.providers.collectAsStateWithLifecycle()
    val providerStatuses by vm.providerStatuses.collectAsStateWithLifecycle()
    val pickedProvider by vm.selectedProvider.collectAsStateWithLifecycle()
    val pickedModel by vm.selectedModel.collectAsStateWithLifecycle()
    val hasStoredRoute by vm.hasStoredRoute.collectAsStateWithLifecycle()
    val ui = LocalUiSpec.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<ChatSession?>(null) }
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ChatSession?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<ChatMessage?>(null) }
    // Which daemon-composed screen is open, if any (AX-UI vocabulary).
    var specScreen by remember { mutableStateOf<String?>(null) }
    val specValues by settings.specValuesFlow.collectAsStateWithLifecycle(initialValue = emptyMap())
    var toast by remember { mutableStateOf("") }

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
    val routeLabel = when {
        pickedProvider.isBlank() -> ""
        pickedModel.isBlank() -> pickedProvider
        else -> "$pickedProvider · $pickedModel"
    }
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
    LaunchedEffect(liveText.length, liveSteps.size) {
        if (liveText.isNotBlank() || liveSteps.isNotEmpty()) {
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
    // One dispatcher for every action a spec screen can name. An action the app
    // does not know is ignored, so the daemon may introduce new ones safely.
    val onSpecAction: (String, Map<String, String>) -> Unit = { action, params ->
        when (action) {
            "open_screen" -> params["screen"]?.takeIf { it.isNotBlank() }?.let { specScreen = it }
            "close" -> specScreen = null
            "open_settings" -> { specScreen = null; showSettings = true }
            "open_providers" -> { specScreen = null; showProviders = true }
            "new_chat" -> { specScreen = null; vm.newChat() }
            "refresh" -> vm.refresh()
            "send_text" -> { specScreen = null; vm.send(params["text"].orEmpty()) }
            "open_url" -> params["url"]?.takeIf { it.isNotBlank() }?.let { url ->
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    )
                }
            }
            "set_value" -> params["key"]?.takeIf { it.isNotBlank() }?.let { key ->
                scope.launch { settings.setSpecValue(key, params["value"].orEmpty()) }
            }
            // A spec screen can reach any daemon endpoint, so a new server-side
            // action (an engine update, a provider refresh) needs no new build.
            "api_post" -> params["path"]?.takeIf { it.isNotBlank() }?.let { path ->
                scope.launch {
                    val ok = runCatching { repo.postJson(path) }.getOrNull() != null
                    toast = if (ok) (params["ok"] ?: "เรียก $path แล้ว") else "เรียก $path ไม่สำเร็จ"
                }
            }
            "put_ui" -> params["patch"]?.takeIf { it.isNotBlank() }?.let { patch ->
                scope.launch {
                    runCatching { history.saveUiSpec(patch)?.let { settings.saveUiSpec(it) } }
                }
            }
        }
    }
    val openScreen = specScreen?.let { ui.screens[it] }
    if (openScreen != null) {
        BackHandler { specScreen = null }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text(openScreen.title.ifBlank { "หน้าจอ" }, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = { specScreen = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "ย้อนกลับ")
                        }
                    },
                )
            },
        ) { pad ->
            SpecScreen(
                screen = openScreen,
                values = specValues,
                onAction = onSpecAction,
                modifier = Modifier.padding(pad),
            )
        }
        return
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
                    TextButton(
                        onClick = { scope.launch { drawer.close() }; showSettings = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  " + ui.settings)
                    }
                    // Rows the daemon added to the drawer, each opening one of
                    // its composed screens.
                    ui.drawerItems.forEach { item ->
                        TextButton(
                            onClick = {
                                scope.launch { drawer.close() }
                                onSpecAction(item.action, item.params)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(item.title)
                        }
                    }
                    UpdateRow(settings)
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
                    title = {
                        Column {
                            Text(
                                sessionTitle(sessions, sessId),
                                maxLines = 1,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (ui.topbar.showModelChip) {
                                AssistChip(
                                    onClick = {
                                        vm.loadProviders()
                                        showModelPicker = true
                                    },
                                    label = {
                                        Text(
                                            when {
                                                pickedProvider.isBlank() -> ui.agentDefault
                                                pickedModel.isBlank() -> "$pickedProvider · ${ui.pickModel}"
                                                else -> "$pickedProvider · $pickedModel"
                                            },
                                            maxLines = 1,
                                        )
                                    },
                                    modifier = Modifier.height(32.dp),
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = ui.menu)
                        }
                    },
                    actions = {
                        if (ui.topbar.showRefresh) {
                            IconButton(onClick = { vm.refresh() }) {
                                Icon(Icons.Default.Refresh, contentDescription = ui.refresh)
                            }
                        }
                        // Status at a glance: a coloured dot and how long the
                        // daemon has been up. No words — the colour is the state.
                        if (ui.topbar.showStatusDot) ConnDot(conn)
                        if (ui.topbar.showUptime && daemonStartedAt > 0L) {
                            val uptime = formatUptime(daemonStartedAt, uptimeNow, ui.topbar.uptimeFormat)
                            if (uptime.isNotBlank()) {
                                Text(
                                    uptime,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 2.dp, end = 6.dp),
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
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
                                    .semantics { contentDescription = "agent กำลังทำงาน" },
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
                        if (updateTag.isNotBlank()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    installNote.ifBlank { "มีเวอร์ชันใหม่ $updateTag" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { installUpdate() },
                                    enabled = !installing,
                                ) { Text(if (installing) "…" else "ติดตั้ง") }
                                TextButton(onClick = { vm.updateAvailable.value = "" }) { Text("×") }
                            }
                        }
                        if (conn != ConnState.ONLINE) {
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
                        ChatComposer(
                            draft = draft,
                            onDraftChange = { draft = it },
                            busy = busy,
                            onSend = { vm.send(draft); draft = "" },
                            onStop = { vm.stop() },
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
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
                // A short thread sits just above the composer instead of
                // hugging the top with a screen of dead space under it.
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
                contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
            ) {
                if (messages.isEmpty() && liveText.isBlank() && liveSteps.isEmpty() && liveNarrations.isEmpty()) {
                    item { EmptyChatState() }
                }
                itemsIndexed(messages, key = { _, m -> m.id }) { index, m ->
                    MessageBlock(
                        m,
                        startsAfterUser = index > 0 && messages[index - 1].role == "user",
                        onCopy = {
                            clip.setText(AnnotatedString(m.text.ifBlank { m.toolArgs }))
                            toast = ui.copied
                        },
                        tokenFields = tokenFields,
                        copyLabel = ui.copyHint,
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
                // What the model said before each tool round, drawn as its own
                // answer block — the same shape the stored thread has once the
                // turn ends, so nothing jumps or merges when it does.
                liveNarrations.forEachIndexed { index, said ->
                    item(key = "live-narration-$index") {
                        Column(Modifier.fillMaxWidth()) {
                            MarkdownText(
                                text = said.text,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            // The clock a stored answer carries under its own
                            // text, so the block looks the same either way.
                            Text(
                                formatClock(said.atMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                    }
                }
                // The answer being streamed right now. It is not in the
                // database yet; the stored row replaces it when the turn ends.
                if (liveThinkingMs > 0L && liveText.isBlank()) {
                    item(key = "live-thinking") { ThinkingLine(liveThinkingMs, liveThinkingAgent) }
                }
                if (liveText.isNotBlank()) {
                    item(key = "live-answer") {
                        LiveAnswer(liveText, stats, routeLabel, tick, tokenFields)
                    }
                }
                if (liveSteps.isNotEmpty()) {
                    item(key = "live-steps") { ToolSteps(liveSteps) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("โมเดลของแชทนี้ (เฉพาะแชทนี้ ไม่ใช่ค่าเริ่มต้นสากล)", style = MaterialTheme.typography.titleMedium)
            Text(
                if (hasStoredRoute) "แชทนี้ล็อก provider/model ไว้แล้ว" else "แชทนี้ยังไม่ล็อก — ใช้ค่าของ agent",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (providers.isEmpty()) {
                Text(
                    "ยังไม่มี provider — เพิ่มหรือทดสอบ provider ในหน้าตั้งค่าก่อน",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (pickedProvider.isBlank()) {
                    Text(
                        "แชทนี้ยังใช้ค่าของ agent อยู่ — เลือก provider เพื่อล็อกไว้กับแชทนี้",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                            currentStatus == null -> "ยังไม่มีสถานะของ provider นี้ — ตรวจในหน้าตั้งค่า"
                            !currentStatus.probed -> "ยังไม่ทดสอบ · key ${currentStatus.keyCount}"
                            currentStatus.reachable -> buildString {
                                append("ใช้ได้ · key ${currentStatus.keyCount}")
                                if (currentStatus.workingModel.isNotBlank()) append(" · ตอบได้จริง ${currentStatus.workingModel}")
                            }
                            else -> "ใช้ไม่ได้" + (if (currentStatus.lastError.isNotBlank()) " · ${currentStatus.lastError}" else "")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("ค้นหาโมเดล (${models.size})") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pickedProvider.isBlank()) {
                    Text(
                        "เลือก provider ข้างบนก่อน",
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
                                    "ตอบได้จริง",
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
            ) { Text("ใช้กับแชทนี้") }
            TextButton(
                onClick = onClear,
                enabled = hasStoredRoute,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("ใช้ค่าของ agent (ล้างการล็อกแชทนี้)") }

            // Per-session sub-agent settings (ACP session config pattern):
            // each chat may pin its own sub provider/model instead of
            // inheriting the global agent defaults.
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Sub agent (เฉพาะแชทนี้)", style = MaterialTheme.typography.titleSmall)
            val subPickedProvider by vm.subAgentProvider.collectAsStateWithLifecycle()
            val subPickedModel by vm.subAgentModel.collectAsStateWithLifecycle()
            val subEnabled by vm.subAgentEnabled.collectAsStateWithLifecycle()
            val subPinned by vm.subAgentPinned.collectAsStateWithLifecycle()
            Text(
                if (subPinned) "แชทนี้ล็อก sub agent ไว้แล้ว" else "ใช้ค่าของ agent (sub agent ทั่วไป)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("เปิด sub agent", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
                                if (m.id == subWorking) {
                                    Text("ตอบได้จริง", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = { vm.setSubAgent(subPickedProvider, subPickedModel, subEnabled) },
                enabled = subPickedProvider.isNotBlank() && subPickedModel.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("บันทึก sub agent ของแชทนี้") }
            Text(
                "เปิด/ปิดใช้ได้เลยโดยไม่ต้องเลือก provider — แชทนี้จะใช้ sub agent ของ agent",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { vm.clearSubAgent() },
                enabled = subPinned,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("ใช้ค่าของ agent (ล้าง sub agent ของแชทนี้)") }
        }
    }
}

@Composable
private fun UpdateRow(settings: SettingsStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("แอป v" + BuildConfig.VERSION_NAME) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("แอป • $msg", style = MaterialTheme.typography.labelMedium)
        FilledTonalButton(
            onClick = {
                busy = true; msg = "กำลังตรวจ…"
                scope.launch {
                    try {
                        val up = UpdateManager.check()
                        if (up == null) {
                            msg = "ล่าสุดแล้ว (v" + BuildConfig.VERSION_NAME + ")"
                            return@launch
                        }
                        msg = "พบ ${up.tag} กำลังโหลด…"
                        val apk = UpdateManager.download(ctx, up) { p -> msg = "โหลด $p% (${up.tag})" }
                        msg = "พร้อมติดตั้ง ${up.tag}"
                        UpdateManager.install(ctx, apk)
                    } catch (e: Exception) {
                        msg = "อัปเดตล้มเหลว: ${e.message}"
                    } finally { busy = false }
                }
            },
            enabled = !busy,
        ) { Text("ตรวจอัปเดต") }
        Text("ติดตั้งทับตัวเดิม ข้อมูลแชตไม่หาย", style = MaterialTheme.typography.labelSmall)
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
    val secs = (nowMs / 1000 - startedUnix).coerceAtLeast(0)
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
