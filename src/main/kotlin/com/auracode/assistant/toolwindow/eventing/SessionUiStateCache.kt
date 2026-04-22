package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.toolwindow.approval.ApprovalAreaState
import com.auracode.assistant.toolwindow.approval.ApprovalAreaStore
import com.auracode.assistant.toolwindow.composer.ComposerAreaState
import com.auracode.assistant.toolwindow.composer.ComposerAreaStore
import com.auracode.assistant.toolwindow.status.StatusAreaState
import com.auracode.assistant.toolwindow.status.StatusAreaStore
import com.auracode.assistant.toolwindow.timeline.TimelineAreaState
import com.auracode.assistant.toolwindow.timeline.TimelineAreaStore
import com.auracode.assistant.toolwindow.toolinput.ToolUserInputPromptUiModel
import com.auracode.assistant.toolwindow.toolinput.ToolUserInputPromptState
import com.auracode.assistant.toolwindow.toolinput.ToolUserInputPromptStore

/**
 * Keeps per-session UI snapshots so background tabs can continue receiving events
 * without mutating the currently visible stores.
 */
internal class SessionUiStateCache {
    private val bundlesBySessionId = linkedMapOf<String, SessionUiStoreBundle>()

    fun captureVisibleState(
        sessionId: String,
        timelineStore: TimelineAreaStore,
        composerStore: ComposerAreaStore,
        approvalStore: ApprovalAreaStore,
        toolUserInputPromptStore: ToolUserInputPromptStore,
        statusStore: StatusAreaStore,
    ) {
        bundle(sessionId).restoreFromSnapshot(
            SessionUiSnapshot(
                timeline = timelineStore.state.value,
                composer = composerStore.state.value,
                approvals = approvalStore.state.value,
                toolUserInputs = toolUserInputPromptStore.state.value,
                status = statusStore.state.value,
            ),
        )
    }

    fun restoreVisibleState(
        sessionId: String,
        timelineStore: TimelineAreaStore,
        composerStore: ComposerAreaStore,
        approvalStore: ApprovalAreaStore,
        toolUserInputPromptStore: ToolUserInputPromptStore,
        statusStore: StatusAreaStore,
    ): Boolean {
        val snapshot = bundlesBySessionId[sessionId]?.snapshot() ?: return false
        timelineStore.restoreState(snapshot.timeline)
        composerStore.restoreState(snapshot.composer)
        approvalStore.restoreState(snapshot.approvals)
        toolUserInputPromptStore.restoreState(snapshot.toolUserInputs)
        statusStore.restoreState(snapshot.status)
        return true
    }

    fun applyScopedEvent(sessionId: String, event: AppEvent) {
        bundle(sessionId).apply(event)
    }

    /**
     * Stores only the composer state for a session while leaving other scoped stores untouched.
     */
    fun storeComposerState(sessionId: String, composerState: ComposerAreaState) {
        bundle(sessionId).restoreComposerState(composerState)
    }

    fun findToolUserInputPrompt(sessionId: String, requestId: String): ToolUserInputPromptUiModel? {
        return bundlesBySessionId[sessionId]?.findToolUserInputPrompt(requestId)
    }

    fun drop(sessionId: String) {
        bundlesBySessionId.remove(sessionId)
    }

    private fun bundle(sessionId: String): SessionUiStoreBundle {
        return bundlesBySessionId.getOrPut(sessionId) { SessionUiStoreBundle() }
    }
}

private class SessionUiStoreBundle {
    private val timelineStore = TimelineAreaStore()
    private val composerStore = ComposerAreaStore()
    private val approvalStore = ApprovalAreaStore()
    private val toolUserInputPromptStore = ToolUserInputPromptStore()
    private val statusStore = StatusAreaStore()

    fun restoreFromSnapshot(snapshot: SessionUiSnapshot) {
        timelineStore.restoreState(snapshot.timeline)
        composerStore.restoreState(snapshot.composer)
        approvalStore.restoreState(snapshot.approvals)
        toolUserInputPromptStore.restoreState(snapshot.toolUserInputs)
        statusStore.restoreState(snapshot.status)
    }

    /**
     * Replaces the cached composer store without affecting the other cached session stores.
     */
    fun restoreComposerState(composerState: ComposerAreaState) {
        composerStore.restoreState(composerState)
    }

    fun apply(event: AppEvent) {
        timelineStore.onEvent(event)
        composerStore.onEvent(event)
        approvalStore.onEvent(event)
        toolUserInputPromptStore.onEvent(event)
        statusStore.onEvent(event)
    }

    fun snapshot(): SessionUiSnapshot {
        return SessionUiSnapshot(
            timeline = timelineStore.state.value,
            composer = composerStore.state.value,
            approvals = approvalStore.state.value,
            toolUserInputs = toolUserInputPromptStore.state.value,
            status = statusStore.state.value,
        )
    }

    fun findToolUserInputPrompt(requestId: String): ToolUserInputPromptUiModel? {
        return toolUserInputPromptStore.state.value.queue.firstOrNull { it.requestId == requestId }
    }
}

private data class SessionUiSnapshot(
    val timeline: TimelineAreaState,
    val composer: ComposerAreaState,
    val approvals: ApprovalAreaState,
    val toolUserInputs: ToolUserInputPromptState,
    val status: StatusAreaState,
)
