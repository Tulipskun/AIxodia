package com.tulipskun.aixodia.ui.snapshots

import app.cash.paparazzi.Paparazzi
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.ui.chat.MarkdownText
import com.tulipskun.aixodia.ui.display.ConnDot
import com.tulipskun.aixodia.ui.display.EmptyChatState
import com.tulipskun.aixodia.ui.display.OfflineBanner
import com.tulipskun.aixodia.ui.display.SetupNeeded
import com.tulipskun.aixodia.ui.theme.AIxodiaTheme
import org.junit.Rule
import org.junit.Test

/** Visual checks on the JVM: each snapshot is a PNG under app/src/test/snapshots. */
class ScreenSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun emptyChat() = paparazzi.snapshot { AIxodiaTheme { EmptyChatState() } }

    @Test
    fun setupNeeded() = paparazzi.snapshot {
        AIxodiaTheme { SetupNeeded(daemonUrl = "https://aixodia.dpdns.org", onSignedIn = { }) }
    }

    @Test
    fun offlineBanner() = paparazzi.snapshot {
        AIxodiaTheme { OfflineBanner(messageRes = R.string.offline_unknown_host, onRetry = {}) }
    }

    @Test
    fun connDotOnline() = paparazzi.snapshot { AIxodiaTheme { ConnDot(ConnState.ONLINE) } }

    @Test
    fun markdownAnswer() = paparazzi.snapshot {
        AIxodiaTheme {
            MarkdownText("## หัวข้อ\n\nข้อความปกติ **ตัวหนา** และ `inline code`\n\n- รายการแรก\n- รายการที่สอง")
        }
    }

    /**
     * The backup scope as it opens: the engine's folders, tickable.
     *
     * `AIxodia` is picked whole and `projects` only through something inside it, so
     * the one picture shows both states a checkbox can mean — and the nested pick
     * with its own way to drop it.
     */


    /** The state a scope is in before the operator touches it: nothing ticked, all of it. */

}
