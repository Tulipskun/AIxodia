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
import com.tulipskun.aixodia.BuildConfig
import com.tulipskun.aixodia.update.UpdateManager
import kotlinx.coroutines.flow.first
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.Divider
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
import androidx.compose.ui.platform.LocalContext
import com.tulipskun.aixodia.EndpointKind
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
    val curEndpoint by settings.endpointFlow.collectAsState(initial = "")
    val curWs by settings.wsUrlFlow.collectAsState(initial = "")
    val curWorker by settings.workerUrlFlow.collectAsState(initial = "")
    val curToken by settings.tokenFlow.collectAsState(initial = "")
    val curSession by settings.sessionFlow.collectAsState(initial = "default")
    val conn by socket.state.collectAsState(initial = ConnState.OFFLINE)

    var address by remember(curEndpoint) { mutableStateOf(curEndpoint) }
    var showAdvanced by remember { mutableStateOf(false) }
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
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ใส่แค่ 2 อย่าง", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "1) ที่อยู่: URL ของ tunnel (https://<ชื่อ>.trycloudflare.com) " +
                            "หรือ URL ของ Worker (https://<ชื่อ>.<คุณ>.workers.dev)\n" +
                            "2) D1 token\n" +
                            "ไม่ต้องใส่ account id, provider หรือค่าอื่น — ระบบเดา URL ที่ต้องใช้ให้เอง",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("ที่อยู่ (tunnel หรือ Worker)") },
                placeholder = { Text("https://xxxx.trycloudflare.com") },
                supportingText = {
                    val r = SettingsStore.resolve(address)
                    when (r.kind) {
                        EndpointKind.TUNNEL -> Text("tunnel → ประวัติผ่าน ${r.worker}/api • สดที่ ${r.ws}")
                        EndpointKind.WORKER -> Text("Worker → ประวัติตรง • สดจะค้นหาอัตโนมัติจาก /api/node")
                        EndpointKind.NONE -> Text("ยังไม่ได้ใส่")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("D1 token") },
                supportingText = { Text("token ชุดเดียวของระบบ • ส่งเป็น header ไม่ฝังในแอป") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row {
                        val clip = LocalClipboardManager.current
                        IconButton(
                            onClick = { clip.getText()?.text?.let { if (it.isNotBlank()) token = it.trim() } },
                            enabled = !showToken,
                        ) { Icon(Icons.Default.ContentCopy, contentDescription = "วางจากคลิปบอร์ด") }
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "toggle token",
                            )
                        }
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        busy = true; msg = "กำลังตรวจ…"
                        scope.launch {
                            val r = SettingsStore.resolve(address)
                            val kindLabel = when (r.kind) {
                                EndpointKind.TUNNEL -> "tunnel"
                                EndpointKind.WORKER -> "Worker"
                                EndpointKind.NONE -> ""
                            }
                            if (r.kind == EndpointKind.NONE) {
                                msg = "ใส่ URL ที่ขึ้นต้นด้วย https:// ก่อน"
                                busy = false
                                return@launch
                            }
                            // A Worker URL is checked directly; a tunnel URL is
                            // checked through the proxy the daemon exposes.
                            val ok = runCatching { history.ping(r.worker, token) }.isSuccess
                            if (!ok) {
                                msg = "$kindLabel/token ไม่ผ่าน (401 = token ผิด) — ยังไม่บันทึก"
                                busy = false
                                return@launch
                            }
                            settings.saveEndpoint(address, token, session)
                            msg = if (r.kind == EndpointKind.TUNNEL) {
                                "บันทึกแล้ว — ใช้ tunnel นี้ทั้งประวัติและสด"
                            } else {
                                "บันทึกแล้ว — จะค้นหา URL ของ daemon ให้อัตโนมัติ"
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) { Text("บันทึก (ตรวจก่อน)") }
                OutlinedButton(
                    onClick = {
                        busy = true; msg = "กำลังทดสอบ…"
                        scope.launch {
                            val r = SettingsStore.resolve(address)
                            msg = try {
                                val n = history.ping(r.worker, token)
                                "สำเร็จ — เจอ $n เซสชัน"
                            } catch (e: Exception) {
                                "ไม่ผ่าน: ${e.message}"
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && address.isNotBlank(),
                ) { Text("ทดสอบ") }
            }
            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodyMedium)
            val resolved = SettingsStore.resolve(address)
            if (resolved.kind == EndpointKind.WORKER) {
                Divider(Modifier.padding(vertical = 4.dp))
                NodeCard(history = history, workerText = resolved.worker, tokenText = token,
                    onUse = { wsUrl ->
                        scope.launch {
                            settings.saveConnection(wsUrl, resolved.worker, token)
                            msg = "ใช้ URL ของ daemon แล้ว"
                        }
                    })
            }
            OutlinedButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "ซ่อนตั้งค่าขั้นสูง" else "ตั้งค่าขั้นสูง (แยก URL)")
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = session, onValueChange = { session = it }, label = { Text("Session ID") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                OutlinedTextField(
                    value = ws, onValueChange = { ws = it }, label = { Text("WS URL (ควบคุมเอง)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                OutlinedTextField(
                    value = worker, onValueChange = { worker = it }, label = { Text("Worker URL (ควบคุมเอง)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Button(
                    onClick = {
                        scope.launch {
                            settings.saveConnection(ws, worker, token)
                            settings.saveSession(session)
                            msg = "บันทึกค่าขั้นสูงแล้ว"
                        }
                    },
                    enabled = !busy,
                ) { Text("บันทึกค่าขั้นสูง") }
            }
            Divider(Modifier.padding(vertical = 4.dp))
            UpdateRow(settings)
            Text(
                "สถานะ WebSocket: " + when (conn) {
                    ConnState.ONLINE -> "● ออนไลน์ ($curWs)"
                    ConnState.CONNECTING -> "● กำลังต่อ…"
                    ConnState.OFFLINE -> "● ออฟไลน์ — ยังไม่ตั้งค่า หรือ daemon ไม่ออนไลน์"
                },
                style = MaterialTheme.typography.bodySmall,
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

@Composable
private fun UpdateRow(settings: SettingsStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("แอป v" + BuildConfig.VERSION_NAME) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 4.dp)) {
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
