package com.tulipskun.aixodia.ui.display

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.clickable
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import com.tulipskun.aixodia.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 * The composer: one rounded card holding the message being written and every
 * control that acts on it, laid out the way Claude's is.
 *
 * The controls sit in a row along the card's bottom edge rather than beside the
 * field. A button beside the field takes width from every line of a long
 * message; along the bottom it takes none, and it puts send where a thumb
 * already rests. The field itself is borderless inside the card — the card is
 * the only edge the eye needs, and a second rounded outline inside it is what
 * made the old composer read as a form rather than as a place to type.
 */
@Composable
fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    commands: List<ComposerCommand>,
    modelLabel: String,
    onModel: () -> Unit,
    modifier: Modifier = Modifier,
    attachments: List<AttachmentRef> = emptyList(),
    uploading: Boolean = false,
    onAttach: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
) {
    val attachLabel = stringResource(R.string.attach)
    val stopLabel = stringResource(R.string.stop)
    val sendLabel = stringResource(R.string.send)
    val card = MaterialTheme.shapes.extraLarge
    val canSend = draft.isNotBlank() || attachments.isNotEmpty()
    var commandMenuOpen by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .widthIn(max = 900.dp)
            .clip(card)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, card)
            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
    ) {
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
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            maxLines = 5,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) {
                        Text(
                            stringResource(R.string.composer_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleControl(
                icon = Icons.Default.Add,
                label = attachLabel,
                onClick = onAttach,
                enabled = !uploading,
            )
            Spacer(Modifier.size(8.dp))
            // The model is chosen once per chat and then left alone, so it reads
            // as a label on the message rather than as a control to work. It is
            // given the room it needs, up to two thirds of the free width, and
            // ellipsises past that — a long id should not push send off the row.
            PillControl(
                label = modelLabel,
                onClick = onModel,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.size(6.dp))
            // The command menu keeps its own pill beside the model. Upper case
            // only: CMD is the name of the menu, not a shout.
            Box {
                PillControl(label = stringResource(R.string.cmd), onClick = { commandMenuOpen = true })
                DropdownMenu(
                    expanded = commandMenuOpen,
                    onDismissRequest = { commandMenuOpen = false },
                ) {
                    commands.forEach { command ->
                        DropdownMenuItem(
                            text = { Text(command.label) },
                            enabled = command.enabled,
                            onClick = {
                                commandMenuOpen = false
                                command.onClick()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(0.5f))
            if (busy) {
                CircleControl(
                    icon = Icons.Default.Stop,
                    label = stopLabel,
                    onClick = onStop,
                    primary = true,
                    size = 44.dp,
                )
            } else {
                CircleControl(
                    icon = Icons.Default.ArrowUpward,
                    label = sendLabel,
                    onClick = onSend,
                    enabled = canSend,
                    primary = true,
                    size = 44.dp,
                )
            }
        }
    }
}

/**
 * A round, wordless control for the composer's bottom row. What it does is a
 * content description rather than a label: four labelled buttons would crowd
 * the model name out of the row.
 *
 * [primary] inverts the disc — a light circle with a dark glyph — which is how
 * the one button that ends the turn stands out from the quiet ones beside it.
 * Disabled, it drops back to the same grey as its neighbours.
 */
@Composable
private fun CircleControl(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
    size: Dp = 40.dp,
) {
    val inverted = primary && enabled
    val container = if (inverted) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = when {
        inverted -> MaterialTheme.colorScheme.surface
        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Icon(icon, contentDescription = null, tint = content)
    }
}

/**
 * A full-round label that acts as a button — the shape Claude gives the model.
 * Base size and weight: it names something, so it sits quietly next to the
 * round controls instead of competing with them.
 */
@Composable
private fun PillControl(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


/**
 * One entry of the command menu in the composer's bottom row.
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
