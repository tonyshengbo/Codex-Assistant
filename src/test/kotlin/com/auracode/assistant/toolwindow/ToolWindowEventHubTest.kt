package com.auracode.assistant.toolwindow

import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.eventing.ToolWindowEventHub
import com.auracode.assistant.toolwindow.eventing.UiIntent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ToolWindowEventHubTest {
    @Test
    fun `publishes ui intents to app event stream`() {
        val hub = ToolWindowEventHub()
        hub.publishUiIntent(UiIntent.ToggleHistory)

        val event = hub.events.value.last()
        val ui = assertIs<AppEvent.UiIntentPublished>(event)
        assertEquals(UiIntent.ToggleHistory, ui.intent)
    }

    @Test
    fun `stream does not drop burst events when consumer is slow`() = runBlocking {
        val hub = ToolWindowEventHub()
        val received = mutableListOf<AppEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            hub.stream.take(300).collect { event ->
                received += event
                delay(5)
            }
        }

        repeat(300) { index ->
            hub.publishUiIntent(
                when (index % 2) {
                    0 -> UiIntent.ToggleHistory
                    else -> UiIntent.ToggleSettings
                },
            )
        }

        collector.join()
        assertEquals(300, received.size)
    }
}
