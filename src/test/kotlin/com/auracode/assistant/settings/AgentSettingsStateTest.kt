package com.auracode.assistant.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentSettingsStateTest {
    @Test
    fun `state defaults theme mode to follow ide`() {
        val state = AgentSettingsService.State()

        assertEquals(UiThemeMode.FOLLOW_IDE.name, state.uiTheme)
    }

    @Test
    fun `state defaults ui scale mode to 100 percent`() {
        val state = AgentSettingsService.State()

        assertEquals(UiScaleMode.P100.name, state.uiScale)
    }

    @Test
    fun `legacy persisted ui scale values map to new percentage modes`() {
        val state = AgentSettingsService.State(uiScale = "SMALL")
        val service = AgentSettingsService()
        service.loadState(state)
        assertEquals(UiScaleMode.P90, service.uiScaleMode())

        service.loadState(AgentSettingsService.State(uiScale = "NORMAL"))
        assertEquals(UiScaleMode.P100, service.uiScaleMode())

        service.loadState(AgentSettingsService.State(uiScale = "LARGE"))
        assertEquals(UiScaleMode.P110, service.uiScaleMode())
    }

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

    @Test
    fun `state stores node executable path separately`() {
        val state = AgentSettingsService.State(
            nodeExecutablePath = "/opt/homebrew/bin/node",
        )

        assertEquals("/opt/homebrew/bin/node", state.nodeExecutablePath)
    }

    @Test
    fun `state stores local agent definitions`() {
        val state = AgentSettingsService.State(
            savedAgents = mutableListOf(
                SavedAgentDefinition(
                    id = "agent-1",
                    name = "Reviewer",
                    prompt = "Review the current implementation.",
                ),
            ),
        )

        assertEquals(1, state.savedAgents.size)
        assertEquals("Reviewer", state.savedAgents.single().name)
        assertEquals("Review the current implementation.", state.savedAgents.single().prompt)
    }

    @Test
    fun `state stores selected agent ids separately from saved agent definitions`() {
        val state = AgentSettingsService.State(
            selectedAgentIds = linkedSetOf("agent-2", "agent-1"),
        )

        assertEquals(listOf("agent-2", "agent-1"), state.selectedAgentIds.toList())
    }
}
