package com.auracode.assistant.provider.codex

import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedToolUserInputAnswerDraft
import com.auracode.assistant.protocol.UnifiedToolUserInputPrompt
import com.auracode.assistant.protocol.UnifiedToolUserInputSubmission
import com.auracode.assistant.toolwindow.execution.ApprovalAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/** Stores mutable request state that is shared across provider lifecycle helpers. */
internal data class ActiveRequest(
    val process: Process,
    @Volatile var threadId: String? = null,
    @Volatile var turnId: String? = null,
    @Volatile var lastStderrLine: String? = null,
    @Volatile var cancelledLocally: Boolean = false,
    val turnCompleted: CompletableDeferred<Unit> = CompletableDeferred(),
    val pendingApprovals: ConcurrentHashMap<String, CompletableDeferred<ApprovalAction>> = ConcurrentHashMap(),
    val pendingToolUserInputs: ConcurrentHashMap<String, PendingToolUserInput> = ConcurrentHashMap(),
) {
    /** Cancels the running request and resolves pending user interactions defensively. */
    fun cancel() {
        cancelledLocally = true
        pendingApprovals.values.forEach { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(ApprovalAction.REJECT)
            }
        }
        pendingToolUserInputs.values.forEach { pending ->
            if (!pending.response.isCompleted) {
                pending.response.complete(UnifiedToolUserInputSubmission(emptyMap()))
            }
        }
        process.destroyForcibly()
    }

    /** Submits a previously requested approval decision. */
    fun submitApprovalDecision(requestId: String, decision: ApprovalAction): Boolean {
        val deferred = pendingApprovals.remove(requestId) ?: return false
        return deferred.complete(decision)
    }

    /** Submits tool user-input answers collected from the UI. */
    fun submitToolUserInput(
        requestId: String,
        answers: Map<String, UnifiedToolUserInputAnswerDraft>,
    ): Boolean {
        val pending = pendingToolUserInputs[requestId] ?: return false
        pending.respondedLocally = true
        return pending.response.complete(UnifiedToolUserInputSubmission(answers))
    }
}

/** Tracks one outstanding request-user-input prompt emitted by the app-server. */
internal data class PendingToolUserInput(
    val rawRequestId: JsonElement,
    val prompt: UnifiedToolUserInputPrompt,
    val response: CompletableDeferred<UnifiedToolUserInputSubmission> = CompletableDeferred(),
    @Volatile var respondedLocally: Boolean = false,
)

/**
 * Completes the turn when the app-server exits silently after turn start.
 *
 * This prevents Aura from getting stuck in a running state when the server
 * terminates cleanly but forgets to publish `turn/completed`.
 */
internal fun CoroutineScope.launchSilentExitCompletionWatcher(
    process: Process,
    active: ActiveRequest,
    emitUnified: suspend (UnifiedEvent) -> Unit,
) = launch(Dispatchers.IO) {
    runCatching {
        process.waitFor()
        val completion = syntheticTurnCompletionForSilentExit(
            turnId = active.turnId,
            turnAlreadyCompleted = active.turnCompleted.isCompleted,
            cancelledLocally = active.cancelledLocally,
        ) ?: return@launch
        emitUnified(completion)
    }
}

/** Wraps a startup request with a timeout and a user-facing failure message. */
internal suspend fun <T> runAppServerStartupStep(
    stepName: String,
    active: ActiveRequest,
    process: Process,
    action: suspend () -> T,
): T {
    return try {
        withTimeout(APP_SERVER_HANDSHAKE_TIMEOUT_MS) {
            action()
        }
    } catch (error: Throwable) {
        throw IllegalStateException(buildStartupFailureMessage(stepName, active, process, error), error)
    }
}

/** Builds a stable startup failure message from process state and stderr context. */
internal fun buildStartupFailureMessage(
    stepName: String,
    active: ActiveRequest,
    process: Process,
    cause: Throwable,
): String {
    val stderr = active.lastStderrLine?.trim().orEmpty()
    if (stderr.contains("node: No such file or directory", ignoreCase = true)) {
        return "Codex app-server cannot find Node. Configure Node Path in Settings."
    }
    if (!process.isAlive) {
        return "Codex app-server exited during $stepName${stderr.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
    }
    return "Codex app-server failed during $stepName: ${cause.message.orEmpty().ifBlank { "request timed out" }}"
}

/**
 * Synthesizes a terminal turn event for silent app-server exits after turn start.
 *
 * Explicit failures already flow through dedicated error handling. This fallback
 * only unblocks Aura when the app-server exits without publishing turn/completed.
 */
internal fun syntheticTurnCompletionForSilentExit(
    turnId: String?,
    turnAlreadyCompleted: Boolean,
    cancelledLocally: Boolean,
): UnifiedEvent.TurnCompleted? {
    val normalizedTurnId = turnId?.trim().orEmpty()
    if (normalizedTurnId.isBlank() || turnAlreadyCompleted || cancelledLocally) {
        return null
    }
    return UnifiedEvent.TurnCompleted(
        turnId = normalizedTurnId,
        outcome = TurnOutcome.SUCCESS,
        usage = null,
    )
}
