package com.auracode.assistant.toolwindow.eventing

import androidx.compose.ui.text.input.TextFieldValue
import com.auracode.assistant.conversation.ConversationCapabilities
import com.auracode.assistant.conversation.ConversationSummary
import com.auracode.assistant.integration.build.BuildErrorAuraRequest
import com.auracode.assistant.integration.ide.IdeExternalRequest
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.SavedAgentDefinition
import com.auracode.assistant.settings.UiLanguageMode
import com.auracode.assistant.settings.UiScaleMode
import com.auracode.assistant.settings.UiThemeMode
import com.auracode.assistant.settings.skills.SkillRuntimeEntry
import com.auracode.assistant.settings.mcp.McpBusyState
import com.auracode.assistant.settings.mcp.McpRuntimeStatus
import com.auracode.assistant.settings.mcp.McpServerDraft
import com.auracode.assistant.settings.mcp.McpServerSummary
import com.auracode.assistant.settings.mcp.McpTestResult
import com.auracode.assistant.settings.mcp.McpValidationErrors
import com.auracode.assistant.provider.claude.ClaudeCliVersionSnapshot
import com.auracode.assistant.provider.codex.CodexEnvironmentCheckResult
import com.auracode.assistant.provider.codex.CodexCliVersionSnapshot
import com.auracode.assistant.provider.runtime.RuntimeExecutableCheckResult
import com.auracode.assistant.toolwindow.execution.ApprovalAction
import com.auracode.assistant.toolwindow.submission.SubmissionRunningPlanState
import com.auracode.assistant.toolwindow.submission.MentionSuggestion
import com.auracode.assistant.toolwindow.submission.PlanCompletionAction
import com.auracode.assistant.toolwindow.submission.PendingSubmission
import com.auracode.assistant.toolwindow.submission.SessionSubagentUiModel
import com.auracode.assistant.toolwindow.execution.PendingApprovalRequestUiModel
import com.auracode.assistant.toolwindow.submission.ContextEntry
import com.auracode.assistant.toolwindow.submission.FocusedContextSnapshot
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.execution.PlanCompletionPromptUiModel
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptUiModel
import com.auracode.assistant.toolwindow.settings.RuntimeSettingsTab
import com.auracode.assistant.toolwindow.settings.SettingsSection
import com.auracode.assistant.toolwindow.shared.UiText
import com.auracode.assistant.toolwindow.execution.ExecutionTurnStatusUiState
import com.auracode.assistant.toolwindow.conversation.ConversationFileChange
import com.auracode.assistant.toolwindow.conversation.ConversationActivityItem

internal sealed interface UiIntent {
    data object NewSession : UiIntent
    data object NewTab : UiIntent
    data object ToggleHistory : UiIntent
    data object LoadHistoryConversations : UiIntent
    data object LoadMoreHistoryConversations : UiIntent
    data class EditHistorySearchQuery(val value: String) : UiIntent
    data class OpenRemoteConversation(val remoteConversationId: String, val title: String) : UiIntent
    data class ExportRemoteConversation(val remoteConversationId: String, val title: String) : UiIntent
    data object ToggleSettings : UiIntent
    data object CloseSidePanel : UiIntent
    data class DeleteSession(val sessionId: String) : UiIntent
    data class SwitchSession(val sessionId: String) : UiIntent
    data object LoadOlderMessages : UiIntent
    data class ToggleNodeExpanded(val nodeId: String) : UiIntent
    data class OpenConversationFileChange(val change: ConversationFileChange) : UiIntent
    data class OpenConversationFilePath(val path: String) : UiIntent
    data class UpdateDocument(val value: TextFieldValue) : UiIntent
    data class InputChanged(val value: String) : UiIntent
    data class SubmitBuildErrorRequest(val request: BuildErrorAuraRequest) : UiIntent
    data class SubmitExternalRequest(val request: IdeExternalRequest) : UiIntent
    data object SendPrompt : UiIntent
    data object CancelRun : UiIntent
    data class RemovePendingSubmission(val id: String) : UiIntent
    data class SelectMode(val mode: SubmissionMode) : UiIntent
    data object ToggleExecutionMode : UiIntent
    data object TogglePlanMode : UiIntent
    data object ToggleEngineMenu : UiIntent
    data class RequestEngineSwitch(val engineId: String) : UiIntent
    data class SelectEngine(val engineId: String) : UiIntent
    data object DismissEngineSwitchDialog : UiIntent
    data object ToggleModelMenu : UiIntent
    data class SelectModel(val model: String) : UiIntent
    data object StartAddingCustomModel : UiIntent
    data class EditCustomModelDraft(val value: String) : UiIntent
    data object SaveCustomModel : UiIntent
    data object CancelAddingCustomModel : UiIntent
    data class DeleteCustomModel(val model: String) : UiIntent
    data object ToggleReasoningMenu : UiIntent
    data class SelectReasoning(val reasoning: SubmissionReasoning) : UiIntent
    data object OpenAttachmentPicker : UiIntent
    data object PasteImageFromClipboard : UiIntent
    data class AddAttachments(val paths: List<String>) : UiIntent
    data class AddContextFiles(val paths: List<String>) : UiIntent
    data class RemoveAttachment(val id: String) : UiIntent
    data class OpenAttachmentPreview(val id: String) : UiIntent
    data object CloseAttachmentPreview : UiIntent
    data class RemoveContextFile(val path: String) : UiIntent
    data object ToggleEditedFilesExpanded : UiIntent
    data class AcceptEditedFile(val path: String) : UiIntent
    data object AcceptAllEditedFiles : UiIntent
    data class OpenEditedFileDiff(val path: String) : UiIntent
    data class RevertEditedFile(val path: String) : UiIntent
    data object RevertAllEditedFiles : UiIntent
    data class UpdateFocusedContextFile(val snapshot: FocusedContextSnapshot?) : UiIntent
    data class RequestMentionSuggestions(val query: String, val documentVersion: Long) : UiIntent
    data class SelectMentionFile(val path: String) : UiIntent
    data class SelectSessionSubagentMention(val threadId: String) : UiIntent
    data class ToggleSubagentDetails(val threadId: String) : UiIntent
    data class RemoveMentionFile(val id: String) : UiIntent
    data object MoveMentionSelectionNext : UiIntent
    data object MoveMentionSelectionPrevious : UiIntent
    data object DismissMentionPopup : UiIntent
    data object ToggleSubagentTrayExpanded : UiIntent
    data object DismissSubagentTray : UiIntent
    data class RequestAgentSuggestions(val query: String, val documentVersion: Long) : UiIntent
    data class SelectAgent(val agent: SavedAgentDefinition) : UiIntent
    data class RemoveSelectedAgent(val id: String) : UiIntent
    data object MoveAgentSelectionNext : UiIntent
    data object MoveAgentSelectionPrevious : UiIntent
    data object DismissAgentPopup : UiIntent
    data object MoveSlashSelectionNext : UiIntent
    data object MoveSlashSelectionPrevious : UiIntent
    data object DismissSlashPopup : UiIntent
    data class SelectSlashCommand(val command: String) : UiIntent
    data class SelectSlashSkill(val name: String) : UiIntent
    data object MoveApprovalActionNext : UiIntent
    data object MoveApprovalActionPrevious : UiIntent
    data class SelectApprovalAction(val action: ApprovalAction) : UiIntent
    data class SubmitApprovalAction(val action: ApprovalAction? = null) : UiIntent
    data class SelectToolUserInputOption(val questionId: String, val optionLabel: String) : UiIntent
    data class EditToolUserInputAnswer(val questionId: String, val value: String) : UiIntent
    data object MoveToolUserInputSelectionNext : UiIntent
    data object MoveToolUserInputSelectionPrevious : UiIntent
    data object AdvanceToolUserInputPrompt : UiIntent
    data object RetreatToolUserInputPrompt : UiIntent
    data object SubmitToolUserInputPrompt : UiIntent
    data object CancelToolUserInputPrompt : UiIntent
    data object ExecuteApprovedPlan : UiIntent
    data class SelectPlanCompletionAction(val action: PlanCompletionAction) : UiIntent
    data object MovePlanCompletionSelectionNext : UiIntent
    data object MovePlanCompletionSelectionPrevious : UiIntent
    data object ToggleRunningPlanExpanded : UiIntent
    data class EditPlanRevisionDraft(val value: String) : UiIntent
    data object SubmitPlanRevision : UiIntent
    data object RequestPlanRevision : UiIntent
    data object DismissPlanCompletionPrompt : UiIntent
    data class SelectSettingsSection(val section: SettingsSection) : UiIntent
    data class SelectRuntimeSettingsTab(val tab: RuntimeSettingsTab) : UiIntent
    data class EditSettingsCodexCliPath(val value: String) : UiIntent
    data class EditSettingsClaudeCliPath(val value: String) : UiIntent
    data class EditSettingsNodePath(val value: String) : UiIntent
    data class EditSettingsLanguageMode(val mode: UiLanguageMode) : UiIntent
    data class EditSettingsThemeMode(val mode: UiThemeMode) : UiIntent
    data class EditSettingsUiScaleMode(val mode: UiScaleMode) : UiIntent
    data class EditSettingsAutoContextEnabled(val enabled: Boolean) : UiIntent
    data class EditSettingsBackgroundCompletionNotificationsEnabled(val enabled: Boolean) : UiIntent
    data class EditSettingsCodexCliAutoUpdateCheckEnabled(val enabled: Boolean) : UiIntent
    data object DetectCodexEnvironment : UiIntent
    data object TestCodexEnvironment : UiIntent
    data object CheckCodexCliVersion : UiIntent
    data object UpgradeCodexCli : UiIntent
    data class IgnoreCodexCliVersion(val version: String) : UiIntent
    data object CheckClaudeCliVersion : UiIntent
    data object UpgradeClaudeCli : UiIntent
    data object CreateNewAgentDraft : UiIntent
    data object ShowAgentSettingsList : UiIntent
    data class SelectSavedAgentForEdit(val id: String) : UiIntent
    data class EditAgentDraftName(val value: String) : UiIntent
    data class EditAgentDraftPrompt(val value: String) : UiIntent
    data object SaveAgentDraft : UiIntent
    data class DeleteSavedAgent(val id: String) : UiIntent
    data object LoadSkills : UiIntent
    data object RefreshSkills : UiIntent
    data class ToggleSkillEnabled(val name: String, val path: String, val enabled: Boolean) : UiIntent
    data class OpenSkillPath(val path: String) : UiIntent
    data class RevealSkillPath(val path: String) : UiIntent
    data class UninstallSkill(val name: String, val path: String) : UiIntent
    data object LoadMcpServers : UiIntent
    data object RefreshMcpStatuses : UiIntent
    data object CreateNewMcpDraft : UiIntent
    data object ShowMcpSettingsList : UiIntent
    data class SelectMcpServerForEdit(val name: String) : UiIntent
    data class EditMcpDraftName(val value: String) : UiIntent
    data class EditMcpDraftJson(val value: String) : UiIntent
    data object SaveMcpDraft : UiIntent
    data class ToggleMcpServerEnabled(val name: String, val enabled: Boolean) : UiIntent
    data class DeleteMcpServer(val name: String) : UiIntent
    data class TestMcpServer(val name: String? = null) : UiIntent
    data class LoginMcpServer(val name: String) : UiIntent
    data class LogoutMcpServer(val name: String) : UiIntent
    data object DiscardRuntimeSettingsChanges : UiIntent
    data object SaveSettings : UiIntent
}

internal enum class SubmissionMode {
    AUTO,
    APPROVAL,
}

internal enum class SubmissionReasoning(
    val effort: String,
) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    MAX("xhigh"),
}

internal fun SubmissionMode.localizedLabel(): String = when (this) {
    SubmissionMode.AUTO -> AuraCodeBundle.message("composer.mode.auto")
    SubmissionMode.APPROVAL -> AuraCodeBundle.message("composer.mode.approval")
}

internal fun SubmissionReasoning.localizedLabel(): String = when (this) {
    SubmissionReasoning.LOW -> AuraCodeBundle.message("composer.reasoning.low")
    SubmissionReasoning.MEDIUM -> AuraCodeBundle.message("composer.reasoning.medium")
    SubmissionReasoning.HIGH -> AuraCodeBundle.message("composer.reasoning.high")
    SubmissionReasoning.MAX -> AuraCodeBundle.message("composer.reasoning.max")
}

internal sealed interface AppEvent {
    data class UiIntentPublished(val intent: UiIntent) : AppEvent
    data class SessionSnapshotUpdated(
        val sessions: List<AgentChatService.SessionSummary>,
        val activeSessionId: String,
    ) : AppEvent
    data class HistoryConversationsUpdated(
        val conversations: List<ConversationSummary>,
        val nextCursor: String?,
        val isLoading: Boolean,
        val append: Boolean,
    ) : AppEvent
    data class MentionSuggestionsUpdated(
        val query: String,
        val documentVersion: Long,
        val suggestions: List<MentionSuggestion>,
    ) : AppEvent
    data class AgentSuggestionsUpdated(
        val query: String,
        val documentVersion: Long,
        val suggestions: List<SavedAgentDefinition>,
    ) : AppEvent
    data class SettingsSnapshotUpdated(
        val codexCliPath: String,
        val claudeCliPath: String = "claude",
        val selectedEngineId: String = "codex",
        val availableEngines: List<com.auracode.assistant.provider.EngineDescriptor> = emptyList(),
        val nodePath: String = "",
        val languageMode: UiLanguageMode,
        val themeMode: UiThemeMode,
        val uiScaleMode: UiScaleMode = UiScaleMode.P100,
        val autoContextEnabled: Boolean,
        val backgroundCompletionNotificationsEnabled: Boolean = true,
        val codexCliAutoUpdateCheckEnabled: Boolean = true,
        val savedAgents: List<SavedAgentDefinition>,
        val selectedAgentIds: List<String> = emptyList(),
        val customModelIds: List<String> = emptyList(),
        val selectedModel: String = com.auracode.assistant.provider.codex.CodexModelCatalog.defaultModel,
        val selectedReasoning: String = SubmissionReasoning.MEDIUM.effort,
        val codexCliVersionSnapshot: CodexCliVersionSnapshot = CodexCliVersionSnapshot(),
        val claudeCliVersionSnapshot: ClaudeCliVersionSnapshot = ClaudeCliVersionSnapshot(),
    ) : AppEvent
    data class CodexEnvironmentCheckRunning(
        val running: Boolean,
    ) : AppEvent
    data class CodexEnvironmentCheckUpdated(
        val result: CodexEnvironmentCheckResult,
        val updateDraftPaths: Boolean = false,
    ) : AppEvent
    data class CodexCliVersionSnapshotUpdated(
        val snapshot: CodexCliVersionSnapshot,
    ) : AppEvent
    data class ClaudeCliVersionSnapshotUpdated(
        val snapshot: ClaudeCliVersionSnapshot,
    ) : AppEvent
    data class ClaudeRuntimeExecutableCheckUpdated(
        val result: RuntimeExecutableCheckResult,
    ) : AppEvent
    data class ConversationCapabilitiesUpdated(
        val capabilities: ConversationCapabilities,
    ) : AppEvent
    data class SkillsLoaded(
        val engineId: String,
        val cwd: String,
        val skills: List<SkillRuntimeEntry>,
        val supportsRuntimeSkills: Boolean,
        val stale: Boolean,
        val errorMessage: String? = null,
    ) : AppEvent
    data class SkillsLoadingChanged(
        val loading: Boolean,
        val activePath: String? = null,
    ) : AppEvent
    data class McpServersLoaded(val servers: List<McpServerSummary>) : AppEvent
    data class McpDraftLoaded(val draft: McpServerDraft) : AppEvent
    data class McpStatusesUpdated(val statuses: Map<String, McpRuntimeStatus>) : AppEvent
    data class McpTestResultUpdated(val name: String, val result: McpTestResult) : AppEvent
    data class McpBusyStateUpdated(val state: McpBusyState) : AppEvent
    data class McpValidationErrorsUpdated(val errors: McpValidationErrors) : AppEvent
    data class McpFeedbackUpdated(val message: String, val isError: Boolean) : AppEvent
    data class StatusTextUpdated(val text: UiText) : AppEvent
    data class ConversationOlderLoadingChanged(val loading: Boolean) : AppEvent
    data class ConversationUiProjectionUpdated(
        val nodes: List<ConversationActivityItem>,
        val oldestCursor: String?,
        val hasOlder: Boolean,
        val isRunning: Boolean,
        val latestError: String?,
    ) : AppEvent
    data class ExecutionUiProjectionUpdated(
        val approvals: List<PendingApprovalRequestUiModel>,
        val toolUserInputs: List<ToolUserInputPromptUiModel>,
        val turnStatus: ExecutionTurnStatusUiState?,
    ) : AppEvent
    data class SubmissionUiProjectionUpdated(
        val isRunning: Boolean,
        val editedFiles: List<com.auracode.assistant.toolwindow.submission.EditedFileAggregate>,
    ) : AppEvent
    data class SessionNavigationUiProjectionUpdated(
        val subagents: List<SessionSubagentUiModel>,
    ) : AppEvent
    data class ApprovalRequested(
        val request: PendingApprovalRequestUiModel,
    ) : AppEvent
    data class ApprovalResolved(
        val requestId: String,
    ) : AppEvent
    data object ClearApprovals : AppEvent
    data class ToolUserInputRequested(
        val prompt: ToolUserInputPromptUiModel,
    ) : AppEvent
    data class ToolUserInputResolved(
        val requestId: String,
    ) : AppEvent
    data object ActiveRunCancelled : AppEvent
    data object ClearToolUserInputs : AppEvent
    data class PendingSubmissionsUpdated(
        val submissions: List<PendingSubmission>,
        val clearSubmissionDraft: Boolean = false,
    ) : AppEvent
    data class PlanCompletionPromptUpdated(
        val prompt: PlanCompletionPromptUiModel?,
    ) : AppEvent
    data class RunningPlanUpdated(
        val plan: SubmissionRunningPlanState?,
    ) : AppEvent

    data class PromptAccepted(
        val prompt: String,
        val localTurnId: String? = null,
    ) : AppEvent
    data object ConversationReset : AppEvent
}
