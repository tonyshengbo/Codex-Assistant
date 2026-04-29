package com.auracode.assistant.toolwindow.conversation

/**
 * Captures the minimum timeline viewport state required to restore one session's scroll position.
 */
internal data class ConversationScrollSnapshot(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val autoFollowEnabled: Boolean,
)

