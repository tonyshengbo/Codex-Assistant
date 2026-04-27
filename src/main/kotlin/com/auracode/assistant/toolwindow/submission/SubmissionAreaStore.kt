package com.auracode.assistant.toolwindow.submission

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.model.ContextFile
import com.auracode.assistant.model.FileAttachment
import com.auracode.assistant.model.ImageAttachment
import com.auracode.assistant.model.TurnUsageSnapshot
import com.auracode.assistant.persistence.chat.PersistedMessageAttachment
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.claude.ClaudeModelCatalog
import com.auracode.assistant.provider.codex.CodexModelCatalog
import com.auracode.assistant.settings.SavedAgentDefinition
import com.auracode.assistant.toolwindow.engineDisplayLabel
import com.auracode.assistant.toolwindow.execution.PendingApprovalRequestUiModel
import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.eventing.SubmissionMode
import com.auracode.assistant.toolwindow.eventing.SubmissionReasoning
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.execution.PlanCompletionPromptUiModel
import com.auracode.assistant.toolwindow.execution.compactPreview
import com.auracode.assistant.toolwindow.conversation.ConversationFileChange
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.name
import kotlin.math.min

internal data class ContextEntry(
    val path: String,
    val displayName: String,
    val tailPath: String = "",
    val selectedText: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val isSelectionContext: Boolean = false,
)

internal data class FocusedContextSnapshot(
    val path: String,
    val selectedText: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
)

internal data class MentionEntry(
    val id: String,
    val kind: MentionEntryKind = MentionEntryKind.FILE,
    val path: String,
    val displayName: String,
    val start: Int = 0,
    val endExclusive: Int = 0,
)

internal enum class MentionEntryKind {
    FILE,
    AGENT,
}

internal enum class AttachmentKind {
    TEXT,
    IMAGE,
    BINARY,
}

internal data class AttachmentEntry(
    val id: String,
    val path: String,
    val displayName: String,
    val tailPath: String = "",
    val kind: AttachmentKind,
    val sizeBytes: Long = 0L,
    val mimeType: String = "",
)

internal data class MentionLookupRequest(
    val query: String,
    val documentVersion: Long,
)

internal data class SubmissionLookupRequest(
    val mention: MentionLookupRequest? = null,
    val agentQuery: String? = null,
    val documentVersion: Long = 0L,
)

internal data class AgentContextEntry(
    val id: String,
    val name: String,
    val prompt: String,
)

internal data class SubmissionPlanCompletionState(
    val turnId: String,
    val threadId: String?,
    val preferredExecutionMode: SubmissionMode,
    val planTitle: String,
    val planSummary: String,
    val planBody: String,
    val revisionDraft: String = "",
    val selectedAction: PlanCompletionAction = PlanCompletionAction.EXECUTE,
)

internal enum class SubmissionRunningPlanStepStatus {
    COMPLETED,
    IN_PROGRESS,
    PENDING,
}

internal data class SubmissionRunningPlanStep(
    val step: String,
    val status: SubmissionRunningPlanStepStatus,
)

internal data class SubmissionRunningPlanState(
    val threadId: String?,
    val turnId: String,
    val explanation: String?,
    val steps: List<SubmissionRunningPlanStep>,
)

internal data class SubmissionModelOption(
    val id: String,
    val shortName: String,
    val isCustom: Boolean,
)

internal enum class PlanCompletionAction {
    EXECUTE,
    CANCEL,
    REVISION,
}

internal enum class SubmissionInteractionCardKind {
    APPROVAL,
    TOOL_USER_INPUT,
    PLAN_COMPLETION,
}

internal data class SubmissionInteractionCard(
    val kind: SubmissionInteractionCardKind,
)

/**
 * Describes a pending engine switch that requires confirmation before branching sessions.
 */
internal data class EngineSwitchConfirmationState(
    val targetEngineId: String,
    val targetEngineLabel: String,
)

internal data class PendingSubmission(
    val id: String,
    val prompt: String,
    val systemInstructions: List<String>,
    val contextFiles: List<ContextFile>,
    val imageAttachments: List<ImageAttachment>,
    val fileAttachments: List<FileAttachment>,
    val stagedAttachments: List<PersistedMessageAttachment>,
    val selectedModel: String,
    val selectedReasoning: SubmissionReasoning,
    val executionMode: SubmissionMode,
    val planEnabled: Boolean,
    val engineId: String = "codex",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val summary: String
        get() = prompt.lineSequence().firstOrNull { it.isNotBlank() }
            ?: systemInstructions.firstOrNull { it.isNotBlank() }
            ?: AuraCodeBundle.message("composer.pending.summary.empty")

    val totalAttachmentCount: Int
        get() = stagedAttachments.size
}

internal data class SubmissionAreaState(
    val currentSessionId: String? = null,
    val document: TextFieldValue = TextFieldValue(""),
    val documentVersion: Long = 0L,
    val executionMode: SubmissionMode = SubmissionMode.AUTO,
    val planEnabled: Boolean = false,
    val planModeAvailable: Boolean = true,
    val capabilityHint: String? = null,
    val disabledCapabilityReason: String? = null,
    val selectedEngineId: String = "codex",
    val availableEngines: List<EngineDescriptor> = emptyList(),
    val engineMenuExpanded: Boolean = false,
    val engineSwitchConfirmation: EngineSwitchConfirmationState? = null,
    val selectedModel: String = CodexModelCatalog.defaultModel,
    val selectedReasoning: SubmissionReasoning = SubmissionReasoning.MEDIUM,
    val autoContextEnabled: Boolean = true,
    val customModelIds: List<String> = emptyList(),
    val addingCustomModel: Boolean = false,
    val customModelDraft: String = "",
    val modelMenuExpanded: Boolean = false,
    val reasoningMenuExpanded: Boolean = false,
    val manualContextEntries: List<ContextEntry> = emptyList(),
    val attachments: List<AttachmentEntry> = emptyList(),
    val focusedContextEntry: ContextEntry? = null,
    val contextEntries: List<ContextEntry> = emptyList(),
    val agentEntries: List<AgentContextEntry> = emptyList(),
    val previewAttachmentId: String? = null,
    val mentionEntries: List<MentionEntry> = emptyList(),
    val mentionQuery: String = "",
    val mentionSuggestions: List<MentionSuggestion> = emptyList(),
    val mentionPopupVisible: Boolean = false,
    val activeMentionIndex: Int = 0,
    val sessionSubagents: List<SessionSubagentUiModel> = emptyList(),
    val subagentTrayExpanded: Boolean = false,
    val subagentTrayDismissed: Boolean = false,
    val selectedSubagentThreadId: String? = null,
    val agentQuery: String = "",
    val agentSuggestions: List<SavedAgentDefinition> = emptyList(),
    val agentPopupVisible: Boolean = false,
    val activeAgentIndex: Int = 0,
    val slashQuery: String = "",
    val slashSuggestions: List<SlashSuggestionItem> = emptyList(),
    val slashPopupVisible: Boolean = false,
    val activeSlashIndex: Int = 0,
    val editedFiles: List<EditedFileAggregate> = emptyList(),
    val editedFilesExpanded: Boolean = false,
    val usageSnapshot: TurnUsageSnapshot? = null,
    val activeSessionMessageCount: Int? = null,
    val sessionIsRunning: Boolean = false,
    val pendingApprovalCount: Int = 0,
    val pendingToolInputCount: Int = 0,
    val approvalQueue: List<PendingApprovalRequestUiModel> = emptyList(),
    val approvalPrompt: PendingApprovalRequestUiModel? = null,
    val toolUserInputQueue: List<ToolUserInputPromptUiModel> = emptyList(),
    val toolUserInputPrompt: ToolUserInputPromptUiModel? = null,
    val runningPlan: SubmissionRunningPlanState? = null,
    val runningPlanExpanded: Boolean = true,
    val planCompletion: SubmissionPlanCompletionState? = null,
    val pendingSubmissions: List<PendingSubmission> = emptyList(),
) {
    val subagentTrayVisible: Boolean
        get() = sessionSubagents.isNotEmpty() && !subagentTrayDismissed

    /**
     * Exposes the currently selected subagent details row for the tray detail card.
     */
    val selectedSubagent: SessionSubagentUiModel?
        get() = sessionSubagents.firstOrNull { it.threadId == selectedSubagentThreadId }

    val modelOptions: List<SubmissionModelOption>
        get() = submissionModelOptions(
            engineId = selectedEngineId,
            builtInModelIds = availableEngines.firstOrNull { it.id == selectedEngineId }?.models
                ?: defaultModelIdsForEngine(selectedEngineId),
            customModelIds = customModelIds,
        )

    /**
     * 返回当前选中模型对应的展示选项。
     *
     * 状态和提交流程仍继续持有原始模型 id，这里只负责给 UI 提供短名称。
     */
    val selectedModelOption: SubmissionModelOption?
        get() = modelOptions.firstOrNull { it.id == selectedModel }

    val inputText: String
        get() = normalizePromptBody(removeMentionRanges(document.text, mentionEntries, setOf(MentionEntryKind.FILE)))

    /**
     * 空态提示区域已移除，因此这里始终返回空，避免旧逻辑继续驱动 UI。
     */
    val emptyStateHint: String?
        get() = null

    /**
     * 根据当前引擎能力决定是否显示推理等级选择器。
     */
    val reasoningSelectorVisible: Boolean
        get() = availableEngines
            .firstOrNull { it.id == selectedEngineId }
            ?.capabilities
            ?.supportsReasoningEffortSelection
            ?: true

    fun serializedPrompt(): String {
        val mentionBlock = mentionEntries
            .filter { it.kind == MentionEntryKind.FILE }
            .sortedBy { it.start }
            .joinToString("\n") { it.path.trim() }
            .trim()
        val textBlock = normalizePromptBody(removeMentionRanges(document.text, mentionEntries, setOf(MentionEntryKind.FILE)))
        return when {
            mentionBlock.isBlank() -> textBlock
            textBlock.isBlank() -> mentionBlock
            else -> "$mentionBlock\n\n$textBlock"
        }
    }

    fun serializedSystemInstructions(): List<String> = agentEntries.mapNotNull { it.prompt.trim().takeIf(String::isNotBlank) }

    fun hasPromptContent(): Boolean = serializedPrompt().isNotBlank() || agentEntries.isNotEmpty()

    val editedFilesSummary: EditedFilesSummary
        get() = EditedFilesSummary(
            total = editedFiles.size,
        )

    val activeInteractionCard: SubmissionInteractionCard?
        get() = when {
            approvalPrompt != null -> SubmissionInteractionCard(SubmissionInteractionCardKind.APPROVAL)
            toolUserInputPrompt != null -> SubmissionInteractionCard(SubmissionInteractionCardKind.TOOL_USER_INPUT)
            planCompletion != null -> SubmissionInteractionCard(SubmissionInteractionCardKind.PLAN_COMPLETION)
            else -> null
        }
}

internal class SubmissionAreaStore(
    private val availableSkillsProvider: () -> List<SlashSkillDescriptor> = ::discoverAvailableSkills,
) {
    companion object {
        const val MAX_CONTEXT_FILES: Int = 10
        const val MAX_ATTACHMENTS: Int = 10
        const val MAX_IMAGE_BYTES: Long = 20L * 1024L * 1024L
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        private val TEXT_EXTENSIONS = setOf(
            "kt", "kts", "java", "md", "txt", "json", "xml", "yaml", "yml", "gradle",
            "js", "ts", "tsx", "jsx", "py", "sql", "properties", "toml", "sh", "log",
        )
    }

    private val _state = MutableStateFlow(SubmissionAreaState())
    val state: StateFlow<SubmissionAreaState> = _state.asStateFlow()

    fun restoreState(state: SubmissionAreaState) {
        _state.value = state
    }

    fun onEvent(event: AppEvent) {
        when (event) {
            is AppEvent.UiIntentPublished -> {
                when (val intent = event.intent) {
                    is UiIntent.UpdateDocument -> applyDocumentUpdate(intent.value)
                    is UiIntent.InputChanged -> applyDocumentUpdate(
                        TextFieldValue(intent.value, TextRange(intent.value.length)),
                    )
                    UiIntent.ToggleEngineMenu -> _state.value = _state.value.copy(
                        engineMenuExpanded = !_state.value.engineMenuExpanded,
                        modelMenuExpanded = false,
                        reasoningMenuExpanded = false,
                    )
                    is UiIntent.RequestEngineSwitch -> {
                        _state.value = _state.value.copy(
                            engineMenuExpanded = false,
                            modelMenuExpanded = false,
                            reasoningMenuExpanded = false,
                            engineSwitchConfirmation = null,
                        )
                    }
                    is UiIntent.SelectEngine -> {
                        val nextEngineId = intent.engineId.trim().ifBlank { _state.value.selectedEngineId }
                        val nextBuiltInModels = _state.value.availableEngines.firstOrNull { it.id == nextEngineId }?.models
                            ?: defaultModelIdsForEngine(nextEngineId)
                        val nextAvailableModels = submissionModelOptions(
                            engineId = nextEngineId,
                            builtInModelIds = nextBuiltInModels,
                            customModelIds = _state.value.customModelIds,
                        ).map { it.id }.toSet()
                        _state.value = _state.value.copy(
                            selectedEngineId = nextEngineId,
                            selectedModel = _state.value.selectedModel.takeIf { it in nextAvailableModels }
                                ?: defaultModelForEngine(nextEngineId),
                            engineMenuExpanded = false,
                            modelMenuExpanded = false,
                            engineSwitchConfirmation = null,
                        )
                    }
                    UiIntent.DismissEngineSwitchDialog -> _state.value = _state.value.copy(
                        engineSwitchConfirmation = null,
                    )
                    UiIntent.ToggleExecutionMode -> _state.value = _state.value.toggleExecutionMode()
                    is UiIntent.SelectMode -> _state.value = _state.value.copy(
                        executionMode = intent.mode,
                    )
                    UiIntent.TogglePlanMode -> _state.value = _state.value.let { current ->
                        if (!current.planModeAvailable) {
                            current
                        } else {
                            current.copy(planEnabled = !current.planEnabled)
                        }
                    }
                    UiIntent.ToggleModelMenu -> _state.value = _state.value.copy(
                        modelMenuExpanded = !_state.value.modelMenuExpanded,
                        engineMenuExpanded = false,
                        addingCustomModel = if (_state.value.modelMenuExpanded) false else _state.value.addingCustomModel,
                        customModelDraft = if (_state.value.modelMenuExpanded) "" else _state.value.customModelDraft,
                        reasoningMenuExpanded = false,
                    )
                    is UiIntent.SelectModel -> _state.value = _state.value.copy(
                        selectedModel = intent.model,
                        modelMenuExpanded = false,
                        addingCustomModel = false,
                        customModelDraft = "",
                    )
                    UiIntent.StartAddingCustomModel -> _state.value = _state.value.copy(
                        addingCustomModel = true,
                        customModelDraft = "",
                    )
                    is UiIntent.EditCustomModelDraft -> _state.value = _state.value.copy(
                        customModelDraft = intent.value,
                    )
                    UiIntent.CancelAddingCustomModel -> _state.value = _state.value.copy(
                        addingCustomModel = false,
                        customModelDraft = "",
                    )
                    UiIntent.ToggleReasoningMenu -> _state.value = _state.value.copy(
                        reasoningMenuExpanded = !_state.value.reasoningMenuExpanded,
                        engineMenuExpanded = false,
                        modelMenuExpanded = false,
                        addingCustomModel = false,
                        customModelDraft = "",
                    )
                    is UiIntent.SelectReasoning -> _state.value = _state.value.copy(
                        selectedReasoning = intent.reasoning,
                        reasoningMenuExpanded = false,
                    )
                    is UiIntent.EditPlanRevisionDraft -> _state.value = _state.value.copy(
                        planCompletion = _state.value.planCompletion?.copy(revisionDraft = intent.value),
                    )
                    is UiIntent.SelectPlanCompletionAction -> _state.value = _state.value.copy(
                        planCompletion = _state.value.planCompletion?.copy(selectedAction = intent.action),
                    )
                    UiIntent.MovePlanCompletionSelectionNext -> _state.value = _state.value.copy(
                        planCompletion = _state.value.planCompletion?.copy(
                            selectedAction = (_state.value.planCompletion?.selectedAction ?: PlanCompletionAction.EXECUTE).next(),
                        ),
                    )
                    UiIntent.MovePlanCompletionSelectionPrevious -> _state.value = _state.value.copy(
                        planCompletion = _state.value.planCompletion?.copy(
                            selectedAction = (_state.value.planCompletion?.selectedAction ?: PlanCompletionAction.EXECUTE).previous(),
                        ),
                    )
                    UiIntent.ToggleRunningPlanExpanded -> {
                        if (_state.value.runningPlan != null) {
                            _state.value = _state.value.copy(
                                runningPlanExpanded = !_state.value.runningPlanExpanded,
                            )
                        }
                    }
                    is UiIntent.AddAttachments -> addAttachments(intent.paths)
                    is UiIntent.AddContextFiles -> addContextFiles(intent.paths)
                    is UiIntent.RemoveAttachment -> _state.value = _state.value.copy(
                        attachments = _state.value.attachments.filterNot { it.id == intent.id },
                        previewAttachmentId = _state.value.previewAttachmentId?.takeUnless { it == intent.id },
                    ).withResolvedContextEntries()
                    is UiIntent.OpenAttachmentPreview -> _state.value = _state.value.copy(previewAttachmentId = intent.id)
                    UiIntent.CloseAttachmentPreview -> _state.value = _state.value.copy(previewAttachmentId = null)
                    is UiIntent.UpdateFocusedContextFile -> {
                        val current = _state.value
                        _state.value = if (!current.autoContextEnabled) {
                            current.copy(focusedContextEntry = null).withResolvedContextEntries()
                        } else {
                            current.copy(
                                focusedContextEntry = intent.snapshot?.toContextEntry(),
                            ).withResolvedContextEntries()
                        }
                    }
                    is UiIntent.EditSettingsAutoContextEnabled -> _state.value = _state.value.copy(
                        autoContextEnabled = intent.enabled,
                        focusedContextEntry = if (intent.enabled) _state.value.focusedContextEntry else null,
                    ).withResolvedContextEntries()
                    is UiIntent.RemoveContextFile -> _state.value = _state.value.copy(
                        manualContextEntries = _state.value.manualContextEntries.filterNot { it.path == intent.path },
                        focusedContextEntry = _state.value.focusedContextEntry?.takeUnless { it.path == intent.path },
                    ).withResolvedContextEntries()
                    UiIntent.ToggleEditedFilesExpanded -> _state.value = _state.value.copy(
                        editedFilesExpanded = !_state.value.editedFilesExpanded && _state.value.editedFiles.isNotEmpty(),
                    )
                    is UiIntent.AcceptEditedFile -> _state.value = _state.value.removeEditedFile(intent.path)
                    UiIntent.AcceptAllEditedFiles -> _state.value = _state.value.copy(
                        editedFiles = emptyList(),
                        editedFilesExpanded = false,
                    )
                    is UiIntent.SelectMentionFile -> addMention(intent.path)
                    is UiIntent.SelectSessionSubagentMention -> addSubagentMention(intent.threadId)
                    is UiIntent.ToggleSubagentDetails -> toggleSubagentDetails(intent.threadId)
                    is UiIntent.RemoveMentionFile -> removeMention(intent.id)
                    is UiIntent.SelectAgent -> addAgent(intent.agent)
                    is UiIntent.RemoveSelectedAgent -> _state.value = _state.value.copy(
                        agentEntries = _state.value.agentEntries.filterNot { it.id == intent.id },
                    )
                    UiIntent.ToggleSubagentTrayExpanded -> _state.value = _state.value.copy(
                        subagentTrayExpanded = !_state.value.subagentTrayExpanded && _state.value.sessionSubagents.isNotEmpty(),
                    )
                    UiIntent.DismissSubagentTray -> _state.value = _state.value.copy(
                        subagentTrayDismissed = true,
                        subagentTrayExpanded = false,
                        selectedSubagentThreadId = null,
                    )
                    UiIntent.MoveMentionSelectionNext -> {
                        val size = _state.value.mentionSuggestions.size
                        if (size > 0) {
                            _state.value = _state.value.copy(activeMentionIndex = (_state.value.activeMentionIndex + 1) % size)
                        }
                    }
                    UiIntent.MoveMentionSelectionPrevious -> {
                        val size = _state.value.mentionSuggestions.size
                        if (size > 0) {
                            _state.value = _state.value.copy(activeMentionIndex = (_state.value.activeMentionIndex - 1 + size) % size)
                        }
                    }
                    UiIntent.DismissMentionPopup -> _state.value = _state.value.copy(
                        mentionPopupVisible = false,
                        mentionSuggestions = emptyList(),
                        mentionQuery = "",
                        activeMentionIndex = 0,
                    )
                    UiIntent.MoveAgentSelectionNext -> {
                        val size = _state.value.agentSuggestions.size
                        if (size > 0) {
                            _state.value = _state.value.copy(activeAgentIndex = (_state.value.activeAgentIndex + 1) % size)
                        }
                    }
                    UiIntent.MoveAgentSelectionPrevious -> {
                        val size = _state.value.agentSuggestions.size
                        if (size > 0) {
                            _state.value = _state.value.copy(activeAgentIndex = (_state.value.activeAgentIndex - 1 + size) % size)
                        }
                    }
                    UiIntent.DismissAgentPopup -> _state.value = _state.value.copy(
                        agentPopupVisible = false,
                        agentSuggestions = emptyList(),
                        agentQuery = "",
                        activeAgentIndex = 0,
                    )
                    UiIntent.MoveSlashSelectionNext -> {
                        val size = _state.value.slashSuggestions.size
                        if (size > 0) {
                            _state.value = _state.value.copy(activeSlashIndex = (_state.value.activeSlashIndex + 1) % size)
                        }
                    }
                    UiIntent.MoveSlashSelectionPrevious -> {
                        val size = _state.value.slashSuggestions.size
                        if (size > 0) {
                            _state.value = _state.value.copy(activeSlashIndex = (_state.value.activeSlashIndex - 1 + size) % size)
                        }
                    }
                    UiIntent.DismissSlashPopup -> dismissSlashPopup()
                    is UiIntent.SelectSlashCommand -> applySlashCommand(intent.command)
                    is UiIntent.SelectSlashSkill -> applySlashSkill(intent.name)
                    UiIntent.DismissPlanCompletionPrompt -> _state.value = _state.value.copy(planCompletion = null)
                    else -> Unit
                }
            }
            is AppEvent.MentionSuggestionsUpdated -> {
                val current = _state.value
                if (
                    event.documentVersion != current.documentVersion ||
                    event.query != current.mentionQuery ||
                    current.document.composition != null
                ) {
                    return
                }
                _state.value = current.copy(
                    mentionSuggestions = event.suggestions,
                    mentionPopupVisible = event.suggestions.isNotEmpty(),
                    activeMentionIndex = 0,
                )
            }

            is AppEvent.SessionNavigationUiProjectionUpdated -> {
                val current = _state.value
                val nextSubagents = sortSessionSubagents(event.subagents)
                val shouldRestoreDismissedTray = hasNewSubagentThreads(
                    previous = current.sessionSubagents,
                    next = nextSubagents,
                )
                _state.value = current.copy(
                    sessionSubagents = nextSubagents,
                    subagentTrayExpanded = current.subagentTrayExpanded &&
                        nextSubagents.isNotEmpty(),
                    subagentTrayDismissed = if (shouldRestoreDismissedTray) false else current.subagentTrayDismissed,
                    selectedSubagentThreadId = current.selectedSubagentThreadId
                        ?.takeIf { selectedThreadId -> nextSubagents.any { it.threadId == selectedThreadId } },
                    mentionSuggestions = current.mentionSuggestions.mapNotNull { suggestion ->
                        when (suggestion) {
                            is MentionSuggestion.File -> suggestion
                            is MentionSuggestion.Agent -> nextSubagents
                                .firstOrNull { it.threadId == suggestion.agent.threadId }
                                ?.let(MentionSuggestion::Agent)
                        }
                    },
                    mentionPopupVisible = current.mentionPopupVisible &&
                        current.mentionQuery.isNotBlank() &&
                        (current.mentionSuggestions.isNotEmpty() || nextSubagents.isNotEmpty()),
                )
            }

            is AppEvent.ConversationCapabilitiesUpdated -> {
                val supportsPlanMode = event.capabilities.supportsPlanMode
                val disabledReason = if (supportsPlanMode) {
                    null
                } else {
                    AuraCodeBundle.message(
                        "composer.capability.planUnavailable",
                        engineDisplayLabel(_state.value.selectedEngineId, _state.value.availableEngines),
                    )
                }
                _state.value = _state.value.copy(
                    planModeAvailable = supportsPlanMode,
                    planEnabled = if (supportsPlanMode) _state.value.planEnabled else false,
                    capabilityHint = disabledReason,
                    disabledCapabilityReason = disabledReason,
                )
            }

            is AppEvent.AgentSuggestionsUpdated -> {
                val current = _state.value
                if (
                    event.documentVersion != current.documentVersion ||
                    event.query != current.agentQuery ||
                    current.document.composition != null
                ) {
                    return
                }
                _state.value = current.copy(
                    agentSuggestions = event.suggestions,
                    agentPopupVisible = event.suggestions.isNotEmpty(),
                    activeAgentIndex = 0,
                )
            }

            is AppEvent.ExecutionUiProjectionUpdated -> {
                _state.value = _state.value.copy(
                    approvalQueue = event.approvals.reindexedApprovalQueue(),
                    approvalPrompt = event.approvals.firstOrNull(),
                    toolUserInputQueue = event.toolUserInputs.reindexedToolInputQueue(),
                    toolUserInputPrompt = event.toolUserInputs.firstOrNull(),
                )
            }

            is AppEvent.SubmissionUiProjectionUpdated -> {
                _state.value = _state.value.copy(
                    sessionIsRunning = event.isRunning,
                    editedFiles = event.editedFiles,
                    editedFilesExpanded = _state.value.editedFilesExpanded && event.editedFiles.isNotEmpty(),
                )
            }

            is AppEvent.PromptAccepted -> {
                _state.value = _state.value.clearSubmissionDraft(clearInteractionQueues = true)
            }

            is AppEvent.ApprovalRequested -> {
                val queue = (_state.value.approvalQueue + event.request).reindexedApprovalQueue()
                _state.value = _state.value.copy(
                    approvalQueue = queue,
                    approvalPrompt = queue.firstOrNull(),
                )
            }

            is AppEvent.ApprovalResolved -> {
                val queue = _state.value.approvalQueue
                    .filterNot { it.requestId == event.requestId }
                    .reindexedApprovalQueue()
                _state.value = _state.value.copy(
                    approvalQueue = queue,
                    approvalPrompt = queue.firstOrNull(),
                )
            }

            AppEvent.ClearApprovals -> _state.value = _state.value.copy(
                approvalQueue = emptyList(),
                approvalPrompt = null,
            )

            is AppEvent.ToolUserInputRequested -> {
                val queue = (_state.value.toolUserInputQueue + event.prompt).reindexedToolInputQueue()
                _state.value = _state.value.copy(
                    toolUserInputQueue = queue,
                    toolUserInputPrompt = queue.firstOrNull(),
                )
            }

            is AppEvent.ToolUserInputResolved -> {
                val queue = _state.value.toolUserInputQueue
                    .filterNot { it.requestId == event.requestId }
                    .reindexedToolInputQueue()
                _state.value = _state.value.copy(
                    toolUserInputQueue = queue,
                    toolUserInputPrompt = queue.firstOrNull(),
                )
            }

            AppEvent.ClearToolUserInputs -> _state.value = _state.value.copy(
                toolUserInputQueue = emptyList(),
                toolUserInputPrompt = null,
            )

            is AppEvent.PlanCompletionPromptUpdated -> {
                _state.value = _state.value.copy(
                    runningPlan = null,
                    runningPlanExpanded = true,
                    planCompletion = event.prompt?.toSubmissionPlanCompletionState(),
                )
            }

            is AppEvent.RunningPlanUpdated -> {
                val currentTurnId = _state.value.runningPlan?.turnId
                val incomingTurnId = event.plan?.turnId
                _state.value = _state.value.copy(
                    runningPlan = event.plan,
                    runningPlanExpanded = when {
                        event.plan == null -> true
                        currentTurnId == null -> true
                        currentTurnId != incomingTurnId -> true
                        else -> _state.value.runningPlanExpanded
                    },
                )
            }

            is AppEvent.SessionSnapshotUpdated -> {
                val activeSession = event.sessions.firstOrNull { it.id == event.activeSessionId }
                val activeEngineId = activeSession?.providerId?.trim()?.takeIf(String::isNotBlank)
                val selectedEngineId = activeEngineId ?: _state.value.selectedEngineId
                val builtInModelIds = _state.value.availableEngines.firstOrNull { it.id == selectedEngineId }?.models
                    ?: defaultModelIdsForEngine(selectedEngineId)
                val availableModelIds = submissionModelOptions(
                    engineId = selectedEngineId,
                    builtInModelIds = builtInModelIds,
                    customModelIds = _state.value.customModelIds,
                ).map { it.id }.toSet()
                val switchedSession = _state.value.currentSessionId != null &&
                    _state.value.currentSessionId != event.activeSessionId
                val filteredMentionSuggestions = if (switchedSession) {
                    _state.value.mentionSuggestions.filterIsInstance<MentionSuggestion.File>()
                } else {
                    _state.value.mentionSuggestions
                }
                _state.value = _state.value.copy(
                    currentSessionId = event.activeSessionId,
                    selectedEngineId = selectedEngineId,
                    selectedModel = _state.value.selectedModel.takeIf { it in availableModelIds }
                        ?: defaultModelForEngine(selectedEngineId),
                    usageSnapshot = activeSession?.usageSnapshot,
                    activeSessionMessageCount = activeSession?.messageCount,
                    engineSwitchConfirmation = null,
                    sessionSubagents = if (switchedSession) emptyList() else _state.value.sessionSubagents,
                    subagentTrayExpanded = if (switchedSession) false else _state.value.subagentTrayExpanded,
                    subagentTrayDismissed = if (switchedSession) false else _state.value.subagentTrayDismissed,
                    selectedSubagentThreadId = if (switchedSession) null else _state.value.selectedSubagentThreadId,
                    mentionSuggestions = filteredMentionSuggestions,
                    mentionPopupVisible = _state.value.mentionPopupVisible && filteredMentionSuggestions.isNotEmpty(),
                    activeMentionIndex = if (
                        filteredMentionSuggestions.isEmpty() ||
                        _state.value.activeMentionIndex >= filteredMentionSuggestions.size
                    ) {
                        0
                    } else {
                        _state.value.activeMentionIndex
                    },
                )
            }

            is AppEvent.PendingSubmissionsUpdated -> {
                _state.value = _state.value
                    .copy(pendingSubmissions = event.submissions)
                    .let { current ->
                        if (event.clearSubmissionDraft) {
                            current.clearSubmissionDraft(clearInteractionQueues = false)
                        } else {
                            current
                        }
                    }
            }

            is AppEvent.SettingsSnapshotUpdated -> {
                val selectedEngineId = event.selectedEngineId.trim().ifBlank { "codex" }
                val builtInModelIds = event.availableEngines.firstOrNull { it.id == selectedEngineId }?.models
                    ?: defaultModelIdsForEngine(selectedEngineId)
                val availableModelIds = submissionModelOptions(
                    engineId = selectedEngineId,
                    builtInModelIds = builtInModelIds,
                    customModelIds = event.customModelIds,
                ).map { it.id }.toSet()
                val trimmedDraft = _state.value.customModelDraft.trim()
                val selectedModel = event.selectedModel.takeIf { it in availableModelIds }
                    ?: defaultModelForEngine(selectedEngineId)
                val selectedReasoning = SubmissionReasoning.entries
                    .firstOrNull { it.effort == event.selectedReasoning }
                    ?: SubmissionReasoning.MEDIUM
                val restoredAgents = restoreSelectedAgents(
                    savedAgents = event.savedAgents,
                    selectedAgentIds = event.selectedAgentIds,
                )
                _state.value = _state.value.copy(
                    autoContextEnabled = event.autoContextEnabled,
                    focusedContextEntry = if (event.autoContextEnabled) _state.value.focusedContextEntry else null,
                    agentEntries = restoredAgents,
                    selectedEngineId = selectedEngineId,
                    availableEngines = event.availableEngines,
                    engineMenuExpanded = false,
                    engineSwitchConfirmation = null,
                    customModelIds = event.customModelIds,
                    selectedModel = selectedModel,
                    selectedReasoning = selectedReasoning,
                    addingCustomModel = _state.value.addingCustomModel &&
                        trimmedDraft.isNotBlank() &&
                        trimmedDraft !in availableModelIds,
                    customModelDraft = if (
                        _state.value.addingCustomModel &&
                        trimmedDraft.isNotBlank() &&
                        trimmedDraft in availableModelIds
                    ) {
                        ""
                    } else {
                        _state.value.customModelDraft
                    },
                ).withResolvedContextEntries()
            }

            AppEvent.ConversationReset -> _state.value = SubmissionAreaState(
                currentSessionId = _state.value.currentSessionId,
                autoContextEnabled = _state.value.autoContextEnabled,
                agentEntries = _state.value.agentEntries,
                selectedEngineId = _state.value.selectedEngineId,
                availableEngines = _state.value.availableEngines,
                customModelIds = _state.value.customModelIds,
                selectedModel = _state.value.selectedModel,
                selectedReasoning = _state.value.selectedReasoning,
                activeSessionMessageCount = null,
            )

            else -> Unit
        }
    }

    private fun PlanCompletionPromptUiModel.toSubmissionPlanCompletionState(): SubmissionPlanCompletionState {
        val preview = compactPreview()
        val title = preview.title.ifBlank {
            AuraCodeBundle.message("composer.planCompletion.defaultTitle")
        }
        return SubmissionPlanCompletionState(
            turnId = turnId,
            threadId = threadId,
            preferredExecutionMode = preferredExecutionMode,
            planTitle = title,
            planSummary = preview.summary,
            planBody = body,
            revisionDraft = "",
        )
    }

    fun applyDocumentUpdate(value: TextFieldValue): SubmissionLookupRequest? {
        val current = _state.value
        val normalizedValue = normalizeMentionSelection(current.document, value, current.mentionEntries)
        val syncedMentions = syncMentions(current.mentionEntries, current.document.text, normalizedValue)
        val next = current.nextDocumentState(document = normalizedValue, mentionEntries = syncedMentions)
        _state.value = next
        return next.activeLookup()
    }

    private fun addAttachments(paths: List<String>, clearMention: Boolean = false) {
        if (paths.isEmpty()) return
        val current = _state.value
        val existingByPath = current.attachments.associateBy { it.path }.toMutableMap()
        paths.mapNotNull { toAttachmentEntry(it) }.forEach { candidate ->
            existingByPath.putIfAbsent(candidate.path, candidate)
        }
        val attachments = existingByPath.values.take(MAX_ATTACHMENTS)
        val nextDocument = if (clearMention) {
            val clearedText = clearTrailingMentionQuery(current.document, current.mentionEntries)
            current.document.copy(text = clearedText, selection = TextRange(clearedText.length))
        } else {
            current.document
        }
        val next = current.copy(
            attachments = attachments,
            document = nextDocument,
            mentionQuery = if (clearMention) "" else current.mentionQuery,
            mentionSuggestions = if (clearMention) emptyList() else current.mentionSuggestions,
            mentionPopupVisible = if (clearMention) false else current.mentionPopupVisible,
            activeMentionIndex = if (clearMention) 0 else current.activeMentionIndex,
        )
        _state.value = next.withResolvedContextEntries()
    }

    private fun addContextFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        val current = _state.value
        val additions = paths.map(::toContextEntry)
        _state.value = current.copy(
            manualContextEntries = mergeManualEntries(current.manualContextEntries, additions),
        ).withResolvedContextEntries()
    }

    private fun addMention(path: String) {
        val current = _state.value
        val entry = toContextEntry(path)
        val inserted = insertMentionLabel(
            document = current.document,
            mentions = current.mentionEntries,
            mentionPath = entry.path,
            displayName = entry.displayName,
            kind = MentionEntryKind.FILE,
        ) ?: return
        val nextValue = inserted.first
        val newMention = inserted.second
        _state.value = current.nextDocumentState(
            document = nextValue,
            mentionEntries = syncMentions(current.mentionEntries, current.document.text, nextValue) + newMention,
        )
    }

    private fun addSubagentMention(threadId: String) {
        val current = _state.value
        val subagent = current.sessionSubagents.firstOrNull { it.threadId == threadId } ?: return
        val inserted = insertMentionLabel(
            document = current.document,
            mentions = current.mentionEntries,
            mentionPath = subagent.threadId,
            displayName = subagent.mentionSlug,
            kind = MentionEntryKind.AGENT,
        ) ?: insertMentionAtCursor(
            document = current.document,
            mentions = current.mentionEntries,
            mentionPath = subagent.threadId,
            displayName = subagent.mentionSlug,
            kind = MentionEntryKind.AGENT,
        )
        val nextValue = inserted.first
        val newMention = inserted.second
        _state.value = current.nextDocumentState(
            document = nextValue,
            mentionEntries = syncMentions(current.mentionEntries, current.document.text, nextValue) + newMention,
        )
    }

    /**
     * Toggles the inline tray detail card for one concrete subagent row without affecting @ mention flows.
     */
    private fun toggleSubagentDetails(threadId: String) {
        val current = _state.value
        if (current.sessionSubagents.none { it.threadId == threadId }) return
        _state.value = current.copy(
            selectedSubagentThreadId = if (current.selectedSubagentThreadId == threadId) {
                null
            } else {
                threadId
            },
        )
    }

    private fun removeMention(id: String) {
        val current = _state.value
        val removed = removeMentionById(current.document, current.mentionEntries, id) ?: return
        _state.value = current.nextDocumentState(document = removed.first, mentionEntries = removed.second)
    }

    private fun addAgent(agent: SavedAgentDefinition) {
        val current = _state.value
        val queryMatch = findAgentQuery(current.document, current.mentionEntries)
        val nextText = clearTrailingAgentQuery(current.document, current.mentionEntries)
        val nextDocument = current.document.copy(
            text = nextText,
            selection = TextRange(queryMatch?.start ?: nextText.length),
        )
        val existing = current.agentEntries.associateBy { it.id }.toMutableMap()
        existing.putIfAbsent(
            agent.id,
            AgentContextEntry(id = agent.id, name = agent.name, prompt = agent.prompt),
        )
        _state.value = current.copy(
            document = nextDocument,
            documentVersion = current.documentVersion + 1,
            agentEntries = existing.values.toList(),
            agentQuery = "",
            agentSuggestions = emptyList(),
            agentPopupVisible = false,
            activeAgentIndex = 0,
            mentionSuggestions = emptyList(),
            mentionPopupVisible = false,
            mentionQuery = if (findMentionQuery(nextDocument, current.mentionEntries) != null) current.mentionQuery else "",
        )
    }

    /**
     * Detects whether the latest snapshot introduced a thread that was not visible in the previous tray state.
     */
    private fun hasNewSubagentThreads(
        previous: List<SessionSubagentUiModel>,
        next: List<SessionSubagentUiModel>,
    ): Boolean {
        if (next.isEmpty()) return false
        val previousThreadIds = previous.mapTo(linkedSetOf()) { it.threadId }
        return next.any { subagent -> subagent.threadId !in previousThreadIds }
    }

    // Restore selected agents strictly from persisted ids so restart and new chat behavior stays deterministic.
    private fun restoreSelectedAgents(
        savedAgents: List<SavedAgentDefinition>,
        selectedAgentIds: List<String>,
    ): List<AgentContextEntry> {
        if (selectedAgentIds.isEmpty() || savedAgents.isEmpty()) {
            return emptyList()
        }
        val savedById = savedAgents.associateBy { it.id }
        return selectedAgentIds.mapNotNull { id ->
            savedById[id]?.let { agent ->
                AgentContextEntry(
                    id = agent.id,
                    name = agent.name,
                    prompt = agent.prompt,
                )
            }
        }
    }

    private fun dismissSlashPopup() {
        _state.value = _state.value.copy(
            slashQuery = "",
            slashSuggestions = emptyList(),
            slashPopupVisible = false,
            activeSlashIndex = 0,
        )
    }

    private fun applySlashCommand(command: String) {
        val current = _state.value
        val normalized = normalizeSlashCommand(command)
        val nextDocument = replaceSlashQuery(current.document, current.mentionEntries, "") ?: return
        val nextState = current.nextDocumentState(
            document = nextDocument,
            mentionEntries = current.mentionEntries,
        ).copy(
            slashQuery = "",
            slashSuggestions = emptyList(),
            slashPopupVisible = false,
            activeSlashIndex = 0,
        )
        _state.value = when (normalized) {
            "plan" -> {
                if (!current.planModeAvailable) {
                    nextState
                } else {
                    nextState.copy(planEnabled = !current.planEnabled)
                }
            }
            "auto" -> nextState.toggleExecutionMode()
            "new" -> nextState
            else -> nextState
        }
    }

    private fun applySlashSkill(name: String) {
        val current = _state.value
        val nextDocument = replaceSlashQuery(
            document = current.document,
            mentions = current.mentionEntries,
            replacement = slashSkillToken(name),
        ) ?: return
        _state.value = current.nextDocumentState(
            document = nextDocument,
            mentionEntries = current.mentionEntries,
        ).copy(
            slashQuery = "",
            slashSuggestions = emptyList(),
            slashPopupVisible = false,
            activeSlashIndex = 0,
        )
    }

    private fun buildSlashSuggestions(
        query: String,
        state: SubmissionAreaState,
    ): List<SlashSuggestionItem> {
        return buildList {
            addAll(buildSlashCommandSuggestions(query, state))
            addAll(
                availableSkillsProvider()
                    .filter { skill ->
                        query.trim().isBlank() ||
                            skill.name.contains(query.trim(), ignoreCase = true) ||
                            skill.description.contains(query.trim(), ignoreCase = true)
                    }
                    .map { skill ->
                        SlashSuggestionItem.Skill(
                            name = skill.name,
                            description = skill.description,
                        )
                    },
            )
        }
    }

    private fun mergeManualEntries(existing: List<ContextEntry>, additions: List<ContextEntry>): List<ContextEntry> {
        val merged = LinkedHashMap<String, ContextEntry>()
        existing.forEach { merged[it.path] = it }
        additions.forEach { merged.putIfAbsent(it.path, it) }
        return merged.values.take(MAX_CONTEXT_FILES)
    }

    /** Recomputes resolved context entries after one caller restores a session-local draft snapshot. */
    internal fun SubmissionAreaState.withResolvedContextEntries(): SubmissionAreaState {
        val merged = LinkedHashMap<String, ContextEntry>()
        focusedContextEntry?.let { merged[it.path] = it }
        manualContextEntries.forEach { merged.putIfAbsent(it.path, it) }
        return copy(
            contextEntries = merged.values.take(MAX_CONTEXT_FILES),
        )
    }

    /**
     * Merges one cached per-session draft snapshot onto the latest global/session-backed composer state.
     */
    internal fun SubmissionAreaState.restoreSessionViewOnto(base: SubmissionAreaState): SubmissionAreaState {
        return copy(
            currentSessionId = base.currentSessionId,
            planModeAvailable = base.planModeAvailable,
            capabilityHint = base.capabilityHint,
            disabledCapabilityReason = base.disabledCapabilityReason,
            selectedEngineId = base.selectedEngineId,
            availableEngines = base.availableEngines,
            engineMenuExpanded = false,
            engineSwitchConfirmation = null,
            selectedModel = base.selectedModel,
            selectedReasoning = base.selectedReasoning,
            autoContextEnabled = base.autoContextEnabled,
            customModelIds = base.customModelIds,
            addingCustomModel = false,
            customModelDraft = "",
            modelMenuExpanded = false,
            reasoningMenuExpanded = false,
            focusedContextEntry = focusedContextEntry?.takeIf { base.autoContextEnabled },
            agentEntries = base.agentEntries,
            mentionSuggestions = emptyList(),
            mentionPopupVisible = false,
            activeMentionIndex = 0,
            agentQuery = "",
            agentSuggestions = emptyList(),
            agentPopupVisible = false,
            activeAgentIndex = 0,
            slashQuery = "",
            slashSuggestions = emptyList(),
            slashPopupVisible = false,
            activeSlashIndex = 0,
            usageSnapshot = base.usageSnapshot,
            activeSessionMessageCount = base.activeSessionMessageCount,
            sessionIsRunning = base.sessionIsRunning,
            pendingApprovalCount = base.pendingApprovalCount,
            pendingToolInputCount = base.pendingToolInputCount,
            editedFiles = base.editedFiles,
            editedFilesExpanded = editedFilesExpanded && base.editedFiles.isNotEmpty(),
            approvalQueue = base.approvalQueue,
            approvalPrompt = base.approvalPrompt,
            toolUserInputQueue = base.toolUserInputQueue,
            toolUserInputPrompt = base.toolUserInputPrompt,
            runningPlan = base.runningPlan,
            // Restore queued submissions from the cached session-local composer state.
            pendingSubmissions = pendingSubmissions,
        ).withResolvedContextEntries()
    }

    private fun clearTrailingMentionQuery(document: TextFieldValue, mentions: List<MentionEntry>): String {
        val query = findMentionQuery(document, mentions) ?: return document.text
        return buildString {
            append(document.text.substring(0, query.start))
            append(document.text.substring(query.end))
        }.trimEnd()
    }

    private fun clearTrailingAgentQuery(document: TextFieldValue, mentions: List<MentionEntry>): String {
        val query = findAgentQuery(document, mentions) ?: return document.text
        return buildString {
            append(document.text.substring(0, query.start))
            append(document.text.substring(query.end))
        }
    }

    private fun SubmissionAreaState.nextDocumentState(
        document: TextFieldValue,
        mentionEntries: List<MentionEntry>,
    ): SubmissionAreaState {
        val slashMatch = findSlashQuery(document, mentionEntries)
        val slashQuery = slashMatch?.query.orEmpty()
        val slashSuggestions = if (slashMatch != null) {
            buildSlashSuggestions(slashQuery, this)
        } else {
            emptyList()
        }
        val keepSlashSelection = slashMatch != null && slashQuery == this.slashQuery
        val query = if (slashMatch == null) findMentionQuery(document, mentionEntries)?.query.orEmpty() else ""
        val agentQuery = if (slashMatch == null) findAgentQuery(document, mentionEntries)?.query.orEmpty() else ""
        val keepSuggestions = query.isNotBlank() &&
            query == this.mentionQuery &&
            this.mentionSuggestions.isNotEmpty() &&
            document.composition == null
        val keepAgentSuggestions = agentQuery == this.agentQuery &&
            this.agentSuggestions.isNotEmpty() &&
            document.composition == null &&
            findAgentQuery(document, mentionEntries) != null

        return copy(
            document = document,
            documentVersion = documentVersion + 1,
            mentionEntries = mentionEntries,
            slashQuery = slashQuery,
            slashSuggestions = slashSuggestions,
            slashPopupVisible = slashMatch != null && slashSuggestions.isNotEmpty(),
            activeSlashIndex = if (keepSlashSelection) {
                activeSlashIndex.coerceAtMost((slashSuggestions.size - 1).coerceAtLeast(0))
            } else {
                0
            },
            mentionQuery = query,
            mentionSuggestions = if (slashMatch == null && keepSuggestions) mentionSuggestions else emptyList(),
            mentionPopupVisible = slashMatch == null && keepSuggestions && mentionPopupVisible,
            activeMentionIndex = if (keepSuggestions) activeMentionIndex.coerceAtMost((mentionSuggestions.size - 1).coerceAtLeast(0)) else 0,
            agentQuery = agentQuery,
            agentSuggestions = if (slashMatch == null && keepAgentSuggestions) agentSuggestions else emptyList(),
            agentPopupVisible = slashMatch == null && keepAgentSuggestions && agentPopupVisible,
            activeAgentIndex = if (keepAgentSuggestions) activeAgentIndex.coerceAtMost((agentSuggestions.size - 1).coerceAtLeast(0)) else 0,
        )
    }

    private fun SubmissionAreaState.activeLookup(): SubmissionLookupRequest? {
        if (document.composition != null) return null
        if (findSlashQuery(document, mentionEntries) != null) return null
        findAgentQuery(document, mentionEntries)?.let { match ->
            return SubmissionLookupRequest(agentQuery = match.query, documentVersion = documentVersion)
        }
        val query = mentionQuery
        if (findMentionQuery(document, mentionEntries) == null) return null
        return SubmissionLookupRequest(
            mention = MentionLookupRequest(query = query, documentVersion = documentVersion),
            documentVersion = documentVersion,
        )
    }

    private fun SubmissionAreaState.removeEditedFile(path: String): SubmissionAreaState {
        val nextFiles = editedFiles.filterNot { it.path == path }
        return copy(
            editedFiles = nextFiles,
            editedFilesExpanded = editedFilesExpanded && nextFiles.isNotEmpty(),
        )
    }

    private fun FocusedContextSnapshot.toContextEntry(): ContextEntry? {
        val normalizedPath = path.trim()
        if (normalizedPath.isBlank()) return null
        return toContextEntry(
            path = normalizedPath,
            selectedText = selectedText?.takeIf { it.isNotBlank() },
            startLine = startLine,
            endLine = endLine,
        )
    }

    private fun toContextEntry(path: String): ContextEntry = toContextEntry(
        path = path,
        selectedText = null,
        startLine = null,
        endLine = null,
    )

    private fun toContextEntry(
        path: String,
        selectedText: String?,
        startLine: Int?,
        endLine: Int?,
    ): ContextEntry {
        val normalized = path.trim()
        val p = runCatching { Path.of(normalized) }.getOrNull()
        val name = p?.name ?: normalized.substringAfterLast('/').substringAfterLast('\\').ifBlank { normalized }
        val parent = p?.parent?.fileName?.toString().orEmpty()
        val hasSelection = !selectedText.isNullOrBlank() && startLine != null && endLine != null
        val displayName = when {
            !hasSelection -> name
            startLine == endLine -> "$name:$startLine"
            else -> "$name:$startLine-$endLine"
        }
        return ContextEntry(
            path = normalized,
            displayName = displayName,
            tailPath = parent,
            selectedText = selectedText,
            startLine = startLine,
            endLine = endLine,
            isSelectionContext = hasSelection,
        )
    }

    private fun toAttachmentEntry(path: String): AttachmentEntry? {
        val normalized = path.trim()
        if (normalized.isBlank()) return null
        val p = runCatching { Path.of(normalized) }.getOrNull() ?: return null
        val name = p.name.ifBlank { normalized.substringAfterLast('/').substringAfterLast('\\') }
        val parent = p.parent?.fileName?.toString().orEmpty()
        val size = runCatching { Files.size(p) }.getOrDefault(0L)
        val ext = name.substringAfterLast('.', "").lowercase()
        val kind = when {
            ext in IMAGE_EXTENSIONS -> {
                if (size > MAX_IMAGE_BYTES) return null
                AttachmentKind.IMAGE
            }
            ext in TEXT_EXTENSIONS -> AttachmentKind.TEXT
            isProbablyTextFile(p, size) -> AttachmentKind.TEXT
            else -> AttachmentKind.BINARY
        }
        val mime = runCatching { Files.probeContentType(p).orEmpty() }.getOrDefault("")
        return AttachmentEntry(
            id = UUID.randomUUID().toString(),
            path = normalized,
            displayName = name,
            tailPath = parent,
            kind = kind,
            sizeBytes = size,
            mimeType = mime,
        )
    }

    private fun isProbablyTextFile(path: Path, size: Long): Boolean {
        if (size <= 0L) return false
        val sampleSize = min(size, 4096L).toInt()
        val bytes = runCatching {
            Files.newInputStream(path).use { input ->
                val sample = ByteArray(sampleSize)
                val read = input.read(sample)
                if (read <= 0) ByteArray(0) else sample.copyOf(read)
            }
        }.getOrDefault(ByteArray(0))
        if (bytes.isEmpty()) return false
        if (bytes.any { it == 0.toByte() }) return false
        return runCatching {
            String(bytes, StandardCharsets.UTF_8)
            true
        }.getOrDefault(false)
    }
}

internal fun submissionModelOptions(
    engineId: String,
    builtInModelIds: List<String>,
    customModelIds: List<String>,
): List<SubmissionModelOption> {
    val builtIns = builtInModelIds.map { modelId ->
        SubmissionModelOption(
            id = modelId,
            shortName = modelShortName(engineId = engineId, modelId = modelId),
            isCustom = false,
        )
    }
    val builtInIds = builtIns.mapTo(linkedSetOf()) { it.id }
    val customs = customModelIds
        .map { it.trim() }
        .filter { it.isNotBlank() && it !in builtInIds }
        .distinct()
        .map {
            SubmissionModelOption(
                id = it,
                shortName = it,
                isCustom = true,
            )
        }
    return builtIns + customs
}

/** 为 composer 里的模型展示解析稳定的短名称。 */
internal fun modelShortName(
    engineId: String,
    modelId: String,
): String {
    val normalizedEngineId = engineId.trim()
    val normalizedModelId = modelId.trim()
    if (normalizedModelId.isBlank()) return modelId
    return when (normalizedEngineId) {
        "claude" -> ClaudeModelCatalog.option(normalizedModelId)?.shortName
        else -> CodexModelCatalog.option(normalizedModelId)?.description
    } ?: normalizedModelId
}

private fun defaultModelIdsForEngine(engineId: String): List<String> {
    return when (engineId.trim()) {
        "claude" -> ClaudeModelCatalog.ids()
        else -> CodexModelCatalog.ids()
    }
}

private fun defaultModelForEngine(engineId: String): String {
    return when (engineId.trim()) {
        "claude" -> ClaudeModelCatalog.defaultModel
        else -> CodexModelCatalog.defaultModel
    }
}

private fun SubmissionAreaState.clearSubmissionDraft(clearInteractionQueues: Boolean): SubmissionAreaState {
    return copy(
        document = TextFieldValue(""),
        documentVersion = documentVersion + 1,
        attachments = emptyList(),
        previewAttachmentId = null,
        mentionEntries = emptyList(),
        mentionQuery = "",
        mentionSuggestions = emptyList(),
        mentionPopupVisible = false,
        activeMentionIndex = 0,
        agentQuery = "",
        agentSuggestions = emptyList(),
        agentPopupVisible = false,
        activeAgentIndex = 0,
        slashQuery = "",
        slashSuggestions = emptyList(),
        slashPopupVisible = false,
        activeSlashIndex = 0,
        approvalQueue = if (clearInteractionQueues) emptyList() else approvalQueue,
        approvalPrompt = if (clearInteractionQueues) null else approvalPrompt,
        toolUserInputQueue = if (clearInteractionQueues) emptyList() else toolUserInputQueue,
        toolUserInputPrompt = if (clearInteractionQueues) null else toolUserInputPrompt,
        planCompletion = if (clearInteractionQueues) null else planCompletion,
    )
}

/** Keeps execution-mode flipping in one place so button and slash toggles cannot drift apart. */
private fun SubmissionAreaState.toggleExecutionMode(): SubmissionAreaState {
    return copy(
        executionMode = when (executionMode) {
            SubmissionMode.AUTO -> SubmissionMode.APPROVAL
            SubmissionMode.APPROVAL -> SubmissionMode.AUTO
        },
    )
}

internal fun SubmissionAreaState.copySubmissionSessionConfiguration(): SubmissionAreaState {
    return SubmissionAreaState(
        executionMode = executionMode,
        planEnabled = planEnabled,
        planModeAvailable = planModeAvailable,
        selectedModel = selectedModel,
        selectedReasoning = selectedReasoning,
        autoContextEnabled = autoContextEnabled,
        customModelIds = customModelIds,
        agentEntries = agentEntries,
        focusedContextEntry = focusedContextEntry,
        contextEntries = focusedContextEntry?.let(::listOf).orEmpty(),
    )
}


private fun PlanCompletionAction.next(): PlanCompletionAction {
    return when (this) {
        PlanCompletionAction.EXECUTE -> PlanCompletionAction.CANCEL
        PlanCompletionAction.CANCEL -> PlanCompletionAction.REVISION
        PlanCompletionAction.REVISION -> PlanCompletionAction.EXECUTE
    }
}

private fun PlanCompletionAction.previous(): PlanCompletionAction {
    return when (this) {
        PlanCompletionAction.EXECUTE -> PlanCompletionAction.REVISION
        PlanCompletionAction.CANCEL -> PlanCompletionAction.EXECUTE
        PlanCompletionAction.REVISION -> PlanCompletionAction.CANCEL
    }
}

private fun List<PendingApprovalRequestUiModel>.reindexedApprovalQueue(): List<PendingApprovalRequestUiModel> {
    val total = size
    return mapIndexed { index, request ->
        request.copy(queuePosition = index + 1, queueSize = total)
    }
}

private fun List<ToolUserInputPromptUiModel>.reindexedToolInputQueue(): List<ToolUserInputPromptUiModel> {
    val total = size
    return mapIndexed { index, prompt ->
        prompt.copy(queuePosition = index + 1, queueSize = total)
    }
}
