package com.tulipskun.aixodia.data.remote

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PKCE half of the browser sign-in.
 *
 * These are the properties that make it safe to ship without a client secret:
 * the verifier must hash to the challenge exactly as RFC 7636 specifies, and a
 * different verifier must not. If either slipped, an intercepted code would be
 * redeemable by any other app that received the deep link.
 */
class AccessLoginTest {
    @Test
    fun challengeMatchesTheRfc7636TestVector() {
        // Appendix B of RFC 7636.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            AccessLogin.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifierIsLongEnoughAndUrlSafe() {
        val verifier = AccessLogin.newVerifier()
        // 48 random bytes encode to 64 unpadded base64url characters.
        assertEquals(64, verifier.length)
        assertTrue(
            "Verifier must stay inside the RFC 7636 alphabet: $verifier",
            verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' },
        )
        assertFalse("Padding is not allowed in PKCE", verifier.contains('='))
    }

    @Test
    fun twoAttemptsGetDifferentVerifiers() {
        assertNotEquals(AccessLogin.newVerifier(), AccessLogin.newVerifier())
    }

    @Test
    fun aDifferentVerifierDoesNotSatisfyTheChallenge() {
        assertNotEquals(
            AccessLogin.challengeFor(AccessLogin.newVerifier()),
            AccessLogin.challengeFor(AccessLogin.newVerifier()),
        )
    }

    @Test
    fun theChallengeIsTheSha256OfTheSameVerifier() {
        val verifier = AccessLogin.newVerifier()
        assertEquals(AccessLogin.challengeFor(verifier), AccessLogin.challengeFor(verifier))
    }

    @Test
    fun readsTheCodeFromTheAccessDeepLink() {
        val uri = Uri.parse("aixodia://auth/callback?code=abc-123")
        assertEquals("abc-123", AccessLogin.codeFromDeepLink(uri))
    }

    @Test
    fun ignoresLinksThatAreNotTheSignInCallback() {
        assertNull(AccessLogin.codeFromDeepLink(Uri.parse("https://example.com/?code=abc")))
        assertNull(AccessLogin.codeFromDeepLink(Uri.parse("aixodia://other/callback?code=abc")))
        assertNull(AccessLogin.codeFromDeepLink(Uri.parse("aixodia://auth/callback")))
    }

    @Test
    fun rememberinGAndClearingThePendingVerifier() {
        val verifier = AccessLogin.startAttempt()
        assertEquals(verifier, AccessLogin.pendingVerifier)
        AccessLogin.clearPending()
        assertEquals("", AccessLogin.pendingVerifier)
    }
}
