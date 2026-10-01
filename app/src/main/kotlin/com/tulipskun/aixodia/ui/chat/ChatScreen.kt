@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.tulipskun.aixodia.ui.chat

import androidx.activity.compose.BackHandler
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
import com.tulipskun.aixodia.data.remote.HistoryApi
import com.tulipskun.aixodia.data.repo.ChatRepository
import com.tulipskun.aixodia.ui.display.ChatComposer
import com.tulipskun.aixodia.ui.display.ConnDot
import com.tulipskun.aixodia.ui.display.EmptyChatState
import com.tulipskun.aixodia.ui.display.LiveAnswer
import com.tulipskun.aixodia.ui.display.MessageBlock
import com.tulipskun.aixodia.ui.display.OfflineBanner
import com.tulipskun.aixodia.ui.display.SetupNeeded
import com.tulipskun.aixodia.ui.display.SubAgentPanel
import com.tulipskun.aixodia.ui.display.ThinkingLine
import com.tulipskun.aixodia.ui.display.TokenField
import com.tulipskun.aixodia.ui.display.ToolSteps
import com.tulipskun.aixodia.ui.sessions.ChatActionsSheet
import com.tulipskun.aixodia.ui.sessions.DeleteChatDialog
import com.tulipskun.aixodia.ui.sessions.RenameChatDialog
import com.tulipskun.aixodia.ui.sessions.SessionDrawerContent
import com.tulipskun.aixodia.ui.sessions.sessionTitle
import com.tulipskun.aixodia.ui.settings.SettingsScreen
import com.tulipskun.aixodia.update.UpdateManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(repo: ChatRepository, settings: SettingsStore, history: HistoryApi, socket: AiDirectSocket) {
    val endpoint by settings.endpointFlow.collectAsStateWithLifecycle(initial = "")
    val token by settings.tokenFlow.collectAsStateWithLifecycle(initial = "")
    val configured = endpoint.isNotBlank() && token.isNotBlank()
    val sessId by settings.sessionFlow.collectAsStateWithLifecycle(initial = "")
    var showSettings by remember { mutableStateOf(false) }

    // Settings is a screen branch of the chat, not a separate Android route: the
    // system back button has to return here, not leave the app.
    BackHandler(enabled = showSettings) { showSettings = false }

    // Unconfigured installs stop here — before the ViewModel exists — so a
    // first launch cannot reach the network layer and crash. The settings
    // screen still has to open from here, or there would be no way to fix it.
    if (!configured) {
        if (showSettings) {
            SettingsScreen(
                settings = settings, history = history, socket = socket,
                sessionId = sessId, onBack = { showSettings = false },
            )
            return
        }
        SetupNeeded(endpoint = endpoint, onOpen = { showSettings = true })
        return
    }

    val vm: ChatViewModel = viewModel(key = sessId) { ChatViewModel(repo, settings, sessId) }
    val messages by vm.messages.collectAsStateWithLifecycle(initial = emptyList())
    val sessions by vm.sessions.collectAsStateWithLifecycle(initial = emptyList())
    val conn by vm.conn.collectAsStateWithLifecycle(initial = ConnState.OFFLINE)
    val socketErr by vm.socketError.collectAsStateWithLifecycle(initial = 0)
    val status by vm.status.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val liveText by vm.liveText.collectAsStateWithLifecycle()
    val liveThinkingMs by vm.liveThinkingMs.collectAsStateWithLifecycle()
    val liveThinkingAgent by vm.liveThinkingAgent.collectAsStateWithLifecycle()
    val liveSteps by vm.liveSteps.collectAsStateWithLifecycle()
    val subAgents by vm.subAgents.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val stats by vm.turnStats.collectAsStateWithLifecycle()
    // The footer format follows the provider, because what is worth printing
    // differs: Anthropic has no reasoning count to show.
    val tokenFields by vm.tokenFields.collectAsStateWithLifecycle(initial = TokenField.DEFAULT)
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

    if (showSettings) {
        SettingsScreen(
            settings = settings, history = history, socket = socket,
            sessionId = vm.sessionId, onBack = { showSettings = false },
        )
        return
    }

    val listState = rememberLazyListState()
    val clip = LocalClipboardManager.current
    // One clock for the whole screen: the footer, the rate and every sub agent
    // row read the same millisecond, so their numbers agree with each other.
    var nowMs by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(busy, subAgents.isNotEmpty()) {
        while (busy || subAgents.any { it.running }) {
            nowMs = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(200)
        }
    }
    val routeLabel = when {
        pickedProvider.isBlank() -> ""
        pickedModel.isBlank() -> pickedProvider
        else -> "$pickedProvider · $pickedModel"
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
                footer = { UpdateRow(settings) },
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
                            AssistChip(
                                onClick = {
                                    vm.loadProviders()
                                    showModelPicker = true
                                },
                                label = {
                                    Text(
                                        when {
                                            pickedProvider.isBlank() -> "Agent default"
                                            pickedModel.isBlank() -> "$pickedProvider · เลือก model"
                                            else -> "$pickedProvider · $pickedModel"
                                        },
                                        maxLines = 1,
                                    )
                                },
                                modifier = Modifier.height(32.dp),
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "เมนูเซสชัน")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "ดึงข้อมูลล่าสุด")
                        }
                        ConnDot(conn)
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "ตั้งค่า")
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
                        )
                    }
                }
            }
        ) { pad ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
            ) {
                if (messages.isEmpty() && liveText.isBlank() && liveSteps.isEmpty()) {
                    item { EmptyChatState() }
                }
                itemsIndexed(messages, key = { _, m -> m.id }) { index, m ->
                    MessageBlock(
                        m,
                        startsAfterUser = index > 0 && messages[index - 1].role == "user",
                        onCopy = {
                            clip.setText(AnnotatedString(m.text.ifBlank { m.toolArgs }))
                            toast = context.getString(R.string.copied)
                        },
                        tokenFields = tokenFields,
                        copyLabel = context.getString(R.string.copy_long_press),
                    )
                }
                // The answer being streamed right now. It is not in the
                // database yet; the stored row replaces it when the turn ends.
                if (liveThinkingMs > 0L && liveText.isBlank()) {
                    item(key = "live-thinking") { ThinkingLine(liveThinkingMs, liveThinkingAgent) }
                }
                if (liveText.isNotBlank()) {
                    item(key = "live-answer") {
                        LiveAnswer(liveText, stats, routeLabel, nowMs, tokenFields)
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
