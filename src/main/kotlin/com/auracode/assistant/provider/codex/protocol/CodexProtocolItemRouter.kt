package com.auracode.assistant.provider.codex.protocol

import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.ProviderItem
import kotlinx.serialization.json.JsonObject

internal class CodexProviderItemTypeRouter(
    private val typeParsers: CodexProviderItemTypeParsers,
) {
    /**
     * {"method":"item/completed","params":{"item":{"type":"reasoning","id":"rs_09a942ea3f6ca2c70169cde70740a08199a9fe23290bab7ebf","summary":[],"content":[]},"threadId":"019d4c4a-bb04-7323-8ac9-67304be49170","turnId":"019d4c4a-bb0a-7ef0-baf2-8d8532016052"}}
     * {"method":"item/completed","params":{"item":{"type":"fileChange","id":"call_p6i4gXWoClxPGJMBCQgS28jd","changes":[{"path":"/Users/tonysheng/StudioProject/Aura/src/test/kotlin/com/auracode/assistant/toolwindow/shared/AttachmentPreviewOverlayTest.kt","kind":{"type":"update","move_path":null},"diff":"@@ ..."}],"status":"completed"},"threadId":"019d4c4c-710a-7872-83a8-96551b85de82","turnId":"019d4c4e-6245-7510-bb92-ffded17c4ca8"}}
     */
    fun parse(
        item: JsonObject,
        canonicalType: String,
        status: ItemStatus,
        state: CodexProtocolParserState,
        previous: ProviderItem?,
    ): ProviderItem {
        val itemId = item.string("id") ?: "item-${canonicalType.ifBlank { "unknown" }}"
        return when (canonicalType) {
            CodexProviderItemTypeAliases.AGENT_MESSAGE -> typeParsers.parseAgentMessage(item = item, status = status)
            CodexProviderItemTypeAliases.REASONING -> typeParsers.parseReasoning(item = item, status = status)
            CodexProviderItemTypeAliases.COMMAND_EXECUTION -> typeParsers.parseCommandExecution(
                item = item,
                status = status,
                bufferedOutput = state.activityOutputBuffers[itemId]?.toString().orEmpty(),
            )
            CodexProviderItemTypeAliases.FILE_CHANGE -> typeParsers.parseFileChange(
                item = item,
                status = status,
                previousChanges = previous?.fileChanges.orEmpty(),
            )
            CodexProviderItemTypeAliases.CONTEXT_COMPACTION -> {
                val currentTurnId = state.activeTurnId?.takeIf { it.isNotBlank() }
                if (currentTurnId != null) {
                    state.contextCompactionItemIdsByTurnId[currentTurnId] = itemId
                }
                typeParsers.parseContextCompaction(item = item, status = status)
            }
            CodexProviderItemTypeAliases.WEB_SEARCH -> typeParsers.parseWebSearch(item = item, status = status)
            CodexProviderItemTypeAliases.MCP_TOOL_CALL -> typeParsers.parseMcpToolCall(item = item, status = status)
            CodexProviderItemTypeAliases.PLAN -> typeParsers.parsePlan(item = item, status = status)
            CodexProviderItemTypeAliases.USER_MESSAGE -> typeParsers.parseUserMessage(item = item, status = status)
            else -> typeParsers.parseFallback(item = item, normalizedType = canonicalType, status = status)
        }
    }
}
