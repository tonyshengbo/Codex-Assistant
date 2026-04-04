package com.auracode.assistant.provider.codex

import com.auracode.assistant.conversation.ConversationCapabilities
import com.auracode.assistant.conversation.ConversationHistoryPage
import com.auracode.assistant.conversation.ConversationRef
import com.auracode.assistant.conversation.ConversationSummary
import com.auracode.assistant.conversation.ConversationSummaryPage
import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.CodexProviderFactory
import com.auracode.assistant.protocol.ApprovalDecision
import com.auracode.assistant.protocol.CodexMcpToolContentFormatter
import com.auracode.assistant.protocol.FileChangeMetrics
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedApprovalRequest
import com.auracode.assistant.protocol.UnifiedApprovalRequestKind
import com.auracode.assistant.protocol.TurnUsage
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedFileChange
import com.auracode.assistant.protocol.UnifiedItem
import com.auracode.assistant.protocol.UnifiedRunningPlanStep
import com.auracode.assistant.protocol.UnifiedToolUserInputAnswerDraft
import com.auracode.assistant.protocol.UnifiedToolUserInputOption
import com.auracode.assistant.protocol.UnifiedToolUserInputPrompt
import com.auracode.assistant.protocol.UnifiedToolUserInputQuestion
import com.auracode.assistant.protocol.UnifiedToolUserInputSubmission
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.approval.ApprovalAction
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Streams Codex app-server events and exposes Codex-specific provider capabilities. */
internal class CodexAppServerProvider(
    private val settings: AgentSettingsService,
    private val environmentDetector: CodexEnvironmentDetector = CodexEnvironmentDetector(),
    private val diagnosticLogger: (String) -> Unit = { message -> LOG.info(message) },
) : AgentProvider {
    private val running = ConcurrentHashMap<String, ActiveRequest>()
    override val providerId: String = CodexProviderFactory.ENGINE_ID
    private val historyLoader = CodexConversationHistoryLoader(
        settings = settings,
        environmentDetector = environmentDetector,
        diagnosticLogger = diagnosticLogger,
        providerId = providerId,
    )

    override fun stream(request: AgentRequest): Flow<UnifiedEvent> = callbackFlow {
        val resolution = environmentDetector.resolveForLaunch(
            configuredCodexPath = settings.getState().executablePathFor(CodexProviderFactory.ENGINE_ID),
            configuredNodePath = settings.nodeExecutablePath(),
        )
        if (resolution.codexStatus == CodexEnvironmentStatus.FAILED) {
            trySend(UnifiedEvent.Error("Configured Codex Runtime Path is not executable. Update Settings and try again."))
            close()
            return@callbackFlow
        }
        if (resolution.codexStatus == CodexEnvironmentStatus.MISSING) {
            trySend(UnifiedEvent.Error("Aura Code runtime path is not configured."))
            close()
            return@callbackFlow
        }
        val binary = resolution.codexPath.trim()
        if (settings.nodeExecutablePath().isNotBlank() && resolution.nodeStatus == CodexEnvironmentStatus.FAILED) {
            trySend(UnifiedEvent.Error("Configured Node Path is not executable. Update Settings and try again."))
            close()
            return@callbackFlow
        }

        val process = try {
            createCodexAppServerProcess(
                binary = binary,
                environmentOverrides = resolution.environmentOverrides,
                workingDirectory = File(request.workingDirectory),
            )
        } catch (t: Throwable) {
            trySend(UnifiedEvent.Error("Failed to start Aura Code app-server: ${t.message}"))
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
            lateinit var bridge: CodexAppServerConversationBridge
            val session = CodexProcessAppServerSession(
                process = process,
                diagnosticLogger = diagnosticLogger,
                onNotification = { method, params -> bridge.handleNotification(method, params) },
                onServerRequest = { id, method, params -> bridge.handleServerRequest(id, method, params) },
            )
            val client = CodexAppServerClient(
                session = session,
                diagnosticLogger = diagnosticLogger,
            )
            bridge = CodexAppServerConversationBridge(
                request = request,
                active = active,
                session = session,
                client = client,
                diagnosticLogger = diagnosticLogger,
                emitUnified = { event ->
                    trySend(event)
                    if (event is UnifiedEvent.TurnCompleted) {
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
                    trySend(UnifiedEvent.ThreadStarted(threadId = threadId))
                }
                runAppServerStartupStep("turn/start", active, process) { bridge.startTurn(threadId) }
                launchSilentExitCompletionWatcher(
                    process = process,
                    active = active,
                    emitUnified = { event ->
                        trySend(event)
                        if (event is UnifiedEvent.TurnCompleted) {
                            active.turnCompleted.complete(Unit)
                        }
                    },
                )
                active.turnCompleted.await()
            } catch (t: Throwable) {
                diagnosticLogger("Codex app-server request failed: requestId=${request.requestId} message=${t.message}")
                trySend(UnifiedEvent.Error(t.message ?: "Unknown app-server error"))
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
        answers: Map<String, UnifiedToolUserInputAnswerDraft>,
    ): Boolean {
        return running.values.any { it.submitToolUserInput(requestId, answers) }
    }

    internal class AppServerNotificationParser(
        private val requestId: String,
        private val diagnosticLogger: (String) -> Unit,
    ) {
        private val narrativeBuffers = mutableMapOf<String, StringBuilder>()
        private val activityOutputBuffers = mutableMapOf<String, StringBuilder>()
        private val planBuffers = mutableMapOf<String, StringBuilder>()
        private val itemSnapshots = mutableMapOf<String, UnifiedItem>()
        private val contextCompactionItemIdsByTurnId = mutableMapOf<String, String>()
        private var activeTurnId: String? = null
        private var activeThreadId: String? = null

        fun parseNotification(method: String, params: JsonObject): List<UnifiedEvent> {
            return when (method) {
                "thread/started" -> {
                    val threadId = params.objectValue("thread")?.string("id") ?: return emptyList()
                    activeThreadId = threadId
                    listOf(UnifiedEvent.ThreadStarted(threadId = threadId))
                }

                "turn/started" -> {
                    val turn = params.objectValue("turn")
                    val turnId = turn?.string("id") ?: return emptyList()
                    activeTurnId = turnId
                    val threadId = params.string("threadId")
                        ?: turn.string("threadId")
                    activeThreadId = threadId ?: activeThreadId
                    listOf(UnifiedEvent.TurnStarted(turnId = turnId, threadId = threadId))
                }

                "thread/tokenUsage/updated" -> {
                    val tokenUsage = params.objectValue("tokenUsage") ?: return emptyList()
                    val total = tokenUsage.objectValue("total") ?: return emptyList()
                    val threadId = params.string("threadId") ?: return emptyList()
                    listOf(
                        UnifiedEvent.ThreadTokenUsageUpdated(
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
                        UnifiedEvent.TurnDiffUpdated(
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
                        UnifiedEvent.Error(
                            message = message,
                            terminal = !willRetry,
                        ),
                    )
                }

                "thread/compacted" -> parseThreadCompacted(params)

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
                    listOf(UnifiedEvent.TurnCompleted(turnId = turnId, outcome = outcome, usage = usage))
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
                            UnifiedRunningPlanStep(
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
                        UnifiedEvent.RunningPlanUpdated(
                            threadId = activeThreadId,
                            turnId = turnId,
                            explanation = explanation.takeIf { it.isNotBlank() },
                            steps = steps,
                            body = body,
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
                        UnifiedEvent.ItemUpdated(
                            UnifiedItem(
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

        fun parseHistoricalTurn(turn: JsonObject): List<UnifiedEvent> {
            val turnId = turn.string("id").orEmpty()
            val threadId = turn.string("threadId") ?: turn.string("thread_id")
            activeTurnId = turnId.takeIf { it.isNotBlank() }
            activeThreadId = threadId ?: activeThreadId
            val turnEvents = mutableListOf<UnifiedEvent>()
            if (turnId.isNotBlank()) {
                turnEvents += UnifiedEvent.TurnStarted(turnId = turnId, threadId = threadId)
            }
            turn.arrayValue("items")
                ?.mapNotNull { it as? JsonObject }
                ?.forEach { item ->
                    turnEvents += parseHistoricalItem(item)
                }
            val status = turn.string("status")
            val usageObject = turn.objectValue("usage")
            turnEvents += UnifiedEvent.TurnCompleted(
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

        private fun parseHistoricalItem(item: JsonObject): List<UnifiedEvent> {
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
        ): List<UnifiedEvent> {
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
                normalizedType.contains("message") -> UnifiedItem(
                    id = sourceId,
                    kind = ItemKind.NARRATIVE,
                    status = status,
                    name = if (normalizedType.contains("user")) "user_message" else "message",
                    text = extractText(item),
                )

                normalizedType.contains("reasoning") -> UnifiedItem(
                    id = sourceId,
                    kind = ItemKind.NARRATIVE,
                    status = status,
                    name = "reasoning",
                    text = extractText(item),
                )

                isWebSearchType(normalizedType) -> UnifiedItem(
                    id = sourceId,
                    kind = ItemKind.TOOL_CALL,
                    status = status,
                    name = "web_search",
                    text = extractWebSearchText(item),
                )

                isMcpToolCallType(normalizedType) -> UnifiedItem(
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

                normalizedType.contains("commandexecution") -> {
                    val previousOutput = activityOutputBuffers[sourceId]?.toString().orEmpty()
                    UnifiedItem(
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
                    UnifiedItem(
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
                    UnifiedItem(
                        id = sourceId,
                        kind = ItemKind.CONTEXT_COMPACTION,
                        status = status,
                        name = "Context Compaction",
                        text = contextCompactionText(status),
                    )
                }

                normalizedType == "plan" -> UnifiedItem(
                    id = sourceId,
                    kind = ItemKind.PLAN_UPDATE,
                    status = status,
                    name = "Plan Update",
                    text = extractText(item),
                )

                normalizedType.contains("requestuserinput") || normalizedType.contains("userinput") -> UnifiedItem(
                    id = sourceId,
                    kind = ItemKind.USER_INPUT,
                    status = status,
                    name = "User Input",
                    text = extractUserInputText(item),
                )

                else -> UnifiedItem(
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
            return listOf(UnifiedEvent.ItemUpdated(result))
        }

        private fun parseThreadCompacted(params: JsonObject): List<UnifiedEvent> {
            val turnId = params.string("turnId") ?: activeTurnId
            val threadId = params.string("threadId") ?: activeThreadId
            val sourceId = turnId
                ?.let { contextCompactionItemIdsByTurnId[it] }
                ?: turnId?.let { scopedId("context-compaction:$it") }
                ?: threadId?.let { scopedId("context-compaction:$it") }
                ?: return emptyList()
            val item = UnifiedItem(
                id = sourceId,
                kind = ItemKind.CONTEXT_COMPACTION,
                status = ItemStatus.SUCCESS,
                name = "Context Compaction",
                text = contextCompactionText(ItemStatus.SUCCESS),
            )
            turnId?.takeIf { it.isNotBlank() }?.let { contextCompactionItemIdsByTurnId[it] = sourceId }
            itemSnapshots[sourceId] = item
            return listOf(UnifiedEvent.ItemUpdated(item))
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
        ): List<UnifiedEvent> {
            val rawId = params.string("itemId") ?: params.string("item_id") ?: itemName
            val id = scopedId(rawId)
            val delta = params.string("delta")
                ?: params.objectValue("delta")?.string("text")
                ?: params.string("text")
                ?: return emptyList()
            val buffer = narrativeBuffers.getOrPut(id) { StringBuilder() }
            buffer.append(delta)
            val item = UnifiedItem(
                id = id,
                kind = ItemKind.NARRATIVE,
                status = ItemStatus.RUNNING,
                name = if (isThinking) "reasoning" else itemName,
                text = buffer.toString(),
            )
            itemSnapshots[id] = item
            return listOf(UnifiedEvent.ItemUpdated(item))
        }

        private fun parsePlanDelta(params: JsonObject): List<UnifiedEvent> {
            val planId = planItemId(params.string("turnId") ?: activeTurnId)
            val delta = params.string("delta")
                ?: params.objectValue("delta")?.string("text")
                ?: params.string("text")
                ?: return emptyList()
            val buffer = planBuffers.getOrPut(planId) {
                StringBuilder(itemSnapshots[planId]?.text.orEmpty())
            }
            buffer.append(delta)
            val item = UnifiedItem(
                id = planId,
                kind = ItemKind.PLAN_UPDATE,
                status = ItemStatus.RUNNING,
                name = "Plan Update",
                text = buffer.toString(),
            )
            itemSnapshots[planId] = item
            return listOf(UnifiedEvent.ItemUpdated(item))
        }

        private fun parseActivityDelta(
            params: JsonObject,
            kind: ItemKind,
        ): List<UnifiedEvent> {
            val rawId = params.string("itemId") ?: params.string("item_id") ?: return emptyList()
            val id = scopedId(rawId)
            val delta = params.string("delta")
                ?: params.objectValue("delta")?.string("text")
                ?: params.string("output")
                ?: return emptyList()
            val buffer = activityOutputBuffers.getOrPut(id) { StringBuilder() }
            buffer.append(delta)
            val snapshot = itemSnapshots[id]
            val item = UnifiedItem(
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
            return listOf(UnifiedEvent.ItemUpdated(item))
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

        private fun extractWebSearchText(item: JsonObject): String {
            val query = item.string("query").orEmpty().trim()
            val action = item.objectValue("action")
            if (action == null) return query
            val detail = webSearchDetail(query = query, action = action)
            return detail.ifBlank { query }
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

                "open_page", "openpage" -> action.string("url").orEmpty().ifBlank { query }

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
            changes: List<UnifiedFileChange>,
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
        ): List<UnifiedFileChange> {
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
                    UnifiedFileChange(
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
        private val LOG = Logger.getInstance(CodexAppServerProvider::class.java)
    }
}
