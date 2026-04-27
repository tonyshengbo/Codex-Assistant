package com.auracode.assistant.session.kernel

/**
 * Reduces normalized session domain events into feature-organized session state.
 */
internal class SessionStateReducer {
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
                editedFiles = mergeEditedFiles(
                    editedFiles = state.editedFiles,
                    threadId = state.runtime.activeThreadId,
                    turnId = event.turnId,
                    changes = event.changes,
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
                        id = toolUserInputEntryId(event.request),
                        turnId = event.request.turnId,
                        request = event.request,
                        status = SessionActivityStatus.RUNNING,
                    ),
                ),
                execution = state.execution.copy(
                    toolUserInputs = state.execution.toolUserInputs + (event.request.requestId to event.request),
                    toolUserInputEntryIdsByRequestId = state.execution.toolUserInputEntryIdsByRequestId +
                        (event.request.requestId to toolUserInputEntryId(event.request)),
                ),
            )

            is SessionDomainEvent.ToolUserInputResolved -> {
                val entryId = state.execution.toolUserInputEntryIdsByRequestId[event.requestId] ?: event.requestId
                val existingEntry = state.conversation.entries[entryId] as? SessionConversationEntry.ToolUserInput
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
                                id = entryId,
                                turnId = request.turnId,
                                request = request,
                                status = event.status,
                                responseSummary = event.responseSummary,
                            ),
                        )
                    },
                    execution = state.execution.copy(
                        toolUserInputs = state.execution.toolUserInputs - event.requestId,
                        toolUserInputEntryIdsByRequestId = state.execution.toolUserInputEntryIdsByRequestId - event.requestId,
                    ),
                )
            }

            is SessionDomainEvent.RunningPlanUpdated -> {
                val planTurnId = event.plan.turnId
                val planEntryId = runningPlanEntryId(planTurnId)
                state.copy(
                    runtime = updateRuntimeForTurnScopedEvent(state.runtime, planTurnId),
                    conversation = if (event.plan.presentation == SessionRunningPlanPresentation.TIMELINE) {
                        upsertConversationEntry(
                            conversation = state.conversation,
                            entry = SessionConversationEntry.Plan(
                                id = planEntryId,
                                turnId = planTurnId,
                                status = SessionActivityStatus.RUNNING,
                                body = event.plan.body,
                            ),
                        )
                    } else {
                        state.conversation
                    },
                    execution = state.execution.copy(
                        runningPlan = event.plan,
                    ),
                )
            }

            is SessionDomainEvent.EditedFilesTracked -> state.copy(
                editedFiles = mergeEditedFiles(
                    editedFiles = state.editedFiles,
                    threadId = event.threadId,
                    turnId = event.turnId,
                    changes = event.changes,
                ),
            )

            is SessionDomainEvent.UsageUpdated -> state.copy(
                usage = state.usage.copy(
                    model = event.model?.trim()?.takeIf { it.isNotBlank() } ?: state.usage.model,
                    contextWindow = event.contextWindow,
                    inputTokens = event.inputTokens,
                    cachedInputTokens = event.cachedInputTokens,
                    outputTokens = event.outputTokens,
                ),
            )

            is SessionDomainEvent.SubagentsUpdated -> state.copy(
                runtime = state.runtime.copy(
                    activeThreadId = event.threadId ?: state.runtime.activeThreadId,
                    activeTurnId = event.turnId ?: state.runtime.activeTurnId,
                ),
                subagents = state.subagents.copy(
                    agents = event.agents,
                ),
            )

            is SessionDomainEvent.EngineSwitched -> state.copy(
                conversation = appendConversationEntry(
                    conversation = state.conversation,
                    entry = SessionConversationEntry.EngineSwitched(
                        id = event.itemId,
                        targetEngineLabel = event.targetEngineLabel,
                        timestamp = event.timestamp,
                    ),
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

            is SessionDomainEvent.TurnCompleted -> {
                val updatedConversation = finalizeTurnPlanEntry(
                    conversation = state.conversation,
                    turnId = event.turnId,
                    outcome = event.outcome,
                )
                state.copy(
                    runtime = state.runtime.copy(
                        activeTurnId = event.turnId,
                        runStatus = SessionRunStatus.IDLE,
                        lastOutcome = event.outcome,
                        turnStartedAtMs = null,
                    ),
                    conversation = updatedConversation,
                    execution = state.execution.copy(
                        approvalRequests = emptyMap(),
                        toolUserInputs = emptyMap(),
                        toolUserInputEntryIdsByRequestId = emptyMap(),
                        runningPlan = null,
                    ),
                )
            }
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

    /** Builds the stable conversation entry id for one tool user-input request. */
    private fun toolUserInputEntryId(request: SessionToolUserInputRequest): String {
        val scopedTurnId = request.turnId?.takeIf { it.isNotBlank() }
        val scopedItemId = request.itemId.takeIf { it.isNotBlank() }
        return when {
            scopedTurnId != null && scopedItemId != null -> "tool-input:$scopedTurnId:$scopedItemId"
            scopedTurnId != null -> "tool-input:$scopedTurnId:${request.requestId}"
            else -> "tool-input:${request.threadId}:${request.requestId}"
        }
    }

    /** Builds the stable conversation entry id for one plan timeline node. */
    private fun runningPlanEntryId(turnId: String?): String {
        return listOfNotNull("plan", turnId?.takeIf { it.isNotBlank() }).joinToString(":")
    }

    /** Finalizes one running plan entry when the owning turn completes. */
    private fun finalizeTurnPlanEntry(
        conversation: SessionConversationState,
        turnId: String,
        outcome: SessionTurnOutcome,
    ): SessionConversationState {
        val entryId = runningPlanEntryId(turnId)
        val existing = conversation.entries[entryId] as? SessionConversationEntry.Plan ?: return conversation
        if (existing.status != SessionActivityStatus.RUNNING) return conversation
        return upsertConversationEntry(
            conversation = conversation,
            entry = existing.copy(
                status = when (outcome) {
                    SessionTurnOutcome.SUCCESS -> SessionActivityStatus.SUCCESS
                    SessionTurnOutcome.FAILED -> SessionActivityStatus.FAILED
                    SessionTurnOutcome.CANCELLED -> SessionActivityStatus.SKIPPED
                },
            ),
        )
    }

    /** Merges structured edited-file details into the session submission slice by file path. */
    private fun mergeEditedFiles(
        editedFiles: SessionEditedFilesState,
        threadId: String?,
        turnId: String?,
        changes: List<SessionFileChange>,
    ): SessionEditedFilesState {
        if (changes.isEmpty()) return editedFiles
        val nextFilesByPath = editedFiles.filesByPath.toMutableMap()
        changes.forEach { change ->
            val previous = nextFilesByPath[change.path]
            nextFilesByPath[change.path] = SessionEditedFileState(
                path = change.path,
                changeKind = change.kind.ifBlank { previous?.changeKind ?: "update" },
                displayName = change.displayName.ifBlank { previous?.displayName ?: change.path },
                threadId = threadId ?: previous?.threadId,
                turnId = turnId ?: previous?.turnId,
                addedLines = change.addedLines ?: previous?.addedLines,
                deletedLines = change.deletedLines ?: previous?.deletedLines,
                unifiedDiff = change.unifiedDiff ?: previous?.unifiedDiff,
                oldContent = change.oldContent ?: previous?.oldContent,
                newContent = change.newContent ?: previous?.newContent,
                lastUpdatedAt = change.updatedAtMs ?: previous?.lastUpdatedAt ?: 0L,
            )
        }
        return editedFiles.copy(filesByPath = nextFilesByPath)
    }
}
