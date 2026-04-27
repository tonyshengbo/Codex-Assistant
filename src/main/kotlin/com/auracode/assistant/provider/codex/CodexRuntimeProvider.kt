package com.auracode.assistant.provider.codex

import com.auracode.assistant.conversation.ConversationCapabilities
import com.auracode.assistant.conversation.ConversationHistoryPage
import com.auracode.assistant.conversation.ConversationRef
import com.auracode.assistant.conversation.ConversationSummary
import com.auracode.assistant.conversation.ConversationSummaryPage
import com.auracode.assistant.diff.FileChangeMetrics
import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.CodexProviderFactory
import com.auracode.assistant.provider.session.ProviderProtocolDomainMapper
import com.auracode.assistant.provider.codex.protocol.CodexMcpToolContentFormatter
import com.auracode.assistant.protocol.ApprovalDecision
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.ProviderApprovalRequest
import com.auracode.assistant.protocol.ProviderApprovalRequestKind
import com.auracode.assistant.protocol.ProviderAgentSnapshot
import com.auracode.assistant.protocol.ProviderAgentStatus
import com.auracode.assistant.protocol.TurnUsage
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderFileChange
import com.auracode.assistant.protocol.ProviderItem
import com.auracode.assistant.protocol.ProviderPlanStep
import com.auracode.assistant.protocol.ProviderRunningPlanPresentation
import com.auracode.assistant.protocol.ProviderToolUserInputAnswerDraft
import com.auracode.assistant.protocol.ProviderToolUserInputOption
import com.auracode.assistant.protocol.ProviderToolUserInputPrompt
import com.auracode.assistant.protocol.ProviderToolUserInputQuestion
import com.auracode.assistant.protocol.ProviderToolUserInputSubmission
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.toolwindow.execution.ApprovalAction
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Streams Codex app-server events and exposes Codex-specific provider capabilities. */
internal class CodexRuntimeProvider(
    private val settings: AgentSettingsService,
    private val environmentDetector: CodexEnvironmentDetector = CodexEnvironmentDetector(),
    private val diagnosticLogger: (String) -> Unit = { message -> LOG.info(message) },
) : AgentProvider {
    private val running = ConcurrentHashMap<String, ActiveRequest>()
    override val providerId: String = CodexProviderFactory.ENGINE_ID
    private val historyLoader = CodexConversationReplayLoader(
        settings = settings,
        environmentDetector = environmentDetector,
        diagnosticLogger = diagnosticLogger,
        providerId = providerId,
    )

    override fun stream(request: AgentRequest): Flow<SessionDomainEvent> = callbackFlow {
        val resolution = environmentDetector.resolveForLaunch(
            configuredCodexPath = settings.getState().executablePathFor(CodexProviderFactory.ENGINE_ID),
            configuredNodePath = settings.nodeExecutablePath(),
        )
        if (resolution.codexStatus == CodexEnvironmentStatus.FAILED) {
            trySend(
                SessionDomainEvent.ErrorAppended(
                    itemId = "codex-error:${request.requestId}:runtime",
                    turnId = null,
                    message = "Configured Codex Runtime Path is not executable. Update Settings and try again.",
                ),
            )
            close()
            return@callbackFlow
        }
        if (resolution.codexStatus == CodexEnvironmentStatus.MISSING) {
            trySend(
                SessionDomainEvent.ErrorAppended(
                    itemId = "codex-error:${request.requestId}:missing-runtime",
                    turnId = null,
                    message = "Aura Code runtime path is not configured.",
                ),
            )
            close()
            return@callbackFlow
        }
        val binary = resolution.codexPath.trim()
        if (settings.nodeExecutablePath().isNotBlank() && resolution.nodeStatus == CodexEnvironmentStatus.FAILED) {
            trySend(
                SessionDomainEvent.ErrorAppended(
                    itemId = "codex-error:${request.requestId}:node-runtime",
                    turnId = null,
                    message = "Configured Node Path is not executable. Update Settings and try again.",
                ),
            )
            close()
            return@callbackFlow
        }

        val process = try {
            createCodexRuntimeProcess(
                binary = binary,
                environmentOverrides = resolution.environmentOverrides,
                workingDirectory = File(request.workingDirectory),
            )
        } catch (t: Throwable) {
            trySend(
                SessionDomainEvent.ErrorAppended(
                    itemId = "codex-error:${request.requestId}:launch",
                    turnId = null,
                    message = "Failed to start Aura Code app-server: ${t.message}",
                ),
            )
            close()
            return@callbackFlow
        }

        val active = ActiveRequest(process = process)
        running[request.requestId] = active

        launch(Dispatchers.IO) {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        active.lastStderrLine = line
                        diagnosticLogger("Codex app-server stderr: ${line.take(4000)}")
                    }
                }
            }
        }

        launch(Dispatchers.IO) {
            lateinit var bridge: CodexRuntimeConversationRouter
            val sessionEventMapper = ProviderProtocolDomainMapper()
            val session = CodexProcessRuntimeSession(
                process = process,
                diagnosticLogger = diagnosticLogger,
                onNotification = { method, params -> bridge.handleNotification(method, params) },
                onServerRequest = { id, method, params -> bridge.handleServerRequest(id, method, params) },
            )
            val client = CodexRuntimeClient(
                session = session,
                diagnosticLogger = diagnosticLogger,
            )
            bridge = CodexRuntimeConversationRouter(
                request = request,
                active = active,
                session = session,
                client = client,
                diagnosticLogger = diagnosticLogger,
                emitProviderEvent = { event ->
                    sessionEventMapper.map(event).forEach { domainEvent ->
                        trySend(domainEvent)
                    }
                    if (shouldCompleteActiveTurn(active.turnId, event)) {
                        active.turnCompleted.complete(Unit)
                    }
                },
            )
            try {
                session.start()
                runAppServerStartupStep("initialize", active, process) { bridge.initialize() }
                val threadId = runAppServerStartupStep("thread/start", active, process) { bridge.ensureThread() }
                if (request.remoteConversationId?.isBlank() != false) {
                    // thread/start already emits thread/started; only synthesize on resume path.
                } else {
                    sessionEventMapper.map(ProviderEvent.ThreadStarted(threadId = threadId)).forEach { domainEvent ->
                        trySend(domainEvent)
                    }
                }
                runAppServerStartupStep("turn/start", active, process) { bridge.startTurn(threadId) }
                launchSilentExitCompletionWatcher(
                    process = process,
                    active = active,
                    emitProviderEvent = { event ->
                        sessionEventMapper.map(event).forEach { domainEvent ->
                            trySend(domainEvent)
                        }
                        if (shouldCompleteActiveTurn(active.turnId, event)) {
                            active.turnCompleted.complete(Unit)
                        }
                    },
                )
                active.turnCompleted.await()
            } catch (t: Throwable) {
                diagnosticLogger("Codex app-server request failed: requestId=${request.requestId} message=${t.message}")
                trySend(
                    SessionDomainEvent.ErrorAppended(
                        itemId = "codex-error:${request.requestId}:request",
                        turnId = active.turnId,
                        message = t.message ?: "Unknown app-server error",
                    ),
                )
            } finally {
                running.remove(request.requestId)
                process.destroy()
                close()
            }
        }

        awaitClose {
            running.remove(request.requestId)?.cancel()
        }
    }

    override suspend fun loadInitialHistory(ref: ConversationRef, pageSize: Int): ConversationHistoryPage {
        return historyLoader.loadInitialHistory(
            remoteConversationId = ref.remoteConversationId,
            pageSize = pageSize,
        )
    }

    override suspend fun loadOlderHistory(
        ref: ConversationRef,
        cursor: String,
        pageSize: Int,
    ): ConversationHistoryPage {
        return historyLoader.loadOlderHistory(
            remoteConversationId = ref.remoteConversationId,
            cursor = cursor,
            pageSize = pageSize,
        )
    }

    override suspend fun listRemoteConversations(
        pageSize: Int,
        cursor: String?,
        cwd: String?,
        searchTerm: String?,
    ): ConversationSummaryPage {
        return historyLoader.listRemoteConversations(
            pageSize = pageSize,
            cursor = cursor,
            cwd = cwd,
            searchTerm = searchTerm,
        )
    }

    override fun capabilities(): ConversationCapabilities {
        return ConversationCapabilities(
            supportsStructuredHistory = true,
            supportsHistoryPagination = true,
            supportsPlanMode = true,
            supportsApprovalRequests = true,
            supportsToolUserInput = true,
            supportsResume = true,
            supportsAttachments = true,
            supportsImageInputs = true,
            supportsSubagents = true,
        )
    }

    override fun cancel(requestId: String) {
        running.remove(requestId)?.cancel()
    }

    override fun submitApprovalDecision(requestId: String, decision: ApprovalAction): Boolean {
        return running.values.any { it.submitApprovalDecision(requestId, decision) }
    }

    override fun submitToolUserInput(
        requestId: String,
        answers: Map<String, ProviderToolUserInputAnswerDraft>,
    ): Boolean {
        return running.values.any { it.submitToolUserInput(requestId, answers) }
    }

    internal class CodexRuntimeNotificationParser(
        private val requestId: String,
        private val diagnosticLogger: (String) -> Unit,
    ) {
        /**
         * Caches collaboration tool-call metadata so late wait/status updates can reuse
         * the last known child-thread mapping even when the current payload omits it.
         */
        private data class CollabToolCallSnapshot(
            val tool: String?,
            val prompt: String?,
            val receiverThreadIds: List<String>,
            val agentStatesByThreadId: Map<String, JsonObject>,
        )

        private val narrativeBuffers = mutableMapOf<String, StringBuilder>()
        private val activityOutputBuffers = mutableMapOf<String, StringBuilder>()
        private val planBuffers = mutableMapOf<String, StringBuilder>()
        private val itemSnapshots = mutableMapOf<String, ProviderItem>()
        private val subagentSnapshotsByThreadId = linkedMapOf<String, ProviderAgentSnapshot>()
        private val closedSubagentThreadIds = linkedSetOf<String>()
        private val collabToolCallSnapshotsById = mutableMapOf<String, CollabToolCallSnapshot>()
        private val collabToolCallSnapshotsBySenderThreadId = mutableMapOf<String, CollabToolCallSnapshot>()
        private val contextCompactionItemIdsByTurnId = mutableMapOf<String, String>()
        private var parentTurnFailureEmitted = false
        private var activeTurnId: String? = null
        private var activeThreadId: String? = null

        fun parseNotification(method: String, params: JsonObject): List<ProviderEvent> {
            return when (method) {
                "thread/started" -> {
                    val threadId = params.objectValue("thread")?.string("id") ?: return emptyList()
                    activeThreadId = threadId
                    listOf(ProviderEvent.ThreadStarted(threadId = threadId))
                }

                "turn/started" -> {
                    val turn = params.objectValue("turn")
                    val turnId = turn?.string("id") ?: return emptyList()
                    activeTurnId = turnId
                    val threadId = params.string("threadId")
                        ?: turn.string("threadId")
                    activeThreadId = threadId ?: activeThreadId
                    listOf(ProviderEvent.TurnStarted(turnId = turnId, threadId = threadId))
                }

                "thread/tokenUsage/updated" -> {
                    val tokenUsage = params.objectValue("tokenUsage") ?: return emptyList()
                    val total = tokenUsage.objectValue("total") ?: return emptyList()
                    val threadId = params.string("threadId") ?: return emptyList()
                    listOf(
                        ProviderEvent.ThreadTokenUsageUpdated(
                            threadId = threadId,
                            turnId = params.string("turnId"),
                            contextWindow = tokenUsage.int("modelContextWindow", "model_context_window"),
                            inputTokens = total.int("inputTokens", "input_tokens"),
                            cachedInputTokens = total.int("cachedInputTokens", "cached_input_tokens"),
                            outputTokens = total.int("outputTokens", "output_tokens"),
                        ),
                    )
                }

                "turn/diff/updated" -> {
                    val threadId = params.string("threadId") ?: return emptyList()
                    val turnId = params.string("turnId") ?: return emptyList()
                    val diff = params.string("diff") ?: return emptyList()
                    listOf(
                        ProviderEvent.TurnDiffUpdated(
                            threadId = threadId,
                            turnId = turnId,
                            diff = diff,
                        ),
                    )
                }

                "error" -> {
                    val error = params.objectValue("error")
                    val message = error?.string("message")
                        ?: params.string("message")
                        ?: return emptyList()
                    val willRetry = params["willRetry"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                    listOf(
                        // Retryable transport errors should still surface as toasts, but they must
                        // not tear down the active turn because the app-server is about to retry.
                        ProviderEvent.Error(
                            message = message,
                            terminal = !willRetry,
                        ),
                    )
                }

                "thread/compacted" -> parseThreadCompacted(params)

                "thread/status/changed" -> parseThreadStatusChanged(params)

                "turn/completed" -> {
                    val turn = params.objectValue("turn")
                    val turnId = turn?.string("id").orEmpty()
                    val status = turn?.string("status") ?: params.string("status")
                    val outcome = when (status?.lowercase()) {
                        "completed", "success" -> TurnOutcome.SUCCESS
                        "interrupted" -> TurnOutcome.CANCELLED
                        "failed", "error" -> TurnOutcome.FAILED
                        else -> TurnOutcome.SUCCESS
                    }
                    val usageObject = params.objectValue("usage")
                    val usage = usageObject?.let {
                        TurnUsage(
                            inputTokens = it.int("inputTokens", "input_tokens"),
                            cachedInputTokens = it.int("cachedInputTokens", "cached_input_tokens"),
                            outputTokens = it.int("outputTokens", "output_tokens"),
                        )
                    }
                    val threadId = params.string("threadId")
                    if (threadId != null && isTrackedSubagentThread(threadId) && activeTurnId != turnId) {
                        parseChildTurnCompleted(
                            threadId = threadId,
                            outcome = outcome,
                            rawStatus = status,
                        )
                    } else {
                        listOf(ProviderEvent.TurnCompleted(turnId = turnId, outcome = outcome, usage = usage))
                    }
                }

                "item/started",
                "item/completed",
                -> {
                    val item = params.objectValue("item") ?: return emptyList()
                    parseItemLifecycle(
                        item = item,
                        method = method,
                        includeUserMessages = false,
                    )
                }

                "item/agentMessage/delta" -> parseNarrativeDelta(
                    params = params,
                    itemName = "message",
                    isThinking = false,
                )

                "item/reasoning/textDelta",
                "item/reasoning/summaryTextDelta",
                -> parseNarrativeDelta(
                    params = params,
                    itemName = "reasoning",
                    isThinking = true,
                )

                "item/commandExecution/outputDelta" -> parseActivityDelta(
                    params = params,
                    kind = ItemKind.COMMAND_EXEC,
                )

                "item/fileChange/outputDelta" -> parseActivityDelta(
                    params = params,
                    kind = ItemKind.DIFF_APPLY,
                )

                "item/plan/delta" -> parsePlanDelta(params)

                "turn/plan/updated" -> {
                    val turnId = params.string("turnId").orEmpty()
                    val explanation = params.string("explanation").orEmpty()
                    val steps = params.arrayValue("plan")
                        ?.mapNotNull { step ->
                            val value = step as? JsonObject ?: return@mapNotNull null
                            val text = value.string("step") ?: return@mapNotNull null
                            ProviderPlanStep(
                                step = text,
                                status = value.string("status").orEmpty(),
                            )
                        }
                        .orEmpty()
                    val planText = steps.joinToString("\n") { step ->
                        "- [${step.status}] ${step.step}"
                    }
                    val body = listOf(explanation.takeIf { it.isNotBlank() }, planText.takeIf { it.isNotBlank() })
                        .joinToString("\n\n")
                        .ifBlank { "Plan updated" }
                    val planId = planItemId(turnId)
                    planBuffers[planId] = StringBuilder(body)
                    listOf(
                        ProviderEvent.RunningPlanUpdated(
                            threadId = activeThreadId,
                            turnId = turnId,
                            explanation = explanation.takeIf { it.isNotBlank() },
                            steps = steps,
                            body = body,
                            presentation = ProviderRunningPlanPresentation.SUBMISSION_PANEL,
                        ),
                    )
                }

                "item/commandExecution/requestApproval",
                "item/fileChange/requestApproval",
                -> {
                    val sourceId = params.string("itemId") ?: scopedId("approval")
                    val body = listOfNotNull(
                        params.string("reason"),
                        params.string("command"),
                        params.string("cwd"),
                    ).joinToString("\n").ifBlank { method }
                    listOf(
                        ProviderEvent.ItemUpdated(
                            ProviderItem(
                                id = scopedId(sourceId),
                                kind = ItemKind.APPROVAL_REQUEST,
                                status = ItemStatus.RUNNING,
                                name = "Approval",
                                text = body,
                                approvalDecision = ApprovalDecision.PENDING,
                            ),
                        ),
                    )
                }

                else -> emptyList()
            }
        }

        fun parseHistoricalTurn(turn: JsonObject): List<ProviderEvent> {
            val turnId = turn.string("id").orEmpty()
            val threadId = turn.string("threadId") ?: turn.string("thread_id")
            activeTurnId = turnId.takeIf { it.isNotBlank() }
            activeThreadId = threadId ?: activeThreadId
            val turnEvents = mutableListOf<ProviderEvent>()
            if (turnId.isNotBlank()) {
                turnEvents += ProviderEvent.TurnStarted(turnId = turnId, threadId = threadId)
            }
            turn.arrayValue("items")
                ?.mapNotNull { it as? JsonObject }
                ?.forEach { item ->
                    turnEvents += parseHistoricalItem(item)
                }
            val status = turn.string("status")
            val usageObject = turn.objectValue("usage")
            turnEvents += ProviderEvent.TurnCompleted(
                turnId = turnId,
                outcome = when (status?.lowercase()) {
                    "interrupted" -> TurnOutcome.CANCELLED
                    "failed", "error" -> TurnOutcome.FAILED
                    else -> TurnOutcome.SUCCESS
                },
                usage = usageObject?.let {
                    TurnUsage(
                        inputTokens = it.int("inputTokens", "input_tokens"),
                        cachedInputTokens = it.int("cachedInputTokens", "cached_input_tokens"),
                        outputTokens = it.int("outputTokens", "output_tokens"),
                    )
                },
            )
            return turnEvents
        }

        private fun parseHistoricalItem(item: JsonObject): List<ProviderEvent> {
            return parseItemLifecycle(
                item = item,
                method = "item/completed",
                includeUserMessages = true,
            )
        }

        private fun parseItemLifecycle(
            item: JsonObject,
            method: String,
            includeUserMessages: Boolean,
        ): List<ProviderEvent> {
            val status = if (method == "item/completed") {
                parseStatus(item.string("status"), ItemStatus.SUCCESS)
            } else {
                parseStatus(item.string("status"), ItemStatus.RUNNING)
            }
            val normalizedType = item.string("type").orEmpty().lowercase()
            val sourceId = if (normalizedType == "plan") {
                planItemId(activeTurnId)
            } else {
                scopedId(item.string("id") ?: normalizedType.ifBlank { "item" })
            }
            if (!includeUserMessages && isUserMessageType(normalizedType)) {
                return emptyList()
            }
            val result = when {
                normalizedType.contains("message") -> ProviderItem(
                    id = sourceId,
                    kind = ItemKind.NARRATIVE,
                    status = status,
                    name = if (normalizedType.contains("user")) "user_message" else "message",
                    text = extractText(item),
                )

                normalizedType.contains("reasoning") -> ProviderItem(
                    id = sourceId,
                    kind = ItemKind.NARRATIVE,
                    status = status,
                    name = "reasoning",
                    text = extractText(item),
                )

                isWebSearchType(normalizedType) -> ProviderItem(
                    id = sourceId,
                    kind = ItemKind.TOOL_CALL,
                    status = status,
                    name = "web_search",
                    text = extractWebSearchText(item),
                )

                isMcpToolCallType(normalizedType) -> ProviderItem(
                    id = sourceId,
                    kind = ItemKind.TOOL_CALL,
                    status = status,
                    name = item.string("server")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "mcp:$it" }
                        ?: "mcp",
                    text = CodexMcpToolContentFormatter.formatBody(item),
                )

                normalizedType == "collabagenttoolcall" -> {
                    rememberCollabToolCallSnapshot(item)
                    ProviderItem(
                        id = sourceId,
                        kind = ItemKind.TOOL_CALL,
                        status = status,
                        name = collabToolCallDisplayName(collabToolCallSnapshot(item)?.tool),
                        text = formatCollabAgentToolCallBody(item),
                    )
                }

                normalizedType.contains("commandexecution") -> {
                    val previousOutput = activityOutputBuffers[sourceId]?.toString().orEmpty()
                    ProviderItem(
                        id = sourceId,
                        kind = ItemKind.COMMAND_EXEC,
                        status = status,
                        name = item.string("command") ?: "Exec Command",
                        text = item.string("aggregatedOutput") ?: item.string("aggregated_output") ?: previousOutput,
                        command = item.string("command"),
                        cwd = item.string("cwd"),
                        exitCode = item.intOrNull("exitCode", "exit_code"),
                    )
                }

                normalizedType.contains("filechange") -> {
                    val previousChanges = itemSnapshots[sourceId]?.fileChanges.orEmpty()
                    val changes = extractFileChanges(item = item, sourceId = sourceId)
                        .ifEmpty { previousChanges }
                    ProviderItem(
                        id = sourceId,
                        kind = ItemKind.DIFF_APPLY,
                        status = status,
                        name = "File Changes",
                        text = extractFileChangeSummary(changes, item),
                        fileChanges = changes,
                    )
                }

                normalizedType.contains("contextcompaction") -> {
                    val runningTurnId = activeTurnId?.takeIf { it.isNotBlank() }
                    if (runningTurnId != null) {
                        contextCompactionItemIdsByTurnId[runningTurnId] = sourceId
                    }
                    ProviderItem(
                        id = sourceId,
                        kind = ItemKind.CONTEXT_COMPACTION,
                        status = status,
                        name = "Context Compaction",
                        text = contextCompactionText(status),
                    )
                }

                normalizedType == "plan" -> ProviderItem(
                    id = sourceId,
                    kind = ItemKind.PLAN_UPDATE,
                    status = status,
                    name = "Plan Update",
                    text = extractText(item),
                )

                normalizedType.contains("requestuserinput") || normalizedType.contains("userinput") -> ProviderItem(
                    id = sourceId,
                    kind = ItemKind.USER_INPUT,
                    status = status,
                    name = "User Input",
                    text = extractUserInputText(item),
                    toolUserInputPrompt = extractHistoricalToolUserInputPrompt(
                        sourceId = sourceId,
                        item = item,
                        status = status,
                    ),
                )

                else -> ProviderItem(
                    id = sourceId,
                    kind = if (normalizedType.contains("tool") || normalizedType.contains("call")) ItemKind.TOOL_CALL else ItemKind.UNKNOWN,
                    status = status,
                    name = item.string("title") ?: item.string("name") ?: item.string("toolName") ?: item.string("tool_name"),
                    text = extractText(item),
                    command = item.string("command"),
                    cwd = item.string("cwd"),
                )
            }
            itemSnapshots[sourceId] = result
            if (normalizedType == "collabagenttoolcall") {
                return buildList {
                    add(ProviderEvent.ItemUpdated(result))
                    buildSubagentUpdatedEvent(item)?.let(::add)
                }
            }
            return listOf(ProviderEvent.ItemUpdated(result))
        }

        /**
         * Maps Codex thread status notifications into the current subagent snapshot list.
         */
        private fun parseThreadStatusChanged(params: JsonObject): List<ProviderEvent> {
            val threadId = params.string("threadId") ?: return emptyList()
            val rawStatus = params.objectValue("status")?.string("type")
                ?: params.string("status")
                ?: return emptyList()
            if (isNotLoadedSubagentStatus(rawStatus)) {
                if (!isTrackedSubagentThread(threadId)) return emptyList()
                subagentSnapshotsByThreadId.remove(threadId)
                closedSubagentThreadIds += threadId
                return listOf(currentSubagentSnapshotEvent())
            }
            val existing = subagentSnapshotsByThreadId[threadId] ?: return emptyList()
            closedSubagentThreadIds.remove(threadId)
            val updated = existing.copy(
                status = normalizeSubagentStatus(rawStatus),
                statusText = rawStatus.trim().ifBlank { existing.statusText },
                updatedAt = System.currentTimeMillis(),
            )
            subagentSnapshotsByThreadId[threadId] = updated
            val events = mutableListOf<ProviderEvent>(
                currentSubagentSnapshotEvent(),
            )
            maybeBuildParentTurnFailureCompletion(threadId = threadId, rawStatus = rawStatus)?.let(events::add)
            return events
        }

        /**
         * Converts a child-thread terminal turn into a subagent snapshot refresh instead of a
         * top-level timeline completion event.
         */
        private fun parseChildTurnCompleted(
            threadId: String,
            outcome: TurnOutcome,
            rawStatus: String?,
        ): List<ProviderEvent> {
            val existing = subagentSnapshotsByThreadId[threadId] ?: return emptyList()
            val effectiveStatus = rawStatus?.trim()?.takeIf { it.isNotBlank() } ?: when (outcome) {
                TurnOutcome.SUCCESS -> "completed"
                TurnOutcome.CANCELLED -> "interrupted"
                TurnOutcome.FAILED -> "failed"
                TurnOutcome.RUNNING -> "running"
            }
            val updated = existing.copy(
                status = normalizeSubagentStatus(effectiveStatus),
                statusText = effectiveStatus,
                updatedAt = System.currentTimeMillis(),
            )
            subagentSnapshotsByThreadId[threadId] = updated
            val events = mutableListOf<ProviderEvent>(
                currentSubagentSnapshotEvent(),
            )
            maybeBuildParentTurnFailureCompletion(threadId = threadId, rawStatus = effectiveStatus)?.let(events::add)
            return events
        }

        private fun parseThreadCompacted(params: JsonObject): List<ProviderEvent> {
            val turnId = params.string("turnId") ?: activeTurnId
            val threadId = params.string("threadId") ?: activeThreadId
            val sourceId = turnId
                ?.let { contextCompactionItemIdsByTurnId[it] }
                ?: turnId?.let { scopedId("context-compaction:$it") }
                ?: threadId?.let { scopedId("context-compaction:$it") }
                ?: return emptyList()
            val item = ProviderItem(
                id = sourceId,
                kind = ItemKind.CONTEXT_COMPACTION,
                status = ItemStatus.SUCCESS,
                name = "Context Compaction",
                text = contextCompactionText(ItemStatus.SUCCESS),
            )
            turnId?.takeIf { it.isNotBlank() }?.let { contextCompactionItemIdsByTurnId[it] = sourceId }
            itemSnapshots[sourceId] = item
            return listOf(ProviderEvent.ItemUpdated(item))
        }

        private fun contextCompactionText(status: ItemStatus): String {
            return when (status) {
                ItemStatus.RUNNING -> "Compacting context"
                ItemStatus.SUCCESS -> "Context compacted"
                ItemStatus.FAILED -> "Context compaction interrupted"
                ItemStatus.SKIPPED -> "Context compaction skipped"
            }
        }

        private fun parseNarrativeDelta(
            params: JsonObject,
            itemName: String,
            isThinking: Boolean,
        ): List<ProviderEvent> {
            val rawId = params.string("itemId") ?: params.string("item_id") ?: itemName
            val id = scopedId(rawId)
            val delta = params.string("delta")
                ?: params.objectValue("delta")?.string("text")
                ?: params.string("text")
                ?: return emptyList()
            val buffer = narrativeBuffers.getOrPut(id) { StringBuilder() }
            buffer.append(delta)
            val item = ProviderItem(
                id = id,
                kind = ItemKind.NARRATIVE,
                status = ItemStatus.RUNNING,
                name = if (isThinking) "reasoning" else itemName,
                text = buffer.toString(),
            )
            itemSnapshots[id] = item
            return listOf(ProviderEvent.ItemUpdated(item))
        }

        private fun parsePlanDelta(params: JsonObject): List<ProviderEvent> {
            val planId = planItemId(params.string("turnId") ?: activeTurnId)
            val delta = params.string("delta")
                ?: params.objectValue("delta")?.string("text")
                ?: params.string("text")
                ?: return emptyList()
            val buffer = planBuffers.getOrPut(planId) {
                StringBuilder(itemSnapshots[planId]?.text.orEmpty())
            }
            buffer.append(delta)
            val item = ProviderItem(
                id = planId,
                kind = ItemKind.PLAN_UPDATE,
                status = ItemStatus.RUNNING,
                name = "Plan Update",
                text = buffer.toString(),
            )
            itemSnapshots[planId] = item
            return listOf(ProviderEvent.ItemUpdated(item))
        }

        private fun parseActivityDelta(
            params: JsonObject,
            kind: ItemKind,
        ): List<ProviderEvent> {
            val rawId = params.string("itemId") ?: params.string("item_id") ?: return emptyList()
            val id = scopedId(rawId)
            val delta = params.string("delta")
                ?: params.objectValue("delta")?.string("text")
                ?: params.string("output")
                ?: return emptyList()
            val buffer = activityOutputBuffers.getOrPut(id) { StringBuilder() }
            buffer.append(delta)
            val snapshot = itemSnapshots[id]
            val item = ProviderItem(
                id = id,
                kind = kind,
                status = ItemStatus.RUNNING,
                name = snapshot?.name,
                text = buffer.toString(),
                command = snapshot?.command,
                cwd = snapshot?.cwd,
                fileChanges = snapshot?.fileChanges.orEmpty(),
            )
            itemSnapshots[id] = item
            return listOf(ProviderEvent.ItemUpdated(item))
        }

        private fun isUserMessageType(normalizedType: String): Boolean {
            return normalizedType == "usermessage" ||
                normalizedType == "user_message" ||
                normalizedType == "user-message"
        }

        private fun isWebSearchType(normalizedType: String): Boolean {
            return normalizedType == "websearch" ||
                normalizedType == "web_search" ||
                normalizedType == "web-search"
        }

        private fun isMcpToolCallType(normalizedType: String): Boolean {
            return normalizedType == "mcptoolcall" ||
                normalizedType == "mcp_tool_call" ||
                normalizedType == "mcp-tool-call"
        }

        private fun extractText(item: JsonObject): String {
            return item.string("text")
                ?: item.string("output")
                ?: item.string("aggregatedOutput")
                ?: item.string("query")
                ?: item.objectValue("content")?.string("text")
                ?: item.arrayValue("content")?.firstTextBlock()
                ?: ""
        }

        /**
         * Formats collaboration tool payloads into readable lines so wait/spawn tool
         * nodes keep the child-thread failure details visible in the timeline.
         */
        private fun formatCollabAgentToolCallBody(item: JsonObject): String {
            val snapshot = collabToolCallSnapshot(item)
            val lines = mutableListOf<String>()
            snapshot?.tool
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { lines += "- Tool: `$it`" }
            snapshot?.prompt
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { lines += "- Prompt: $it" }
            val receiverThreadIds = snapshot?.receiverThreadIds.orEmpty()
            if (receiverThreadIds.isNotEmpty()) {
                lines += "- Agent Threads: ${receiverThreadIds.joinToString(", ") { "`$it`" }}"
            }
            val agentStates = snapshot?.agentStatesByThreadId.orEmpty()
            receiverThreadIds.forEach { threadId ->
                val state = agentStates[threadId] ?: return@forEach
                state.string("status")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { lines += "- Agent Status: `$threadId` -> `$it`" }
                state.string("message")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { lines += "- Agent Message: `$threadId` -> $it" }
            }
            return lines.joinToString("\n")
        }

        /**
         * Converts raw collaboration tool names into stable timeline-facing titles.
         */
        private fun collabToolCallDisplayName(rawTool: String?): String {
            return when (rawTool?.trim()?.lowercase()) {
                "spawnagent" -> "Dispatch Agent"
                "wait", "wait_agent" -> "Wait Agent"
                "sendinput", "send_input" -> "Message Agent"
                "closeagent", "close_agent" -> "Close Agent"
                "resumeagent", "resume_agent" -> "Resume Agent"
                else -> rawTool
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.replace(Regex("([a-z])([A-Z])"), "$1 $2")
                    ?.replace('_', ' ')
                    ?.replaceFirstChar { it.uppercase() }
                    ?: "Tool Call"
            }
        }

        /**
         * Builds a normalized subagent snapshot update from Codex collaboration payloads.
         */
        private fun buildSubagentUpdatedEvent(item: JsonObject): ProviderEvent.SubagentsUpdated? {
            val snapshot = collabToolCallSnapshot(item)
            val rawTool = snapshot?.tool?.trim()?.lowercase()
            val receiverThreadIds = snapshot?.receiverThreadIds.orEmpty()
            val agentStates = snapshot?.agentStatesByThreadId.orEmpty()
            val prompt = snapshot?.prompt.orEmpty()
            if (receiverThreadIds.isEmpty()) {
                return null
            }
            if (isCloseAgentTool(rawTool) && isCompletedCollabToolCall(item.string("status"))) {
                receiverThreadIds.forEach { threadId ->
                    subagentSnapshotsByThreadId.remove(threadId)
                    closedSubagentThreadIds += threadId
                }
                return currentSubagentSnapshotEvent()
            }
            receiverThreadIds.forEach { threadId ->
                if (isSpawnAgentTool(rawTool)) {
                    closedSubagentThreadIds.remove(threadId)
                } else if (threadId in closedSubagentThreadIds) {
                    return@forEach
                }
                val state = agentStates[threadId]
                val displayName = deriveSubagentDisplayName(prompt)
                val mentionSlug = ensureUniqueMentionSlug(
                    baseSlug = deriveSubagentMentionSlug(prompt),
                    threadId = threadId,
                )
                val rawStatus = state?.string("status").orEmpty()
                val summary = state?.string("message")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: prompt.trim().takeIf(String::isNotBlank)
                subagentSnapshotsByThreadId[threadId] = ProviderAgentSnapshot(
                    threadId = threadId,
                    displayName = displayNameForSlug(mentionSlug, displayName),
                    mentionSlug = mentionSlug,
                    status = normalizeSubagentStatus(rawStatus),
                    statusText = rawStatus.ifBlank { "pending" },
                    summary = summary,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            return currentSubagentSnapshotEvent()
        }

        /**
         * Returns a stable, UI-ready snapshot order for the current session subagents.
         */
        private fun currentSubagentSnapshots(): List<ProviderAgentSnapshot> {
            return subagentSnapshotsByThreadId.values.sortedWith(
                compareBy<ProviderAgentSnapshot> { statusSortOrder(it.status) }
                    .thenByDescending { it.updatedAt },
            )
        }

        /**
         * Emits the current child-thread snapshot list using the active parent thread/turn coordinates.
         */
        private fun currentSubagentSnapshotEvent(): ProviderEvent.SubagentsUpdated {
            return ProviderEvent.SubagentsUpdated(
                threadId = activeThreadId,
                turnId = activeTurnId,
                agents = currentSubagentSnapshots(),
            )
        }

        /**
         * Normalizes raw Codex collaboration status strings into shared UI states.
         */
        private fun normalizeSubagentStatus(rawStatus: String?): ProviderAgentStatus {
            return when (rawStatus?.trim()?.lowercase()) {
                "active", "running", "inprogress", "in_progress" -> ProviderAgentStatus.ACTIVE
                "idle" -> ProviderAgentStatus.IDLE
                "pending", "pendinginit", "pending_init", "queued" -> ProviderAgentStatus.PENDING
                "systemerror", "errored", "error", "failed", "interrupted", "declined" -> ProviderAgentStatus.FAILED
                "completed", "success", "succeeded", "done" -> ProviderAgentStatus.COMPLETED
                else -> ProviderAgentStatus.UNKNOWN
            }
        }

        /**
         * Returns true when the raw thread status means the child thread has been unloaded and should be discarded.
         */
        private fun isNotLoadedSubagentStatus(rawStatus: String): Boolean {
            return rawStatus.trim().equals("notLoaded", ignoreCase = true)
        }

        /**
         * Returns true when a collaboration tool call represents spawning a fresh child thread.
         */
        private fun isSpawnAgentTool(rawTool: String?): Boolean {
            return rawTool == "spawnagent" || rawTool == "spawn_agent"
        }

        /**
         * Returns true when a collaboration tool call is closing child threads and should evict them from the tray.
         */
        private fun isCloseAgentTool(rawTool: String?): Boolean {
            return rawTool == "closeagent" || rawTool == "close_agent"
        }

        /**
         * Returns true when the collaboration tool call itself reached a successful terminal state.
         */
        private fun isCompletedCollabToolCall(rawStatus: String?): Boolean {
            return when (rawStatus?.trim()?.lowercase()) {
                "completed", "success", "succeeded", "done" -> true
                else -> false
            }
        }

        /**
         * Derives a compact mention slug from the original collaboration prompt.
         */
        private fun deriveSubagentMentionSlug(prompt: String): String {
            val lower = prompt.lowercase()
            val base = when {
                "review" in lower -> "review-agent"
                "search" in lower -> "search-agent"
                "fix" in lower -> "fix-agent"
                "plan" in lower -> "plan-agent"
                "test" in lower -> "test-agent"
                else -> "agent"
            }
            return base
        }

        /**
         * Derives a human-readable display name from the collaboration prompt.
         */
        private fun deriveSubagentDisplayName(prompt: String): String {
            return displayNameForSlug(deriveSubagentMentionSlug(prompt), null)
        }

        /**
         * Ensures mention slugs stay unique inside one main session even when multiple agents share a role.
         */
        private fun ensureUniqueMentionSlug(baseSlug: String, threadId: String): String {
            val existing = subagentSnapshotsByThreadId[threadId]?.mentionSlug
            if (!existing.isNullOrBlank()) return existing
            if (subagentSnapshotsByThreadId.values.none { it.mentionSlug == baseSlug }) {
                return baseSlug
            }
            var suffix = 2
            while (subagentSnapshotsByThreadId.values.any { it.mentionSlug == "$baseSlug-$suffix" }) {
                suffix += 1
            }
            return "$baseSlug-$suffix"
        }

        /**
         * Formats a display name from the mention slug while allowing an explicit preferred label.
         */
        private fun displayNameForSlug(slug: String, preferredName: String?): String {
            if (!preferredName.isNullOrBlank()) {
                return preferredName
            }
            return slug.split('-')
                .filter { it.isNotBlank() }
                .joinToString(" ") { token -> token.replaceFirstChar { it.uppercase() } }
        }

        /**
         * Provides stable UI ordering for the collapsed tray and mention popup.
         */
        private fun statusSortOrder(status: ProviderAgentStatus): Int {
            return when (status) {
                ProviderAgentStatus.ACTIVE -> 0
                ProviderAgentStatus.IDLE -> 1
                ProviderAgentStatus.PENDING -> 2
                ProviderAgentStatus.FAILED -> 3
                ProviderAgentStatus.COMPLETED -> 4
                ProviderAgentStatus.UNKNOWN -> 5
            }
        }

        /**
         * Resolves the main thread that initiated a collaboration tool call.
         */
        private fun collabToolCallSenderThreadId(item: JsonObject): String? {
            return item.string("senderThreadId")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: activeThreadId?.trim()?.takeIf { it.isNotBlank() }
        }

        /**
         * Merges the latest collaboration payload with any cached call snapshot for the same item id.
         */
        private fun collabToolCallSnapshot(item: JsonObject): CollabToolCallSnapshot? {
            val rawId = item.string("id")?.trim()?.takeIf { it.isNotBlank() }
            val cached = rawId?.let(collabToolCallSnapshotsById::get)
            val senderThreadId = collabToolCallSenderThreadId(item)
            val senderCached = senderThreadId?.let(collabToolCallSnapshotsBySenderThreadId::get)
            val receiverThreadIds = item.arrayValue("receiverThreadIds")
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                .orEmpty()
                .ifEmpty { cached?.receiverThreadIds.orEmpty() }
                .ifEmpty { senderCached?.receiverThreadIds.orEmpty() }
            val currentStates = item.objectValue("agentsStates")
                ?.entries
                ?.mapNotNull { (threadId, value) ->
                    val state = value as? JsonObject ?: return@mapNotNull null
                    threadId.trim().takeIf(String::isNotBlank)?.let { it to state }
                }
                ?.toMap()
                .orEmpty()
            val mergedStates = linkedMapOf<String, JsonObject>()
            senderCached?.agentStatesByThreadId?.forEach { (threadId, state) -> mergedStates[threadId] = state }
            cached?.agentStatesByThreadId?.forEach { (threadId, state) -> mergedStates[threadId] = state }
            currentStates.forEach { (threadId, state) -> mergedStates[threadId] = state }
            return CollabToolCallSnapshot(
                tool = item.string("tool") ?: cached?.tool ?: senderCached?.tool,
                prompt = item.string("prompt") ?: cached?.prompt ?: senderCached?.prompt,
                receiverThreadIds = receiverThreadIds,
                agentStatesByThreadId = mergedStates,
            )
        }

        /**
         * Refreshes the collaboration cache whenever a spawn/wait item reports new child metadata.
         */
        private fun rememberCollabToolCallSnapshot(item: JsonObject) {
            val rawId = item.string("id")?.trim()?.takeIf { it.isNotBlank() } ?: return
            collabToolCallSnapshot(item)?.let { snapshot ->
                collabToolCallSnapshotsById[rawId] = snapshot
                collabToolCallSenderThreadId(item)?.let { senderThreadId ->
                    collabToolCallSnapshotsBySenderThreadId[senderThreadId] = snapshot
                }
            }
        }

        /**
         * Synthesizes a parent turn failure once a tracked child thread reaches a terminal failure state.
         */
        private fun maybeBuildParentTurnFailureCompletion(
            threadId: String,
            rawStatus: String,
        ): ProviderEvent.TurnCompleted? {
            val normalizedTurnId = activeTurnId?.trim().orEmpty()
            if (parentTurnFailureEmitted || normalizedTurnId.isBlank()) {
                return null
            }
            if (!isTrackedSubagentThread(threadId) || !isTerminalFailedSubagentStatus(rawStatus)) {
                return null
            }
            parentTurnFailureEmitted = true
            return ProviderEvent.TurnCompleted(
                turnId = normalizedTurnId,
                outcome = TurnOutcome.FAILED,
                usage = null,
            )
        }

        /**
         * Returns true when the thread id belongs to a child collaboration thread tracked for this turn.
         */
        private fun isTrackedSubagentThread(threadId: String): Boolean {
            return subagentSnapshotsByThreadId.containsKey(threadId) ||
                collabToolCallSnapshotsById.values.any { snapshot -> threadId in snapshot.receiverThreadIds }
        }

        /**
         * Limits parent turn auto-completion to child failures that cannot recover automatically.
         */
        private fun isTerminalFailedSubagentStatus(rawStatus: String): Boolean {
            return normalizeSubagentStatus(rawStatus) == ProviderAgentStatus.FAILED
        }

        private fun extractWebSearchText(item: JsonObject): String? {
            val query = item.string("query").orEmpty().trim()
            val action = item.objectValue("action")
            if (action == null) return query.ifBlank { null }
            val detail = webSearchDetail(query = query, action = action)
            return detail.ifBlank { query }.ifBlank { null }
        }

        private fun webSearchDetail(
            query: String,
            action: JsonObject,
        ): String {
            val actionType = action.string("type").orEmpty().trim().lowercase()
            return when (actionType) {
                "search" -> {
                    val primary = action.string("query")
                        ?.takeIf { it.isNotBlank() }
                        ?: action.arrayValue("queries")
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                            .orEmpty()
                            .firstOrNull()
                        ?: query
                    val extraQueries = action.arrayValue("queries")
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                        .orEmpty()
                        .dropWhile { it == primary }
                    listOfNotNull(primary.takeIf { it.isNotBlank() }, extraQueries.takeIf { it.isNotEmpty() }?.joinToString("\n"))
                        .joinToString("\n")
                }

                "open_page", "openpage" -> {
                    val target = action.string("url").orEmpty().ifBlank { query }
                    summarizeOpenedLocation(target)
                }

                "find_in_page", "findinpage" -> {
                    val pattern = action.string("pattern").orEmpty().trim()
                    val url = action.string("url").orEmpty().trim()
                    when {
                        pattern.isNotBlank() && url.isNotBlank() -> "'$pattern' in $url"
                        pattern.isNotBlank() -> pattern
                        url.isNotBlank() -> url
                        else -> query
                    }
                }

                else -> query
            }
        }

        private fun summarizeOpenedLocation(target: String): String {
            val trimmed = target.trim()
            if (trimmed.isBlank()) return ""
            val host = runCatching {
                URI(trimmed).host
                    ?.removePrefix("www.")
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
            return host?.let { "Opened $it" } ?: trimmed
        }

        private fun extractUserInputText(item: JsonObject): String {
            val questions = item.arrayValue("questions")
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            val answers = item.objectValue("answers")
                ?: item.objectValue("submittedAnswers")
                ?: item.objectValue("submitted_answers")
                ?: item.objectValue("response")

            val answeredBlocks = questions.mapNotNull { question ->
                val questionId = question.string("id").orEmpty()
                if (questionId.isBlank()) return@mapNotNull null
                val title = question.string("header")
                    ?.takeIf { it.isNotBlank() }
                    ?: question.string("question")
                    ?: questionId
                val isSecret = question["isSecret"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                val answerValues = extractUserInputAnswers(answers, questionId)
                when {
                    answerValues.isEmpty() -> null
                    isSecret -> "$title\nPrivate answer provided"
                    else -> "$title\n${answerValues.joinToString(", ")}"
                }
            }
            if (answeredBlocks.isNotEmpty()) {
                return answeredBlocks.joinToString("\n\n")
            }
            if (answers != null) {
                return "No answers were provided."
            }
            return extractText(item).ifBlank {
                if (questions.isNotEmpty()) {
                    "Waiting for input"
                } else {
                    ""
                }
            }
        }

        /** Restores one historical tool user-input prompt with replay-safe metadata. */
        private fun extractHistoricalToolUserInputPrompt(
            sourceId: String,
            item: JsonObject,
            status: ItemStatus,
        ): ProviderToolUserInputPrompt? {
            val questions = item.arrayValue("questions")
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            if (questions.isEmpty()) return null
            val answersSummary = extractUserInputText(item).trim().takeIf { it.isNotBlank() }
            return ProviderToolUserInputPrompt(
                requestId = sourceId,
                threadId = activeThreadId.orEmpty(),
                turnId = activeTurnId,
                itemId = item.string("id").orEmpty().ifBlank { sourceId },
                questions = questions.map { question ->
                    ProviderToolUserInputQuestion(
                        id = question.string("id").orEmpty(),
                        header = question.string("header").orEmpty(),
                        question = question.string("question").orEmpty(),
                        options = question.arrayValue("options")
                            ?.mapNotNull { option -> option as? JsonObject }
                            ?.map { option ->
                                ProviderToolUserInputOption(
                                    label = option.string("label").orEmpty(),
                                    description = option.string("description").orEmpty(),
                                )
                            }
                            .orEmpty(),
                        isOther = question["isOther"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                        isSecret = question["isSecret"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                    )
                },
                responseSummary = answersSummary,
                status = status,
            )
        }

        private fun extractUserInputAnswers(
            answers: JsonObject?,
            questionId: String,
        ): List<String> {
            val answerObject = answers?.objectValue(questionId)
            val answerList = answerObject?.arrayValue("answers")
                ?.mapNotNull { element ->
                    (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                }
                .orEmpty()
            if (answerList.isNotEmpty()) {
                return answerList
            }
            return listOfNotNull(
                answerObject?.string("answer")?.takeIf { it.isNotBlank() },
                answers?.string(questionId)?.takeIf { it.isNotBlank() },
            )
        }

        private fun extractFileChangeSummary(
            changes: List<ProviderFileChange>,
            item: JsonObject,
        ): String {
            if (changes.isNotEmpty()) {
                return changes.joinToString("\n") { "${it.kind} ${it.path}" }
            }
            return extractText(item)
        }

        private fun extractFileChanges(
            item: JsonObject,
            sourceId: String,
        ): List<ProviderFileChange> {
            val timestamp = item.longOrNull("updatedAt", "updated_at", "createdAt", "created_at")
                ?: System.currentTimeMillis()
            val changeArrays = listOfNotNull(
                item.arrayValue("changes"),
                item.objectValue("payload")?.arrayValue("changes"),
                item.objectValue("result")?.arrayValue("changes"),
                item.objectValue("proposal")?.arrayValue("changes"),
                item.objectValue("fileChange")?.arrayValue("changes"),
                item.objectValue("file_change")?.arrayValue("changes"),
            )
            changeArrays.forEach { changes ->
                val parsed = changes.mapIndexedNotNull { index, change ->
                    val value = change as? JsonObject ?: return@mapIndexedNotNull null
                    val path = value.string("path") ?: value.string("filePath") ?: value.string("file_path") ?: return@mapIndexedNotNull null
                    val oldContent = value.string("oldContent") ?: value.string("old_content")
                    val newContent = value.string("newContent") ?: value.string("new_content") ?: value.string("content")
                    val computedStats = FileChangeMetrics.fromContents(
                        oldContent = oldContent,
                        newContent = newContent,
                    )
                    ProviderFileChange(
                        sourceScopedId = "$sourceId:$index",
                        path = path,
                        kind = extractFileChangeKind(value),
                        timestamp = timestamp,
                        addedLines = value.intOrNull("addedLines", "added_lines") ?: computedStats?.addedLines,
                        deletedLines = value.intOrNull("deletedLines", "deleted_lines") ?: computedStats?.deletedLines,
                        unifiedDiff = value.string("diff"),
                        oldContent = oldContent,
                        newContent = newContent,
                    )
                }
                if (parsed.isNotEmpty()) return parsed
            }
            return emptyList()
        }

        private fun parseStatus(status: String?, fallback: ItemStatus): ItemStatus {
            return when (status?.trim()?.lowercase()) {
                "running", "inprogress", "in_progress", "started", "active" -> ItemStatus.RUNNING
                "completed", "success", "succeeded" -> ItemStatus.SUCCESS
                "failed", "error", "declined", "interrupted" -> ItemStatus.FAILED
                "skipped" -> ItemStatus.SKIPPED
                else -> fallback
            }
        }

        private fun scopedId(rawId: String): String {
            val normalized = rawId.trim()
            return if (normalized.isBlank()) rawId else "$requestId:$normalized"
        }

        private fun planItemId(turnId: String?): String {
            val normalizedTurnId = turnId?.trim().orEmpty()
            return if (normalizedTurnId.isBlank()) {
                scopedId("plan")
            } else {
                scopedId("plan:$normalizedTurnId")
            }
        }

        private fun JsonArray.firstTextBlock(): String? {
            return firstNotNullOfOrNull { element ->
                val obj = element as? JsonObject ?: return@firstNotNullOfOrNull null
                obj.string("text")
            }
        }

        private fun JsonObject.string(key: String): String? {
            return this[key]?.jsonPrimitive?.contentOrNull
        }

        private fun JsonObject.objectValue(key: String): JsonObject? {
            return this[key]?.let { runCatching { it.jsonObject }.getOrNull() }
        }

        private fun JsonObject.arrayValue(key: String): JsonArray? {
            return this[key] as? JsonArray
        }

        private fun JsonObject.int(vararg keys: String): Int {
            return intOrNull(*keys) ?: 0
        }

        private fun JsonObject.intOrNull(vararg keys: String): Int? {
            return keys.firstNotNullOfOrNull { key ->
                this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            }
        }

        private fun JsonObject.longOrNull(vararg keys: String): Long? {
            return keys.firstNotNullOfOrNull { key ->
                this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            }
        }

    }

    companion object {
        private val LOG = Logger.getInstance(CodexRuntimeProvider::class.java)
    }
}

/**
 * Accepts only the current request turn as the completion signal so nested child-agent
 * completions cannot release the parent request lifecycle early.
 */
internal fun shouldCompleteActiveTurn(
    activeTurnId: String?,
    event: ProviderEvent,
): Boolean {
    if (event !is ProviderEvent.TurnCompleted) return false
    val active = activeTurnId?.trim().orEmpty()
    val completed = event.turnId.trim()
    return active.isNotBlank() && completed.isNotBlank() && active == completed
}
