package com.auracode.assistant.notification

/**
 * Describes whether the user is actively watching the tool window and which session is visible.
 */
internal data class IdeAttentionState(
    val isIdeFrameFocused: Boolean,
    val isToolWindowVisible: Boolean,
    val activeSessionId: String,
) {
    /**
     * Returns true when the completed session is already visible to the user and needs no reminder.
     */
    fun isWatchingSession(sessionId: String): Boolean {
        return isIdeFrameFocused && isToolWindowVisible && activeSessionId == sessionId
    }
}
