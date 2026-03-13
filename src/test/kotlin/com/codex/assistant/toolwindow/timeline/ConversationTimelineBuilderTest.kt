package com.codex.assistant.toolwindow.timeline

import com.codex.assistant.model.ChatMessage
import com.codex.assistant.model.MessageRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.Base64

class ConversationTimelineBuilderTest {
    private val builder = ConversationTimelineBuilder()

    @Test
    fun `builds history turn from legacy structured assistant message`() {
        val turns = builder.build(
            messages = listOf(
                ChatMessage(
                    id = "user-1",
                    role = MessageRole.USER,
                    content = "Refactor the conversation UI",
                    timestamp = 1_000L,
                ),
                ChatMessage(
                    id = "assistant-1",
                    role = MessageRole.ASSISTANT,
                    content = """
                    ### Thinking
                    Need to inspect the current renderer before changing it.

                    ### Response
                    Reworked the timeline renderer and preserved session controls.

                    ### Tools
                    - done | id:tool-1 | read_file | seq:1 | in: src/main/kotlin/com/codex/assistant/toolwindow/AgentToolWindowPanel.kt | out: scanned panel structure

                    ### Commands
                    - command | id:cmd-1 | status:done | seq:2 | cmd: ./gradlew test | cwd: . | exit:0 | out: BUILD SUCCESSFUL
                    """.trimIndent(),
                    timestamp = 2_000L,
                ),
            ),
        )

        assertEquals(1, turns.size)
        val turn = turns.single()
        assertNotNull(turn.userMessage)
        assertEquals(
            listOf(
                TimelineNodeKind.THINKING,
                TimelineNodeKind.TOOL_STEP,
                TimelineNodeKind.COMMAND_STEP,
                TimelineNodeKind.RESULT,
            ),
            turn.nodes.map { it.kind },
        )
        assertFalse(turn.nodes.first().expanded)
        assertEquals(TimelineNodeStatus.SUCCESS, turn.nodes[1].status)
        assertEquals("read_file", turn.nodes[1].toolName)
        assertFalse(turn.nodes[1].expanded)
        assertEquals(TimelineNodeStatus.SUCCESS, turn.nodes[2].status)
        assertEquals("./gradlew test", turn.nodes[2].command)
        assertFalse(turn.nodes[2].expanded)
        assertTrue(turn.nodes.last().expanded)
        assertEquals("Reworked the timeline renderer and preserved session controls.", turn.nodes.last().body)
    }

    @Test
    fun `keeps running thinking expanded for live turn while startup status no longer leaks into footer`() {
        val turns = builder.build(
            messages = listOf(
                ChatMessage(
                    id = "user-1",
                    role = MessageRole.USER,
                    content = "Show live progress",
                    timestamp = 1_000L,
                ),
            ),
            liveTurn = LiveTurnSnapshot(
                statusText = "正在准备会话...",
                thinking = "Need to inspect message grouping before replacing the renderer.",
                tools = listOf(
                    LiveToolTrace(
                        id = "tool-live-1",
                        name = "read_file",
                        input = "src/main/kotlin/com/codex/assistant/toolwindow/AgentToolWindowPanel.kt",
                        output = "",
                        status = TimelineNodeStatus.RUNNING,
                        sequence = 1,
                    ),
                ),
                isRunning = true,
            ),
        )

        assertEquals(1, turns.size)
        val turn = turns.single()
        assertTrue(turn.isRunning)
        assertTrue(turn.nodes.none { it.kind == TimelineNodeKind.ASSISTANT_NOTE && it.body == "正在准备会话..." })
        assertEquals("执行中...", turnStatusText(turn))
        assertEquals(TimelineNodeStatus.RUNNING, turnFooterStatus(turn))
        assertNull(turnDurationMs(turn))
        val thinking = turn.nodes.first { it.kind == TimelineNodeKind.THINKING }
        assertTrue(thinking.expanded)
        assertEquals(TimelineNodeStatus.RUNNING, thinking.status)
        assertEquals(TimelineNodeStatus.RUNNING, turn.nodes.first { it.kind == TimelineNodeKind.TOOL_STEP }.status)
    }

    @Test
    fun `shows waiting footer when live turn has only startup action statuses`() {
        val turns = builder.build(
            messages = listOf(
                ChatMessage(
                    id = "user-1",
                    role = MessageRole.USER,
                    content = "Run the task",
                    timestamp = 1_000L,
                ),
            ),
            liveTurn = LiveTurnSnapshot(
                actions = listOf(
                    com.codex.assistant.model.TimelineAction.AppendNarrative(
                        id = "note-1",
                        kind = com.codex.assistant.model.TimelineNarrativeKind.NOTE,
                        text = "turn.started",
                        sequence = 1,
                    ),
                ),
                isRunning = true,
            ),
        )

        val turn = turns.single()
        assertTrue(turn.nodes.isEmpty(), "startup-only action narrative should not remain visible")
        assertEquals("等待响应中", turnStatusText(turn))
        assertEquals(TimelineNodeStatus.RUNNING, turnFooterStatus(turn))
    }

    @Test
    fun `maps system messages to failure and auxiliary nodes across multiple turns`() {
        val turns = builder.build(
            messages = listOf(
                ChatMessage(
                    id = "user-1",
                    role = MessageRole.USER,
                    content = "First request",
                    timestamp = 1_000L,
                ),
                ChatMessage(
                    id = "assistant-1",
                    role = MessageRole.ASSISTANT,
                    content = "### Response\nDone",
                    timestamp = 2_000L,
                ),
                ChatMessage(
                    id = "user-2",
                    role = MessageRole.USER,
                    content = "Second request",
                    timestamp = 3_000L,
                ),
                ChatMessage(
                    id = "system-1",
                    role = MessageRole.SYSTEM,
                    content = "Error: command exited with code 1",
                    timestamp = 4_000L,
                ),
                ChatMessage(
                    id = "system-2",
                    role = MessageRole.SYSTEM,
                    content = "Applied diff to /tmp/Foo.kt",
                    timestamp = 5_000L,
                ),
            ),
        )

        assertEquals(2, turns.size)
        val secondTurn = turns.last()
        assertEquals(
            listOf(TimelineNodeKind.FAILURE, TimelineNodeKind.SYSTEM_AUX),
            secondTurn.nodes.map { it.kind },
        )
        assertTrue(secondTurn.nodes.first().expanded)
        assertEquals(TimelineNodeStatus.FAILED, secondTurn.nodes.first().status)
        assertEquals("Applied diff to /tmp/Foo.kt", secondTurn.nodes.last().body)
    }

    @Test
    fun `aggressively interleaves legacy response blocks between steps`() {
        val turns = builder.build(
            messages = listOf(
                ChatMessage(
                    id = "user-1",
                    role = MessageRole.USER,
                    content = "Please fix and rerun the command",
                    timestamp = 1_000L,
                ),
                ChatMessage(
                    id = "assistant-1",
                    role = MessageRole.ASSISTANT,
                    content = """
                    ### Response
                    I first inspected the source file to verify the current structure.

                    Then I compiled the project to reproduce the failure.

                    After the failure, I updated the invocation and reran it.

                    The final output is `1 2 3 5 9`.

                    ### Tools
                    - done | id:tool-1 | read_file | seq:1 | in: hello.java | out: inspected hello.java

                    ### Commands
                    - command | id:cmd-1 | status:done | seq:2 | cmd: javac hello.java | cwd: . | exit:0 | out: compilation successful
                    """.trimIndent(),
                    timestamp = 2_000L,
                ),
            ),
        )

        val nodes = turns.single().nodes
        assertEquals(
            listOf(
                TimelineNodeKind.ASSISTANT_NOTE,
                TimelineNodeKind.TOOL_STEP,
                TimelineNodeKind.ASSISTANT_NOTE,
                TimelineNodeKind.COMMAND_STEP,
                TimelineNodeKind.ASSISTANT_NOTE,
                TimelineNodeKind.RESULT,
            ),
            nodes.map { it.kind },
        )
        assertEquals(TimelineNodeOrigin.INFERRED_RESPONSE, nodes.first().origin)
        assertEquals("I first inspected the source file to verify the current structure.", nodes.first().body)
        assertEquals("Then I compiled the project to reproduce the failure.", nodes[2].body)
        assertEquals("After the failure, I updated the invocation and reran it.", nodes[4].body)
        assertEquals("The final output is `1 2 3 5 9`.", nodes.last().body)
    }

    @Test
    fun `uses structured narrative section ordering before falling back to response splitting`() {
        fun encoded(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

        val turns = builder.build(
            messages = listOf(
                ChatMessage(
                    id = "user-1",
                    role = MessageRole.USER,
                    content = "Show me the execution story",
                    timestamp = 1_000L,
                ),
                ChatMessage(
                    id = "assistant-1",
                    role = MessageRole.ASSISTANT,
                    content = """
                    ### Narrative
                    - note | id:note-1 | seq:1 | origin:event | body64:${encoded("I started by scanning the file.")}
                    - note | id:note-2 | seq:3 | origin:event | body64:${encoded("Once the scan was done, I ran the command.")}
                    - result | id:result-1 | seq:5 | origin:event | body64:${encoded("Everything finished cleanly.")}

                    ### Response
                    This paragraph should not be used for ordering because narrative already exists.

                    ### Tools
                    - done | id:tool-1 | read_file | seq:2 | in: hello.java | out: inspected hello.java

                    ### Commands
                    - command | id:cmd-1 | status:done | seq:4 | cmd: javac hello.java | cwd: . | exit:0 | out: compilation successful
                    """.trimIndent(),
                    timestamp = 2_000L,
                ),
            ),
        )

        val nodes = turns.single().nodes
        assertEquals(
            listOf(
                TimelineNodeKind.ASSISTANT_NOTE,
                TimelineNodeKind.TOOL_STEP,
                TimelineNodeKind.ASSISTANT_NOTE,
                TimelineNodeKind.COMMAND_STEP,
                TimelineNodeKind.RESULT,
            ),
            nodes.map { it.kind },
        )
        assertEquals(TimelineNodeOrigin.EVENT, nodes.first().origin)
        assertEquals("I started by scanning the file.", nodes.first().body)
        assertEquals("Once the scan was done, I ran the command.", nodes[2].body)
        assertEquals("Everything finished cleanly.", nodes.last().body)
    }

    @Test
    fun `uses live narrative notes in sequence with command steps`() {
        val turns = builder.build(
            messages = listOf(
                ChatMessage(
                    id = "user-1",
                    role = MessageRole.USER,
                    content = "Execute and explain",
                    timestamp = 1_000L,
                ),
            ),
            liveTurn = LiveTurnSnapshot(
                notes = listOf(
                    LiveNarrativeTrace(
                        id = "note-1",
                        body = "I first checked the source file.",
                        sequence = 1,
                        origin = TimelineNodeOrigin.EVENT,
                    ),
                    LiveNarrativeTrace(
                        id = "note-2",
                        body = "The command succeeded, so I am summarizing the result.",
                        sequence = 3,
                        origin = TimelineNodeOrigin.EVENT,
                    ),
                ),
                commands = listOf(
                    LiveCommandTrace(
                        id = "cmd-1",
                        command = "javac hello.java",
                        cwd = ".",
                        output = "ok",
                        status = TimelineNodeStatus.SUCCESS,
                        sequence = 2,
                    ),
                ),
                isRunning = true,
            ),
        )

        assertEquals(
            listOf(
                TimelineNodeKind.ASSISTANT_NOTE,
                TimelineNodeKind.COMMAND_STEP,
                TimelineNodeKind.ASSISTANT_NOTE,
            ),
            turns.single().nodes.map { it.kind },
        )
    }

    @Test
    fun `adds footer metadata for completed historical turn`() {
        val turns = builder.build(
            messages = listOf(
                ChatMessage(
                    id = "user-1",
                    role = MessageRole.USER,
                    content = "Summarize the run",
                    timestamp = 1_000L,
                ),
                ChatMessage(
                    id = "assistant-1",
                    role = MessageRole.ASSISTANT,
                    content = "### Response\nAll steps completed successfully.",
                    timestamp = 2_500L,
                ),
            ),
        )

        val turn = turns.single()
        assertEquals("已完成", turnStatusText(turn))
        assertEquals(TimelineNodeStatus.SUCCESS, turnFooterStatus(turn))
        assertEquals(1_500L, turnDurationMs(turn))
    }

    private fun turnStatusText(turn: TimelineTurnViewModel): String {
        val field = assertNotNull(
            runCatching { turn.javaClass.getDeclaredField("statusText") }.getOrNull(),
            "TimelineTurnViewModel.statusText should be present",
        )
        field.isAccessible = true
        return field.get(turn) as String
    }

    private fun turnFooterStatus(turn: TimelineTurnViewModel): TimelineNodeStatus {
        val field = assertNotNull(
            runCatching { turn.javaClass.getDeclaredField("footerStatus") }.getOrNull(),
            "TimelineTurnViewModel.footerStatus should be present",
        )
        field.isAccessible = true
        return field.get(turn) as TimelineNodeStatus
    }

    private fun turnDurationMs(turn: TimelineTurnViewModel): Long? {
        val field = assertNotNull(
            runCatching { turn.javaClass.getDeclaredField("durationMs") }.getOrNull(),
            "TimelineTurnViewModel.durationMs should be present",
        )
        field.isAccessible = true
        return field.get(turn) as Long?
    }
}
