package com.tulipskun.aixodia.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WebView has to start on a protected URL.
 *
 * `/cdn-cgi/access/login` answers 404 unless Access itself appends a kid and a
 * redirect_url, so loading it directly showed the user an error page and the
 * sign-in silently did nothing. These tests pin the entry point to a path that
 * Access redirects.
 */
class AccessLoginUrlTest {
    @Test
    fun startsOnAProtectedPathThatAccessRedirects() {
        assertEquals(
            "https://aixodia.dpdns.org/healthz",
            AccessLogin.loginEntryUrl("https://aixodia.dpdns.org"),
        )
    }

    @Test
    fun neverLoadsTheBareAccessLoginRoute() {
        val url = AccessLogin.loginEntryUrl("https://aixodia.dpdns.org")
        // JUnit's assertFalse takes only the condition; the explanation goes first.
        assertFalse(
            "Loading this route without kid/redirect_url returns 404: $url",
            url.contains("/cdn-cgi/access/login"),
        )
    }

    @Test
    fun toleratesATrailingSlashAndSurroundingSpace() {
        assertEquals(
            "https://aixodia.dpdns.org/healthz",
            AccessLogin.loginEntryUrl("  https://aixodia.dpdns.org/  "),
        )
        assertEquals(
            "https://aixodia.dpdns.org/healthz",
            AccessLogin.loginEntryUrl("https://aixodia.dpdns.org/"),
        )
    }

    @Test
    fun keepsAnExplicitPort() {
        assertTrue(
            AccessLogin.loginEntryUrl("http://127.0.0.1:8787")
                .endsWith(":8787/healthz"),
        )
    }
}
