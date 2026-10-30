package com.tulipskun.aixodia.ui.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerSendEligibilityTest {
    @Test fun onlineTextCanSend() = assertTrue(canSendMessage("hello", false, false, true, false))
    @Test fun offlineTextCanBeQueued() = assertTrue(canSendMessage("hello", false, false, false, false))
    @Test fun anEmptyMessageCannotSend() = assertFalse(canSendMessage(" \n", false, false, true, false))
    @Test fun filesCanSendWithoutText() = assertTrue(canSendMessage("", true, false, true, false))
    @Test fun filesCannotBeQueuedWithoutTheirDaemon() = assertFalse(canSendMessage("hello", true, false, false, false))
    @Test fun uploadsMustFinishBeforeSending() = assertFalse(canSendMessage("hello", false, false, true, true))
    @Test fun uploadsAlsoBlockSendingEarlierFiles() = assertFalse(canSendMessage("", true, false, true, true))
    @Test fun aLiveTurnOwnsTheComposer() = assertFalse(canSendMessage("next", false, true, true, false))
}
