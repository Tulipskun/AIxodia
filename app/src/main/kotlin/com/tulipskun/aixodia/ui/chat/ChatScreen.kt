package com.tulipskun.aixodia.ui.chat

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(repo: ChatRepository, settings: SettingsStore, history: HistoryApi, socket: AiDirectSocket) {
    val endpoint by settings.endpointFlow.collectAsState(initial = "")
    val token by settings.tokenFlow.collectAsState(initial = "")
    val configured = endpoint.isNotBlank() && token.isNotBlank()
    val sessId by settings.sessionFlow.collectAsState(initial = "")
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(settings = settings, history = history, socket = socket, onBack = { showSettings = false })
        return
    }

    // Unconfigured installs stop here — before the ViewModel exists — so a
    // first launch cannot reach the network layer and crash.
    if (!configured) {
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
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<ChatSession?>(null) }
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ChatSession?>(null) }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
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
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
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
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(sessionTitle(sessions, sessId), maxLines = 1)
                            Text(
                                "session: $sessId",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Column(
                    Modifier.fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    if (status.isNotEmpty()) {
                        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (notice.isNotEmpty()) {
                        Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (socketErr.isNotEmpty() && conn != ConnState.ONLINE) {
                        Text(socketErr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("ส่งงานให้ agent…") },
                            maxLines = 4,
                        )
                        IconButton(
                            onClick = { vm.send(draft); draft = "" },
                            enabled = !busy,
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "ส่ง")
                        }
                    }
                }
            }
        ) { pad ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "ยังไม่มีข้อความ — agent จะทำงานต่อแม้ปิดแอป แล้วข้อมูลจะมาตอนเปิดใหม่",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                items(messages, key = { it.id }) { m -> Bubble(m) }
            }
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
private fun Bubble(m: ChatMessage) {
    val mine = m.role == "user"
    val isTool = m.role == "tool_call" || m.role == "tool_result"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    mine -> MaterialTheme.colorScheme.primaryContainer
                    isTool -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.secondaryContainer
                }
            ),
        ) {
            Column(Modifier.padding(10.dp)) {
                if (m.agent.isNotBlank() || isTool) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                }
                Text(m.text.ifBlank { m.toolArgs })
                Text(
                    buildString {
                        if (m.pending) append("กำลังส่ง…")
                        append(clock(m.createdAt))
                        if (m.tokensOut > 0) append(" • ${m.tokensIn}↓ ${m.tokensOut}↑")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AgentBadge(m: ChatMessage) {
    val (label, bg) = when {
        m.role == "user" -> "คุณ" to MaterialTheme.colorScheme.primary
        m.agent == "main" -> "MAIN AGENT" to MaterialTheme.colorScheme.primary
        m.agent == "sub" -> "SUB AGENT" to MaterialTheme.colorScheme.tertiary
        m.agent == "worker" -> "WORKER" to MaterialTheme.colorScheme.secondary
        else -> return
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
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
