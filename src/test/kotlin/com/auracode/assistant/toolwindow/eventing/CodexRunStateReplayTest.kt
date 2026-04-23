package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.provider.codex.CodexAppServerProvider
import com.auracode.assistant.toolwindow.status.StatusAreaStore
import com.auracode.assistant.toolwindow.timeline.TimelineAreaStore
import com.auracode.assistant.toolwindow.timeline.TimelineNode
import com.auracode.assistant.toolwindow.timeline.TimelineNodeMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Replays a real Codex turn sequence to verify run state cleanup after streaming commentary.
 */
class CodexRunStateReplayTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Verifies that turn completion clears both the bottom status strip and the composer running state.
     */
    @Test
    fun `turn completed clears running state after commentary deltas`() {
        val statusStore = StatusAreaStore()
        val timelineStore = TimelineAreaStore()
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-replay",
            diagnosticLogger = {},
        )

        dispatchPromptAccepted(
            statusStore = statusStore,
            timelineStore = timelineStore,
            localTurnId = "local-turn-1776947455852",
        )

        loggedTurnEvents().forEach { rawEvent ->
            val eventObject = json.parseToJsonElement(rawEvent).jsonObject
            val method = eventObject["method"]?.toString()?.trim('"') ?: return@forEach
            val params = eventObject["params"]?.jsonObject ?: return@forEach
            parser.parseNotification(method = method, params = params).forEach { event ->
                statusStore.onEvent(AppEvent.UnifiedEventPublished(event))
                timelineStore.onEvent(AppEvent.UnifiedEventPublished(event))
                TimelineNodeMapper.fromUnifiedEvent(event)?.let { mutation ->
                    timelineStore.onEvent(AppEvent.TimelineMutationApplied(mutation))
                }
            }
        }

        val assistantMessage = timelineStore.state.value.nodes
            .filterIsInstance<TimelineNode.MessageNode>()
            .last { it.role == com.auracode.assistant.model.MessageRole.ASSISTANT }

        assertEquals(ItemStatus.SUCCESS, assistantMessage.status)
        assertEquals(null, statusStore.state.value.turnStatus)
        assertFalse(timelineStore.state.value.isRunning)
    }

    /**
     * Dispatches the local optimistic start event that the tool window emits before remote events arrive.
     */
    private fun dispatchPromptAccepted(
        statusStore: StatusAreaStore,
        timelineStore: TimelineAreaStore,
        localTurnId: String,
    ) {
        val event = AppEvent.PromptAccepted(
            prompt = "审查一下这里的几个 UI 是不是 图标文字 和 x 在一个控件 里面有没有对齐",
            localTurnId = localTurnId,
        )
        statusStore.onEvent(event)
        timelineStore.onEvent(event)
    }

    /**
     * Returns a reduced replay of the real log sequence around the stuck-run report.
     */
    private fun loggedTurnEvents(): List<String> {
        return listOf(
            """{"method":"turn/started","params":{"threadId":"019dba52-675b-75f3-b606-ab05111b9b7b","turn":{"id":"019dba52-676d-76f3-bc90-af7605bee576","items":[],"status":"inProgress","error":null,"startedAt":1776947455,"completedAt":null,"durationMs":null}}}""",
            """{"method":"item/started","params":{"item":{"type":"userMessage","id":"4c973290-6d4c-4dce-8a9d-61ff7ab2f5ce","content":[{"type":"text","text":"审查一下这里的几个 UI 是不是 图标文字 和 x 在一个控件 里面有没有对齐","text_elements":[]}]},"threadId":"019dba52-675b-75f3-b606-ab05111b9b7b","turnId":"019dba52-676d-76f3-bc90-af7605bee576"}}""",
            """{"method":"item/completed","params":{"item":{"type":"userMessage","id":"4c973290-6d4c-4dce-8a9d-61ff7ab2f5ce","content":[{"type":"text","text":"审查一下这里的几个 UI 是不是 图标文字 和 x 在一个控件 里面有没有对齐","text_elements":[]}]},"threadId":"019dba52-675b-75f3-b606-ab05111b9b7b","turnId":"019dba52-676d-76f3-bc90-af7605bee576"}}""",
            """{"method":"item/started","params":{"item":{"type":"reasoning","id":"rs_04ed27bf8517faa10169ea1103fb1c819398cade945197bd84","summary":[],"content":[]},"threadId":"019dba52-675b-75f3-b606-ab05111b9b7b","turnId":"019dba52-676d-76f3-bc90-af7605bee576"}}""",
            """{"method":"item/completed","params":{"item":{"type":"reasoning","id":"rs_04ed27bf8517faa10169ea1103fb1c819398cade945197bd84","summary":[],"content":[]},"threadId":"019dba52-675b-75f3-b606-ab05111b9b7b","turnId":"019dba52-676d-76f3-bc90-af7605bee576"}}""",
            """{"method":"item/started","params":{"item":{"type":"commandExecution","id":"call_CGHIzZKFdLkBATcceuOQa58M","command":"/bin/zsh -lc \"sed -n '1,220p' /Users/tonysheng/.codex/superpowers/skills/using-superpowers/SKILL.md\"","cwd":"/Users/tonysheng/StudioProject/Aura","status":"inProgress"},"threadId":"019dba52-675b-75f3-b606-ab05111b9b7b","turnId":"019dba52-676d-76f3-bc90-af7605bee576"}}""",
            """{"method":"item/completed","params":{"item":{"type":"commandExecution","id":"call_CGHIzZKFdLkBATcceuOQa58M","command":"/bin/zsh -lc \"sed -n '1,220p' /Users/tonysheng/.codex/superpowers/skills/using-superpowers/SKILL.md\"","cwd":"/Users/tonysheng/StudioProject/Aura","status":"completed","aggregatedOutput":"---\nname: using-superpowers"},"threadId":"019dba52-675b-75f3-b606-ab05111b9b7b","turnId":"019dba52-676d-76f3-bc90-af7605bee576"}}""",
            """{"method":"item/started","params":{"item":{"type":"agentMessage","id":"msg_01ea44eff6b8f6af0169ea11ba6e4c819598719a19dc494ebc","text":"","phase":"commentary","memoryCitation":null},"threadId":"019dba52-675b-75f3-b606-ab05111b9b7b","turnId":"019dba52-676d-76f3-bc90-af7605bee576"}}""",
            """{"method":"item/agentMessage/delta","params":{"threadId":"019dba52-675b-75f3-b606-ab05111b9b7b","turnId":"019dba52-676d-76f3-bc90-af7605bee576","itemId":"msg_01ea44eff6b8f6af0169ea11ba6e4c819598719a19dc494ebc","delta":"日志已经给出一个很关键的结论："}}""",
            """{"method":"item/agentMessage/delta","params":{"threadId":"019dba52-675b-75f3-b606-ab05111b9b7b","turnId":"019dba52-676d-76f3-bc90-af7605bee576","itemId":"msg_01ea44eff6b8f6af0169ea11ba6e4c819598719a19dc494ebc","delta":"这次会话后端其实结束了。"}}""",
            """{"method":"thread/tokenUsage/updated","params":{"threadId":"019dba52-675b-75f3-b606-ab05111b9b7b","turnId":"019dba52-676d-76f3-bc90-af7605bee576","tokenUsage":{"total":{"totalTokens":447174,"inputTokens":438591,"cachedInputTokens":387840,"outputTokens":8583,"reasoningOutputTokens":5477},"last":{"totalTokens":58270,"inputTokens":54788,"cachedInputTokens":54144,"outputTokens":3482,"reasoningOutputTokens":2588},"modelContextWindow":258400}}}""",
            """{"method":"turn/completed","params":{"threadId":"019dba52-675b-75f3-b606-ab05111b9b7b","turn":{"id":"019dba52-676d-76f3-bc90-af7605bee576","items":[],"status":"completed","error":null,"startedAt":1776947455,"completedAt":1776947659,"durationMs":203337}}}""",
        )
    }
}
