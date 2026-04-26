package com.auracode.assistant.toolwindow.execution

import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.eventing.ComposerMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanCompletionPromptStoreTest {
    @Test
    fun `prompt becomes visible when updated`() {
        val store = PlanCompletionPromptStore()

        store.onEvent(AppEvent.PlanCompletionPromptUpdated(prompt("turn-1")))

        assertTrue(store.state.value.visible)
        assertEquals("turn-1", store.state.value.current?.turnId)
    }

    @Test
    fun `prompt clears when null update arrives`() {
        val store = PlanCompletionPromptStore()
        store.onEvent(AppEvent.PlanCompletionPromptUpdated(prompt("turn-1")))

        store.onEvent(AppEvent.PlanCompletionPromptUpdated(null))

        assertFalse(store.state.value.visible)
        assertEquals(null, store.state.value.current)
    }

    @Test
    fun `prompt clears on accepted outgoing message`() {
        val store = PlanCompletionPromptStore()
        store.onEvent(AppEvent.PlanCompletionPromptUpdated(prompt("turn-1")))

        store.onEvent(AppEvent.PromptAccepted("next prompt"))

        assertFalse(store.state.value.visible)
        assertEquals(null, store.state.value.current)
    }

    private fun prompt(turnId: String): PlanCompletionPromptUiModel {
        return PlanCompletionPromptUiModel(
            turnId = turnId,
            threadId = "thread-1",
            body = "- [pending] Implement plan mode",
            preferredExecutionMode = ComposerMode.APPROVAL,
        )
    }
}
