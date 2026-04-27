package com.auracode.assistant.provider.codex.protocol

import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderItem
import kotlinx.serialization.json.JsonObject

internal class CodexProtocolDeltaParser {
    /**
     * {"method":"item/agentMessage/delta","params":{"threadId":"019d4c4c-710a-7872-83a8-96551b85de82","turnId":"019d4c4e-6245-7510-bb92-ffded17c4ca8","itemId":"msg_0c864aa2bb0238290169cde706deac819bab2b6f4e65a7b7c9","delta":"入口"}}
     * {"method":"item/agentMessage/delta","params":{"threadId":"019d4c4c-710a-7872-83a8-96551b85de82","turnId":"019d4c4e-6245-7510-bb92-ffded17c4ca8","itemId":"msg_0c864aa2bb0238290169cde706deac819bab2b6f4e65a7b7c9","delta":"层"}}
     */
    fun parseAgentMessageDelta(params: JsonObject, state: CodexProtocolParserState): ProviderEvent? {
        return parseNarrativeDelta(params = params, itemName = "message", state = state)
    }

    /**
     * {"method":"item/reasoning/textDelta","params":{"threadId":"x","turnId":"x","itemId":"rs_x","delta":"reasoning token"}}
     * {"method":"item/reasoning/summaryTextDelta","params":{"threadId":"x","turnId":"x","itemId":"rs_x","delta":"summary token"}}
     */
    fun parseReasoningDelta(params: JsonObject, state: CodexProtocolParserState): ProviderEvent? {
        return parseNarrativeDelta(params = params, itemName = "reasoning", state = state)
    }

    /**
     * {"method":"item/commandExecution/outputDelta","params":{"threadId":"019d47ab-e17e-7401-ba0a-4b69ecf81858","turnId":"019d4c47-1dbe-7bf0-84e3-8262199bc8d5","itemId":"call_f3Rq8ULnFgbb23Eey9qUrn0f","delta":"To honour the JVM settings for this build a single-use Daemon process will be forked."}}
     * {"method":"item/commandExecution/outputDelta","params":{"threadId":"019d47ab-e17e-7401-ba0a-4b69ecf81858","turnId":"019d4c47-1dbe-7bf0-84e3-8262199bc8d5","itemId":"call_f3Rq8ULnFgbb23Eey9qUrn0f","delta":"\\n"}}
     */
    fun parseCommandExecutionOutputDelta(params: JsonObject, state: CodexProtocolParserState): ProviderEvent? {
        return parseActivityDelta(params = params, kind = ItemKind.COMMAND_EXEC, state = state)
    }

    /**
     * {"method":"item/fileChange/outputDelta","params":{"threadId":"019d4c4c-710a-7872-83a8-96551b85de82","turnId":"019d4c4e-6245-7510-bb92-ffded17c4ca8","itemId":"call_p6i4gXWoClxPGJMBCQgS28jd","delta":"Success. Updated the following files:\nM /Users/tonysheng/StudioProject/Aura/src/test/kotlin/com/auracode/assistant/toolwindow/shared/AttachmentPreviewOverlayTest.kt\n"}}
     * {"method":"item/fileChange/outputDelta","params":{"threadId":"019d4c4c-710a-7872-83a8-96551b85de82","turnId":"019d4c4e-6245-7510-bb92-ffded17c4ca8","itemId":"call_7yD3lBUdC2vXPaE3x0Ffszcs","delta":"Success. Updated the following files:\nM /Users/tonysheng/StudioProject/Aura/src/main/kotlin/com/auracode/assistant/toolwindow/shared/AttachmentPreviewOverlay.kt\n"}}
     */
    fun parseFileChangeOutputDelta(params: JsonObject, state: CodexProtocolParserState): ProviderEvent? {
        return parseActivityDelta(params = params, kind = ItemKind.DIFF_APPLY, state = state)
    }

    /**
     * {"method":"item/plan/delta","params":{"threadId":"019d4d59-0bf3-7d71-9ae0-de34d6d50e7e","turnId":"019d4d6a-38f5-7df2-ada6-28943def2f9d","itemId":"019d4d6a-38f5-7df2-ada6-28943def2f9d-plan","delta":"#"}}
     * {"method":"item/plan/delta","params":{"threadId":"019d4d59-0bf3-7d71-9ae0-de34d6d50e7e","turnId":"019d4d6a-38f5-7df2-ada6-28943def2f9d","itemId":"019d4d6a-38f5-7df2-ada6-28943def2f9d-plan","delta":" CLI"}}
     */
    fun parsePlanDelta(params: JsonObject, state: CodexProtocolParserState): ProviderEvent? {
        val turnId = params.string("turnId")?.takeIf { it.isNotBlank() }
        val threadId = params.string("threadId")?.takeIf { it.isNotBlank() }
        state.activeTurnId = turnId ?: state.activeTurnId
        state.activeThreadId = threadId ?: state.activeThreadId

        val planId = state.planItemId(turnId ?: state.activeTurnId)
        val delta = params.string("delta")
            ?: params.objectValue("delta")?.string("text")
            ?: params.string("text")
            ?: return null
        val buffer = state.planBuffers.getOrPut(planId) {
            StringBuilder(state.itemSnapshots[planId]?.text.orEmpty())
        }
        buffer.append(delta)
        val item = ProviderItem(
            id = planId,
            kind = ItemKind.PLAN_UPDATE,
            status = ItemStatus.RUNNING,
            name = "Plan Update",
            text = buffer.toString(),
        )
        state.itemSnapshots[planId] = item
        return ProviderEvent.ItemUpdated(item)
    }

    private fun parseNarrativeDelta(
        params: JsonObject,
        itemName: String,
        state: CodexProtocolParserState,
    ): ProviderEvent? {
        val turnId = params.string("turnId")?.takeIf { it.isNotBlank() }
        val threadId = params.string("threadId")?.takeIf { it.isNotBlank() }
        state.activeTurnId = turnId ?: state.activeTurnId
        state.activeThreadId = threadId ?: state.activeThreadId

        val rawId = params.string("itemId") ?: params.string("item_id") ?: itemName
        val delta = params.string("delta")
            ?: params.objectValue("delta")?.string("text")
            ?: params.string("text")
            ?: return null
        val buffer = state.narrativeBuffers.getOrPut(rawId) { StringBuilder() }
        buffer.append(delta)
        val item = ProviderItem(
            id = rawId,
            kind = ItemKind.NARRATIVE,
            status = ItemStatus.RUNNING,
            name = itemName,
            text = buffer.toString(),
        )
        state.itemSnapshots[rawId] = item
        return ProviderEvent.ItemUpdated(item)
    }

    private fun parseActivityDelta(
        params: JsonObject,
        kind: ItemKind,
        state: CodexProtocolParserState,
    ): ProviderEvent? {
        val turnId = params.string("turnId")?.takeIf { it.isNotBlank() }
        val threadId = params.string("threadId")?.takeIf { it.isNotBlank() }
        state.activeTurnId = turnId ?: state.activeTurnId
        state.activeThreadId = threadId ?: state.activeThreadId

        val itemId = params.string("itemId") ?: params.string("item_id") ?: return null
        val delta = params.string("delta")
            ?: params.objectValue("delta")?.string("text")
            ?: params.string("output")
            ?: return null
        val buffer = state.activityOutputBuffers.getOrPut(itemId) { StringBuilder() }
        buffer.append(delta)
        val snapshot = state.itemSnapshots[itemId]
        val item = ProviderItem(
            id = itemId,
            kind = kind,
            status = ItemStatus.RUNNING,
            name = snapshot?.name,
            text = buffer.toString(),
            command = snapshot?.command,
            cwd = snapshot?.cwd,
            fileChanges = snapshot?.fileChanges.orEmpty(),
        )
        state.itemSnapshots[itemId] = item
        return ProviderEvent.ItemUpdated(item)
    }
}
