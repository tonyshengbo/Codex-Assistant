package com.auracode.assistant.notification

/**
 * Supplies the latest IDE attention snapshot without coupling reminder logic to UI classes.
 */
internal fun interface IdeAttentionStateProvider {
    /**
     * Returns the current attention state of the IDE and tool window.
     */
    fun currentState(): IdeAttentionState
}
