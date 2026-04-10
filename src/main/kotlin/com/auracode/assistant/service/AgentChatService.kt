package com.auracode.assistant.service

import com.auracode.assistant.coroutine.AppCoroutineManager
import com.auracode.assistant.coroutine.ManagedCoroutineScope
import com.auracode.assistant.conversation.ConversationHistoryPage
import com.auracode.assistant.conversation.ConversationCapabilities
import com.auracode.assistant.conversation.ConversationRef
import com.auracode.assistant.conversation.ConversationSummaryPage
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.model.AgentAction
import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.model.ChatMessage
import com.auracode.assistant.model.ContextFile
import com.auracode.assistant.model.FileAttachment
import com.auracode.assistant.model.ImageAttachment
import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.model.TurnUsageSnapshot
import com.auracode.assistant.persistence.chat.ChatSessionRepository
import com.auracode.assistant.persistence.chat.PersistedMessageAttachment
import com.auracode.assistant.persistence.chat.PersistedSessionAsset
import com.auracode.assistant.persistence.chat.PersistedChatSession
import com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnUsage
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedItem
import com.auracode.assistant.protocol.UnifiedMessageAttachment
import com.auracode.assistant.protocol.UnifiedToolUserInputAnswerDraft
import com.auracode.assistant.provider.CodexProviderFactory
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.approval.ApprovalAction
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.PathManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Service(Service.Level.PROJECT)
class AgentChatService private constructor(
    private val project: Project?,
    private val repository: ChatSessionRepository,
    private val settings: AgentSettingsService,
    private val registry: ProviderRegistry,
    private val workingDirectoryProvider: () -> String,
    private val diagnosticLogger: (String) -> Unit,
) : Disposable {
    constructor(project: Project) : this(
        project = project,
        repository = SQLiteChatSessionRepository(defaultDatabasePath(project)),
        settings = AgentSettingsService.getInstance(),
        registry = ProviderRegistry(AgentSettingsService.getInstance()),
        workingDirectoryProvider = { project.basePath ?: "." },
        diagnosticLogger = { message -> LOG.info(message) },
    )

    internal constructor(
        repository: ChatSessionRepository,
        registry: ProviderRegistry,
        settings: AgentSettingsService,
        workingDirectoryProvider: () -> String = { "." },
        diagnosticLogger: (String) -> Unit = { message -> LOG.info(message) },
    ) : this(
        project = null,
        repository = repository,
        settings = settings,
        registry = registry,
        workingDirectoryProvider = workingDirectoryProvider,
        diagnosticLogger = diagnosticLogger,
    )

    data class SessionSummary(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messageCount: Int,
        val remoteConversationId: String,
        val usageSnapshot: TurnUsageSnapshot? = null,
        val isRunning: Boolean = false,
    )

    private data class SessionData(
        val id: String,
        var providerId: String,
        val createdAt: Long,
        var title: String,
        var updatedAt: Long,
        var messageCount: Int,
        var remoteConversationId: String,
        var usageSnapshot: TurnUsageSnapshot? = null,
    )

    private val scope: ManagedCoroutineScope = AppCoroutineManager.createScope(
        scopeName = "AgentChatService",
        dispatcher = Dispatchers.IO,
        failureReporter = { _, label, error ->
            diagnosticLogger(
                "AgentChatService coroutine failed${label?.let { ": $it" }.orEmpty()}: ${error.message}",
            )
            LOG.error(
                "AgentChatService coroutine failed${label?.let { ": $it" }.orEmpty()}",
                error,
            )
        },
    )
    private val stateLock = Any()
    private val engineLaunchErrorPresenter = EngineLaunchErrorPresenter(
        registry = registry,
    )

    private val sessions = linkedMapOf<String, SessionData>()
    private val sessionRuns = SessionRunRegistry()
    private var currentSessionId: String = ""

    companion object {
        private val LOG = Logger.getInstance(AgentChatService::class.java)

        private fun defaultDatabasePath(project: Project): Path {
            val baseDir = Path.of(PathManager.getSystemPath(), "aura-code", project.locationHash)
            Files.createDirectories(baseDir)
            return baseDir.resolve("chat-history.db")
        }

        private fun defaultAssetDirectory(sessionId: String): Path {
            return Path.of(PathManager.getSystemPath(), "aura-code", "chat-assets", sessionId)
        }
    }

    init {
        loadFromRepository()
    }

    fun getCurrentSessionId(): String = synchronized(stateLock) { currentSessionId }

    fun currentSessionTitle(): String = synchronized(stateLock) {
        sessions[currentSessionId]?.title?.trim().orEmpty().ifBlank { AuraCodeBundle.message("session.new") }
    }

    fun isCurrentSessionEmpty(): Boolean = synchronized(stateLock) {
        (sessions[currentSessionId]?.messageCount ?: 0) == 0
    }

    fun currentUsageSnapshot(): TurnUsageSnapshot? = synchronized(stateLock) {
        sessions[currentSessionId]?.usageSnapshot
    }

    /** Returns the working directory currently used for provider requests. */
    fun currentWorkingDirectory(): String = workingDirectoryProvider()

    internal suspend fun loadCurrentConversationHistory(limit: Int): ConversationHistoryPage {
        val session = synchronized(stateLock) { sessions[currentSessionId] }
            ?: return ConversationHistoryPage(events = emptyList(), hasOlder = false, olderCursor = null)
        val conversationId = session.remoteConversationId.trim()
        if (conversationId.isBlank()) {
            return ConversationHistoryPage(events = emptyList(), hasOlder = false, olderCursor = null)
        }
        val provider = registry.providerOrDefault(session.providerId)
        return runCatching {
            provider.loadInitialHistory(
                ref = ConversationRef(providerId = session.providerId, remoteConversationId = conversationId),
                pageSize = limit,
            )
        }.getOrElse {
            ConversationHistoryPage(events = emptyList(), hasOlder = false, olderCursor = null)
        }.attachLocalAssets(session.id)
    }

    internal suspend fun loadOlderConversationHistory(cursor: String, limit: Int): ConversationHistoryPage {
        val session = synchronized(stateLock) { sessions[currentSessionId] }
            ?: return ConversationHistoryPage(events = emptyList(), hasOlder = false, olderCursor = null)
        val conversationId = session.remoteConversationId.trim()
        if (conversationId.isBlank()) {
            return ConversationHistoryPage(events = emptyList(), hasOlder = false, olderCursor = null)
        }
        val provider = registry.providerOrDefault(session.providerId)
        return runCatching {
            provider.loadOlderHistory(
                ref = ConversationRef(providerId = session.providerId, remoteConversationId = conversationId),
                cursor = cursor,
                pageSize = limit,
            )
        }.getOrElse {
            ConversationHistoryPage(events = emptyList(), hasOlder = false, olderCursor = null)
        }.attachLocalAssets(session.id)
    }

    fun listSessions(): List<SessionSummary> {
        return synchronized(stateLock) {
            sessions.values
                .sortedByDescending { it.updatedAt }
                .map {
                    SessionSummary(
                        id = it.id,
                        title = it.title,
                        updatedAt = it.updatedAt,
                        messageCount = it.messageCount,
                        remoteConversationId = it.remoteConversationId,
                        usageSnapshot = it.usageSnapshot,
                        isRunning = sessionRuns.isRunning(it.id),
                    )
                }
        }
    }

    internal suspend fun loadRemoteConversationSummaries(
        limit: Int,
        cursor: String? = null,
        searchTerm: String? = null,
    ): ConversationSummaryPage {
        val engineId = synchronized(stateLock) {
            sessions[currentSessionId]?.providerId ?: registry.defaultEngineId()
        }
        val provider = registry.providerOrDefault(engineId)
        return runCatching {
            provider.listRemoteConversations(
                pageSize = limit,
                cursor = cursor,
                cwd = workingDirectoryProvider(),
                searchTerm = searchTerm,
            )
        }.getOrElse {
            ConversationSummaryPage(conversations = emptyList(), nextCursor = null)
        }
    }

    internal suspend fun loadFullRemoteConversationHistory(
        remoteConversationId: String,
        pageSize: Int,
    ): ConversationHistoryPage {
        val normalizedRemoteId = remoteConversationId.trim()
        if (normalizedRemoteId.isBlank()) {
            return ConversationHistoryPage(events = emptyList(), hasOlder = false, olderCursor = null)
        }
        val (providerId, sessionId) = synchronized(stateLock) {
            val existingSession = sessions.values.firstOrNull { it.remoteConversationId == normalizedRemoteId }
            val resolvedProviderId = existingSession?.providerId
                ?: sessions[currentSessionId]?.providerId
                ?: registry.defaultEngineId()
            resolvedProviderId to existingSession?.id
        }
        val provider = registry.providerOrDefault(providerId)
        val ref = ConversationRef(providerId = providerId, remoteConversationId = normalizedRemoteId)
        val initialPage = runCatching {
            provider.loadInitialHistory(ref = ref, pageSize = pageSize)
        }.getOrElse {
            return ConversationHistoryPage(events = emptyList(), hasOlder = false, olderCursor = null)
        }.attachLocalAssetsIfAvailable(sessionId)
        if (!initialPage.hasOlder || initialPage.olderCursor.isNullOrBlank()) {
            return initialPage
        }
        val events = initialPage.events.toMutableList()
        var cursor = initialPage.olderCursor
        while (!cursor.isNullOrBlank()) {
            val page = try {
                provider.loadOlderHistory(ref = ref, cursor = cursor, pageSize = pageSize)
            } catch (_: Throwable) {
                break
            }.attachLocalAssetsIfAvailable(sessionId)
            if (page.events.isNotEmpty()) {
                events.addAll(0, page.events)
            }
            cursor = if (page.hasOlder) page.olderCursor else null
        }
        return ConversationHistoryPage(events = events, hasOlder = false, olderCursor = null)
    }

    fun createSession(): String {
        val session = PersistedChatSession(
            id = UUID.randomUUID().toString(),
            providerId = CodexProviderFactory.ENGINE_ID,
            title = "",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            messageCount = 0,
            remoteConversationId = "",
            usageSnapshot = null,
            isActive = true,
        )
        synchronized(stateLock) {
            sessions[session.id] = session.toSessionData()
            sessionRuns.ensureSession(session.id)
            currentSessionId = session.id
        }
        repository.upsertSession(session)
        repository.markActiveSession(session.id)
        return session.id
    }

    fun switchSession(sessionId: String): Boolean {
        val switched = synchronized(stateLock) {
            if (!sessions.containsKey(sessionId)) {
                false
            } else {
                currentSessionId = sessionId
                true
            }
        }
        if (!switched) return false
        repository.markActiveSession(sessionId)
        persistSessionSnapshot(sessionId)
        return true
    }

    fun openRemoteConversation(
        remoteConversationId: String,
        suggestedTitle: String = "",
        providerId: String = registry.defaultEngineId(),
    ): String? {
        val normalizedRemoteId = remoteConversationId.trim()
        if (normalizedRemoteId.isBlank()) return null
        val sessionId = synchronized(stateLock) {
            val existing = sessions.values.firstOrNull {
                it.providerId == providerId && it.remoteConversationId == normalizedRemoteId
            }
            when {
                existing != null -> {
                    existing.updatedAt = System.currentTimeMillis()
                    if (existing.title.isBlank() && suggestedTitle.isNotBlank()) {
                        existing.title = suggestedTitle.trim()
                    }
                    sessionRuns.ensureSession(existing.id)
                    currentSessionId = existing.id
                    existing.id
                }
                currentSessionId.isNotBlank() && sessions[currentSessionId]?.let { current ->
                    current.messageCount == 0 && current.remoteConversationId.isBlank()
                } == true -> {
                    val current = sessions.getValue(currentSessionId)
                    current.providerId = providerId
                    current.remoteConversationId = normalizedRemoteId
                    current.updatedAt = System.currentTimeMillis()
                    if (suggestedTitle.isNotBlank()) {
                        current.title = suggestedTitle.trim()
                    }
                    current.id
                }
                else -> {
                    val now = System.currentTimeMillis()
                    val id = UUID.randomUUID().toString()
                    sessions[id] = SessionData(
                        id = id,
                        providerId = providerId,
                        createdAt = now,
                        title = suggestedTitle.trim(),
                        updatedAt = now,
                        messageCount = 0,
                        remoteConversationId = normalizedRemoteId,
                    )
                    sessionRuns.ensureSession(id)
                    currentSessionId = id
                    id
                }
            }
        }
        repository.markActiveSession(sessionId)
        persistSessionSnapshot(sessionId)
        return sessionId
    }

    fun deleteSession(sessionId: String): Boolean {
        cancelSessionRun(sessionId)
        val fallbackSessionId = synchronized(stateLock) {
            if (!sessions.containsKey(sessionId)) {
                return false
            }
            sessions.remove(sessionId)
            sessionRuns.removeSession(sessionId)
            if (sessions.isEmpty()) {
                currentSessionId = ""
                null
            } else {
                if (currentSessionId == sessionId) {
                    currentSessionId = sessions.values.maxByOrNull { it.updatedAt }?.id ?: sessions.keys.first()
                }
                currentSessionId
            }
        }
        repository.deleteSession(sessionId)
        deleteSessionAssets(sessionId)
        if (fallbackSessionId == null) {
            createSession()
            return true
        }
        repository.markActiveSession(fallbackSessionId)
        persistSessionSnapshot(fallbackSessionId)
        return true
    }

    internal data class LocalUserMessage(
        val sourceId: String,
        val prompt: String,
        val turnId: String?,
        val timestamp: Long,
        val attachments: List<PersistedMessageAttachment>,
    )

    internal fun recordUserMessage(
        sessionId: String = getCurrentSessionId(),
        prompt: String,
        turnId: String = "",
        attachments: List<PersistedMessageAttachment> = emptyList(),
    ): LocalUserMessage? {
        if (sessionId.isBlank()) return null
        val message = ChatMessage(role = MessageRole.USER, content = prompt)
        repository.saveSessionAssets(
            sessionId = sessionId,
            turnId = turnId,
            messageRole = MessageRole.USER,
            attachments = attachments,
            createdAt = message.timestamp,
        )
        synchronized(stateLock) {
            sessions[sessionId]?.applyMessage(message)
        }
        persistSessionSnapshot(sessionId)
        return LocalUserMessage(
            sourceId = message.id,
            prompt = prompt,
            turnId = turnId.ifBlank { null },
            timestamp = message.timestamp,
            attachments = attachments,
        )
    }

    fun runAgent(
        sessionId: String = getCurrentSessionId(),
        engineId: String,
        model: String,
        reasoningEffort: String? = null,
        prompt: String,
        systemInstructions: List<String> = emptyList(),
        localTurnId: String? = null,
        contextFiles: List<ContextFile>,
        imageAttachments: List<ImageAttachment> = emptyList(),
        fileAttachments: List<FileAttachment> = emptyList(),
        approvalMode: AgentApprovalMode = AgentApprovalMode.AUTO,
        collaborationMode: AgentCollaborationMode = AgentCollaborationMode.DEFAULT,
        onTurnPersisted: () -> Unit = {},
        onUnifiedEvent: (UnifiedEvent) -> Unit = {},
    ) {
        cancelSessionRun(sessionId)
        val resolvedModel = resolveModel(engineId, model)
        val remoteConversationId = currentRemoteConversationId(sessionId)
        val request = AgentRequest(
            engineId = engineId,
            action = AgentAction.CHAT,
            model = resolvedModel,
            reasoningEffort = reasoningEffort?.trim()?.takeIf { it.isNotBlank() },
            prompt = prompt,
            systemInstructions = systemInstructions,
            contextFiles = contextFiles,
            imageAttachments = imageAttachments,
            fileAttachments = fileAttachments,
            workingDirectory = workingDirectoryProvider(),
            remoteConversationId = remoteConversationId,
            approvalMode = approvalMode,
            collaborationMode = collaborationMode,
        )
        if (engineId == CodexProviderFactory.ENGINE_ID) {
            logCodexChain(
                "Codex chain request: sessionId=${sessionIdForLog(sessionId)} requestId=${request.requestId} " +
                    "mode=${if (remoteConversationId == null) "start-thread" else "resume-thread"} " +
                    "model=${resolvedModel ?: "<auto>"} " +
                    "remoteConversationId=${remoteConversationId ?: "<none>"} contextFiles=${contextFiles.size} " +
                    "images=${imageAttachments.size} files=${fileAttachments.size}",
            )
        }
        val provider = registry.providerOrDefault(engineId)
        val job = scope.launch(label = "stream:${request.requestId}") {
            val assistantBuffer = StringBuilder()
            var activeTurnId = localTurnId?.trim().orEmpty()
            val countedAssistantItems = mutableSetOf<String>()
            try {
                runCatching {
                    provider.stream(request).collect { unified ->
                        val normalizedEvent = normalizeUnifiedEvent(engineId, unified)
                        collectAssistantOutput(normalizedEvent, assistantBuffer)
                        val persistResult = persistUnifiedEvent(
                            sessionId = sessionId,
                            activeTurnId = activeTurnId,
                            event = normalizedEvent,
                        )
                        activeTurnId = persistResult.turnId
                        if (normalizedEvent is UnifiedEvent.ItemUpdated &&
                            normalizedEvent.item.kind == ItemKind.NARRATIVE &&
                            narrativeRole(normalizedEvent.item) == MessageRole.ASSISTANT &&
                            assistantBuffer.isNotBlank() &&
                            countedAssistantItems.add(normalizedEvent.item.id)
                        ) {
                            synchronized(stateLock) {
                                sessions[sessionId]?.applyMessage(
                                    ChatMessage(
                                        role = MessageRole.ASSISTANT,
                                        content = assistantBuffer.toString().ifBlank { prompt },
                                        timestamp = System.currentTimeMillis(),
                                    ),
                                )
                            }
                            persistSessionSnapshot(sessionId)
                        }
                        if (normalizedEvent is UnifiedEvent.ThreadTokenUsageUpdated) {
                            updateCurrentUsageSnapshot(
                                sessionId = sessionId,
                                model = resolvedModel ?: model,
                                contextWindow = normalizedEvent.contextWindow,
                                inputTokens = normalizedEvent.inputTokens,
                                cachedInputTokens = normalizedEvent.cachedInputTokens,
                                outputTokens = normalizedEvent.outputTokens,
                                engineId = engineId,
                            )
                        }
                        onUnifiedEvent(normalizedEvent)
                    }
                    onTurnPersisted()
                }.onFailure { error ->
                    if (error is CancellationException) {
                        return@onFailure
                    }
                    runCatching {
                        LOG.error(
                            "AgentChatService stream coroutine failed: requestId=${request.requestId} engineId=$engineId sessionId=$sessionId",
                            error,
                        )
                    }
                    onUnifiedEvent(normalizeThrowableAsUnifiedError(engineId, error))
                }
            } finally {
                synchronized(stateLock) {
                    sessionRuns.clearRun(sessionId, request.requestId)
                }
            }
        }
        synchronized(stateLock) {
            sessionRuns.ensureSession(sessionId)
            sessionRuns.replaceRun(sessionId, request.requestId, job)
        }
    }

    fun cancelCurrent() {
        cancelSessionRun(getCurrentSessionId())
    }

    fun cancelSessionRun(sessionId: String) {
        val cancelledRun = synchronized(stateLock) {
            sessionRuns.clearRun(sessionId)
        } ?: return
        cancelledRun.job?.cancel()
        cancelledRun.requestId?.let { id ->
            runCatching {
                registry.cancel(id)
            }
        }
    }

    fun submitApprovalDecision(requestId: String, decision: ApprovalAction): Boolean {
        return registry.submitApprovalDecision(requestId, decision)
    }

    fun submitToolUserInput(
        requestId: String,
        answers: Map<String, UnifiedToolUserInputAnswerDraft>,
    ): Boolean {
        return registry.submitToolUserInput(requestId, answers)
    }

    fun availableEngines(): List<EngineDescriptor> = registry.engines()

    fun defaultEngineId(): String = registry.defaultEngineId()

    fun conversationCapabilities(engineId: String = defaultEngineId()): ConversationCapabilities {
        return registry.providerOrDefault(engineId).capabilities()
    }

    fun engineDescriptor(engineId: String): EngineDescriptor? = registry.engine(engineId)

    fun executeCommand(command: String, workingDirectory: String): Pair<Int, String> {
        val cli = if (System.getProperty("os.name").lowercase().contains("win")) {
            GeneralCommandLine("cmd", "/c", command)
        } else {
            GeneralCommandLine("sh", "-lc", command)
        }
        cli.withWorkDirectory(workingDirectory)
        val output = CapturingProcessHandler(cli).runProcess(60_000)
        val text = buildString {
            if (output.stdout.isNotBlank()) {
                append(output.stdout)
            }
            if (output.stderr.isNotBlank()) {
                if (isNotEmpty()) {
                    append("\n")
                }
                append(output.stderr)
            }
        }.trim()
        return output.exitCode to text
    }

    override fun dispose() {
        val sessionIds = synchronized(stateLock) { sessions.keys.toList() }
        sessionIds.forEach(::cancelSessionRun)
        scope.cancel()
    }

    private fun loadFromRepository() {
        val persistedSessions = repository.listSessions()
        if (persistedSessions.isEmpty()) {
            createSession()
            return
        }

        synchronized(stateLock) {
            sessions.clear()
            persistedSessions.forEach { session ->
                sessions[session.id] = session.toSessionData()
            }
            sessionRuns.retainSessions(sessions.keys)
            currentSessionId = persistedSessions.firstOrNull { it.isActive }?.id
                ?: persistedSessions.maxByOrNull { it.updatedAt }?.id
                ?: sessions.keys.first()
        }
        repository.markActiveSession(getCurrentSessionId())
    }

    private fun persistSessionSnapshot(sessionId: String) {
        val snapshot = synchronized(stateLock) {
            sessions[sessionId]?.toPersisted(isActive = sessionId == currentSessionId)
        } ?: return
        repository.upsertSession(snapshot)
    }

    private fun collectAssistantOutput(
        event: UnifiedEvent,
        assistantBuffer: StringBuilder,
    ) {
        when (event) {
            is UnifiedEvent.ItemUpdated -> {
                val item = event.item
                if (item.kind == ItemKind.NARRATIVE &&
                    narrativeRole(item) == MessageRole.ASSISTANT &&
                    !item.text.isNullOrBlank()
                ) {
                    assistantBuffer.clear()
                    assistantBuffer.append(item.text)
                }
            }
            else -> Unit
        }
    }

    private fun persistUnifiedEvent(
        sessionId: String,
        activeTurnId: String,
        event: UnifiedEvent,
    ): PersistResult {
        var nextTurnId = activeTurnId
        when (event) {
            is UnifiedEvent.ApprovalRequested -> Unit
            is UnifiedEvent.ToolUserInputRequested -> Unit
            is UnifiedEvent.ToolUserInputResolved -> Unit
            is UnifiedEvent.ThreadStarted -> updateCurrentRemoteConversationId(sessionId, event.threadId)
            is UnifiedEvent.ThreadTokenUsageUpdated -> Unit
            is UnifiedEvent.TurnDiffUpdated -> Unit
            is UnifiedEvent.RunningPlanUpdated -> Unit
            is UnifiedEvent.TurnStarted -> {
                val incoming = event.turnId.trim()
                if (incoming.isNotBlank()) {
                    repository.replaceSessionAssetTurnId(sessionId, nextTurnId, incoming)
                    nextTurnId = incoming
                }
            }

            is UnifiedEvent.ItemUpdated -> {
                val item = event.item
                if (item.kind == ItemKind.NARRATIVE && narrativeRole(item) == MessageRole.USER) {
                    // user messages are restored from remote history but not duplicated into local persistence
                }
            }
            is UnifiedEvent.TurnCompleted -> if (event.turnId.isNotBlank()) nextTurnId = event.turnId
            is UnifiedEvent.Error -> Unit
        }
        return PersistResult(
            turnId = nextTurnId,
        )
    }

    private fun currentRemoteConversationId(sessionId: String): String? = synchronized(stateLock) {
        sessions[sessionId]?.remoteConversationId?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun updateCurrentRemoteConversationId(sessionId: String, remoteConversationId: String) {
        val trimmed = remoteConversationId.trim()
        if (trimmed.isBlank()) return
        synchronized(stateLock) {
            sessions[sessionId]?.remoteConversationId = trimmed
            sessions[sessionId]?.updatedAt = System.currentTimeMillis()
        }
        logCodexChain("Codex chain stored remote conversation: sessionId=${sessionIdForLog(sessionId)} remoteConversationId=$trimmed")
        persistSessionSnapshot(sessionId)
    }

    private fun updateCurrentUsageSnapshot(
        sessionId: String,
        model: String?,
        contextWindow: Int,
        inputTokens: Int,
        cachedInputTokens: Int,
        outputTokens: Int,
        engineId: String,
    ) {
        val snapshot = TurnUsageSnapshot(
            model = model?.trim().orEmpty(),
            contextWindow = contextWindow,
            inputTokens = inputTokens,
            cachedInputTokens = cachedInputTokens,
            outputTokens = outputTokens,
        )
        synchronized(stateLock) {
            sessions[sessionId]?.usageSnapshot = snapshot
            sessions[sessionId]?.updatedAt = snapshot.capturedAt
        }
        if (engineId == CodexProviderFactory.ENGINE_ID) {
            logCodexChain(
                "Codex chain usage captured: sessionId=${sessionIdForLog(sessionId)} " +
                    "model=${snapshot.model.ifBlank { "<unknown>" }} contextWindow=${snapshot.contextWindow} " +
                    "inputTokens=${snapshot.inputTokens} cachedInputTokens=${snapshot.cachedInputTokens} " +
                    "outputTokens=${snapshot.outputTokens}",
            )
        }
        persistSessionSnapshot(sessionId)
    }

    private fun logCodexChain(message: String) {
        diagnosticLogger(message)
    }

    private fun sessionIdForLog(sessionId: String? = null): String = synchronized(stateLock) {
        sessionId?.takeIf { it.isNotBlank() } ?: currentSessionId.ifBlank { "<none>" }
    }

    private fun resolveModel(engineId: String, selectedModel: String): String? {
        val trimmed = selectedModel.trim()
        return if (engineId == "codex") trimmed.ifBlank { null } else trimmed.ifBlank { null }
    }

    private fun normalizeUnifiedEvent(engineId: String, event: UnifiedEvent): UnifiedEvent {
        if (event is UnifiedEvent.Error) {
            val normalizedMessage = engineLaunchErrorPresenter.present(engineId, event.message) ?: event.message
            return event.copy(message = normalizedMessage)
        }
        return event
    }

    private fun normalizeThrowableAsUnifiedError(engineId: String, error: Throwable): UnifiedEvent.Error {
        val rawMessage = error.message.orEmpty().ifBlank { error::class.java.simpleName }
        val normalizedMessage = engineLaunchErrorPresenter.present(engineId, rawMessage) ?: rawMessage
        return UnifiedEvent.Error(normalizedMessage)
    }

    private fun PersistedChatSession.toSessionData(): SessionData {
        return SessionData(
            id = id,
            providerId = providerId,
            createdAt = createdAt,
            title = title.trim(),
            updatedAt = updatedAt,
            messageCount = messageCount,
            remoteConversationId = remoteConversationId,
            usageSnapshot = usageSnapshot,
        )
    }

    private fun SessionData.toPersisted(isActive: Boolean): PersistedChatSession {
        return PersistedChatSession(
            id = id,
            providerId = providerId,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            messageCount = messageCount,
            remoteConversationId = remoteConversationId,
            usageSnapshot = usageSnapshot,
            isActive = isActive,
        )
    }

    private fun SessionData.applyMessage(message: ChatMessage) {
        if (message.role == MessageRole.USER && title.isBlank()) {
            title = deriveTitle(message.content)
        }
        messageCount += 1
        updatedAt = maxOf(updatedAt, message.timestamp)
    }

    private fun deriveTitle(firstUserMessage: String): String {
        val trimmed = firstUserMessage.trim()
        return if (trimmed.isBlank()) "" else trimmed.take(36)
    }

    private fun deleteSessionAssets(sessionId: String) {
        val dir = defaultAssetDirectory(sessionId)
        if (!Files.exists(dir)) return
        runCatching {
            Files.walk(dir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach { path -> Files.deleteIfExists(path) }
        }
    }

    private data class PersistResult(
        val turnId: String,
    )

    private fun narrativeRole(item: UnifiedItem): MessageRole {
        return when (item.name) {
            "user_message" -> MessageRole.USER
            "system_message" -> MessageRole.SYSTEM
            else -> MessageRole.ASSISTANT
        }
    }

    private fun ConversationHistoryPage.attachLocalAssets(sessionId: String): ConversationHistoryPage {
        if (events.isEmpty()) return this
        val assetsByTurn = repository.loadSessionAssets(sessionId)
            .filter { it.messageRole == MessageRole.USER && it.turnId.isNotBlank() }
            .groupBy { it.turnId }
            .mapValues { (_, value) ->
                value.map { asset ->
                    UnifiedMessageAttachment(
                        id = asset.attachment.id,
                        kind = asset.attachment.kind.name.lowercase(),
                        displayName = asset.attachment.displayName,
                        assetPath = asset.attachment.assetPath,
                        originalPath = asset.attachment.originalPath,
                        mimeType = asset.attachment.mimeType,
                        sizeBytes = asset.attachment.sizeBytes,
                        status = asset.attachment.status,
                    )
                }
            }
        if (assetsByTurn.isEmpty()) return this
        var activeTurnId: String? = null
        return copy(
            events = events.map { event ->
                when (event) {
                    is UnifiedEvent.TurnStarted -> {
                        activeTurnId = event.turnId
                        event
                    }
                    is UnifiedEvent.ItemUpdated -> {
                        val turnId = activeTurnId
                        if (
                            event.item.kind == ItemKind.NARRATIVE &&
                            narrativeRole(event.item) == MessageRole.USER &&
                            !turnId.isNullOrBlank()
                        ) {
                            val attachments = assetsByTurn[turnId].orEmpty()
                            if (attachments.isNotEmpty()) {
                                event.copy(item = event.item.copy(attachments = attachments))
                            } else {
                                event
                            }
                        } else {
                            event
                        }
                    }
                    else -> event
                }
            },
        )
    }

    private fun ConversationHistoryPage.attachLocalAssetsIfAvailable(sessionId: String?): ConversationHistoryPage {
        if (sessionId.isNullOrBlank()) return this
        return attachLocalAssets(sessionId)
    }
}
