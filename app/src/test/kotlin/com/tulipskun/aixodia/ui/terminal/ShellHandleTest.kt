package com.tulipskun.aixodia.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The name the shell server answers to, as the drawer prints it. The engine
 * builds a tmux session name out of the tab id by keeping the characters tmux
 * allows and replacing the rest; the drawer shows that name for each shell, so
 * a tab can be matched against `tmux ls` on the host.
 */
class ShellHandleTest {
    @Test fun theServerNameIsTheTabIdWithAPrefix() =
        assertEquals("aix-t-6f", shellHandle("t-6f"))

    @Test fun aCharacterTmuxWouldRejectBecomesAnUnderscore() =
        assertEquals("aix-t_6f_2", shellHandle("t 6f/2"))

    @Test fun aShortIdIsShownWhole() = assertEquals("aix-ab", shellHandle("ab"))

    @Test fun aLongIdIsCutToTheWidthOfTheRow() =
        assertEquals("aix-t-e615791f6…", shellHandle("t-e615791f681242c689c50830c38ae129"))

    @Test fun theCutKeepsTheHeadBecauseThatIsWhatIdentifies() {
        val first = shellHandle("t-e615791f681242c689c50830c38ae129")
        val second = shellHandle("t-6935845796344f70895847d2140316c8")
        assertEquals(false, first == second)
    }
}
