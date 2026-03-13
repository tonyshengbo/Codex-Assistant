package com.codex.assistant.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentSettingsStateTest {
    @Test
    fun `state reads codex path from engine map first`() {
        val state = AgentSettingsService.State(
            codexCliPath = "legacy-codex",
            engineExecutablePaths = mutableMapOf("codex" to "/usr/local/bin/codex"),
        )

        assertEquals("/usr/local/bin/codex", state.executablePathFor("codex"))
    }

    @Test
    fun `state falls back to codexCliPath when map key is absent`() {
        val state = AgentSettingsService.State(
            codexCliPath = "codex-from-legacy",
            engineExecutablePaths = mutableMapOf(),
        )

        assertEquals("codex-from-legacy", state.executablePathFor("codex"))
    }
}
