package com.auracode.assistant.toolwindow.execution

import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.eventing.UiIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ApprovalAreaStore {
    private val _state = MutableStateFlow(ApprovalAreaState())
    val state: StateFlow<ApprovalAreaState> = _state.asStateFlow()

    fun restoreState(state: ApprovalAreaState) {
        _state.value = state
    }

    fun onEvent(event: AppEvent) {
        when (event) {
            is AppEvent.ApprovalRequested -> enqueue(event.request)
            is AppEvent.ApprovalResolved -> resolve(event.requestId)
            is AppEvent.ExecutionUiProjectionUpdated -> replaceQueue(event.approvals)
            AppEvent.ClearApprovals -> _state.value = ApprovalAreaState()
            is AppEvent.UiIntentPublished -> handleUiIntent(event.intent)
            else -> Unit
        }
    }

    private fun enqueue(request: PendingApprovalRequestUiModel) {
        val queue = (_state.value.queue + request).reindexed()
        _state.value = ApprovalAreaState(
            queue = queue,
            selectedAction = ApprovalAction.ALLOW,
            current = queue.firstOrNull(),
            visible = queue.isNotEmpty(),
        )
    }

    private fun resolve(requestId: String) {
        val queue = _state.value.queue.filterNot { it.requestId == requestId }.reindexed()
        _state.value = ApprovalAreaState(
            queue = queue,
            selectedAction = ApprovalAction.ALLOW,
            current = queue.firstOrNull(),
            visible = queue.isNotEmpty(),
        )
    }

    /** Replaces the projected approval queue while preserving the active selection when possible. */
    private fun replaceQueue(projectedQueue: List<PendingApprovalRequestUiModel>) {
        val queue = projectedQueue.reindexed()
        val currentRequestId = _state.value.current?.requestId
        _state.value = ApprovalAreaState(
            queue = queue,
            selectedAction = _state.value.selectedAction,
            current = queue.firstOrNull { it.requestId == currentRequestId } ?: queue.firstOrNull(),
            visible = queue.isNotEmpty(),
        )
    }

    private fun handleUiIntent(intent: UiIntent) {
        if (_state.value.queue.isEmpty()) return
        when (intent) {
            UiIntent.MoveApprovalActionNext -> {
                _state.value = _state.value.copy(selectedAction = _state.value.selectedAction.next())
            }

            UiIntent.MoveApprovalActionPrevious -> {
                _state.value = _state.value.copy(selectedAction = _state.value.selectedAction.previous())
            }

            is UiIntent.SelectApprovalAction -> {
                _state.value = _state.value.copy(selectedAction = intent.action)
            }

            else -> Unit
        }
    }

    private fun List<PendingApprovalRequestUiModel>.reindexed(): List<PendingApprovalRequestUiModel> {
        val total = size
        return mapIndexed { index, request ->
            request.copy(queuePosition = index + 1, queueSize = total)
        }
    }

    private fun ApprovalAction.next(): ApprovalAction {
        return when (this) {
            ApprovalAction.ALLOW -> ApprovalAction.REJECT
            ApprovalAction.REJECT -> ApprovalAction.ALLOW_FOR_SESSION
            ApprovalAction.ALLOW_FOR_SESSION -> ApprovalAction.ALLOW
        }
    }

    private fun ApprovalAction.previous(): ApprovalAction {
        return when (this) {
            ApprovalAction.ALLOW -> ApprovalAction.ALLOW_FOR_SESSION
            ApprovalAction.REJECT -> ApprovalAction.ALLOW
            ApprovalAction.ALLOW_FOR_SESSION -> ApprovalAction.REJECT
        }
    }
}
