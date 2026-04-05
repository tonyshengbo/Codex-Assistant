package com.auracode.assistant.toolwindow.eventing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.auracode.assistant.integration.ide.IdeExternalRequest
import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.FileAttachment
import com.auracode.assistant.model.ImageAttachment
import com.auracode.assistant.persistence.chat.PersistedAttachmentKind
import com.auracode.assistant.toolwindow.composer.PendingComposerSubmission
import com.auracode.assistant.toolwindow.shared.UiText
import com.auracode.assistant.toolwindow.timeline.TimelineNodeMapper

internal class ConversationFlowHandler(
    private val context: ToolWindowCoordinatorContext,
    private val workspaceHandler: WorkspaceInteractionHandler,
) {
    fun deleteSession(onRestoreHistory: () -> Unit, sessionId: String) {
        if (!context.chatService.deleteSession(sessionId)) return
        context.sessionAttentionStore.drop(sessionId)
        context.pendingSubmissionsBySessionId.remove(sessionId)
        context.activePlanRunContexts.remove(sessionId)
        context.publishSessionSnapshot()
        onRestoreHistory()
    }

    fun switchSession(sessionId: String, onRestoreHistory: () -> Unit) {
        val previousSessionId = context.activeSessionId()
        if (previousSessionId == sessionId) return
        context.eventDispatcher.captureVisibleSessionState(previousSessionId)
        if (!context.chatService.switchSession(sessionId)) return
        context.sessionAttentionStore.clear(sessionId)
        context.publishSessionSnapshot()
        if (!context.eventDispatcher.restoreCachedSessionState(sessionId)) {
            onRestoreHistory()
        }
    }

    fun submitPromptIfAllowed() {
        val composerState = context.composerStore.state.value
        val submission = buildPendingSubmission(composerState) ?: return
        if (composerState.sessionIsRunning || context.timelineStore.state.value.isRunning) {
            enqueuePendingSubmission(submission)
        } else {
            dispatchSubmission(submission)
        }
    }

    /** Routes IDE-originated requests through the same submission pipeline as composer prompts. */
    fun submitExternalRequest(request: IdeExternalRequest) {
        context.composerStore.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue(request.prompt, TextRange(request.prompt.length))),
            ),
        )
        val submission = buildExternalSubmission(request)
        if (context.composerStore.state.value.sessionIsRunning || context.timelineStore.state.value.isRunning) {
            enqueuePendingSubmission(submission)
        } else {
            dispatchSubmission(submission)
        }
    }

    fun cancelPromptRun(onResetPlanFlowState: () -> Unit, onTurnCompleted: (String) -> Unit) {
        context.chatService.cancelCurrent()
        onResetPlanFlowState()
        context.eventHub.publish(AppEvent.ActiveRunCancelled)
        publishUnifiedEvent(
            context.activeSessionId(),
            com.auracode.assistant.protocol.UnifiedEvent.TurnCompleted(
                turnId = "",
                outcome = com.auracode.assistant.protocol.TurnOutcome.CANCELLED,
                usage = null,
            ),
            onTurnCompleted,
        )
    }

    fun removePendingSubmission(id: String) {
        val sessionId = context.activeSessionId()
        val removed = context.pendingSubmissionQueue(sessionId).removeAll { it.id == id }
        if (!removed) return
        publishPendingSubmissions(sessionId = sessionId)
    }

    fun dispatchNextPendingSubmissionIfIdle(sessionId: String, allowTurnCompletedBypass: Boolean = false) {
        if (!allowTurnCompletedBypass && context.chatService.listSessions().firstOrNull { it.id == sessionId }?.isRunning == true) return
        val next = context.pendingSubmissionQueue(sessionId).removeFirstOrNull() ?: return
        publishPendingSubmissions(sessionId = sessionId)
        dispatchSubmission(next, sessionId)
    }

    fun requestAgentSuggestions(query: String, documentVersion: Long, mentionLimit: Int) {
        val normalizedQuery = query.trim()
        val suggestions = context.settingsService.savedAgents()
            .filter { agent -> normalizedQuery.isBlank() || agent.name.contains(normalizedQuery, ignoreCase = true) }
            .take(mentionLimit)
        context.eventHub.publish(
            AppEvent.AgentSuggestionsUpdated(
                query = normalizedQuery,
                documentVersion = documentVersion,
                suggestions = suggestions,
            ),
        )
    }

    fun publishUnifiedEvent(sessionId: String, event: com.auracode.assistant.protocol.UnifiedEvent, onTurnCompleted: (String) -> Unit) {
        context.eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.UnifiedEventPublished(event))
        com.auracode.assistant.toolwindow.timeline.TimelineNodeMapper.fromUnifiedEvent(event)?.let { mutation ->
            context.eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.TimelineMutationApplied(mutation = mutation))
        }
        if (event is com.auracode.assistant.protocol.UnifiedEvent.TurnCompleted) {
            onTurnCompleted(sessionId)
        }
    }

    private fun buildPendingSubmission(
        composerState: com.auracode.assistant.toolwindow.composer.ComposerAreaState,
    ): PendingComposerSubmission? {
        val disabledSkills = context.skillsRuntimeService.findDisabledSkillMentions(
            engineId = context.chatService.defaultEngineId(),
            cwd = context.chatService.currentWorkingDirectory(),
            text = composerState.inputText,
        )
        if (disabledSkills.isNotEmpty()) {
            context.eventHub.publish(
                AppEvent.StatusTextUpdated(UiText.raw("Disabled skills cannot be used: ${disabledSkills.joinToString(", ")}")),
            )
            return null
        }
        val prompt = composerState.serializedPrompt()
        val systemInstructions = composerState.serializedSystemInstructions()
        if (prompt.isBlank() && systemInstructions.isEmpty()) return null
        val stagedAttachments = workspaceHandler.stageAttachments(
            sessionId = context.chatService.getCurrentSessionId(),
            attachments = composerState.attachments,
        )
        return PendingComposerSubmission(
            id = ToolWindowCoordinatorIds.newPendingSubmissionId(composerState),
            prompt = prompt,
            systemInstructions = systemInstructions,
            contextFiles = workspaceHandler.buildContextFiles(
                contextEntries = composerState.contextEntries,
                attachments = stagedAttachments,
            ),
            imageAttachments = stagedAttachments.filter { it.kind == PersistedAttachmentKind.IMAGE }.map {
                ImageAttachment(path = it.assetPath, name = it.displayName, mimeType = it.mimeType.ifBlank { "image/png" })
            },
            fileAttachments = stagedAttachments.filter { it.kind == PersistedAttachmentKind.FILE }.map {
                FileAttachment(path = it.assetPath, name = it.displayName, mimeType = it.mimeType.ifBlank { "application/octet-stream" })
            },
            stagedAttachments = stagedAttachments,
            selectedModel = composerState.selectedModel,
            selectedReasoning = composerState.selectedReasoning,
            executionMode = composerState.executionMode,
            planEnabled = composerState.planEnabled,
        )
    }

    /** IDE entrypoints may supply their own explicit context files while reusing the active composer settings. */
    private fun buildExternalSubmission(request: IdeExternalRequest): PendingComposerSubmission {
        val composerState = context.composerStore.state.value
        return PendingComposerSubmission(
            id = ToolWindowCoordinatorIds.newExternalSubmissionId(context.pendingSubmissionQueue(context.activeSessionId())),
            prompt = request.prompt,
            systemInstructions = composerState.serializedSystemInstructions(),
            contextFiles = request.contextFiles,
            imageAttachments = emptyList(),
            fileAttachments = emptyList(),
            stagedAttachments = emptyList(),
            selectedModel = composerState.selectedModel,
            selectedReasoning = composerState.selectedReasoning,
            executionMode = composerState.executionMode,
            planEnabled = false,
        )
    }

    private fun enqueuePendingSubmission(submission: PendingComposerSubmission) {
        val sessionId = context.activeSessionId()
        context.pendingSubmissionQueue(sessionId).addLast(submission)
        publishPendingSubmissions(sessionId = sessionId, clearComposerDraft = true)
    }

    private fun publishPendingSubmissions(sessionId: String, clearComposerDraft: Boolean = false) {
        context.eventDispatcher.dispatchSessionEvent(
            sessionId,
            AppEvent.PendingSubmissionsUpdated(
                submissions = context.pendingSubmissionQueue(sessionId).toList(),
                clearComposerDraft = clearComposerDraft,
            ),
        )
    }

    private fun dispatchSubmission(
        submission: PendingComposerSubmission,
        sessionId: String = context.activeSessionId(),
    ) {
        val localTurnId = ToolWindowCoordinatorIds.newLocalTurnId()
        val localMessage = if (submission.prompt.isBlank()) {
            null
        } else {
            context.chatService.recordUserMessage(
                sessionId = sessionId,
                prompt = submission.prompt,
                turnId = localTurnId,
                attachments = submission.stagedAttachments,
            )
        }
        if (submission.planEnabled) {
            context.activePlanRunContexts[sessionId] = ActivePlanRunContext(
                localTurnId = localTurnId,
                preferredExecutionMode = submission.executionMode,
            )
        } else {
            context.activePlanRunContexts.remove(sessionId)
        }
        context.eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.PlanCompletionPromptUpdated(prompt = null))
        context.eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.ClearToolUserInputs)
        context.eventDispatcher.dispatchSessionEvent(
            sessionId,
            AppEvent.PromptAccepted(prompt = submission.prompt, localTurnId = localTurnId),
        )
        localMessage?.let { message ->
            context.eventDispatcher.dispatchSessionEvent(
                sessionId,
                AppEvent.TimelineMutationApplied(
                    mutation = TimelineNodeMapper.localUserMessageMutation(
                        sourceId = message.sourceId,
                        text = message.prompt,
                        timestamp = message.timestamp,
                        turnId = message.turnId,
                        attachments = message.attachments,
                    ),
                ),
            )
        }
        context.publishSessionSnapshot()
        context.chatService.runAgent(
            sessionId = sessionId,
            engineId = context.chatService.defaultEngineId(),
            model = submission.selectedModel,
            reasoningEffort = submission.selectedReasoning.effort,
            prompt = submission.prompt,
            systemInstructions = submission.systemInstructions,
            localTurnId = localTurnId,
            contextFiles = submission.contextFiles,
            imageAttachments = submission.imageAttachments,
            fileAttachments = submission.fileAttachments,
            approvalMode = submission.executionMode.toApprovalMode(),
            collaborationMode = if (submission.planEnabled) AgentCollaborationMode.PLAN else AgentCollaborationMode.DEFAULT,
            onTurnPersisted = { context.publishSessionSnapshot() },
            onUnifiedEvent = { event -> context.publishUnifiedEvent(sessionId, event) },
        )
    }

    private fun ComposerMode.toApprovalMode(): AgentApprovalMode {
        return when (this) {
            ComposerMode.AUTO -> AgentApprovalMode.AUTO
            ComposerMode.APPROVAL -> AgentApprovalMode.REQUIRE_CONFIRMATION
        }
    }
}
