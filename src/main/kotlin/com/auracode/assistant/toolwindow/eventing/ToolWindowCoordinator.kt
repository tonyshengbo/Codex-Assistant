package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.coroutine.AppCoroutineManager
import com.auracode.assistant.coroutine.ManagedCoroutineScope
import com.auracode.assistant.context.MentionFileWhitelist
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.notification.ChatCompletionNotificationService
import com.auracode.assistant.provider.codex.CodexCliVersionService
import com.auracode.assistant.provider.codex.CodexEnvironmentDetector
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.settings.skills.LocalSkillInstallPolicy
import com.auracode.assistant.settings.skills.SkillsRuntimeService
import com.auracode.assistant.settings.mcp.McpManagementAdapterRegistry
import com.auracode.assistant.toolwindow.approval.ApprovalAreaStore
import com.auracode.assistant.toolwindow.composer.ComposerAreaStore
import com.auracode.assistant.toolwindow.drawer.RightDrawerAreaStore
import com.auracode.assistant.toolwindow.drawer.RightDrawerKind
import com.auracode.assistant.toolwindow.shared.UiText
import com.auracode.assistant.toolwindow.session.SessionAttentionStore
import com.auracode.assistant.toolwindow.header.HeaderAreaStore
import com.auracode.assistant.toolwindow.status.StatusAreaStore
import com.auracode.assistant.toolwindow.timeline.TimelineAreaStore
import com.auracode.assistant.toolwindow.timeline.TimelineFileChange
import com.auracode.assistant.toolwindow.timeline.TimelineMutation
import com.auracode.assistant.toolwindow.toolinput.ToolUserInputPromptStore
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import java.awt.Desktop
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

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
    private val completionNotificationService: ChatCompletionNotificationService? = null,
    private val sessionAttentionStore: SessionAttentionStore = SessionAttentionStore(),
    private val mcpAdapterRegistry: McpManagementAdapterRegistry = McpManagementAdapterRegistry(settingsService),
    private val skillsRuntimeService: SkillsRuntimeService = SkillsRuntimeService(
        adapterRegistry = com.auracode.assistant.settings.skills.SkillsManagementAdapterRegistry(settingsService),
    ),
    private val codexEnvironmentDetector: CodexEnvironmentDetector = CodexEnvironmentDetector(),
    private val codexCliVersionService: CodexCliVersionService = CodexCliVersionService(settingsService, codexEnvironmentDetector),
    private val pickAttachments: () -> List<String> = { emptyList() },
    private val pickExportPath: (String) -> String? = { null },
    private val searchProjectFiles: (String, Int) -> List<String> = { _, _ -> emptyList() },
    private val isMentionCandidateFile: (String) -> Boolean = { path -> MentionFileWhitelist.allowPath(path) },
    private val readFileContent: (String) -> String? = { path -> readFileContentDefault(path) },
    private val openTimelineFileChange: (TimelineFileChange) -> Unit = {},
    private val openTimelineFilePath: (String) -> Unit = {},
    private val revealPathInFileManager: (String) -> Boolean = { false },
    private val openSessionInNewTab: (String) -> Boolean = { true },
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
            "The user approved the latest plan. Execute it now."
    }

    private val scope: ManagedCoroutineScope = AppCoroutineManager.createScope(
        scopeName = "ToolWindowCoordinator",
        dispatcher = Dispatchers.Default,
        failureReporter = { _, label, error ->
            LOG.error("ToolWindowCoordinator coroutine failed${label?.let { ": $it" }.orEmpty()}", error)
        },
    )
    private val recentFocusedFiles = ArrayDeque<String>()
    private val sessionUiStateCache = SessionUiStateCache()
    private val pendingSubmissionsBySessionId = linkedMapOf<String, ArrayDeque<com.auracode.assistant.toolwindow.composer.PendingComposerSubmission>>()
    private val activePlanRunContexts = linkedMapOf<String, ActivePlanRunContext>()
    private val eventDispatcher = SessionScopedEventDispatcher(
        activeSessionId = { activeSessionId() },
        sessionUiStateCache = sessionUiStateCache,
        statusStore = statusStore,
        timelineStore = timelineStore,
        composerStore = composerStore,
        approvalStore = approvalStore,
        toolUserInputPromptStore = toolUserInputPromptStore,
    )
    private val coroutineLauncher = CoordinatorCoroutineLauncher(
        scope = scope,
        logger = LOG,
        onMcpCancellation = { label ->
            diagnosticLog("MCP coroutine cancelled: label=$label | ${settingsHandler.mcpContextSnapshotForLog()}", null)
        },
        onMcpFailure = { label, error ->
            diagnosticLog("MCP coroutine failed: label=$label | ${settingsHandler.mcpContextSnapshotForLog()}", error)
        },
    )
    private val context: ToolWindowCoordinatorContext = ToolWindowCoordinatorContext(
        chatService = chatService,
        settingsService = settingsService,
        eventHub = eventHub,
        headerStore = headerStore,
        statusStore = statusStore,
        timelineStore = timelineStore,
        composerStore = composerStore,
        rightDrawerStore = rightDrawerStore,
        approvalStore = approvalStore,
        toolUserInputPromptStore = toolUserInputPromptStore,
        completionNotificationService = completionNotificationService,
        sessionAttentionStore = sessionAttentionStore,
        mcpAdapterRegistry = mcpAdapterRegistry,
        skillsRuntimeService = skillsRuntimeService,
        codexEnvironmentDetector = codexEnvironmentDetector,
        codexCliVersionService = codexCliVersionService,
        pickAttachments = pickAttachments,
        pickExportPath = pickExportPath,
        searchProjectFiles = searchProjectFiles,
        isMentionCandidateFile = isMentionCandidateFile,
        readFileContent = readFileContent,
        openTimelineFileChange = openTimelineFileChange,
        openTimelineFilePath = openTimelineFilePath,
        revealPathInFileManager = revealPathInFileManager,
        localSkillInstallPolicy = localSkillInstallPolicy,
        writeExportFile = writeExportFile,
        openExternalUrl = openExternalUrl,
        diagnosticLog = diagnosticLog,
        onSessionSnapshotPublished = onSessionSnapshotPublished,
        historyPageSize = historyPageSize,
        scope = scope.coroutineScope,
        recentFocusedFiles = recentFocusedFiles,
        pendingSubmissionsBySessionId = pendingSubmissionsBySessionId,
        activePlanRunContexts = activePlanRunContexts,
        eventDispatcher = eventDispatcher,
        coroutineLauncher = coroutineLauncher,
        publishSessionSnapshot = { publishSessionSnapshot() },
        publishSettingsSnapshot = { publishSettingsSnapshot() },
        publishConversationCapabilities = { publishConversationCapabilities() },
        publishUnifiedEvent = { sessionId: String, event: UnifiedEvent -> publishUnifiedEvent(sessionId, event) },
    )
    private val workspaceHandler = WorkspaceInteractionHandler(context)
    private val settingsHandler = SettingsAndEnvironmentHandler(context)
    private val planHandler = PlanFlowHandler(context, EXECUTE_APPROVED_PLAN_PROMPT)
    private val historyHandler = ConversationHistoryHandler(context) { planHandler.resetPlanFlowState() }
    private val conversationHandler = ConversationFlowHandler(context, workspaceHandler)

    init {
        coroutineLauncher.launch("eventHub.collect") {
            eventHub.stream.collect { event ->
                if (event is AppEvent.UiIntentPublished && settingsHandler.isMcpIntent(event.intent)) {
                    diagnosticLog(
                        "MCP intent received: intent=${settingsHandler.formatMcpIntent(event.intent)} | ${settingsHandler.mcpContextSnapshotForLog()}",
                        null,
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
                    when (event) {
                        is AppEvent.UiIntentPublished -> handleUiIntent(event.intent)
                        is AppEvent.UnifiedEventPublished -> handleUnifiedEvent(activeSessionId(), event.event)
                        is AppEvent.TimelineMutationApplied -> {
                            if (event.mutation is TimelineMutation.TurnCompleted) {
                                conversationHandler.dispatchNextPendingSubmissionIfIdle(chatService.getCurrentSessionId())
                            }
                        }
                        else -> Unit
                    }
                }.onFailure { error ->
                    if (event is AppEvent.UiIntentPublished && settingsHandler.isMcpIntent(event.intent)) {
                        diagnosticLog(
                            "MCP intent handling failed: intent=${settingsHandler.formatMcpIntent(event.intent)} | ${settingsHandler.mcpContextSnapshotForLog()}",
                            error,
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
        historyHandler.restoreCurrentSessionHistory()
        settingsHandler.warmSkillsRuntimeCache()
        settingsHandler.warmCodexCliVersionState()
    }

    private fun handleUiIntent(intent: UiIntent) {
        when (intent) {
            UiIntent.ToggleSettings -> {
                if (rightDrawerStore.state.value.kind == RightDrawerKind.SETTINGS) {
                    settingsHandler.onSettingsDrawerOpened()
                }
            }
            UiIntent.ToggleHistory -> {
                if (rightDrawerStore.state.value.kind == RightDrawerKind.HISTORY) {
                    historyHandler.loadHistoryConversations(reset = true)
                }
            }
            UiIntent.SendPrompt -> conversationHandler.submitPromptIfAllowed()
            is UiIntent.SubmitBuildErrorRequest -> conversationHandler.submitExternalRequest(intent.request.toIdeExternalRequest())
            is UiIntent.SubmitExternalRequest -> conversationHandler.submitExternalRequest(intent.request)
            UiIntent.CancelRun -> conversationHandler.cancelPromptRun(
                onResetPlanFlowState = { planHandler.resetPlanFlowState() },
                onTurnCompleted = { sessionId ->
                    conversationHandler.dispatchNextPendingSubmissionIfIdle(sessionId, allowTurnCompletedBypass = true)
                },
            )
            is UiIntent.RemovePendingSubmission -> conversationHandler.removePendingSubmission(intent.id)
            is UiIntent.DeleteSession -> conversationHandler.deleteSession(
                onRestoreHistory = { historyHandler.restoreCurrentSessionHistory() },
                sessionId = intent.sessionId,
            )
            is UiIntent.SwitchSession -> conversationHandler.switchSession(intent.sessionId) {
                historyHandler.restoreCurrentSessionHistory()
            }
            UiIntent.LoadHistoryConversations -> historyHandler.loadHistoryConversations(reset = true)
            UiIntent.LoadMoreHistoryConversations -> historyHandler.loadHistoryConversations(reset = false)
            UiIntent.LoadMcpServers -> settingsHandler.loadMcpServers()
            UiIntent.RefreshMcpStatuses -> settingsHandler.refreshMcpStatuses()
            is UiIntent.EditHistorySearchQuery -> historyHandler.loadHistoryConversations(reset = true)
            is UiIntent.OpenRemoteConversation -> historyHandler.openRemoteConversation(intent.remoteConversationId, intent.title)
            is UiIntent.ExportRemoteConversation -> historyHandler.exportRemoteConversation(intent.remoteConversationId, intent.title)
            is UiIntent.OpenTimelineFileChange -> openTimelineFileChange(intent.change)
            is UiIntent.OpenTimelineFilePath -> openTimelineFilePath(intent.path)
            UiIntent.LoadOlderMessages -> historyHandler.loadOlderMessages()
            is UiIntent.SelectSettingsSection -> settingsHandler.onSettingsSectionSelected(intent.section)
            UiIntent.OpenAttachmentPicker -> {
                val selected = pickAttachments()
                if (selected.isNotEmpty()) {
                    eventHub.publishUiIntent(UiIntent.AddAttachments(selected))
                }
            }
            UiIntent.PasteImageFromClipboard -> workspaceHandler.pasteImageFromClipboard()
            is UiIntent.OpenEditedFileDiff -> workspaceHandler.openEditedFileDiff(intent.path)
            is UiIntent.RevertEditedFile -> workspaceHandler.revertEditedFile(intent.path)
            UiIntent.RevertAllEditedFiles -> workspaceHandler.revertAllEditedFiles()
            is UiIntent.RequestMentionSuggestions -> workspaceHandler.requestMentionSuggestions(intent.query, intent.documentVersion, MENTION_LIMIT)
            is UiIntent.RequestAgentSuggestions -> conversationHandler.requestAgentSuggestions(intent.query, intent.documentVersion, MENTION_LIMIT)
            is UiIntent.UpdateFocusedContextFile -> workspaceHandler.recordFocusedFile(intent.snapshot?.path)
            is UiIntent.EditSettingsLanguageMode -> settingsHandler.applyLanguagePreview(intent.mode)
            is UiIntent.EditSettingsThemeMode -> settingsHandler.applyThemePreview(intent.mode)
            is UiIntent.EditSettingsUiScaleMode -> settingsHandler.applyUiScalePreview(intent.mode)
            is UiIntent.EditSettingsAutoContextEnabled -> settingsHandler.applyAutoContextPreference(intent.enabled)
            is UiIntent.EditSettingsBackgroundCompletionNotificationsEnabled -> {
                settingsHandler.applyBackgroundCompletionNotificationPreference(intent.enabled)
            }
            is UiIntent.EditSettingsCodexCliAutoUpdateCheckEnabled -> {
                settingsHandler.applyCodexCliAutoUpdatePreference(intent.enabled)
            }
            is UiIntent.SubmitApprovalAction -> {
                planHandler.submitApprovalDecision(intent.action)
            }
            UiIntent.SubmitToolUserInputPrompt -> {
                planHandler.submitToolUserInputPrompt(cancelled = false) {
                    conversationHandler.cancelPromptRun(
                        onResetPlanFlowState = { planHandler.resetPlanFlowState() },
                        onTurnCompleted = { sessionId ->
                            conversationHandler.dispatchNextPendingSubmissionIfIdle(sessionId, allowTurnCompletedBypass = true)
                        },
                    )
                }
            }
            UiIntent.CancelToolUserInputPrompt -> {
                planHandler.submitToolUserInputPrompt(cancelled = true) {
                    conversationHandler.cancelPromptRun(
                        onResetPlanFlowState = { planHandler.resetPlanFlowState() },
                        onTurnCompleted = { sessionId ->
                            conversationHandler.dispatchNextPendingSubmissionIfIdle(sessionId, allowTurnCompletedBypass = true)
                        },
                    )
                }
            }
            UiIntent.ExecuteApprovedPlan -> planHandler.executeApprovedPlan()
            UiIntent.SubmitPlanRevision -> planHandler.submitPlanRevision()
            UiIntent.RequestPlanRevision -> planHandler.requestPlanRevision()
            UiIntent.DismissPlanCompletionPrompt -> planHandler.dismissPlanCompletionPrompt()
            is UiIntent.SelectAgent -> settingsHandler.persistSelectedAgent(intent.agent.id)
            is UiIntent.RemoveSelectedAgent -> settingsHandler.persistDeselectedAgent(intent.id)
            is UiIntent.RequestEngineSwitch -> Unit
            is UiIntent.SelectEngine -> handleEngineSelection(intent.engineId)
            UiIntent.DismissEngineSwitchDialog -> Unit
            is UiIntent.SelectModel -> {
                settingsService.setSelectedComposerModel(chatService.defaultEngineId(), intent.model)
                publishSettingsSnapshot()
            }
            is UiIntent.SelectReasoning -> {
                settingsService.setSelectedComposerReasoning(intent.reasoning.effort)
                publishSettingsSnapshot()
            }
            UiIntent.SaveCustomModel -> settingsHandler.saveCustomModel()
            is UiIntent.DeleteCustomModel -> settingsHandler.deleteCustomModel(intent.model)
            UiIntent.SaveAgentDraft -> settingsHandler.saveAgentDraft()
            is UiIntent.DeleteSavedAgent -> settingsHandler.deleteSavedAgent(intent.id)
            UiIntent.LoadSkills -> settingsHandler.loadSkills()
            UiIntent.RefreshSkills -> settingsHandler.loadSkills(forceReload = true)
            is UiIntent.ToggleSkillEnabled -> settingsHandler.toggleSkillEnabled(intent.name, intent.path, intent.enabled)
            is UiIntent.OpenSkillPath -> settingsHandler.openSkillPath(intent.path)
            is UiIntent.RevealSkillPath -> settingsHandler.revealSkillPath(intent.path)
            is UiIntent.UninstallSkill -> settingsHandler.uninstallSkill(intent.name, intent.path)
            UiIntent.CreateNewMcpDraft -> settingsHandler.loadMcpEditorDraft()
            is UiIntent.SelectMcpServerForEdit -> settingsHandler.loadMcpEditorDraft()
            UiIntent.SaveMcpDraft -> settingsHandler.saveMcpDraft()
            is UiIntent.ToggleMcpServerEnabled -> settingsHandler.toggleMcpServerEnabled(intent.name, intent.enabled)
            is UiIntent.DeleteMcpServer -> settingsHandler.deleteMcpServer(intent.name)
            is UiIntent.TestMcpServer -> settingsHandler.testMcpServer(intent.name)
            is UiIntent.LoginMcpServer -> settingsHandler.authenticateMcpServer(intent.name, login = true)
            is UiIntent.LogoutMcpServer -> settingsHandler.authenticateMcpServer(intent.name, login = false)
            UiIntent.DetectCodexEnvironment -> settingsHandler.detectCodexEnvironment()
            UiIntent.TestCodexEnvironment -> settingsHandler.testCodexEnvironment()
            UiIntent.CheckCodexCliVersion -> settingsHandler.refreshCodexCliVersion(force = true)
            UiIntent.UpgradeCodexCli -> settingsHandler.upgradeCodexCli()
            is UiIntent.IgnoreCodexCliVersion -> settingsHandler.ignoreCodexCliVersion(intent.version)
            UiIntent.SaveSettings -> settingsHandler.saveSettings()
            else -> Unit
        }
    }

    private fun handleUnifiedEvent(sessionId: String, event: UnifiedEvent) {
        planHandler.handleUnifiedEvent(sessionId, event)
    }

    private fun publishUnifiedEvent(sessionId: String, event: UnifiedEvent) {
        eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.UnifiedEventPublished(event))
        com.auracode.assistant.toolwindow.timeline.TimelineNodeMapper.fromUnifiedEvent(event)?.let { mutation ->
            eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.TimelineMutationApplied(mutation = mutation))
        }
        handleUnifiedEvent(sessionId, event)
        if (event is UnifiedEvent.TurnCompleted) {
            conversationHandler.dispatchNextPendingSubmissionIfIdle(sessionId, allowTurnCompletedBypass = true)
        }
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

    private fun publishSettingsSnapshot() {
        val state = settingsService.state
        val selectedEngineId = chatService.defaultEngineId()
        eventHub.publish(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = state.executablePathFor("codex"),
                claudeCliPath = state.executablePathFor("claude"),
                selectedEngineId = selectedEngineId,
                availableEngines = chatService.availableEngines(),
                nodePath = settingsService.nodeExecutablePath(),
                languageMode = settingsService.uiLanguageMode(),
                themeMode = settingsService.uiThemeMode(),
                uiScaleMode = settingsService.uiScaleMode(),
                autoContextEnabled = settingsService.autoContextEnabled(),
                backgroundCompletionNotificationsEnabled = settingsService.backgroundCompletionNotificationsEnabled(),
                codexCliAutoUpdateCheckEnabled = settingsService.codexCliAutoUpdateCheckEnabled(),
                savedAgents = state.savedAgents.toList(),
                selectedAgentIds = settingsService.selectedAgentIds(),
                customModelIds = settingsService.customModelIds(),
                selectedModel = settingsService.selectedComposerModel(selectedEngineId),
                selectedReasoning = settingsService.selectedComposerReasoning(),
                codexCliVersionSnapshot = codexCliVersionService.snapshot(),
            ),
        )
    }

    private fun publishConversationCapabilities() {
        val activeEngineId = chatService.sessionProviderId(chatService.getCurrentSessionId())
        eventHub.publish(
            AppEvent.ConversationCapabilitiesUpdated(
                capabilities = chatService.conversationCapabilities(activeEngineId),
            ),
        )
    }

    /**
     * Applies the selected engine in the current session and clears any resumable remote conversation when needed.
     */
    private fun handleEngineSelection(engineId: String) {
        val normalizedEngineId = engineId.trim().ifBlank { chatService.defaultEngineId() }
        val currentSessionId = activeSessionId()
        val currentSession = chatService.listSessions().firstOrNull { it.id == currentSessionId }
        if (currentSession?.isRunning == true) {
            eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw("Stop the current run before switching engines.")))
            return
        }
        val previousEngineId = chatService.sessionProviderId(currentSessionId)
        if (previousEngineId == normalizedEngineId) {
            publishSettingsSnapshot()
            publishConversationCapabilities()
            publishSessionSnapshot()
            return
        }

        settingsService.setDefaultEngineId(normalizedEngineId)
        val switchedEmptySession = chatService.setSessionProviderIfEmpty(
            sessionId = currentSessionId,
            providerId = normalizedEngineId,
        )
        val switchedInPlace = switchedEmptySession || chatService.resetSessionForEngineSwitch(
            sessionId = currentSessionId,
            providerId = normalizedEngineId,
        )

        if (switchedInPlace && !switchedEmptySession) {
            val targetEngineLabel = chatService.engineDescriptor(normalizedEngineId)?.displayName ?: normalizedEngineId
            eventDispatcher.dispatchSessionEvent(
                currentSessionId,
                AppEvent.TimelineMutationApplied(
                    TimelineMutation.AppendEngineSwitched(
                        sourceId = "engine-switch-${System.currentTimeMillis()}",
                        targetEngineLabel = targetEngineLabel,
                        body = AuraCodeBundle.message("timeline.system.engineSwitched", targetEngineLabel),
                        timestamp = System.currentTimeMillis(),
                    ),
                ),
            )
        }

        publishSettingsSnapshot()
        publishConversationCapabilities()
        publishSessionSnapshot()
    }

    /**
     * Resolves the remembered model for an engine while falling back to that engine's default.
     */
    private fun resolveComposerModelForEngine(engineId: String): String {
        val availableModels = chatService.engineDescriptor(engineId)?.models.orEmpty().toSet()
        val selectedModel = settingsService.selectedComposerModel(engineId)
        return selectedModel.takeIf { it in availableModels }
            ?: settingsService.state.defaultModelFor(engineId)
    }

    private fun activeSessionId(): String = chatService.getCurrentSessionId()

    fun onSessionActivated() {
        publishSessionSnapshot()
        if (!eventDispatcher.restoreCachedSessionState(activeSessionId())) {
            historyHandler.restoreCurrentSessionHistory()
        }
    }

    fun onSessionSwitched(sessionId: String) {
        eventHub.publishUiIntent(UiIntent.SwitchSession(sessionId))
    }

    fun captureSessionState(sessionId: String) {
        eventDispatcher.captureVisibleSessionState(sessionId)
    }

    override fun dispose() {
        scope.cancel()
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
