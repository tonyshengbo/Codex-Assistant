package com.auracode.assistant.provider

import com.auracode.assistant.conversation.ConversationCapabilities
import com.auracode.assistant.conversation.ConversationHistoryPage
import com.auracode.assistant.conversation.ConversationRef
import com.auracode.assistant.conversation.ConversationSummaryPage
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedToolUserInputAnswerDraft
import com.auracode.assistant.toolwindow.execution.ApprovalAction
import kotlinx.coroutines.flow.Flow

interface AgentProvider {
    val providerId: String
        get() = CodexProviderFactory.ENGINE_ID
    fun stream(request: AgentRequest): Flow<UnifiedEvent>
    suspend fun loadInitialHistory(ref: ConversationRef, pageSize: Int): ConversationHistoryPage =
        ConversationHistoryPage(events = emptyList(), hasOlder = false, olderCursor = null)

    suspend fun loadOlderHistory(ref: ConversationRef, cursor: String, pageSize: Int): ConversationHistoryPage =
        ConversationHistoryPage(events = emptyList(), hasOlder = false, olderCursor = null)

    suspend fun listRemoteConversations(
        pageSize: Int,
        cursor: String? = null,
        cwd: String? = null,
        searchTerm: String? = null,
    ): ConversationSummaryPage = ConversationSummaryPage(conversations = emptyList(), nextCursor = null)

    fun capabilities(): ConversationCapabilities = ConversationCapabilities(
        supportsStructuredHistory = false,
        supportsHistoryPagination = false,
        supportsPlanMode = false,
        supportsApprovalRequests = false,
        supportsToolUserInput = false,
        supportsResume = true,
        supportsAttachments = true,
        supportsImageInputs = true,
        supportsSubagents = false,
    )
    fun submitApprovalDecision(requestId: String, decision: ApprovalAction): Boolean = false
    fun submitToolUserInput(
        requestId: String,
        answers: Map<String, UnifiedToolUserInputAnswerDraft>,
    ): Boolean = false
    fun cancel(requestId: String)
}
