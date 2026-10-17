@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.tulipskun.aixodia.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.model.ChatSession
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * ChatGPT-style session grouping by recency.
 * Buckets: วันนี้ · เมื่อวาน · 7 วันล่าสุด · เก่ากว่า
 */
data class SessionGroup(
    val label: String,
    val sessions: List<ChatSession>,
)

fun groupSessionsByDate(sessions: List<ChatSession>, nowMs: Long = System.currentTimeMillis()): List<SessionGroup> {
    if (sessions.isEmpty()) return emptyList()
    val cal = Calendar.getInstance()
    cal.timeInMillis = nowMs
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val startOfToday = cal.timeInMillis
    val startOfYesterday = startOfToday - TimeUnit.DAYS.toMillis(1)
    val startOfWeek = startOfToday - TimeUnit.DAYS.toMillis(7)

    val today = mutableListOf<ChatSession>()
    val yesterday = mutableListOf<ChatSession>()
    val week = mutableListOf<ChatSession>()
    val older = mutableListOf<ChatSession>()

    // Newest first within each bucket (sessions are usually already sorted).
    sessions.sortedByDescending { it.lastAt }.forEach { s ->
        val t = if (s.lastAt > 0L) s.lastAt else 0L
        when {
            t >= startOfToday -> today += s
            t >= startOfYesterday -> yesterday += s
            t >= startOfWeek -> week += s
            else -> older += s
        }
    }
    return buildList {
        if (today.isNotEmpty()) add(SessionGroup("Today", today))
        if (yesterday.isNotEmpty()) add(SessionGroup("Yesterday", yesterday))
        if (week.isNotEmpty()) add(SessionGroup("Last 7 days", week))
        if (older.isNotEmpty()) add(SessionGroup("Older", older))
    }
}

fun sessionTitle(sessions: List<ChatSession>, id: String): String =
    sessions.firstOrNull { it.id == id }?.title?.takeIf { it.isNotBlank() } ?: id.ifBlank { "New chat" }

/**
 * Drawer content: search + new chat + date-grouped session list.
 * Typical AI chatbot sidebar pattern (ChatGPT / Claude mobile).
 */
@Composable
fun SessionDrawerContent(
    sessions: List<ChatSession>,
    activeId: String,
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit,
    onLongPress: (ChatSession) -> Unit,
    footer: @Composable () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(sessions, query) {
        if (query.isBlank()) sessions
        else sessions.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.lastSnippet.contains(query, ignoreCase = true) ||
                it.id.contains(query, ignoreCase = true)
        }
    }
    val groups = remember(filtered) { groupSessionsByDate(filtered) }

    ModalDrawerSheet {
        // The list scrolls and takes the room it needs; the footer stays pinned
        // to the bottom of the sheet. Sharing one scroll left the footer
        // floating mid-sheet with dead space under it.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Chats",
                    modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${sessions.size} sessions",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedButton(
                    onClick = onNewChat,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  New chat")
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                    placeholder = { Text("Search chats…") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                if (sessions.isEmpty()) {
                    Column(
                        Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Outlined.ChatBubbleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "No chats yet",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Press \"New chat\" to start talking to the agent",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (groups.isEmpty()) {
                    Text(
                        "No chats match \"$query\"",
                        Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    groups.forEach { group ->
                        Text(
                            group.label,
                            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        group.sessions.forEach { s ->
                            SessionRow(
                                s = s,
                                active = s.id == activeId,
                                onOpen = { onOpen(s.id) },
                                onLongPress = { onLongPress(s) },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            footer()
        }
    }
}

@Composable
fun SessionRow(
    s: ChatSession,
    active: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val bg = if (active) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
        )
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                s.title.ifBlank { s.id },
                maxLines = 1,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                s.lastSnippet.ifBlank { "No messages yet" },
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (s.unread > 0) {
            AssistChip(onClick = onOpen, label = { Text("${s.unread}") })
        }
    }
}

@Composable
fun ChatActionsSheet(
    session: ChatSession,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(session.title.ifBlank { session.id }, maxLines = 2) },
        text = {
            Column {
                Text(
                    if (session.lastSnippet.isBlank()) "No messages yet"
                    else session.lastSnippet.take(80),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onRename) { Text("Rename") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
fun RenameChatDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var field by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                label = { Text("Chat name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(field) }) { Text("บันทึก") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ยกเลิก") }
        },
    )
}

@Composable
fun DeleteChatDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ลบแชทนี้?") },
        text = {
            Text("แชท “$title” และประวัติทั้งหมดจะถูกลบทั้งในเครื่องและบน D1")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ยกเลิก") }
        },
    )
}
