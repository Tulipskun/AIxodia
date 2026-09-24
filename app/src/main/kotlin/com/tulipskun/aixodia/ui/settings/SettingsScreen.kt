package com.tulipskun.aixodia.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.remote.AiDirectSocket
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.remote.HistoryApi
import com.tulipskun.aixodia.data.remote.NodeInfo
import kotlinx.coroutines.launch

/**
 * Connection settings (AX-030): which daemon / Worker / token / session
 * the app talks to, with one-tap tests so a misconfigured URL or a
 * not-yet-deployed D1 shows up here instead of a silent empty chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    history: HistoryApi,
    socket: AiDirectSocket,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val curWs by settings.wsUrlFlow.collectAsState(initial = "")
    val curWorker by settings.workerUrlFlow.collectAsState(initial = "")
    val curToken by settings.tokenFlow.collectAsState(initial = "")
    val curSession by settings.sessionFlow.collectAsState(initial = "default")
    val conn by socket.state.collectAsState(initial = ConnState.OFFLINE)

    var ws by remember(curWs) { mutableStateOf(curWs) }
    var worker by remember(curWorker) { mutableStateOf(curWorker) }
    var token by remember(curToken) { mutableStateOf(curToken) }
    var session by remember(curSession) { mutableStateOf(curSession) }
    var showToken by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ตั้งค่าการเชื่อมต่อ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "back")
                    }
                },
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ทดสอบก่อนได้เลย (mock)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "บนเครื่องคอม: go run ./mock/cmd/mockai แล้วใช้ค่าด้านล่าง\n" +
                            "• emulator: ws://10.0.2.2:39118/ws + http://10.0.2.2:39117\n" +
                            "• มือถือจริง: เปลี่ยน 10.0.2.2 เป็น IP LAN ของเครื่องนั้น",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text("ai daemon (รับสดผ่าน WebSocket โดยตรง)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = ws, onValueChange = { ws = it }, label = { Text("WS URL") },
                placeholder = { Text("ws://192.168.1.50:18789/ws") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Text("Cloudflare Worker (ประวัติเก่าจาก D1)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = worker, onValueChange = { worker = it }, label = { Text("Worker URL") },
                placeholder = { Text("https://aixodia.<you>.workers.dev") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("D1 token (ใช้ทั้งเข้า DB และยืนยันตัวตนกับ daemon)") },
                supportingText = {
                    Text("token ชุดเดียวของระบบ • ส่งเป็น header ตอนเชื่อมต่อ ไม่ฝังในแอป")
                },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row {
                        val clip = LocalClipboardManager.current
                        IconButton(
                            onClick = { clip.getText()?.text?.let { if (it.isNotBlank()) token = it.trim() } },
                            enabled = !showToken,
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "วางจากคลิปบอร์ด")
                        }
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "toggle token",
                            )
                        }
                    }
                },
            )
            OutlinedTextField(
                value = session, onValueChange = { session = it }, label = { Text("Session ID") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        busy = true; msg = "กำลังตรวจค่า…"
                        scope.launch {
                            val workerOk = if (worker.isBlank() && curWorker.isNotBlank()) {
                                runCatching { history.ping("", token) }.isSuccess
                            } else {
                                runCatching { history.ping(worker, token) }.fold(
                                    onSuccess = { true },
                                    onFailure = { false },
                                )
                            }
                            if (!workerOk) {
                                msg = "Worker/token ไม่ผ่าน — ยังไม่บันทึก (401 = token ผิด, 404 = ยังไม่ deploy)"
                                busy = false
                                return@launch
                            }
                            settings.saveConnection(ws, worker, token)
                            settings.saveSession(session)
                            msg = "บันทึกแล้ว — daemon จะใช้ค่านี้ยืนยันตัวตนและดึง state จาก D1"
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) { Text("บันทึก (ตรวจก่อน)") }
                OutlinedButton(
                    onClick = {
                        busy = true; msg = "กำลังทดสอบ Worker…"
                        scope.launch {
                            try {
                                val n = history.ping(worker.ifBlank { curWorker }, token)
                                msg = "Worker OK — เจอ $n sessions ใน D1"
                            } catch (e: Exception) {
                                msg = "Worker ไม่ผ่าน: ${e.message} — รัน worker/setup.sh หรือยัง?"
                            } finally { busy = false }
                        }
                    },
                    enabled = !busy,
                ) { Text("ทดสอบ Worker") }
            }
            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodyMedium)
            NodeCard(history = history, workerText = worker, tokenText = token,
                onUse = { wsUrl ->
                    ws = wsUrl
                    scope.launch {
                        settings.saveConnection(wsUrl, worker.ifBlank { curWorker }, token)
                        msg = "ใช้ tunnel URL แล้ว — กลับไปแชตได้เลย"
                    }
                })
            Text(
                "สถานะ WebSocket: " + when (conn) {
                    ConnState.ONLINE -> "● online ($curWs)"
                    ConnState.CONNECTING -> "● connecting…"
                    ConnState.OFFLINE -> "● offline — ตรวจ WS URL ว่าถึง daemon ใน LAN หรือใช้ Worker /ws"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "มือถือเข้า 127.0.0.1 ของตัวเอง ไม่ใช่ของ server — ต้องใส่ IP LAN ของเครื่องที่รัน ai (เช่น ws://192.168.1.50:18789/ws)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun NodeCard(
    history: HistoryApi,
    workerText: String,
    tokenText: String,
    onUse: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var node by remember { mutableStateOf<NodeInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ai daemon ผ่าน quick tunnel (auto-discovery)", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    checking = true; err = ""
                    scope.launch {
                        try {
                            node = history.node(workerText, tokenText)
                            if (node == null) err = "ติดต่อ Worker ไม่ได้ — ตรวจ URL/token"
                        } catch (e: Exception) {
                            err = e.message ?: "error"
                        } finally { checking = false }
                    }
                },
                enabled = !checking,
            ) { Text("ค้นหา ai") }
            if (node?.online == true) {
                Button(onClick = { onUse(history.wsUrlFor(node!!.tunnelUrl)) }) { Text("ใช้ URL นี้") }
            }
        }
        val n = node
        if (n != null) {
            Text(
                if (n.online) "● ai online (${n.tunnelUrl}, heartbeat ${n.ageS}s, ${n.version})"
                else "● ai ออฟไลน์ (heartbeat ขาดเกิน 90s) — ไปรัน ai ให้เปิด tunnel ก่อน",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (err.isNotEmpty()) Text(err, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
}
