package com.auracode.assistant.toolwindow.eventing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.auracode.assistant.integration.ide.IdeExternalRequest
import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.FileAttachment
import com.auracode.assistant.model.ImageAttachment
import com.auracode.assistant.persistence.chat.PersistedAttachmentKind
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.session.kernel.SessionTurnOutcome
import com.auracode.assistant.toolwindow.submission.PendingSubmission
import com.auracode.assistant.toolwindow.shared.UiText

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
        context.publishSettingsSnapshot()
        context.publishConversationCapabilities()
        onRestoreHistory()
    }

    fun switchSession(sessionId: String, onRestoreHistory: () -> Unit) {
        val previousSessionId = context.activeSessionId()
        if (previousSessionId == sessionId) return
        context.captureSessionViewState(previousSessionId)
        if (!context.chatService.switchSession(sessionId)) return
        context.sessionAttentionStore.clear(sessionId)
        context.publishSessionSnapshot()
        context.publishSettingsSnapshot()
        context.publishConversationCapabilities()
        if (!context.restoreSessionViewState(sessionId)) {
            onRestoreHistory()
        }
        publishPendingSubmissions(sessionId = sessionId)
    }

    fun submitPromptIfAllowed() {
        val submissionState = context.submissionStore.state.value
        val submission = buildPendingSubmission(submissionState) ?: return
        if (submissionState.sessionIsRunning || context.conversationStore.state.value.isRunning) {
            enqueuePendingSubmission(submission)
        } else {
            dispatchSubmission(submission)
        }
    }

    /** Routes IDE-originated requests through the same submission pipeline as composer prompts. */
    fun submitExternalRequest(request: IdeExternalRequest) {
        context.submissionStore.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue(request.prompt, TextRange(request.prompt.length))),
            ),
        )
        val submission = buildExternalSubmission(request)
        if (context.submissionStore.state.value.sessionIsRunning || context.conversationStore.state.value.isRunning) {
            enqueuePendingSubmission(submission)
        } else {
            dispatchSubmission(submission)
        }
    }

    fun cancelPromptRun(onResetPlanFlowState: () -> Unit) {
        context.chatService.cancelCurrent()
        onResetPlanFlowState()
        context.eventHub.publish(AppEvent.ActiveRunCancelled)
        context.applySessionDomainEvents(
            context.activeSessionId(),
            listOf(
                SessionDomainEvent.TurnCompleted(
                    turnId = "",
                    outcome = SessionTurnOutcome.CANCELLED,
                ),
            ),
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

    private fun buildPendingSubmission(
        submissionState: com.auracode.assistant.toolwindow.submission.SubmissionAreaState,
    ): PendingSubmission? {
        val disabledSkills = context.skillsRuntimeService.findDisabledSkillMentions(
            engineId = submissionState.selectedEngineId,
            cwd = context.chatService.currentWorkingDirectory(),
            text = submissionState.inputText,
        )
        if (disabledSkills.isNotEmpty()) {
            context.eventHub.publish(
                AppEvent.StatusTextUpdated(UiText.raw("Disabled skills cannot be used: ${disabledSkills.joinToString(", ")}")),
            )
            return null
        }
        val prompt = submissionState.serializedPrompt()
        val systemInstructions = submissionState.serializedSystemInstructions()
        if (prompt.isBlank() && systemInstructions.isEmpty()) return null
        val stagedAttachments = workspaceHandler.stageAttachments(
            sessionId = context.chatService.getCurrentSessionId(),
            attachments = submissionState.attachments,
        )
        return PendingSubmission(
            id = ToolWindowCoordinatorIds.newPendingSubmissionId(submissionState),
            engineId = submissionState.selectedEngineId,
            prompt = prompt,
            systemInstructions = systemInstructions,
            contextFiles = workspaceHandler.buildContextFiles(
                contextEntries = submissionState.contextEntries,
                attachments = stagedAttachments,
            ),
            imageAttachments = stagedAttachments.filter { it.kind == PersistedAttachmentKind.IMAGE }.map {
                ImageAttachment(path = it.assetPath, name = it.displayName, mimeType = it.mimeType.ifBlank { "image/png" })
            },
            fileAttachments = stagedAttachments.filter { it.kind == PersistedAttachmentKind.FILE }.map {
                FileAttachment(path = it.assetPath, name = it.displayName, mimeType = it.mimeType.ifBlank { "application/octet-stream" })
            },
            stagedAttachments = stagedAttachments,
            selectedModel = submissionState.selectedModel,
            selectedReasoning = submissionState.selectedReasoning,
            executionMode = submissionState.executionMode,
            planEnabled = submissionState.planEnabled,
        )
    }

    /** IDE entrypoints may supply their own explicit context files while reusing the active composer settings. */
    private fun buildExternalSubmission(request: IdeExternalRequest): PendingSubmission {
        val submissionState = context.submissionStore.state.value
        return PendingSubmission(
            id = ToolWindowCoordinatorIds.newExternalSubmissionId(context.pendingSubmissionQueue(context.activeSessionId())),
            engineId = submissionState.selectedEngineId,
            prompt = request.prompt,
            systemInstructions = submissionState.serializedSystemInstructions(),
            contextFiles = request.contextFiles,
            imageAttachments = emptyList(),
            fileAttachments = emptyList(),
            stagedAttachments = emptyList(),
            selectedModel = submissionState.selectedModel,
            selectedReasoning = submissionState.selectedReasoning,
            executionMode = submissionState.executionMode,
            planEnabled = false,
        )
    }

    private fun enqueuePendingSubmission(submission: PendingSubmission) {
        val sessionId = context.activeSessionId()
        context.pendingSubmissionQueue(sessionId).addLast(submission)
        publishPendingSubmissions(sessionId = sessionId, clearSubmissionDraft = true)
    }

    private fun publishPendingSubmissions(sessionId: String, clearSubmissionDraft: Boolean = false) {
        context.dispatchSessionEvent(
            sessionId,
            AppEvent.PendingSubmissionsUpdated(
                submissions = context.pendingSubmissionQueue(sessionId).toList(),
                clearSubmissionDraft = clearSubmissionDraft,
            ),
        )
    }

    private fun dispatchSubmission(
        submission: PendingSubmission,
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
        context.dispatchSessionEvent(sessionId, AppEvent.PlanCompletionPromptUpdated(prompt = null))
        context.dispatchSessionEvent(sessionId, AppEvent.ClearToolUserInputs)
        context.dispatchSessionEvent(
            sessionId,
            AppEvent.PromptAccepted(prompt = submission.prompt, localTurnId = localTurnId),
        )
        localMessage?.let { message ->
            context.publishLocalUserMessage(
                sessionId,
                message.sourceId,
                message.prompt,
                message.timestamp,
                message.turnId,
                message.attachments,
            )
        }
        context.publishSessionSnapshot()
        context.chatService.runAgent(
            sessionId = sessionId,
            engineId = submission.engineId,
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
            onSessionDomainEvents = { events -> context.applySessionDomainEvents(sessionId, events) },
            onRunStateChanged = { context.publishSessionSnapshot() },
        )
    }

    private fun SubmissionMode.toApprovalMode(): AgentApprovalMode {
        return when (this) {
            SubmissionMode.AUTO -> AgentApprovalMode.AUTO
            SubmissionMode.APPROVAL -> AgentApprovalMode.REQUIRE_CONFIRMATION
        }
    }
}
