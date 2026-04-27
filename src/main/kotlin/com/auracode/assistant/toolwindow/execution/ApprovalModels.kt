package com.auracode.assistant.toolwindow.execution

import com.auracode.assistant.protocol.ProviderApprovalRequest
import com.auracode.assistant.protocol.ProviderApprovalRequestKind

enum class ApprovalAction {
    ALLOW,
    REJECT,
    ALLOW_FOR_SESSION,
}

internal data class PendingApprovalRequestUiModel(
    val requestId: String,
    val turnId: String?,
    val itemId: String,
    val kind: ProviderApprovalRequestKind,
    val title: String,
    val body: String,
    val command: String? = null,
    val cwd: String? = null,
    val permissions: List<String> = emptyList(),
    val allowForSession: Boolean = true,
    val queuePosition: Int = 1,
    val queueSize: Int = 1,
)

internal data class ApprovalAreaState(
    val queue: List<PendingApprovalRequestUiModel> = emptyList(),
    val selectedAction: ApprovalAction = ApprovalAction.ALLOW,
    val current: PendingApprovalRequestUiModel? = null,
    val visible: Boolean = false,
)

internal fun ProviderApprovalRequest.toUiModel(
    queuePosition: Int = 1,
    queueSize: Int = 1,
): PendingApprovalRequestUiModel {
    return PendingApprovalRequestUiModel(
        requestId = requestId,
        turnId = turnId,
        itemId = itemId,
        kind = kind,
        title = title,
        body = body,
        command = command,
        cwd = cwd,
        permissions = permissions,
        allowForSession = allowForSession,
        queuePosition = queuePosition,
        queueSize = queueSize,
    )
}
