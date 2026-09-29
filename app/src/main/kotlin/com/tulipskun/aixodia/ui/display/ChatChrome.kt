package com.tulipskun.aixodia.ui.display

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tulipskun.aixodia.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.remote.ConnState

@Composable
fun ConnDot(c: ConnState) {
    val (label, color) = when (c) {
        ConnState.ONLINE -> "● ออนไลน์" to MaterialTheme.colorScheme.primary
        ConnState.CONNECTING -> "● กำลังต่อ" to MaterialTheme.colorScheme.tertiary
        ConnState.OFFLINE -> "● ออฟไลน์" to MaterialTheme.colorScheme.error
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.padding(end = 4.dp),
    )
}

/** First-launch / unconfigured gate — before any network call. */
@Composable
fun SetupNeeded(endpoint: String, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(48.dp).padding(bottom = 12.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            "ยินดีต้อนรับสู่ AIxodia",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "ใส่ Cloudflare API token ที่หน้าตั้งค่า — account/database ค้นหาให้อัตโนมัติ\n" +
                "ใส่ URL ของ tunnel ถ้าต้องการแชทสด (ประวัติอ่านได้แม้ daemon หยุด)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        if (endpoint.isNotBlank()) {
            Text(
                "ที่อยู่: $endpoint",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(
            onClick = onOpen,
            modifier = Modifier
                .padding(top = 20.dp)
                .fillMaxWidth()
                .widthIn(max = 360.dp),
        ) { Text("เปิดหน้าตั้งค่า") }
    }
}

/** Empty thread — ChatGPT-style welcome with a clear call to action. */
@Composable
fun EmptyChatState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 56.dp, start = 28.dp, end = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            "เริ่มสนทนากับ agent",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "พิมพ์งานด้านล่าง แล้ว agent จะทำต่อแม้ปิดแอป — ประวัติกลับมาตอนเปิดใหม่",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Offline / reconnect banner above the composer. */
@Composable
fun OfflineBanner(@StringRes messageRes: Int, onRetry: () -> Unit) {
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
                stringResource(messageRes),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.offline_retry)) }
        }
    }
}

/**
 * Composer bar: multi-line field + send / stop — standard chatbot input.
 */
@Composable
fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .widthIn(max = 900.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("ส่งข้อความ…") },
            shape = MaterialTheme.shapes.large,
            maxLines = 5,
        )
        Spacer(Modifier.size(8.dp))
        if (busy) {
            FilledTonalIconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "หยุดการทำงาน" },
            ) {
                Icon(Icons.Default.Stop, contentDescription = "หยุดการทำงาน")
            }
        } else {
            FilledIconButton(
                onClick = onSend,
                enabled = draft.isNotBlank(),
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "ส่ง" },
            ) {
                Icon(Icons.Default.Send, contentDescription = "ส่ง")
            }
        }
    }
}
