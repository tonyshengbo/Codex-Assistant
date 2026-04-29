package com.auracode.assistant.toolwindow.shell

import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.settings.RuntimeSettingsTab
import com.auracode.assistant.toolwindow.settings.SkillsSettingsTab
import kotlin.test.Test
import kotlin.test.assertEquals

class SidePanelAreaStoreTest {
    @Test
    fun `select skills settings tab updates only skills tab state`() {
        val store = SidePanelAreaStore()

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.SelectRuntimeSettingsTab(RuntimeSettingsTab.CLAUDE),
            ),
        )
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.SelectSkillsSettingsTab(SkillsSettingsTab.CLAUDE),
            ),
        )

        assertEquals(RuntimeSettingsTab.CLAUDE, store.state.value.runtimeSettingsTab)
        assertEquals(SkillsSettingsTab.CLAUDE, store.state.value.skillsSettingsTab)
    }
}
