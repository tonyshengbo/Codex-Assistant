package com.auracode.assistant.conversation

import com.auracode.assistant.session.kernel.SessionDomainEvent

data class ConversationRef(
    val providerId: String,
    val remoteConversationId: String,
)

data class ConversationSummary(
    val remoteConversationId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String,
)

data class ConversationSummaryPage(
    val conversations: List<ConversationSummary>,
    val nextCursor: String?,
)

data class ConversationCapabilities(
    val supportsStructuredHistory: Boolean,
    val supportsHistoryPagination: Boolean,
    val supportsPlanMode: Boolean,
    val supportsApprovalRequests: Boolean,
    val supportsToolUserInput: Boolean,
    val supportsResume: Boolean,
    val supportsAttachments: Boolean,
    val supportsImageInputs: Boolean,
    val supportsSubagents: Boolean = false,
)

internal data class ConversationHistoryPage(
    val events: List<SessionDomainEvent>,
    val hasOlder: Boolean,
    val olderCursor: String?,
)
