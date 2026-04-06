package com.auracode.assistant.toolwindow.drawer.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class AboutPluginInfoTest {
    @Test
    fun `returns provided version when not blank`() {
        assertEquals("1.2.3", normalizeVersion("1.2.3"))
    }

    @Test
    fun `falls back when version is blank`() {
        assertEquals("1.0.0", normalizeVersion("   ", fallbackVersion = "1.0.0"))
    }

    @Test
    fun `falls back when version is null`() {
        assertEquals("9.9.9", normalizeVersion(null, fallbackVersion = "9.9.9"))
    }
}
