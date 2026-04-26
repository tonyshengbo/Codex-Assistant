package com.auracode.assistant.session.kernel

/**
 * Reduces normalized session domain events into feature-organized session state.
 */
internal class SessionReducer {
    /** Applies one domain event to the current session state snapshot. */
    fun reduce(
        state: SessionState,
        event: SessionDomainEvent,
    ): SessionState {
        val nextState = when (event) {
            is SessionDomainEvent.ThreadStarted -> state.copy(
                runtime = state.runtime.copy(
                    activeThreadId = event.threadId,
                ),
            )

            is SessionDomainEvent.TurnStarted -> state.copy(
                runtime = state.runtime.copy(
                    activeThreadId = event.threadId ?: state.runtime.activeThreadId,
                    activeTurnId = event.turnId,
                    runStatus = SessionRunStatus.RUNNING,
                    lastOutcome = null,
                    turnStartedAtMs = event.startedAtMs ?: state.runtime.turnStartedAtMs,
                ),
            )

            is SessionDomainEvent.MessageAppended -> state.copy(
                runtime = updateRuntimeForTurnScopedEvent(state.runtime, event.turnId),
                conversation = upsertConversationEntry(
                    conversation = state.conversation,
                    entry = SessionConversationEntry.Message(
                        id = event.messageId,
                        turnId = event.turnId,
                        role = event.role,
                        text = event.text,
                        attachments = event.attachments,
                    ),
                ),
            )

            is SessionDomainEvent.ReasoningUpdated -> state.copy(
                runtime = updateRuntimeForTurnScopedEvent(state.runtime, event.turnId),
                conversation = upsertConversationEntry(
                    conversation = state.conversation,
                    entry = SessionConversationEntry.Reasoning(
                        id = event.itemId,
                        turnId = event.turnId,
                        status = event.status,
                        text = event.text,
                    ),
                ),
            )

            is SessionDomainEvent.CommandUpdated -> state.copy(
                runtime = updateRuntimeForTurnScopedEvent(state.runtime, event.turnId),
                conversation = upsertConversationEntry(
                    conversation = state.conversation,
                    entry = SessionConversationEntry.Command(
                        id = event.itemId,
                        turnId = event.turnId,
                        status = event.status,
                        commandKind = event.commandKind,
                        command = event.command,
                        cwd = event.cwd,
                        outputText = event.outputText,
                    ),
                ),
            )

            is SessionDomainEvent.ToolUpdated -> state.copy(
                runtime = updateRuntimeForTurnScopedEvent(state.runtime, event.turnId),
                conversation = upsertConversationEntry(
                    conversation = state.conversation,
                    entry = SessionConversationEntry.Tool(
                        id = event.itemId,
                        turnId = event.turnId,
                        status = event.status,
                        toolKind = event.toolKind,
                        toolName = event.toolName,
                        summary = event.summary,
                    ),
                ),
            )

            is SessionDomainEvent.FileChangesUpdated -> state.copy(
                runtime = updateRuntimeForTurnScopedEvent(state.runtime, event.turnId),
                conversation = upsertConversationEntry(
                    conversation = state.conversation,
                    entry = SessionConversationEntry.FileChanges(
                        id = event.itemId,
                        turnId = event.turnId,
                        status = event.status,
                        summary = event.summary,
                        changes = event.changes,
                    ),
                ),
                editedFiles = state.editedFiles.copy(
                    filesByPath = state.editedFiles.filesByPath + event.changes.associate { change ->
                        change.path to SessionEditedFileState(
                            path = change.path,
                            changeKind = change.kind,
                        )
                    },
                ),
            )

            is SessionDomainEvent.ApprovalRequested -> state.copy(
                runtime = updateRuntimeForTurnScopedEvent(state.runtime, event.request.turnId),
                conversation = upsertConversationEntry(
                    conversation = state.conversation,
                    entry = SessionConversationEntry.Approval(
                        id = event.request.requestId,
                        turnId = event.request.turnId,
                        request = event.request,
                        status = SessionActivityStatus.RUNNING,
                    ),
                ),
                execution = state.execution.copy(
                    approvalRequests = state.execution.approvalRequests + (event.request.requestId to event.request),
                ),
            )

            is SessionDomainEvent.ApprovalResolved -> {
                val request = state.execution.approvalRequests[event.requestId]
                    ?: state.conversation.entries[event.requestId]
                        ?.let { entry -> (entry as? SessionConversationEntry.Approval)?.request }
                state.copy(
                    conversation = if (request == null) {
                        state.conversation
                    } else {
                        upsertConversationEntry(
                            conversation = state.conversation,
                            entry = SessionConversationEntry.Approval(
                                id = event.requestId,
                                turnId = request.turnId,
                                request = request,
                                status = when (event.decision) {
                                    SessionApprovalDecision.REJECT -> SessionActivityStatus.FAILED
                                    SessionApprovalDecision.ALLOW,
                                    SessionApprovalDecision.ALLOW_FOR_SESSION,
                                    -> SessionActivityStatus.SUCCESS
                                },
                                decision = event.decision,
                            ),
                        )
                    },
                    execution = state.execution.copy(
                        approvalRequests = state.execution.approvalRequests - event.requestId,
                    ),
                )
            }

            is SessionDomainEvent.ToolUserInputRequested -> state.copy(
                runtime = updateRuntimeForTurnScopedEvent(state.runtime, event.request.turnId),
                conversation = upsertConversationEntry(
                    conversation = state.conversation,
                    entry = SessionConversationEntry.ToolUserInput(
                        id = event.request.requestId,
                        turnId = event.request.turnId,
                        request = event.request,
                        status = SessionActivityStatus.RUNNING,
                    ),
                ),
                execution = state.execution.copy(
                    toolUserInputs = state.execution.toolUserInputs + (event.request.requestId to event.request),
                ),
            )

            is SessionDomainEvent.ToolUserInputResolved -> {
                val existingEntry = state.conversation.entries[event.requestId] as? SessionConversationEntry.ToolUserInput
                val request = when {
                    state.execution.toolUserInputs.containsKey(event.requestId) ->
                        state.execution.toolUserInputs[event.requestId]

                    event.responseSummary != null ->
                        existingEntry?.request

                    existingEntry?.status == SessionActivityStatus.RUNNING ->
                        existingEntry.request

                    else -> null
                }
                state.copy(
                    conversation = if (request == null) {
                        state.conversation
                    } else {
                        upsertConversationEntry(
                            conversation = state.conversation,
                            entry = SessionConversationEntry.ToolUserInput(
                                id = event.requestId,
                                turnId = request.turnId,
                                request = request,
                                status = event.status,
                                responseSummary = event.responseSummary,
                            ),
                        )
                    },
                    execution = state.execution.copy(
                        toolUserInputs = state.execution.toolUserInputs - event.requestId,
                    ),
                )
            }

            is SessionDomainEvent.RunningPlanUpdated -> state.copy(
                runtime = updateRuntimeForTurnScopedEvent(state.runtime, event.plan.turnId),
                execution = state.execution.copy(
                    runningPlan = event.plan,
                ),
            )

            is SessionDomainEvent.ErrorAppended -> state.copy(
                runtime = state.runtime.copy(
                    runStatus = if (event.terminal) SessionRunStatus.IDLE else state.runtime.runStatus,
                    lastOutcome = if (event.terminal) SessionTurnOutcome.FAILED else state.runtime.lastOutcome,
                    turnStartedAtMs = if (event.terminal) null else state.runtime.turnStartedAtMs,
                ),
                conversation = appendConversationEntry(
                    conversation = state.conversation,
                    entry = SessionConversationEntry.Error(
                        id = event.itemId,
                        turnId = event.turnId,
                        message = event.message,
                    ),
                ),
            )

            is SessionDomainEvent.TurnCompleted -> state.copy(
                runtime = state.runtime.copy(
                    activeTurnId = event.turnId,
                    runStatus = SessionRunStatus.IDLE,
                    lastOutcome = event.outcome,
                    turnStartedAtMs = null,
                ),
                execution = state.execution.copy(
                    approvalRequests = emptyMap(),
                    toolUserInputs = emptyMap(),
                    runningPlan = null,
                ),
            )
        }
        return nextState.copy(
            history = nextState.history.copy(
                loadedHistory = true,
            ),
        )
    }

    /** Replays a full event sequence from an initial session snapshot. */
    fun reduceAll(
        initialState: SessionState,
        events: Iterable<SessionDomainEvent>,
    ): SessionState {
        return events.fold(initialState) { currentState, event ->
            reduce(currentState, event)
        }
    }

    /** Updates runtime turn metadata for non-terminal events that still belong to an active turn. */
    private fun updateRuntimeForTurnScopedEvent(
        runtime: SessionRuntimeState,
        turnId: String?,
    ): SessionRuntimeState {
        if (turnId.isNullOrBlank()) {
            return runtime
        }
        return runtime.copy(
            activeTurnId = turnId,
        )
    }

    /** Upserts one conversation entry while preserving stable render order. */
    private fun upsertConversationEntry(
        conversation: SessionConversationState,
        entry: SessionConversationEntry,
    ): SessionConversationState {
        val alreadyPresent = conversation.entries.containsKey(entry.id)
        return conversation.copy(
            order = if (alreadyPresent) conversation.order else conversation.order + entry.id,
            entries = conversation.entries + (entry.id to entry),
        )
    }

    /** Appends one new conversation entry without collapsing prior entries that share the same type. */
    private fun appendConversationEntry(
        conversation: SessionConversationState,
        entry: SessionConversationEntry,
    ): SessionConversationState {
        return conversation.copy(
            order = conversation.order + entry.id,
            entries = conversation.entries + (entry.id to entry),
        )
    }
}
