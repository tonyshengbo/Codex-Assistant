package com.codex.assistant.service

import com.codex.assistant.model.AgentAction
import com.codex.assistant.model.EngineEvent
import com.codex.assistant.model.AgentRequest
import com.codex.assistant.model.ChatMessage
import com.codex.assistant.model.ContextFile
import com.codex.assistant.model.MessageRole
import com.codex.assistant.model.TurnUsageSnapshot
import com.codex.assistant.model.TimelineAction
import com.codex.assistant.provider.CodexProviderFactory
import com.codex.assistant.provider.ProviderRegistry
import com.codex.assistant.provider.EngineDescriptor
import com.codex.assistant.settings.AgentSettingsService
import com.codex.assistant.timeline.TimelineActionAssembler
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Service(Service.Level.PROJECT)
class AgentChatService private constructor(
    private val project: Project?,
    private val sessionStore: ProjectSessionStore,
    private val settings: AgentSettingsService,
    private val registry: ProviderRegistry,
    private val workingDirectoryProvider: () -> String,
    private val diagnosticLogger: (String) -> Unit,
) : Disposable {
    constructor(project: Project) : this(
        project = project,
        sessionStore = project.getService(ProjectSessionStore::class.java),
        settings = AgentSettingsService.getInstance(),
        registry = ProviderRegistry(AgentSettingsService.getInstance()),
        workingDirectoryProvider = { project.basePath ?: "." },
        diagnosticLogger = { message -> LOG.info(message) },
    )

    internal constructor(
        sessionStore: ProjectSessionStore,
        registry: ProviderRegistry,
        settings: AgentSettingsService,
        workingDirectoryProvider: () -> String = { "." },
        diagnosticLogger: (String) -> Unit = { message -> LOG.info(message) },
    ) : this(
        project = null,
        sessionStore = sessionStore,
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
        var title: String,
        var updatedAt: Long,
        val messages: MutableList<ChatMessage>,
        var cliSessionId: String,
        var usageSnapshot: TurnUsageSnapshot? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentJob: Job? = null
    private var currentRequestId: String? = null

    private val sessions = linkedMapOf<String, SessionData>()
    private var currentSessionId: String = ""

    companion object {
        private val LOG = Logger.getInstance(AgentChatService::class.java)
    }

    val messages = mutableListOf<ChatMessage>()

    init {
        loadFromStore()
    }

    fun getCurrentSessionId(): String = currentSessionId

    fun currentSessionTitle(): String {
        return sessions[currentSessionId]?.title ?: "New Session"
    }

    fun isCurrentSessionEmpty(): Boolean = messages.isEmpty()

    fun currentUsageSnapshot(): TurnUsageSnapshot? = sessions[currentSessionId]?.usageSnapshot

    fun listSessions(): List<SessionSummary> {
        return sessions.values
            .sortedByDescending { it.updatedAt }
            .map {
                SessionSummary(
                    id = it.id,
                    title = it.title,
                    updatedAt = it.updatedAt,
                    messageCount = it.messages.size,
                )
            }
    }

    fun createSession(): String {
        saveCurrentSession()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        sessions[id] = SessionData(
            id = id,
            title = "New Session",
            updatedAt = now,
            messages = mutableListOf(),
            cliSessionId = "",
            usageSnapshot = null,
        )
        currentSessionId = id
        messages.clear()
        saveAllToStore()
        return id
    }

    fun switchSession(sessionId: String): Boolean {
        val target = sessions[sessionId] ?: return false
        saveCurrentSession()
        currentSessionId = sessionId
        messages.clear()
        messages.addAll(target.messages)
        saveAllToStore()
        return true
    }

    fun deleteSession(sessionId: String): Boolean {
        if (!sessions.containsKey(sessionId)) {
            return false
        }
        sessions.remove(sessionId)
        if (sessions.isEmpty()) {
            createSession()
            return true
        }
        if (currentSessionId == sessionId) {
            val fallback = sessions.values.maxByOrNull { it.updatedAt } ?: sessions.values.first()
            currentSessionId = fallback.id
            messages.clear()
            messages.addAll(fallback.messages)
        }
        saveAllToStore()
        return true
    }

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        syncCurrentSessionFromMessages()
        saveAllToStore()
    }

    fun clearMessages() {
        val previousCliSessionId = currentCliSessionId()
        messages.clear()
        sessions[currentSessionId]?.apply {
            cliSessionId = ""
            usageSnapshot = null
        }
        previousCliSessionId?.let {
            logCodexChain("Codex chain reset: sessionId=${sessionIdForLog()} reason=clearMessages cliSessionId=$it")
        }
        syncCurrentSessionFromMessages()
        saveAllToStore()
    }

    fun runAgent(
        engineId: String,
        model: String,
        prompt: String,
        contextFiles: List<ContextFile>,
        onAction: (TimelineAction) -> Unit,
    ) {
        cancelCurrent()
        val resolvedModel = resolveModel(engineId, model)
        val cliSessionId = currentCliSessionId()
        val request = AgentRequest(
            engineId = engineId,
            action = AgentAction.CHAT,
            model = resolvedModel,
            prompt = prompt,
            contextFiles = contextFiles,
            workingDirectory = workingDirectoryProvider(),
            cliSessionId = cliSessionId,
        )
        if (engineId == CodexProviderFactory.ENGINE_ID) {
            logCodexChain(
                "Codex chain request: sessionId=${sessionIdForLog()} requestId=${request.requestId} " +
                    "mode=${if (cliSessionId == null) "exec" else "resume"} " +
                    "model=${resolvedModel ?: "<auto>"} " +
                    "cliSessionId=${cliSessionId ?: "<none>"} contextFiles=${contextFiles.size}",
            )
        }
        currentRequestId = request.requestId
        val provider = registry.providerOrDefault(engineId)

        currentJob = scope.launch {
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
            provider.stream(request).collect { event ->
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
        }
    }

    fun cancelCurrent() {
        currentJob?.cancel()
        currentRequestId?.let { id ->
            runCatching {
                registry.cancel(id)
            }
        }
        currentJob = null
        currentRequestId = null
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
        saveCurrentSession()
        saveAllToStore()
        scope.cancel()
    }

    private fun loadFromStore() {
        val persisted = sessionStore.readState()
        if (persisted.sessions.isEmpty()) {
            createSession()
            return
        }

        persisted.sessions.forEach { s ->
            sessions[s.id] = SessionData(
                id = s.id,
                title = s.title.ifBlank { "New Session" },
                updatedAt = s.updatedAt,
                messages = s.messages.map {
                    ChatMessage(
                        id = it.id,
                        role = MessageRole.valueOf(it.role),
                        content = it.content,
                        timestamp = it.timestamp,
                        timelineActionsPayload = it.timelineActionsPayload,
                    )
                }.toMutableList(),
                cliSessionId = s.cliSessionId.ifBlank { s.continuationResponseId },
                usageSnapshot = s.usageSnapshot?.toModel(),
            )
        }

        currentSessionId = if (sessions.containsKey(persisted.currentSessionId)) {
            persisted.currentSessionId
        } else {
            sessions.values.maxByOrNull { it.updatedAt }?.id ?: sessions.keys.first()
        }

        messages.clear()
        messages.addAll(sessions[currentSessionId]?.messages.orEmpty())
    }

    private fun saveCurrentSession() {
        syncCurrentSessionFromMessages()
    }

    private fun syncCurrentSessionFromMessages() {
        val current = sessions[currentSessionId] ?: return
        current.messages.clear()
        current.messages.addAll(messages.takeLast(300))
        current.updatedAt = System.currentTimeMillis()
        current.title = deriveTitle(current.messages)
    }

    private fun deriveTitle(messages: List<ChatMessage>): String {
        val firstUser = messages.firstOrNull { it.role == MessageRole.USER }?.content?.trim().orEmpty()
        return if (firstUser.isBlank()) "New Session" else firstUser.take(36)
    }

    private fun saveAllToStore() {
        val sessionsState = sessions.values.map { s ->
            ProjectSessionStore.SessionState(
                id = s.id,
                title = s.title,
                updatedAt = s.updatedAt,
                cliSessionId = s.cliSessionId,
                usageSnapshot = s.usageSnapshot?.toState(),
                continuationResponseId = "",
                messages = s.messages.takeLast(300).map {
                    ProjectSessionStore.MessageState(
                        id = it.id,
                        role = it.role.name,
                        content = it.content,
                        timestamp = it.timestamp,
                        timelineActionsPayload = it.timelineActionsPayload,
                    )
                }.toMutableList(),
            )
        }.toMutableList()

        sessionStore.saveState(
            ProjectSessionStore.State(
                currentSessionId = currentSessionId,
                sessions = sessionsState,
            ),
        )
    }

    private fun currentCliSessionId(): String? {
        return sessions[currentSessionId]?.cliSessionId?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun updateCurrentCliSessionId(cliSessionId: String) {
        val trimmed = cliSessionId.trim()
        if (trimmed.isBlank()) return
        sessions[currentSessionId]?.cliSessionId = trimmed
        logCodexChain("Codex chain stored cli session: sessionId=${sessionIdForLog()} cliSessionId=$trimmed")
        saveAllToStore()
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
        sessions[currentSessionId]?.usageSnapshot = snapshot
        if (engineId == CodexProviderFactory.ENGINE_ID) {
            logCodexChain(
                "Codex chain usage captured: sessionId=${sessionIdForLog()} " +
                    "model=${snapshot.model.ifBlank { "<unknown>" }} contextWindow=${snapshot.contextWindow} " +
                    "inputTokens=${snapshot.inputTokens} cachedInputTokens=${snapshot.cachedInputTokens} " +
                    "outputTokens=${snapshot.outputTokens}",
            )
        }
        saveAllToStore()
    }

    private fun logCodexChain(message: String) {
        diagnosticLogger(message)
    }

    private fun sessionIdForLog(): String = currentSessionId.ifBlank { "<none>" }

    private fun resolveModel(engineId: String, selectedModel: String): String? {
        val trimmed = selectedModel.trim()
        return if (engineId == "codex") trimmed.ifBlank { null } else trimmed.ifBlank { null }
    }

    private fun ProjectSessionStore.UsageSnapshotState.toModel(): TurnUsageSnapshot {
        return TurnUsageSnapshot(
            model = model,
            contextWindow = contextWindow,
            inputTokens = inputTokens,
            cachedInputTokens = cachedInputTokens,
            outputTokens = outputTokens,
            capturedAt = capturedAt,
        )
    }

    private fun TurnUsageSnapshot.toState(): ProjectSessionStore.UsageSnapshotState {
        return ProjectSessionStore.UsageSnapshotState(
            model = model,
            contextWindow = contextWindow,
            inputTokens = inputTokens,
            cachedInputTokens = cachedInputTokens,
            outputTokens = outputTokens,
            capturedAt = capturedAt,
        )
    }
}
