package com.codex.assistant.service

import com.codex.assistant.i18n.CodexBundle
import com.codex.assistant.model.AgentAction
import com.codex.assistant.model.AgentRequest
import com.codex.assistant.model.ChatMessage
import com.codex.assistant.model.ContextFile
import com.codex.assistant.model.EngineEvent
import com.codex.assistant.model.FileAttachment
import com.codex.assistant.model.ImageAttachment
import com.codex.assistant.model.MessageRole
import com.codex.assistant.model.TimelineAction
import com.codex.assistant.model.TurnUsageSnapshot
import com.codex.assistant.persistence.chat.ChatSessionRepository
import com.codex.assistant.persistence.chat.PersistedActivityKind
import com.codex.assistant.persistence.chat.PersistedMessageAttachment
import com.codex.assistant.persistence.chat.PersistedTimelineEntry
import com.codex.assistant.persistence.chat.PersistedChatSession
import com.codex.assistant.persistence.chat.SQLiteChatSessionRepository
import com.codex.assistant.persistence.chat.TimelineHistoryPage
import com.codex.assistant.protocol.EngineEventBridge
import com.codex.assistant.protocol.ActivityTitleFormatter
import com.codex.assistant.protocol.ItemKind
import com.codex.assistant.protocol.ItemStatus
import com.codex.assistant.protocol.UnifiedEvent
import com.codex.assistant.protocol.UnifiedItem
import com.codex.assistant.provider.CodexProviderFactory
import com.codex.assistant.provider.EngineDescriptor
import com.codex.assistant.provider.ProviderRegistry
import com.codex.assistant.settings.AgentSettingsService
import com.codex.assistant.timeline.TimelineActionAssembler
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.PathManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
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
    )

    private data class SessionData(
        val id: String,
        val createdAt: Long,
        var title: String,
        var updatedAt: Long,
        var messageCount: Int,
        var cliSessionId: String,
        var usageSnapshot: TurnUsageSnapshot? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()

    private var currentJob: Job? = null
    private var currentRequestId: String? = null

    private val sessions = linkedMapOf<String, SessionData>()
    private var currentSessionId: String = ""

    companion object {
        private val LOG = Logger.getInstance(AgentChatService::class.java)

        private fun defaultDatabasePath(project: Project): Path {
            val baseDir = Path.of(PathManager.getSystemPath(), "codex-assistant", project.locationHash)
            Files.createDirectories(baseDir)
            return baseDir.resolve("chat-history.db")
        }

        private fun defaultAssetDirectory(sessionId: String): Path {
            return Path.of(PathManager.getSystemPath(), "codex-assistant", "chat-assets", sessionId)
        }
    }

    init {
        loadFromRepository()
    }

    fun getCurrentSessionId(): String = synchronized(stateLock) { currentSessionId }

    fun currentSessionTitle(): String = synchronized(stateLock) {
        sessions[currentSessionId]?.title?.trim().orEmpty().ifBlank { CodexBundle.message("session.new") }
    }

    fun isCurrentSessionEmpty(): Boolean = synchronized(stateLock) {
        (sessions[currentSessionId]?.messageCount ?: 0) == 0
    }

    fun currentUsageSnapshot(): TurnUsageSnapshot? = synchronized(stateLock) {
        sessions[currentSessionId]?.usageSnapshot
    }

    internal fun loadCurrentSessionTimeline(limit: Int): TimelineHistoryPage {
        val sessionId = synchronized(stateLock) { currentSessionId }
        if (sessionId.isBlank()) {
            return TimelineHistoryPage(entries = emptyList(), hasOlder = false)
        }
        return repository.loadRecentTimeline(sessionId = sessionId, limit = limit)
    }

    internal fun loadOlderTimeline(beforeCursorExclusive: Long, limit: Int): TimelineHistoryPage {
        if (beforeCursorExclusive <= 0L) {
            return TimelineHistoryPage(entries = emptyList(), hasOlder = false)
        }
        val sessionId = synchronized(stateLock) { currentSessionId }
        if (sessionId.isBlank()) {
            return TimelineHistoryPage(entries = emptyList(), hasOlder = false)
        }
        return repository.loadTimelineBefore(
            sessionId = sessionId,
            beforeCursorExclusive = beforeCursorExclusive,
            limit = limit,
        )
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
                    )
                }
        }
    }

    fun createSession(): String {
        val session = PersistedChatSession(
            id = UUID.randomUUID().toString(),
            title = "",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            messageCount = 0,
            remoteThreadId = "",
            usageSnapshot = null,
            isActive = true,
        )
        synchronized(stateLock) {
            sessions[session.id] = session.toSessionData()
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

    fun deleteSession(sessionId: String): Boolean {
        val fallbackSessionId = synchronized(stateLock) {
            if (!sessions.containsKey(sessionId)) {
                return false
            }
            sessions.remove(sessionId)
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

    internal fun recordUserMessage(
        prompt: String,
        turnId: String = "",
        attachments: List<PersistedMessageAttachment> = emptyList(),
    ): PersistedTimelineEntry? {
        val sessionId = synchronized(stateLock) { currentSessionId }
        if (sessionId.isBlank()) return null
        val message = ChatMessage(role = MessageRole.USER, content = prompt)
        val entry = repository.insertMessageRecord(
            sessionId = sessionId,
            message = message,
            turnId = turnId,
            sourceId = message.id,
            attachments = attachments,
        )
        synchronized(stateLock) {
            sessions[sessionId]?.applyMessage(message)
        }
        persistSessionSnapshot(sessionId)
        return entry
    }

    fun runAgent(
        engineId: String,
        model: String,
        reasoningEffort: String? = null,
        prompt: String,
        systemInstructions: List<String> = emptyList(),
        localTurnId: String? = null,
        contextFiles: List<ContextFile>,
        imageAttachments: List<ImageAttachment> = emptyList(),
        fileAttachments: List<FileAttachment> = emptyList(),
        onTurnPersisted: () -> Unit = {},
        onUnifiedEvent: (UnifiedEvent) -> Unit = {},
        onAction: (TimelineAction) -> Unit,
    ) {
        cancelCurrent()
        val resolvedModel = resolveModel(engineId, model)
        val cliSessionId = currentCliSessionId()
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
            cliSessionId = cliSessionId,
        )
        if (engineId == CodexProviderFactory.ENGINE_ID) {
            logCodexChain(
                "Codex chain request: sessionId=${sessionIdForLog()} requestId=${request.requestId} " +
                    "mode=${if (cliSessionId == null) "exec" else "resume"} " +
                    "model=${resolvedModel ?: "<auto>"} " +
                    "cliSessionId=${cliSessionId ?: "<none>"} contextFiles=${contextFiles.size} " +
                    "images=${imageAttachments.size} files=${fileAttachments.size}",
            )
        }
        val provider = registry.providerOrDefault(engineId)
        val job = scope.launch {
            val assembler = TimelineActionAssembler()
            val runtime = TimelineActionRuntime(
                scope = this,
                supportsCommandExecution = registry.engine(engineId)?.capabilities?.supportsCommandProposal == true,
                commandExecutor = { command, cwd ->
                    withContext(Dispatchers.IO) {
                        executeCommand(command, cwd)
                    }
                },
                emitAction = onAction,
            )
            val assistantBuffer = StringBuilder()
            val sessionId = synchronized(stateLock) { currentSessionId }
            var activeTurnId = localTurnId?.trim().orEmpty()
            try {
                provider.stream(request).collect { event ->
                    collectAssistantOutput(event, assistantBuffer)
                    EngineEventBridge.map(event, request.requestId)?.let { unified ->
                        val persistResult = persistUnifiedEvent(
                            sessionId = sessionId,
                            activeTurnId = activeTurnId,
                            event = unified,
                            assistantText = assistantBuffer.toString(),
                        )
                        activeTurnId = persistResult.turnId
                        if (persistResult.createdAssistantMessage ) {
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
                        onUnifiedEvent(unified)
                    }
                    when (event) {
                        is EngineEvent.SessionReady -> {
                            if (engineId == CodexProviderFactory.ENGINE_ID) {
                                logCodexChain(
                                    "Codex chain session ready: sessionId=${sessionIdForLog()} cliSessionId=${event.sessionId}",
                                )
                            }
                            updateCurrentCliSessionId(event.sessionId)
                        }
                        is EngineEvent.TurnUsage -> {
                            updateCurrentUsageSnapshot(
                                model = resolvedModel ?: model,
                                usage = event,
                                engineId = engineId,
                            )
                        }
                        else -> Unit
                    }
                    assembler.accept(event).forEach { action ->
                        runtime.accept(action)
                    }
                }
                runtime.awaitIdle()
                onTurnPersisted()
            } finally {
                synchronized(stateLock) {
                    if (currentRequestId == request.requestId) {
                        currentRequestId = null
                        currentJob = null
                    }
                }
            }
        }
        synchronized(stateLock) {
            currentRequestId = request.requestId
            currentJob = job
        }
    }

    fun cancelCurrent() {
        val (job, requestId) = synchronized(stateLock) {
            val activeJob = currentJob
            val activeRequestId = currentRequestId
            currentJob = null
            currentRequestId = null
            activeJob to activeRequestId
        }
        job?.cancel()
        requestId?.let { id ->
            runCatching {
                registry.cancel(id)
            }
        }
    }

    fun availableEngines(): List<EngineDescriptor> = registry.engines()

    fun defaultEngineId(): String = registry.defaultEngineId()

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
        cancelCurrent()
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
        event: EngineEvent,
        assistantBuffer: StringBuilder,
    ) {
        when (event) {
            is EngineEvent.AssistantTextDelta -> assistantBuffer.append(event.text)
            is EngineEvent.NarrativeItem -> {
                if (!event.isUser && !event.isThinking) {
                    assistantBuffer.append(event.text)
                }
            }
            else -> Unit
        }
    }

    private fun persistUnifiedEvent(
        sessionId: String,
        activeTurnId: String,
        event: UnifiedEvent,
        assistantText: String,
    ): PersistResult {
        var nextTurnId = activeTurnId
        var createdAssistantMessage = false
        when (event) {
            is UnifiedEvent.ThreadStarted -> Unit
            is UnifiedEvent.TurnStarted -> {
                val incoming = event.turnId.trim()
                if (incoming.isNotBlank()) {
                    if (nextTurnId.isNotBlank() && nextTurnId != incoming) {
                        repository.replaceTurnId(
                            sessionId = sessionId,
                            fromTurnId = nextTurnId,
                            toTurnId = incoming,
                        )
                    }
                    nextTurnId = incoming
                }
            }

            is UnifiedEvent.ItemUpdated -> {
                val turnId = nextTurnId
                val item = event.item
                if (item.kind == ItemKind.NARRATIVE) {
                    val role = narrativeRole(item)
                    val body = when {
                        role == MessageRole.ASSISTANT && assistantText.isNotBlank() -> assistantText
                        else -> item.text.orEmpty()
                    }
                    if (body.isNotBlank()) {
                        val existingAssistant = role == MessageRole.ASSISTANT &&
                            item.status == ItemStatus.RUNNING &&
                            assistantText.isNotBlank()
                        repository.upsertMessageRecord(
                            sessionId = sessionId,
                            turnId = turnId,
                            sourceId = item.id,
                            role = role,
                            body = body,
                            status = item.status,
                            timestamp = System.currentTimeMillis(),
                        )
                        createdAssistantMessage = existingAssistant
                    }
                } else {
                    repository.upsertActivityRecord(
                        sessionId = sessionId,
                        turnId = turnId,
                        sourceId = item.id,
                        kind = item.kind.toPersistedActivityKind(),
                        title = item.activityTitle(),
                        body = item.activityBody(),
                        status = item.status,
                        timestamp = System.currentTimeMillis(),
                    )
                }
            }

            is UnifiedEvent.TurnCompleted -> {
                val targetTurnId = event.turnId.takeIf { it.isNotBlank() } ?: nextTurnId
                if (targetTurnId.isNotBlank()) {
                    repository.markTurnRecordsStatus(
                        sessionId = sessionId,
                        turnId = targetTurnId,
                        fromStatus = ItemStatus.RUNNING,
                        toStatus = event.outcome.toItemStatus(),
                    )
                }
                if (event.turnId.isNotBlank()) {
                    nextTurnId = event.turnId
                }
            }

            is UnifiedEvent.Error -> {
                if (nextTurnId.isNotBlank()) {
                    repository.markTurnRecordsStatus(
                        sessionId = sessionId,
                        turnId = nextTurnId,
                        fromStatus = ItemStatus.RUNNING,
                        toStatus = ItemStatus.FAILED,
                    )
                }
            }
        }
        return PersistResult(
            turnId = nextTurnId,
            createdAssistantMessage = createdAssistantMessage,
        )
    }

    private fun currentCliSessionId(): String? = synchronized(stateLock) {
        sessions[currentSessionId]?.cliSessionId?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun updateCurrentCliSessionId(cliSessionId: String) {
        val trimmed = cliSessionId.trim()
        if (trimmed.isBlank()) return
        val sessionId = synchronized(stateLock) {
            val sessionId = currentSessionId
            sessions[sessionId]?.cliSessionId = trimmed
            sessions[sessionId]?.updatedAt = System.currentTimeMillis()
            sessionId
        }
        logCodexChain("Codex chain stored cli session: sessionId=${sessionIdForLog()} cliSessionId=$trimmed")
        persistSessionSnapshot(sessionId)
    }

    private fun updateCurrentUsageSnapshot(
        model: String?,
        usage: EngineEvent.TurnUsage,
        engineId: String,
    ) {
        val snapshot = TurnUsageSnapshot(
            model = model?.trim().orEmpty(),
            contextWindow = ModelContextWindows.resolve(model),
            inputTokens = usage.inputTokens,
            cachedInputTokens = usage.cachedInputTokens,
            outputTokens = usage.outputTokens,
        )
        val sessionId = synchronized(stateLock) {
            val sessionId = currentSessionId
            sessions[sessionId]?.usageSnapshot = snapshot
            sessions[sessionId]?.updatedAt = snapshot.capturedAt
            sessionId
        }
        if (engineId == CodexProviderFactory.ENGINE_ID) {
            logCodexChain(
                "Codex chain usage captured: sessionId=${sessionIdForLog()} " +
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

    private fun sessionIdForLog(): String = synchronized(stateLock) { currentSessionId.ifBlank { "<none>" } }

    private fun resolveModel(engineId: String, selectedModel: String): String? {
        val trimmed = selectedModel.trim()
        return if (engineId == "codex") trimmed.ifBlank { null } else trimmed.ifBlank { null }
    }

    private fun PersistedChatSession.toSessionData(): SessionData {
        return SessionData(
            id = id,
            createdAt = createdAt,
            title = title.trim(),
            updatedAt = updatedAt,
            messageCount = messageCount,
            cliSessionId = remoteThreadId,
            usageSnapshot = usageSnapshot,
        )
    }

    private fun SessionData.toPersisted(isActive: Boolean): PersistedChatSession {
        return PersistedChatSession(
            id = id,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            messageCount = messageCount,
            remoteThreadId = cliSessionId,
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
                .sorted(Comparator.reverseOrder())
                .forEach { path -> Files.deleteIfExists(path) }
        }
    }

    private data class PersistResult(
        val turnId: String,
        val createdAssistantMessage: Boolean,
    )

    private fun narrativeRole(item: UnifiedItem): MessageRole {
        return when (item.name) {
            "user_message" -> MessageRole.USER
            "system_message" -> MessageRole.SYSTEM
            else -> MessageRole.ASSISTANT
        }
    }

    private fun UnifiedItem.activityTitle(): String {
        return when (kind.toPersistedActivityKind()) {
            PersistedActivityKind.TOOL -> ActivityTitleFormatter.toolTitle(
                explicitName = name,
                body = activityBody(),
            )
            PersistedActivityKind.COMMAND -> ActivityTitleFormatter.commandTitle(
                explicitName = name,
                command = command,
                body = activityBody(),
            )
            PersistedActivityKind.DIFF -> ActivityTitleFormatter.fileChangeTitle(
                explicitName = name,
                changes = fileChanges.map { change ->
                    ActivityTitleFormatter.FileChangeSummary(
                        path = change.path,
                        kind = change.kind,
                    )
                },
                body = activityBody(),
            )
            PersistedActivityKind.APPROVAL -> "Approval"
            PersistedActivityKind.PLAN -> "Plan Update"
            PersistedActivityKind.UNKNOWN -> "Activity"
        }
    }

    private fun UnifiedItem.activityBody(): String {
        return listOfNotNull(
            text?.takeIf { it.isNotBlank() },
            command?.takeIf { it.isNotBlank() },
            filePath?.takeIf { it.isNotBlank() },
        ).joinToString("\n").ifBlank { id }
    }

    private fun ItemKind.toPersistedActivityKind(): PersistedActivityKind {
        return when (this) {
            ItemKind.TOOL_CALL -> PersistedActivityKind.TOOL
            ItemKind.COMMAND_EXEC -> PersistedActivityKind.COMMAND
            ItemKind.DIFF_APPLY -> PersistedActivityKind.DIFF
            ItemKind.APPROVAL_REQUEST -> PersistedActivityKind.APPROVAL
            ItemKind.PLAN_UPDATE -> PersistedActivityKind.PLAN
            ItemKind.NARRATIVE,
            ItemKind.UNKNOWN,
            -> PersistedActivityKind.UNKNOWN
        }
    }

    private fun com.codex.assistant.protocol.TurnOutcome.toItemStatus(): ItemStatus {
        return when (this) {
            com.codex.assistant.protocol.TurnOutcome.SUCCESS -> ItemStatus.SUCCESS
            com.codex.assistant.protocol.TurnOutcome.CANCELLED,
            com.codex.assistant.protocol.TurnOutcome.FAILED,
            -> ItemStatus.FAILED

            com.codex.assistant.protocol.TurnOutcome.RUNNING -> ItemStatus.RUNNING
        }
    }
}
