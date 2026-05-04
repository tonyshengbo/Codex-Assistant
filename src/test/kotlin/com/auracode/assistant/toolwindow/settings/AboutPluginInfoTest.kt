package com.auracode.assistant.toolwindow.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies the stable metadata helpers used by the About settings page. */
class AboutPluginInfoTest {
    /** Ensures a non-blank version is returned unchanged. */
    @Test
    fun `returns provided version when not blank`() {
        assertEquals("1.2.3", normalizeVersion("1.2.3"))
    }

    /** Ensures blank versions fall back to the provided default. */
    @Test
    fun `falls back when version is blank`() {
        assertEquals("1.0.0", normalizeVersion("   ", fallbackVersion = "1.0.0"))
    }

    /** Ensures null versions fall back to the provided default. */
    @Test
    fun `falls back when version is null`() {
        assertEquals("9.9.9", normalizeVersion(null, fallbackVersion = "9.9.9"))
    }

    /** Ensures the community entry opens the new join URL instead of exposing a group number. */
    @Test
    fun `uses join url for community entry`() {
        assertTrue(AboutPluginInfo.communityJoinUrl.contains("qm.qq.com"))
    }
}
