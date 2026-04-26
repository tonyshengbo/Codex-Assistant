package com.auracode.assistant.provider.codex

import com.auracode.assistant.coroutine.AppCoroutineManager
import com.auracode.assistant.coroutine.ManagedCoroutineScope
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.UnifiedApprovalRequest
import com.auracode.assistant.protocol.UnifiedApprovalRequestKind
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedToolUserInputSubmission
import com.auracode.assistant.toolwindow.execution.ApprovalAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bridges raw Codex app-server traffic into Aura conversation events.
 *
 * The shared client remains domain-oriented while this bridge owns provider-
 * specific approval, user-input, and event-emission behavior.
 */
internal class CodexAppServerConversationBridge(
    private val request: AgentRequest,
    private val active: ActiveRequest,
    private val session: CodexAppServerSession,
    private val client: CodexAppServerClient,
    private val diagnosticLogger: (String) -> Unit,
    private val emitUnified: suspend (UnifiedEvent) -> Unit,
) {
    private val parser = AppServerNotificationParser(
        requestId = request.requestId,
        diagnosticLogger = diagnosticLogger,
    )
    private val scope: ManagedCoroutineScope = AppCoroutineManager.createScope(
        scopeName = "CodexAppServerConversationBridge",
        dispatcher = Dispatchers.IO,
        failureReporter = { scopeName, label, error ->
            diagnosticLogger(
                "$scopeName coroutine failed${label?.let { ": $it" }.orEmpty()}: ${error.message}\n${error.stackTraceToString()}",
            )
        },
    )

    /** Initializes the underlying session. */
    suspend fun initialize() {
        session.initialize()
    }

    /** Ensures a thread exists and stores the thread id in active state. */
    suspend fun ensureThread(): String {
        val threadId = client.ensureThread(request)
        active.threadId = threadId
        return threadId
    }

    /** Starts a turn and stores the returned turn id in active state. */
    suspend fun startTurn(threadId: String) {
        active.turnId = client.startTurn(request, threadId)
    }

    /** Handles a one-way notification emitted by the app-server. */
    suspend fun handleNotification(method: String, params: JsonObject) {
        if (method == "serverRequest/resolved") {
            handleServerRequestResolved(params)
            return
        }
        val events = parser.parseNotification(method = method, params = params)
        events.forEach { event ->
            when (event) {
                is UnifiedEvent.ThreadStarted -> active.threadId = event.threadId
                is UnifiedEvent.TurnStarted -> active.turnId = event.turnId
                else -> Unit
            }
            emitUnified(event)
        }
    }

    /** Handles a server request that expects an approval or input response. */
    suspend fun handleServerRequest(id: JsonElement, method: String, params: JsonObject) {
        val serverRequestId = jsonRpcIdKey(id) ?: return
        if (method == "item/tool/requestUserInput") {
            val prompt = buildToolUserInputPrompt(id, params)
            active.pendingToolUserInputs[serverRequestId] = PendingToolUserInput(
                rawRequestId = id,
                prompt = prompt,
            )
            scope.launch(label = "toolUserInput:$method") {
                runCatching {
                    emitUnified(UnifiedEvent.ToolUserInputRequested(prompt))
                    val submission = active.pendingToolUserInputs[serverRequestId]?.response?.await()
                        ?: UnifiedToolUserInputSubmission(emptyMap())
                        session.respond(
                            serverRequestId = id,
                            result = buildToolUserInputResponse(id, submission.answers).getValue("result").jsonObject,
                        )
                    }.onFailure { error ->
                    diagnosticLogger(
                        "Codex app-server user-input response failed: method=$method params=${params.toString().take(500)} message=${error.message}\n${error.stackTraceToString()}",
                    )
                }
            }
            return
        }
        if (method !in APPROVAL_METHODS) {
            if (looksLikeApprovalMethod(method)) {
                diagnosticLogger("Codex app-server sent an unsupported approval-like request: $method")
            }
            scope.launch(label = "autoApprove:$method") {
                runCatching {
                    session.respond(
                        serverRequestId = id,
                        result = buildJsonObject {
                            put("decision", "accept")
                        },
                    )
                }.onFailure {
                    diagnosticLogger(
                        "Codex app-server server request response failed: method=$method params=${params.toString().take(500)} message=${it.message}\n${it.stackTraceToString()}",
                    )
                }
            }
            return
        }

        val approvalRequest = buildApprovalRequest(serverRequestId = serverRequestId, method = method, params = params)
        val pendingDecision = CompletableDeferred<ApprovalAction>()
        active.pendingApprovals[serverRequestId] = pendingDecision
        scope.launch(label = "approval:$method") {
            runCatching {
                emitUnified(UnifiedEvent.ApprovalRequested(approvalRequest))
                val decision = pendingDecision.await()
                active.pendingApprovals.remove(serverRequestId, pendingDecision)
                session.respond(
                    serverRequestId = id,
                    result = approvalResponse(method = method, params = params, decision = decision),
                )
            }.onFailure { error ->
                active.pendingApprovals.remove(serverRequestId, pendingDecision)
                diagnosticLogger(
                    "Codex app-server approval coroutine failed: method=$method params=${params.toString().take(500)} message=${error.message}\n${error.stackTraceToString()}",
                )
            }
        }
    }

    private fun handleServerRequestResolved(params: JsonObject) {
        val requestId = params["requestId"]?.let(::jsonRpcIdKey) ?: return
        val pendingToolUserInput = active.pendingToolUserInputs.remove(requestId)
        if (pendingToolUserInput != null) {
            scope.launch(label = "toolUserInputResolved:$requestId") {
                emitUnified(UnifiedEvent.ToolUserInputResolved(requestId))
            }
        }
    }

    private fun buildApprovalRequest(
        serverRequestId: String,
        method: String,
        params: JsonObject,
    ): UnifiedApprovalRequest {
        val itemId = scopedApprovalItemId(params.string("itemId") ?: params.string("item_id") ?: serverRequestId)
        val legacyCallId = params.string("callId") ?: params.string("call_id")
        val effectiveItemId = scopedApprovalItemId(
            params.string("itemId") ?: params.string("item_id") ?: legacyCallId ?: serverRequestId,
        )
        val turnId = params.string("turnId") ?: params.string("turn_id") ?: active.turnId
        return when (method) {
            "item/commandExecution/requestApproval",
            "execCommandApproval",
            -> UnifiedApprovalRequest(
                requestId = serverRequestId,
                turnId = turnId,
                itemId = effectiveItemId,
                kind = UnifiedApprovalRequestKind.COMMAND,
                title = "Run command",
                body = listOfNotNull(
                    params.string("reason"),
                    extractCommandText(params),
                    params.string("cwd")?.let { "cwd: $it" },
                ).joinToString("\n").ifBlank { "Command execution requires approval." },
                command = extractCommandText(params),
                cwd = params.string("cwd"),
                allowForSession = true,
            )

            "item/fileChange/requestApproval",
            "applyPatchApproval",
            -> {
                val fileChanges = buildApprovalFileChanges(
                    params = params,
                    sourceId = effectiveItemId,
                )
                UnifiedApprovalRequest(
                    requestId = serverRequestId,
                    turnId = turnId,
                    itemId = effectiveItemId,
                    kind = UnifiedApprovalRequestKind.FILE_CHANGE,
                    title = "Apply file changes",
                    body = listOfNotNull(
                        params.string("reason")?.takeIf { it.isNotBlank() },
                        fileChanges.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.kind} ${it.path}" },
                    ).joinToString("\n").ifBlank { "File changes require approval." },
                    fileChanges = fileChanges,
                    allowForSession = true,
                )
            }

            else -> UnifiedApprovalRequest(
                requestId = serverRequestId,
                turnId = turnId,
                itemId = itemId,
                kind = UnifiedApprovalRequestKind.PERMISSIONS,
                title = "Grant permissions",
                body = params.string("reason").orEmpty().ifBlank { "Additional permissions requested." },
                permissions = summarizePermissions(params.objectValue("permissions")),
                allowForSession = true,
            )
        }
    }

    private fun approvalResponse(
        method: String,
        params: JsonObject,
        decision: ApprovalAction,
    ): JsonObject {
        return buildJsonObject {
            when (method) {
                "item/commandExecution/requestApproval",
                "item/fileChange/requestApproval",
                -> put("decision", commandOrFileDecision(decision))

                "execCommandApproval",
                "applyPatchApproval",
                -> put("decision", legacyCommandOrFileDecision(decision))

                "item/permissions/requestApproval" -> {
                    put(
                        "permissions",
                        when (decision) {
                            ApprovalAction.REJECT -> buildJsonObject {}
                            ApprovalAction.ALLOW,
                            ApprovalAction.ALLOW_FOR_SESSION,
                            -> params.objectValue("permissions") ?: buildJsonObject {}
                        },
                    )
                    put(
                        "scope",
                        when (decision) {
                            ApprovalAction.ALLOW_FOR_SESSION -> "session"
                            else -> "turn"
                        },
                    )
                }
            }
        }
    }

    private fun commandOrFileDecision(decision: ApprovalAction): String {
        return when (decision) {
            ApprovalAction.ALLOW -> "accept"
            ApprovalAction.REJECT -> "decline"
            ApprovalAction.ALLOW_FOR_SESSION -> "acceptForSession"
        }
    }

    /**
     * Maps Aura approval actions to the legacy ReviewDecision strings expected by older app-server flows.
     */
    private fun legacyCommandOrFileDecision(decision: ApprovalAction): String {
        return when (decision) {
            ApprovalAction.ALLOW -> "approved"
            ApprovalAction.REJECT -> "denied"
            ApprovalAction.ALLOW_FOR_SESSION -> "approved_for_session"
        }
    }

    /**
     * Extracts a readable command string from either the v2 text payload or the legacy v1 argument array.
     */
    private fun extractCommandText(params: JsonObject): String? {
        return when (val command = params["command"]) {
            is JsonPrimitive -> command.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonArray -> command.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" ")
            else -> null
        }
    }

    private fun summarizePermissions(permissions: JsonObject?): List<String> {
        if (permissions == null) return emptyList()
        val summary = mutableListOf<String>()
        permissions.objectValue("network")?.let { network ->
            if (!network.isEmpty()) summary += "Network access"
        }
        permissions.objectValue("fileSystem")?.let { fileSystem ->
            fileSystem.arrayValue("read")
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
                ?.takeIf { it.isNotEmpty() }
                ?.let { summary += "Read roots: ${it.joinToString(", ")}" }
            fileSystem.arrayValue("write")
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
                ?.takeIf { it.isNotEmpty() }
                ?.let { summary += "Write roots: ${it.joinToString(", ")}" }
        }
        return summary
    }

    private fun scopedApprovalItemId(rawId: String): String {
        val normalized = rawId.trim()
        return if (normalized.isBlank()) rawId else "${request.requestId}:$normalized"
    }

    private companion object {
        val APPROVAL_METHODS = setOf(
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
            "item/permissions/requestApproval",
            "execCommandApproval",
            "applyPatchApproval",
        )

        /**
         * Flags methods that look like approval requests so unsupported protocol changes do not fail silently.
         */
        fun looksLikeApprovalMethod(method: String): Boolean {
            return method.contains("Approval", ignoreCase = true) || method.endsWith("/requestApproval")
        }
    }
}
