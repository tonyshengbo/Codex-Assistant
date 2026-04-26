package com.auracode.assistant.toolwindow.submission

import com.auracode.assistant.toolwindow.eventing.ComposerMode
import com.auracode.assistant.toolwindow.eventing.UiIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerControlBarTest {
    @Test
    fun `running session disables engine switching affordance`() {
        assertFalse(resolveEngineSwitchEnabled(running = true))
        assertTrue(resolveEngineSwitchEnabled(running = false))
    }

    @Test
    fun `switching engine from a populated session selects immediately`() {
        val intent = resolveEngineSelectionIntent(
            state = ComposerAreaState(
                selectedEngineId = "codex",
                activeSessionMessageCount = 2,
            ),
            engineId = "claude",
        )

        assertEquals(UiIntent.SelectEngine("claude"), intent)
    }

    @Test
    fun `switching engine from an empty session still selects immediately`() {
        val intent = resolveEngineSelectionIntent(
            state = ComposerAreaState(
                selectedEngineId = "codex",
                activeSessionMessageCount = 0,
            ),
            engineId = "claude",
        )

        assertEquals(UiIntent.SelectEngine("claude"), intent)
    }

    @Test
    fun `running engine keeps trailing action locked to cancel even when prompt has content`() {
        val state = resolveComposerTrailingActionState(
            running = true,
            hasPromptContent = true,
        )

        assertEquals("/icons/stop.svg", state.iconPath)
        assertEquals(UiIntent.CancelRun, state.intent)
        assertTrue(state.enabled)
        assertTrue(state.running)
    }

    @Test
    fun `idle engine keeps send disabled until prompt content exists`() {
        val emptyState = resolveComposerTrailingActionState(
            running = false,
            hasPromptContent = false,
        )
        val readyState = resolveComposerTrailingActionState(
            running = false,
            hasPromptContent = true,
        )

        assertEquals("/icons/send.svg", emptyState.iconPath)
        assertEquals(UiIntent.SendPrompt, emptyState.intent)
        assertFalse(emptyState.enabled)
        assertFalse(emptyState.running)

        assertEquals("/icons/send.svg", readyState.iconPath)
        assertEquals(UiIntent.SendPrompt, readyState.intent)
        assertTrue(readyState.enabled)
        assertFalse(readyState.running)
    }

    @Test
    fun `plan tooltip falls back to capability reason when plan mode is unavailable`() {
        assertEquals(
            "Plan mode is not available for Claude yet.",
            resolvePlanModeTooltip(
                planModeAvailable = false,
                disabledReason = "Plan mode is not available for Claude yet.",
            ),
        )
    }

    @Test
    fun `approval mode tooltip explains workspace write approvals`() {
        assertEquals(
            "Approval mode is enabled. Codex uses a workspace-write sandbox and asks when approval is required.",
            resolveExecutionModeTooltip(ComposerMode.APPROVAL),
        )
    }
}
