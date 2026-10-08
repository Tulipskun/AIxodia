package com.tulipskun.aixodia.ui.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.model.ProviderStatus
import com.tulipskun.aixodia.data.remote.HistoryApi
import com.tulipskun.aixodia.ui.settings.ProviderFilter
import com.tulipskun.aixodia.ui.settings.countProviders
import com.tulipskun.aixodia.ui.settings.filterProviders
import kotlinx.coroutines.launch

private val adapters = listOf("openai", "anthropic", "opencode", "gemini")

/** Providers and their keys. Opened from the chat drawer; it is its own page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(history: HistoryApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<ProviderStatus>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ProviderFilter.ALL) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    // Providers the user tested in this session, so "ใช้ไม่ได้" and "ยังไม่ทดสอบ" stay honest.
    var tested by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        providers = history.providers()
        loaded = true
    }

    val counts = remember(providers, tested) { countProviders(providers, tested) }
    val visible = remember(providers, tested, query, filter) {
        filterProviders(providers, tested, query, filter)
    }
    val selected = providers.firstOrNull { it.id == selectedId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ผู้ให้บริการ") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "ย้อนกลับ") }
                },
                actions = {
                    IconButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val fresh = history.refreshProviders()
                                if (fresh.isEmpty()) {
                                    message = "daemon ไม่ได้ตอบ ตรวจว่า ai-engine ยังรันอยู่"
                                } else {
                                    providers = fresh
                                    tested = fresh.map { it.id }.toSet()
                                    message = "ทดสอบแล้ว ${fresh.count { it.reachable }} จาก ${fresh.size} ใช้ได้"
                                }
                                busy = false
                            }
                        },
                    ) { Icon(Icons.Default.Refresh, contentDescription = "ทดสอบทั้งหมด") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, contentDescription = "เพิ่ม provider")
            }
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            Text(
                if (!loaded) "กำลังโหลด…"
                else "${counts.all} ผู้ให้บริการ · ${providers.sumOf { it.keyCount }} key · ${counts.working} ใช้ได้",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            if (providers.size > 6) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("ค้นหาชื่อ, adapter หรือ endpoint") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    Triple(ProviderFilter.ALL, "ทั้งหมด", counts.all),
                    Triple(ProviderFilter.WORKING, "ใช้ได้", counts.working),
                    Triple(ProviderFilter.UNTESTED, "ยังไม่ทดสอบ", counts.untested),
                    Triple(ProviderFilter.BROKEN, "ใช้ไม่ได้", counts.broken),
                ).forEach { (value, label, n) ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { filter = value },
                        label = { Text("$label $n") },
                    )
                }
            }

            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            when {
                loaded && providers.isEmpty() -> EmptyState(
                    "ยังไม่มีผู้ให้บริการ\nกด + เพื่อเพิ่ม หรือกดรีเฟรชเพื่อให้ daemon รายงาน",
                )
                loaded && visible.isEmpty() -> EmptyState("ไม่มีรายการที่ตรงกับตัวกรอง")
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { p ->
                    ProviderCard(p, tested = p.id in tested, onClick = { selectedId = p.id })
                }
            }
        }
    }

    if (selected != null) {
        ProviderDetailSheet(
            provider = selected,
            tested = selected.id in tested,
            history = history,
            onChanged = { updated ->
                providers = providers.map { if (it.id == updated.id) updated else it }
            },
            onRemoved = {
                providers = providers.filterNot { p -> p.id == selected.id }
                selectedId = null
            },
            onDismiss = { selectedId = null },
            onTested = { tested = tested + selected.id },
        )
    }

    if (adding) {
        AddProviderSheet(
            history = history,
            onDismiss = { adding = false },
            onAdded = { result ->
                adding = false
                message = result
                scope.launch { providers = history.providers() }
            },
        )
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun ProviderCard(p: ProviderStatus, tested: Boolean, onClick: () -> Unit) {
    val working = p.reachable && tested
    val broken = !p.reachable && tested
    val dotColor = when {
        working -> MaterialTheme.colorScheme.primary
        broken -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val statusText = when {
        working && p.workingModel.isNotBlank() -> "ใช้ได้ · ${p.workingModel}"
        working -> "ใช้ได้ · ${p.modelCount} โมเดล"
        broken -> "ใช้ไม่ได้ · ${p.lastError.ifBlank { "ไม่ทราบสาเหตุ" }}"
        else -> "ยังไม่ทดสอบ"
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(dotColor)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${p.adapter} · ${p.keyCount} key" + if (p.freeOnly) " · ฟรี" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (broken) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDetailSheet(
    provider: ProviderStatus,
    tested: Boolean,
    history: HistoryApi,
    onChanged: (ProviderStatus) -> Unit,
    onRemoved: () -> Unit,
    onDismiss: () -> Unit,
    onTested: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var newKey by rememberSaveable { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(provider.id, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "${provider.adapter} · ${provider.endpoint}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (note.isNotBlank()) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Text("key ${provider.keyCount} อัน", style = MaterialTheme.typography.titleSmall)
            Text(
                "key ถูกเก็บไว้ที่ daemon และอ่านกลับไม่ได้ ลบแล้วต้องใส่ใหม่",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            (1..provider.keyCount).forEach { position ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("key $position", modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch {
                            val result = history.changeKeys(provider.id, remove = listOf(position - 1))
                            note = result
                            history.providers().firstOrNull { it.id == provider.id }?.let(onChanged)
                        }
                    }) { Text("ลบ") }
                }
            }

            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                singleLine = true,
                label = { Text("เพิ่ม key") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = newKey.isNotBlank(),
                onClick = {
                    val key = newKey.trim()
                    newKey = ""
                    scope.launch {
                        note = history.changeKeys(provider.id, add = listOf(key))
                        history.providers().firstOrNull { it.id == provider.id }?.let(onChanged)
                    }
                },
            ) { Text("เพิ่ม key") }

            HorizontalDivider()

            Button(
                enabled = !testing,
                onClick = {
                    testing = true
                    scope.launch {
                        val status = history.refreshProvider(provider.id)
                        testing = false
                        if (status == null) {
                            note = "ทดสอบไม่สำเร็จ daemon ไม่ตอบ"
                        } else {
                            onChanged(status)
                            onTested()
                            note = if (status.reachable) "ใช้ได้ ${status.modelCount} โมเดล" else "ใช้ไม่ได้: ${status.lastError}"
                        }
                    }
                },
            ) { Text(if (testing) "กำลังทดสอบ…" else "ทดสอบ provider นี้") }

            OutlinedButton(onClick = { confirmDelete = true }) { Text("ลบ provider") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("ลบ ${provider.id}?") },
            text = { Text("ลบ provider และ key ทั้งหมดของมัน กู้คืนไม่ได้") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val result = history.removeProvider(provider.id)
                        note = result
                        onRemoved()
                    }
                }) { Text("ลบ") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("ยกเลิก") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProviderSheet(
    history: HistoryApi,
    onDismiss: () -> Unit,
    onAdded: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var id by rememberSaveable { mutableStateOf("") }
    var adapter by rememberSaveable { mutableStateOf(adapters.first()) }
    var endpoint by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var freeOnly by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val valid = id.isNotBlank() && endpoint.isNotBlank() && key.isNotBlank()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("เพิ่ม provider", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = id, onValueChange = { id = it }, singleLine = true,
                label = { Text("ชื่อ (เช่น nousresearch)") }, modifier = Modifier.fillMaxWidth())
            Text("รูปแบบ API", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                adapters.forEach { name ->
                    FilterChip(selected = adapter == name, onClick = { adapter = name }, label = { Text(name) })
                }
            }
            OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, singleLine = true,
                label = { Text("Endpoint (https://…)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = key, onValueChange = { key = it }, singleLine = true,
                label = { Text("API key") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ใช้เฉพาะโมเดลฟรี", modifier = Modifier.weight(1f))
                Switch(checked = freeOnly, onCheckedChange = { freeOnly = it })
            }
            if (error.isNotBlank()) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                enabled = valid && !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val result = history.addProvider(id.trim(), adapter, endpoint.trim(), listOf(key.trim()), freeOnly)
                        saving = false
                        val exists = history.providers().any { it.id == id.trim() }
                        if (exists) onAdded("เพิ่ม ${id.trim()} แล้ว") else error = result
                    }
                },
            ) { Text(if (saving) "กำลังบันทึก…" else "บันทึก") }
        }
    }
}
