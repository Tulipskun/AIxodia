package com.tulipskun.aixodia.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.layout.weight
import androidx.compose.ui.platform.LocalContext
import com.tulipskun.aixodia.EndpointKind
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.remote.AiDirectSocket
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.remote.HistoryApi
import com.tulipskun.aixodia.data.model.ProviderView
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
    sessionId: String,
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
    var token by remember(curToken) { mutableStateOf(curToken) }
    var showToken by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf<List<ProviderView>>(emptyList()) }
    var pickedProvider by remember { mutableStateOf("") }
    var pickedModel by remember { mutableStateOf("") }
    var modelsByProvider by remember { mutableStateOf<Map<String, List<ProviderView>>>(emptyMap()) }
    var agentSettings by remember { mutableStateOf<com.tulipskun.aixodia.data.model.AgentSettings?>(null) }

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
                            "ไม่ต้องใส่ account id, provider, session หรือค่าอื่น — " +
                            "ระบบเดา URL ที่ต้องใช้ให้เอง และเลือกแชทได้จากเมนูในแอป",
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
                            settings.saveEndpoint(address, token)
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
            Divider(Modifier.padding(vertical = 4.dp))
            Text("Provider / key / agent", style = MaterialTheme.typography.titleSmall)
            Text(
                "เพิ่ม provider และจัดการ key pool ได้จากที่นี่ — key ถูกส่งไปครั้งเดียวและอ่านกลับไม่ได้",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                OutlinedButton(
                    onClick = {
                        busy = true; msg = "กำลังโหลด…"
                        scope.launch {
                            providers = runCatching { history.providers() }.getOrDefault(emptyList())
                            val catalogue = runCatching { history.models() }.getOrDefault(emptyList())
                            modelsByProvider = catalogue.associate { it.id to it }
                            agentSettings = history.agentSettings()
                            val current = agentSettings?.main
                            if (current != null && current.provider.isNotBlank()) {
                                pickedProvider = current.provider
                                pickedModel = current.model
                            }
                            msg = if (providers.isEmpty()) "โหลด provider ไม่ได้" else "มี ${providers.size} provider"
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) { Text("โหลด provider") }
                Spacer(Modifier.padding(horizontal = 4.dp))
                OutlinedButton(
                    onClick = {
                        busy = true; msg = "กำลังตรวจการเข้าถึงใหม่…"
                        scope.launch {
                            providers = runCatching { history.refreshProviders() }.getOrDefault(emptyList())
                            val bad = providers.count { !it.reachable }
                            msg = if (bad == 0) "ทุก provider ใช้งานได้" else "$bad provider ใช้ไม่ได้"
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) { Text("ทดสอบใหม่") }
            }
            providers.forEach { provider ->
                ProviderRow(
                    provider = provider,
                    onAddKey = { key ->
                        scope.launch {
                            msg = history.changeKeys(provider.id, add = listOf(key))
                            providers = runCatching { history.providers() }.getOrDefault(providers)
                        }
                    },
                    onReplaceKeys = { keys ->
                        scope.launch {
                            msg = history.changeKeys(provider.id, replace = keys)
                            providers = runCatching { history.providers() }.getOrDefault(providers)
                        }
                    },
                    onRemoveLast = {
                        scope.launch {
                            msg = history.changeKeys(provider.id, remove = listOf(provider.keyCount - 1))
                            providers = runCatching { history.providers() }.getOrDefault(providers)
                        }
                    },
                    onDelete = {
                        scope.launch {
                            msg = history.removeProvider(provider.id)
                            providers = runCatching { history.providers() }.getOrDefault(providers)
                        }
                    },
                )
            }
            AddProviderCard(
                onAdd = { id, adapter, endpoint, key, freeOnly ->
                    scope.launch {
                        msg = history.addProvider(id, adapter, endpoint, listOf(key), freeOnly)
                        providers = runCatching { history.providers() }.getOrDefault(providers)
                    }
                },
            )

            Divider(Modifier.padding(vertical = 4.dp))
            Text("โมเดลของแต่ละ agent", style = MaterialTheme.typography.titleSmall)
            Text(
                "main agent คือคนที่คุณคุยด้วย, sub agent คือคนงานที่ถูกเรียกมาช่วย",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (providers.isEmpty()) {
                Text("โหลด provider ก่อนจะเลือกโมเดล", style = MaterialTheme.typography.bodySmall)
            } else {
                if (providers.none { it.reachable }) {
                    Text("ยังไม่มี provider ที่ใช้ได้ — key ของบางตัวอาจหมดอายุหรือใช้นอกแอปของผู้ให้บริการไม่ได้", style = MaterialTheme.typography.bodySmall)
                } else {
                    val reachable = providers.filter { it.reachable }
                    val main = agentSettings?.main ?: AgentRoute(
                        pickedProvider, pickedModel,
                    )
                    val sub = agentSettings?.sub ?: AgentRoute()
                    var subProvider by remember { mutableStateOf(sub.provider) }
                    var subModel by remember { mutableStateOf(sub.model) }
                    AgentRoutePicker("main agent", reachable, main.provider, main.model,
                        onProvider = { id ->
                            pickedProvider = id
                            pickedModel = defaultModelFor(id, modelsByProvider)
                        },
                        onModel = { pickedModel = it },
                        models = modelsByProvider)
                    Spacer(Modifier.padding(4.dp))
                    AgentRoutePicker("sub agent", reachable, subProvider, subModel,
                        onProvider = { id -> subProvider = id; subModel = defaultModelFor(id, modelsByProvider) },
                        onModel = { subModel = it },
                        models = modelsByProvider)
                    Spacer(Modifier.padding(4.dp))
                    Button(
                        onClick = {
                            busy = true; msg = "กำลังบันทึก…"
                            scope.launch {
                                val mainRoute = AgentRoute(pickedProvider, pickedModel)
                                val subRoute = AgentRoute(
                                    subProvider.ifBlank { pickedProvider },
                                    subModel.ifBlank { pickedModel },
                                )
                                msg = history.saveAgentSettings(AgentSettings(mainRoute, subRoute, true))
                                agentSettings = history.agentSettings()
                                busy = false
                            }
                        },
                        enabled = !busy && pickedProvider.isNotBlank() && pickedModel.isNotBlank(),
                    ) { Text("บันทึกโมเดลของ agent") }
                }
            }
            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodyMedium)
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

/** The provider's default model, which is the first one the daemon found. */
private fun defaultModelFor(id: String, catalogue: Map<String, List<ProviderView>>): String =
    catalogue[id]?.firstOrNull()?.defaultModel.orEmpty()

@Composable
private fun ProviderRow(
    provider: com.tulipskun.aixodia.data.model.ProviderStatus,
    onAddKey: (String) -> Unit,
    onReplaceKeys: (List<String>) -> Unit,
    onRemoveLast: () -> Unit,
    onDelete: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(provider.id, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    if (provider.reachable) "ใช้ได้" else "ใช้ไม่ได้",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (provider.reachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "${provider.adapter} · ${provider.endpoint} · key ${provider.keyCount} · model ${provider.modelCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (provider.lastError.isNotBlank()) {
                Text(
                    provider.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("เพิ่ม API key") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.padding(4.dp))
                Button(onClick = { onAddKey(key.trim()); key = "" }, enabled = key.isNotBlank()) { Text("เพิ่ม") }
            }
            Row {
                TextButton(onClick = onRemoveLast, enabled = provider.keyCount > 0) { Text("ลบ key ตัวสุดท้าย") }
                TextButton(onClick = onDelete) { Text("ลบ provider") }
            }
        }
    }
}

@Composable
private fun AddProviderCard(onAdd: (String, String, String, String, Boolean) -> Unit) {
    var id by remember { mutableStateOf("") }
    var adapter by remember { mutableStateOf("openai") }
    var endpoint by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text("เพิ่ม provider ใหม่", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = id, onValueChange = { id = it },
                label = { Text("ชื่อ (เช่น my-gateway)") }, singleLine = true,
            )
            OutlinedTextField(
                value = endpoint, onValueChange = { endpoint = it },
                label = { Text("endpoint เช่น https://api.example.com/v1") }, singleLine = true,
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it },
                label = { Text("API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Text("adapter: $adapter", style = MaterialTheme.typography.bodySmall)
            Row {
                listOf("openai", "anthropic", "gemini", "opencode").forEach { option ->
                    TextButton(onClick = { adapter = option }) { Text(option) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = freeOnly, onCheckedChange = { freeOnly = it })
                Text("ใช้เฉพาะโมเดลฟรี")
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onAdd(id.trim(), adapter, endpoint.trim(), key.trim(), freeOnly) },
                    enabled = id.isNotBlank() && endpoint.startsWith("https://") && key.isNotBlank(),
                ) { Text("เพิ่ม") }
            }
        }
    }
}

@Composable
private fun AgentRoutePicker(
    label: String,
    providers: List<com.tulipskun.aixodia.data.model.ProviderStatus>,
    provider: String,
    model: String,
    onProvider: (String) -> Unit,
    onModel: (String) -> Unit,
    models: Map<String, List<ProviderView>>,
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row {
        val names = providers.map { it.id }
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f)) {
                Text(if (provider.isBlank()) "เลือก provider" else provider)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                names.forEach { id ->
                    DropdownMenuItem(text = { Text(id) }, onClick = { onProvider(id); open = false })
                }
            }
        }
        Spacer(Modifier.padding(4.dp))
        var modelOpen by remember { mutableStateOf(false) }
        val options = models[provider].orEmpty()
        Box {
            OutlinedButton(
                onClick = { modelOpen = true },
                modifier = Modifier.weight(1f),
                enabled = options.isNotEmpty(),
            ) { Text(if (model.isBlank()) "เลือก model" else model, maxLines = 1) }
            DropdownMenu(expanded = modelOpen, onDismissRequest = { modelOpen = false }) {
                options.forEach { m ->
                    DropdownMenuItem(text = { Text(m.id) }, onClick = { onModel(m.id); modelOpen = false })
                }
            }
        }
    }
}

@Composable
private fun ProviderDropdown(providers: List<ProviderView>, picked: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(providers.firstOrNull { it.id == picked }?.id ?: "เลือก provider")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            providers.forEach { p ->
                DropdownMenuItem(
                    text = { Text("${p.id} (${p.models.size} models)") },
                    onClick = { onPick(p.id); open = false },
                )
            }
        }
    }
}

@Composable
private fun ModelDropdown(provider: ProviderView?, picked: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val models = provider?.models.orEmpty()
    Box {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = models.isNotEmpty(),
        ) { Text(picked.ifBlank { "เลือก model" }) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.id + if (m.supportsStreaming) " •stream" else "") },
                    onClick = { onPick(m.id); open = false },
                )
            }
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
