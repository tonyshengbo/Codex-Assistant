package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.session.kernel.SessionActivityStatus
import com.auracode.assistant.session.kernel.SessionApprovalDecision
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.session.kernel.SessionRunningPlanPresentation
import com.auracode.assistant.session.kernel.SessionTurnOutcome
import com.auracode.assistant.toolwindow.execution.ApprovalAction
import com.auracode.assistant.toolwindow.execution.PlanCompletionPromptUiModel
import com.auracode.assistant.toolwindow.execution.toSubmissionAnswers
import com.auracode.assistant.toolwindow.submission.SubmissionRunningPlanState
import com.auracode.assistant.toolwindow.submission.SubmissionRunningPlanStep
import com.auracode.assistant.toolwindow.submission.SubmissionRunningPlanStepStatus

internal class PlanFlowHandler(
    private val context: ToolWindowCoordinatorContext,
    private val executeApprovedPlanPrompt: String,
) {
    /** Applies domain-level turn/progress events needed by the plan-mode control flow. */
    fun handleSessionDomainEvents(sessionId: String, events: List<SessionDomainEvent>) {
        events.forEach { event ->
            when (event) {
                is SessionDomainEvent.ThreadStarted -> {
                    context.activePlanRunContexts[sessionId]?.threadId = event.threadId
                }

                is SessionDomainEvent.TurnStarted -> {
                    context.activePlanRunContexts[sessionId]?.apply {
                        remoteTurnId = event.turnId
                        threadId = event.threadId ?: threadId
                    }
                }

                is SessionDomainEvent.UsageUpdated -> {
                    context.publishSessionSnapshot()
                }

                is SessionDomainEvent.RunningPlanUpdated -> {
                    context.activePlanRunContexts[sessionId]?.apply {
                        latestPlanBody = event.plan.body.trim().takeIf { it.isNotBlank() } ?: latestPlanBody
                        remoteTurnId = event.plan.turnId?.takeIf { it.isNotBlank() } ?: remoteTurnId
                    }
                    if (event.plan.presentation == SessionRunningPlanPresentation.SUBMISSION_PANEL) {
                        context.dispatchSessionEvent(
                            sessionId,
                            AppEvent.RunningPlanUpdated(plan = event.plan.toSubmissionRunningPlanState()),
                        )
                    }
                }

                is SessionDomainEvent.TurnCompleted -> {
                    context.dispatchSessionEvent(sessionId, AppEvent.ClearApprovals)
                    context.dispatchSessionEvent(sessionId, AppEvent.ClearToolUserInputs)
                    notifyBackgroundCompletionIfNeeded(sessionId, event)
                    if (!handlePlanTurnCompleted(sessionId, event)) {
                        clearRunningPlanIfNeeded(sessionId, event)
                    }
                    context.publishSessionSnapshot()
                }

                else -> Unit
            }
        }
    }

    fun executeApprovedPlan() {
        val prompt = context.submissionStore.state.value.planCompletion ?: return
        val sessionId = context.activeSessionId()
        context.activePlanRunContexts.remove(sessionId)
        context.dispatchSessionEvent(sessionId, AppEvent.RunningPlanUpdated(plan = null))
        context.dispatchSessionEvent(sessionId, AppEvent.PlanCompletionPromptUpdated(prompt = null))
        context.eventHub.publishUiIntent(UiIntent.SelectMode(prompt.preferredExecutionMode))
        if (context.submissionStore.state.value.planEnabled) {
            context.eventHub.publishUiIntent(UiIntent.TogglePlanMode)
        }
        startProgrammaticTurn(
            prompt = executeApprovedPlanPrompt,
            approvalMode = prompt.preferredExecutionMode.toApprovalMode(),
        )
    }

    fun submitPlanRevision() {
        val planCompletion = context.submissionStore.state.value.planCompletion ?: return
        val revision = planCompletion.revisionDraft.trim()
        if (revision.isBlank()) return
        if (!context.submissionStore.state.value.planEnabled) {
            context.eventHub.publishUiIntent(UiIntent.TogglePlanMode)
        }
        context.activePlanRunContexts[context.activeSessionId()] = ActivePlanRunContext(
            localTurnId = ToolWindowCoordinatorIds.newLocalTurnId(),
            preferredExecutionMode = planCompletion.preferredExecutionMode,
            threadId = planCompletion.threadId,
        )
        context.eventHub.publish(AppEvent.PlanCompletionPromptUpdated(prompt = null))
        startProgrammaticTurn(
            prompt = revision,
            approvalMode = planCompletion.preferredExecutionMode.toApprovalMode(),
            collaborationMode = AgentCollaborationMode.PLAN,
            localTurnId = context.activePlanRunContexts[context.activeSessionId()]?.localTurnId.orEmpty(),
        )
    }

    fun requestPlanRevision() {
        context.eventHub.publish(AppEvent.PlanCompletionPromptUpdated(prompt = null))
    }

    fun dismissPlanCompletionPrompt() {
        context.eventHub.publish(AppEvent.PlanCompletionPromptUpdated(prompt = null))
    }

    fun resetPlanFlowState() {
        context.activePlanRunContexts.remove(context.activeSessionId())
        context.eventHub.publish(AppEvent.ClearToolUserInputs)
        context.eventHub.publish(AppEvent.RunningPlanUpdated(plan = null))
        context.eventHub.publish(AppEvent.PlanCompletionPromptUpdated(prompt = null))
    }

    fun submitApprovalDecision(explicitAction: ApprovalAction?) {
        val current = context.approvalStore.state.value.current ?: return
        val action = explicitAction ?: context.approvalStore.state.value.selectedAction
        if (!context.chatService.submitApprovalDecision(current.requestId, action)) return
        context.publishSessionSnapshot()
        context.applySessionDomainEvents(
            context.activeSessionId(),
            listOf(
                SessionDomainEvent.ApprovalResolved(
                    requestId = current.requestId,
                    decision = when (action) {
                        ApprovalAction.ALLOW -> SessionApprovalDecision.ALLOW
                        ApprovalAction.REJECT -> SessionApprovalDecision.REJECT
                        ApprovalAction.ALLOW_FOR_SESSION -> SessionApprovalDecision.ALLOW_FOR_SESSION
                    },
                ),
            ),
        )
    }

    fun submitToolUserInputPrompt(cancelled: Boolean, onCancelled: () -> Unit) {
        val prompt = context.toolUserInputPromptStore.state.value.current ?: return
        val answers = if (cancelled) {
            emptyMap()
        } else {
            val submission = context.toolUserInputPromptStore.state.value.toSubmissionAnswers()
            if (submission.isEmpty()) return
            submission
        }
        if (!context.chatService.submitToolUserInput(prompt.requestId, answers)) return
        context.publishSessionSnapshot()
        context.applySessionDomainEvents(
            context.activeSessionId(),
            listOf(
                SessionDomainEvent.ToolUserInputResolved(
                    requestId = prompt.requestId,
                    status = when {
                        cancelled || answers.isEmpty() -> SessionActivityStatus.FAILED
                        else -> SessionActivityStatus.SUCCESS
                    },
                    responseSummary = buildResolvedToolUserInputBody(prompt, answers, cancelled),
                ),
            ),
        )
        if (cancelled) {
            onCancelled()
        }
    }

    /** Handles one finished plan turn and opens the completion prompt when the plan succeeded. */
    private fun handlePlanTurnCompleted(sessionId: String, event: SessionDomainEvent.TurnCompleted): Boolean {
        val current = context.activePlanRunContexts[sessionId] ?: return false
        if (current.remoteTurnId != null && current.remoteTurnId != event.turnId) return false
        context.activePlanRunContexts.remove(sessionId)
        context.dispatchSessionEvent(sessionId, AppEvent.RunningPlanUpdated(plan = null))
        if (event.outcome != SessionTurnOutcome.SUCCESS) return true
        val body = current.latestPlanBody?.trim().orEmpty()
        if (body.isBlank()) return true
        context.dispatchSessionEvent(
            sessionId,
            AppEvent.PlanCompletionPromptUpdated(
                prompt = PlanCompletionPromptUiModel(
                    turnId = event.turnId,
                    threadId = current.threadId,
                    body = body,
                    preferredExecutionMode = current.preferredExecutionMode,
                ),
            ),
        )
        return true
    }

    /** Clears transient running-plan UI after a normal turn completes. */
    private fun clearRunningPlanIfNeeded(sessionId: String, event: SessionDomainEvent.TurnCompleted) {
        val runningPlan = context.submissionStore.state.value.runningPlan ?: return
        if (event.turnId.isNotBlank() && runningPlan.turnId != event.turnId) return
        context.dispatchSessionEvent(sessionId, AppEvent.RunningPlanUpdated(plan = null))
    }

    /** Shows background completion notifications using the existing notification service contract. */
    private fun notifyBackgroundCompletionIfNeeded(sessionId: String, event: SessionDomainEvent.TurnCompleted) {
        val sessionTitle = context.chatService.listSessions()
            .firstOrNull { it.id == sessionId }
            ?.title
            .orEmpty()
        context.completionNotificationService?.notifyIfNeeded(
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            outcome = when (event.outcome) {
                SessionTurnOutcome.SUCCESS -> TurnOutcome.SUCCESS
                SessionTurnOutcome.FAILED -> TurnOutcome.FAILED
                SessionTurnOutcome.CANCELLED -> TurnOutcome.CANCELLED
            },
        )
    }

    /** Builds the replay summary shown inside the user-input timeline node after submission. */
    private fun buildResolvedToolUserInputBody(
        prompt: com.auracode.assistant.toolwindow.execution.ToolUserInputPromptUiModel,
        answers: Map<String, com.auracode.assistant.protocol.ProviderToolUserInputAnswerDraft>,
        cancelled: Boolean,
    ): String {
        if (cancelled || answers.isEmpty()) {
            return AuraCodeBundle.message("timeline.userInput.cancelled")
        }
        return prompt.questions.joinToString("\n\n") { question ->
            val title = question.header.ifBlank { question.question }
            val value = when {
                question.isSecret && answers.containsKey(question.id) -> AuraCodeBundle.message("timeline.userInput.secretProvided")
                else -> answers[question.id]?.answers?.joinToString(", ").orEmpty()
                    .ifBlank { AuraCodeBundle.message("timeline.userInput.noAnswer") }
            }
            "$title\n$value"
        }
    }

    /** Maps composer execution mode into the provider approval mode. */
    private fun SubmissionMode.toApprovalMode(): AgentApprovalMode {
        return when (this) {
            SubmissionMode.AUTO -> AgentApprovalMode.AUTO
            SubmissionMode.APPROVAL -> AgentApprovalMode.REQUIRE_CONFIRMATION
        }
    }

    /** Maps one session running plan into the submission-area running-plan state. */
    private fun com.auracode.assistant.session.kernel.SessionRunningPlan.toSubmissionRunningPlanState(): SubmissionRunningPlanState {
        return SubmissionRunningPlanState(
            threadId = context.activePlanRunContexts[context.activeSessionId()]?.threadId,
            turnId = turnId.orEmpty(),
            explanation = explanation,
            steps = steps.map { step ->
                SubmissionRunningPlanStep(
                    step = step.step,
                    status = when (step.status.trim().lowercase()) {
                        "completed" -> SubmissionRunningPlanStepStatus.COMPLETED
                        "inprogress", "in_progress", "running", "active" -> SubmissionRunningPlanStepStatus.IN_PROGRESS
                        else -> SubmissionRunningPlanStepStatus.PENDING
                    },
                )
            },
        )
    }

    /** Starts one programmatic follow-up turn while keeping the session kernel in sync. */
    private fun startProgrammaticTurn(
        prompt: String,
        approvalMode: AgentApprovalMode,
        collaborationMode: AgentCollaborationMode = AgentCollaborationMode.DEFAULT,
        localTurnId: String = ToolWindowCoordinatorIds.newLocalTurnId(),
    ) {
        val sessionId = context.activeSessionId()
        context.chatService.recordUserMessage(
            sessionId = sessionId,
            prompt = prompt,
            turnId = localTurnId,
            attachments = emptyList(),
        )?.let { message ->
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
            engineId = context.chatService.defaultEngineId(),
            model = context.submissionStore.state.value.selectedModel,
            reasoningEffort = context.submissionStore.state.value.selectedReasoning.effort,
            prompt = prompt,
            systemInstructions = emptyList(),
            localTurnId = localTurnId,
            contextFiles = emptyList(),
            imageAttachments = emptyList(),
            fileAttachments = emptyList(),
            approvalMode = approvalMode,
            collaborationMode = collaborationMode,
            onTurnPersisted = { context.publishSessionSnapshot() },
            onSessionDomainEvents = { events -> context.applySessionDomainEvents(sessionId, events) },
            onRunStateChanged = { context.publishSessionSnapshot() },
        )
    }
}
