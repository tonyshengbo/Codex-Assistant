package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.toolwindow.approval.ApprovalAreaStore
import com.auracode.assistant.toolwindow.composer.ComposerAreaStore
import com.auracode.assistant.toolwindow.status.StatusAreaStore
import com.auracode.assistant.toolwindow.timeline.TimelineAreaStore
import com.auracode.assistant.toolwindow.toolinput.ToolUserInputPromptStore
import com.auracode.assistant.toolwindow.toolinput.ToolUserInputPromptUiModel

internal class SessionScopedEventDispatcher(
    private val activeSessionId: () -> String,
    private val sessionUiStateCache: SessionUiStateCache,
    private val statusStore: StatusAreaStore,
    private val timelineStore: TimelineAreaStore,
    private val composerStore: ComposerAreaStore,
    private val approvalStore: ApprovalAreaStore,
    private val toolUserInputPromptStore: ToolUserInputPromptStore,
) {
    fun dispatchSessionEvent(sessionId: String, event: AppEvent) {
        if (sessionId == activeSessionId()) {
            statusStore.onEvent(event)
            timelineStore.onEvent(event)
            composerStore.onEvent(event)
            approvalStore.onEvent(event)
            toolUserInputPromptStore.onEvent(event)
        } else {
            sessionUiStateCache.applyScopedEvent(sessionId, event)
        }
    }

    fun captureVisibleSessionState(sessionId: String) {
        if (sessionId.isBlank()) return
        sessionUiStateCache.captureVisibleState(
            sessionId = sessionId,
            timelineStore = timelineStore,
            composerStore = composerStore,
            approvalStore = approvalStore,
            toolUserInputPromptStore = toolUserInputPromptStore,
            statusStore = statusStore,
        )
    }

    fun restoreCachedSessionState(sessionId: String): Boolean {
        return sessionUiStateCache.restoreVisibleState(
            sessionId = sessionId,
            timelineStore = timelineStore,
            composerStore = composerStore,
            approvalStore = approvalStore,
            toolUserInputPromptStore = toolUserInputPromptStore,
            statusStore = statusStore,
        )
    }

    /**
     * Stores the composer snapshot for a session while preserving other scoped stores.
     */
    fun storeComposerState(sessionId: String, composerState: com.auracode.assistant.toolwindow.composer.ComposerAreaState) {
        sessionUiStateCache.storeComposerState(sessionId, composerState)
    }

    fun findToolUserInputPrompt(sessionId: String, requestId: String): ToolUserInputPromptUiModel? {
        return if (sessionId == activeSessionId()) {
            toolUserInputPromptStore.state.value.queue.firstOrNull { it.requestId == requestId }
        } else {
            sessionUiStateCache.findToolUserInputPrompt(sessionId, requestId)
        }
    }
}
