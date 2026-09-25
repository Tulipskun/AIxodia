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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tulipskun.aixodia.BuildConfig
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.model.ChatSession
import com.tulipskun.aixodia.data.remote.AiDirectSocket
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.remote.HistoryApi
import com.tulipskun.aixodia.data.repo.ChatRepository
import com.tulipskun.aixodia.ui.settings.SettingsScreen
import com.tulipskun.aixodia.update.UpdateManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val userShape = RoundedCornerShape(
    topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp,
)
private val answerShape = RoundedCornerShape(
    topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(repo: ChatRepository, settings: SettingsStore, history: HistoryApi, socket: AiDirectSocket) {
    val endpoint by settings.endpointFlow.collectAsState(initial = "")
    val token by settings.tokenFlow.collectAsState(initial = "")
    val configured = endpoint.isNotBlank() && token.isNotBlank()
    val sessId by settings.sessionFlow.collectAsState(initial = "")
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
    val messages by vm.messages.collectAsState(initial = emptyList())
    val sessions by vm.sessions.collectAsState(initial = emptyList())
    val conn by vm.conn.collectAsState(initial = ConnState.OFFLINE)
    val socketErr by vm.socketError.collectAsState(initial = "")
    val status by vm.status.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    val liveText by vm.liveText.collectAsState()
    val liveThinkingMs by vm.liveThinkingMs.collectAsState()
    val liveThinkingAgent by vm.liveThinkingAgent.collectAsState()
    val liveSteps by vm.liveSteps.collectAsState()
    val subAgents by vm.subAgents.collectAsState()
    val stats by vm.turnStats.collectAsState()
    val providers by vm.providers.collectAsState()
    val providerStatuses by vm.providerStatuses.collectAsState()
    val pickedProvider by vm.selectedProvider.collectAsState()
    val pickedModel by vm.selectedModel.collectAsState()
    val hasStoredRoute by vm.hasStoredRoute.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<ChatSession?>(null) }
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameText by remember { mutableStateOf("") }
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
        var field by remember(renameText) { mutableStateOf(renameText) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("เปลี่ยนชื่อแชท") },
            text = {
                OutlinedTextField(
                    value = field, onValueChange = { field = it },
                    label = { Text("ชื่อแชท") }, singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget?.let { vm.renameChat(it.id, field) }
                    renameTarget = null
                }) { Text("บันทึก") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("ยกเลิก") } },
        )
    }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("ลบแชทนี้?") },
            text = { Text("แชท “${deleting!!.title}” และประวัติทั้งหมดจะถูกลบทั้งในเครื่องและบน D1") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteChat(deleting!!.id)
                    deleting = null
                }) { Text("ลบ") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("ยกเลิก") } },
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
            ModalDrawerSheet {
                Text(
                    "AIxodia • ${sessions.size} แชท",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(
                    onClick = { vm.newChat(); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("  เซสชันใหม่")
                }
                Divider(Modifier.padding(vertical = 8.dp))
                if (sessions.isEmpty()) {
                    Text("ยังไม่มีแชท — กด “แชทใหม่” เพื่อเริ่ม", Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
                sessions.forEach { s ->
                    SessionRow(s, active = s.id == sessId, onOpen = {
                        vm.openChat(s.id); scope.launch { drawer.close() }
                    }, onLongPress = { pending = s })
                }
                Divider(Modifier.padding(vertical = 8.dp))
                UpdateRow(settings)
            }
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
                            Text(sessionTitle(sessions, sessId), maxLines = 1)
                            // Which model this chat runs on, and a way to change
                            // it — the session id is plumbing, not information.
                            Surface(
                                onClick = {
                                    vm.loadProviders()
                                    showModelPicker = true
                                },
                                color = Color.Transparent,
                                shape = MaterialTheme.shapes.extraSmall,
                            ) {
                                Text(
                                    text = when {
                                        pickedProvider.isBlank() -> "ใช้ค่าของ agent (แตะเพื่อล็อกโมเดล)"
                                        pickedModel.isBlank() -> "$pickedProvider · เลือก model"
                                        else -> "$pickedProvider · $pickedModel"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                )
                            }
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
                            // Being unable to talk to the daemon is the one problem
                            // the user can act on, so it gets a banner and a retry
                            // instead of a line of grey text.
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            ) {
                                Row(
                                    Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        socketErr.ifBlank { "ต่อ daemon ไม่ได้" },
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { vm.refresh() }) { Text("ลองใหม่") }
                                }
                            }
                        }
                        if (subAgents.isNotEmpty()) {
                            SubAgentPanel(
                                agents = subAgents,
                                nowMs = nowMs,
                                onStop = { vm.stopSubAgent(it) },
                            )
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("ส่งงานให้ agent…") },
                                shape = MaterialTheme.shapes.large,
                                maxLines = 4,
                            )
                            Spacer(Modifier.size(8.dp))
                            if (busy) {
                                // Stop is only meaningful while a turn is running;
                                // the daemon answers whether it actually stopped one.
                                FilledTonalIconButton(
                                    onClick = { vm.stop() },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = "หยุดการทำงาน")
                                }
                            } else {
                                FilledIconButton(
                                    onClick = { vm.send(draft); draft = "" },
                                    enabled = draft.isNotBlank(),
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = "ส่ง")
                                }
                            }
                        }
                    }
                }
            }
        ) { pad ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
            ) {
                if (messages.isEmpty() && liveText.isBlank() && liveSteps.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "เริ่มงานกับ agent",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "พิมพ์งานล่างจอ แล้ว agent จะทำต่อแม้ปิดแอป — ประวัติจะกลับมาตอนเปิดใหม่",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(messages, key = { it.id }) { m ->
                    MessageBlock(m, onCopy = {
                        clip.setText(AnnotatedString(m.text.ifBlank { m.toolArgs }))
                        toast = "คัดลอกข้อความแล้ว"
                    })
                }
                // The answer being streamed right now. It is not in the
                // database yet; the stored row replaces it when the turn ends.
                if (liveThinkingMs > 0L && liveText.isBlank()) {
                    item(key = "live-thinking") { ThinkingLine(liveThinkingMs, liveThinkingAgent) }
                }
                if (liveText.isNotBlank()) {
                    item(key = "live-answer") {
                        LiveAnswer(liveText, stats, routeLabel, nowMs)
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
    var query by remember { mutableStateOf("") }
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
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
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
        }
    }
}

@Composable
private fun ChatActionsSheet(
    session: ChatSession,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(session.title, maxLines = 2) },
        text = {
            Column {
                Text("แชทนี้มี ${if (session.lastSnippet.isBlank()) "ยังไม่มี" else session.lastSnippet.take(60)}")
            }
        },
        confirmButton = { TextButton(onClick = onRename) { Text("เปลี่ยนชื่อ") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("ลบ", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("ปิด") }
            }
        },
    )
}

@Composable
private fun SetupNeeded(endpoint: String, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ยังไม่ได้ตั้งค่าการเชื่อมต่อ", style = MaterialTheme.typography.headlineSmall)
        Text(
            "ใส่แค่ 2 อย่างที่หน้าตั้งค่า: URL ของ tunnel หรือ Worker และ D1 token\n" +
                "(ไม่ต้องใส่ account id — ไม่มีค่าใดฝังในแอป)",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (endpoint.isNotBlank()) {
            Text("ที่อยู่: $endpoint", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("เปิดหน้าตั้งค่า") }
    }
}

private fun sessionTitle(sessions: List<ChatSession>, id: String): String =
    sessions.firstOrNull { it.id == id }?.title?.takeIf { it.isNotBlank() } ?: id

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    s: ChatSession,
    active: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(RoundedCornerShape(4.dp))
                .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        )
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(s.title, maxLines = 1, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
            Text(
                s.lastSnippet.ifBlank { "ยังไม่มีข้อความ" },
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (s.unread > 0) {
            AssistChip(onClick = onOpen, label = { Text("${s.unread} ใหม่") })
        }
    }
}

/**
 * What the sub agents of this turn are doing, one row each, with a stop button
 * per row. The daemon sends the frames, so the panel never claims work that did
 * not happen: a row appears when a sub agent frame arrives and its state changes
 * only when the daemon says so.
 */
@Composable
private fun SubAgentPanel(
    agents: List<SubAgentActivity>,
    nowMs: Long,
    onStop: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "sub agent ${agents.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            agents.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (dot, tint) = when {
                        a.stopping -> "◐" to MaterialTheme.colorScheme.tertiary
                        a.running -> "●" to MaterialTheme.colorScheme.primary
                        else -> "✓" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${a.detail.ifBlank { a.label }} · ${seconds(a.elapsedMs(nowMs))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = when {
                                a.stopping -> "กำลังหยุด…"
                                a.running -> a.jobId
                                else -> "หยุดแล้ว · ${a.jobId}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Text(dot, color = tint, style = MaterialTheme.typography.labelSmall)
                    if (a.running && !a.stopping) {
                        Spacer(Modifier.size(4.dp))
                        TextButton(
                            onClick = { onStop(a.jobId) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("  หยุด")
                        }
                    }
                }
            }
        }
    }
}

/**
 * The footer of the chat: which model is answering, what it cost in tokens, how
 * long it has taken and how fast it is going. While the answer streams the
 * numbers are an estimate and say so; when the provider reports its usage they
 * become the real ones.
 */
/**
 * The numbers under the answer being streamed. While the answer is still
 * coming the counts are an estimate and say so with ≈; the provider's own counts
 * replace them on the closing frame, and the stored message that follows keeps
 * them (AX-095).
 */
@Composable
private fun TurnStatsLine(stats: TurnStats, model: String, nowMs: Long, live: Boolean) {
    if (stats.startedAtMs == 0L && model.isBlank()) return
    val elapsed = seconds(stats.elapsedMs(nowMs))
    val tokens = stats.outputTokensNow()
    val rate = stats.tokensPerSecond(nowMs)
    val mark = if (stats.exact) "" else "≈"
    Text(
        text = buildString {
            val route = stats.model.ifBlank { model }
            if (route.isNotBlank()) append(route).append(" · ")
            append(mark).append(tokens).append(" token")
            if (stats.inputTokens > 0) append(" (↑").append(stats.inputTokens).append(")")
            append(cacheText(stats.cacheRead, stats.cacheWrite))
            append(" · ").append(elapsed)
            if (rate > 0.0) append(" · ").append(mark).append(String.format(Locale.US, "%.1f", rate)).append(" tok/s")
            if (live && stats.running) append(" · กำลังทำงาน")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/** Whole seconds, or one decimal under ten, so a short turn is not "0s". */
private fun seconds(millis: Long): String = when {
    millis < 10_000 -> String.format(Locale.US, "%.1fs", millis / 1000.0)
    else -> "${millis / 1000}s"
}

/** Cache usage is a subset of input/output, so it reads as its own clause. */
private fun cacheText(cacheRead: Int, cacheWrite: Int): String = when {
    cacheRead > 0 && cacheWrite > 0 -> " · cache $cacheRead/$cacheWrite"
    cacheRead > 0 -> " · cache $cacheRead"
    cacheWrite > 0 -> " · cache เขียน $cacheWrite"
    else -> ""
}

@Composable
private fun ConnDot(c: ConnState) {
    val (label, color) = when (c) {
        ConnState.ONLINE -> "● ออนไลน์" to MaterialTheme.colorScheme.primary
        ConnState.CONNECTING -> "● กำลังต่อ" to MaterialTheme.colorScheme.tertiary
        ConnState.OFFLINE -> "● ออฟไลน์" to MaterialTheme.colorScheme.error
    }
    Text(label, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(end = 4.dp))
}

@Composable
private fun ThinkingLine(reasoningMs: Long, agent: String) {
    // Raw reasoning is progress, not transcript: the thread shows that thinking
    // is happening and how long the daemon has recorded, but never stores prose.
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentBadge(ChatMessage(id = "thinking", sessionId = "", seq = 0, role = "model", text = "", createdAt = 0, agent = agent))
        Text(
            "กำลังคิด · " + seconds(reasoningMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveAnswer(text: String, stats: TurnStats, routeLabel: String, nowMs: Long) {
    // A slow pulse on the "กำลังตอบ" line: motion that says the answer is
    // still coming, and stops the moment it has.
    val pulse by rememberInfiniteTransition(label = "live-answer").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "live-answer-alpha",
    )
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentBadge(ChatMessage(id = "live", sessionId = "", seq = 0, role = "model", text = "", createdAt = 0, agent = "main"))
            Text(
                "กำลังตอบ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = pulse),
            )
        }
        MarkdownText(
            text = text,
            modifier = Modifier.padding(top = 2.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
        )
        TurnStatsLine(stats = stats, model = routeLabel, nowMs = nowMs, live = true)
    }
}

/** The tool calls of the running turn, one line each, in the order they ran. */
@Composable
private fun ToolSteps(steps: List<com.tulipskun.aixodia.data.model.ToolStep>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "เครื่องมือ ${steps.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        steps.forEach { step ->
            val tint = if (!step.done) {
                MaterialTheme.colorScheme.tertiary
            } else if (step.isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = buildString {
                    append("● ")
                    append(step.name)
                    if (step.args.isNotBlank()) append(" ").append(step.args.take(40))
                    if (step.durationMs > 0L) append(" · ").append(seconds(step.durationMs))
                    append(when {
                        !step.done -> "…"
                        step.isError -> " — ล้มเหลว"
                        else -> " — เสร็จแล้ว"
                    })
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = tint,
            )
        }
    }
}

/**
 * One message in the thread. The thread is the document: messages run the full
 * width of the screen with no bubble around them, the model's answer is drawn
 * as Markdown, and only the agent that spoke and the clock stay small enough to
 * stay out of the way.
 */
@Composable
private fun MessageBlock(m: ChatMessage, onCopy: () -> Unit = {}) {
    val mine = m.role == "user"
    val isTool = m.role == "tool_call" || m.role == "tool_result"
    val body = m.text.ifBlank { m.toolArgs }
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onCopy)
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (m.agent.isNotBlank() || isTool) {
                AgentBadge(m)
                if (m.toolName.isNotBlank()) {
                    Text(
                        m.toolName,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = buildString {
                    if (m.pending) append("กำลังส่ง… · ")
                    append(clock(m.createdAt))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = if (m.agent.isNotBlank() || isTool) 4.dp else 0.dp),
            )
        }
        if (isTool) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        } else {
            MarkdownText(
                text = body,
                color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (!mine && !isTool) {
            MessageFooter(m)
        }
    }
}

/**
 * The footer of one answer: which model replied, what it cost, how long it took
 * and how fast it wrote (AX-095). Nothing is shown for a message that never
 * carried counts, rather than zeros pretending to be a measurement.
 */
@Composable
private fun MessageFooter(m: ChatMessage) {
    val line = buildString {
        if (m.model.isNotBlank()) append(m.model).append(" · ")
        if (m.tokensOut > 0 || m.tokensIn > 0 || m.cacheRead > 0 || m.cacheWrite > 0) {
            append(m.tokensOut).append(" token")
            if (m.tokensIn > 0) append(" (↑").append(m.tokensIn).append(")")
            append(cacheText(m.cacheRead, m.cacheWrite))
        }
        if (m.durationMs > 0L) {
            if (isNotEmpty()) append(" · ")
            append(seconds(m.durationMs))
            val rate = if (m.durationMs > 0) m.tokensOut * 1000.0 / m.durationMs else 0.0
            if (m.tokensOut > 0 && rate > 0.0) {
                append(" · ").append(String.format(Locale.US, "%.1f", rate)).append(" tok/s")
            }
        }
    }
    if (line.isBlank()) return
    Text(
        line,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

@Composable
private fun AgentBadge(m: ChatMessage) {
    val scheme = MaterialTheme.colorScheme
    val (label, bg, fg) = when {
        m.role == "user" -> Triple("คุณ", scheme.primaryContainer, scheme.onPrimaryContainer)
        m.agent == "main" -> Triple("MAIN AGENT", scheme.primaryContainer, scheme.onPrimaryContainer)
        m.agent == "sub" -> Triple("SUB AGENT", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        m.agent == "worker" -> Triple("WORKER", scheme.secondaryContainer, scheme.onSecondaryContainer)
        else -> return
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun clock(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

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
                        val token = settings.tokenFlow.first()
                        val up = UpdateManager.check(token)
                        if (up == null) {
                            msg = "ล่าสุดแล้ว (v" + BuildConfig.VERSION_NAME + ")"
                            return@launch
                        }
                        msg = "พบ ${up.tag} กำลังโหลด…"
                        val apk = UpdateManager.download(ctx, up, token) { p -> msg = "โหลด $p% (${up.tag})" }
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
