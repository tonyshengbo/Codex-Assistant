package com.auracode.assistant.toolwindow.execution

import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.shared.UiText
import kotlin.test.Test
import kotlin.test.assertEquals

class ExecutionStatusAreaStoreTest {
    @Test
    fun `prompt accepted stores a localized running turn status reference`() {
        val store = ExecutionStatusAreaStore()

        store.onEvent(AppEvent.PromptAccepted(prompt = "hello"))

        assertEquals(UiText.Bundle("status.running"), store.state.value.turnStatus?.label)
    }

    @Test
    fun `execution projection clears the running turn status when no turn is active`() {
        val store = ExecutionStatusAreaStore()
        store.onEvent(AppEvent.PromptAccepted(prompt = "hello"))

        store.onEvent(
            AppEvent.ExecutionUiProjectionUpdated(
                approvals = emptyList(),
                toolUserInputs = emptyList(),
                turnStatus = null,
            ),
        )

        assertEquals(null, store.state.value.toast)
        assertEquals(null, store.state.value.turnStatus)
    }

    @Test
    fun `status text keeps the running turn status while showing toast`() {
        val store = ExecutionStatusAreaStore()
        store.onEvent(AppEvent.PromptAccepted(prompt = "hello"))

        store.onEvent(AppEvent.StatusTextUpdated(UiText.Raw("Reconnecting... 1/5")))

        assertEquals(UiText.Raw("Reconnecting... 1/5"), store.state.value.toast?.text)
        assertEquals(UiText.Bundle("status.running"), store.state.value.turnStatus?.label)
    }

    @Test
    fun `tool user input toggles turn status between waiting and running`() {
        val store = ExecutionStatusAreaStore()
        store.onEvent(AppEvent.PromptAccepted(prompt = "hello"))

        store.onEvent(AppEvent.ToolUserInputRequested(prompt()))
        assertEquals(UiText.Bundle("status.waitingInput"), store.state.value.turnStatus?.label)

        store.onEvent(AppEvent.ToolUserInputResolved(requestId = "request-1"))
        assertEquals(UiText.Bundle("status.running"), store.state.value.turnStatus?.label)
    }

    @Test
    fun `tool user input cancel keeps turn status cleared instead of restoring running`() {
        val store = ExecutionStatusAreaStore()
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
