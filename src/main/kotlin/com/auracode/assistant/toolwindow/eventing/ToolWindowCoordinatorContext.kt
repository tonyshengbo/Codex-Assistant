package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.notification.ChatCompletionNotificationService
import com.auracode.assistant.persistence.chat.PersistedMessageAttachment
import com.auracode.assistant.provider.claude.ClaudeCliVersionService
import com.auracode.assistant.provider.codex.CodexCliVersionService
import com.auracode.assistant.provider.codex.CodexEnvironmentDetector
import com.auracode.assistant.provider.runtime.RuntimeExecutableCheckService
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.settings.skills.LocalSkillInstallPolicy
import com.auracode.assistant.settings.skills.SkillsRuntimeService
import com.auracode.assistant.settings.mcp.McpManagementAdapterRegistry
import com.auracode.assistant.toolwindow.execution.ApprovalAreaStore
import com.auracode.assistant.toolwindow.submission.PendingComposerSubmission
import com.auracode.assistant.toolwindow.shell.RightDrawerAreaStore
import com.auracode.assistant.toolwindow.sessions.HeaderAreaStore
import com.auracode.assistant.toolwindow.sessions.SessionAttentionStore
import com.auracode.assistant.toolwindow.execution.StatusAreaStore
import com.auracode.assistant.toolwindow.conversation.TimelineAreaStore
import com.auracode.assistant.toolwindow.conversation.TimelineFileChange
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptStore
import kotlinx.coroutines.CoroutineScope

internal class ToolWindowCoordinatorContext(
    val chatService: AgentChatService,
    val settingsService: AgentSettingsService,
    val eventHub: ToolWindowEventHub,
    val headerStore: HeaderAreaStore,
    val statusStore: StatusAreaStore,
    val timelineStore: TimelineAreaStore,
    val composerStore: com.auracode.assistant.toolwindow.submission.ComposerAreaStore,
    val rightDrawerStore: RightDrawerAreaStore,
    val approvalStore: ApprovalAreaStore,
    val toolUserInputPromptStore: ToolUserInputPromptStore,
    val completionNotificationService: ChatCompletionNotificationService?,
    val sessionAttentionStore: SessionAttentionStore,
    val mcpAdapterRegistry: McpManagementAdapterRegistry,
    val skillsRuntimeService: SkillsRuntimeService,
    val codexEnvironmentDetector: CodexEnvironmentDetector,
    val codexCliVersionService: CodexCliVersionService,
    val claudeCliVersionService: ClaudeCliVersionService,
    val runtimeExecutableCheckService: RuntimeExecutableCheckService,
    val pickAttachments: () -> List<String>,
    val pickExportPath: (String) -> String?,
    val searchProjectFiles: (String, Int) -> List<String>,
    val isMentionCandidateFile: (String) -> Boolean,
    val readFileContent: (String) -> String?,
    val openTimelineFileChange: (TimelineFileChange) -> Unit,
    val openTimelineFilePath: (String) -> Unit,
    val revealPathInFileManager: (String) -> Boolean,
    val localSkillInstallPolicy: LocalSkillInstallPolicy,
    val writeExportFile: (String, String) -> Unit,
    val openExternalUrl: (String) -> Boolean,
    val diagnosticLog: (String, Throwable?) -> Unit,
    val onSessionSnapshotPublished: () -> Unit,
    val historyPageSize: Int,
    val scope: CoroutineScope,
    val recentFocusedFiles: ArrayDeque<String>,
    val pendingSubmissionsBySessionId: LinkedHashMap<String, ArrayDeque<PendingComposerSubmission>>,
    val activePlanRunContexts: LinkedHashMap<String, ActivePlanRunContext>,
    val coroutineLauncher: CoordinatorCoroutineLauncher,
    val dispatchSessionEvent: (String, AppEvent) -> Unit,
    val captureSessionViewState: (String) -> Unit,
    val restoreSessionViewState: (String) -> Boolean,
    val publishSessionSnapshot: () -> Unit,
    val publishSettingsSnapshot: () -> Unit,
    val publishConversationCapabilities: () -> Unit,
    val publishUnifiedEvent: (String, com.auracode.assistant.protocol.UnifiedEvent) -> Unit,
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
        events: List<com.auracode.assistant.protocol.UnifiedEvent>,
        oldestCursor: String?,
        hasOlder: Boolean,
        prepend: Boolean,
    ) -> Unit,
    val applySessionDomainEvents: (String, List<SessionDomainEvent>) -> Unit,
) {
    fun activeSessionId(): String = chatService.getCurrentSessionId()

    fun pendingSubmissionQueue(sessionId: String): ArrayDeque<PendingComposerSubmission> {
        return pendingSubmissionsBySessionId.getOrPut(sessionId) { ArrayDeque() }
    }
}
