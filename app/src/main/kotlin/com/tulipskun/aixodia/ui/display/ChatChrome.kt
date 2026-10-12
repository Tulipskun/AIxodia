package com.tulipskun.aixodia.ui.display

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.clickable
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import com.tulipskun.aixodia.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.model.AttachmentRef
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.ui.LocalUiSpec

/**
 * The connection state as a bare colour — green / amber / red — with no word
 * beside it. The state is already the thing the colour encodes, and a label on
 * every screen of every app is noise; a screen reader still gets the meaning
 * from the content description.
 */
@Composable
fun ConnDot(c: ConnState) {
    val ui = LocalUiSpec.current
    val (label, color) = when (c) {
        ConnState.ONLINE -> ui.online to MaterialTheme.colorScheme.primary
        ConnState.CONNECTING -> ui.connecting to MaterialTheme.colorScheme.tertiary
        ConnState.OFFLINE -> ui.offline to MaterialTheme.colorScheme.error
    }
    Box(
        Modifier
            .padding(horizontal = 6.dp)
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = label },
    )
}

/** First-launch / unconfigured gate — before any network call. */
@Composable
fun SetupNeeded(onOpen: () -> Unit) {
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
            "ใส่ Cloudflare API token ที่หน้าตั้งค่า — บัญชี ฐานข้อมูล และ URL ของ daemon\n" +
                "ค้นหาให้เองหมด ไม่ต้องพิมพ์อะไรอีก (ประวัติแชทอ่านได้แม้ daemon หยุด)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
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
    val ui = LocalUiSpec.current
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
            ui.emptyTitle,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            ui.emptyHint,
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            // A red box is a visual-only error: nothing announced it, so a
            // screen reader user finds out only when they reach the text by
            // accident. Assertive because it interrupts to say the app is down.
            .semantics { liveRegion = LiveRegionMode.Assertive },
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
    attachments: List<AttachmentRef> = emptyList(),
    uploading: Boolean = false,
    onAttach: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
) {
    val ui = LocalUiSpec.current
    Column(modifier.fillMaxWidth().widthIn(max = 900.dp)) {
        // Picked files wait above the field as removable chips, so it is clear
        // what will travel with the next message before it is sent.
        if (attachments.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                attachments.forEachIndexed { index, a ->
                    AssistChip(
                        onClick = { onRemoveAttachment(index) },
                        label = { Text(a.label(), maxLines = 1) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "เอา ${a.name} ออก",
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (ui.composer.showAttach) {
                FilledTonalIconButton(
                    onClick = onAttach,
                    enabled = !uploading,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = ui.attach },
                ) {
                    Icon(Icons.Default.Add, contentDescription = ui.attach)
                }
                Spacer(Modifier.size(8.dp))
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(ui.composerPlaceholder) },
                shape = MaterialTheme.shapes.large,
                maxLines = ui.composer.maxLines.coerceIn(1, 12),
            )
            Spacer(Modifier.size(8.dp))
            if (busy && ui.composer.showStopButton) {
                FilledTonalIconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = ui.stop },
                ) {
                    Icon(Icons.Default.Stop, contentDescription = ui.stop)
                }
            } else {
                FilledIconButton(
                    onClick = onSend,
                    enabled = draft.isNotBlank() || attachments.isNotEmpty(),
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = ui.send },
                ) {
                    Icon(Icons.Default.Send, contentDescription = ui.send)
                }
            }
        }
    }
}


/**
 * What the "+" button opens: a sheet to choose where the file comes from. The
 * photo picker handles images and video; the document picker handles anything
 * else. Both feed the same upload, so the choice is only about which system
 * picker is right for the content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(
    onMedia: () -> Unit,
    onFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ui = LocalUiSpec.current
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                ui.attachTitle,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
            AttachOption(Icons.Default.Image, ui.attachMedia, onMedia)
            AttachOption(Icons.Default.InsertDriveFile, ui.attachFile, onFile)
        }
    }
}

@Composable
private fun AttachOption(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
