package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.toolwindow.approval.ApprovalAction
import com.auracode.assistant.toolwindow.approval.toUiModel
import com.auracode.assistant.toolwindow.composer.ComposerRunningPlanState
import com.auracode.assistant.toolwindow.composer.ComposerRunningPlanStep
import com.auracode.assistant.toolwindow.composer.ComposerRunningPlanStepStatus
import com.auracode.assistant.toolwindow.plan.PlanCompletionPromptUiModel
import com.auracode.assistant.toolwindow.timeline.TimelineMutation
import com.auracode.assistant.toolwindow.toolinput.toSubmissionAnswers
import com.auracode.assistant.toolwindow.toolinput.toUiModel

internal class PlanFlowHandler(
    private val context: ToolWindowCoordinatorContext,
    private val executeApprovedPlanPrompt: String,
) {
    fun handleUnifiedEvent(sessionId: String, event: UnifiedEvent) {
        when (event) {
            is UnifiedEvent.ApprovalRequested -> {
                context.eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.ApprovalRequested(event.request.toUiModel()))
            }

            is UnifiedEvent.ToolUserInputRequested -> {
                val prompt = event.prompt.toUiModel()
                context.eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.ToolUserInputRequested(prompt))
                context.eventDispatcher.dispatchSessionEvent(
                    sessionId,
                    AppEvent.TimelineMutationApplied(
                        mutation = TimelineMutation.UpsertUserInput(
                            sourceId = ToolWindowCoordinatorIds.toolUserInputSourceId(prompt),
                            title = AuraCodeBundle.message("timeline.userInput.title"),
                            body = AuraCodeBundle.message("timeline.userInput.waiting"),
                            status = com.auracode.assistant.protocol.ItemStatus.RUNNING,
                            turnId = prompt.turnId,
                        ),
                    ),
                )
            }

            is UnifiedEvent.ToolUserInputResolved -> {
                context.eventDispatcher.findToolUserInputPrompt(sessionId, event.requestId)?.let { prompt ->
                    context.eventDispatcher.dispatchSessionEvent(
                        sessionId,
                        AppEvent.TimelineMutationApplied(
                            mutation = TimelineMutation.UpsertUserInput(
                                sourceId = ToolWindowCoordinatorIds.toolUserInputSourceId(prompt),
                                title = AuraCodeBundle.message("timeline.userInput.title"),
                                body = AuraCodeBundle.message("timeline.userInput.cancelled"),
                                status = com.auracode.assistant.protocol.ItemStatus.FAILED,
                                turnId = prompt.turnId,
                            ),
                        ),
                    )
                }
                context.eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.ToolUserInputResolved(event.requestId))
            }

            is UnifiedEvent.ThreadStarted -> {
                context.activePlanRunContexts[sessionId] = context.planRunContext(sessionId).apply {
                    threadId = event.threadId
                }
            }

            is UnifiedEvent.TurnStarted -> {
                context.activePlanRunContexts[sessionId] = context.planRunContext(sessionId).apply {
                    remoteTurnId = event.turnId
                    threadId = event.threadId ?: threadId
                }
            }

            is UnifiedEvent.ThreadTokenUsageUpdated -> {
                context.publishSessionSnapshot()
            }

            is UnifiedEvent.TurnDiffUpdated -> {
                context.eventDispatcher.dispatchSessionEvent(
                    sessionId,
                    AppEvent.TurnDiffUpdated(
                        threadId = event.threadId,
                        turnId = event.turnId,
                        diff = event.diff,
                    ),
                )
            }

            is UnifiedEvent.RunningPlanUpdated -> {
                context.activePlanRunContexts[sessionId] = context.planRunContext(sessionId).apply {
                    latestPlanBody = event.body.trim().takeIf { it.isNotBlank() } ?: latestPlanBody
                    threadId = event.threadId ?: threadId
                    remoteTurnId = event.turnId.takeIf { it.isNotBlank() } ?: remoteTurnId
                }
                context.eventDispatcher.dispatchSessionEvent(
                    sessionId,
                    AppEvent.RunningPlanUpdated(
                        plan = ComposerRunningPlanState(
                            threadId = event.threadId ?: context.activePlanRunContexts[sessionId]?.threadId,
                            turnId = event.turnId,
                            explanation = event.explanation,
                            steps = event.steps.map { step ->
                                ComposerRunningPlanStep(
                                    step = step.step,
                                    status = step.status.toComposerRunningPlanStepStatus(),
                                )
                            },
                        ),
                    ),
                )
            }

            is UnifiedEvent.ItemUpdated -> {
                if (event.item.kind == com.auracode.assistant.protocol.ItemKind.PLAN_UPDATE) {
                    context.activePlanRunContexts[sessionId] = context.planRunContext(sessionId).apply {
                        latestPlanBody = event.item.text?.trim()?.takeIf { it.isNotBlank() } ?: latestPlanBody
                    }
                }
            }

            is UnifiedEvent.TurnCompleted -> {
                context.eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.ClearApprovals)
                context.eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.ClearToolUserInputs)
                notifyBackgroundCompletionIfNeeded(sessionId, event)
                handlePlanTurnCompleted(sessionId, event)
            }

            else -> Unit
        }
        when (event) {
            is UnifiedEvent.ApprovalRequested,
            is UnifiedEvent.ToolUserInputRequested,
            is UnifiedEvent.ToolUserInputResolved,
            is UnifiedEvent.TurnCompleted,
            -> context.publishSessionSnapshot()
            else -> Unit
        }
    }

    fun executeApprovedPlan() {
        val prompt = context.composerStore.state.value.planCompletion ?: return
        context.eventHub.publish(AppEvent.PlanCompletionPromptUpdated(prompt = null))
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
        context.eventHub.publish(AppEvent.ApprovalResolved(requestId = current.requestId))
        context.eventHub.publish(
            AppEvent.TimelineMutationApplied(
                mutation = TimelineMutation.UpsertApproval(
                    sourceId = current.itemId,
                    title = current.title,
                    body = buildResolvedApprovalBody(current.body, action),
                    status = when (action) {
                        ApprovalAction.REJECT -> com.auracode.assistant.protocol.ItemStatus.FAILED
                        ApprovalAction.ALLOW,
                        ApprovalAction.ALLOW_FOR_SESSION,
                        -> com.auracode.assistant.protocol.ItemStatus.SUCCESS
                    },
                    turnId = current.turnId,
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
        context.eventHub.publish(AppEvent.ToolUserInputResolved(requestId = prompt.requestId))
        context.eventHub.publish(
            AppEvent.TimelineMutationApplied(
                mutation = TimelineMutation.UpsertUserInput(
                    sourceId = ToolWindowCoordinatorIds.toolUserInputSourceId(prompt),
                    title = AuraCodeBundle.message("timeline.userInput.title"),
                    body = buildResolvedToolUserInputBody(prompt, answers, cancelled),
                    status = when {
                        cancelled || answers.isEmpty() -> com.auracode.assistant.protocol.ItemStatus.FAILED
                        else -> com.auracode.assistant.protocol.ItemStatus.SUCCESS
                    },
                    turnId = prompt.turnId,
                ),
            ),
        )
        if (cancelled) {
            onCancelled()
        }
    }

    fun publishUnifiedEvent(sessionId: String, event: UnifiedEvent, onTurnCompleted: (String) -> Unit) {
        context.eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.UnifiedEventPublished(event))
        com.auracode.assistant.toolwindow.timeline.TimelineNodeMapper.fromUnifiedEvent(event)?.let { mutation ->
            context.eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.TimelineMutationApplied(mutation = mutation))
        }
        handleUnifiedEvent(sessionId, event)
        if (event is UnifiedEvent.TurnCompleted) {
            onTurnCompleted(sessionId)
        }
    }

    private fun handlePlanTurnCompleted(sessionId: String, event: UnifiedEvent.TurnCompleted) {
        val current = context.activePlanRunContexts[sessionId] ?: return
        if (current.remoteTurnId != null && current.remoteTurnId != event.turnId) return
        context.activePlanRunContexts.remove(sessionId)
        context.eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.RunningPlanUpdated(plan = null))
        if (event.outcome != TurnOutcome.SUCCESS) return
        val body = current.latestPlanBody?.trim().orEmpty()
        if (body.isBlank()) return
        context.eventDispatcher.dispatchSessionEvent(
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
        prompt: com.auracode.assistant.toolwindow.toolinput.ToolUserInputPromptUiModel,
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
            context.eventHub.publish(
                AppEvent.TimelineMutationApplied(
                    mutation = com.auracode.assistant.toolwindow.timeline.TimelineNodeMapper.localUserMessageMutation(
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
        )
    }
}
