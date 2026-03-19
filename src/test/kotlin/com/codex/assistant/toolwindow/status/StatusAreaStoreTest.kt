package com.codex.assistant.toolwindow.status

import com.codex.assistant.protocol.UnifiedEvent
import com.codex.assistant.toolwindow.eventing.AppEvent
import com.codex.assistant.toolwindow.shared.UiText
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
    fun `engine errors become raw toasts and clear the running turn status`() {
        val store = StatusAreaStore()
        store.onEvent(AppEvent.PromptAccepted(prompt = "hello"))

        store.onEvent(AppEvent.UnifiedEventPublished(UnifiedEvent.Error("boom")))

        assertEquals(UiText.Raw("boom"), store.state.value.toast?.text)
        assertEquals(null, store.state.value.turnStatus)
    }
}
