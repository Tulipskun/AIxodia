package com.tulipskun.aixodia.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.remote.ScreenSocket

/**
 * The keys a phone keyboard does not have.
 *
 * A remote desktop needs Escape, Tab, the arrows and the function keys, and a
 * phone's keyboard has none of them. Rather than build a whole keyboard, this is
 * the row of keys that is missing — the typing itself is the phone's own
 * keyboard, which already knows every language the phone does.
 *
 * Text is sent as text, not as keystrokes, so what is typed here reaches the
 * machine exactly as written: Thai included.
 */
@Composable
fun KeyboardBar(socket: ScreenSocket) {
    var compose by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }

    fun modifiers(): List<String> = buildList {
        if (ctrl) add("ctrl")
        if (alt) add("alt")
    }

    fun send(key: String) {
        socket.key(key, modifiers())
        // A modifier is for one key, the way a physical chord works.
        ctrl = false
        alt = false
    }

    fun typed(value: String) {
        if (value.isEmpty()) return
        // With a modifier held, the field is a way to press a chord. Without
        // one, it is ordinary text in whatever language it was written in.
        if (ctrl || alt) {
            socket.key(value, modifiers())
            ctrl = false
            alt = false
        } else {
            socket.text(value)
        }
        compose = ""
    }

    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = compose,
                onValueChange = { compose = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { typed(compose) }),
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                decorationBox = { field ->
                    if (compose.isEmpty()) {
                        Text(
                            if (ctrl || alt) "key for the chord…" else "type, any language…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    field()
                },
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Modifier.width(1.dp)
            Key("Esc", on = false) { send("Escape") }
            Key("Tab", on = false) { send("Tab") }
            Key("Ctrl", on = ctrl) { ctrl = !ctrl }
            Key("Alt", on = alt) { alt = !alt }
            Key("←", on = false) { send("Left") }
            Key("↑", on = false) { send("Up") }
            Key("↓", on = false) { send("Down") }
            Key("→", on = false) { send("Right") }
            Key("Home", on = false) { send("Home") }
            Key("End", on = false) { send("End") }
            Key("PgUp", on = false) { send("Prior") }
            Key("PgDn", on = false) { send("Next") }
            listOf("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12")
                .forEach { name -> Key(name, on = false) { send(name) } }
        }
    }
}

@Composable
private fun Key(label: String, on: Boolean, onPress: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .background(
                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            )
            .pointerTapPress(onPress)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** A press that reports itself once, on the way down, like a key. */
private fun Modifier.pointerTapPress(onPress: () -> Unit): Modifier =
    this.then(
        Modifier.pointerInput(onPress) {
            detectTapGestures(onPress = { onPress() })
        },
    )
