package com.tulipskun.aixodia.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {
    @Test
    fun aHigherRollingVersionCodeIsAnUpdate() {
        assertTrue(UpdateManager.rollingNeedsUpdate(1_700_000_000L, 1_699_999_999))
    }

    @Test
    fun theSameOrOlderRollingVersionCodeIsNotAnUpdate() {
        assertFalse(UpdateManager.rollingNeedsUpdate(1_700_000_000L, 1_700_000_000))
        assertFalse(UpdateManager.rollingNeedsUpdate(1_699_999_999L, 1_700_000_000))
    }

    @Test
    fun missingRollingMetadataDoesNotOfferAnUpdate() {
        assertFalse(UpdateManager.rollingNeedsUpdate(-1L, 1))
        assertFalse(UpdateManager.rollingNeedsUpdate(0L, 1))
    }

    @Test
    fun theUpdaterIsNotGatedOnSignInState() {
        // Sign-in lives in Cloudflare Access, in front of the engine. Replacing
        // the APK does not depend on it, so both states must offer the update.
        assertTrue(UpdateManager.canUpdate(signedIn = false))
        assertTrue(UpdateManager.canUpdate(signedIn = true))
    }
}
