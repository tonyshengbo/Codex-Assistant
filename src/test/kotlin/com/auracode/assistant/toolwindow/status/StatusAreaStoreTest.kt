package com.auracode.assistant.toolwindow.execution

import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedToolUserInputPrompt
import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.shared.UiText
import kotlin.test.Test
import kotlin.test.assertEquals

class StatusAreaStoreTest {
    @Test
    fun `prompt accepted stores a localized running turn status reference`() {
        val store = StatusAreaStore()

        store.onEvent(AppEvent.PromptAccepted(prompt = "hello"))

        assertEquals(UiText.Bundle("status.running"), store.state.value.turnStatus?.label)
    }

    @Test
    fun `terminal engine errors clear the running turn status without toast`() {
        val store = StatusAreaStore()
        store.onEvent(AppEvent.PromptAccepted(prompt = "hello"))

        store.onEvent(AppEvent.UnifiedEventPublished(UnifiedEvent.Error("boom")))

        assertEquals(null, store.state.value.toast)
        assertEquals(null, store.state.value.turnStatus)
    }

    @Test
    fun `non terminal engine errors keep the running turn status while showing toast`() {
        val store = StatusAreaStore()
        store.onEvent(AppEvent.PromptAccepted(prompt = "hello"))

        store.onEvent(AppEvent.UnifiedEventPublished(UnifiedEvent.Error("Reconnecting... 1/5", terminal = false)))

        assertEquals(UiText.Raw("Reconnecting... 1/5"), store.state.value.toast?.text)
        assertEquals(UiText.Bundle("status.running"), store.state.value.turnStatus?.label)
    }

    @Test
    fun `tool user input toggles turn status between waiting and running`() {
        val store = StatusAreaStore()
        store.onEvent(AppEvent.PromptAccepted(prompt = "hello"))

        store.onEvent(
            AppEvent.UnifiedEventPublished(
                UnifiedEvent.ToolUserInputRequested(
                    UnifiedToolUserInputPrompt(
                        requestId = "request-1",
                        threadId = "thread-1",
                        turnId = "turn-1",
                        itemId = "call-1",
                        questions = emptyList(),
                    ),
                ),
            ),
        )
        assertEquals(UiText.Bundle("status.waitingInput"), store.state.value.turnStatus?.label)

        store.onEvent(
            AppEvent.UnifiedEventPublished(
                UnifiedEvent.ToolUserInputResolved(requestId = "request-1"),
            ),
        )
        assertEquals(UiText.Bundle("status.running"), store.state.value.turnStatus?.label)
    }

    @Test
    fun `tool user input cancel keeps turn status cleared instead of restoring running`() {
        val store = StatusAreaStore()
        store.onEvent(AppEvent.PromptAccepted(prompt = "hello"))

        store.onEvent(AppEvent.ToolUserInputRequested(prompt()))
        assertEquals(UiText.Bundle("status.waitingInput"), store.state.value.turnStatus?.label)

        store.onEvent(AppEvent.ActiveRunCancelled)
        store.onEvent(AppEvent.ToolUserInputResolved("request-1"))

        assertEquals(null, store.state.value.turnStatus)
    }

    private fun prompt(): com.auracode.assistant.toolwindow.execution.ToolUserInputPromptUiModel {
        return com.auracode.assistant.toolwindow.execution.ToolUserInputPromptUiModel(
            requestId = "request-1",
            threadId = "thread-1",
            turnId = "turn-1",
            itemId = "call-1",
            questions = emptyList(),
        )
    }
}
