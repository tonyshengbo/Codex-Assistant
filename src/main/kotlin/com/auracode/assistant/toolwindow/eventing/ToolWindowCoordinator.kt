package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.ChatMessage
import com.auracode.assistant.model.ContextFile
import com.auracode.assistant.model.FileAttachment
import com.auracode.assistant.model.ImageAttachment
import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.context.MentionFileWhitelist
import com.auracode.assistant.persistence.chat.PersistedAttachmentKind
import com.auracode.assistant.persistence.chat.PersistedMessageAttachment
import com.auracode.assistant.provider.codex.CodexModelCatalog
import com.auracode.assistant.provider.codex.CodexEnvironmentDetector
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.settings.SavedAgentDefinition
import com.auracode.assistant.settings.skills.LocalSkillInstallPolicy
import com.auracode.assistant.settings.skills.SkillSelector
import com.auracode.assistant.settings.skills.SkillsRuntimeService
import com.auracode.assistant.settings.mcp.McpAuthActionResult
import com.auracode.assistant.settings.mcp.McpBusyState
import com.auracode.assistant.settings.mcp.McpManagementAdapter
import com.auracode.assistant.settings.mcp.McpManagementAdapterRegistry
import com.auracode.assistant.settings.mcp.McpServerDraft
import com.auracode.assistant.settings.mcp.McpTestResult
import com.auracode.assistant.settings.mcp.McpValidationErrors
import com.auracode.assistant.settings.mcp.validate
import com.auracode.assistant.toolwindow.approval.ApprovalAction
import com.auracode.assistant.toolwindow.approval.ApprovalAreaStore
import com.auracode.assistant.toolwindow.approval.toUiModel
import com.auracode.assistant.toolwindow.composer.AttachmentEntry
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.composer.ContextEntry
import com.auracode.assistant.toolwindow.composer.AttachmentKind
import com.auracode.assistant.toolwindow.composer.ComposerAreaStore
import com.auracode.assistant.toolwindow.composer.ComposerRunningPlanState
import com.auracode.assistant.toolwindow.composer.ComposerRunningPlanStep
import com.auracode.assistant.toolwindow.composer.ComposerRunningPlanStepStatus
import com.auracode.assistant.toolwindow.composer.PendingComposerSubmission
import com.auracode.assistant.toolwindow.drawer.RightDrawerKind
import com.auracode.assistant.toolwindow.drawer.RightDrawerAreaStore
import com.auracode.assistant.toolwindow.drawer.SettingsSection
import com.auracode.assistant.toolwindow.drawer.formatConversationExportMarkdown
import com.auracode.assistant.toolwindow.drawer.suggestConversationExportFileName
import com.auracode.assistant.toolwindow.header.HeaderAreaStore
import com.auracode.assistant.toolwindow.plan.PlanCompletionPromptUiModel
import com.auracode.assistant.toolwindow.status.StatusAreaStore
import com.auracode.assistant.toolwindow.timeline.TimelineAreaStore
import com.auracode.assistant.toolwindow.timeline.TimelineFileChange
import com.auracode.assistant.toolwindow.timeline.TimelineFileChangePreview
import com.auracode.assistant.toolwindow.timeline.TimelineMutation
import com.auracode.assistant.toolwindow.timeline.TimelineNodeMapper
import com.auracode.assistant.toolwindow.timeline.TimelineNodeReducer
import com.auracode.assistant.toolwindow.toolinput.ToolUserInputPromptStore
import com.auracode.assistant.toolwindow.toolinput.ToolUserInputPromptUiModel
import com.auracode.assistant.toolwindow.toolinput.toSubmissionAnswers
import com.auracode.assistant.toolwindow.toolinput.toUiModel
import com.auracode.assistant.toolwindow.shared.UiText
import com.auracode.assistant.i18n.AuraCodeBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import java.security.MessageDigest
import java.net.URI
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

internal class ToolWindowCoordinator(
    private val chatService: AgentChatService,
    private val settingsService: AgentSettingsService,
    private val eventHub: ToolWindowEventHub,
    private val headerStore: HeaderAreaStore,
    private val statusStore: StatusAreaStore,
    private val timelineStore: TimelineAreaStore,
    private val composerStore: ComposerAreaStore,
    private val rightDrawerStore: RightDrawerAreaStore,
    private val approvalStore: ApprovalAreaStore = ApprovalAreaStore(),
    private val toolUserInputPromptStore: ToolUserInputPromptStore = ToolUserInputPromptStore(),
    private val mcpAdapterRegistry: McpManagementAdapterRegistry = McpManagementAdapterRegistry(settingsService),
    private val skillsRuntimeService: SkillsRuntimeService = SkillsRuntimeService(
        adapterRegistry = com.auracode.assistant.settings.skills.SkillsManagementAdapterRegistry(settingsService),
    ),
    private val codexEnvironmentDetector: CodexEnvironmentDetector = CodexEnvironmentDetector(),
    private val pickAttachments: () -> List<String> = { emptyList() },
    private val pickExportPath: (String) -> String? = { null },
    private val searchProjectFiles: (String, Int) -> List<String> = { _, _ -> emptyList() },
    private val isMentionCandidateFile: (String) -> Boolean = { path -> MentionFileWhitelist.allowPath(path) },
    private val readFileContent: (String) -> String? = { path -> readFileContentDefault(path) },
    private val openTimelineFileChange: (TimelineFileChange) -> Unit = {},
    private val openTimelineFilePath: (String) -> Unit = {},
    private val revealPathInFileManager: (String) -> Boolean = { false },
    private val localSkillInstallPolicy: LocalSkillInstallPolicy = LocalSkillInstallPolicy(),
    private val writeExportFile: (String, String) -> Unit = { path, content ->
        val exportPath = Path.of(path)
        exportPath.parent?.let { Files.createDirectories(it) }
        Files.writeString(exportPath, content, StandardCharsets.UTF_8)
    },
    private val openExternalUrl: (String) -> Boolean = { url ->
        runCatching {
            if (!Desktop.isDesktopSupported()) return@runCatching false
            Desktop.getDesktop().browse(URI(url))
            true
        }.getOrDefault(false)
    },
    private val diagnosticLog: (String, Throwable?) -> Unit = { message, error ->
        if (error != null) {
            LOG.warn(message, error)
        } else {
            LOG.info(message)
        }
    },
    private val onSessionSnapshotPublished: () -> Unit = {},
    private val historyPageSize: Int = 40,
) : Disposable {
    companion object {
        private val LOG = Logger.getInstance(ToolWindowCoordinator::class.java)
        private const val MENTION_LIMIT: Int = 10
        private const val EXECUTE_APPROVED_PLAN_PROMPT: String =
            "The user approved the latest plan. Execute it now. Unless blocked, do not re-plan."
    }

    private data class ActivePlanRunContext(
        val localTurnId: String,
        val preferredExecutionMode: ComposerMode,
        var remoteTurnId: String? = null,
        var threadId: String? = null,
        var latestPlanBody: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).apply {

    }
    private val recentFocusedFiles = ArrayDeque<String>()
    private val pendingSubmissions = ArrayDeque<PendingComposerSubmission>()
    private var activePlanRunContext: ActivePlanRunContext? = null

    init {
        launchLogged("eventHub.collect") {
            eventHub.stream.collect { event ->
                if (event is AppEvent.UiIntentPublished && isMcpIntent(event.intent)) {
                    logMcpDiagnostic(
                        message = "MCP intent received: intent=${formatMcpIntent(event.intent)} | ${mcpContextSnapshot()}",
                    )
                }
                runCatching {
                    headerStore.onEvent(event)
                    statusStore.onEvent(event)
                    timelineStore.onEvent(event)
                    composerStore.onEvent(event)
                    rightDrawerStore.onEvent(event)
                    approvalStore.onEvent(event)
                    toolUserInputPromptStore.onEvent(event)
                    if (event is AppEvent.UiIntentPublished) {
                        handleUiIntent(event.intent)
                    } else if (event is AppEvent.UnifiedEventPublished) {
                        handleUnifiedEvent(event.event)
                    } else if (event is AppEvent.TimelineMutationApplied &&
                        event.mutation is TimelineMutation.TurnCompleted
                    ) {
                        dispatchNextPendingSubmissionIfIdle()
                    }
                }.onFailure { error ->
                    if (event is AppEvent.UiIntentPublished && isMcpIntent(event.intent)) {
                        logMcpDiagnostic(
                            message = "MCP intent handling failed: intent=${formatMcpIntent(event.intent)} | ${mcpContextSnapshot()}",
                            error = error,
                        )
                    } else {
                        LOG.error(error)
                    }
                }
            }
        }

        publishSessionSnapshot()
        publishSettingsSnapshot()
        publishConversationCapabilities()
        restoreCurrentSessionHistory()
        warmSkillsRuntimeCache()
    }

    private fun handleUiIntent(intent: UiIntent) {
        when (intent) {
            UiIntent.ToggleSettings -> {
                if (rightDrawerStore.state.value.kind == RightDrawerKind.SETTINGS) {
                    publishSettingsSnapshot()
                    if (rightDrawerStore.state.value.settingsSection == SettingsSection.MCP) {
                        loadMcpServers()
                    } else if (rightDrawerStore.state.value.settingsSection == SettingsSection.SKILLS) {
                        loadSkills()
                    } else if (rightDrawerStore.state.value.settingsSection == SettingsSection.GENERAL) {
                        detectCodexEnvironment()
                    }
                }
            }

            UiIntent.ToggleHistory -> {
                if (rightDrawerStore.state.value.kind == RightDrawerKind.HISTORY) {
                    loadHistoryConversations(reset = true)
                }
            }

            UiIntent.SendPrompt -> submitPromptIfAllowed()
            UiIntent.CancelRun -> cancelPromptRun()
            is UiIntent.RemovePendingSubmission -> removePendingSubmission(intent.id)
            is UiIntent.DeleteSession -> deleteSession(intent.sessionId)
            is UiIntent.SwitchSession -> switchSession(intent.sessionId)
            UiIntent.LoadHistoryConversations -> loadHistoryConversations(reset = true)
            UiIntent.LoadMoreHistoryConversations -> loadHistoryConversations(reset = false)
            UiIntent.LoadMcpServers -> loadMcpServers()
            UiIntent.RefreshMcpStatuses -> refreshMcpStatuses()
            is UiIntent.EditHistorySearchQuery -> loadHistoryConversations(reset = true)
            is UiIntent.OpenRemoteConversation -> openRemoteConversation(intent.remoteConversationId, intent.title)
            is UiIntent.ExportRemoteConversation -> exportRemoteConversation(intent.remoteConversationId, intent.title)
            is UiIntent.OpenTimelineFileChange -> openTimelineFileChange(intent.change)
            is UiIntent.OpenTimelineFilePath -> openTimelineFilePath(intent.path)
            UiIntent.LoadOlderMessages -> loadOlderMessages()
            is UiIntent.SelectSettingsSection -> {
                if (intent.section == SettingsSection.MCP) {
                    loadMcpServers()
                } else if (intent.section == SettingsSection.SKILLS) {
                    loadSkills()
                } else if (
                    intent.section == SettingsSection.GENERAL &&
                    rightDrawerStore.state.value.kind == RightDrawerKind.SETTINGS
                ) {
                    detectCodexEnvironment()
                }
            }
            UiIntent.OpenAttachmentPicker -> {
                val selected = pickAttachments()
                if (selected.isNotEmpty()) {
                    eventHub.publishUiIntent(UiIntent.AddAttachments(selected))
                }
            }
            UiIntent.PasteImageFromClipboard -> pasteImageFromClipboard()
            is UiIntent.OpenEditedFileDiff -> openEditedFileDiff(intent.path)
            is UiIntent.RevertEditedFile -> revertEditedFile(intent.path)
            UiIntent.RevertAllEditedFiles -> revertAllEditedFiles()
            is UiIntent.RequestMentionSuggestions -> {
                val query = intent.query.trim()
                val paths = if (query.isBlank()) {
                    recentFocusedFiles.toList().take(MENTION_LIMIT)
                } else {
                    searchProjectFiles(query, MENTION_LIMIT)
                }
                val suggestions = paths.map { toContextEntry(it) }
                eventHub.publish(
                    AppEvent.MentionSuggestionsUpdated(
                        query = query,
                        documentVersion = intent.documentVersion,
                        suggestions = suggestions,
                    ),
                )
            }
            is UiIntent.RequestAgentSuggestions -> {
                val query = intent.query.trim()
                val suggestions = settingsService.savedAgents()
                    .filter { agent ->
                        query.isBlank() || agent.name.contains(query, ignoreCase = true)
                    }
                    .take(MENTION_LIMIT)
                eventHub.publish(
                    AppEvent.AgentSuggestionsUpdated(
                        query = query,
                        documentVersion = intent.documentVersion,
                        suggestions = suggestions,
                    ),
                )
            }
            is UiIntent.UpdateFocusedContextFile -> recordFocusedFile(intent.snapshot?.path)
            is UiIntent.EditSettingsLanguageMode -> applyLanguagePreview(intent.mode)
            is UiIntent.EditSettingsThemeMode -> applyThemePreview(intent.mode)
            is UiIntent.EditSettingsAutoContextEnabled -> applyAutoContextPreference(intent.enabled)
            is UiIntent.SubmitApprovalAction -> submitApprovalDecision(intent.action)
            UiIntent.SubmitToolUserInputPrompt -> submitToolUserInputPrompt(cancelled = false)
            UiIntent.CancelToolUserInputPrompt -> submitToolUserInputPrompt(cancelled = true)
            UiIntent.ExecuteApprovedPlan -> executeApprovedPlan()
            UiIntent.SubmitPlanRevision -> submitPlanRevision()
            UiIntent.RequestPlanRevision -> requestPlanRevision()
            UiIntent.DismissPlanCompletionPrompt -> dismissPlanCompletionPrompt()
            is UiIntent.SelectAgent -> persistSelectedAgent(intent.agent.id)
            is UiIntent.RemoveSelectedAgent -> persistDeselectedAgent(intent.id)
            is UiIntent.SelectModel -> {
                settingsService.setSelectedComposerModel(intent.model)
                publishSettingsSnapshot()
            }
            is UiIntent.SelectReasoning -> {
                settingsService.setSelectedComposerReasoning(intent.reasoning.effort)
                publishSettingsSnapshot()
            }
            UiIntent.SaveCustomModel -> saveCustomModel()
            is UiIntent.DeleteCustomModel -> deleteCustomModel(intent.model)
            UiIntent.SaveAgentDraft -> saveAgentDraft()
            is UiIntent.DeleteSavedAgent -> deleteSavedAgent(intent.id)
            UiIntent.LoadSkills -> loadSkills()
            UiIntent.RefreshSkills -> loadSkills(forceReload = true)
            is UiIntent.ToggleSkillEnabled -> toggleSkillEnabled(
                name = intent.name,
                path = intent.path,
                enabled = intent.enabled,
            )
            is UiIntent.OpenSkillPath -> openSkillPath(intent.path)
            is UiIntent.RevealSkillPath -> revealSkillPath(intent.path)
            is UiIntent.UninstallSkill -> uninstallSkill(intent.name, intent.path)
            UiIntent.CreateNewMcpDraft -> loadMcpEditorDraft()
            is UiIntent.SelectMcpServerForEdit -> loadMcpEditorDraft()
            UiIntent.SaveMcpDraft -> saveMcpDraft()
            is UiIntent.ToggleMcpServerEnabled -> toggleMcpServerEnabled(intent.name, intent.enabled)
            is UiIntent.DeleteMcpServer -> deleteMcpServer(intent.name)
            is UiIntent.TestMcpServer -> testMcpServer(intent.name)
            is UiIntent.LoginMcpServer -> authenticateMcpServer(intent.name, login = true)
            is UiIntent.LogoutMcpServer -> authenticateMcpServer(intent.name, login = false)
            UiIntent.DetectCodexEnvironment -> detectCodexEnvironment()
            UiIntent.TestCodexEnvironment -> testCodexEnvironment()
            UiIntent.SaveSettings -> saveSettings()
            else -> Unit
        }
    }

    private fun handleUnifiedEvent(event: UnifiedEvent) {
        when (event) {
            is UnifiedEvent.ApprovalRequested -> {
                eventHub.publish(AppEvent.ApprovalRequested(event.request.toUiModel()))
            }

            is UnifiedEvent.ToolUserInputRequested -> {
                val prompt = event.prompt.toUiModel()
                eventHub.publish(AppEvent.ToolUserInputRequested(prompt))
                eventHub.publish(
                    AppEvent.TimelineMutationApplied(
                        mutation = TimelineMutation.UpsertUserInput(
                            sourceId = toolUserInputSourceId(prompt),
                            title = AuraCodeBundle.message("timeline.userInput.title"),
                            body = AuraCodeBundle.message("timeline.userInput.waiting"),
                            status = com.auracode.assistant.protocol.ItemStatus.RUNNING,
                            turnId = prompt.turnId,
                        ),
                    ),
                )
            }

            is UnifiedEvent.ToolUserInputResolved -> {
                toolUserInputPromptStore.state.value.queue.firstOrNull { it.requestId == event.requestId }?.let { prompt ->
                    eventHub.publish(
                        AppEvent.TimelineMutationApplied(
                            mutation = TimelineMutation.UpsertUserInput(
                                sourceId = toolUserInputSourceId(prompt),
                                title = AuraCodeBundle.message("timeline.userInput.title"),
                                body = AuraCodeBundle.message("timeline.userInput.cancelled"),
                                status = com.auracode.assistant.protocol.ItemStatus.FAILED,
                                turnId = prompt.turnId,
                            ),
                        ),
                    )
                }
                eventHub.publish(AppEvent.ToolUserInputResolved(event.requestId))
            }

            is UnifiedEvent.ThreadStarted -> {
                activePlanRunContext = activePlanRunContext?.apply {
                    threadId = event.threadId
                } ?: return
            }

            is UnifiedEvent.TurnStarted -> {
                activePlanRunContext = activePlanRunContext?.apply {
                    remoteTurnId = event.turnId
                    threadId = event.threadId ?: threadId
                } ?: return
            }

            is UnifiedEvent.ThreadTokenUsageUpdated -> {
                publishSessionSnapshot()
            }

            is UnifiedEvent.TurnDiffUpdated -> {
                eventHub.publish(
                    AppEvent.TurnDiffUpdated(
                        threadId = event.threadId,
                        turnId = event.turnId,
                        diff = event.diff,
                    ),
                )
            }

            is UnifiedEvent.RunningPlanUpdated -> {
                activePlanRunContext = activePlanRunContext?.apply {
                    latestPlanBody = event.body.trim().takeIf { it.isNotBlank() } ?: latestPlanBody
                    threadId = event.threadId ?: threadId
                    remoteTurnId = event.turnId.takeIf { it.isNotBlank() } ?: remoteTurnId
                } ?: return
                eventHub.publish(
                    AppEvent.RunningPlanUpdated(
                        plan = ComposerRunningPlanState(
                            threadId = event.threadId ?: activePlanRunContext?.threadId,
                            turnId = event.turnId,
                            explanation = event.explanation,
                            steps = event.steps.map { step ->
                                ComposerRunningPlanStep(
                                    step = step.step,
                                    status = step.status.toComposerRunningPlanStepStatus(),
                                )
                            },
                        ),
                    ),
                )
            }

            is UnifiedEvent.ItemUpdated -> {
                if (event.item.kind == com.auracode.assistant.protocol.ItemKind.PLAN_UPDATE) {
                    activePlanRunContext = activePlanRunContext?.apply {
                        latestPlanBody = event.item.text?.trim()?.takeIf { it.isNotBlank() } ?: latestPlanBody
                    } ?: return
                }
            }

            is UnifiedEvent.TurnCompleted -> {
                eventHub.publish(AppEvent.ClearApprovals)
                eventHub.publish(AppEvent.ClearToolUserInputs)
                handlePlanTurnCompleted(event)
            }

            else -> Unit
        }
        when (event) {
            is UnifiedEvent.ApprovalRequested,
            is UnifiedEvent.ToolUserInputRequested,
            is UnifiedEvent.ToolUserInputResolved,
            is UnifiedEvent.TurnCompleted,
            -> publishSessionSnapshot()
            else -> Unit
        }
    }

    private fun applyLanguagePreview(mode: com.auracode.assistant.settings.UiLanguageMode) {
        if (settingsService.uiLanguageMode() == mode) return
        settingsService.setUiLanguageMode(mode)
        settingsService.notifyLanguageChanged()
        publishSettingsSnapshot()
    }

    private fun applyThemePreview(mode: com.auracode.assistant.settings.UiThemeMode) {
        if (settingsService.uiThemeMode() == mode) return
        settingsService.setUiThemeMode(mode)
        settingsService.notifyAppearanceChanged()
        publishSettingsSnapshot()
    }

    private fun applyAutoContextPreference(enabled: Boolean) {
        if (settingsService.autoContextEnabled() == enabled) return
        settingsService.setAutoContextEnabled(enabled)
        publishSettingsSnapshot()
    }

    private fun saveSettings() {
        val drawerState = rightDrawerStore.state.value
        val oldLanguage = settingsService.uiLanguageMode()
        val oldTheme = settingsService.uiThemeMode()
        val state = settingsService.state
        state.setExecutablePathFor("codex", drawerState.codexCliPath.trim())
        settingsService.setNodeExecutablePath(drawerState.nodePath.trim())
        settingsService.setUiLanguageMode(drawerState.languageMode)
        settingsService.setUiThemeMode(drawerState.themeMode)
        settingsService.setAutoContextEnabled(drawerState.autoContextEnabled)
        if (oldLanguage != settingsService.uiLanguageMode()) {
            settingsService.notifyLanguageChanged()
        }
        if (oldTheme != settingsService.uiThemeMode()) {
            settingsService.notifyAppearanceChanged()
        }
        publishSettingsSnapshot()
    }

    private fun detectCodexEnvironment() {
        val drawerState = rightDrawerStore.state.value
        eventHub.publish(AppEvent.CodexEnvironmentCheckRunning(true))
        scope.launch {
            runCatching {
                codexEnvironmentDetector.autoDetect(
                    configuredCodexPath = drawerState.codexCliPath,
                    configuredNodePath = drawerState.nodePath,
                )
            }.onSuccess { result ->
                eventHub.publish(
                    AppEvent.CodexEnvironmentCheckUpdated(
                        result = result,
                        updateDraftPaths = true,
                    ),
                )
                eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(result.message)))
            }.onFailure { error ->
                eventHub.publish(AppEvent.CodexEnvironmentCheckRunning(false))
                eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to detect Codex environment.")))
            }
        }
    }

    private fun testCodexEnvironment() {
        val drawerState = rightDrawerStore.state.value
        eventHub.publish(AppEvent.CodexEnvironmentCheckRunning(true))
        scope.launch {
            runCatching {
                codexEnvironmentDetector.testEnvironment(
                    configuredCodexPath = drawerState.codexCliPath,
                    configuredNodePath = drawerState.nodePath,
                )
            }.onSuccess { result ->
                eventHub.publish(AppEvent.CodexEnvironmentCheckUpdated(result = result))
                eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(result.message)))
            }.onFailure { error ->
                eventHub.publish(AppEvent.CodexEnvironmentCheckRunning(false))
                eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to test Codex environment.")))
            }
        }
    }

    private fun saveAgentDraft() {
        val drawerState = rightDrawerStore.state.value
        val id = drawerState.editingAgentId?.takeIf { it.isNotBlank() }
        val name = drawerState.agentDraftName.trim()
        val prompt = drawerState.agentDraftPrompt.trim()
        if (name.isBlank() || prompt.isBlank()) {
            eventHub.publish(AppEvent.StatusTextUpdated(com.auracode.assistant.toolwindow.shared.UiText.raw("Agent name and prompt are required.")))
            return
        }
        val state = settingsService.state
        val duplicate = state.savedAgents.any { agent ->
            agent.id != id && agent.name.trim().equals(name, ignoreCase = true)
        }
        if (duplicate) {
            eventHub.publish(AppEvent.StatusTextUpdated(com.auracode.assistant.toolwindow.shared.UiText.raw("Agent name must be unique.")))
            return
        }
        val saved = SavedAgentDefinition(
            id = id ?: java.util.UUID.randomUUID().toString(),
            name = name,
            prompt = prompt,
        )
        val updated = state.savedAgents.toMutableList()
        val index = updated.indexOfFirst { it.id == saved.id }
        if (index >= 0) {
            updated[index] = saved
        } else {
            updated += saved
        }
        state.savedAgents = updated
        publishSettingsSnapshot()
        eventHub.publishUiIntent(UiIntent.ShowAgentSettingsList)
    }

    private fun saveCustomModel() {
        val draft = composerStore.state.value.customModelDraft.trim()
        if (draft.isBlank()) {
            eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(AuraCodeBundle.message("composer.model.custom.error.blank"))))
            return
        }
        val existing = settingsService.customModelIds()
        if (CodexModelCatalog.ids().contains(draft) || existing.contains(draft)) {
            eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(AuraCodeBundle.message("composer.model.custom.error.duplicate"))))
            return
        }
        settingsService.setCustomModelIds(existing + draft)
        publishSettingsSnapshot()
        eventHub.publishUiIntent(UiIntent.SelectModel(draft))
    }

    private fun deleteCustomModel(model: String) {
        val normalized = model.trim()
        if (normalized.isBlank()) return
        val existing = settingsService.customModelIds()
        val updated = existing.filterNot { it == normalized }
        if (updated.size == existing.size) return
        settingsService.setCustomModelIds(updated)
        publishSettingsSnapshot()
        if (composerStore.state.value.selectedModel == normalized) {
            eventHub.publishUiIntent(UiIntent.SelectModel(CodexModelCatalog.defaultModel))
        }
    }

    private fun deleteSavedAgent(id: String) {
        val state = settingsService.state
        val updated = state.savedAgents.filterNot { it.id == id }.toMutableList()
        if (updated.size == state.savedAgents.size) return
        state.savedAgents = updated
        settingsService.deselectAgent(id)
        publishSettingsSnapshot()
        eventHub.publishUiIntent(UiIntent.ShowAgentSettingsList)
    }

    private fun persistSelectedAgent(id: String) {
        settingsService.selectAgent(id)
        publishSettingsSnapshot()
    }

    private fun persistDeselectedAgent(id: String) {
        settingsService.deselectAgent(id)
        publishSettingsSnapshot()
    }

    private fun loadSkills(forceReload: Boolean = false) {
        eventHub.publish(AppEvent.SkillsLoadingChanged(loading = true))
        launchLogged("loadSkills") {
            runCatching {
                publishSkillsSnapshot(forceReload = forceReload)
            }.onFailure { error ->
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        UiText.raw(error.message ?: "Failed to load runtime skills."),
                    ),
                )
            }
            eventHub.publish(AppEvent.SkillsLoadingChanged(loading = false))
        }
    }

    /**
     * Preloads runtime skills in the background so composer suggestions can rely
     * on the same engine-backed cache without requiring the settings page first.
     */
    private fun warmSkillsRuntimeCache() {
        launchLogged("warmSkillsRuntimeCache") {
            runCatching {
                publishSkillsSnapshot(forceReload = false)
            }
        }
    }

    /** Reads the current runtime snapshot and publishes the unified skills page state. */
    private suspend fun publishSkillsSnapshot(forceReload: Boolean) {
        val snapshot = skillsRuntimeService.getSkills(
            engineId = chatService.defaultEngineId(),
            cwd = chatService.currentWorkingDirectory(),
            forceReload = forceReload,
        )
        eventHub.publish(
            AppEvent.SkillsLoaded(
                engineId = snapshot.engineId,
                cwd = snapshot.cwd,
                skills = snapshot.skills,
                supportsRuntimeSkills = snapshot.supportsRuntimeSkills,
                stale = snapshot.stale,
                errorMessage = snapshot.errorMessage,
            ),
        )
    }

    private fun toggleSkillEnabled(name: String, path: String, enabled: Boolean) {
        if (name.isBlank() || path.isBlank()) return
        eventHub.publish(AppEvent.SkillsLoadingChanged(loading = true, activePath = path))
        launchLogged("toggleSkillEnabled($name,$enabled)") {
            runCatching {
                val snapshot = skillsRuntimeService.setSkillEnabled(
                    engineId = chatService.defaultEngineId(),
                    cwd = chatService.currentWorkingDirectory(),
                    selector = SkillSelector.ByPath(path),
                    enabled = enabled,
                )
                eventHub.publish(
                    AppEvent.SkillsLoaded(
                        engineId = snapshot.engineId,
                        cwd = snapshot.cwd,
                        skills = snapshot.skills,
                        supportsRuntimeSkills = snapshot.supportsRuntimeSkills,
                        stale = snapshot.stale,
                        errorMessage = snapshot.errorMessage,
                    ),
                )
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        UiText.raw(
                            if (enabled) "Enabled skill '$name'." else "Disabled skill '$name'.",
                        ),
                    ),
                )
            }.onFailure { error ->
                eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to update skill '$name'.")))
            }
            eventHub.publish(AppEvent.SkillsLoadingChanged(loading = false))
        }
    }

    private fun openSkillPath(path: String) {
        if (path.isBlank()) return
        openTimelineFilePath(path)
    }

    private fun revealSkillPath(path: String) {
        if (path.isBlank()) return
        if (!revealPathInFileManager(path)) {
            eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw("Failed to reveal skill location.")))
        }
    }

    private fun uninstallSkill(name: String, path: String) {
        if (name.isBlank() || path.isBlank()) return
        eventHub.publish(AppEvent.SkillsLoadingChanged(loading = true, activePath = path))
        launchLogged("uninstallSkill($name)") {
            runCatching {
                localSkillInstallPolicy.uninstall(path).getOrThrow()
                publishSkillsSnapshot(forceReload = true)
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        UiText.raw("Uninstalled local skill '$name'."),
                    ),
                )
            }.onFailure { error ->
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        UiText.raw(error.message ?: "Failed to uninstall skill '$name'."),
                    ),
                )
            }
            eventHub.publish(AppEvent.SkillsLoadingChanged(loading = false))
        }
    }

    private fun loadMcpServers() {
        updateMcpBusy { copy(loading = true) }
        logMcpDiagnostic("MCP coroutine scheduled: label=loadMcpServers | ${mcpContextSnapshot()}")
        launchLogged("loadMcpServers") {
            logMcpDiagnostic("MCP coroutine start: label=loadMcpServers | ${mcpContextSnapshot()}")
            runCatching {
                val adapter = mcpAdapter()
                logMcpDiagnostic("MCP adapter call begin: label=loadMcpServers step=listServers | ${mcpContextSnapshot()}")
                val servers = adapter.listServers()
                logMcpDiagnostic("MCP adapter call success: label=loadMcpServers step=listServers count=${servers.size} | ${mcpContextSnapshot()}")
                logMcpDiagnostic("MCP adapter call begin: label=loadMcpServers step=refreshStatuses | ${mcpContextSnapshot()}")
                val statuses = adapter.refreshStatuses()
                logMcpDiagnostic("MCP adapter call success: label=loadMcpServers step=refreshStatuses count=${statuses.size} | ${mcpContextSnapshot()}")
                eventHub.publish(AppEvent.McpServersLoaded(servers))
                eventHub.publish(AppEvent.McpStatusesUpdated(statuses))
            }.onFailure { error ->
                logMcpDiagnostic(
                    message = "MCP coroutine handled failure: label=loadMcpServers | ${mcpContextSnapshot()}",
                    error = error,
                )
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        com.auracode.assistant.toolwindow.shared.UiText.raw(
                            error.message ?: "Failed to load MCP servers.",
                        ),
                    ),
                )
            }
            updateMcpBusy { copy(loading = false) }
            logMcpDiagnostic("MCP coroutine finish: label=loadMcpServers | ${mcpContextSnapshot()}")
        }
    }

    private fun refreshMcpStatuses() {
        updateMcpBusy { copy(loading = true) }
        logMcpDiagnostic("MCP coroutine scheduled: label=refreshMcpStatuses | ${mcpContextSnapshot()}")
        launchLogged("refreshMcpStatuses") {
            logMcpDiagnostic("MCP coroutine start: label=refreshMcpStatuses | ${mcpContextSnapshot()}")
            runCatching {
                logMcpDiagnostic("MCP adapter call begin: label=refreshMcpStatuses step=refreshStatuses | ${mcpContextSnapshot()}")
                val statuses = mcpAdapter().refreshStatuses()
                logMcpDiagnostic("MCP adapter call success: label=refreshMcpStatuses step=refreshStatuses count=${statuses.size} | ${mcpContextSnapshot()}")
                eventHub.publish(AppEvent.McpStatusesUpdated(statuses))
            }.onFailure { error ->
                logMcpDiagnostic(
                    message = "MCP coroutine handled failure: label=refreshMcpStatuses | ${mcpContextSnapshot()}",
                    error = error,
                )
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        com.auracode.assistant.toolwindow.shared.UiText.raw(
                            error.message ?: "Failed to refresh MCP runtime status.",
                        ),
                    ),
                )
            }
            updateMcpBusy { copy(loading = false) }
            logMcpDiagnostic("MCP coroutine finish: label=refreshMcpStatuses | ${mcpContextSnapshot()}")
        }
    }

    private fun loadMcpEditorDraft() {
        updateMcpBusy { copy(loading = true) }
        logMcpDiagnostic("MCP coroutine scheduled: label=loadMcpEditorDraft | ${mcpContextSnapshot()}")
        launchLogged("loadMcpEditorDraft") {
            logMcpDiagnostic("MCP coroutine start: label=loadMcpEditorDraft | ${mcpContextSnapshot()}")
            runCatching {
                logMcpDiagnostic("MCP adapter call begin: label=loadMcpEditorDraft step=getEditorDraft | ${mcpContextSnapshot()}")
                val draft = mcpAdapter().getEditorDraft()
                logMcpDiagnostic("MCP adapter call success: label=loadMcpEditorDraft step=getEditorDraft | ${mcpContextSnapshot()}")
                eventHub.publish(AppEvent.McpDraftLoaded(draft))
            }.onFailure { error ->
                logMcpDiagnostic(
                    message = "MCP coroutine handled failure: label=loadMcpEditorDraft | ${mcpContextSnapshot()}",
                    error = error,
                )
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        com.auracode.assistant.toolwindow.shared.UiText.raw(
                            error.message ?: "Failed to load MCP server JSON.",
                        ),
                    ),
                )
            }
            updateMcpBusy { copy(loading = false) }
            logMcpDiagnostic("MCP coroutine finish: label=loadMcpEditorDraft | ${mcpContextSnapshot()}")
        }
    }

    private fun saveMcpDraft(afterSave: (suspend (List<String>) -> Unit)? = null) {
        val normalized = rightDrawerStore.state.value.mcpDraft.normalized()
        val validation = normalized.validate()
        eventHub.publish(AppEvent.McpValidationErrorsUpdated(validation))
        if (validation.hasAny()) {
            eventHub.publish(
                AppEvent.McpFeedbackUpdated(
                    message = "Fix the MCP config errors before saving.",
                    isError = true,
                ),
            )
            eventHub.publish(
                AppEvent.StatusTextUpdated(
                    com.auracode.assistant.toolwindow.shared.UiText.raw("Please fix the MCP config errors before saving."),
                ),
            )
            return
        }
        val entries = normalized.parseServerEntries().getOrElse { error ->
            eventHub.publish(
                AppEvent.McpValidationErrorsUpdated(
                    McpValidationErrors(json = error.message ?: "Invalid MCP JSON."),
                ),
            )
            return
        }
        updateMcpBusy { copy(saving = true) }
        logMcpDiagnostic("MCP coroutine scheduled: label=saveMcpDraft(count=${entries.size}) | ${mcpContextSnapshot()}")
        launchLogged("saveMcpDraft(count=${entries.size})") {
            logMcpDiagnostic("MCP coroutine start: label=saveMcpDraft(count=${entries.size}) | ${mcpContextSnapshot()}")
            runCatching {
                val adapter = mcpAdapter()
                val drafts = entries.map { entry ->
                    McpServerDraft(
                        name = entry.name,
                        configJson = McpServerDraft.entryConfigJson(entry.name, entry.config),
                    )
                }
                val savedNames = drafts.map { it.name.trim() }
                logMcpDiagnostic("MCP adapter call begin: label=saveMcpDraft(count=${entries.size}) step=saveServers names=${savedNames.joinToString(",")} | ${mcpContextSnapshot()}")
                adapter.saveServers(drafts)
                logMcpDiagnostic("MCP adapter call success: label=saveMcpDraft(count=${entries.size}) step=saveServers names=${savedNames.joinToString(",")} | ${mcpContextSnapshot()}")
                eventHub.publish(
                    AppEvent.McpDraftLoaded(
                        normalized.copy(
                            originalName = null,
                            name = "",
                            configJson = McpServerDraft.serversConfigJson(entries),
                        ),
                    ),
                )
                logMcpDiagnostic("MCP adapter call begin: label=saveMcpDraft(count=${entries.size}) step=listServers | ${mcpContextSnapshot()}")
                eventHub.publish(AppEvent.McpServersLoaded(adapter.listServers()))
                logMcpDiagnostic("MCP adapter call success: label=saveMcpDraft(count=${entries.size}) step=listServers | ${mcpContextSnapshot()}")
                logMcpDiagnostic("MCP adapter call begin: label=saveMcpDraft(count=${entries.size}) step=refreshStatuses | ${mcpContextSnapshot()}")
                eventHub.publish(AppEvent.McpStatusesUpdated(adapter.refreshStatuses()))
                logMcpDiagnostic("MCP adapter call success: label=saveMcpDraft(count=${entries.size}) step=refreshStatuses | ${mcpContextSnapshot()}")
                val feedbackMessage = when (savedNames.size) {
                    1 -> "Saved MCP server '${savedNames.single()}'."
                    else -> "Saved ${savedNames.size} MCP servers."
                }
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        com.auracode.assistant.toolwindow.shared.UiText.raw(feedbackMessage),
                    ),
                )
                eventHub.publish(
                    AppEvent.McpFeedbackUpdated(
                        message = feedbackMessage,
                        isError = false,
                    ),
                )
                afterSave?.invoke(savedNames)
            }.onFailure { error ->
                logMcpDiagnostic(
                    message = "MCP coroutine handled failure: label=saveMcpDraft(count=${entries.size}) | ${mcpContextSnapshot()}",
                    error = error,
                )
                eventHub.publish(
                    AppEvent.McpFeedbackUpdated(
                        message = error.message ?: "Failed to save MCP server.",
                        isError = true,
                    ),
                )
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        com.auracode.assistant.toolwindow.shared.UiText.raw(
                            error.message ?: "Failed to save MCP server.",
                        ),
                    ),
                )
            }
            updateMcpBusy { copy(saving = false) }
            logMcpDiagnostic("MCP coroutine finish: label=saveMcpDraft(count=${entries.size}) | ${mcpContextSnapshot()}")
        }
    }

    private fun deleteMcpServer(name: String) {
        if (name.isBlank()) return
        updateMcpBusy { copy(deletingName = name) }
        launchLogged("deleteMcpServer($name)") {
            runCatching {
                if (!mcpAdapter().deleteServer(name)) return@runCatching
                eventHub.publishUiIntent(UiIntent.ShowMcpSettingsList)
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        com.auracode.assistant.toolwindow.shared.UiText.raw("Deleted MCP server '$name'."),
                    ),
                )
                eventHub.publishUiIntent(UiIntent.LoadMcpServers)
            }.onFailure { error ->
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        com.auracode.assistant.toolwindow.shared.UiText.raw(
                            error.message ?: "Failed to delete MCP server '$name'.",
                        ),
                    ),
                )
            }
            updateMcpBusy { copy(deletingName = null) }
        }
    }

    private fun toggleMcpServerEnabled(name: String, enabled: Boolean) {
        if (name.isBlank()) return
        updateMcpBusy { copy(loading = true) }
        launchLogged("toggleMcpServerEnabled($name,$enabled)") {
            runCatching {
                val adapter = mcpAdapter()
                adapter.setServerEnabled(name, enabled)
                eventHub.publish(AppEvent.McpServersLoaded(adapter.listServers()))
            }.onFailure { error ->
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        com.auracode.assistant.toolwindow.shared.UiText.raw(
                            error.message ?: "Failed to update MCP server '$name'.",
                        ),
                    ),
                )
            }
            updateMcpBusy { copy(loading = false) }
        }
    }

    private fun testMcpServer(name: String?) {
        val savedName = name?.trim().takeUnless { it.isNullOrBlank() }
        if (savedName != null) {
            runMcpServerTest(savedName)
            return
        }
        saveMcpDraft(afterSave = { draftNames ->
            draftNames.firstOrNull()?.let(::runMcpServerTest)
        })
    }

    private fun runMcpServerTest(name: String) {
        updateMcpBusy { copy(testingName = name) }
        logMcpDiagnostic("MCP coroutine scheduled: label=runMcpServerTest($name) | ${mcpContextSnapshot(requestedName = name)}")
        launchLogged("runMcpServerTest($name)") {
            logMcpDiagnostic("MCP coroutine start: label=runMcpServerTest($name) | ${mcpContextSnapshot(requestedName = name)}")
            runCatching {
                val adapter = mcpAdapter()
                logMcpDiagnostic("MCP adapter call begin: label=runMcpServerTest($name) step=testServer | ${mcpContextSnapshot(requestedName = name)}")
                val result: McpTestResult = adapter.testServer(name)
                logMcpDiagnostic("MCP adapter call success: label=runMcpServerTest($name) step=testServer success=${result.success} | ${mcpContextSnapshot(requestedName = name)}")
                eventHub.publish(AppEvent.McpTestResultUpdated(name = name, result = result))
                logMcpDiagnostic("MCP adapter call begin: label=runMcpServerTest($name) step=refreshStatuses | ${mcpContextSnapshot(requestedName = name)}")
                eventHub.publish(AppEvent.McpStatusesUpdated(adapter.refreshStatuses()))
                logMcpDiagnostic("MCP adapter call success: label=runMcpServerTest($name) step=refreshStatuses | ${mcpContextSnapshot(requestedName = name)}")
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        com.auracode.assistant.toolwindow.shared.UiText.raw(
                            if (result.success) {
                                "MCP server '$name' refreshed: ${result.summary}"
                            } else {
                                "MCP server '$name' test failed: ${result.summary}"
                            },
                        ),
                    ),
                )
                eventHub.publish(
                    AppEvent.McpFeedbackUpdated(
                        message = if (result.success) {
                            "Test succeeded: ${result.summary}"
                        } else {
                            "Test failed: ${result.summary}"
                        },
                        isError = !result.success,
                    ),
                )
            }.onFailure { error ->
                logMcpDiagnostic(
                    message = "MCP coroutine handled failure: label=runMcpServerTest($name) | ${mcpContextSnapshot(requestedName = name)}",
                    error = error,
                )
                eventHub.publish(
                    AppEvent.McpFeedbackUpdated(
                        message = error.message ?: "Failed to test MCP server '$name'.",
                        isError = true,
                    ),
                )
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        com.auracode.assistant.toolwindow.shared.UiText.raw(
                            error.message ?: "Failed to test MCP server '$name'.",
                        ),
                    ),
                )
            }
            updateMcpBusy { copy(testingName = null) }
            logMcpDiagnostic("MCP coroutine finish: label=runMcpServerTest($name) | ${mcpContextSnapshot(requestedName = name)}")
        }
    }

    private fun authenticateMcpServer(name: String, login: Boolean) {
        if (name.isBlank()) return
        updateMcpBusy { copy(authenticatingName = name) }
        logMcpDiagnostic("MCP coroutine scheduled: label=authenticateMcpServer($name, login=$login) | ${mcpContextSnapshot(requestedName = name)}")
        launchLogged("authenticateMcpServer($name, login=$login)") {
            logMcpDiagnostic("MCP coroutine start: label=authenticateMcpServer($name, login=$login) | ${mcpContextSnapshot(requestedName = name)}")
            runCatching {
                val adapter = mcpAdapter()
                logMcpDiagnostic("MCP adapter call begin: label=authenticateMcpServer($name, login=$login) step=${if (login) "login" else "logout"} | ${mcpContextSnapshot(requestedName = name)}")
                val result: McpAuthActionResult = if (login) {
                    adapter.login(name)
                } else {
                    adapter.logout(name)
                }
                logMcpDiagnostic("MCP adapter call success: label=authenticateMcpServer($name, login=$login) step=${if (login) "login" else "logout"} | ${mcpContextSnapshot(requestedName = name)}")
                logMcpDiagnostic("MCP adapter call begin: label=authenticateMcpServer($name, login=$login) step=refreshStatuses | ${mcpContextSnapshot(requestedName = name)}")
                eventHub.publish(AppEvent.McpStatusesUpdated(adapter.refreshStatuses()))
                logMcpDiagnostic("MCP adapter call success: label=authenticateMcpServer($name, login=$login) step=refreshStatuses | ${mcpContextSnapshot(requestedName = name)}")
                val openedInBrowser = result.authorizationUrl?.let(openExternalUrl) == true
                val suffix = when {
                    openedInBrowser -> " Opened the authorization page in your browser."
                    !result.authorizationUrl.isNullOrBlank() -> " ${result.authorizationUrl}"
                    else -> ""
                }
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        com.auracode.assistant.toolwindow.shared.UiText.raw(result.message + suffix),
                    ),
                )
            }.onFailure { error ->
                logMcpDiagnostic(
                    message = "MCP coroutine handled failure: label=authenticateMcpServer($name, login=$login) | ${mcpContextSnapshot(requestedName = name)}",
                    error = error,
                )
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        com.auracode.assistant.toolwindow.shared.UiText.raw(
                            error.message ?: "Failed to update MCP authentication for '$name'.",
                        ),
                    ),
                )
            }
            updateMcpBusy { copy(authenticatingName = null) }
            logMcpDiagnostic("MCP coroutine finish: label=authenticateMcpServer($name, login=$login) | ${mcpContextSnapshot(requestedName = name)}")
        }
    }

    private fun updateMcpBusy(transform: McpBusyState.() -> McpBusyState) {
        eventHub.publish(
            AppEvent.McpBusyStateUpdated(
                rightDrawerStore.state.value.mcpBusyState.transform(),
            ),
        )
    }

    private fun mcpAdapter(): McpManagementAdapter = mcpAdapterRegistry.defaultAdapter()

    private fun deleteSession(sessionId: String) {
        if (!chatService.deleteSession(sessionId)) return
        publishSessionSnapshot()
        restoreCurrentSessionHistory()
    }

    private fun switchSession(sessionId: String) {
        if (!chatService.switchSession(sessionId)) return
        publishSessionSnapshot()
        restoreCurrentSessionHistory()
    }

    private fun openRemoteConversation(remoteConversationId: String, title: String) {
        chatService.openRemoteConversation(
            remoteConversationId = remoteConversationId,
            suggestedTitle = title,
        ) ?: return
        publishSessionSnapshot()
        restoreCurrentSessionHistory()
        eventHub.publishUiIntent(UiIntent.CloseRightDrawer)
        onSessionSnapshotPublished()
    }

    private fun recordFocusedFile(path: String?) {
        val normalized = path?.trim().orEmpty()
        if (normalized.isBlank() || !isMentionCandidateFile(normalized)) return
        recentFocusedFiles.remove(normalized)
        recentFocusedFiles.addFirst(normalized)
        while (recentFocusedFiles.size > 64) {
            recentFocusedFiles.removeLast()
        }
    }

    private fun submitPromptIfAllowed() {
        val composerState = composerStore.state.value
        val submission = buildPendingSubmission(composerState) ?: return
        if (composerState.sessionIsRunning || timelineStore.state.value.isRunning) {
            enqueuePendingSubmission(submission)
        } else {
            dispatchSubmission(submission)
        }
    }

    private fun cancelPromptRun() {
        chatService.cancelCurrent()
        resetPlanFlowState()
        eventHub.publish(AppEvent.ActiveRunCancelled)
        publishUnifiedEvent(
            UnifiedEvent.TurnCompleted(
                turnId = "",
                outcome = TurnOutcome.CANCELLED,
                usage = null,
            ),
        )
    }

    private fun publishSessionSnapshot() {
        eventHub.publish(
            AppEvent.SessionSnapshotUpdated(
                sessions = chatService.listSessions(),
                activeSessionId = chatService.getCurrentSessionId(),
            ),
        )
        onSessionSnapshotPublished()
    }

    private fun buildPendingSubmission(composerState: com.auracode.assistant.toolwindow.composer.ComposerAreaState): PendingComposerSubmission? {
        val disabledSkills = skillsRuntimeService.findDisabledSkillMentions(
            engineId = chatService.defaultEngineId(),
            cwd = chatService.currentWorkingDirectory(),
            text = composerState.inputText,
        )
        if (disabledSkills.isNotEmpty()) {
            eventHub.publish(
                AppEvent.StatusTextUpdated(
                    UiText.raw("Disabled skills cannot be used: ${disabledSkills.joinToString(", ")}"),
                ),
            )
            return null
        }
        val prompt = composerState.serializedPrompt()
        val systemInstructions = composerState.serializedSystemInstructions()
        if (prompt.isBlank() && systemInstructions.isEmpty()) return null

        val stagedAttachments = stageAttachments(
            sessionId = chatService.getCurrentSessionId(),
            attachments = composerState.attachments,
        )
        return PendingComposerSubmission(
            id = "pending-${System.currentTimeMillis()}-${composerState.pendingSubmissions.size}",
            prompt = prompt,
            systemInstructions = systemInstructions,
            contextFiles = buildContextFiles(
                contextEntries = composerState.contextEntries,
                attachments = stagedAttachments,
            ),
            imageAttachments = stagedAttachments.filter { it.kind == PersistedAttachmentKind.IMAGE }.map {
                ImageAttachment(path = it.assetPath, name = it.displayName, mimeType = it.mimeType.ifBlank { "image/png" })
            },
            fileAttachments = stagedAttachments.filter { it.kind == PersistedAttachmentKind.FILE }.map {
                FileAttachment(path = it.assetPath, name = it.displayName, mimeType = it.mimeType.ifBlank { "application/octet-stream" })
            },
            stagedAttachments = stagedAttachments,
            selectedModel = composerState.selectedModel,
            selectedReasoning = composerState.selectedReasoning,
            executionMode = composerState.executionMode,
            planEnabled = composerState.planEnabled,
        )
    }

    private fun enqueuePendingSubmission(submission: PendingComposerSubmission) {
        pendingSubmissions.addLast(submission)
        publishPendingSubmissions(clearComposerDraft = true)
    }

    private fun removePendingSubmission(id: String) {
        val removed = pendingSubmissions.removeAll { it.id == id }
        if (!removed) return
        publishPendingSubmissions()
    }

    private fun dispatchNextPendingSubmissionIfIdle() {
        if (timelineStore.state.value.isRunning) return
        val next = pendingSubmissions.removeFirstOrNull() ?: return
        publishPendingSubmissions()
        dispatchSubmission(next)
    }

    private fun publishPendingSubmissions(clearComposerDraft: Boolean = false) {
        eventHub.publish(
            AppEvent.PendingSubmissionsUpdated(
                submissions = pendingSubmissions.toList(),
                clearComposerDraft = clearComposerDraft,
            ),
        )
    }

    private fun dispatchSubmission(submission: PendingComposerSubmission) {
        val localTurnId = "local-turn-${System.currentTimeMillis()}"
        val localMessage = if (submission.prompt.isBlank()) {
            null
        } else {
            chatService.recordUserMessage(
                prompt = submission.prompt,
                turnId = localTurnId,
                attachments = submission.stagedAttachments,
            )
        }
        if (submission.planEnabled) {
            activePlanRunContext = ActivePlanRunContext(
                localTurnId = localTurnId,
                preferredExecutionMode = submission.executionMode,
            )
        } else {
            activePlanRunContext = null
        }
        eventHub.publish(AppEvent.PlanCompletionPromptUpdated(prompt = null))
        eventHub.publish(AppEvent.ClearToolUserInputs)
        eventHub.publish(AppEvent.PromptAccepted(prompt = submission.prompt, localTurnId = localTurnId))
        localMessage?.let { message ->
            eventHub.publish(
                AppEvent.TimelineMutationApplied(
                    mutation = TimelineNodeMapper.localUserMessageMutation(
                        sourceId = message.sourceId,
                        text = message.prompt,
                        timestamp = message.timestamp,
                        turnId = message.turnId,
                        attachments = message.attachments,
                    ),
                ),
            )
        }
        publishSessionSnapshot()

        chatService.runAgent(
            engineId = chatService.defaultEngineId(),
            model = submission.selectedModel,
            reasoningEffort = submission.selectedReasoning.effort,
            prompt = submission.prompt,
            systemInstructions = submission.systemInstructions,
            localTurnId = localTurnId,
            contextFiles = submission.contextFiles,
            imageAttachments = submission.imageAttachments,
            fileAttachments = submission.fileAttachments,
            approvalMode = submission.executionMode.toApprovalMode(),
            collaborationMode = if (submission.planEnabled) AgentCollaborationMode.PLAN else AgentCollaborationMode.DEFAULT,
            onTurnPersisted = { publishSessionSnapshot() },
            onUnifiedEvent = { event -> publishUnifiedEvent(event) },
        )
    }

    private fun publishSettingsSnapshot() {
        val state = settingsService.state
        eventHub.publish(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = state.executablePathFor("codex"),
                nodePath = settingsService.nodeExecutablePath(),
                languageMode = settingsService.uiLanguageMode(),
                themeMode = settingsService.uiThemeMode(),
                autoContextEnabled = settingsService.autoContextEnabled(),
                savedAgents = state.savedAgents.toList(),
                selectedAgentIds = settingsService.selectedAgentIds(),
                customModelIds = settingsService.customModelIds(),
                selectedModel = settingsService.selectedComposerModel(),
                selectedReasoning = settingsService.selectedComposerReasoning(),
            ),
        )
    }

    private fun publishConversationCapabilities() {
        eventHub.publish(
            AppEvent.ConversationCapabilitiesUpdated(
                capabilities = chatService.conversationCapabilities(),
            ),
        )
    }

    private fun handlePlanTurnCompleted(event: UnifiedEvent.TurnCompleted) {
        val context = activePlanRunContext ?: return
        if (context.remoteTurnId != null && context.remoteTurnId != event.turnId) return
        activePlanRunContext = null
        eventHub.publish(AppEvent.RunningPlanUpdated(plan = null))
        if (event.outcome != TurnOutcome.SUCCESS) return
        val body = context.latestPlanBody?.trim().orEmpty()
        if (body.isBlank()) return
        eventHub.publish(
            AppEvent.PlanCompletionPromptUpdated(
                prompt = PlanCompletionPromptUiModel(
                    turnId = event.turnId,
                    threadId = context.threadId,
                    body = body,
                    preferredExecutionMode = context.preferredExecutionMode,
                ),
            ),
        )
    }

    private fun executeApprovedPlan() {
        val prompt = composerStore.state.value.planCompletion ?: return
        eventHub.publish(AppEvent.PlanCompletionPromptUpdated(prompt = null))
        eventHub.publishUiIntent(UiIntent.SelectMode(prompt.preferredExecutionMode))
        if (composerStore.state.value.planEnabled) {
            eventHub.publishUiIntent(UiIntent.TogglePlanMode)
        }
        startProgrammaticTurn(
            prompt = EXECUTE_APPROVED_PLAN_PROMPT,
            approvalMode = prompt.preferredExecutionMode.toApprovalMode(),
        )
    }

    private fun submitPlanRevision() {
        val planCompletion = composerStore.state.value.planCompletion ?: return
        val revision = planCompletion.revisionDraft.trim()
        if (revision.isBlank()) return
        if (!composerStore.state.value.planEnabled) {
            eventHub.publishUiIntent(UiIntent.TogglePlanMode)
        }
        activePlanRunContext = ActivePlanRunContext(
            localTurnId = "local-turn-${System.currentTimeMillis()}",
            preferredExecutionMode = planCompletion.preferredExecutionMode,
            threadId = planCompletion.threadId,
        )
        eventHub.publish(AppEvent.PlanCompletionPromptUpdated(prompt = null))
        startProgrammaticTurn(
            prompt = revision,
            approvalMode = planCompletion.preferredExecutionMode.toApprovalMode(),
            collaborationMode = AgentCollaborationMode.PLAN,
            localTurnId = activePlanRunContext?.localTurnId.orEmpty(),
        )
    }

    private fun requestPlanRevision() {
        eventHub.publish(AppEvent.PlanCompletionPromptUpdated(prompt = null))
    }

    private fun dismissPlanCompletionPrompt() {
        eventHub.publish(AppEvent.PlanCompletionPromptUpdated(prompt = null))
    }

    private fun resetPlanFlowState() {
        activePlanRunContext = null
        eventHub.publish(AppEvent.ClearToolUserInputs)
        eventHub.publish(AppEvent.RunningPlanUpdated(plan = null))
        eventHub.publish(AppEvent.PlanCompletionPromptUpdated(prompt = null))
    }

    private fun String.toComposerRunningPlanStepStatus(): ComposerRunningPlanStepStatus {
        return when (trim().lowercase()) {
            "completed", "complete", "success", "succeeded", "done" -> ComposerRunningPlanStepStatus.COMPLETED
            "inprogress", "in_progress", "running", "active" -> ComposerRunningPlanStepStatus.IN_PROGRESS
            else -> ComposerRunningPlanStepStatus.PENDING
        }
    }

    private fun startProgrammaticTurn(
        prompt: String,
        approvalMode: AgentApprovalMode,
        collaborationMode: AgentCollaborationMode = AgentCollaborationMode.DEFAULT,
        localTurnId: String = "local-turn-${System.currentTimeMillis()}",
    ) {
        chatService.recordUserMessage(
            prompt = prompt,
            turnId = localTurnId,
            attachments = emptyList(),
        )?.let { message ->
            eventHub.publish(
                AppEvent.TimelineMutationApplied(
                    mutation = TimelineNodeMapper.localUserMessageMutation(
                        sourceId = message.sourceId,
                        text = message.prompt,
                        timestamp = message.timestamp,
                        turnId = message.turnId,
                        attachments = message.attachments,
                    ),
                ),
            )
        }
        publishSessionSnapshot()
        chatService.runAgent(
            engineId = chatService.defaultEngineId(),
            model = composerStore.state.value.selectedModel,
            reasoningEffort = composerStore.state.value.selectedReasoning.effort,
            prompt = prompt,
            systemInstructions = emptyList(),
            localTurnId = localTurnId,
            contextFiles = emptyList(),
            imageAttachments = emptyList(),
            fileAttachments = emptyList(),
            approvalMode = approvalMode,
            collaborationMode = collaborationMode,
            onTurnPersisted = { publishSessionSnapshot() },
            onUnifiedEvent = { event -> publishUnifiedEvent(event) },
        )
    }

    private fun loadHistoryConversations(reset: Boolean) {
        val drawerState = rightDrawerStore.state.value
        if (drawerState.historyLoading) return
        if (!reset && drawerState.historyNextCursor == null) return
        eventHub.publish(
            AppEvent.HistoryConversationsUpdated(
                conversations = if (reset) emptyList() else drawerState.historyConversations,
                nextCursor = drawerState.historyNextCursor,
                isLoading = true,
                append = !reset,
            ),
        )
        launchLogged("loadHistoryConversations(reset=$reset)") {
            val page = chatService.loadRemoteConversationSummaries(
                limit = historyPageSize,
                cursor = if (reset) null else drawerState.historyNextCursor,
                searchTerm = drawerState.historyQuery.trim().takeIf { it.isNotBlank() },
            )
            eventHub.publish(
                AppEvent.HistoryConversationsUpdated(
                    conversations = page.conversations,
                    nextCursor = page.nextCursor,
                    isLoading = false,
                    append = !reset,
                ),
            )
        }
    }

    private fun exportRemoteConversation(remoteConversationId: String, title: String) {
        val normalizedRemoteId = remoteConversationId.trim()
        if (normalizedRemoteId.isBlank()) return
        val suggestedFileName = suggestConversationExportFileName(title = title, remoteConversationId = normalizedRemoteId)
        val selectedPath = pickExportPath(suggestedFileName)?.trim().orEmpty()
        if (selectedPath.isBlank()) return
        launchLogged("exportRemoteConversation($normalizedRemoteId)") {
            runCatching {
                val history = chatService.loadFullRemoteConversationHistory(
                    remoteConversationId = normalizedRemoteId,
                    pageSize = historyPageSize.coerceAtLeast(1),
                )
                val summary = rightDrawerStore.state.value.historyConversations.firstOrNull {
                    it.remoteConversationId == normalizedRemoteId
                } ?: com.auracode.assistant.conversation.ConversationSummary(
                    remoteConversationId = normalizedRemoteId,
                    title = title,
                    createdAt = 0L,
                    updatedAt = 0L,
                    status = "idle",
                )
                val content = formatConversationExportMarkdown(summary = summary, events = history.events)
                writeExportFile(selectedPath, content)
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        UiText.raw("Exported conversation to $selectedPath"),
                    ),
                )
            }.onFailure { error ->
                eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        UiText.raw(error.message ?: "Failed to export conversation."),
                    ),
                )
            }
        }
    }

    fun onSessionActivated() {
        publishSessionSnapshot()
        restoreCurrentSessionHistory()
    }

    fun onSessionSwitched(sessionId: String) {
        eventHub.publishUiIntent(UiIntent.SwitchSession(sessionId))
    }

    override fun dispose() {
        scope.cancel()
    }

    private fun toContextEntry(path: String): ContextEntry {
        val p = runCatching { Path.of(path) }.getOrNull()
        val name = p?.name ?: path.substringAfterLast('/').substringAfterLast('\\')
        val tail = p?.parent?.fileName?.toString().orEmpty()
        return ContextEntry(path = path, displayName = name.ifBlank { path }, tailPath = tail)
    }

    private fun pasteImageFromClipboard() {
        val image = readImageFromClipboard() ?: return
        val tempPath = writeClipboardImage(image) ?: return
        eventHub.publishUiIntent(UiIntent.AddAttachments(listOf(tempPath)))
    }

    private fun openEditedFileDiff(path: String) {
        val aggregate = composerStore.state.value.editedFiles.firstOrNull { it.path == path } ?: return
        val resolved = TimelineFileChangePreview.resolve(aggregate.parsedDiff)
        val change = TimelineFileChange(
            sourceScopedId = "turn:${aggregate.turnId}",
            path = aggregate.path,
            displayName = aggregate.displayName,
            kind = aggregate.parsedDiff.kind,
            timestamp = aggregate.lastUpdatedAt,
            addedLines = aggregate.latestAddedLines,
            deletedLines = aggregate.latestDeletedLines,
            unifiedDiff = aggregate.parsedDiff.unifiedDiff,
            oldContent = resolved.oldContent,
            newContent = resolved.newContent,
        )
        openTimelineFileChange(change)
    }

    private fun revertEditedFile(path: String) {
        val aggregate = composerStore.state.value.editedFiles.firstOrNull { it.path == path } ?: return
        val result = revertAggregate(aggregate)
        if (result.isSuccess) {
            eventHub.publishUiIntent(UiIntent.AcceptEditedFile(path))
            eventHub.publish(AppEvent.StatusTextUpdated(com.auracode.assistant.toolwindow.shared.UiText.raw(result.getOrDefault(""))))
        } else {
            eventHub.publish(AppEvent.StatusTextUpdated(com.auracode.assistant.toolwindow.shared.UiText.raw(result.exceptionOrNull()?.message ?: "Revert failed.")))
        }
    }

    private fun revertAllEditedFiles() {
        val aggregates = composerStore.state.value.editedFiles
        if (aggregates.isEmpty()) return
        var success = 0
        var failed = 0
        aggregates.forEach { aggregate ->
            val result = revertAggregate(aggregate)
            if (result.isSuccess) {
                success += 1
                eventHub.publishUiIntent(UiIntent.AcceptEditedFile(aggregate.path))
            } else {
                failed += 1
            }
        }
        eventHub.publish(
            AppEvent.StatusTextUpdated(
                com.auracode.assistant.toolwindow.shared.UiText.raw(
                    if (failed == 0) {
                        "Reverted $success files."
                    } else {
                        "Reverted $success files, failed $failed."
                    },
                ),
            ),
        )
    }

    private fun revertAggregate(aggregate: com.auracode.assistant.toolwindow.composer.EditedFileAggregate): Result<String> {
        return TimelineFileChangePreview.revertParsedDiff(aggregate.parsedDiff)
    }

    private fun readImageFromClipboard(): BufferedImage? {
        return runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
            val image = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
            if (image is BufferedImage) return image
            val buffered = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
            val g = buffered.createGraphics()
            g.drawImage(image, 0, 0, null)
            g.dispose()
            buffered
        }.getOrNull()
    }

    private fun writeClipboardImage(image: BufferedImage): String? {
        val path = runCatching {
            Files.createTempFile("codex-clip-", ".png")
        }.getOrNull() ?: return null
        return runCatching {
            Files.newOutputStream(path, StandardOpenOption.WRITE).use { out ->
                ImageIO.write(image, "png", out)
            }
            path.toAbsolutePath().toString()
        }.getOrNull()
    }

    private fun restoreCurrentSessionHistory() {
        resetPlanFlowState()
        eventHub.publish(AppEvent.ConversationReset)
        launchLogged("restoreCurrentSessionHistory") {
            val page = chatService.loadCurrentConversationHistory(limit = historyPageSize)
            eventHub.publish(
                AppEvent.TimelineHistoryLoaded(
                    nodes = restoreNodes(page.events),
                    oldestCursor = page.olderCursor,
                    hasOlder = page.hasOlder,
                    prepend = false,
                ),
            )
        }
    }

    private fun loadOlderMessages() {
        val state = timelineStore.state.value
        if (!state.hasOlder || state.isLoadingOlder) return
        val beforeCursor = state.oldestCursor ?: return
        eventHub.publish(AppEvent.TimelineOlderLoadingChanged(loading = true))
        launchLogged("loadOlderMessages(cursor=$beforeCursor)") {
            val page = chatService.loadOlderConversationHistory(
                cursor = beforeCursor,
                limit = historyPageSize,
            )
            eventHub.publish(
                AppEvent.TimelineHistoryLoaded(
                    nodes = restoreNodes(page.events),
                    oldestCursor = page.olderCursor,
                    hasOlder = page.hasOlder,
                    prepend = true,
                ),
            )
        }
    }

    private fun publishUnifiedEvent(event: UnifiedEvent) {
        eventHub.publishUnifiedEvent(event)
        TimelineNodeMapper.fromUnifiedEvent(event)?.let { mutation ->
            eventHub.publish(AppEvent.TimelineMutationApplied(mutation = mutation))
        }
    }

    private fun submitApprovalDecision(explicitAction: ApprovalAction?) {
        val current = approvalStore.state.value.current ?: return
        val action = explicitAction ?: approvalStore.state.value.selectedAction
        if (!chatService.submitApprovalDecision(current.requestId, action)) return
        publishSessionSnapshot()
        eventHub.publish(AppEvent.ApprovalResolved(requestId = current.requestId))
        eventHub.publish(
            AppEvent.TimelineMutationApplied(
                mutation = TimelineMutation.UpsertApproval(
                    sourceId = current.itemId,
                    title = current.title,
                    body = buildResolvedApprovalBody(current.body, action),
                    status = when (action) {
                        ApprovalAction.REJECT -> com.auracode.assistant.protocol.ItemStatus.FAILED
                        ApprovalAction.ALLOW,
                        ApprovalAction.ALLOW_FOR_SESSION,
                        -> com.auracode.assistant.protocol.ItemStatus.SUCCESS
                    },
                    turnId = current.turnId,
                ),
            ),
        )
    }

    private fun submitToolUserInputPrompt(cancelled: Boolean) {
        val prompt = toolUserInputPromptStore.state.value.current ?: return
        val answers = if (cancelled) {
            emptyMap()
        } else {
            val submission = toolUserInputPromptStore.state.value.toSubmissionAnswers()
            if (submission.isEmpty()) return
            submission
        }
        if (!chatService.submitToolUserInput(prompt.requestId, answers)) return
        publishSessionSnapshot()
        eventHub.publish(AppEvent.ToolUserInputResolved(requestId = prompt.requestId))
        eventHub.publish(
            AppEvent.TimelineMutationApplied(
                mutation = TimelineMutation.UpsertUserInput(
                    sourceId = toolUserInputSourceId(prompt),
                    title = AuraCodeBundle.message("timeline.userInput.title"),
                    body = buildResolvedToolUserInputBody(prompt, answers, cancelled),
                    status = when {
                        cancelled || answers.isEmpty() -> com.auracode.assistant.protocol.ItemStatus.FAILED
                        else -> com.auracode.assistant.protocol.ItemStatus.SUCCESS
                    },
                    turnId = prompt.turnId,
                ),
            ),
        )
        if (cancelled) {
            cancelPromptRun()
        }
    }

    private fun buildResolvedApprovalBody(body: String, action: ApprovalAction): String {
        val decisionLabel = when (action) {
            ApprovalAction.ALLOW -> "Allowed"
            ApprovalAction.REJECT -> "Rejected"
            ApprovalAction.ALLOW_FOR_SESSION -> "Remembered for session"
        }
        return listOf(body.trim().takeIf { it.isNotBlank() }, decisionLabel).joinToString("\n\n")
    }

    private fun ComposerMode.toApprovalMode(): AgentApprovalMode {
        return when (this) {
            ComposerMode.AUTO -> AgentApprovalMode.AUTO
            ComposerMode.APPROVAL -> AgentApprovalMode.REQUIRE_CONFIRMATION
        }
    }

    private fun buildResolvedToolUserInputBody(
        prompt: ToolUserInputPromptUiModel,
        answers: Map<String, com.auracode.assistant.protocol.UnifiedToolUserInputAnswerDraft>,
        cancelled: Boolean,
    ): String {
        if (cancelled || answers.isEmpty()) {
            return AuraCodeBundle.message("timeline.userInput.cancelled")
        }
        return prompt.questions.joinToString("\n\n") { question ->
            val title = question.header.ifBlank { question.question }
            val value = when {
                question.isSecret && answers.containsKey(question.id) -> AuraCodeBundle.message("timeline.userInput.secretProvided")
                else -> answers[question.id]?.answers?.joinToString(", ").orEmpty()
                    .ifBlank { AuraCodeBundle.message("timeline.userInput.noAnswer") }
            }
            "$title\n$value"
        }
    }

    private fun toolUserInputSourceId(prompt: ToolUserInputPromptUiModel): String {
        return "tool-user-input:${prompt.requestId}:${prompt.itemId}"
    }

    private fun restoreNodes(events: List<UnifiedEvent>): List<com.auracode.assistant.toolwindow.timeline.TimelineNode> {
        val reducer = TimelineNodeReducer()
        events.forEach { event ->
            TimelineNodeMapper.fromUnifiedEvent(event)?.let(reducer::accept)
        }
        return reducer.state.nodes.filterNot { it is com.auracode.assistant.toolwindow.timeline.TimelineNode.LoadMoreNode }
    }

    private fun launchLogged(label: String, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { error ->
                if (error is CancellationException) {
                    if (label.startsWith("loadMcp") || label.startsWith("refreshMcp") || label.startsWith("saveMcp") || label.startsWith("runMcp") || label.startsWith("authenticateMcp") || label.startsWith("deleteMcp")) {
                        logMcpDiagnostic("MCP coroutine cancelled: label=$label | ${mcpContextSnapshot()}")
                    }
                    return@onFailure
                }
                if (label.startsWith("loadMcp") || label.startsWith("refreshMcp") || label.startsWith("saveMcp") || label.startsWith("runMcp") || label.startsWith("authenticateMcp") || label.startsWith("deleteMcp")) {
                    logMcpDiagnostic(
                        message = "MCP coroutine failed: label=$label | ${mcpContextSnapshot()}",
                        error = error,
                    )
                } else {
                    LOG.error("ToolWindowCoordinator coroutine failed: $label", error)
                }
            }
        }
    }

    private fun isMcpIntent(intent: UiIntent): Boolean {
        return when (intent) {
            UiIntent.LoadMcpServers,
            UiIntent.RefreshMcpStatuses,
            UiIntent.CreateNewMcpDraft,
            UiIntent.ShowMcpSettingsList,
            UiIntent.SaveMcpDraft -> true
            is UiIntent.SelectMcpServerForEdit,
            is UiIntent.EditMcpDraftName,
            is UiIntent.EditMcpDraftJson,
            is UiIntent.ToggleMcpServerEnabled,
            is UiIntent.DeleteMcpServer,
            is UiIntent.TestMcpServer,
            is UiIntent.LoginMcpServer,
            is UiIntent.LogoutMcpServer -> true
            else -> false
        }
    }

    private fun formatMcpIntent(intent: UiIntent): String {
        return when (intent) {
            UiIntent.LoadMcpServers -> "LoadMcpServers"
            UiIntent.RefreshMcpStatuses -> "RefreshMcpStatuses"
            UiIntent.CreateNewMcpDraft -> "CreateNewMcpDraft"
            UiIntent.ShowMcpSettingsList -> "ShowMcpSettingsList"
            UiIntent.SaveMcpDraft -> "SaveMcpDraft"
            is UiIntent.TestMcpServer -> "TestMcpServer(name=${intent.name ?: "<draft>"})"
            is UiIntent.SelectMcpServerForEdit -> "SelectMcpServerForEdit(name=${intent.name})"
            is UiIntent.EditMcpDraftName -> "EditMcpDraftName"
            is UiIntent.EditMcpDraftJson -> "EditMcpDraftJson"
            is UiIntent.ToggleMcpServerEnabled -> "ToggleMcpServerEnabled(name=${intent.name}, enabled=${intent.enabled})"
            is UiIntent.DeleteMcpServer -> "DeleteMcpServer(name=${intent.name})"
            is UiIntent.LoginMcpServer -> "LoginMcpServer(name=${intent.name})"
            is UiIntent.LogoutMcpServer -> "LogoutMcpServer(name=${intent.name})"
            else -> intent::class.simpleName ?: "UnknownMcpIntent"
        }
    }

    private fun mcpContextSnapshot(requestedName: String? = null): String {
        val state = rightDrawerStore.state.value
        val draftName = state.mcpDraft.name.ifBlank { "<blank>" }
        val editingName = state.editingMcpName ?: "<none>"
        val preview = state.mcpDraft.configJson
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(180)
            .ifBlank { "<empty>" }
        return buildList {
            add("settingsSection=${state.settingsSection}")
            add("mcpSettingsPage=${state.mcpSettingsPage}")
            add("editingMcpName=$editingName")
            add("draftName=$draftName")
            requestedName?.let { add("requestedName=$it") }
            add("busy=${state.mcpBusyState}")
            add("draftJsonPreview=$preview")
        }.joinToString(" | ")
    }

    private fun logMcpDiagnostic(message: String, error: Throwable? = null) {
        diagnosticLog(message, error)
    }

    private fun buildContextFiles(
        contextEntries: List<ContextEntry>,
        attachments: List<PersistedMessageAttachment>,
    ): List<ContextFile> {
        val merged = LinkedHashMap<String, ContextFile>()
        contextEntries.forEach { entry ->
            val contextFile = when {
                entry.isSelectionContext && !entry.selectedText.isNullOrBlank() -> {
                    ContextFile(
                        path = entry.encodedContextPath(),
                        content = entry.selectedText.orEmpty(),
                    )
                }
                else -> ContextFile(path = entry.path)
            }
            merged.putIfAbsent(entry.path, contextFile)
        }
        attachments
            .filter { it.kind == PersistedAttachmentKind.TEXT }
            .forEach { attachment ->
                val displayPath = attachment.originalPath.ifBlank { attachment.assetPath }
                merged.putIfAbsent(displayPath, ContextFile(path = displayPath))
            }
        return merged.values.toList()
    }

    private fun ContextEntry.encodedContextPath(): String {
        val start = startLine ?: return path
        val end = endLine ?: return path
        return if (start == end) "$path:$start" else "$path:$start-$end"
    }

    private fun stageAttachments(
        sessionId: String,
        attachments: List<AttachmentEntry>,
    ): List<PersistedMessageAttachment> {
        if (sessionId.isBlank() || attachments.isEmpty()) return emptyList()
        return attachments.mapNotNull { attachment ->
            val source = runCatching { Path.of(attachment.path) }.getOrNull() ?: return@mapNotNull null
            if (!source.isRegularFile()) return@mapNotNull null
            val bytes = runCatching { Files.readAllBytes(source) }.getOrNull() ?: return@mapNotNull null
            val sha = sha256(bytes)
            val ext = attachment.displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
            val fileName = buildString {
                append(sha)
                if (!ext.isNullOrBlank()) {
                    append('.')
                    append(ext)
                }
            }
            val targetDir = Path.of(PathManager.getSystemPath(), "aura-code", "chat-assets", sessionId)
            runCatching { Files.createDirectories(targetDir) }.getOrNull() ?: return@mapNotNull null
            val target = targetDir.resolve(fileName)
            runCatching {
                if (!Files.exists(target)) {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }.getOrNull() ?: return@mapNotNull null

            PersistedMessageAttachment(
                kind = attachment.kind.toPersistedAttachmentKind(),
                displayName = attachment.displayName,
                assetPath = target.toAbsolutePath().toString(),
                originalPath = attachment.path,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.sizeBytes,
                sha256 = sha,
            )
        }
    }

    private fun AttachmentKind.toPersistedAttachmentKind(): PersistedAttachmentKind {
        return when (this) {
            AttachmentKind.IMAGE -> PersistedAttachmentKind.IMAGE
            AttachmentKind.TEXT -> PersistedAttachmentKind.TEXT
            AttachmentKind.BINARY -> PersistedAttachmentKind.FILE
        }
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun McpServerDraft.normalized(): McpServerDraft {
        return copy(
            configJson = configJson.trim(),
            name = name.trim(),
            originalName = originalName?.trim()?.ifBlank { null },
        )
    }
}

private fun readFileContentDefault(path: String): String? {
    val maxFileBytes = 128 * 1024
    val file = runCatching { Path.of(path) }.getOrNull() ?: return null
    if (!file.isRegularFile()) return null
    val bytes = runCatching { Files.readAllBytes(file) }.getOrNull() ?: return null
    if (bytes.isEmpty() || bytes.any { it == 0.toByte() }) return null
    val clipped = if (bytes.size > maxFileBytes) bytes.copyOf(maxFileBytes) else bytes
    return runCatching { clipped.toString(Charsets.UTF_8) }.getOrNull()
}
