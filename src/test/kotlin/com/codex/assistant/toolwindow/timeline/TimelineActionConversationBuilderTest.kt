package com.codex.assistant.toolwindow.timeline

import com.codex.assistant.model.ChatMessage
import com.codex.assistant.model.MessageRole
import com.codex.assistant.model.TimelineAction
import com.codex.assistant.model.TimelineActionCodec
import com.codex.assistant.model.TimelineActionStatus
import com.codex.assistant.model.TimelineNarrativeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TimelineActionConversationBuilderTest {
    private val builder = ConversationTimelineBuilder()

    @Test
    fun `prefers serialized timeline actions over response text heuristics`() {
        val actionPayload = TimelineActionCodec.encode(
            listOf(
                TimelineAction.AppendNarrative(
                    id = "note-1",
                    kind = TimelineNarrativeKind.NOTE,
                    text = "I scanned the file first.",
                    sequence = 1,
                ),
                TimelineAction.UpsertTool(
                    id = "tool-1",
                    name = "read_file",
                    input = "src/App.kt",
                    output = "file loaded",
                    status = TimelineActionStatus.SUCCESS,
                    sequence = 2,
                ),
                TimelineAction.AppendNarrative(
                    id = "result-1",
                    kind = TimelineNarrativeKind.RESULT,
                    text = "The run completed cleanly.",
                    sequence = 3,
                ),
            ),
        )

        val turns = builder.build(
            messages = listOf(
                ChatMessage(
                    id = "user-1",
                    role = MessageRole.USER,
                    content = "Refactor the provider flow",
                    timestamp = 1_000L,
                ),
                ChatMessage(
                    id = "assistant-1",
                    role = MessageRole.ASSISTANT,
                    content = "This legacy body should not drive the ordering anymore.",
                    timestamp = 2_000L,
                    timelineActionsPayload = actionPayload,
                ),
            ),
        )

        assertEquals(1, turns.size)
        val turn = turns.single()
        assertNotNull(turn.userMessage)
        assertEquals(
            listOf(
                TimelineNodeKind.ASSISTANT_NOTE,
                TimelineNodeKind.TOOL_STEP,
                TimelineNodeKind.RESULT,
            ),
            turn.nodes.map { it.kind },
        )
        assertEquals("I scanned the file first.", turn.nodes[0].body)
        assertEquals("read_file", turn.nodes[1].toolName)
        assertEquals("The run completed cleanly.", turn.nodes[2].body)
    }
}
