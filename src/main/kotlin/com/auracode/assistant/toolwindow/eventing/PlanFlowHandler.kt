package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.session.kernel.SessionActivityStatus
import com.auracode.assistant.session.kernel.SessionApprovalDecision
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.toolwindow.execution.ApprovalAction
import com.auracode.assistant.toolwindow.submission.ComposerRunningPlanState
import com.auracode.assistant.toolwindow.submission.ComposerRunningPlanStep
import com.auracode.assistant.toolwindow.submission.ComposerRunningPlanStepStatus
import com.auracode.assistant.toolwindow.execution.PlanCompletionPromptUiModel
import com.auracode.assistant.toolwindow.conversation.TimelineMutation
import com.auracode.assistant.toolwindow.execution.toSubmissionAnswers

internal class PlanFlowHandler(
    private val context: ToolWindowCoordinatorContext,
    private val executeApprovedPlanPrompt: String,
) {
    fun handleUnifiedEvent(sessionId: String, event: UnifiedEvent) {
        when (event) {
            is UnifiedEvent.ThreadStarted -> {
                context.activePlanRunContexts[sessionId]?.apply {
                    threadId = event.threadId
                }
            }

            is UnifiedEvent.TurnStarted -> {
                context.activePlanRunContexts[sessionId]?.apply {
                    remoteTurnId = event.turnId
                    threadId = event.threadId ?: threadId
                }
            }

            is UnifiedEvent.ThreadTokenUsageUpdated -> {
                context.publishSessionSnapshot()
            }

            is UnifiedEvent.TurnDiffUpdated -> {
                context.dispatchSessionEvent(
                    sessionId,
                    AppEvent.TurnDiffUpdated(
                        threadId = event.threadId,
                        turnId = event.turnId,
                        diff = event.diff,
                    ),
                )
            }

            is UnifiedEvent.RunningPlanUpdated -> {
                context.activePlanRunContexts[sessionId]?.apply {
                    latestPlanBody = event.body.trim().takeIf { it.isNotBlank() } ?: latestPlanBody
                    threadId = event.threadId ?: threadId
                    remoteTurnId = event.turnId.takeIf { it.isNotBlank() } ?: remoteTurnId
                }
            }

            is UnifiedEvent.ItemUpdated -> {
                val planContext = context.activePlanRunContexts[sessionId]
                if (event.item.kind == com.auracode.assistant.protocol.ItemKind.PLAN_UPDATE) {
                    // Codex 风格：通过专用 PLAN_UPDATE 类型传递计划内容
                    planContext?.apply {
                        latestPlanBody = event.item.text?.trim()?.takeIf { it.isNotBlank() } ?: latestPlanBody
                    }
                }
            }

            is UnifiedEvent.TurnCompleted -> {
                context.dispatchSessionEvent(sessionId, AppEvent.ClearApprovals)
                context.dispatchSessionEvent(sessionId, AppEvent.ClearToolUserInputs)
                notifyBackgroundCompletionIfNeeded(sessionId, event)
                if (!handlePlanTurnCompleted(sessionId, event)) {
                    clearRunningPlanIfNeeded(sessionId, event)
                }
            }

            else -> Unit
        }
        when (event) {
            is UnifiedEvent.TurnCompleted,
            -> context.publishSessionSnapshot()
            else -> Unit
        }
    }

    fun executeApprovedPlan() {
        val prompt = context.composerStore.state.value.planCompletion ?: return
        val sessionId = context.activeSessionId()
        context.activePlanRunContexts.remove(sessionId)
        context.dispatchSessionEvent(sessionId, AppEvent.RunningPlanUpdated(plan = null))
        context.dispatchSessionEvent(sessionId, AppEvent.PlanCompletionPromptUpdated(prompt = null))
        context.eventHub.publishUiIntent(UiIntent.SelectMode(prompt.preferredExecutionMode))
        if (context.composerStore.state.value.planEnabled) {
            context.eventHub.publishUiIntent(UiIntent.TogglePlanMode)
        }
        startProgrammaticTurn(
            prompt = executeApprovedPlanPrompt,
            approvalMode = prompt.preferredExecutionMode.toApprovalMode(),
        )
    }

    fun submitPlanRevision() {
        val planCompletion = context.composerStore.state.value.planCompletion ?: return
        val revision = planCompletion.revisionDraft.trim()
        if (revision.isBlank()) return
        if (!context.composerStore.state.value.planEnabled) {
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

    /** 处理 plan mode turn 结束逻辑；处理成功时返回 true。 */
    private fun handlePlanTurnCompleted(sessionId: String, event: UnifiedEvent.TurnCompleted): Boolean {
        val current = context.activePlanRunContexts[sessionId] ?: return false
        if (current.remoteTurnId != null && current.remoteTurnId != event.turnId) return false
        context.activePlanRunContexts.remove(sessionId)
        context.dispatchSessionEvent(sessionId, AppEvent.RunningPlanUpdated(plan = null))
        if (event.outcome != TurnOutcome.SUCCESS) return true
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

    /** 清理普通 turn 顶部运行计划，避免 Claude TodoWrite 在完成后残留。 */
    private fun clearRunningPlanIfNeeded(sessionId: String, event: UnifiedEvent.TurnCompleted) {
        val runningPlan = context.composerStore.state.value.runningPlan ?: return
        if (event.turnId.isNotBlank() && runningPlan.turnId != event.turnId) return
        context.dispatchSessionEvent(sessionId, AppEvent.RunningPlanUpdated(plan = null))
    }

    private fun notifyBackgroundCompletionIfNeeded(sessionId: String, event: UnifiedEvent.TurnCompleted) {
        val sessionTitle = context.chatService.listSessions()
            .firstOrNull { it.id == sessionId }
            ?.title
            .orEmpty()
        context.completionNotificationService?.notifyIfNeeded(
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            outcome = event.outcome,
        )
    }

    private fun buildResolvedApprovalBody(body: String, action: ApprovalAction): String {
        val decisionLabel = when (action) {
            ApprovalAction.ALLOW -> "Allowed"
            ApprovalAction.REJECT -> "Rejected"
            ApprovalAction.ALLOW_FOR_SESSION -> "Remembered for session"
        }
        return listOf(body.trim().takeIf { it.isNotBlank() }, decisionLabel).joinToString("\n\n")
    }

    private fun buildResolvedToolUserInputBody(
        prompt: com.auracode.assistant.toolwindow.execution.ToolUserInputPromptUiModel,
        answers: Map<String, com.auracode.assistant.protocol.UnifiedToolUserInputAnswerDraft>,
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

    private fun String.toComposerRunningPlanStepStatus(): ComposerRunningPlanStepStatus {
        return when (trim().lowercase()) {
            "completed", "complete", "success", "succeeded", "done" -> ComposerRunningPlanStepStatus.COMPLETED
            "inprogress", "in_progress", "running", "active" -> ComposerRunningPlanStepStatus.IN_PROGRESS
            else -> ComposerRunningPlanStepStatus.PENDING
        }
    }

    private fun ComposerMode.toApprovalMode(): AgentApprovalMode {
        return when (this) {
            ComposerMode.AUTO -> AgentApprovalMode.AUTO
            ComposerMode.APPROVAL -> AgentApprovalMode.REQUIRE_CONFIRMATION
        }
    }

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
            model = context.composerStore.state.value.selectedModel,
            reasoningEffort = context.composerStore.state.value.selectedReasoning.effort,
            prompt = prompt,
            systemInstructions = emptyList(),
            localTurnId = localTurnId,
            contextFiles = emptyList(),
            imageAttachments = emptyList(),
            fileAttachments = emptyList(),
            approvalMode = approvalMode,
            collaborationMode = collaborationMode,
            onTurnPersisted = { context.publishSessionSnapshot() },
            onUnifiedEvent = { event -> context.publishUnifiedEvent(sessionId, event) },
            onRunStateChanged = { context.publishSessionSnapshot() },
        )
    }
}
