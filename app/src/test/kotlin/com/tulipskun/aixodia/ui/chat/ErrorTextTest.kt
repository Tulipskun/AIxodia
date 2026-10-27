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
            "No key set for this provider. Add a key in Settings.",
            friendlyError("provider Zen has no API keys"),
        )

    @Test fun freeTierRefusalPointsAtTheKeylessProvider() =
        assertEquals(
            "OpenCode serves its free models to the OpenCode app only, so the engine is refused here. " +
                "Pick AIxodia Free, which needs no key, or add an OpenCode key to that provider.",
            friendlyError(
                "upstream_error: OpenCode's free tier can only be used from within OpenCode. " +
                    "Download it at https://opencode.ai and keep building.",
            ),
        )

    @Test fun freeUsageQuotaIsNotAKeyProblem() =
        assertEquals(
            "OpenCode serves its free models to the OpenCode app only, so the engine is refused here. " +
                "Pick AIxodia Free, which needs no key, or add an OpenCode key to that provider.",
            friendlyError(
                "upstream HTTP 429: {\"code\":\"FreeUsageLimitError\",\"title\":\"Free models " +
                    "usage limited\"}: try again later",
            ),
        )

    // The keyless built-in is throttled per network, and its own body names the
    // window. Saying "one reply every 30 seconds" is actionable; "HTTP 402" is not.
    @Test fun keylessThrottleNamesTheWait() =
        assertEquals(
            "The free provider answers about one reply every 30 seconds. Wait a moment and send it again.",
            friendlyError(
                "upstream HTTP 402: Free limit reached: 1 reply every 30 seconds per IP. " +
                    "Retry after 28s, or get unlimited replies with a pollen key.",
            ),
        )

    @Test fun unknownModelIsExplained() =
        assertEquals(
            "The selected model or provider was not found. Check the model settings.",
            friendlyError("unknown model \"foo\": prefix it with a provider"),
        )

    // The daemon stores the history, so the phone can hold a chat it no longer
    // has. This is the exact text the phone received for that case.
    @Test fun goneSessionIsNotRetryable() =
        assertEquals(
            "This chat is no longer on the daemon. Start a new chat.",
            friendlyError(
                "chat 404: {\"error\":{\"message\":\"session \\\"246b602223971d82\\\" not found\",\"type\":\"not_found\"}}",
            ),
        )

    @Test fun upstreamNotFoundIsNotAGoneSession() =
        assertEquals(
            "Failed to send the message. Please try again.",
            friendlyError("chat: model not found"),
        )

    // The status alone is not the failure. A 503 body often names the real
    // cause (a rejected parameter, an unavailable model, a quota), and hiding
    // it behind "temporarily unavailable" leaves nothing the user can do.
    @Test fun upstreamFailureKeepsTheProvidersOwnMessage() =
        assertEquals(
            "The provider returned HTTP 503: model claude-x is not available for this key",
            friendlyError("upstream HTTP 503: model claude-x is not available for this key"),
        )

    @Test fun unknownErrorFallsBack() =
        assertEquals("Failed to send the message. Please try again.", friendlyError("something odd"))
}
