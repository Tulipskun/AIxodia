package com.tulipskun.aixodia.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.repo.ChatRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(repo: ChatRepository, settings: SettingsStore) {
    val sessId by settings.sessionFlow.collectAsState(initial = "default")
    val vm: ChatViewModel = viewModel(key = sessId) { ChatViewModel(repo, sessId) }
    val messages by vm.messages.collectAsState(initial = emptyList())
    val sessions by vm.sessions.collectAsState(initial = emptyList())
    val conn by vm.conn.collectAsState(initial = ConnState.OFFLINE)
    val status by vm.status.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text("AIxodia sessions", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                sessions.forEach { s ->
                    AssistChip(
                        onClick = { scope.launch { settings.save("", "", "", s.id); drawer.close() } },
                        label = { Text("${s.id} • ${s.lastSnippet.take(24)}") },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AIxodia • $sessId") },
                    actions = { ConnDot(conn) },
                )
            },
            bottomBar = {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft, onValueChange = { draft = it },
                            modifier = Modifier.weight(1f), placeholder = { Text("Message…") }, maxLines = 4,
                        )
                        IconButton(onClick = { vm.send(draft); draft = "" }) {
                            Icon(Icons.Default.Send, contentDescription = "send")
                        }
                    }
                }
            }
        ) { pad ->
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState, modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages, key = { it.id }) { m -> Bubble(m) }
            }
        }
    }
}

@Composable
private fun ConnDot(c: ConnState) {
    val t = when (c) { ConnState.ONLINE -> "● online"; ConnState.CONNECTING -> "● connecting"; ConnState.OFFLINE -> "● offline" }
    Box(Modifier.padding(end = 12.dp)) { Text(t, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val mine = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Card(colors = CardDefaults.cardColors(
            containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )) {
            Column(Modifier.padding(10.dp)) {
                Text(m.text)
                Text(
                    (if (m.pending) "sending • " else "") + m.role,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
