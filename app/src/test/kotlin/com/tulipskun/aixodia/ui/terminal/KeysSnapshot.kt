package com.tulipskun.aixodia.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.tulipskun.aixodia.ui.theme.AIxodiaTheme
import org.junit.Rule
import org.junit.Test

/**
 * Renders the keypad on the same backdrop the panel uses, so a layout or
 * colour change is looked at instead of guessed at.
 */
class KeysSnapshot {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false),
    )

    private fun keys(ctrlOn: Boolean = false, shiftOn: Boolean = false) {
        paparazzi.snapshot {
            AIxodiaTheme {
                Box(Modifier.fillMaxWidth().background(Color(0xFF0B0F14))) {
                    TerminalKeys(
                        ctrlOn = ctrlOn,
                        altOn = false,
                        shiftOn = shiftOn,
                        onCtrl = {},
                        onAlt = {},
                        onShift = {},
                        onShiftSpent = {},
                        onKey = {},
                    )
                }
            }
        }
    }

    @Test
    fun atRest() = keys()

    @Test
    fun latched() = keys(ctrlOn = true, shiftOn = true)

}
