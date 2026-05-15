package com.auracode.assistant.provider.claude

import com.auracode.assistant.conversation.ConversationCapabilities
import com.auracode.assistant.conversation.ConversationHistoryPage
import com.auracode.assistant.conversation.ConversationRef
import com.auracode.assistant.conversation.ConversationSummaryPage
import com.auracode.assistant.logging.CliDebugLogger
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.session.ProviderProtocolDomainMapper
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderToolUserInputAnswerDraft
import com.auracode.assistant.protocol.ProviderToolUserInputPrompt
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.execution.ApprovalAction
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives Claude CLI streaming sessions and outputs diagnostic logs at key boundaries.
 */
internal class ClaudeCliProvider(
    private val settings: AgentSettingsService,
    private val launcher: ClaudeCliLauncher = DefaultClaudeCliLauncher(),
    private val parser: ClaudeStreamEventParser = ClaudeStreamEventParser(),
    private val historyReader: ClaudeLocalHistoryReader = ClaudeLocalHistoryReader(),
    private val diagnosticLogger: (String) -> Unit = { message -> CLI_LOGGER.info { message } },
) : AgentProvider {
    override val providerId: String = ClaudeProviderFactory.ENGINE_ID
    private val running = ConcurrentHashMap<String, ClaudeStreamJsonSession>()

    /** Two-level mapping of requestId → (requestId → deferred), supporting multiple concurrent authorization requests within a single turn. */
    private val pendingApprovals = ConcurrentHashMap<String, ConcurrentHashMap<String, CompletableDeferred<ApprovalAction>>>()

    /** Two-level mapping of requestId → (controlRequestId → deferred), supporting user input feedback for AskUserQuestion. */
    private val pendingToolUserInputs = ConcurrentHashMap<String, ConcurrentHashMap<String, CompletableDeferred<Map<String, ProviderToolUserInputAnswerDraft>>>>()

    /** Starts Claude CLI streaming sessions and collapses unified events into session domain events at the provider boundary. */
    override fun stream(request: AgentRequest): Flow<SessionDomainEvent> = channelFlow {
        logStreamStart(request)
        val session = runCatching { launcher.start(request, settings) }
            .onFailure { error ->
                CLI_LOGGER.warn {
                    "Claude CLI launch failed: requestId=${request.requestId} " +
                        "message=${error.message.orEmpty().ifBlank { error::class.java.simpleName }}"
                }
            }
            .getOrElse { error ->
                emitDomainEvents(
                    request = request,
                    event = ProviderEvent.Error(error.message.orEmpty().ifBlank { "Failed to start Claude CLI." }),
                    sessionEventMapper = ProviderProtocolDomainMapper(),
                    emitDomain = { domainEvent -> send(domainEvent) },
                )
                return@channelFlow
            }
        running[request.requestId] = session
        val approvals = ConcurrentHashMap<String, CompletableDeferred<ApprovalAction>>()
        pendingApprovals[request.requestId] = approvals
        val toolInputs = ConcurrentHashMap<String, CompletableDeferred<Map<String, ProviderToolUserInputAnswerDraft>>>()
        pendingToolUserInputs[request.requestId] = toolInputs
        val accumulator = ClaudeStreamAccumulator()
        val mapper = ClaudeProviderEventMapper(request)
        val sessionEventMapper = ProviderProtocolDomainMapper()
        var planModeExited = false
        try {
            val exitCode = session.collect(
                onStdoutLine = { line ->
                    collectLine(
                        request = request,
                        channel = "stdout",
                        line = line,
                        accumulator = accumulator,
                        mapper = mapper,
                        emitProviderEvent = { unified ->
                            if (unified is ProviderEvent.ApprovalRequested &&
                                unified.request.title.trim().equals("ExitPlanMode", ignoreCase = true)
                            ) {
                                session.writeStdin(
                                    buildControlResponse(
                                        approvalRequestId = unified.request.requestId,
                                        decision = ApprovalAction.ALLOW,
                                        permissionSuggestions = unified.request.permissionSuggestions,
                                        toolInputJson = unified.request.toolInputJson,
                                    ),
                                )
                                diagnosticLogger(
                                    "Claude CLI exit plan mode accepted internally: requestId=${request.requestId} " +
                                        "approvalId=${unified.request.requestId}",
                                )
                                mapper.onExitPlanModeAccepted().forEach { completionEvent ->
                                    emitDomainEvents(
                                        request = request,
                                        event = completionEvent,
                                        sessionEventMapper = sessionEventMapper,
                                        emitDomain = { domainEvent -> send(domainEvent) },
                                    )
                                }
                                planModeExited = true
                                session.cancel()
                            } else if (unified is ProviderEvent.ApprovalRequested) {
                                if (request.approvalMode == com.auracode.assistant.model.AgentApprovalMode.AUTO) {
                                    // In AUTO mode, auto-approve without prompting the authorization UI
                                    session.writeStdin(
                                        buildControlResponse(
                                            approvalRequestId = unified.request.requestId,
                                            decision = ApprovalAction.ALLOW,
                                            permissionSuggestions = unified.request.permissionSuggestions,
                                            toolInputJson = unified.request.toolInputJson,
                                        ),
                                    )
                                    diagnosticLogger(
                                        "Claude CLI approval auto-allowed: requestId=${request.requestId} " +
                                            "approvalId=${unified.request.requestId}",
                                    )
                                } else {
                                // Register deferred to wait for UI layer to call submitApprovalDecision.
                                val deferred = CompletableDeferred<ApprovalAction>()
                                approvals[unified.request.requestId] = deferred
                                emitDomainEvents(
                                    request = request,
                                    event = unified,
                                    sessionEventMapper = sessionEventMapper,
                                    emitDomain = { domainEvent -> send(domainEvent) },
                                )
                                val decision = deferred.await()
                                approvals.remove(unified.request.requestId)
                                session.writeStdin(
                                    buildControlResponse(
                                        approvalRequestId = unified.request.requestId,
                                        decision = decision,
                                        permissionSuggestions = unified.request.permissionSuggestions,
                                        toolInputJson = unified.request.toolInputJson,
                                    ),
                                )
                                diagnosticLogger(
                                    "Claude CLI approval decision: requestId=${request.requestId} " +
                                        "approvalId=${unified.request.requestId} decision=$decision",
                                )
                                }
                            } else if (unified is ProviderEvent.ToolUserInputRequested) {
                                // Register deferred to wait for UI layer to call submitToolUserInput.
                                val deferred = CompletableDeferred<Map<String, ProviderToolUserInputAnswerDraft>>()
                                toolInputs[unified.prompt.requestId] = deferred
                                emitDomainEvents(
                                    request = request,
                                    event = unified,
                                    sessionEventMapper = sessionEventMapper,
                                    emitDomain = { domainEvent -> send(domainEvent) },
                                )
                                val answers = deferred.await()
                                toolInputs.remove(unified.prompt.requestId)
                                session.writeStdin(
                                    buildToolUserInputResponse(
                                        controlRequestId = unified.prompt.requestId,
                                        prompt = unified.prompt,
                                        answers = answers,
                                    ),
                                )
                                diagnosticLogger(
                                    "Claude CLI tool user input answered: requestId=${request.requestId} " +
                                        "controlId=${unified.prompt.requestId} answerCount=${answers.size}",
                                )
                            } else {
                                emitDomainEvents(
                                    request = request,
                                    event = unified,
                                    sessionEventMapper = sessionEventMapper,
                                    emitDomain = { domainEvent -> send(domainEvent) },
                                )
                            }
                        },
                    )
                },
                onStderrLine = { line ->
                    collectLine(
                        request = request,
                        channel = "stderr",
                        line = line,
                        accumulator = accumulator,
                        mapper = mapper,
                        emitProviderEvent = { unified ->
                            emitDomainEvents(
                                request = request,
                                event = unified,
                                sessionEventMapper = sessionEventMapper,
                                emitDomain = { domainEvent -> send(domainEvent) },
                            )
                        },
                    )
                },
            )
            diagnosticLogger("Claude CLI exited: requestId=${request.requestId} exitCode=$exitCode")
            mapper.onProcessExit(exitCode).forEach { unified ->
                emitDomainEvents(
                    request = request,
                    event = unified,
                    sessionEventMapper = sessionEventMapper,
                    emitDomain = { domainEvent -> send(domainEvent) },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (planModeExited) {
                diagnosticLogger("Claude CLI stream closed after ExitPlanMode: requestId=${request.requestId}")
                return@channelFlow
            }
            CLI_LOGGER.warn {
                "Claude CLI stream failed: requestId=${request.requestId} " +
                    "message=${error.message.orEmpty().ifBlank { error::class.java.simpleName }}"
            }
            emitDomainEvents(
                request = request,
                event = ProviderEvent.Error(error.message.orEmpty().ifBlank { "Claude CLI request failed." }),
                sessionEventMapper = sessionEventMapper,
                emitDomain = { domainEvent -> send(domainEvent) },
            )
        } finally {
            pendingApprovals.remove(request.requestId)?.values?.forEach { it.complete(ApprovalAction.REJECT) }
            pendingToolUserInputs.remove(request.requestId)?.values?.forEach { it.complete(emptyMap()) }
            running.remove(request.requestId)
            session.cancel()
            diagnosticLogger("Claude CLI finalized: requestId=${request.requestId}")
        }
    }

    /** Returns the session capabilities currently supported by the Claude engine. */
    override fun capabilities(): ConversationCapabilities = ConversationCapabilities(
        supportsStructuredHistory = true,
        supportsHistoryPagination = false,
        supportsPlanMode = true,
        supportsApprovalRequests = true,
        supportsToolUserInput = true,
        supportsResume = true,
        supportsAttachments = true,
        supportsImageInputs = true,
        supportsSubagents = true,
    )

    /** Writes the authorization decision from the UI layer back to Claude CLI's stdin. */
    override fun submitApprovalDecision(requestId: String, decision: ApprovalAction): Boolean {
        val pendingKeys = pendingApprovals.values.flatMap { it.keys }
        diagnosticLogger(
            "Claude CLI submitApprovalDecision called: requestId=$requestId decision=$decision pendingKeys=$pendingKeys",
        )
        for (approvals in pendingApprovals.values) {
            val deferred = approvals[requestId] ?: continue
            val completed = deferred.complete(decision)
            diagnosticLogger(
                "Claude CLI submitApprovalDecision: deferred found requestId=$requestId completed=$completed",
            )
            return completed
        }
        diagnosticLogger("Claude CLI submitApprovalDecision: deferred NOT found for requestId=$requestId")
        return false
    }

    /** Writes the user input answers collected by the UI layer back to Claude CLI's stdin. */
    override fun submitToolUserInput(
        requestId: String,
        answers: Map<String, ProviderToolUserInputAnswerDraft>,
    ): Boolean {
        diagnosticLogger(
            "Claude CLI submitToolUserInput called: requestId=$requestId answerKeys=${answers.keys}",
        )
        for (inputs in pendingToolUserInputs.values) {
            val deferred = inputs[requestId] ?: continue
            val completed = deferred.complete(answers)
            diagnosticLogger(
                "Claude CLI submitToolUserInput: deferred found requestId=$requestId completed=$completed",
            )
            return completed
        }
        diagnosticLogger("Claude CLI submitToolUserInput: deferred NOT found for requestId=$requestId")
        return false
    }

    /** Reads historical session records from Claude CLI's local JSONL files. */
    override suspend fun loadInitialHistory(ref: ConversationRef, pageSize: Int): ConversationHistoryPage {
        return historyReader.readHistory(ref.remoteConversationId)
    }

    /**
     * Enumerates the local Claude CLI session list by scanning JSONL files under ~/.claude/projects/<encoded-cwd>/.
     * Supports filtering by cwd, keyword search, and pagination.
     */
    override suspend fun listRemoteConversations(
        pageSize: Int,
        cursor: String?,
        cwd: String?,
        searchTerm: String?,
    ): ConversationSummaryPage {
        return historyReader.listSessions(
            cwd = cwd,
            pageSize = pageSize,
            cursor = cursor,
            searchTerm = searchTerm,
        )
    }

    /** Cancels the Claude CLI process corresponding to the specified request. */
    override fun cancel(requestId: String) {
        pendingApprovals.remove(requestId)?.values?.forEach { it.complete(ApprovalAction.REJECT) }
        pendingToolUserInputs.remove(requestId)?.values?.forEach { it.complete(emptyMap()) }
        running.remove(requestId)?.cancel()
        diagnosticLogger("Claude CLI cancelled: requestId=$requestId")
    }

    /** Constructs control_response JSON lines sent back to Claude CLI stdin.
     *  When allow, must return updatedInput (original tool input), otherwise CLI-side zod validation fails.
     *  When deny, must include message field, otherwise CLI-side zod validation fails.
     */
    private fun buildControlResponse(
        approvalRequestId: String,
        decision: ApprovalAction,
        permissionSuggestions: List<String> = emptyList(),
        toolInputJson: String = "{}",
    ): String {
        val innerResponse = when (decision) {
            ApprovalAction.ALLOW, ApprovalAction.ALLOW_FOR_SESSION -> buildJsonObject {
                put("behavior", "allow")
                val inputElement = runCatching { Json.parseToJsonElement(toolInputJson) }.getOrNull()
                if (inputElement != null) put("updatedInput", inputElement)
            }
            ApprovalAction.REJECT -> buildJsonObject {
                put("behavior", "deny")
                put("message", "User denied the request.")
            }
        }
        val response = buildJsonObject {
            put("type", "control_response")
            put("response", buildJsonObject {
                put("subtype", "success")
                put("request_id", approvalRequestId)
                put("response", innerResponse)
            })
        }
        val json = response.toString()
        diagnosticLogger("Claude CLI control_response: $json")
        return json
    }

    /** Constructs control_response for AskUserQuestion containing the user's selected answers. */
    internal fun buildToolUserInputResponse(
        controlRequestId: String,
        prompt: ProviderToolUserInputPrompt,
        answers: Map<String, ProviderToolUserInputAnswerDraft>,
    ): String {
        val rawQuestions: JsonElement = prompt.rawQuestionsJson
            ?.let { raw -> runCatching { Json.parseToJsonElement(raw) }.getOrNull() }
            ?: JsonArray(emptyList())
        val normalizedAnswers = prompt.questions.mapNotNull { question ->
            val submitted = answers[question.id]?.answers.orEmpty()
            submitted.takeIf { it.isNotEmpty() }?.let { question.question to it.joinToString(", ") }
        }.toMap()
        val annotations = prompt.questions.mapNotNull { question ->
            val submitted = answers[question.id]?.answers.orEmpty()
            if (submitted.isEmpty()) return@mapNotNull null
            val optionLabels = question.options.map { option -> option.label }.toSet()
            val freeformNotes = submitted.filterNot { answer -> answer in optionLabels }
            freeformNotes.takeIf { it.isNotEmpty() && optionLabels.isNotEmpty() }
                ?.let { question.question to it.joinToString(", ") }
        }.toMap()
        val response = buildJsonObject {
            put("type", "control_response")
            put(
                "response",
                buildJsonObject {
                    put("subtype", "success")
                    put("request_id", controlRequestId)
                    put(
                        "response",
                        buildJsonObject {
                            put("behavior", "allow")
                            put(
                                "updatedInput",
                                buildJsonObject {
                                    put("questions", rawQuestions)
                                    put(
                                        "answers",
                                        buildJsonObject {
                                            normalizedAnswers.forEach { (questionText, answerText) ->
                                                put(questionText, answerText)
                                            }
                                        },
                                    )
                                    if (annotations.isNotEmpty()) {
                                        put(
                                            "annotations",
                                            buildJsonObject {
                                                annotations.forEach { (questionText, notes) ->
                                                    put(
                                                        questionText,
                                                        buildJsonObject {
                                                            put("notes", notes)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                },
            )
        }
        val json = response.toString()
        diagnosticLogger("Claude CLI tool-user-input control_response: $json")
        return json
    }

    /** Records session startup metadata to confirm the CLI execution context. */
    private fun logStreamStart(request: AgentRequest) {
        diagnosticLogger(
            "Claude CLI start: requestId=${request.requestId} " +
                "model=${request.model?.trim().orEmpty().ifBlank { "<auto>" }} " +
                "reasoningEffort=${request.reasoningEffort?.trim().orEmpty().ifBlank { "<default>" }} " +
                "cwd=${request.workingDirectory} " +
                "resume=${request.remoteConversationId?.isNotBlank() == true} " +
                "collaborationMode=${request.collaborationMode} " +
                "approvalMode=${request.approvalMode} " +
                "contextFiles=${request.contextFiles.size} " +
                "images=${request.imageAttachments.size} files=${request.fileAttachments.size}",
        )
    }

    /** Processes single-line output from Claude CLI and logs raw content, parsing results, and unified events. */
    private suspend fun collectLine(
        request: AgentRequest,
        channel: String,
        line: String,
        accumulator: ClaudeStreamAccumulator,
        mapper: ClaudeProviderEventMapper,
        emitProviderEvent: suspend (ProviderEvent) -> Unit,
    ) {
        diagnosticLogger("Claude CLI $channel: requestId=${request.requestId} line=${line.toLogSnippet()}")
        val rawEvent = parser.parse(line)
        if (rawEvent == null) {
            diagnosticLogger("Claude CLI ignored $channel line: requestId=${request.requestId}")
            return
        }
        // diagnosticLogger(
        //     "Claude CLI parsed event: requestId=${request.requestId} channel=$channel event=${rawEvent.toLogSummary()}",
        // )
        val semanticEvents = accumulator.accumulate(rawEvent)
        if (semanticEvents.isEmpty()) {
            diagnosticLogger("Claude CLI emitted no semantic events: requestId=${request.requestId} channel=$channel")
            return
        }
        semanticEvents.forEach { semanticEvent ->
            // diagnosticLogger(
            //     "Claude CLI semantic event: requestId=${request.requestId} channel=$channel event=${semanticEvent.toLogSummary()}",
            // )
            mapper.map(semanticEvent).forEach { unified ->
                emitProviderEvent(unified)
            }
        }
    }

    /** Converts provider-local unified events into session domain events before emitting them upstream. */
    private suspend fun emitDomainEvents(
        request: AgentRequest,
        event: ProviderEvent,
        sessionEventMapper: ProviderProtocolDomainMapper,
        emitDomain: suspend (SessionDomainEvent) -> Unit,
    ) {
        // diagnosticLogger(
        //     "Claude CLI emit unified event: requestId=${request.requestId} event=${event.toLogSummary()}",
        // )
        sessionEventMapper.map(event).forEach { domainEvent ->
            emitDomain(domainEvent)
        }
    }

    /** Trims raw output into single-line snippets suitable for log reading. */
    private fun String.toLogSnippet(): String {
        return replace("\n", "\\n").take(LOG_SNIPPET_LIMIT)
    }

    /** Generates a compact summary of Claude raw events. */
    private fun ClaudeStreamEvent.toLogSummary(): String {
        return when (this) {
            is ClaudeStreamEvent.AssistantSnapshot -> {
                "assistant sessionId=${sessionId.orEmpty()} messageId=${messageId.orEmpty()} content=${content.size}"
            }

            is ClaudeStreamEvent.ApiRetry -> {
                "api-retry sessionId=${sessionId.orEmpty()} attempt=$attempt maxRetries=$maxRetries delayMs=$retryDelayMs"
            }

            is ClaudeStreamEvent.ContentBlockDelta -> {
                "content-block-delta sessionId=${sessionId.orEmpty()} index=$index delta=${delta::class.java.simpleName}"
            }

            is ClaudeStreamEvent.ContentBlockStarted -> {
                "content-block-start sessionId=${sessionId.orEmpty()} index=$index block=${block::class.java.simpleName}"
            }

            is ClaudeStreamEvent.ContentBlockStopped -> {
                "content-block-stop sessionId=${sessionId.orEmpty()} index=$index"
            }

            is ClaudeStreamEvent.Error -> {
                "error sessionId=${sessionId.orEmpty()} message=${message.toLogSnippet()}"
            }

            is ClaudeStreamEvent.MessageDelta -> {
                "message-delta sessionId=${sessionId.orEmpty()} stopReason=${stopReason.orEmpty()} outputTokens=${usage?.outputTokens ?: 0}"
            }

            is ClaudeStreamEvent.TaskProgress -> {
                "task-progress sessionId=${sessionId.orEmpty()} toolUseId=${toolUseId.orEmpty()} tool=$lastToolName tokens=$totalTokens"
            }

            is ClaudeStreamEvent.MessageStart -> {
                "message-start sessionId=${sessionId.orEmpty()} messageId=$messageId model=${model.orEmpty()}"
            }

            is ClaudeStreamEvent.MessageStopped -> {
                "message-stop sessionId=${sessionId.orEmpty()}"
            }

            is ClaudeStreamEvent.Result -> {
                "result sessionId=${sessionId.orEmpty()} subtype=${subtype.orEmpty()} " +
                    "isError=$isError result=${resultText.orEmpty().toLogSnippet()}"
            }

            is ClaudeStreamEvent.SessionStarted -> {
                "system-init sessionId=$sessionId model=${model.orEmpty()}"
            }

            is ClaudeStreamEvent.UserToolResult -> {
                "tool-result sessionId=${sessionId.orEmpty()} toolUseId=$toolUseId isError=$isError content=${content.toLogSnippet()}"
            }

            is ClaudeStreamEvent.ControlRequest -> {
                "control-request sessionId=${sessionId.orEmpty()} requestId=$requestId toolName=$toolName"
            }

            is ClaudeStreamEvent.TaskNotification -> {
                "task-notification sessionId=${sessionId.orEmpty()} toolUseId=$toolUseId status=$status"
            }

            is ClaudeStreamEvent.ContextCompaction -> {
                "context-compaction sessionId=${sessionId.orEmpty()} isStarting=$isStarting"
            }
        }
    }

    /** Generates a compact summary of semantic-level events to verify if the aggregation layer missed any actual processes. */
    private fun ClaudeConversationEvent.toLogSummary(): String {
        return when (this) {
            is ClaudeConversationEvent.AssistantErrorCaptured -> {
                "assistant-error message=${message.toLogSnippet()}"
            }

            is ClaudeConversationEvent.AssistantTextUpdated -> {
                "assistant-text messageId=$messageId completed=$completed text=${text.toLogSnippet()}"
            }

            is ClaudeConversationEvent.Completed -> {
                "completed isError=$isError model=${model.orEmpty()} result=${resultText.orEmpty().toLogSnippet()}"
            }

            is ClaudeConversationEvent.Error -> {
                "error message=${message.toLogSnippet()}"
            }

            is ClaudeConversationEvent.RetryScheduled -> {
                "retry-scheduled attempt=$attempt maxRetries=$maxRetries delayMs=$retryDelayMs error=${error.orEmpty()}"
            }

            is ClaudeConversationEvent.ReasoningUpdated -> {
                "reasoning messageId=$messageId blockIndex=$blockIndex completed=$completed text=${text.toLogSnippet()}"
            }

            is ClaudeConversationEvent.SessionStarted -> {
                "session-started sessionId=$sessionId model=${model.orEmpty()}"
            }

            is ClaudeConversationEvent.ToolCallUpdated -> {
                "tool-call toolUseId=$toolUseId name=$toolName completed=$completed isError=$isError"
            }

            is ClaudeConversationEvent.UsageUpdated -> {
                "usage model=${model.orEmpty()} inputTokens=$inputTokens outputTokens=$outputTokens contextWindow=${contextWindow ?: 0}"
            }

            is ClaudeConversationEvent.PermissionRequested -> {
                "permission-requested requestId=$requestId toolName=$toolName"
            }

            is ClaudeConversationEvent.ToolUserInputReceived -> {
                "tool-user-input-received requestId=$requestId"
            }

            is ClaudeConversationEvent.SubagentUpdated -> {
                "subagent-updated toolUseId=$toolUseId displayName=$displayName status=$status"
            }

            is ClaudeConversationEvent.ContextCompactionUpdated -> {
                "context-compaction-updated isCompleted=$isCompleted isSuccess=$isSuccess"
            }
        }
    }

    /** Generates a compact summary of unified events to verify if the UI received closing events. */
    private fun ProviderEvent.toLogSummary(): String {
        return when (this) {
            is ProviderEvent.Error -> "error terminal=$terminal message=${message.toLogSnippet()}"
            is ProviderEvent.ItemUpdated -> {
                "item kind=${item.kind} status=${item.status} name=${item.name.orEmpty()} " +
                    "text=${item.text.orEmpty().toLogSnippet()}"
            }

            is ProviderEvent.ThreadStarted -> "thread-started threadId=$threadId"
            is ProviderEvent.TurnCompleted -> "turn-completed turnId=$turnId outcome=$outcome"
            is ProviderEvent.TurnStarted -> "turn-started turnId=$turnId threadId=${threadId.orEmpty()}"
            is ProviderEvent.ApprovalRequested -> "approval-request requestId=${request.requestId}"
            is ProviderEvent.RunningPlanUpdated -> "running-plan turnId=$turnId steps=${steps.size}"
            is ProviderEvent.SubagentsUpdated -> "subagents-updated count=${agents.size}"
            is ProviderEvent.ThreadTokenUsageUpdated -> {
                "token-usage threadId=$threadId turnId=${turnId.orEmpty()} outputTokens=$outputTokens"
            }

            is ProviderEvent.ToolUserInputRequested -> "tool-user-input requestId=${prompt.requestId}"
            is ProviderEvent.ToolUserInputResolved -> "tool-user-input-resolved requestId=$requestId"
            is ProviderEvent.TurnDiffUpdated -> "turn-diff turnId=$turnId"
        }
    }

    companion object {
        private const val LOG_SNIPPET_LIMIT: Int = 4000
        private val LOG = Logger.getInstance(ClaudeCliProvider::class.java)
        private val CLI_LOGGER = CliDebugLogger(LOG)
    }
}
