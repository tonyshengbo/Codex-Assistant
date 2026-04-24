package com.auracode.assistant.toolwindow.timeline

import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.eventing.UiIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class TimelineRenderCause {
    IDLE,
    HISTORY_RESET,
    HISTORY_PREPEND,
    LIVE_UPDATE,
}

internal data class TimelineAreaState(
    val nodes: List<TimelineNode> = emptyList(),
    val oldestCursor: String? = null,
    val hasOlder: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val isRunning: Boolean = false,
    val expandedNodeIds: Set<String> = emptySet(),
    val renderVersion: Long = 0L,
    val renderCause: TimelineRenderCause = TimelineRenderCause.IDLE,
    val prependedCount: Int = 0,
    val latestError: String? = null,
    val promptScrollRequestVersion: Long = 0L,
)

internal class TimelineAreaStore {
    private val reducer = TimelineNodeReducer()

    private val _state = MutableStateFlow(TimelineAreaState())
    val state: StateFlow<TimelineAreaState> = _state.asStateFlow()

    fun onEvent(event: AppEvent) {
        when (event) {
            is AppEvent.TimelineMutationApplied -> {
                val previous = _state.value
                reducer.accept(event.mutation)
                syncReducerState(previous)
            }

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

            is AppEvent.TimelineOlderLoadingChanged -> {
                val previous = _state.value
                reducer.setLoadingOlder(event.loading)
                syncReducerState(previous)
            }

            is AppEvent.TimelineHistoryLoaded -> {
                val previous = _state.value
                if (event.prepend) {
                    reducer.prependHistory(
                        nodes = event.nodes,
                        oldestCursor = event.oldestCursor,
                        hasOlder = event.hasOlder,
                    )
                } else {
                    reducer.replaceHistory(
                        nodes = event.nodes,
                        oldestCursor = event.oldestCursor,
                        hasOlder = event.hasOlder,
                    )
                }
                syncReducerState(previous)
            }

            is AppEvent.PromptAccepted -> {
                val previous = _state.value
                reducer.markTurnPending(event.localTurnId)
                syncReducerState(previous)
            }

            AppEvent.ConversationReset -> {
                reducer.reset()
                _state.value = reducer.state
            }

            else -> Unit
        }
    }

    fun restoreState(state: TimelineAreaState) {
        reducer.restoreState(state)
        _state.value = state
    }

    private fun syncReducerState(previous: TimelineAreaState = _state.value) {
        val nextState = reducer.state
        val nextNodeIds = nextState.nodes.mapTo(linkedSetOf(), TimelineNode::id)
        var expanded = previous.expandedNodeIds.intersect(nextNodeIds)

        val previousById = previous.nodes.associateBy(TimelineNode::id)
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

        _state.value = reducer.state.copy(
            expandedNodeIds = expanded,
        )
    }

    private fun shouldAutoExpandOnArrival(node: TimelineNode, isRunning: Boolean): Boolean {
        return isRunning && node !is TimelineNode.FileChangeNode && node.hasAutoExpandableContent()
    }

    private fun shouldAutoExpandAfterContentArrives(
        previousNode: TimelineNode?,
        node: TimelineNode,
        isRunning: Boolean,
    ): Boolean {
        if (!isRunning || node is TimelineNode.FileChangeNode) {
            return false
        }
        if (previousNode == null) {
            return false
        }
        return !previousNode.hasAutoExpandableContent() && node.hasAutoExpandableContent()
    }

    private fun shouldAutoCollapseOnCompletion(node: TimelineNode, isRunning: Boolean): Boolean {
        return !isRunning && node !is TimelineNode.FileChangeNode
    }

    private fun shouldExpandByDefault(node: TimelineNode, previousNode: TimelineNode?): Boolean {
        return previousNode == null && (node is TimelineNode.PlanNode || node is TimelineNode.EngineSwitchedNode)
    }
}

/**
 * Keeps streaming process nodes collapsed until they have meaningful body content to show.
 */
private fun TimelineNode.hasAutoExpandableContent(): Boolean {
    return when (this) {
        is TimelineNode.ToolCallNode -> body.isNotBlank()
        is TimelineNode.CommandNode -> body.isNotBlank()
        is TimelineNode.FileChangeNode -> changes.isNotEmpty()
        is TimelineNode.ReasoningNode -> body.isNotBlank()
        else -> false
    }
}
