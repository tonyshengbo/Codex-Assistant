package com.codex.assistant.toolwindow.eventing

import com.codex.assistant.model.EngineEvent
import com.codex.assistant.protocol.UnifiedEvent
import com.codex.assistant.service.AgentChatService
import com.codex.assistant.settings.SavedAgentDefinition
import com.codex.assistant.settings.UiLanguageMode
import com.codex.assistant.settings.UiThemeMode
import com.codex.assistant.toolwindow.composer.ContextEntry
import com.codex.assistant.i18n.CodexBundle
import com.codex.assistant.toolwindow.drawer.SettingsSection
import com.codex.assistant.toolwindow.shared.UiText
import com.codex.assistant.toolwindow.timeline.TimelineFileChange
import com.codex.assistant.toolwindow.timeline.TimelineMutation
import com.codex.assistant.toolwindow.timeline.TimelineNode
import androidx.compose.ui.text.input.TextFieldValue

internal sealed interface UiIntent {
    data object NewSession : UiIntent
    data object NewTab : UiIntent
    data object ToggleHistory : UiIntent
    data object ToggleSettings : UiIntent
    data object CloseRightDrawer : UiIntent
    data class DeleteSession(val sessionId: String) : UiIntent
    data class SwitchSession(val sessionId: String) : UiIntent
    data object LoadOlderMessages : UiIntent
    data class ToggleNodeExpanded(val nodeId: String) : UiIntent
    data class OpenTimelineFileChange(val change: TimelineFileChange) : UiIntent
    data class UpdateDocument(val value: TextFieldValue) : UiIntent
    data class InputChanged(val value: String) : UiIntent
    data object SendPrompt : UiIntent
    data object CancelRun : UiIntent
    data object ToggleModeMenu : UiIntent
    data class SelectMode(val mode: ComposerMode) : UiIntent
    data object ToggleModelMenu : UiIntent
    data class SelectModel(val model: String) : UiIntent
    data object ToggleReasoningMenu : UiIntent
    data class SelectReasoning(val reasoning: ComposerReasoning) : UiIntent
    data object OpenAttachmentPicker : UiIntent
    data object PasteImageFromClipboard : UiIntent
    data class AddAttachments(val paths: List<String>) : UiIntent
    data class AddContextFiles(val paths: List<String>) : UiIntent
    data class RemoveAttachment(val id: String) : UiIntent
    data class OpenAttachmentPreview(val id: String) : UiIntent
    data object CloseAttachmentPreview : UiIntent
    data class RemoveContextFile(val path: String) : UiIntent
    data class UpdateFocusedContextFile(val path: String?) : UiIntent
    data class RequestMentionSuggestions(val query: String, val documentVersion: Long) : UiIntent
    data class SelectMentionFile(val path: String) : UiIntent
    data class RemoveMentionFile(val id: String) : UiIntent
    data object MoveMentionSelectionNext : UiIntent
    data object MoveMentionSelectionPrevious : UiIntent
    data object DismissMentionPopup : UiIntent
    data class RequestAgentSuggestions(val query: String, val documentVersion: Long) : UiIntent
    data class SelectAgent(val agent: SavedAgentDefinition) : UiIntent
    data class RemoveSelectedAgent(val id: String) : UiIntent
    data object MoveAgentSelectionNext : UiIntent
    data object MoveAgentSelectionPrevious : UiIntent
    data object DismissAgentPopup : UiIntent
    data class SelectSettingsSection(val section: SettingsSection) : UiIntent
    data class EditSettingsCodexCliPath(val value: String) : UiIntent
    data class EditSettingsLanguageMode(val mode: UiLanguageMode) : UiIntent
    data class EditSettingsThemeMode(val mode: UiThemeMode) : UiIntent
    data object CreateNewAgentDraft : UiIntent
    data object ShowAgentSettingsList : UiIntent
    data class SelectSavedAgentForEdit(val id: String) : UiIntent
    data class EditAgentDraftName(val value: String) : UiIntent
    data class EditAgentDraftPrompt(val value: String) : UiIntent
    data object SaveAgentDraft : UiIntent
    data class DeleteSavedAgent(val id: String) : UiIntent
    data object SaveSettings : UiIntent
}

internal enum class ComposerMode {
    AUTO,
    APPROVAL,
}

internal enum class ComposerReasoning(
    val effort: String,
) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    MAX("high"),
}

internal fun ComposerMode.localizedLabel(): String = when (this) {
    ComposerMode.AUTO -> CodexBundle.message("composer.mode.auto")
    ComposerMode.APPROVAL -> CodexBundle.message("composer.mode.approval")
}

internal fun ComposerReasoning.localizedLabel(): String = when (this) {
    ComposerReasoning.LOW -> CodexBundle.message("composer.reasoning.low")
    ComposerReasoning.MEDIUM -> CodexBundle.message("composer.reasoning.medium")
    ComposerReasoning.HIGH -> CodexBundle.message("composer.reasoning.high")
    ComposerReasoning.MAX -> CodexBundle.message("composer.reasoning.max")
}

internal sealed interface AppEvent {
    data class UiIntentPublished(val intent: UiIntent) : AppEvent
    data class UnifiedEventPublished(val event: UnifiedEvent) : AppEvent
    data class TimelineMutationApplied(val mutation: TimelineMutation) : AppEvent
    data class EngineEventPublished(val event: EngineEvent) : AppEvent
    data class SessionSnapshotUpdated(
        val sessions: List<AgentChatService.SessionSummary>,
        val activeSessionId: String,
    ) : AppEvent
    data class MentionSuggestionsUpdated(
        val query: String,
        val documentVersion: Long,
        val suggestions: List<ContextEntry>,
    ) : AppEvent
    data class AgentSuggestionsUpdated(
        val query: String,
        val documentVersion: Long,
        val suggestions: List<SavedAgentDefinition>,
    ) : AppEvent
    data class SettingsSnapshotUpdated(
        val codexCliPath: String,
        val languageMode: UiLanguageMode,
        val themeMode: UiThemeMode,
        val savedAgents: List<SavedAgentDefinition>,
    ) : AppEvent
    data class StatusTextUpdated(val text: UiText) : AppEvent
    data class TimelineOlderLoadingChanged(val loading: Boolean) : AppEvent
    data class TimelineHistoryLoaded(
        val nodes: List<TimelineNode>,
        val oldestCursor: Long?,
        val hasOlder: Boolean,
        val prepend: Boolean,
    ) : AppEvent

    data class PromptAccepted(val prompt: String) : AppEvent
    data object ConversationReset : AppEvent
}
