package com.tulipskun.aixodia.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyAnswerTest {
    @Test fun blankAssistantReplyIsHidden() = assertTrue(isEmptyAnswer("assistant", "", "", false))

    @Test fun answersWithTextAreKept() = assertFalse(isEmptyAnswer("assistant", "hi", "", false))

    @Test fun toolRowsAreKeptEvenWithoutText() {
        assertFalse(isEmptyAnswer("tool_call", "", "fetch_url", false))
        assertFalse(isEmptyAnswer("tool_result", "", "", false))
    }

    @Test fun userAndPendingMessagesAreKept() {
        assertFalse(isEmptyAnswer("user", "", "", false))
        assertFalse(isEmptyAnswer("assistant", "", "", true))
    }
}
