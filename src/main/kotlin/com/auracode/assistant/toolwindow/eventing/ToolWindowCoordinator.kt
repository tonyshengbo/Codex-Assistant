package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.coroutine.AppCoroutineManager
import com.auracode.assistant.coroutine.ManagedCoroutineScope
import com.auracode.assistant.context.MentionFileWhitelist
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.notification.ChatCompletionNotificationService
import com.auracode.assistant.persistence.chat.PersistedMessageAttachment
import com.auracode.assistant.provider.claude.ClaudeCliVersionService
import com.auracode.assistant.provider.codex.CodexCliVersionService
import com.auracode.assistant.provider.codex.CodexEnvironmentDetector
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.session.kernel.SessionKernelManager
import com.auracode.assistant.session.kernel.SessionMessageAttachment
import com.auracode.assistant.session.kernel.SessionMessageRole
import com.auracode.assistant.session.projection.SessionProjectionBuilder
import com.auracode.assistant.session.projection.execution.ExecutionProjection
import com.auracode.assistant.session.normalizer.UnifiedEventSessionEventMapper
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.settings.skills.LocalSkillInstallPolicy
import com.auracode.assistant.settings.skills.SkillsRuntimeService
import com.auracode.assistant.settings.mcp.McpManagementAdapterRegistry
import com.auracode.assistant.provider.runtime.RuntimeExecutableCheckService
import com.auracode.assistant.toolwindow.execution.ApprovalAreaStore
import com.auracode.assistant.toolwindow.submission.ComposerAreaStore
import com.auracode.assistant.toolwindow.shell.RightDrawerAreaStore
import com.auracode.assistant.toolwindow.shell.RightDrawerKind
import com.auracode.assistant.toolwindow.shared.UiText
import com.auracode.assistant.toolwindow.sessions.SessionAttentionStore
import com.auracode.assistant.toolwindow.sessions.SessionComposerViewStateRegistry
import com.auracode.assistant.toolwindow.sessions.SessionTimelineUiStateRegistry
import com.auracode.assistant.toolwindow.sessions.HeaderAreaStore
import com.auracode.assistant.toolwindow.execution.StatusAreaStore
import com.auracode.assistant.toolwindow.conversation.TimelineAreaStore
import com.auracode.assistant.toolwindow.conversation.TimelineFileChange
import com.auracode.assistant.toolwindow.conversation.TimelineMutation
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptStore
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineDispatcher
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
    private val claudeCliVersionService: ClaudeCliVersionService = ClaudeCliVersionService(settingsService),
    private val runtimeExecutableCheckService: RuntimeExecutableCheckService = RuntimeExecutableCheckService(),
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
    private val runStartupWarmups: Boolean = true,
    private val scopeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Disposable {
    companion object {
        private val LOG = Logger.getInstance(ToolWindowCoordinator::class.java)
        private const val MENTION_LIMIT: Int = 10
        private const val EXECUTE_APPROVED_PLAN_PROMPT: String =
            "The user approved the latest plan. Execute it now."
    }

    /** Stores per-session paging metadata that is not part of the kernel event log. */
    private data class SessionTimelinePaging(
        val oldestCursor: String? = null,
        val hasOlder: Boolean = false,
    )

    private val scope: ManagedCoroutineScope = AppCoroutineManager.createScope(
        scopeName = "ToolWindowCoordinator",
        dispatcher = scopeDispatcher,
        failureReporter = { _, label, error ->
            LOG.error("ToolWindowCoordinator coroutine failed${label?.let { ": $it" }.orEmpty()}", error)
        },
    )
    private val recentFocusedFiles = ArrayDeque<String>()
    private val sessionKernelManager = SessionKernelManager()
    private val sessionProjectionBuilder = SessionProjectionBuilder()
    private val unifiedEventMappersBySessionId = linkedMapOf<String, UnifiedEventSessionEventMapper>()
    private val sessionTimelinePagingBySessionId = linkedMapOf<String, SessionTimelinePaging>()
    private val sessionComposerViewStateRegistry = SessionComposerViewStateRegistry()
    private val sessionTimelineUiStateRegistry = SessionTimelineUiStateRegistry()
    private val pendingSubmissionsBySessionId = linkedMapOf<String, ArrayDeque<com.auracode.assistant.toolwindow.submission.PendingComposerSubmission>>()
    private val activePlanRunContexts = linkedMapOf<String, ActivePlanRunContext>()
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
        claudeCliVersionService = claudeCliVersionService,
        runtimeExecutableCheckService = runtimeExecutableCheckService,
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
        coroutineLauncher = coroutineLauncher,
        dispatchSessionEvent = { sessionId, event -> dispatchSessionEvent(sessionId, event) },
        captureSessionViewState = { sessionId -> captureSessionViewState(sessionId) },
        restoreSessionViewState = { sessionId -> restoreSessionViewState(sessionId) },
        publishSessionSnapshot = { publishSessionSnapshot() },
        publishSettingsSnapshot = { publishSettingsSnapshot() },
        publishConversationCapabilities = { publishConversationCapabilities() },
        publishUnifiedEvent = { sessionId: String, event: UnifiedEvent -> publishUnifiedEvent(sessionId, event) },
        publishLocalUserMessage = { sessionId, sourceId, text, timestamp, turnId, attachments ->
            publishLocalUserMessage(
                sessionId = sessionId,
                sourceId = sourceId,
                text = text,
                timestamp = timestamp,
                turnId = turnId,
                attachments = attachments,
            )
        },
        restoreSessionHistory = { sessionId, events, oldestCursor, hasOlder, prepend ->
            restoreSessionHistory(
                sessionId = sessionId,
                events = events,
                oldestCursor = oldestCursor,
                hasOlder = hasOlder,
                prepend = prepend,
            )
        },
        applySessionDomainEvents = { sessionId, events ->
            applySessionDomainEvents(
                sessionId = sessionId,
                events = events,
            )
        },
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
        if (runStartupWarmups) {
            settingsHandler.warmSkillsRuntimeCache()
            settingsHandler.warmCodexCliVersionState()
            settingsHandler.warmClaudeCliVersionState()
        }
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
            is UiIntent.SelectRuntimeSettingsTab -> settingsHandler.onRuntimeSettingsTabSelected(intent.tab)
            UiIntent.DiscardRuntimeSettingsChanges -> {
                settingsHandler.onRuntimeSettingsTabSelected(rightDrawerStore.state.value.runtimeSettingsTab)
            }
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
                    )
                }
            }
            UiIntent.CancelToolUserInputPrompt -> {
                planHandler.submitToolUserInputPrompt(cancelled = true) {
                    conversationHandler.cancelPromptRun(
                        onResetPlanFlowState = { planHandler.resetPlanFlowState() },
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
            UiIntent.CheckClaudeCliVersion -> settingsHandler.refreshClaudeCliVersion(force = true)
            UiIntent.UpgradeClaudeCli -> settingsHandler.upgradeClaudeCli()
            is UiIntent.IgnoreCodexCliVersion -> settingsHandler.ignoreCodexCliVersion(intent.version)
            UiIntent.SaveSettings -> settingsHandler.saveSettings()
            else -> Unit
        }
    }

    private fun handleUnifiedEvent(sessionId: String, event: UnifiedEvent) {
        planHandler.handleUnifiedEvent(sessionId, event)
    }

    private fun publishUnifiedEvent(sessionId: String, event: UnifiedEvent) {
        dispatchSessionEvent(sessionId, AppEvent.UnifiedEventPublished(event))
        applyUnifiedEventToKernel(sessionId = sessionId, event = event)
        handleUnifiedEvent(sessionId, event)
        if (event is UnifiedEvent.TurnCompleted) {
            conversationHandler.dispatchNextPendingSubmissionIfIdle(sessionId, allowTurnCompletedBypass = true)
        }
    }

    /** Records one local user message into the session kernel before provider events arrive. */
    private fun publishLocalUserMessage(
        sessionId: String,
        sourceId: String,
        text: String,
        timestamp: Long,
        turnId: String?,
        attachments: List<PersistedMessageAttachment>,
    ) {
        val localEvents = buildList {
            turnId?.takeIf { it.isNotBlank() }?.let { localTurnId ->
                add(
                    SessionDomainEvent.TurnStarted(
                        turnId = localTurnId,
                        threadId = kernelForSession(sessionId).currentState.runtime.activeThreadId,
                        startedAtMs = timestamp,
                    ),
                )
            }
            add(
                SessionDomainEvent.MessageAppended(
                    messageId = sourceId,
                    turnId = turnId,
                    role = SessionMessageRole.USER,
                    text = text,
                    attachments = attachments.map { attachment ->
                        SessionMessageAttachment(
                            id = attachment.id,
                            kind = attachment.kind.name.lowercase(),
                            displayName = attachment.displayName,
                            assetPath = attachment.assetPath,
                            originalPath = attachment.originalPath,
                            mimeType = attachment.mimeType,
                            sizeBytes = attachment.sizeBytes,
                            status = when (attachment.status) {
                                com.auracode.assistant.protocol.ItemStatus.RUNNING ->
                                    com.auracode.assistant.session.kernel.SessionActivityStatus.RUNNING

                                com.auracode.assistant.protocol.ItemStatus.SUCCESS ->
                                    com.auracode.assistant.session.kernel.SessionActivityStatus.SUCCESS

                                com.auracode.assistant.protocol.ItemStatus.FAILED ->
                                    com.auracode.assistant.session.kernel.SessionActivityStatus.FAILED

                                com.auracode.assistant.protocol.ItemStatus.SKIPPED ->
                                    com.auracode.assistant.session.kernel.SessionActivityStatus.SKIPPED
                            },
                        )
                    },
                ),
            )
        }
        applySessionDomainEvents(sessionId = sessionId, events = localEvents)
    }

    /** Restores or prepends persisted history through the same kernel and projection pipeline as live events. */
    private fun restoreSessionHistory(
        sessionId: String,
        events: List<UnifiedEvent>,
        oldestCursor: String?,
        hasOlder: Boolean,
        prepend: Boolean,
    ) {
        val mapper = mapperForSession(sessionId)
        val domainEvents = if (prepend) {
            val prependMapper = UnifiedEventSessionEventMapper()
            events.flatMap(prependMapper::map)
        } else {
            mapper.reset()
            events.flatMap(mapper::map)
        }
        val kernel = kernelForSession(sessionId)
        if (prepend) {
            kernel.prependHistory(domainEvents)
        } else {
            kernel.restoreHistory(domainEvents)
        }
        sessionTimelinePagingBySessionId[sessionId] = SessionTimelinePaging(
            oldestCursor = oldestCursor,
            hasOlder = hasOlder,
        )
        events.filterIsInstance<UnifiedEvent.SubagentsUpdated>()
            .lastOrNull()
            ?.let { subagentsEvent ->
                dispatchSessionEvent(
                    sessionId,
                    AppEvent.UnifiedEventPublished(subagentsEvent),
                )
            }
        syncSessionProjection(sessionId)
        if (sessionId == activeSessionId()) {
            sessionTimelineUiStateRegistry.restore(sessionId, timelineStore)
        }
    }

    /** Applies one live unified event to the session kernel. */
    private fun applyUnifiedEventToKernel(
        sessionId: String,
        event: UnifiedEvent,
    ) {
        val mappedEvents = mapperForSession(sessionId).map(event)
        if (mappedEvents.isEmpty()) {
            if (event is UnifiedEvent.ThreadStarted || event is UnifiedEvent.TurnCompleted || event is UnifiedEvent.Error) {
                syncSessionProjection(sessionId)
            }
            return
        }
        applySessionDomainEvents(
            sessionId = sessionId,
            events = mappedEvents,
        )
    }

    /** Applies one or more kernel domain events and republishes the read-only session projection. */
    private fun applySessionDomainEvents(
        sessionId: String,
        events: List<SessionDomainEvent>,
    ) {
        if (events.isEmpty()) {
            return
        }
        kernelForSession(sessionId).applyLiveEvents(events)
        syncSessionProjection(sessionId)
    }

    /** Rebuilds the read-only projection for one session and pushes it into scoped UI stores. */
    private fun syncSessionProjection(sessionId: String) {
        val projection = sessionProjectionBuilder.project(kernelForSession(sessionId).currentState)
        val paging = sessionTimelinePagingBySessionId[sessionId] ?: SessionTimelinePaging()
        dispatchSessionEvent(
            sessionId,
            AppEvent.ConversationProjectionUpdated(
                nodes = projection.conversation.nodes,
                oldestCursor = paging.oldestCursor,
                hasOlder = paging.hasOlder,
                isRunning = projection.conversation.isRunning,
                latestError = projection.conversation.latestError,
            ),
        )
        syncExecutionProjection(sessionId = sessionId, projection = projection.execution)
    }

    /** Pushes the execution slice of the current projection into approval, tool-input, plan, and status stores. */
    private fun syncExecutionProjection(
        sessionId: String,
        projection: ExecutionProjection,
    ) {
        dispatchSessionEvent(
            sessionId,
            AppEvent.ExecutionProjectionUpdated(
                approvals = projection.approvals,
                toolUserInputs = projection.toolUserInputs,
                runningPlan = projection.runningPlan,
                turnStatus = projection.turnStatus,
            ),
        )
    }

    /** Returns or creates the kernel that owns one session state graph. */
    private fun kernelForSession(sessionId: String) = sessionKernelManager.getOrCreate(
        sessionId = sessionId,
        engineId = chatService.sessionProviderId(sessionId),
    )

    /** Returns or creates the stateful unified-event mapper bound to one session. */
    private fun mapperForSession(sessionId: String): UnifiedEventSessionEventMapper {
        return unifiedEventMappersBySessionId.getOrPut(sessionId) { UnifiedEventSessionEventMapper() }
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
                claudeCliVersionSnapshot = claudeCliVersionService.snapshot(),
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

        if (switchedInPlace) {
            sessionKernelManager.remove(currentSessionId)
            unifiedEventMappersBySessionId.remove(currentSessionId)
            sessionTimelinePagingBySessionId.remove(currentSessionId)
            sessionComposerViewStateRegistry.drop(currentSessionId)
            sessionTimelineUiStateRegistry.drop(currentSessionId)
            // Rebuild an empty projection immediately so the reused session no longer carries stale running UI state.
            syncSessionProjection(currentSessionId)
        }

        if (switchedInPlace && !switchedEmptySession) {
            val targetEngineLabel = chatService.engineDescriptor(normalizedEngineId)?.displayName ?: normalizedEngineId
            dispatchSessionEvent(
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
        if (!restoreSessionViewState(activeSessionId())) {
            historyHandler.restoreCurrentSessionHistory()
        }
    }

    fun onSessionSwitched(sessionId: String) {
        eventHub.publishUiIntent(UiIntent.SwitchSession(sessionId))
    }

    fun captureSessionState(sessionId: String) {
        captureSessionViewState(sessionId)
    }

    /** Routes a session-scoped UI event either to the visible stores or to per-session draft state registries. */
    private fun dispatchSessionEvent(
        sessionId: String,
        event: AppEvent,
    ) {
        if (sessionId == activeSessionId()) {
            statusStore.onEvent(event)
            timelineStore.onEvent(event)
            composerStore.onEvent(event)
            approvalStore.onEvent(event)
            toolUserInputPromptStore.onEvent(event)
            return
        }
        sessionComposerViewStateRegistry.applyEvent(sessionId, event)
    }

    /** Captures the visible session-scoped draft and expansion state before the UI switches tabs. */
    private fun captureSessionViewState(sessionId: String) {
        if (sessionId.isBlank()) return
        sessionComposerViewStateRegistry.capture(sessionId, composerStore.state.value)
        sessionTimelineUiStateRegistry.capture(sessionId, timelineStore.state.value)
    }

    /** Restores session-local draft UI while rebuilding projection-backed state from the kernel when available. */
    private fun restoreSessionViewState(sessionId: String): Boolean {
        val hasKernel = sessionKernelManager.get(sessionId) != null
        if (hasKernel) {
            syncSessionProjection(sessionId)
            sessionTimelineUiStateRegistry.restore(sessionId, timelineStore)
        }
        composerStore.restoreState(
            sessionComposerViewStateRegistry.restore(
                sessionId = sessionId,
                baseState = composerStore.state.value,
            ),
        )
        return hasKernel
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
