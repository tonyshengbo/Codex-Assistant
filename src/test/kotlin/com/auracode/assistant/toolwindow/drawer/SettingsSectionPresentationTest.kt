package com.auracode.assistant.toolwindow.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSectionPresentationTest {
    @Test
    fun `basic and runtime sections expose the new labels`() {
        val basicPresentation = SettingsSection.BASIC.presentation()
        val runtimePresentation = SettingsSection.RUNTIME.presentation()

        assertEquals("settings.section.basic", basicPresentation.titleKey)
        assertEquals("settings.section.basic.subtitle", basicPresentation.subtitleKey)
        assertTrue(basicPresentation.showHeader)
        assertFalse(basicPresentation.showSidePanel)
        assertEquals("settings.section.runtime", runtimePresentation.titleKey)
        assertEquals("settings.section.runtime.subtitle", runtimePresentation.subtitleKey)
        assertTrue(runtimePresentation.showHeader)
        assertFalse(runtimePresentation.showSidePanel)
    }
}
