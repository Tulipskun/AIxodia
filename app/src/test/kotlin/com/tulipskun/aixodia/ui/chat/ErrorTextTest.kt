package com.tulipskun.aixodia.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorTextTest {
    @Test fun blankGivesGenericMessage() =
        assertEquals("An unknown error occurred. Please try again.", friendlyError("  "))

    @Test fun rateLimitIsBusyNotError() =
        assertEquals(
            "The provider is busy. It will retry automatically, or send again in a few seconds.",
            friendlyError("upstream_error: 429 temporarily at capacity upstream"),
        )

    @Test fun insufficientFundsSuggestsOtherModel() =
        assertEquals(
            "This provider is out of credit. Try another model in Settings.",
            friendlyError("upstream: insufficient funds"),
        )

    @Test fun missingKeyPointsToSettings() =
        assertEquals(
            "No API key set for this provider. Add a key in Settings.",
            friendlyError("provider Zen has no API keys"),
        )

    @Test fun freeTierRefusalIsNotAKeyProblem() =
        assertEquals(
            "The free-tier provider rejected the request; this is not an API key issue. Try again or switch models.",
            friendlyError(
                "upstream_error: OpenCode's free tier can only be used from within OpenCode. " +
                    "Download it at https://opencode.ai and keep building.",
            ),
        )

    @Test fun unknownModelIsExplained() =
        assertEquals(
            "The selected model or provider was not found. Check the model settings.",
            friendlyError("unknown model \"foo\": prefix it with a provider"),
        )

    @Test fun unknownErrorFallsBack() =
        assertEquals("Failed to send the message. Please try again.", friendlyError("something odd"))
}
