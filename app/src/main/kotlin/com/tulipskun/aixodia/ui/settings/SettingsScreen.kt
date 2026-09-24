@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.tulipskun.aixodia.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.BuildConfig
import com.tulipskun.aixodia.EndpointKind
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AgentRoute
import com.tulipskun.aixodia.data.model.AgentSettings
import com.tulipskun.aixodia.data.model.ModelView
import com.tulipskun.aixodia.data.model.ProviderStatus
import com.tulipskun.aixodia.data.model.ProviderView
import com.tulipskun.aixodia.data.remote.AiDirectSocket
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.remote.HistoryApi
import com.tulipskun.aixodia.data.remote.NodeInfo
import com.tulipskun.aixodia.update.UpdateManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class Picker { MainProvider, MainModel, SubProvider, SubModel }

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
    var loading by remember { mutableStateOf(true) }
    var providers by remember { mutableStateOf<List<ProviderStatus>>(emptyList()) }
    var probedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var catalogue by remember { mutableStateOf<List<ProviderView>>(emptyList()) }
    var mainRoute by remember { mutableStateOf(AgentRoute()) }
    var subRoute by remember { mutableStateOf(AgentRoute()) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var adding by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<ProviderStatus?>(null) }
    var deleting by remember { mutableStateOf<ProviderStatus?>(null) }
    var pickingKeyFor by remember { mutableStateOf<ProviderStatus?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }

    val load: suspend (probe: Boolean) -> Unit = { probe ->
        loading = true
        providers = runCatching {
            if (probe) history.refreshProviders() else history.providers()
        }.getOrDefault(emptyList())
        // A daemon that answers the probe knows the verdict, and says so. One that
        // does not report it is still believed about the providers it just tested.
        if (probe) {
            probedIds = providers.filter { it.probed || it.lastError.isNotBlank() }.map { it.id }.toSet()
        }
        catalogue = runCatching { history.models() }.getOrDefault(emptyList())
        runCatching { history.agentSettings() }.getOrNull()?.let {
            mainRoute = it.main
            subRoute = it.sub
        }
        loading = false
    }

    // The provider list, the model catalogue and the saved agent routes are
    // loaded on entry, so the model picker is never empty just because nobody
    // pressed a refresh button first.
    LaunchedEffect(Unit) { load(false) }

    val modelsByProvider = remember(catalogue) { catalogue.associate { it.id to it.models } }
    val routable = remember(providers) {
        providers.sortedBy { when {
            it.probed && it.reachable -> 0
            !it.probed -> 1
            else -> 2
        } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ตั้งค่า") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "ย้อนกลับ")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxWidth().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (msg.isNotBlank()) StatusBanner(msg)

            SectionCard("การเชื่อมต่อ", "ใส่แค่ URL ของ tunnel กับ D1 token — ไม่ต้องใส่ account id หรือค่าอื่น") {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("ที่อยู่ (tunnel หรือ Worker)") },
                    placeholder = { Text("https://xxxx.trycloudflare.com") },
                    supportingText = {
                        when (SettingsStore.resolve(address).kind) {
                            EndpointKind.TUNNEL -> Text("ใช้ทั้งประวัติและสดผ่าน tunnel นี้")
                            EndpointKind.WORKER -> Text("Worker: ประวัติตรง สดจะค้นหาจาก /api/node")
                            EndpointKind.NONE -> Text("ยังไม่ได้ใส่")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("D1 token") },
                    supportingText = { Text("ส่งเป็น header ไม่ฝังในแอป") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        val clip = LocalClipboardManager.current
                        IconButton(
                            onClick = { clip.getText()?.text?.let { if (it.isNotBlank()) token = it.trim() } },
                            enabled = !showToken,
                        ) { Icon(Icons.Default.ContentCopy, contentDescription = "วางจากคลิปบอร์ด") }
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showToken) "ซ่อน token" else "แสดง token",
                            )
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            busy = true; msg = ""
                            scope.launch {
                                val r = SettingsStore.resolve(address)
                                if (r.kind == EndpointKind.NONE) {
                                    msg = "ใส่ URL ที่ขึ้นต้นด้วย https:// ก่อน"
                                    busy = false
                                    return@launch
                                }
                                if (runCatching { history.ping(r.worker, token) }.isFailure) {
                                    msg = "URL/token ไม่ผ่าน (401 = token ผิด) — ยังไม่บันทึก"
                                    busy = false
                                    return@launch
                                }
                                settings.saveEndpoint(address, token)
                                msg = "บันทึกแล้ว"
                                load(false)
                                busy = false
                            }
                        },
                        enabled = !busy,
                    ) { Text("บันทึกและทดสอบ") }
                    OutlinedButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                val r = SettingsStore.resolve(address)
                                msg = runCatching { "ผ่าน — เจอ ${history.ping(r.worker, token)} เซสชัน" }
                                    .getOrElse { "ไม่ผ่าน: ${it.message}" }
                                busy = false
                            }
                        },
                        enabled = !busy && address.isNotBlank(),
                    ) { Text("ทดสอบ") }
                }
                val resolved = SettingsStore.resolve(address)
                if (resolved.kind == EndpointKind.WORKER) {
                    HorizontalDivider()
                    NodeCard(history = history, workerText = resolved.worker, tokenText = token,
                        onUse = { wsUrl ->
                            scope.launch {
                                settings.saveConnection(wsUrl, resolved.worker, token)
                                msg = "ใช้ URL ของ daemon แล้ว"
                            }
                        })
                }
            }

            SectionCard(
                "Provider และ key",
                if (loading) "กำลังโหลด…"
                else "${providers.size} provider · ${providers.sumOf { it.keyCount }} key · " +
                    "${providers.count { it.reachable && (it.probed || it.id in probedIds) }} ใช้ได้ · key อ่านกลับไม่ได้",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                load(true)
                                val bad = providers.count { !it.reachable }
                                msg = if (bad == 0) "ทุก provider ใช้งานได้" else "$bad provider ใช้ไม่ได้"
                                busy = false
                            }
                        },
                        enabled = !busy,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  ทดสอบใหม่")
                    }
                    OutlinedButton(onClick = { adding = true }, enabled = !busy) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  เพิ่ม provider")
                    }
                }
                if (!loading && providers.isEmpty()) {
                    Text(
                        "ยังไม่มี provider — กด “ทดสอบใหม่” เพื่อให้ daemon รายงาน หรือกด “เพิ่ม provider”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                providers.forEach { p ->
                    ProviderCard(
                        p,
                        tested = p.id in probedIds,
                        onAddKey = { key ->
                            scope.launch {
                                msg = history.changeKeys(p.id, add = listOf(key))
                                load(false)
                            }
                        },
                        onReplaceKeys = { replacing = p },
                        onPickKeyToRemove = { pickingKeyFor = p },
                        onTest = {
                            testingId = p.id
                            scope.launch {
                                val status = history.refreshProvider(p.id)
                                probedIds = probedIds + p.id
                                if (status == null) {
                                    msg = "ทดสอบ ${p.id} ไม่สำเร็จ ( daemon ไม่ตอบ)"
                                } else if (status.reachable) {
                                    msg = "${p.id} ใช้ได้ (${status.modelCount} model)"
                                } else {
                                    msg = "${p.id} ใช้ไม่ได้"
                                }
                                providers = providers.map { if (it.id == p.id) status else it }
                                testingId = null
                            }
                        },
                        testing = testingId == p.id,
                        onDelete = { deleting = p },
                    )
                }
            }

            SectionCard("โมเดลของแต่ละ agent", "main agent คือคนที่คุณคุยด้วย, sub agent คือคนงานที่ถูกเรียกมาช่วย") {
                RouteCard(
                    title = "main agent",
                    route = mainRoute,
                    modelCount = modelsByProvider[mainRoute.provider].orEmpty().size,
                    onPickProvider = { picker = Picker.MainProvider },
                    onPickModel = { picker = Picker.MainModel },
                )
                RouteCard(
                    title = "sub agent",
                    route = subRoute,
                    modelCount = modelsByProvider[subRoute.provider].orEmpty().size,
                    onPickProvider = { picker = Picker.SubProvider },
                    onPickModel = { picker = Picker.SubModel },
                )
                if (subRoute.provider.isBlank() || subRoute.model.isBlank()) {
                    Text(
                        "ยังไม่ได้เลือกของ sub agent — ถ้าปล่อยว่าง จะใช้ค่าเดียวกับ main",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            val sub = if (subRoute.provider.isBlank() || subRoute.model.isBlank()) mainRoute else subRoute
                            msg = history.saveAgentSettings(AgentSettings(mainRoute, sub, true))
                            subRoute = sub
                            load(false)
                            busy = false
                        }
                    },
                    enabled = !busy && mainRoute.provider.isNotBlank() && mainRoute.model.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("บันทึกโมเดลของ agent") }
            }

            SectionCard("แอป", "เชื่อมต่อกับ daemon: " + when (conn) {
                ConnState.ONLINE -> "ออนไลน์ ($curWs)"
                ConnState.CONNECTING -> "กำลังต่อ…"
                ConnState.OFFLINE -> "ออฟไลน์ — ยังไม่ได้ตั้งค่า หรือ daemon ไม่ออนไลน์"
            }) {
                Text(
                    "session: $curSession · แอป v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateRow(settings)
            }
        }
    }

    if (picker != null) {
        val isProvider = picker == Picker.MainProvider || picker == Picker.SubProvider
        val isMain = picker == Picker.MainProvider || picker == Picker.MainModel
        val route = if (isMain) mainRoute else subRoute
        if (isProvider) {
            ProviderSheet(
                title = if (isMain) "main agent — provider" else "sub agent — provider",
                providers = routable,
                probedIds = probedIds,
                current = route.provider,
                onPick = { id ->
                    val models = modelsByProvider[id].orEmpty()
                    val model = catalogue.firstOrNull { it.id == id }?.defaultModel
                        ?.takeIf { m -> models.any { it.id == m } }
                        ?: models.firstOrNull()?.id.orEmpty()
                    if (isMain) {
                        mainRoute = AgentRoute(id, model)
                    } else {
                        subRoute = AgentRoute(id, model)
                    }
                    picker = null
                },
                onDismiss = { picker = null },
            )
        } else {
            ModelSheet(
                title = if (isMain) "main agent — model" else "sub agent — model",
                provider = route.provider,
                models = modelsByProvider[route.provider].orEmpty(),
                current = route.model,
                onPick = { id ->
                    if (isMain) {
                        mainRoute = route.copy(model = id)
                    } else {
                        subRoute = route.copy(model = id)
                    }
                    picker = null
                },
                onDismiss = { picker = null },
            )
        }
    }

    if (adding) {
        AddProviderDialog(
            onDismiss = { adding = false },
            onAdd = { id, adapter, endpoint, key, freeOnly ->
                adding = false
                scope.launch {
                    msg = history.addProvider(id, adapter, endpoint, listOf(key), freeOnly)
                    load(true)
                }
            },
        )
    }

    replacing?.let { p ->
        ReplaceKeysDialog(
            provider = p,
            onDismiss = { replacing = null },
            onReplace = { keys ->
                replacing = null
                scope.launch {
                    msg = history.changeKeys(p.id, replace = keys)
                    load(false)
                }
            },
        )
    }

    pickingKeyFor?.let { p ->
        AlertDialog(
            onDismissRequest = { pickingKeyFor = null },
            title = { Text("ลบ key ตัวไหนของ ${p.id}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "key ไม่เคยถูกส่งกลับมาจาก daemon จึงเลือกได้แค่ลำดับ — ลองทีละตัวถ้าตัวสุดท้ายใช้ไม่ได้",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    repeat(p.keyCount) { index ->
                        val position = p.keyCount - index
                        TextButton(
                            onClick = {
                                pickingKeyFor = null
                                scope.launch {
                                    msg = history.changeKeys(p.id, remove = listOf(position - 1))
                                    load(false)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("ลบ key ตัวที่ $position") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickingKeyFor = null }) { Text("ยกเลิก") } },
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("ลบ provider ${p.id}?") },
            text = { Text("key ทั้งหมดของ ${p.id} จะถูกลบออกจาก daemon และ D1 และเอาออกจากตัวเลือกของ agent") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        msg = history.removeProvider(p.id)
                        load(false)
                    }
                }) { Text("ลบ", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("ยกเลิก") } },
        )
    }
}

@Composable
private fun StatusBanner(text: String) {
    val scheme = MaterialTheme.colorScheme
    val error = text.contains("ไม่", ignoreCase = true) || text.contains("HTTP", ignoreCase = true) ||
        text.contains("ผิด", ignoreCase = true) || text.contains("ล้ม", ignoreCase = true)
    Surface(
        color = if (error) scheme.errorContainer else scheme.secondaryContainer,
        contentColor = if (error) scheme.onErrorContainer else scheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (error) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text("  $text", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun RouteCard(
    title: String,
    route: AgentRoute,
    modelCount: Int,
    onPickProvider: () -> Unit,
    onPickModel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        PickerRow("provider", route.provider.ifBlank { "เลือก provider" }, onPick = onPickProvider)
        PickerRow(
            label = "model",
            value = route.model.ifBlank { "เลือก model" },
            hint = if (route.provider.isBlank()) "เลือก provider ก่อน" else "$modelCount โมเดลใน provider นี้",
            onPick = onPickModel,
        )
    }
}

@Composable
private fun PickerRow(label: String, value: String, hint: String = "", onPick: () -> Unit) {
    Surface(
        onClick = onPick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (hint.isNotBlank()) {
                    Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Default.ExpandMore, contentDescription = null)
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderStatus,
    tested: Boolean,
    onAddKey: (String) -> Unit,
    onReplaceKeys: () -> Unit,
    onPickKeyToRemove: () -> Unit,
    onTest: () -> Unit,
    testing: Boolean,
    onDelete: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(provider.id, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusPill(provider, tested)
            }
            Text(
                "${provider.adapter} · key ${provider.keyCount} · model ${provider.modelCount}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Text(
                provider.endpoint,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            if (provider.lastError.isNotBlank()) {
                Surface(
                    color = scheme.errorContainer,
                    contentColor = scheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        provider.lastError,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val clip = LocalClipboardManager.current
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("เพิ่ม API key") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        IconButton(onClick = {
                            clip.getText()?.text?.let { if (it.isNotBlank()) key = it.trim() }
                        }) { Icon(Icons.Default.ContentCopy, contentDescription = "วาง key จากคลิปบอร์ด") }
                    },
                )
                FilledTonalIconButton(
                    onClick = { onAddKey(key.trim()); key = "" },
                    enabled = key.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp),
                ) { Icon(Icons.Default.Key, contentDescription = "เพิ่ม key") }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                TextButton(onClick = onTest, enabled = !testing) {
                    if (testing) {
                        Text("กำลังทดสอบ…")
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("  ทดสอบ")
                    }
                }
                TextButton(onClick = onReplaceKeys) { Text("แทนที่ pool") }
                TextButton(
                    onClick = onPickKeyToRemove,
                    enabled = provider.keyCount > 0,
                ) { Text("ลบ key") }
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = scheme.error,
                    )
                    Text("  ลบ", color = scheme.error)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(provider: ProviderStatus, tested: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val (label, bg, fg) = when {
        !provider.probed && !tested -> Triple(
            "ยังไม่ทดสอบ",
            scheme.surfaceVariant,
            scheme.onSurfaceVariant,
        )
        provider.reachable -> Triple(
            "ใช้ได้ · ${provider.modelCount} model",
            scheme.primaryContainer,
            scheme.onPrimaryContainer,
        )
        else -> Triple("ใช้ไม่ได้", scheme.errorContainer, scheme.onErrorContainer)
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** The pill text a provider row or picker row shows for a status. */
private fun ProviderStatus.statusLine(tested: Boolean = false): String = when {
    !probed && !tested -> "ยังไม่ทดสอบ"
    reachable -> "ใช้ได้"
    else -> "ใช้ไม่ได้"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSheet(
    title: String,
    providers: List<ProviderStatus>,
    probedIds: Set<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (providers.isEmpty()) {
                Text(
                    "ยังไม่มี provider — เพิ่ม provider หรือกด “ทดสอบใหม่” ก่อน",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            providers.forEach { p ->
                val selected = p.id == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        )
                        .clickable { onPick(p.id) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.id,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            p.statusLine(p.id in probedIds) + " · key ${p.keyCount} · model ${p.modelCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected) Icon(Icons.Default.Check, contentDescription = null)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(
    title: String,
    provider: String,
    models: List<ModelView>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(models, query) {
        if (query.isBlank()) models else models.filter { it.id.contains(query, true) || it.name.contains(query, true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("ค้นหาโมเดล") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                provider.isBlank() -> Text(
                    "เลือก provider ก่อน",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                models.isEmpty() -> Text(
                    "provider นี้ยังไม่มีโมเดลที่ daemon ค้นพบ — กด “ทดสอบใหม่” เพื่อให้ค้นอีกครั้ง",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                filtered.isEmpty() -> Text(
                    "ไม่พบโมเดลที่ตรงกับ “$query”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(filtered) { m ->
                    val selected = m.id == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onPick(m.id) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.id,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            val tags = buildList {
                                if (m.supportsStreaming) add("stream")
                                if (m.supportsTools) add("tools")
                            }
                            if (tags.isNotEmpty()) {
                                Text(
                                    tags.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (selected) Icon(Icons.Default.Check, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String, Boolean) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var adapter by remember { mutableStateOf("openai") }
    var endpoint by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("เพิ่ม provider") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = id, onValueChange = { id = it },
                    label = { Text("ชื่อ (เช่น my-gateway)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = endpoint, onValueChange = { endpoint = it },
                    label = { Text("endpoint") }, singleLine = true,
                    placeholder = { Text("https://api.example.com/v1") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("adapter", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("openai", "anthropic", "gemini", "opencode").forEach { option ->
                        FilterChip(
                            selected = adapter == option,
                            onClick = { adapter = option },
                            label = { Text(option) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = freeOnly, onCheckedChange = { freeOnly = it })
                    Text("  ใช้เฉพาะโมเดลฟรี", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(id.trim(), adapter, endpoint.trim(), key.trim(), freeOnly) },
                enabled = id.isNotBlank() && endpoint.startsWith("https://") && key.isNotBlank(),
            ) { Text("เพิ่ม") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } },
    )
}

@Composable
private fun ReplaceKeysDialog(
    provider: ProviderStatus,
    onDismiss: () -> Unit,
    onReplace: (List<String>) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val keys = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("แทนที่ key ของ ${provider.id}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ใส่ key ทีละบรรทัด — pool เดิมจะถูกแทนที่ทั้งหมด", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("key (บรรทัดละ 1) ตอนนี้ ${provider.keyCount} ตัว") },
                    minLines = 3,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onReplace(keys) }, enabled = keys.isNotEmpty()) { Text("แทนที่") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } },
    )
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
        Text("ค้นหา ai daemon ผ่าน /api/node", style = MaterialTheme.typography.titleSmall)
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
            ) { Text("ค้นหา") }
            if (node?.online == true) {
                Button(onClick = { onUse(history.wsUrlFor(node!!.tunnelUrl)) }) { Text("ใช้ URL นี้") }
            }
        }
        val n = node
        if (n != null) {
            Text(
                if (n.online) "● daemon online (${n.tunnelUrl}, heartbeat ${n.ageS}s, ${n.version})"
                else "● daemon ออฟไลน์ (heartbeat ขาดเกิน 90s) — ไปรัน ai ให้เปิด tunnel ก่อน",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (err.isNotEmpty()) Text(err, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun UpdateRow(settings: SettingsStore) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("แอป v" + BuildConfig.VERSION_NAME) }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        Text("ติดตั้งทับตัวเดิม ข้อมูลแชทไม่หาย", style = MaterialTheme.typography.labelSmall)
    }
}
