package com.auracode.assistant.toolwindow.sessions

import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.toolwindow.conversation.ConversationActivityItem
import com.auracode.assistant.toolwindow.conversation.ConversationAreaState
import com.auracode.assistant.toolwindow.conversation.ConversationAreaStore
import com.auracode.assistant.toolwindow.conversation.ConversationScrollSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionConversationUiStateRegistryTest {
    @Test
    fun `restore reapplies expanded nodes and scroll snapshot for captured session`() {
        val registry = SessionConversationUiStateRegistry()
        val store = ConversationAreaStore()
        val expectedSnapshot = ConversationScrollSnapshot(
            firstVisibleItemIndex = 5,
            firstVisibleItemScrollOffset = 28,
            autoFollowEnabled = false,
        )

        registry.capture(
            sessionId = "session-1",
            state = ConversationAreaState(
                expandedNodeIds = setOf("node-2"),
                scrollSnapshot = expectedSnapshot,
            ),
        )

        store.restoreState(
            ConversationAreaState(
                nodes = listOf(
                    ConversationActivityItem.MessageNode(
                        id = "node-1",
                        sourceId = "node-1",
                        role = MessageRole.USER,
                        text = "first",
                        status = ItemStatus.SUCCESS,
                        timestamp = null,
                        turnId = null,
                        cursor = null,
                    ),
                    ConversationActivityItem.MessageNode(
                        id = "node-2",
                        sourceId = "node-2",
                        role = MessageRole.ASSISTANT,
                        text = "second",
                        status = ItemStatus.SUCCESS,
                        timestamp = null,
                        turnId = null,
                        cursor = null,
                    ),
                ),
            ),
        )

        registry.restore("session-1", store)

        assertEquals(setOf("node-2"), store.state.value.expandedNodeIds)
        assertEquals(expectedSnapshot, store.state.value.pendingScrollRestoreSnapshot)
        assertEquals(1L, store.state.value.scrollRestoreRequestVersion)
    }

    @Test
    fun `drop forgets captured scroll snapshot`() {
        val registry = SessionConversationUiStateRegistry()
        val store = ConversationAreaStore()
        registry.capture(
            sessionId = "session-1",
            state = ConversationAreaState(
                scrollSnapshot = ConversationScrollSnapshot(
                    firstVisibleItemIndex = 1,
                    firstVisibleItemScrollOffset = 10,
                    autoFollowEnabled = true,
                ),
            ),
        )

        registry.drop("session-1")
        registry.restore("session-1", store)

        assertNull(store.state.value.pendingScrollRestoreSnapshot)
        assertEquals(0L, store.state.value.scrollRestoreRequestVersion)
    }
}
