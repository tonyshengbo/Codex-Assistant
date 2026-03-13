package com.codex.assistant.toolwindow

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
    fun `formats running composer state with loading message`() {
        assertEquals("Loading · 45s", ToolWindowUiText.runningStatus(45_000))
        assertEquals("Loading · 1m 12s", ToolWindowUiText.runningStatus(72_000))
        assertEquals("Loading · 1h 01m", ToolWindowUiText.runningStatus(3_660_000))
    }

    @Test
    fun `selection chips only show current value`() {
        assertEquals("Codex", ToolWindowUiText.selectionChipLabel("Provider: Codex"))
        assertEquals("gpt-5.3-codex", ToolWindowUiText.selectionChipLabel("Model: gpt-5.3-codex"))
        assertEquals("Auto", ToolWindowUiText.selectionChipLabel("Approvals: Auto"))
        assertEquals("Auto", ToolWindowUiText.selectionChipLabel("Auto"))
    }
}
