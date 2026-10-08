@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.tulipskun.aixodia.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.BuildConfig
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.ui.display.TokenField
import com.tulipskun.aixodia.data.model.AgentRoute
import com.tulipskun.aixodia.data.model.AgentSettings
import com.tulipskun.aixodia.data.model.GenerationSettings
import com.tulipskun.aixodia.data.model.ModelView
import com.tulipskun.aixodia.data.model.SettingsView
import com.tulipskun.aixodia.data.model.ProviderStatus
import com.tulipskun.aixodia.data.model.ProviderView
import com.tulipskun.aixodia.data.remote.AiDirectSocket
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.remote.DaemonDiscovery
import com.tulipskun.aixodia.data.remote.DiscoveryPhase
import com.tulipskun.aixodia.data.remote.DiscoveryState
import com.tulipskun.aixodia.data.remote.HistoryApi
import com.tulipskun.aixodia.data.remote.NodeInfo
import com.tulipskun.aixodia.update.UpdateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private enum class Picker { MainProvider, MainModel, SubProvider, SubModel }

/** The Settings screen is split: the main page, and a page only for providers and keys. */
private enum class SettingsPage { Main, Providers }

/**
 * Connection settings (AX-030): the Cloudflare API token the app uses to read
 * and write D1 directly, the daemon tunnel address that carries the live socket
 * and the provider/model API, and one-tap tests so a wrong token or a missing
 * nodes row shows up here instead of as a silent empty chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    history: HistoryApi,
    socket: AiDirectSocket,
    sessionId: String,
    onBack: () -> Unit,
    discovery: DaemonDiscovery? = null,
) {
    val scope = rememberCoroutineScope()
    val curEndpoint by settings.endpointFlow.collectAsStateWithLifecycle(initialValue = "")
    val curWs by settings.wsUrlFlow.collectAsStateWithLifecycle(initialValue = "")
    val curToken by settings.tokenFlow.collectAsStateWithLifecycle(initialValue = "")
    val curAccount by settings.accountIdFlow.collectAsStateWithLifecycle(initialValue = "")
    val curDatabase by settings.databaseIdFlow.collectAsStateWithLifecycle(initialValue = "")
    val curSession by settings.sessionFlow.collectAsStateWithLifecycle(initialValue = "default")
    val conn by socket.state.collectAsStateWithLifecycle(initialValue = ConnState.OFFLINE)
    val tokenFields by settings.tokenFieldsFlow(curEndpoint).collectAsStateWithLifecycle(initialValue = emptyList<TokenField>())

    var address by rememberSaveable(curEndpoint) { mutableStateOf(curEndpoint) }
    var token by rememberSaveable(curToken) { mutableStateOf(curToken) }
    var showToken by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var providers by remember { mutableStateOf<List<ProviderStatus>>(emptyList()) }
    var providerQuery by rememberSaveable { mutableStateOf("") }
    var providerFilter by rememberSaveable { mutableStateOf(ProviderFilter.ALL) }
    var providerLimit by rememberSaveable { mutableStateOf(PROVIDER_PAGE) }
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }
    BackHandler(enabled = page == SettingsPage.Providers) { page = SettingsPage.Main }
    var probedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var catalogue by remember { mutableStateOf<List<ProviderView>>(emptyList()) }
    var mainRoute by remember { mutableStateOf(AgentRoute()) }
    var subRoute by remember { mutableStateOf(AgentRoute()) }
    var mainGeneration by remember { mutableStateOf(GenerationSettings()) }
    var subGeneration by remember { mutableStateOf(GenerationSettings()) }
    // Names the reader cleared, sent once with the save so the daemon removes
    // exactly those and leaves the rest of the stored values where they are.
    var clearedMain by remember { mutableStateOf<List<String>>(emptyList()) }
    var clearedSub by remember { mutableStateOf<List<String>>(emptyList()) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var adding by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<ProviderStatus?>(null) }
    var deleting by remember { mutableStateOf<ProviderStatus?>(null) }
    var pickingKeyFor by remember { mutableStateOf<ProviderStatus?>(null) }
    var confirmingRemove by remember { mutableStateOf<Pair<ProviderStatus, Int>?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }

    // load reports whether the daemon answered. A failed read keeps the list it
    // already had and names the failure: a screen that empties itself and then
    // says "ทุก provider ใช้งานได้" is worse than no answer at all.
    val load: suspend (probe: Boolean) -> Boolean = { probe ->
        loading = true
        val loaded = runCatching {
            if (probe) history.refreshProviders() else history.providers()
        }
        val rows = loaded.getOrNull()
        if (rows != null) {
            providers = rows
            // A daemon that answers the probe knows the verdict, and says so. One
            // that does not report it is still believed about the providers it
            // just tested.
            if (probe) {
                probedIds = rows.filter { it.probed || it.lastError.isNotBlank() }.map { it.id }.toSet()
            }
        } else {
            msg = "โหลด provider ไม่สำเร็จ: ${loaded.exceptionOrNull()?.message ?: "ไม่รู้สาเหตุ"}"
        }
        runCatching { history.models() }.getOrNull()?.let { catalogue = it }
        runCatching { history.agentSettings() }.getOrNull()?.let {
            mainRoute = AgentRoute(it.main.provider, it.main.model)
            subRoute = AgentRoute(it.sub.provider, it.sub.model)
            mainGeneration = it.main.generation
            subGeneration = it.sub.generation
        }
        loading = false
        rows != null
    }

    // The provider list, the model catalogue and the saved agent routes are
    // loaded on entry, so the model picker is never empty just because nobody
    // pressed a refresh button first.
    // Keyed to the session, not to Unit: the screen is a branch of the chat and
    // comes back in place on every settings toggle, so a Unit key would load
    // once and never again.
    LaunchedEffect(sessionId) { load(false) }

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
                title = { Text(if (page == SettingsPage.Providers) "Provider และ key" else "ตั้งค่า") },
                navigationIcon = {
                    IconButton(onClick = { if (page == SettingsPage.Providers) page = SettingsPage.Main else onBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "ย้อนกลับ")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
            if (msg.isNotBlank()) StatusBanner(msg)

            if (page == SettingsPage.Main) {
            SectionCard(
                "การเชื่อมต่อ",
                "ใส่ Cloudflare API token ตัวเดียว — account/database ถูกค้นหาให้อัตโนมัติ (ไม่มี Worker แล้ว)",
            ) {
                val clip = LocalClipboardManager.current
                LabelledField(
                    value = token, onValueChange = { token = it },
                    label = "Cloudflare API token",
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        IconButton(
                            onClick = { clip.getText()?.text?.let { if (it.isNotBlank()) token = it.trim() } },
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
                                settings.saveD1(token)
                                val found = runCatching { history.discover() }
                                val target = found.getOrNull()
                                if (target == null) {
                                    msg = "ค้นหา D1 ไม่สำเร็จ: " +
                                        (found.exceptionOrNull()?.message ?: "ไม่รู้สาเหตุ")
                                } else {
                                    val chats = runCatching { history.ping() }.getOrDefault(0)
                                    msg = "ผ่าน — ${target.accountName}/${target.databaseName} · เจอ $chats เซสชัน"
                                    load(false)
                                }
                                busy = false
                            }
                        },
                        enabled = !busy && token.isNotBlank(),
                    ) { Text("บันทึกและค้นหา D1") }
                    OutlinedButton(
                        onClick = {
                            busy = true; msg = ""
                            scope.launch {
                                msg = runCatching { "ผ่าน — เจอ ${history.ping()} เซสชัน" }
                                    .getOrElse { "ไม่ผ่าน: ${it.message}" }
                                busy = false
                            }
                        },
                        enabled = !busy && token.isNotBlank(),
                    ) { Text("ทดสอบ") }
                }
                Text(
                    if (curAccount.isBlank() || curDatabase.isBlank()) {
                        "D1: ยังไม่รู้ account/database — กดปุ่มบันทึกและค้นหา D1"
                    } else {
                        "D1: account ${curAccount.take(8)}… · database ${curDatabase.take(8)}…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                LabelledField(
                    value = address,
                    onValueChange = { address = it },
                    label = "ที่อยู่ daemon (tunnel)",
                    placeholder = "https://xxxx.trycloudflare.com",
                    hint = if (address.isBlank()) {
                        "เว้นว่างได้ — ประวัติยังอ่าน/เขียนจาก D1 ตรง · แชทสดจะต่อ wss://<host>/ws"
                    } else {
                        "แชทสดจะต่อ wss://<host>/ws · provider/model จะถาม daemon ที่นี่"
                    },
                    // A tunnel URL is long and has nowhere to break, so it wraps
                    // rather than ending mid-word at the field's edge.
                    singleLine = false,
                    minLines = 2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            busy = true; msg = ""
                            scope.launch {
                                settings.saveDaemon(address)
                                msg = "บันทึกที่อยู่ daemon แล้ว"
                                busy = false
                            }
                        },
                        enabled = !busy,
                    ) { Text("บันทึกที่อยู่") }
                }
                HorizontalDivider()
                NodeCard(
                    history = history,
                    discovery = discovery,
                    onUse = { tunnel ->
                        scope.launch {
                            settings.saveDaemon(tunnel)
                            address = tunnel
                            msg = "ใช้ URL ของ daemon แล้ว"
                        }
                    },
                )
            }

            }

            if (page == SettingsPage.Main) {
                val workingCount = remember(providers, probedIds) { countProviders(providers, probedIds).working }
                SectionCard(
                    "Provider และ key",
                    if (loading) "กำลังโหลด…"
                    else "${providers.size} provider · ${providers.sumOf { it.keyCount }} key · $workingCount ใช้ได้",
                ) {
                    Button(onClick = { page = SettingsPage.Providers }) { Text("จัดการ provider และ key") }
                }
            }

            if (page == SettingsPage.Providers) {
            SectionCard(
                "Provider และ key (ทั้งระบบ)",
                if (loading) "กำลังโหลด…"
                else "${providers.size} provider · ${providers.sumOf { it.keyCount }} key · " +
                    "${providers.count { it.reachable && (it.probed || it.id in probedIds) }} ใช้ได้ · key อ่านกลับไม่ได้ · ตัวตนของ provider สร้างแล้วเปลี่ยนไม่ได้",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                val answered = load(true)
                                val rows = providers
                                msg = when {
                                    !answered -> msg
                                    rows.isEmpty() -> "daemon ไม่ได้รายงาน provider เลย"
                                    rows.all { it.reachable } -> "ทุก provider ใช้งานได้"
                                    else -> "${rows.count { !it.reachable }} provider ใช้ไม่ได้"
                                }
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
                val counts = remember(providers, probedIds) { countProviders(providers, probedIds) }
                val visibleProviders = remember(providers, probedIds, providerQuery, providerFilter) {
                    filterProviders(providers, probedIds, providerQuery, providerFilter)
                }
                if (providers.size > PROVIDER_PAGE) {
                    LabelledField(
                        value = providerQuery,
                        onValueChange = { providerQuery = it; providerLimit = PROVIDER_PAGE },
                        label = "ค้นหา provider (ชื่อ, adapter, endpoint)",
                        leading = { Icon(Icons.Default.Search, contentDescription = null) },
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            Triple(ProviderFilter.ALL, "ทั้งหมด", counts.all),
                            Triple(ProviderFilter.WORKING, "ใช้ได้", counts.working),
                            Triple(ProviderFilter.UNTESTED, "ยังไม่ทดสอบ", counts.untested),
                            Triple(ProviderFilter.BROKEN, "ใช้ไม่ได้", counts.broken),
                        ).forEach { (filter, label, n) ->
                            FilterChip(
                                selected = providerFilter == filter,
                                onClick = { providerFilter = filter; providerLimit = PROVIDER_PAGE },
                                label = { Text("$label $n") },
                            )
                        }
                    }
                }
                visibleProviders.take(providerLimit).forEach { p ->
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
                                val status = runCatching { history.refreshProvider(p.id) }.getOrNull()
                                probedIds = probedIds + p.id
                                msg = when {
                                    status == null -> "ทดสอบ ${p.id} ไม่สำเร็จ ( daemon ไม่ตอบ)"
                                    status.reachable && status.workingModel.isNotBlank() ->
                                        "${p.id} ใช้ได้ (${status.workingModel})"
                                    status.reachable -> "${p.id} ใช้ได้ (${status.modelCount} model)"
                                    else -> "${p.id} ใช้ไม่ได้"
                                }
                                if (status != null) {
                                    providers = providers.map { if (it.id == p.id) status else it }
                                }
                                testingId = null
                            }
                        },
                        testing = testingId == p.id,
                        onDelete = { deleting = p },
                    )
                }
                if (providers.isNotEmpty()) {
                    val shown = minOf(providerLimit, visibleProviders.size)
                    Text(
                        "แสดง $shown จาก ${visibleProviders.size} รายการที่ตรงกับตัวกรอง",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (visibleProviders.size > shown) {
                        OutlinedButton(onClick = { providerLimit += PROVIDER_PAGE }) {
                            Text("แสดงเพิ่ม ${minOf(PROVIDER_PAGE, visibleProviders.size - shown)} รายการ")
                        }
                    }
                }
            }

            }

            if (page == SettingsPage.Main) {
            SectionCard("โมเดลค่าเริ่มต้นของ agent (สากล)", "ค่าที่นี่ใช้กับทุกแชทที่ไม่ได้ล็อก provider/model ไว้เอง main agent คือคนที่คุณคุยด้วย, sub agent คือคนงานที่ถูกเรียกมาช่วย") {
                RouteCard(
                    title = "ค่าเริ่มต้นสากล — main agent",
                    route = mainRoute,
                    modelCount = modelsByProvider[mainRoute.provider].orEmpty().size,
                    onPickProvider = { picker = Picker.MainProvider },
                    onPickModel = { picker = Picker.MainModel },
                )
                RouteCard(
                    title = "ค่าเริ่มต้นสากล — sub agent",
                    route = subRoute,
                    modelCount = modelsByProvider[subRoute.provider].orEmpty().size,
                    onPickProvider = { picker = Picker.SubProvider },
                    onPickModel = { picker = Picker.SubModel },
                )
                HorizontalDivider()
                Text("วิธีตอบของ main agent", style = MaterialTheme.typography.titleMedium)
                // The capabilities are the ones the daemon reported for the model
                // actually selected, so a knob this model would refuse is never
                // offered rather than being offered and then dropped.
                key(mainRoute.provider, mainRoute.model, mainGeneration, clearedMain) {
                    GenerationSettingsCard(
                        model = selectedModel(mainRoute, modelsByProvider),
                        settings = mainGeneration,
                        onChange = { mainGeneration = it },
                        onClear = { knob ->
                            clearedMain = (clearedMain + knob).distinct()
                            mainGeneration = withoutKnob(mainGeneration, knob)
                        },
                    )
                }
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
                            msg = history.saveAgentSettings(
                                SettingsView(
                                    main = AgentSettings(mainRoute.provider, mainRoute.model, mainGeneration),
                                    sub = AgentSettings(sub.provider, sub.model, subGeneration),
                                    subEnabled = true,
                                ),
                                clearMain = clearedMain,
                                clearSub = clearedSub,
                            )
                            clearedMain = emptyList()
                            clearedSub = emptyList()
                            subRoute = sub
                            load(false)
                            busy = false
                        }
                    },
                    enabled = !busy && mainRoute.provider.isNotBlank() && mainRoute.model.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("บันทึกโมเดลของ agent") }
            }

            SectionCard(
                stringResource(R.string.token_fields_label),
                stringResource(R.string.token_fields_subtitle),
            ) {
                var fieldSpec by remember(curEndpoint, tokenFields) {
                    mutableStateOf(
                        if (tokenFields.isEmpty()) "" else TokenField.render(tokenFields),
                    )
                }
                LabelledField(
                    value = fieldSpec,
                    onValueChange = { fieldSpec = it },
                    label = stringResource(R.string.token_fields_label),
                    hint = stringResource(R.string.token_fields_help),
                    singleLine = false,
                    minLines = 2,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            val parsed = TokenField.parse(fieldSpec)
                            if (parsed.isEmpty()) {
                                msg = "ไม่รู้จักชื่อฟิลด์ — ใช้ได้แค่: " +
                                    TokenField.entries.joinToString(", ") { it.name }
                                return@Button
                            }
                            scope.launch { settings.setTokenFields(TokenField.render(parsed)) }
                        },
                    ) { Text("บันทึก") }
                    // A provider that reports no reasoning count should not show
                    // a reasoning field, so each one gets its own list.
                    TextButton(
                        onClick = {
                            scope.launch {
                                settings.setTokenFieldsForProvider(
                                    curEndpoint,
                                    TokenField.render(TokenField.parse(fieldSpec)),
                                )
                            }
                        },
                        enabled = curEndpoint.isNotBlank(),
                    ) { Text("ใช้กับ provider นี้เท่านั้น") }
                }
                if (curEndpoint.isNotBlank()) {
                    Text(
                        "provider: $curEndpoint",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                            onClick = { confirmingRemove = p to position },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("ลบ key ตัวที่ $position") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickingKeyFor = null }) { Text("ยกเลิก") } },
        )
    }

    confirmingRemove?.let { (provider, position) ->
        AlertDialog(
            onDismissRequest = { confirmingRemove = null },
            title = { Text("ลบ key ตัวที่ $position ของ ${provider.id}?") },
            text = {
                Text(
                    "การลบย้อนกลับไม่ได้ และ daemon จะลืมผลทดสอบเดิมของ provider นี้ เลขลำดับนี้อ้างอิงตำแหน่งใน pool ปัจจุบัน ไม่ใช่ค่าของ key",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemove = null
                    pickingKeyFor = null
                    scope.launch {
                        msg = history.changeKeys(provider.id, remove = listOf(position - 1))
                        load(false)
                    }
                }) { Text("ลบ", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingRemove = null }) { Text("ยกเลิก") } },
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
    var key by rememberSaveable { mutableStateOf("") }
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
            if (provider.workingModel.isNotBlank()) {
                Text(
                    "ตอบได้จริง: ${provider.workingModel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
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
                LabelledField(
                    value = key,
                    onValueChange = { key = it },
                    label = "เพิ่ม API key",
                    modifier = Modifier.weight(1f),
                    trailing = {
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
            Text(
                "key เดิมแก้ไขค่าตรง ๆ ไม่ได้ เพราะ daemon ไม่ส่งค่ากลับ — เพิ่ม key ใหม่ ลบตามลำดับ หรือแทนที่ทั้ง pool เท่านั้น",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
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
    var query by rememberSaveable { mutableStateOf("") }
    val list = remember(providers, probedIds, query) {
        filterProviders(providers, probedIds, query, ProviderFilter.ALL)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (providers.size > PROVIDER_PAGE) {
                LabelledField(
                    value = query,
                    onValueChange = { query = it },
                    label = "ค้นหา provider",
                    leading = { Icon(Icons.Default.Search, contentDescription = null) },
                )
                Text(
                    "${list.size} จาก ${providers.size} provider",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (providers.isEmpty()) {
                Text(
                    "ยังไม่มี provider — เพิ่ม provider หรือกด “ทดสอบใหม่” ก่อน",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) { items(list) { p ->
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
                            p.statusLine(p.id in probedIds) + " · key ${p.keyCount} · model ${p.modelCount}" +
                                if (p.workingModel.isNotBlank()) " · ตอบได้: ${p.workingModel}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected) Icon(Icons.Default.Check, contentDescription = null)
                }
            } }
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
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(models, query) {
        if (query.isBlank()) models else models.filter { it.id.contains(query, true) || it.name.contains(query, true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            LabelledField(
                value = query,
                onValueChange = { query = it },
                label = "ค้นหาโมเดล",
                leading = { Icon(Icons.Default.Search, contentDescription = null) },
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

/**
 * The model a route points at, or null when the catalogue has not been read yet.
 * Its capabilities are what decides which knobs are worth showing.
 */
/**
 * What the reader sees after clearing a knob: the field goes back to saying it is
 * unset. The daemon is told to forget the stored value by name, because "send
 * nothing" would be read as "leave it alone".
 */
internal fun withoutKnob(settings: GenerationSettings, knob: String): GenerationSettings =
    when (knob) {
        "thinking_level" -> settings.copy(thinkingLevel = "")
        "temperature" -> settings.copy(temperature = null)
        "top_p" -> settings.copy(topP = null)
        "top_k" -> settings.copy(topK = null)
        "stop_sequences" -> settings.copy(stopSequences = null)
        "presence_penalty" -> settings.copy(presencePenalty = null)
        "frequency_penalty" -> settings.copy(frequencyPenalty = null)
        "seed" -> settings.copy(seed = null)
        "max_output_tokens" -> settings.copy(maxOutputTokens = 0)
        else -> settings
    }

private fun selectedModel(
    route: AgentRoute,
    modelsByProvider: Map<String, List<ModelView>>,
): ModelView? {
    if (route.provider.isBlank() || route.model.isBlank()) return null
    return modelsByProvider[route.provider]?.firstOrNull { it.id == route.model }
}

@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String, Boolean) -> Unit,
) {
    var id by rememberSaveable { mutableStateOf("") }
    var adapter by rememberSaveable { mutableStateOf("openai") }
    var endpoint by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var freeOnly by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("เพิ่ม provider") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LabelledField(
                    value = id, onValueChange = { id = it },
                    label = "ชื่อ", placeholder = "my-gateway",
                )
                LabelledField(
                    value = endpoint, onValueChange = { endpoint = it },
                    label = "endpoint", placeholder = "https://api.example.com/v1",
                    singleLine = false, minLines = 2,
                )
                LabelledField(
                    value = key, onValueChange = { key = it },
                    label = "API key",
                    visualTransformation = PasswordVisualTransformation(),
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
    var text by rememberSaveable { mutableStateOf("") }
    val keys = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("แทนที่ key ของ ${provider.id}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ใส่ key ทีละบรรทัด — pool เดิมจะถูกแทนที่ทั้งหมด", style = MaterialTheme.typography.bodySmall)
                LabelledField(
                    value = text, onValueChange = { text = it },
                    label = "key",
                    hint = "บรรทัดละ 1 · ตอนนี้ ${provider.keyCount} ตัว",
                    singleLine = false, minLines = 3,
                    visualTransformation = PasswordVisualTransformation(),
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
    discovery: DaemonDiscovery?,
    onUse: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var node by remember { mutableStateOf<NodeInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    // When discovery is running it is already polling this row on its own, so
    // the button is a way to look now rather than the only way to look at all.
    // Both sides of the elvis have to be StateFlow, or the expression infers
    // Any and the delegate below has nothing to read.
    val fallback = remember { MutableStateFlow(DiscoveryState()) }
    val auto by (discovery?.state ?: fallback).collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(
            "ai daemon — ค้นหาอัตโนมัติจากแถว nodes ใน D1",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (discovery != null) {
            val (label, colour) = when (auto.phase) {
                DiscoveryPhase.NO_TOKEN ->
                    "○ ยังไม่ได้ใส่ Cloudflare token — ใส���แล้วจะค้นหาให้เอง" to
                        MaterialTheme.colorScheme.onSurfaceVariant
                DiscoveryPhase.CONNECTED ->
                    "● ต่ออยู่ที่ ${auto.tunnelUrl} (heartbeat ${auto.ageS}s${if (auto.version.isNotBlank()) ", ${auto.version}" else ""})" to
                        MaterialTheme.colorScheme.primary
                DiscoveryPhase.IDLE ->
                    "○ ยังไม่มี daemon ที่ heartbeat — สลับไปจะเจอเองถ้ามีรอบใหม่" to
                        MaterialTheme.colorScheme.onSurfaceVariant
                DiscoveryPhase.UNREACHABLE ->
                    "○ อ่าน D1 ไม่ได้${if (auto.error.isNotBlank()) ": ${auto.error}" else ""} — ใช้ URL ที่มีอยู่ต่อไป" to
                        MaterialTheme.colorScheme.error
            }
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = colour,
            )
            Text(
                "URL เปลี่ยนทุกครั้งที่รันใหม่ แอปจะตามให้เอง ไม่ต้องกดหา",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    checking = true; err = ""
                    scope.launch {
                        runCatching { history.node() }
                            .onSuccess { found ->
                                node = found
                                if (found == null) err = "ยังไม่มีแถว nodes — daemon ยังไม่ heartbeat"
                            }
                            .onFailure { err = it.message ?: "error" }
                        checking = false
                    }
                },
                enabled = !checking,
            ) { Text(if (discovery != null) "ตรวจตอนนี้" else "ค้นหา") }
            val usable = node?.takeIf { it.online }?.tunnelUrl
            if (usable != null) {
                Button(onClick = { onUse(usable) }) { Text("ใช้ URL นี้") }
            }
        }
        val n = node
        if (n != null && discovery == null) {
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
    var msg by rememberSaveable { mutableStateOf("แอป v" + BuildConfig.VERSION_NAME) }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        Text("ติดตั้งทับตัวเดิม ข้อมูลแชทไม่หาย", style = MaterialTheme.typography.labelSmall)
    }
}

