package com.tulipskun.aixodia.ui.display

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.model.Block
import com.tulipskun.aixodia.data.model.ChatMessage

/**
 * The generic renderer for engine display blocks. Known types reuse the
 * thread's own pieces (the answer's text, the thought pill, the tool card);
 * a type this app has never seen still draws as a plain card with its title
 * and body, so new kinds of engine content never need an app update to be
 * readable. Rows that predate blocks take the role-based path in
 * [MessageBlock] instead.
 */
@Composable
fun UnknownBlockCard(block: Block) {
    val shape = MaterialTheme.shapes.small
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = shape,
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .clip(shape),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            val title = block.name.ifBlank { block.type }
            if (title.isNotBlank()) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.size(6.dp))
            }
            val body = listOf(block.text, block.result).filter { it.isNotBlank() }.joinToString("\n\n")
            if (body.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        body,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * What a stored tool step shows, whether its block or its row is asked. The
 * stored block and the row fields describe the same step — the block says a
 * tool step is here, the row carries the merged call and result — so each
 * side fills what the other leaves blank rather than either one winning.
 */
fun toolCardName(m: ChatMessage, block: Block?): String =
    m.toolName.ifBlank { block?.name.orEmpty() }

fun toolCardArgs(m: ChatMessage, block: Block?): String =
    m.toolArgs.ifBlank { block?.args.orEmpty() }

/** A stored tool step is settled unless its call is still waiting for a result. */
fun toolCardDone(m: ChatMessage): Boolean = m.role == "tool_result"

/** How long the step ran, preferring the stored measurement. */
fun toolCardDurationMs(m: ChatMessage, block: Block?): Long =
    m.durationMs.takeIf { it > 0L } ?: (block?.durationMs ?: 0L)

/**
 * The live step a tool block upserts. A result frame names the tool and its
 * output, never its arguments, so the running step lends them over — without
 * that the card loses the command it ran the moment it finishes.
 */
fun toolStepFor(block: Block, knownArgs: String, startedAtMs: Long): com.tulipskun.aixodia.data.model.ToolStep =
    com.tulipskun.aixodia.data.model.ToolStep(
        id = block.id,
        name = block.name,
        args = block.args.ifBlank { knownArgs },
        result = block.result,
        isError = block.isError,
        done = block.state == "done" || block.state == "error",
        durationMs = block.durationMs,
        startedAtMs = startedAtMs,
    )
