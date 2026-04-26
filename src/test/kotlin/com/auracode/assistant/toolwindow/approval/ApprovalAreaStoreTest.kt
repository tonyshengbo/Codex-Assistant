package com.auracode.assistant.toolwindow.execution

import com.auracode.assistant.protocol.UnifiedApprovalRequest
import com.auracode.assistant.protocol.UnifiedApprovalRequestKind
import com.auracode.assistant.toolwindow.eventing.AppEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApprovalAreaStoreTest {
    @Test
    fun `approval requests are queued one by one`() {
        val store = ApprovalAreaStore()

        store.onEvent(AppEvent.ApprovalRequested(request("req-1", "item-1").toUiModel()))
        store.onEvent(AppEvent.ApprovalRequested(request("req-2", "item-2").toUiModel()))

        val state = store.state.value
        assertTrue(state.visible)
        assertEquals(2, state.queue.size)
        assertEquals("req-1", state.current?.requestId)
        assertEquals(ApprovalAction.ALLOW, state.selectedAction)
    }

    @Test
    fun `resolving current approval advances queue`() {
        val store = ApprovalAreaStore()
        store.onEvent(AppEvent.ApprovalRequested(request("req-1", "item-1").toUiModel()))
        store.onEvent(AppEvent.ApprovalRequested(request("req-2", "item-2").toUiModel()))

        store.onEvent(AppEvent.ApprovalResolved(requestId = "req-1"))

        val state = store.state.value
        assertEquals(1, state.queue.size)
        assertEquals("req-2", state.current?.requestId)
        assertEquals(ApprovalAction.ALLOW, state.selectedAction)
    }

    @Test
    fun `turn completion clears approval queue`() {
        val store = ApprovalAreaStore()
        store.onEvent(AppEvent.ApprovalRequested(request("req-1", "item-1").toUiModel()))

        store.onEvent(AppEvent.ClearApprovals)

        assertFalse(store.state.value.visible)
        assertTrue(store.state.value.queue.isEmpty())
        assertEquals(null, store.state.value.current)
    }

    @Test
    fun `selection moves across available actions`() {
        val store = ApprovalAreaStore()
        store.onEvent(AppEvent.ApprovalRequested(request("req-1", "item-1").toUiModel()))

        store.onEvent(AppEvent.UiIntentPublished(com.auracode.assistant.toolwindow.eventing.UiIntent.MoveApprovalActionNext))
        assertEquals(ApprovalAction.REJECT, store.state.value.selectedAction)

        store.onEvent(AppEvent.UiIntentPublished(com.auracode.assistant.toolwindow.eventing.UiIntent.MoveApprovalActionNext))
        assertEquals(ApprovalAction.ALLOW_FOR_SESSION, store.state.value.selectedAction)

        store.onEvent(AppEvent.UiIntentPublished(com.auracode.assistant.toolwindow.eventing.UiIntent.MoveApprovalActionPrevious))
        assertEquals(ApprovalAction.REJECT, store.state.value.selectedAction)
        assertNotNull(store.state.value.current)
    }

    private fun request(requestId: String, itemId: String): UnifiedApprovalRequest {
        return UnifiedApprovalRequest(
            requestId = requestId,
            turnId = "turn-1",
            itemId = itemId,
            kind = UnifiedApprovalRequestKind.COMMAND,
            title = "Run command",
            body = "./gradlew test",
            command = "./gradlew test",
            cwd = ".",
        )
    }
}
