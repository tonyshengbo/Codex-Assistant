package com.auracode.assistant.toolwindow.conversation

import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome

internal class TimelineNodeReducer {
    var state: TimelineAreaState = TimelineAreaState()
        private set
    private var activeThreadId: String? = null
    private var activeTurnId: String? = null
    private var openTurnId: String? = null
    private var syntheticTurnCount: Int = 0
    private var errorNodeCount: Int = 0

    fun accept(mutation: TimelineMutation) {
        state = when (mutation) {
            is TimelineMutation.ThreadStarted -> {
                activeThreadId = mutation.threadId
                state
            }

            is TimelineMutation.TurnStarted -> acceptTurnStarted(mutation)
            is TimelineMutation.UpsertMessage -> acceptMessage(mutation)
            is TimelineMutation.UpsertReasoning -> acceptReasoning(mutation)
            is TimelineMutation.UpsertToolCall -> acceptToolCall(mutation)
            is TimelineMutation.UpsertCommand -> acceptCommand(mutation)
            is TimelineMutation.UpsertFileChange -> acceptFileChange(mutation)
            is TimelineMutation.UpsertApproval -> acceptApproval(mutation)
            is TimelineMutation.UpsertContextCompaction -> acceptContextCompaction(mutation)
            is TimelineMutation.UpsertPlan -> acceptPlan(mutation)
            is TimelineMutation.UpsertUserInput -> acceptUserInput(mutation)
            is TimelineMutation.UpsertUnknownActivity -> acceptUnknownActivity(mutation)
            is TimelineMutation.TurnCompleted -> acceptTurnCompleted(mutation)
            is TimelineMutation.AppendError -> acceptAppendedError(mutation)
            is TimelineMutation.AppendEngineSwitched -> acceptEngineSwitched(mutation)
        }.withLiveRender()
    }

    fun replaceHistory(
        nodes: List<TimelineNode>,
        oldestCursor: String?,
        hasOlder: Boolean,
    ) {
        state = state.copy(
            nodes = decorateHistoryNodes(nodes = nodes, hasOlder = hasOlder, isLoadingOlder = false),
            oldestCursor = oldestCursor,
            hasOlder = hasOlder,
            isLoadingOlder = false,
            renderVersion = state.renderVersion + 1,
            renderCause = TimelineRenderCause.HISTORY_RESET,
            prependedCount = 0,
        )
    }

    fun prependHistory(
        nodes: List<TimelineNode>,
        oldestCursor: String?,
        hasOlder: Boolean,
    ) {
        val existing = state.nodes.filterNot { it is TimelineNode.LoadMoreNode }
        state = state.copy(
            nodes = decorateHistoryNodes(
                nodes = nodes.filterNot { it is TimelineNode.LoadMoreNode } + existing,
                hasOlder = hasOlder,
                isLoadingOlder = false,
            ),
            oldestCursor = oldestCursor,
            hasOlder = hasOlder,
            isLoadingOlder = false,
            renderVersion = state.renderVersion + 1,
            renderCause = TimelineRenderCause.HISTORY_PREPEND,
            prependedCount = nodes.count { it !is TimelineNode.LoadMoreNode },
        )
    }

    fun setLoadingOlder(loading: Boolean) {
        state = state.copy(
            nodes = decorateHistoryNodes(
                nodes = state.nodes.filterNot { it is TimelineNode.LoadMoreNode },
                hasOlder = state.hasOlder,
                isLoadingOlder = loading,
            ),
            isLoadingOlder = loading,
        )
    }

    fun markTurnPending(turnId: String? = null) {
        val normalizedTurnId = turnId?.trim().orEmpty()
        if (normalizedTurnId.isNotBlank()) {
            activeTurnId = normalizedTurnId
            openTurnId = normalizedTurnId
        }
        state = state.copy(
            isRunning = true,
            latestError = null,
            renderVersion = state.renderVersion + 1,
            renderCause = TimelineRenderCause.LIVE_UPDATE,
            prependedCount = 0,
            promptScrollRequestVersion = state.promptScrollRequestVersion + 1,
        )
    }

    fun reset() {
        activeThreadId = null
        activeTurnId = null
        openTurnId = null
        syntheticTurnCount = 0
        errorNodeCount = 0
        state = TimelineAreaState()
    }

    fun restoreState(restoredState: TimelineAreaState) {
        state = restoredState
        activeThreadId = null
        activeTurnId = restoredState.nodes.asReversed().firstNotNullOfOrNull { node ->
            node.turnId?.takeIf { restoredState.isRunning || node.status == ItemStatus.RUNNING }
        }
        openTurnId = activeTurnId
        syntheticTurnCount = 0
        errorNodeCount = restoredState.nodes.count { it is TimelineNode.ErrorNode }
    }

    private fun acceptTurnStarted(mutation: TimelineMutation.TurnStarted): TimelineAreaState {
        if (!mutation.threadId.isNullOrBlank()) {
            activeThreadId = mutation.threadId
        }
        val promotedTurnId = activeTurnId?.takeIf(::isSyntheticTurnId)
        val nextNodes = if (promotedTurnId != null) {
            state.nodes.map { node -> node.replaceTurnId(promotedTurnId, mutation.turnId) }
        } else {
            state.nodes
        }
        activeTurnId = mutation.turnId
        openTurnId = mutation.turnId
        return state.copy(
            nodes = nextNodes,
            isRunning = true,
            latestError = null,
        )
    }

    private fun acceptMessage(mutation: TimelineMutation.UpsertMessage): TimelineAreaState {
        return if (mutation.role == MessageRole.ASSISTANT) {
            acceptAssistantAnswer(mutation)
        } else {
            acceptStandaloneMessage(mutation)
        }
    }

    private fun acceptStandaloneMessage(mutation: TimelineMutation.UpsertMessage): TimelineAreaState {
        val turnId = resolveTurnId(mutation.turnId)
        val existing = state.nodes.filterIsInstance<TimelineNode.MessageNode>().firstOrNull {
            it.sourceId == mutation.sourceId && it.turnId == turnId
        }
        val node = TimelineNode.MessageNode(
            id = messageNodeId(turnId, mutation.sourceId),
            sourceId = mutation.sourceId,
            role = mutation.role,
            text = mutation.text,
            status = mutation.status,
            timestamp = mutation.timestamp,
            turnId = turnId,
            cursor = mutation.cursor,
            attachments = if (mutation.attachments.isNotEmpty()) mutation.attachments else existing?.attachments.orEmpty(),
        )
        val nextNodes = upsertNode(state.nodes, node, turnId)
        return state.copy(
            nodes = nextNodes,
            isRunning = hasRunningState(nextNodes),
            latestError = null,
        )
    }

    private fun acceptAssistantAnswer(mutation: TimelineMutation.UpsertMessage): TimelineAreaState {
        val turnId = resolveTurnId(mutation.turnId)
        val existing = state.nodes.filterIsInstance<TimelineNode.MessageNode>().firstOrNull {
            it.sourceId == mutation.sourceId && it.turnId == turnId
        }
        val node = TimelineNode.MessageNode(
            id = messageNodeId(turnId, mutation.sourceId),
            sourceId = mutation.sourceId,
            role = mutation.role,
            text = mutation.text,
            status = mutation.status,
            timestamp = mutation.timestamp,
            turnId = turnId,
            cursor = mutation.cursor,
            attachments = if (mutation.attachments.isNotEmpty()) mutation.attachments else existing?.attachments.orEmpty(),
        )
        val nextNodes = upsertAssistantAnswer(state.nodes, node, turnId)
        return state.copy(
            nodes = nextNodes,
            isRunning = hasRunningState(nextNodes),
            latestError = null,
        )
    }

    private fun acceptReasoning(mutation: TimelineMutation.UpsertReasoning): TimelineAreaState {
        return acceptActivityNode(mutation.turnId, mutation.status) { turnId ->
            TimelineNode.ReasoningNode(
                id = activityNodeId("reasoning", turnId, mutation.sourceId),
                sourceId = mutation.sourceId,
                body = mutation.body,
                status = mutation.status,
                turnId = turnId,
            )
        }
    }

    private fun acceptToolCall(mutation: TimelineMutation.UpsertToolCall): TimelineAreaState {
        return acceptActivityNode(mutation.turnId, mutation.status) { turnId ->
            TimelineNode.ToolCallNode(
                id = activityNodeId("tool", turnId, mutation.sourceId),
                sourceId = mutation.sourceId,
                title = mutation.title,
                titleTargetLabel = mutation.titleTargetLabel,
                titleTargetPath = mutation.titleTargetPath,
                body = mutation.body,
                collapsedSummary = summarizeTextProcess(
                    title = mutation.title,
                    body = mutation.body,
                    status = mutation.status,
                    errorSummary = null,
                ),
                status = mutation.status,
                turnId = turnId,
            )
        }
    }

    private fun acceptCommand(mutation: TimelineMutation.UpsertCommand): TimelineAreaState {
        return acceptActivityNode(mutation.turnId, mutation.status) { turnId ->
            val commandText = mutation.commandText ?: mutation.body.firstMeaningfulLine()
            val outputText = mutation.body
                .takeIf { it.isNotBlank() && it.trim() != commandText?.trim() }
            TimelineNode.CommandNode(
                id = activityNodeId("command", turnId, mutation.sourceId),
                sourceId = mutation.sourceId,
                title = mutation.title,
                titleTargetLabel = mutation.titleTargetLabel,
                titleTargetPath = mutation.titleTargetPath,
                body = mutation.body,
                commandText = commandText,
                outputText = outputText,
                collapsedSummary = summarizeCommandProcess(
                    status = mutation.status,
                    errorSummary = null,
                ),
                status = mutation.status,
                turnId = turnId,
            )
        }
    }

    private fun acceptFileChange(mutation: TimelineMutation.UpsertFileChange): TimelineAreaState {
        val turnId = resolveTurnId(mutation.turnId)
        val fileNodes = mutation.changes.mapIndexed { index, change ->
            val scopedSourceId = change.sourceScopedId.ifBlank { "${mutation.sourceId}:$index" }
            TimelineNode.FileChangeNode(
                id = activityNodeId("diff", turnId, scopedSourceId),
                sourceId = scopedSourceId,
                title = fileChangeNodeTitle(change),
                titleTargetLabel = change.displayName,
                titleTargetPath = change.path,
                changes = listOf(change),
                collapsedSummary = summarizeFileChanges(
                    changes = listOf(change),
                    status = mutation.status,
                    errorSummary = null,
                ),
                status = mutation.status,
                turnId = turnId,
            )
        }
        if (fileNodes.isEmpty()) {
            return state.copy(
                isRunning = hasRunningNodes(state.nodes),
                latestError = null,
            )
        }
        val nextNodes = fileNodes.fold(state.nodes) { nodes, node -> upsertNode(nodes, node, turnId) }
        return state.copy(
            nodes = nextNodes,
            isRunning = hasRunningNodes(nextNodes),
            latestError = null,
        )
    }

    private fun acceptApproval(mutation: TimelineMutation.UpsertApproval): TimelineAreaState {
        return acceptActivityNode(mutation.turnId, mutation.status) { turnId ->
            TimelineNode.ApprovalNode(
                id = activityNodeId("approval", turnId, mutation.sourceId),
                sourceId = mutation.sourceId,
                title = mutation.title,
                body = mutation.body,
                status = mutation.status,
                turnId = turnId,
            )
        }
    }

    private fun acceptContextCompaction(mutation: TimelineMutation.UpsertContextCompaction): TimelineAreaState {
        return acceptActivityNode(mutation.turnId, mutation.status) { turnId ->
            TimelineNode.ContextCompactionNode(
                id = activityNodeId("context-compaction", turnId, mutation.sourceId),
                sourceId = mutation.sourceId,
                title = mutation.title,
                body = mutation.body,
                status = mutation.status,
                turnId = turnId,
            )
        }
    }

    private fun acceptPlan(mutation: TimelineMutation.UpsertPlan): TimelineAreaState {
        return acceptActivityNode(mutation.turnId, mutation.status) { turnId ->
            TimelineNode.PlanNode(
                id = activityNodeId("plan", turnId, mutation.sourceId),
                sourceId = mutation.sourceId,
                title = mutation.title,
                body = mutation.body,
                status = mutation.status,
                turnId = turnId,
            )
        }
    }

    private fun acceptUserInput(mutation: TimelineMutation.UpsertUserInput): TimelineAreaState {
        return acceptActivityNode(mutation.turnId, mutation.status) { turnId ->
            TimelineNode.UserInputNode(
                id = activityNodeId("user-input", turnId, mutation.sourceId),
                sourceId = mutation.sourceId,
                title = mutation.title,
                body = mutation.body,
                collapsedSummary = summarizeUserInputBody(mutation.body),
                status = mutation.status,
                turnId = turnId,
            )
        }
    }

    private fun acceptUnknownActivity(mutation: TimelineMutation.UpsertUnknownActivity): TimelineAreaState {
        return acceptActivityNode(mutation.turnId, mutation.status) { turnId ->
            TimelineNode.UnknownActivityNode(
                id = activityNodeId("unknown", turnId, mutation.sourceId),
                sourceId = mutation.sourceId,
                title = mutation.title,
                body = mutation.body,
                status = mutation.status,
                turnId = turnId,
            )
        }
    }

    private fun acceptActivityNode(
        explicitTurnId: String?,
        status: ItemStatus,
        create: (String?) -> TimelineNode,
    ): TimelineAreaState {
        val turnId = resolveTurnId(explicitTurnId)
        val node = create(turnId)
        val nextNodes = upsertNode(state.nodes, node, turnId)
        // 不在单个节点失败时清空 activeTurnId：Codex 模式下工具调用失败后 agent 会继续运行，
        // 整个 turn 尚未结束。activeTurnId 只应在 TurnCompleted 或 AppendError 时清空。
        return state.copy(
            nodes = nextNodes,
            isRunning = hasRunningState(nextNodes),
            latestError = null,
        )
    }

    private fun acceptTurnCompleted(mutation: TimelineMutation.TurnCompleted): TimelineAreaState {
        val targetTurnId = resolveCompletionTurnId(mutation.turnId)
        val completingActiveTurn = targetTurnId != null && activeTurnId == targetTurnId
        val completingOpenTurn = targetTurnId != null && openTurnId == targetTurnId
        if (completingActiveTurn) {
            activeTurnId = null
        }
        if (completingOpenTurn) {
            openTurnId = null
        }
        val nextNodes = finalizeRunningNodes(
            nodes = state.nodes,
            turnId = targetTurnId,
            status = mutation.outcome.toItemStatus(),
            errorSummary = null,
        )
        return state.copy(
            nodes = nextNodes,
            isRunning = when {
                completingActiveTurn -> hasRunningNodes(nextNodes)
                activeTurnId != null -> true
                else -> hasRunningNodes(nextNodes)
            },
        )
    }

    /**
     * Records a terminal conversation error as its own timeline item while
     * still finalizing any running nodes that belong to the same turn.
     */
    private fun acceptAppendedError(mutation: TimelineMutation.AppendError): TimelineAreaState {
        val turnId = resolveTurnId(activeTurnId)
        val errorSourceId = nextErrorSourceId()
        val nextNodes = appendNode(
            nodes = finalizeRunningNodes(
                nodes = state.nodes,
                turnId = turnId,
                status = ItemStatus.FAILED,
                errorSummary = mutation.message,
            ),
            node = TimelineNode.ErrorNode(
                id = activityNodeId("error", turnId, errorSourceId),
                sourceId = errorSourceId,
                title = "Error",
                body = mutation.message,
                status = ItemStatus.FAILED,
                turnId = turnId,
            ),
        )
        activeTurnId = null
        return state.copy(
            nodes = nextNodes,
            isRunning = false,
            latestError = mutation.message,
        )
    }

    /**
     * Appends a local informational boundary without rebinding prior nodes to a new turn.
     */
    private fun acceptEngineSwitched(mutation: TimelineMutation.AppendEngineSwitched): TimelineAreaState {
        val nextNodes = appendNode(
            nodes = state.nodes,
            node = TimelineNode.EngineSwitchedNode(
                id = activityNodeId("engine-switched", turnId = null, sourceId = mutation.sourceId),
                sourceId = mutation.sourceId,
                title = AuraCodeBundle.message("timeline.system.engineSwitched.title"),
                targetEngineLabel = mutation.targetEngineLabel,
                body = mutation.body,
                iconPath = "/icons/swap-horiz.svg",
                timestamp = mutation.timestamp,
                status = ItemStatus.SUCCESS,
                turnId = null,
            ),
        )
        return state.copy(
            nodes = nextNodes,
            isRunning = hasRunningState(nextNodes),
            latestError = null,
        )
    }

    private fun resolveTurnId(explicitTurnId: String?): String? {
        val normalized = explicitTurnId?.trim().orEmpty()
        if (normalized.isNotBlank()) {
            activeTurnId = normalized
            openTurnId = normalized
            return normalized
        }
        if (!activeTurnId.isNullOrBlank()) {
            return activeTurnId
        }
        openTurnId?.takeIf { it.isNotBlank() }?.let { trackedTurnId ->
            // Keep post-failure items attached to the still-open turn without making
            // the open-turn marker itself a UI running signal.
            activeTurnId = trackedTurnId
            return trackedTurnId
        }
        return nextSyntheticTurnId().also { generated ->
            activeTurnId = generated
            openTurnId = generated
        }
    }

    private fun resolveCompletionTurnId(turnId: String): String? {
        val normalized = turnId.trim()
        if (normalized.isNotBlank()) return normalized
        if (!activeTurnId.isNullOrBlank()) return activeTurnId
        return state.nodes.asReversed().firstNotNullOfOrNull { node ->
            node.turnId?.takeIf { currentTurnId ->
                node.status == ItemStatus.RUNNING && currentTurnId.isNotBlank()
            }
        }
    }

    private fun finalizeRunningNodes(
        nodes: List<TimelineNode>,
        turnId: String?,
        status: ItemStatus,
        errorSummary: String?,
    ): List<TimelineNode> {
        return nodes.map { node ->
            when {
                node is TimelineNode.LoadMoreNode -> node
                node.status == ItemStatus.RUNNING && (turnId == null || node.turnId == turnId) -> node.withStatus(
                    status = status,
                    errorSummary = errorSummary,
                )
                else -> node
            }
        }
    }

    private fun upsertAssistantAnswer(
        nodes: List<TimelineNode>,
        node: TimelineNode.MessageNode,
        turnId: String?,
    ): List<TimelineNode> {
        val next = nodes.toMutableList()
        val existingIndex = next.indexOfFirst { candidate ->
            candidate is TimelineNode.MessageNode &&
                candidate.sourceId == node.sourceId &&
                candidate.turnId == turnId
        }
        if (existingIndex >= 0) {
            next[existingIndex] = node
        } else {
            next += node
        }
        return next
    }

    private fun upsertNode(
        nodes: List<TimelineNode>,
        node: TimelineNode,
        turnId: String?,
    ): List<TimelineNode> {
        val next = nodes.toMutableList()
        val existingIndex = next.indexOfFirst { candidate ->
            candidate::class == node::class &&
                candidate.sourceId != null &&
                candidate.sourceId == node.sourceId &&
                candidate.turnId == turnId
        }
        if (existingIndex >= 0) {
            next[existingIndex] = node
        } else {
            next += node
        }
        return next
    }

    private fun appendNode(
        nodes: List<TimelineNode>,
        node: TimelineNode,
    ): List<TimelineNode> {
        return nodes + node
    }

    private fun decorateHistoryNodes(
        nodes: List<TimelineNode>,
        hasOlder: Boolean,
        isLoadingOlder: Boolean,
    ): List<TimelineNode> {
        val contentNodes = nodes.filterNot { it is TimelineNode.LoadMoreNode }
        return if (hasOlder || isLoadingOlder) {
            listOf(TimelineNode.LoadMoreNode(isLoading = isLoadingOlder)) + contentNodes
        } else {
            contentNodes
        }
    }

    private fun hasRunningNodes(nodes: List<TimelineNode>): Boolean {
        return nodes.any { node ->
            node !is TimelineNode.LoadMoreNode && node.status == ItemStatus.RUNNING
        }
    }

    /**
     * Keeps the timeline in a running state while the active turn is still open even if
     * only completed local placeholder nodes have arrived so far.
     */
    private fun hasRunningState(nodes: List<TimelineNode>): Boolean {
        return activeTurnId != null || hasRunningNodes(nodes)
    }

    private fun TimelineAreaState.withLiveRender(): TimelineAreaState {
        return copy(
            renderVersion = renderVersion + 1,
            renderCause = TimelineRenderCause.LIVE_UPDATE,
            prependedCount = 0,
        )
    }

    private fun TimelineNode.replaceTurnId(
        fromTurnId: String,
        toTurnId: String,
    ): TimelineNode {
        return when (this) {
            is TimelineNode.MessageNode ->
                if (turnId != fromTurnId) this else copy(id = messageNodeId(toTurnId, sourceId), turnId = toTurnId)
            is TimelineNode.ReasoningNode ->
                if (turnId != fromTurnId) this else copy(id = activityNodeId("reasoning", toTurnId, sourceId), turnId = toTurnId)
            is TimelineNode.ToolCallNode ->
                if (turnId != fromTurnId) this else copy(id = activityNodeId("tool", toTurnId, sourceId), turnId = toTurnId)
            is TimelineNode.CommandNode ->
                if (turnId != fromTurnId) this else copy(id = activityNodeId("command", toTurnId, sourceId), turnId = toTurnId)
            is TimelineNode.FileChangeNode ->
                if (turnId != fromTurnId) this else copy(id = activityNodeId("diff", toTurnId, sourceId), turnId = toTurnId)
            is TimelineNode.ApprovalNode ->
                if (turnId != fromTurnId) this else copy(id = activityNodeId("approval", toTurnId, sourceId), turnId = toTurnId)
            is TimelineNode.ContextCompactionNode ->
                if (turnId != fromTurnId) this else copy(id = activityNodeId("context-compaction", toTurnId, sourceId), turnId = toTurnId)
            is TimelineNode.PlanNode ->
                if (turnId != fromTurnId) this else copy(id = activityNodeId("plan", toTurnId, sourceId), turnId = toTurnId)
            is TimelineNode.UserInputNode ->
                if (turnId != fromTurnId) this else copy(id = activityNodeId("user-input", toTurnId, sourceId), turnId = toTurnId)
            is TimelineNode.UnknownActivityNode ->
                if (turnId != fromTurnId) this else copy(id = activityNodeId("unknown", toTurnId, sourceId), turnId = toTurnId)
            is TimelineNode.ErrorNode ->
                if (turnId != fromTurnId) this else copy(id = activityNodeId("error", toTurnId, sourceId), turnId = toTurnId)
            is TimelineNode.EngineSwitchedNode -> this
            is TimelineNode.LoadMoreNode -> this
        }
    }

    private fun TimelineNode.withStatus(
        status: ItemStatus,
        errorSummary: String?,
    ): TimelineNode {
        return when (this) {
            is TimelineNode.MessageNode -> copy(status = status)
            is TimelineNode.ReasoningNode -> copy(status = status)
            is TimelineNode.ToolCallNode -> copy(
                status = status,
                collapsedSummary = summarizeTextProcess(
                    title = title,
                    body = body,
                    status = status,
                    errorSummary = errorSummary,
                ),
            )
            is TimelineNode.CommandNode -> copy(
                status = status,
                collapsedSummary = summarizeCommandProcess(
                    status = status,
                    errorSummary = errorSummary,
                ),
            )
            is TimelineNode.FileChangeNode -> copy(
                status = status,
                collapsedSummary = summarizeFileChanges(
                    changes = changes,
                    status = status,
                    errorSummary = errorSummary,
                ),
            )
            is TimelineNode.ApprovalNode -> copy(status = status)
            is TimelineNode.ContextCompactionNode -> copy(status = status)
            is TimelineNode.PlanNode -> copy(status = status)
            is TimelineNode.UserInputNode -> copy(
                status = status,
                collapsedSummary = summarizeUserInputBody(body),
            )
            is TimelineNode.UnknownActivityNode -> copy(status = status)
            is TimelineNode.ErrorNode -> copy(status = status)
            is TimelineNode.EngineSwitchedNode -> copy(status = status)
            is TimelineNode.LoadMoreNode -> this
        }
    }

    private fun TurnOutcome.toItemStatus(): ItemStatus {
        return when (this) {
            TurnOutcome.SUCCESS -> ItemStatus.SUCCESS
            TurnOutcome.CANCELLED,
            TurnOutcome.FAILED,
            -> ItemStatus.FAILED

            TurnOutcome.RUNNING -> ItemStatus.RUNNING
        }
    }

    private fun nextSyntheticTurnId(): String {
        syntheticTurnCount += 1
        return "local-turn-$syntheticTurnCount"
    }

    private fun nextErrorSourceId(): String {
        errorNodeCount += 1
        return "timeline-error-$errorNodeCount"
    }

    private fun isSyntheticTurnId(turnId: String): Boolean = turnId.startsWith("local-turn-")

    private fun summarizeUserInputBody(body: String): String? {
        return body.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }
    }

    private fun messageNodeId(turnId: String?, sourceId: String): String =
        listOfNotNull("message", turnId?.takeIf { it.isNotBlank() }, sourceId).joinToString(":")

    private fun activityNodeId(prefix: String, turnId: String?, sourceId: String): String =
        TimelineNodeMapper.activityNodeId(prefix = prefix, turnId = turnId, sourceId = sourceId)

    private fun summarizeTextProcess(
        title: String,
        body: String,
        status: ItemStatus,
        errorSummary: String?,
    ): String? {
        return when {
            status == ItemStatus.FAILED && !errorSummary.isNullOrBlank() ->
                errorSummary.firstMeaningfulLine()

            else -> summarizeOutcome(title, body)
        }
    }

    private fun summarizeCommandProcess(
        status: ItemStatus,
        errorSummary: String?,
    ): String? {
        return when {
            status == ItemStatus.SUCCESS -> null
            status == ItemStatus.FAILED -> null
            else -> statusLabel(status)
        }
    }

    private fun summarizeFileChanges(
        changes: List<TimelineFileChange>,
        status: ItemStatus,
        errorSummary: String?,
    ): String? {
        return when {
            status == ItemStatus.FAILED && !errorSummary.isNullOrBlank() ->
                errorSummary.firstMeaningfulLine()

            changes.isEmpty() -> null
            status == ItemStatus.SUCCESS -> null
            changes.size == 1 -> changes.first().compactStatsLabel()
            else -> "${changes.size} ${AuraCodeBundle.message("timeline.fileChange.plural")}"
        }
    }

    private fun statusLabel(status: ItemStatus): String {
        return when (status) {
            ItemStatus.RUNNING -> AuraCodeBundle.message("timeline.process.working")
            ItemStatus.SUCCESS -> AuraCodeBundle.message("timeline.process.completed")
            ItemStatus.FAILED -> AuraCodeBundle.message("timeline.process.failed")
            ItemStatus.SKIPPED -> AuraCodeBundle.message("timeline.process.skipped")
        }
    }

    private fun String?.firstMeaningfulLine(): String? {
        return this?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() }
    }

    private fun String.firstSummaryLine(): String? {
        return lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .lastOrNull()
    }

    private fun summarizeOutcome(
        title: String,
        body: String,
    ): String? {
        val normalizedTitle = title.trim()
        val explicit = when {
            normalizedTitle.startsWith("Read ") -> normalizedTitle.removePrefix("Read ").takeIf { it.isNotBlank() }?.let { "Opened $it" }
            normalizedTitle.startsWith("Edit ") -> normalizedTitle.removePrefix("Edit ").takeIf { it.isNotBlank() }?.let { "Updated $it" }
            normalizedTitle.startsWith("Edited ") -> normalizedTitle.removePrefix("Edited ").takeIf { it.isNotBlank() }?.let { "Updated $it" }
            normalizedTitle.startsWith("Create ") -> normalizedTitle.removePrefix("Create ").takeIf { it.isNotBlank() }?.let { "Created $it" }
            normalizedTitle.startsWith("Created ") -> normalizedTitle
            normalizedTitle.startsWith("Delete ") -> normalizedTitle.removePrefix("Delete ").takeIf { it.isNotBlank() }?.let { "Deleted $it" }
            normalizedTitle.startsWith("Deleted ") -> normalizedTitle
            normalizedTitle.startsWith("Search ") -> "Search completed"
            normalizedTitle.startsWith("List ") -> "Listed files"
            else -> null
        }
        return explicit ?: body.firstSummaryLine()
    }

    private fun fileChangeNodeTitle(change: TimelineFileChange): String {
        return when (change.kind) {
            TimelineFileChangeKind.CREATE -> "Created ${change.displayName}"
            TimelineFileChangeKind.DELETE -> "Deleted ${change.displayName}"
            TimelineFileChangeKind.UPDATE -> "Edited ${change.displayName}"
            TimelineFileChangeKind.UNKNOWN -> "Changed ${change.displayName}"
        }
    }

    private fun TimelineFileChange.compactStatsLabel(): String? {
        val parts = buildList {
            addedLines?.takeIf { it > 0 }?.let { add("+$it") }
            deletedLines?.takeIf { it > 0 }?.let { add("-$it") }
        }
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }
}
