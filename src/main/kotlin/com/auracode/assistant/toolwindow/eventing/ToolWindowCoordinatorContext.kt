package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.notification.ChatCompletionNotificationService
import com.auracode.assistant.persistence.chat.PersistedMessageAttachment
import com.auracode.assistant.provider.claude.ClaudeCliVersionService
import com.auracode.assistant.provider.codex.CodexCliVersionService
import com.auracode.assistant.provider.codex.CodexEnvironmentDetector
import com.auracode.assistant.provider.runtime.RuntimeExecutableCheckService
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.service.AgentChatService.SessionHistoryReplayPage
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.settings.skills.EngineSkillsService
import com.auracode.assistant.settings.skills.LocalSkillInstallPolicy
import com.auracode.assistant.settings.skills.SkillsRuntimeService
import com.auracode.assistant.settings.mcp.McpManagementAdapterRegistry
import com.auracode.assistant.toolwindow.execution.ApprovalAreaStore
import com.auracode.assistant.toolwindow.submission.PendingSubmission
import com.auracode.assistant.toolwindow.shell.SidePanelAreaStore
import com.auracode.assistant.toolwindow.sessions.SessionTabsAreaStore
import com.auracode.assistant.toolwindow.sessions.SessionAttentionStore
import com.auracode.assistant.toolwindow.execution.ExecutionStatusAreaStore
import com.auracode.assistant.toolwindow.conversation.ConversationAreaStore
import com.auracode.assistant.toolwindow.conversation.ConversationFileChange
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptStore
import kotlinx.coroutines.CoroutineScope

internal class ToolWindowCoordinatorContext(
    val chatService: AgentChatService,
    val settingsService: AgentSettingsService,
    val eventHub: ToolWindowEventHub,
    val sessionTabsStore: SessionTabsAreaStore,
    val executionStatusStore: ExecutionStatusAreaStore,
    val conversationStore: ConversationAreaStore,
    val submissionStore: com.auracode.assistant.toolwindow.submission.SubmissionAreaStore,
    val sidePanelStore: SidePanelAreaStore,
    val approvalStore: ApprovalAreaStore,
    val toolUserInputPromptStore: ToolUserInputPromptStore,
    val completionNotificationService: ChatCompletionNotificationService?,
    val sessionAttentionStore: SessionAttentionStore,
    val mcpAdapterRegistry: McpManagementAdapterRegistry,
    val skillsRuntimeService: SkillsRuntimeService,
    val engineSkillsService: EngineSkillsService,
    val codexEnvironmentDetector: CodexEnvironmentDetector,
    val codexCliVersionService: CodexCliVersionService,
    val claudeCliVersionService: ClaudeCliVersionService,
    val runtimeExecutableCheckService: RuntimeExecutableCheckService,
    val pickAttachments: () -> List<String>,
    val pickExportPath: (String) -> String?,
    val searchProjectFiles: (String, Int) -> List<String>,
    val isMentionCandidateFile: (String) -> Boolean,
    val readFileContent: (String) -> String?,
    val openConversationFileChange: (ConversationFileChange) -> Unit,
    val openConversationFilePath: (String) -> Unit,
    val revealPathInFileManager: (String) -> Boolean,
    val localSkillInstallPolicy: LocalSkillInstallPolicy,
    val writeExportFile: (String, String) -> Unit,
    val openExternalUrl: (String) -> Boolean,
    val diagnosticLog: (String, Throwable?) -> Unit,
    val onSessionSnapshotPublished: () -> Unit,
    val historyPageSize: Int,
    val scope: CoroutineScope,
    val recentFocusedFiles: ArrayDeque<String>,
    val pendingSubmissionsBySessionId: LinkedHashMap<String, ArrayDeque<PendingSubmission>>,
    val activePlanRunContexts: LinkedHashMap<String, ActivePlanRunContext>,
    val coroutineLauncher: CoordinatorCoroutineLauncher,
    val dispatchSessionEvent: (String, AppEvent) -> Unit,
    val captureSessionViewState: (String) -> Unit,
    val restoreSessionViewState: (String) -> Boolean,
    val publishSessionSnapshot: () -> Unit,
    val publishSettingsSnapshot: () -> Unit,
    val publishConversationCapabilities: () -> Unit,
    val publishLocalUserMessage: (
        sessionId: String,
        sourceId: String,
        text: String,
        timestamp: Long,
        turnId: String?,
        attachments: List<PersistedMessageAttachment>,
    ) -> Unit,
    val restoreSessionHistory: (
        sessionId: String,
        page: SessionHistoryReplayPage,
        prepend: Boolean,
    ) -> Unit,
    val applySessionDomainEvents: (String, List<SessionDomainEvent>) -> Unit,
) {
    fun activeSessionId(): String = chatService.getCurrentSessionId()

    fun pendingSubmissionQueue(sessionId: String): ArrayDeque<PendingSubmission> {
        return pendingSubmissionsBySessionId.getOrPut(sessionId) { ArrayDeque() }
    }
}
