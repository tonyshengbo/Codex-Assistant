package com.auracode.assistant.toolwindow

import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shared.ToolWindowUiText
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolWindowUiTextTest {
    @Test
    fun `formats durations in seconds minutes and hours`() {
        assertEquals("850ms", ToolWindowUiText.formatDuration(850))
        assertEquals("13s", ToolWindowUiText.formatDuration(13_400))
        assertEquals("1m 05s", ToolWindowUiText.formatDuration(65_000))
        assertEquals("2h 03m", ToolWindowUiText.formatDuration(7_380_000))
    }

    @Test
    fun `formats running composer state with localized message`() {
        assertEquals(AuraCodeBundle.message("toolwindow.running", "45s"), ToolWindowUiText.runningStatus(45_000))
        assertEquals(AuraCodeBundle.message("toolwindow.running", "1m 12s"), ToolWindowUiText.runningStatus(72_000))
        assertEquals(AuraCodeBundle.message("toolwindow.running", "1h 01m"), ToolWindowUiText.runningStatus(3_660_000))
    }

    @Test
    fun `selection chips only show current value`() {
        assertEquals("Codex", ToolWindowUiText.selectionChipLabel("Provider: Codex"))
        assertEquals("gpt-5.3-codex", ToolWindowUiText.selectionChipLabel("Model: gpt-5.3-codex"))
        assertEquals("Auto", ToolWindowUiText.selectionChipLabel("Approvals: Auto"))
        assertEquals("Auto", ToolWindowUiText.selectionChipLabel("Auto"))
    }

    @Test
    fun `settings environment copy reflects codex runtime branding`() {
        assertEquals("Codex Runtime Path", AuraCodeBundle.message("settings.codexPath.label"))
        assertEquals(
            "Path to the local Codex runtime executable used for requests and session resume.",
            AuraCodeBundle.message("settings.codexPath.hint"),
        )
        assertEquals("Codex App Server", AuraCodeBundle.message("settings.environment.appServer"))
    }

    @Test
    fun `language labels expose japanese and korean options`() {
        assertEquals("日本語", AuraCodeBundle.message("settings.language.ja"))
        assertEquals("한국어", AuraCodeBundle.message("settings.language.ko"))
    }
}
