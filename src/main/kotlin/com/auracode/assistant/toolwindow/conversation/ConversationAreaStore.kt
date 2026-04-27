package com.auracode.assistant.toolwindow.conversation

import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.eventing.UiIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ConversationRenderCause {
    IDLE,
    HISTORY_RESET,
    HISTORY_PREPEND,
    LIVE_UPDATE,
}

internal data class ConversationAreaState(
    val nodes: List<ConversationActivityItem> = emptyList(),
    val oldestCursor: String? = null,
    val hasOlder: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val isRunning: Boolean = false,
    val expandedNodeIds: Set<String> = emptySet(),
    val renderVersion: Long = 0L,
    val renderCause: ConversationRenderCause = ConversationRenderCause.IDLE,
    val prependedCount: Int = 0,
    val latestError: String? = null,
    val promptScrollRequestVersion: Long = 0L,
)

internal class ConversationAreaStore {
    private val _state = MutableStateFlow(ConversationAreaState())
    val state: StateFlow<ConversationAreaState> = _state.asStateFlow()

    fun onEvent(event: AppEvent) {
        when (event) {
            is AppEvent.UiIntentPublished -> {
                when (val intent = event.intent) {
                    is UiIntent.ToggleNodeExpanded -> {
                        val current = _state.value.expandedNodeIds
                        val next = if (current.contains(intent.nodeId)) current - intent.nodeId else current + intent.nodeId
                        _state.value = _state.value.copy(expandedNodeIds = next)
                    }

                    else -> Unit
                }
            }

            is AppEvent.ConversationOlderLoadingChanged -> {
                val previous = _state.value
                _state.value = previous.copy(
                    nodes = decorateHistoryNodes(
                        nodes = previous.nodes.filterNot { it is ConversationActivityItem.LoadMoreNode },
                        hasOlder = previous.hasOlder,
                        isLoadingOlder = event.loading,
                    ),
                    isLoadingOlder = event.loading,
                    renderVersion = previous.renderVersion + 1,
                )
            }

            is AppEvent.ConversationUiProjectionUpdated -> {
                syncProjectedState(
                    nodes = event.nodes,
                    oldestCursor = event.oldestCursor,
                    hasOlder = event.hasOlder,
                    isRunning = event.isRunning,
                    latestError = event.latestError,
                )
            }

            is AppEvent.PromptAccepted -> {
                val previous = _state.value
                _state.value = previous.copy(
                    isRunning = true,
                    latestError = null,
                    renderVersion = previous.renderVersion + 1,
                    renderCause = ConversationRenderCause.LIVE_UPDATE,
                    prependedCount = 0,
                    promptScrollRequestVersion = previous.promptScrollRequestVersion + 1,
                )
            }

            AppEvent.ConversationReset -> {
                _state.value = ConversationAreaState()
            }

            else -> Unit
        }
    }

    fun restoreState(state: ConversationAreaState) {
        _state.value = state
    }

    /** Restores the session-local expanded node state after projection/history rebuilt the visible nodes. */
    fun restoreExpandedNodeIds(expandedNodeIds: Set<String>) {
        val availableNodeIds = _state.value.nodes.mapTo(linkedSetOf(), ConversationActivityItem::id)
        _state.value = _state.value.copy(
            expandedNodeIds = expandedNodeIds.intersect(availableNodeIds),
        )
    }

    /** Applies one full conversation projection while preserving local UI-only expansion state. */
    private fun syncProjectedState(
        nodes: List<ConversationActivityItem>,
        oldestCursor: String?,
        hasOlder: Boolean,
        isRunning: Boolean,
        latestError: String?,
    ) {
        val previous = _state.value
        val previousContentNodes = previous.nodes.filterNot { it is ConversationActivityItem.LoadMoreNode }
        val nextContentNodes = nodes.filterNot { it is ConversationActivityItem.LoadMoreNode }
        val prependedCount = when {
            previous.isLoadingOlder && nextContentNodes.size >= previousContentNodes.size ->
                nextContentNodes.size - previousContentNodes.size

            else -> 0
        }
        val renderCause = when {
            prependedCount > 0 -> ConversationRenderCause.HISTORY_PREPEND
            previous.nodes.isEmpty() && nextContentNodes.isNotEmpty() -> ConversationRenderCause.HISTORY_RESET
            else -> ConversationRenderCause.LIVE_UPDATE
        }
        val nextState = previous.copy(
            nodes = decorateHistoryNodes(
                nodes = nodes,
                hasOlder = hasOlder,
                isLoadingOlder = previous.isLoadingOlder,
            ),
            oldestCursor = oldestCursor,
            hasOlder = hasOlder,
            isLoadingOlder = false,
            isRunning = isRunning,
            latestError = latestError,
            renderVersion = previous.renderVersion + 1,
            renderCause = renderCause,
            prependedCount = prependedCount,
        )
        _state.value = nextState.copy(
            expandedNodeIds = projectedExpandedNodeIds(previous = previous, nextState = nextState),
        )
    }

    /** Recomputes expansion state for either reducer-driven or projection-driven node updates. */
    private fun projectedExpandedNodeIds(
        previous: ConversationAreaState,
        nextState: ConversationAreaState,
    ): Set<String> {
        val nextNodeIds = nextState.nodes.mapTo(linkedSetOf(), ConversationActivityItem::id)
        var expanded = previous.expandedNodeIds.intersect(nextNodeIds)

        val previousById = previous.nodes.associateBy(ConversationActivityItem::id)
        nextState.nodes.forEach { node ->
            if (shouldExpandByDefault(node, previousById[node.id])) {
                expanded = expanded + node.id
            }

            if (!node.isProcessActivityNode()) return@forEach

            val previousNode = previousById[node.id]
            val isRunning = node.status == ItemStatus.RUNNING
            val wasRunning = previousNode?.status == ItemStatus.RUNNING
            when {
                previousNode == null && shouldAutoExpandOnArrival(node, isRunning) -> expanded = expanded + node.id
                shouldAutoExpandAfterContentArrives(previousNode, node, isRunning) -> expanded = expanded + node.id
                wasRunning && shouldAutoCollapseOnCompletion(node, isRunning) -> expanded = expanded - node.id
            }
        }
        return expanded
    }

    /** Applies the load-more decoration used by both reducer history replay and projected state replacement. */
    private fun decorateHistoryNodes(
        nodes: List<ConversationActivityItem>,
        hasOlder: Boolean,
        isLoadingOlder: Boolean,
    ): List<ConversationActivityItem> {
        val contentNodes = nodes.filterNot { it is ConversationActivityItem.LoadMoreNode }
        return if (hasOlder || isLoadingOlder) {
            listOf(ConversationActivityItem.LoadMoreNode(isLoading = isLoadingOlder)) + contentNodes
        } else {
            contentNodes
        }
    }

    private fun shouldAutoExpandOnArrival(node: ConversationActivityItem, isRunning: Boolean): Boolean {
        return isRunning && node !is ConversationActivityItem.FileChangeNode && node.hasAutoExpandableContent()
    }

    private fun shouldAutoExpandAfterContentArrives(
        previousNode: ConversationActivityItem?,
        node: ConversationActivityItem,
        isRunning: Boolean,
    ): Boolean {
        if (!isRunning || node is ConversationActivityItem.FileChangeNode) {
            return false
        }
        if (previousNode == null) {
            return false
        }
        return !previousNode.hasAutoExpandableContent() && node.hasAutoExpandableContent()
    }

    private fun shouldAutoCollapseOnCompletion(node: ConversationActivityItem, isRunning: Boolean): Boolean {
        return !isRunning && node !is ConversationActivityItem.FileChangeNode
    }

    private fun shouldExpandByDefault(node: ConversationActivityItem, previousNode: ConversationActivityItem?): Boolean {
        return previousNode == null && (node is ConversationActivityItem.PlanNode || node is ConversationActivityItem.EngineSwitchedNode)
    }
}

/**
 * Keeps streaming process nodes collapsed until they have meaningful body content to show.
 */
private fun ConversationActivityItem.hasAutoExpandableContent(): Boolean {
    return when (this) {
        is ConversationActivityItem.ToolCallNode -> body.isNotBlank()
        is ConversationActivityItem.CommandNode -> body.isNotBlank()
        is ConversationActivityItem.FileChangeNode -> changes.isNotEmpty()
        is ConversationActivityItem.ReasoningNode -> body.isNotBlank()
        else -> false
    }
}
