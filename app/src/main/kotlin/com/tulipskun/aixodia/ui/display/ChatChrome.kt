package com.tulipskun.aixodia.ui.display

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.clickable
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import com.tulipskun.aixodia.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.model.AttachmentRef
import com.tulipskun.aixodia.data.remote.ConnState

/**
 * The connection state as a bare colour — green / amber / red — with no word
 * beside it. The state is already the thing the colour encodes, and a label on
 * every screen of every app is noise; a screen reader still gets the meaning
 * from the content description.
 */
@Composable
fun ConnDot(c: ConnState) {
    // Green / amber / red, the colours a status dot is expected to wear. The
    // scheme's primary was blue, which reads as "selected", not "connected".
    val (label, color) = when (c) {
        ConnState.ONLINE -> stringResource(R.string.online) to Color(0xFF3DDC84)
        ConnState.CONNECTING -> stringResource(R.string.connecting) to Color(0xFFFFB020)
        ConnState.OFFLINE -> stringResource(R.string.offline) to Color(0xFFE5484D)
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
            stringResource(R.string.empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.empty_hint),
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
    val attachLabel = stringResource(R.string.attach)
    val stopLabel = stringResource(R.string.stop)
    val sendLabel = stringResource(R.string.send)
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
            FilledTonalIconButton(
                onClick = onAttach,
                enabled = !uploading,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = attachLabel },
            ) {
                Icon(Icons.Default.Add, contentDescription = attachLabel)
            }
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.composer_hint)) },
                shape = MaterialTheme.shapes.large,
                maxLines = 5,
            )
            Spacer(Modifier.size(8.dp))
            if (busy) {
                FilledTonalIconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = stopLabel },
                ) {
                    Icon(Icons.Default.Stop, contentDescription = stopLabel)
                }
            } else {
                FilledIconButton(
                    onClick = onSend,
                    enabled = draft.isNotBlank() || attachments.isNotEmpty(),
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = sendLabel },
                ) {
                    Icon(Icons.Default.Send, contentDescription = sendLabel)
                }
            }
        }
    }
}


/**
 * One entry of the command menu under the composer.
 *
 * A command runs on the phone, so it needs no round trip and no daemon
 * support: the menu is the set of things a chat can be asked to do that are
 * not messages. [enabled] is false when the command would do nothing — asking
 * to stop while nothing is running, or to clear an empty box — so the menu
 * keeps the same shape instead of rows appearing and vanishing.
 */
data class ComposerCommand(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The row under the composer: the command menu on the left, this chat's model
 * beside it.
 *
 * The model picker used to be a chip in the top bar, nested under the session
 * title. It moved here because it acts on the next message rather than on the
 * screen, and because a long model name under a title pushed the title itself
 * off the bar. The command menu is new and sits next to it for the same reason:
 * both are about the message about to be sent.
 *
 * They are styled apart on purpose. The command menu is filled and bold — it is
 * the one thing here nobody will find by accident, so it is the one thing that
 * has to catch the eye. The model reads as a chip, quiet next to it, because it
 * is set once per chat and then left alone.
 */
@Composable
fun ComposerActionsRow(
    commands: List<ComposerCommand>,
    modelLabel: String,
    onModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val commandLabel = stringResource(R.string.cmd)
    var menuOpen by remember { mutableStateOf(false) }
    // Same width rule as ChatComposer above, so the buttons line up with the
    // field's own edges on a tablet instead of drifting to the screen's edge.
    // The row sits a little below the field, not against it: the two are one
    // group, and the gap is what says so.
    Row(
        modifier.fillMaxWidth().widthIn(max = 900.dp).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Anchored to the button, not a sheet: the list is short and the
        // answer to "what can I do here" belongs next to the thing asked.
        Box {
            FilledTonalButton(
                onClick = { menuOpen = true },
                // The theme's own radius, so the button rounds with every other
                // surface rather than carrying a number of its own.
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(horizontal = 18.dp),
                modifier = Modifier.height(40.dp),
            ) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    commandLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                commands.forEach { command ->
                    DropdownMenuItem(
                        text = { Text(command.label) },
                        enabled = command.enabled,
                        onClick = {
                            menuOpen = false
                            command.onClick()
                        },
                    )
                }
            }
        }
        // fill = false: the row stays left-aligned under the field and the
        // chip grows only as far as the name needs, then ellipsises. The
        // chevron is there because a chip that opens a sheet says so.
        AssistChip(
            onClick = onModel,
            label = { Text(modelLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            trailingIcon = {
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            modifier = Modifier.weight(1f, fill = false).height(40.dp),
        )
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
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            // The two options say everything; a heading above them would only
            // repeat it and push the rows down, so there is none.
            AttachOption(Icons.Default.Image, stringResource(R.string.attach_media), onMedia)
            AttachOption(Icons.Default.InsertDriveFile, stringResource(R.string.attach_file), onFile)
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
