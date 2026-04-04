package com.auracode.assistant.protocol

import kotlinx.serialization.json.JsonObject

internal class CodexUnifiedItemLifecycleParser(
    private val typeParsers: CodexUnifiedItemTypeParsers,
) {
    private val typeRouter = CodexUnifiedItemTypeRouter(typeParsers)

    /**
     * {"method":"item/started","params":{"item":{"type":"agentMessage","id":"msg_09a942ea3f6ca2c70169cde70921d48199b69e918ccb2361b7","text":"","phase":"final_answer"},"threadId":"019d4c4a-bb04-7323-8ac9-67304be49170","turnId":"019d4c4a-bb0a-7ef0-baf2-8d8532016052"}}
     * {"method":"item/started","params":{"item":{"type":"commandExecution","id":"call_baULISSduOEl4nckjNcZS7uQ","command":"/bin/zsh -lc \"sed -n '1,220p' build.gradle.kts\"","cwd":"/Users/tonysheng/StudioProject/Aura","status":"inProgress"},"threadId":"019d4c4c-710a-7872-83a8-96551b85de82","turnId":"019d4c4e-6245-7510-bb92-ffded17c4ca8"}}
     */
    fun parseItemStarted(params: JsonObject, state: CodexUnifiedParserState): UnifiedEvent? {
        return parseItemLifecycle(params = params, fallbackStatus = ItemStatus.RUNNING, state = state)
    }

    /**
     * {"method":"item/completed","params":{"item":{"type":"reasoning","id":"rs_09a942ea3f6ca2c70169cde70740a08199a9fe23290bab7ebf","summary":[],"content":[]},"threadId":"019d4c4a-bb04-7323-8ac9-67304be49170","turnId":"019d4c4a-bb0a-7ef0-baf2-8d8532016052"}}
     * {"method":"item/completed","params":{"item":{"type":"webSearch","id":"ws_05ad61008443747e0169cde801777881989bf6af25c5af80c6","query":"JetBrains Compose Desktop SelectionContainer artifacts text overlap issue","action":{"type":"search","query":"JetBrains Compose Desktop SelectionContainer artifacts text overlap issue","queries":["JetBrains Compose Desktop SelectionContainer artifacts text overlap issue"]}},"threadId":"019d4c4a-bb04-7323-8ac9-67304be49170","turnId":"019d4c51-f951-7ba3-9d88-618a99c65ad2"}}
     */
    fun parseItemCompleted(params: JsonObject, state: CodexUnifiedParserState): UnifiedEvent? {
        return parseItemLifecycle(params = params, fallbackStatus = ItemStatus.SUCCESS, state = state)
    }

    private fun parseItemLifecycle(
        params: JsonObject,
        fallbackStatus: ItemStatus,
        state: CodexUnifiedParserState,
    ): UnifiedEvent? {
        val item = params.objectValue("item") ?: return null
        val canonicalType = CodexUnifiedItemTypeAliases.canonicalType(item.string("type")) ?: return null

        val turnId = params.string("turnId")?.takeIf { it.isNotBlank() }
        val threadId = params.string("threadId")?.takeIf { it.isNotBlank() }
        state.activeTurnId = turnId ?: state.activeTurnId
        state.activeThreadId = threadId ?: state.activeThreadId

        val status = parseStatus(item.string("status"), fallbackStatus)
        val itemId = item.string("id") ?: "item-${canonicalType.ifBlank { "unknown" }}"
        val previous = state.itemSnapshots[itemId]
        val parsed = typeRouter.parse(
            item = item,
            canonicalType = canonicalType,
            status = status,
            state = state,
            previous = previous,
        )
        state.itemSnapshots[itemId] = parsed
        return UnifiedEvent.ItemUpdated(parsed)
    }

    private fun parseStatus(status: String?, fallback: ItemStatus): ItemStatus {
        return when (status?.trim()?.lowercase()) {
            "running", "inprogress", "in_progress", "started", "active" -> ItemStatus.RUNNING
            "completed", "success", "succeeded" -> ItemStatus.SUCCESS
            "failed", "error", "declined", "interrupted", "incomplete", "cancelled", "canceled" -> ItemStatus.FAILED
            "skipped" -> ItemStatus.SKIPPED
            else -> fallback
        }
    }
}
