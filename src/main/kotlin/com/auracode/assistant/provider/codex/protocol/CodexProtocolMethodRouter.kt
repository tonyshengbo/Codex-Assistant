package com.auracode.assistant.provider.codex.protocol

import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.TurnUsage
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderItem
import com.auracode.assistant.protocol.ProviderPlanStep
import kotlinx.serialization.json.JsonObject

internal class CodexProtocolMethodRouter {
    private val state = CodexProtocolParserState()
    private val itemTypeParsers = CodexProviderItemTypeParsers()
    private val itemLifecycleParser = CodexProviderItemLifecycleParser(itemTypeParsers)
    private val deltaEventParser = CodexProtocolDeltaParser()

    fun parse(obj: JsonObject): ProviderEvent? {
        val method = obj.string("method")?.trim()?.lowercase().orEmpty()
        if (method.isBlank()) return null
        val params = obj.objectValue("params") ?: JsonObject(emptyMap())
        return when (method) {
            "thread/started" -> parseThreadStarted(params)
            "turn/started" -> parseTurnStarted(params)
            "turn/completed" -> parseTurnCompleted(params)
            "thread/tokenusage/updated" -> parseThreadTokenUsageUpdated(params)
            "turn/diff/updated" -> parseTurnDiffUpdated(params)
            "turn/plan/updated" -> parseTurnPlanUpdated(params)
            "thread/compacted" -> parseThreadCompacted(params)
            "item/started" -> itemLifecycleParser.parseItemStarted(params, state)
            "item/completed" -> itemLifecycleParser.parseItemCompleted(params, state)
            "item/agentmessage/delta" -> deltaEventParser.parseAgentMessageDelta(params, state)
            "item/reasoning/textdelta", "item/reasoning/summarytextdelta" -> deltaEventParser.parseReasoningDelta(params, state)
            "item/commandexecution/outputdelta" -> deltaEventParser.parseCommandExecutionOutputDelta(params, state)
            "item/filechange/outputdelta" -> deltaEventParser.parseFileChangeOutputDelta(params, state)
            "item/plan/delta" -> deltaEventParser.parsePlanDelta(params, state)
            "error" -> parseError(params)
            else -> null
        }
    }

    /**
     * {"method":"thread/started","params":{"thread":{"id":"019d4d2d-6a0a-7850-b4df-80af9d8a14d5","status":{"type":"idle"}}}}
     * {"method":"thread/started","params":{"thread":{"id":"019d4d59-0bf3-7d71-9ae0-de34d6d50e7e","status":{"type":"idle"}}}}
     */
    private fun parseThreadStarted(params: JsonObject): ProviderEvent? {
        val threadId = params.objectValue("thread")?.string("id") ?: return null
        state.activeThreadId = threadId
        return ProviderEvent.ThreadStarted(threadId = threadId)
    }

    /**
     * {"method":"turn/started","params":{"threadId":"019d4c4a-bb04-7323-8ac9-67304be49170","turn":{"id":"019d4c4f-dfb9-70a2-833e-594bd1b9afd5","status":"inProgress"}}}
     * {"method":"turn/started","params":{"threadId":"019d4c4a-bb04-7323-8ac9-67304be49170","turn":{"id":"019d4c51-f951-7ba3-9d88-618a99c65ad2","status":"inProgress"}}}
     */
    private fun parseTurnStarted(params: JsonObject): ProviderEvent? {
        val turn = params.objectValue("turn") ?: return null
        val turnId = turn.string("id") ?: return null
        val threadId = params.string("threadId") ?: turn.string("threadId")
        state.activeTurnId = turnId
        state.activeThreadId = threadId ?: state.activeThreadId
        return ProviderEvent.TurnStarted(turnId = turnId, threadId = threadId)
    }

    /**
     * {"method":"turn/completed","params":{"threadId":"019d4c4a-bb04-7323-8ac9-67304be49170","turn":{"id":"019d4c4a-bb0a-7ef0-baf2-8d8532016052","status":"completed"}}}
     * {"method":"turn/completed","params":{"threadId":"019d4c4a-bb04-7323-8ac9-67304be49170","turn":{"id":"019d4c4f-dfb9-70a2-833e-594bd1b9afd5","status":"completed"}}}
     */
    private fun parseTurnCompleted(params: JsonObject): ProviderEvent? {
        val turn = params.objectValue("turn")
        val turnId = turn?.string("id")?.ifBlank { null } ?: state.activeTurnId.orEmpty()
        val status = turn?.string("status") ?: params.string("status")
        val usageObject = params.objectValue("usage")
        val usage = usageObject?.let {
            TurnUsage(
                inputTokens = it.int("inputTokens", "input_tokens"),
                cachedInputTokens = it.int("cachedInputTokens", "cached_input_tokens"),
                outputTokens = it.int("outputTokens", "output_tokens"),
            )
        }
        val outcome = when (status?.lowercase()) {
            "completed", "success" -> TurnOutcome.SUCCESS
            "interrupted" -> TurnOutcome.CANCELLED
            "failed", "error" -> TurnOutcome.FAILED
            else -> TurnOutcome.SUCCESS
        }
        return ProviderEvent.TurnCompleted(turnId = turnId, outcome = outcome, usage = usage)
    }

    /**
     * {"method":"thread/tokenUsage/updated","params":{"threadId":"019d47ab-e17e-7401-ba0a-4b69ecf81858","turnId":"019d4c47-1dbe-7bf0-84e3-8262199bc8d5","tokenUsage":{"total":{"inputTokens":9971779,"cachedInputTokens":9093888,"outputTokens":40840},"modelContextWindow":258400}}}
     * {"method":"thread/tokenUsage/updated","params":{"threadId":"019d4c4c-710a-7872-83a8-96551b85de82","turnId":"019d4c4e-6245-7510-bb92-ffded17c4ca8","tokenUsage":{"total":{"inputTokens":231459,"cachedInputTokens":174720,"outputTokens":2748},"modelContextWindow":258400}}}
     */
    private fun parseThreadTokenUsageUpdated(params: JsonObject): ProviderEvent? {
        val tokenUsage = params.objectValue("tokenUsage") ?: return null
        val total = tokenUsage.objectValue("total") ?: return null
        val threadId = params.string("threadId") ?: return null
        state.activeThreadId = threadId
        return ProviderEvent.ThreadTokenUsageUpdated(
            threadId = threadId,
            turnId = params.string("turnId"),
            contextWindow = tokenUsage.int("modelContextWindow", "model_context_window"),
            inputTokens = total.int("inputTokens", "input_tokens"),
            cachedInputTokens = total.int("cachedInputTokens", "cached_input_tokens"),
            outputTokens = total.int("outputTokens", "output_tokens"),
        )
    }

    /**
     * {"method":"turn/diff/updated","params":{"threadId":"019d47ab-e17e-7401-ba0a-4b69ecf81858","turnId":"019d4c47-1dbe-7bf0-84e3-8262199bc8d5","diff":"diff --git a/src/test/..."}}
     * {"method":"turn/diff/updated","params":{"threadId":"019d47ab-e17e-7401-ba0a-4b69ecf81858","turnId":"019d4c47-1dbe-7bf0-84e3-8262199bc8d5","diff":"diff --git a/src/main/..."}}
     */
    private fun parseTurnDiffUpdated(params: JsonObject): ProviderEvent? {
        val threadId = params.string("threadId") ?: return null
        val turnId = params.string("turnId") ?: return null
        val diff = params.string("diff") ?: return null
        return ProviderEvent.TurnDiffUpdated(threadId = threadId, turnId = turnId, diff = diff)
    }

    /**
     * {"method":"turn/plan/updated","params":{"threadId":"019d4d59-0bf3-7d71-9ae0-de34d6d50e7e","turnId":"019d4d6a-38f5-7df2-ada6-28943def2f9d","explanation":"","plan":[{"step":"Refactor parser","status":"in_progress"}]}}
     * {"method":"turn/plan/updated","params":{"threadId":"019d4d59-0bf3-7d71-9ae0-de34d6d50e7e","turnId":"019d4d6a-38f5-7df2-ada6-28943def2f9d","explanation":"Done","plan":[{"step":"Refactor parser","status":"completed"}]}}
     */
    private fun parseTurnPlanUpdated(params: JsonObject): ProviderEvent? {
        val turnId = params.string("turnId") ?: return null
        val threadId = params.string("threadId")
        state.activeTurnId = turnId
        state.activeThreadId = threadId ?: state.activeThreadId

        val explanation = params.string("explanation")?.takeIf { it.isNotBlank() }
        val steps = params.arrayValue("plan")
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { value ->
                val text = value.string("step") ?: return@mapNotNull null
                ProviderPlanStep(step = text, status = value.string("status").orEmpty())
            }
            .orEmpty()
        val planText = steps.joinToString("\n") { "- [${it.status}] ${it.step}" }
        val body = listOfNotNull(explanation, planText.takeIf { it.isNotBlank() })
            .joinToString("\n\n")
            .ifBlank { "Plan updated" }
        val planId = state.planItemId(turnId)
        state.planBuffers[planId] = StringBuilder(body)
        return ProviderEvent.RunningPlanUpdated(
            threadId = state.activeThreadId,
            turnId = turnId,
            explanation = explanation,
            steps = steps,
            body = body,
        )
    }

    /**
     * {"method":"thread/compacted","params":{"threadId":"019d47ab-e17e-7401-ba0a-4b69ecf81858","turnId":"019d4c47-1dbe-7bf0-84e3-8262199bc8d5"}}
     * {"method":"item/completed","params":{"item":{"type":"contextCompaction","id":"23480200-1d53-4a26-82f9-2eab84e10303"},"threadId":"019d47ab-e17e-7401-ba0a-4b69ecf81858","turnId":"019d4c47-1dbe-7bf0-84e3-8262199bc8d5"}}
     */
    private fun parseThreadCompacted(params: JsonObject): ProviderEvent? {
        val turnId = params.string("turnId") ?: state.activeTurnId
        val threadId = params.string("threadId") ?: state.activeThreadId
        val sourceId = turnId
            ?.let { state.contextCompactionItemIdsByTurnId[it] }
            ?: turnId?.let { "context-compaction:$it" }
            ?: threadId?.let { "context-compaction:$it" }
            ?: return null
        val item = ProviderItem(
            id = sourceId,
            kind = ItemKind.CONTEXT_COMPACTION,
            status = ItemStatus.SUCCESS,
            name = "Context Compaction",
            text = "Context compacted",
        )
        turnId?.takeIf { it.isNotBlank() }?.let { state.contextCompactionItemIdsByTurnId[it] = sourceId }
        state.itemSnapshots[sourceId] = item
        return ProviderEvent.ItemUpdated(item)
    }

    /**
     * {"method":"error","params":{"message":"Turn failed because command exited with 1"}}
     * {"method":"error","params":{"error":{"message":"Transport disconnected"},"willRetry":true}}
     */
    private fun parseError(params: JsonObject): ProviderEvent? {
        val error = params.objectValue("error")
        val message = error?.string("message") ?: params.string("message") ?: return null
        val willRetry = params.string("willRetry")?.toBooleanStrictOrNull() == true
        return ProviderEvent.Error(message = message, terminal = !willRetry)
    }
}
