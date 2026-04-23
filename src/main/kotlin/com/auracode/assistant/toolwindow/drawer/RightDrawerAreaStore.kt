package com.auracode.assistant.toolwindow.drawer

import com.auracode.assistant.conversation.ConversationSummary
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.SavedAgentDefinition
import com.auracode.assistant.settings.UiLanguageMode
import com.auracode.assistant.settings.UiScaleMode
import com.auracode.assistant.settings.UiThemeMode
import com.auracode.assistant.settings.skills.SkillRuntimeEntry
import com.auracode.assistant.settings.mcp.McpBusyState
import com.auracode.assistant.settings.mcp.McpServerDraft
import com.auracode.assistant.settings.mcp.McpServerSummary
import com.auracode.assistant.settings.mcp.McpRuntimeStatus
import com.auracode.assistant.settings.mcp.McpTestResult
import com.auracode.assistant.settings.mcp.McpValidationErrors
import com.auracode.assistant.provider.codex.CodexEnvironmentCheckResult
import com.auracode.assistant.provider.codex.CodexCliVersionSnapshot
import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.eventing.UiIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class RightDrawerKind {
    NONE,
    HISTORY,
    SETTINGS,
}

internal enum class AgentSettingsPage {
    LIST,
    EDITOR,
}

internal enum class McpSettingsPage {
    LIST,
    EDITOR,
}

internal data class RightDrawerAreaState(
    val kind: RightDrawerKind = RightDrawerKind.NONE,
    val sessions: List<AgentChatService.SessionSummary> = emptyList(),
    val activeSessionId: String = "",
    val activeRemoteConversationId: String = "",
    val historyConversations: List<ConversationSummary> = emptyList(),
    val historyNextCursor: String? = null,
    val historyLoading: Boolean = false,
    val historyQuery: String = "",
    val environmentDraft: EnvironmentDraftState = EnvironmentDraftState(),
    val claudeCliPath: String = "claude",
    val savedClaudeCliPath: String = "claude",
    val languageMode: UiLanguageMode = UiLanguageMode.FOLLOW_IDE,
    val themeMode: UiThemeMode = UiThemeMode.FOLLOW_IDE,
    val uiScaleMode: UiScaleMode = UiScaleMode.P100,
    val autoContextEnabled: Boolean = true,
    val backgroundCompletionNotificationsEnabled: Boolean = true,
    val codexCliAutoUpdateCheckEnabled: Boolean = true,
    val codexCliVersionSnapshot: CodexCliVersionSnapshot = CodexCliVersionSnapshot(),
    val environmentCheckRunning: Boolean = false,
    val environmentCheckResult: CodexEnvironmentCheckResult? = null,
    val settingsSection: SettingsSection = SettingsSection.GENERAL,
    val savedAgents: List<SavedAgentDefinition> = emptyList(),
    val agentSettingsPage: AgentSettingsPage = AgentSettingsPage.LIST,
    val editingAgentId: String? = null,
    val agentDraftName: String = "",
    val agentDraftPrompt: String = "",
    val skillsEngineId: String = "",
    val skillsCwd: String = "",
    val skills: List<SkillRuntimeEntry> = emptyList(),
    val skillsRuntimeSupported: Boolean = true,
    val skillsStale: Boolean = false,
    val skillsErrorMessage: String? = null,
    val skillsLoading: Boolean = false,
    val skillsHasLoadedSnapshot: Boolean = false,
    val skillsActiveTogglePath: String? = null,
    val mcpSettingsPage: McpSettingsPage = McpSettingsPage.LIST,
    val mcpServers: List<McpServerSummary> = emptyList(),
    val editingMcpName: String? = null,
    val mcpDraft: McpServerDraft = McpServerDraft(),
    val mcpStatusByName: Map<String, McpRuntimeStatus> = emptyMap(),
    val mcpTestResultsByName: Map<String, McpTestResult> = emptyMap(),
    val mcpBusyState: McpBusyState = McpBusyState(),
    val mcpValidationErrors: McpValidationErrors = McpValidationErrors(),
    val mcpFeedbackMessage: String? = null,
    val mcpFeedbackIsError: Boolean = false,
) {
    /** Exposes the editable Codex path from the environment draft for UI callers. */
    val codexCliPath: String
        get() = environmentDraft.codexCliPath

    /** Exposes the editable Node path from the environment draft for UI callers. */
    val nodePath: String
        get() = environmentDraft.nodePath

    /** Returns true when the environment draft needs an explicit save action. */
    val isEnvironmentSaveVisible: Boolean
        get() = environmentDraft.isDirty ||
            claudeCliPath.trim() != savedClaudeCliPath.trim()

    /**
     * Keeps agent editing modal visibility derived from the existing page mode
     * so the UI does not need a second source of truth for the same state.
     */
    val isAgentEditorDialogVisible: Boolean
        get() = settingsSection == SettingsSection.AGENTS && agentSettingsPage == AgentSettingsPage.EDITOR

    val isMcpEditorDialogVisible: Boolean
        get() = settingsSection == SettingsSection.MCP && mcpSettingsPage == McpSettingsPage.EDITOR
}

internal class RightDrawerAreaStore {
    private val _state = MutableStateFlow(RightDrawerAreaState())
    val state: StateFlow<RightDrawerAreaState> = _state.asStateFlow()

    fun onEvent(event: AppEvent) {
        when (event) {
            is AppEvent.UiIntentPublished -> {
                when (event.intent) {
                    UiIntent.ToggleHistory -> {
                        val next = if (_state.value.kind == RightDrawerKind.HISTORY) RightDrawerKind.NONE else RightDrawerKind.HISTORY
                        _state.value = _state.value.copy(kind = next)
                    }

                    is UiIntent.EditHistorySearchQuery -> {
                        _state.value = _state.value.copy(historyQuery = event.intent.value)
                    }

                    UiIntent.ToggleSettings -> {
                        val next = if (_state.value.kind == RightDrawerKind.SETTINGS) RightDrawerKind.NONE else RightDrawerKind.SETTINGS
                        _state.value = _state.value.copy(kind = next)
                    }

                    is UiIntent.SelectSettingsSection -> {
                        _state.value = _state.value.copy(
                            kind = RightDrawerKind.SETTINGS,
                            settingsSection = event.intent.section,
                            // Reset page-scoped editors when switching sections so
                            // modal visibility does not leak across settings areas.
                            agentSettingsPage = AgentSettingsPage.LIST,
                            mcpSettingsPage = if (event.intent.section == SettingsSection.MCP) {
                                McpSettingsPage.LIST
                            } else {
                                _state.value.mcpSettingsPage
                            },
                        )
                    }

                    UiIntent.CloseRightDrawer -> {
                        _state.value = _state.value.copy(kind = RightDrawerKind.NONE)
                    }

                    is UiIntent.EditSettingsCodexCliPath -> {
                        _state.value = _state.value.copy(
                            environmentDraft = _state.value.environmentDraft.withEditedCodexPath(event.intent.value),
                        )
                    }

                    is UiIntent.EditSettingsClaudeCliPath -> {
                        _state.value = _state.value.copy(
                            claudeCliPath = event.intent.value,
                        )
                    }

                    is UiIntent.EditSettingsNodePath -> {
                        _state.value = _state.value.copy(
                            environmentDraft = _state.value.environmentDraft.withEditedNodePath(event.intent.value),
                        )
                    }

                    is UiIntent.EditSettingsLanguageMode -> {
                        _state.value = _state.value.copy(languageMode = event.intent.mode)
                    }

                    is UiIntent.EditSettingsThemeMode -> {
                        _state.value = _state.value.copy(themeMode = event.intent.mode)
                    }

                    is UiIntent.EditSettingsUiScaleMode -> {
                        _state.value = _state.value.copy(uiScaleMode = event.intent.mode)
                    }

                    is UiIntent.EditSettingsAutoContextEnabled -> {
                        _state.value = _state.value.copy(autoContextEnabled = event.intent.enabled)
                    }

                    is UiIntent.EditSettingsBackgroundCompletionNotificationsEnabled -> {
                        _state.value = _state.value.copy(
                            backgroundCompletionNotificationsEnabled = event.intent.enabled,
                        )
                    }

                    is UiIntent.EditSettingsCodexCliAutoUpdateCheckEnabled -> {
                        _state.value = _state.value.copy(
                            codexCliAutoUpdateCheckEnabled = event.intent.enabled,
                        )
                    }

                    UiIntent.CreateNewAgentDraft -> {
                        _state.value = _state.value.copy(
                            agentSettingsPage = AgentSettingsPage.EDITOR,
                            editingAgentId = null,
                            agentDraftName = "",
                            agentDraftPrompt = "",
                        )
                    }

                    UiIntent.ShowAgentSettingsList -> {
                        _state.value = _state.value.copy(agentSettingsPage = AgentSettingsPage.LIST)
                    }

                    is UiIntent.SelectSavedAgentForEdit -> {
                        val agent = _state.value.savedAgents.firstOrNull { it.id == event.intent.id } ?: return
                        _state.value = _state.value.copy(
                            agentSettingsPage = AgentSettingsPage.EDITOR,
                            editingAgentId = agent.id,
                            agentDraftName = agent.name,
                            agentDraftPrompt = agent.prompt,
                        )
                    }

                    is UiIntent.EditAgentDraftName -> {
                        _state.value = _state.value.copy(agentDraftName = event.intent.value)
                    }

                    is UiIntent.EditAgentDraftPrompt -> {
                        _state.value = _state.value.copy(agentDraftPrompt = event.intent.value)
                    }

                    UiIntent.CreateNewMcpDraft -> {
                        _state.value = _state.value.copy(
                            mcpSettingsPage = McpSettingsPage.EDITOR,
                            editingMcpName = null,
                            mcpDraft = McpServerDraft(),
                            mcpValidationErrors = McpValidationErrors(),
                            mcpFeedbackMessage = null,
                            mcpFeedbackIsError = false,
                        )
                    }

                    UiIntent.ShowMcpSettingsList -> {
                        _state.value = _state.value.copy(
                            mcpSettingsPage = McpSettingsPage.LIST,
                            mcpValidationErrors = McpValidationErrors(),
                            mcpFeedbackMessage = null,
                            mcpFeedbackIsError = false,
                        )
                    }

                    is UiIntent.EditMcpDraftName -> {
                        _state.value = _state.value.copy(
                            mcpDraft = _state.value.mcpDraft.copy(name = event.intent.value),
                            mcpValidationErrors = McpValidationErrors(),
                            mcpFeedbackMessage = null,
                            mcpFeedbackIsError = false,
                        )
                    }

                    is UiIntent.EditMcpDraftJson -> {
                        _state.value = _state.value.copy(
                            mcpDraft = _state.value.mcpDraft.copy(configJson = event.intent.value).syncNameFromConfig(),
                            mcpValidationErrors = McpValidationErrors(),
                            mcpFeedbackMessage = null,
                            mcpFeedbackIsError = false,
                        )
                    }

                    else -> Unit
                }
            }

            is AppEvent.SkillsLoaded -> {
                _state.value = _state.value.copy(
                    skillsEngineId = event.engineId,
                    skillsCwd = event.cwd,
                    skills = event.skills,
                    skillsRuntimeSupported = event.supportsRuntimeSkills,
                    skillsStale = event.stale,
                    skillsErrorMessage = event.errorMessage,
                    skillsHasLoadedSnapshot = true,
                )
            }

            is AppEvent.SkillsLoadingChanged -> {
                _state.value = _state.value.copy(
                    skillsLoading = event.loading,
                    skillsActiveTogglePath = event.activePath?.takeIf { event.loading },
                )
            }

            is AppEvent.SessionSnapshotUpdated -> {
                val active = event.sessions.firstOrNull { it.id == event.activeSessionId }
                _state.value = _state.value.copy(
                    sessions = event.sessions,
                    activeSessionId = event.activeSessionId,
                    activeRemoteConversationId = active?.remoteConversationId.orEmpty(),
                )
            }

            is AppEvent.HistoryConversationsUpdated -> {
                _state.value = _state.value.copy(
                    historyConversations = if (event.append) {
                        (_state.value.historyConversations + event.conversations)
                            .distinctBy { it.remoteConversationId }
                    } else {
                        event.conversations
                    },
                    historyNextCursor = event.nextCursor,
                    historyLoading = event.isLoading,
                )
            }

            is AppEvent.SettingsSnapshotUpdated -> {
                val selected = event.savedAgents.firstOrNull { it.id == _state.value.editingAgentId }
                val fallback = event.savedAgents.firstOrNull()
                _state.value = _state.value.copy(
                    environmentDraft = _state.value.environmentDraft.withPersistedPaths(
                        codexCliPath = event.codexCliPath,
                        nodePath = event.nodePath,
                    ),
                    claudeCliPath = event.claudeCliPath,
                    savedClaudeCliPath = event.claudeCliPath,
                    languageMode = event.languageMode,
                    themeMode = event.themeMode,
                    uiScaleMode = event.uiScaleMode,
                    autoContextEnabled = event.autoContextEnabled,
                    backgroundCompletionNotificationsEnabled = event.backgroundCompletionNotificationsEnabled,
                    codexCliAutoUpdateCheckEnabled = event.codexCliAutoUpdateCheckEnabled,
                    codexCliVersionSnapshot = event.codexCliVersionSnapshot,
                    savedAgents = event.savedAgents,
                    editingAgentId = selected?.id ?: _state.value.editingAgentId?.takeIf { id ->
                        event.savedAgents.any { it.id == id }
                    } ?: fallback?.id,
                    agentSettingsPage = when {
                        _state.value.settingsSection != SettingsSection.AGENTS -> _state.value.agentSettingsPage
                        selected != null -> AgentSettingsPage.EDITOR
                        _state.value.editingAgentId != null && event.savedAgents.none { it.id == _state.value.editingAgentId } -> AgentSettingsPage.LIST
                        else -> _state.value.agentSettingsPage
                    },
                    agentDraftName = selected?.name ?: _state.value.agentDraftName.takeIf { _state.value.agentSettingsPage == AgentSettingsPage.EDITOR } ?: "",
                    agentDraftPrompt = selected?.prompt ?: _state.value.agentDraftPrompt.takeIf { _state.value.agentSettingsPage == AgentSettingsPage.EDITOR } ?: "",
                )
            }

            is AppEvent.CodexEnvironmentCheckRunning -> {
                _state.value = _state.value.copy(environmentCheckRunning = event.running)
            }

            is AppEvent.CodexEnvironmentCheckUpdated -> {
                _state.value = _state.value.copy(
                    environmentDraft = _state.value.environmentDraft.withDetectedPaths(
                        codexCliPath = event.result.codexPath,
                        nodePath = event.result.nodePath,
                        updateDraftPaths = event.updateDraftPaths,
                    ),
                    environmentCheckResult = event.result,
                    environmentCheckRunning = false,
                )
            }

            is AppEvent.CodexCliVersionSnapshotUpdated -> {
                _state.value = _state.value.copy(codexCliVersionSnapshot = event.snapshot)
            }

            is AppEvent.McpServersLoaded -> {
                _state.value = _state.value.copy(mcpServers = event.servers)
            }

            is AppEvent.McpDraftLoaded -> {
                _state.value = _state.value.copy(
                    mcpSettingsPage = McpSettingsPage.EDITOR,
                    editingMcpName = (event.draft.originalName ?: event.draft.name).ifBlank { null },
                    mcpDraft = event.draft,
                    mcpValidationErrors = McpValidationErrors(),
                    mcpFeedbackMessage = null,
                    mcpFeedbackIsError = false,
                )
            }

            is AppEvent.McpStatusesUpdated -> {
                _state.value = _state.value.copy(mcpStatusByName = event.statuses)
            }

            is AppEvent.McpTestResultUpdated -> {
                _state.value = _state.value.copy(
                    mcpTestResultsByName = _state.value.mcpTestResultsByName + (event.name to event.result),
                )
            }

            is AppEvent.McpBusyStateUpdated -> {
                _state.value = _state.value.copy(mcpBusyState = event.state)
            }

            is AppEvent.McpValidationErrorsUpdated -> {
                _state.value = _state.value.copy(mcpValidationErrors = event.errors)
            }

            is AppEvent.McpFeedbackUpdated -> {
                _state.value = _state.value.copy(
                    mcpFeedbackMessage = event.message,
                    mcpFeedbackIsError = event.isError,
                )
            }

            else -> Unit
        }
    }
}
