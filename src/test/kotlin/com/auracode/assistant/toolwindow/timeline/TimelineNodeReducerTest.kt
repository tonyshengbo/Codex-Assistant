package com.auracode.assistant.toolwindow.timeline

import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TimelineNodeReducerTest {
    private companion object {
        const val OLD_REQUEST_ID = "request-old"
        const val NEW_REQUEST_ID = "request-new"
    }

    @Test
    fun `replace and prepend history produce a single flat node list`() {
        val reducer = TimelineNodeReducer()

        reducer.replaceHistory(
            nodes = listOf(
                TimelineNode.MessageNode(
                    id = "history-3",
                    sourceId = "3",
                    role = MessageRole.USER,
                    text = "third",
                    status = ItemStatus.SUCCESS,
                    timestamp = 3L,
                    turnId = null,
                    cursor = 3L,
                ),
                TimelineNode.MessageNode(
                    id = "history-4",
                    sourceId = "4",
                    role = MessageRole.ASSISTANT,
                    text = "fourth",
                    status = ItemStatus.SUCCESS,
                    timestamp = 4L,
                    turnId = null,
                    cursor = 4L,
                ),
            ),
            oldestCursor = "3",
            hasOlder = true,
        )

        assertTrue(reducer.state.nodes.first() is TimelineNode.LoadMoreNode)
        assertEquals(listOf("third", "fourth"), reducer.state.messageTexts())

        reducer.prependHistory(
            nodes = listOf(
                TimelineNode.MessageNode(
                    id = "history-1",
                    sourceId = "1",
                    role = MessageRole.USER,
                    text = "first",
                    status = ItemStatus.SUCCESS,
                    timestamp = 1L,
                    turnId = null,
                    cursor = 1L,
                ),
                TimelineNode.MessageNode(
                    id = "history-2",
                    sourceId = "2",
                    role = MessageRole.ASSISTANT,
                    text = "second",
                    status = ItemStatus.SUCCESS,
                    timestamp = 2L,
                    turnId = null,
                    cursor = 2L,
                ),
            ),
            oldestCursor = "1",
            hasOlder = false,
        )

        assertFalse(reducer.state.nodes.first() is TimelineNode.LoadMoreNode)
        assertEquals(listOf("first", "second", "third", "fourth"), reducer.state.messageTexts())
    }

    @Test
    fun `local prompt and later turn start share the same message stream`() {
        val reducer = TimelineNodeReducer()

        reducer.accept(
            TimelineMutation.UpsertMessage(
                sourceId = "local-user-1",
                role = MessageRole.USER,
                text = "hello",
                status = ItemStatus.SUCCESS,
            ),
        )
        reducer.accept(
            TimelineMutation.UpsertMessage(
                sourceId = "$NEW_REQUEST_ID:assistant-live",
                role = MessageRole.ASSISTANT,
                text = "w",
                status = ItemStatus.RUNNING,
            ),
        )
        reducer.accept(TimelineMutation.TurnStarted(turnId = "turn_1", threadId = "thread_1"))
        reducer.accept(
            TimelineMutation.UpsertMessage(
                sourceId = "$NEW_REQUEST_ID:assistant-live",
                role = MessageRole.ASSISTANT,
                text = "world",
                status = ItemStatus.RUNNING,
            ),
        )
        reducer.accept(TimelineMutation.TurnCompleted(turnId = "turn_1", outcome = TurnOutcome.SUCCESS))

        val messages = reducer.state.nodes.filterIsInstance<TimelineNode.MessageNode>()
        assertEquals(2, messages.size)
        assertEquals(listOf("hello", "world"), messages.map { it.text })
        assertTrue(messages.all { it.turnId == "turn_1" })
        assertFalse(reducer.state.isRunning)
    }

    @Test
    fun `terminal error finalizes running nodes and appends dedicated error node`() {
        val reducer = TimelineNodeReducer()

        reducer.accept(TimelineMutation.TurnStarted(turnId = "turn_2", threadId = "thread_1"))
        reducer.accept(
            TimelineMutation.UpsertToolCall(
                sourceId = "tool-1",
                title = "shell",
                body = "ls",
                status = ItemStatus.RUNNING,
            ),
        )
        reducer.accept(
            TimelineMutation.UpsertToolCall(
                sourceId = "tool-1",
                title = "shell",
                body = "ls\nBUILD FAILED",
                status = ItemStatus.RUNNING,
            ),
        )
        reducer.accept(TimelineMutation.AppendError(message = "boom"))

        assertEquals(2, reducer.state.nodes.size)
        val activity = assertIs<TimelineNode.ToolCallNode>(reducer.state.nodes.first())
        assertEquals("ls\nBUILD FAILED", activity.body)
        assertEquals(ItemStatus.FAILED, activity.status)
        assertTrue(activity.collapsedSummary.orEmpty().contains("boom"))
        val error = assertIs<TimelineNode.ErrorNode>(reducer.state.nodes.last())
        assertEquals("boom", error.body)
        assertEquals("turn_2", error.turnId)
        assertEquals("boom", reducer.state.latestError)
        assertFalse(reducer.state.isRunning)
    }

    @Test
    fun `terminal error without active turn creates synthetic error node`() {
        val reducer = TimelineNodeReducer()

        reducer.accept(TimelineMutation.AppendError(message = "startup failed"))

        val error = assertIs<TimelineNode.ErrorNode>(reducer.state.nodes.single())
        assertEquals("startup failed", error.body)
        assertEquals(ItemStatus.FAILED, error.status)
        assertTrue(error.turnId.orEmpty().startsWith("local-turn-"))
        assertFalse(reducer.state.isRunning)
    }

    @Test
    fun `user input node updates in place and keeps submitted summary`() {
        val reducer = TimelineNodeReducer()

        reducer.accept(TimelineMutation.TurnStarted(turnId = "turn_user_input_1", threadId = "thread_1"))
        reducer.accept(
            TimelineMutation.UpsertUserInput(
                sourceId = "request-1:item-user-input",
                title = "User Input",
                body = "Waiting for input",
                status = ItemStatus.RUNNING,
            ),
        )
        reducer.accept(
            TimelineMutation.UpsertUserInput(
                sourceId = "request-1:item-user-input",
                title = "User Input",
                body = "Target\n\nReuse existing demo",
                status = ItemStatus.SUCCESS,
            ),
        )

        val node = assertIs<TimelineNode.UserInputNode>(
            reducer.state.nodes.single(),
        )
        assertEquals(ItemStatus.SUCCESS, node.status)
        assertEquals("Target\n\nReuse existing demo", node.body)
        assertTrue(node.collapsedSummary.orEmpty().contains("Reuse existing demo"))
    }

    @Test
    fun `different file change items in same turn append separate activity nodes`() {
        val reducer = TimelineNodeReducer()

        reducer.accept(TimelineMutation.TurnStarted(turnId = "turn_diff_1", threadId = "thread_1"))
        reducer.accept(
            TimelineMutation.UpsertFileChange(
                sourceId = "request-1:item_5",
                title = "File Changes",
                changes = listOf(
                    TimelineFileChange(
                        sourceScopedId = "request-1:item_5:0",
                        path = "/tmp/hello.java",
                        displayName = "hello.java",
                        kind = TimelineFileChangeKind.UPDATE,
                    ),
                ),
                status = ItemStatus.RUNNING,
            ),
        )
        reducer.accept(
            TimelineMutation.UpsertFileChange(
                sourceId = "request-1:item_6",
                title = "File Changes",
                changes = listOf(
                    TimelineFileChange(
                        sourceScopedId = "request-1:item_6:0",
                        path = "/tmp/Util.kt",
                        displayName = "Util.kt",
                        kind = TimelineFileChangeKind.CREATE,
                    ),
                ),
                status = ItemStatus.RUNNING,
            ),
        )

        val activities = reducer.state.nodes.filterIsInstance<TimelineNode.FileChangeNode>()
        assertEquals(2, activities.size)
        assertEquals(
            listOf("hello.java", "Util.kt"),
            activities.map { it.changes.single().displayName },
        )
        assertEquals(null, activities.first().collapsedSummary)
    }

    @Test
    fun `multi file change mutation expands into one node per file with action titles`() {
        val reducer = TimelineNodeReducer()

        reducer.accept(TimelineMutation.TurnStarted(turnId = "turn_diff_split", threadId = "thread_1"))
        reducer.accept(
            TimelineMutation.UpsertFileChange(
                sourceId = "request-1:item_5",
                title = "File Changes",
                changes = listOf(
                    TimelineFileChange(
                        sourceScopedId = "request-1:item_5:0",
                        path = "/tmp/hello.java",
                        displayName = "hello.java",
                        kind = TimelineFileChangeKind.UPDATE,
                        addedLines = 3,
                        deletedLines = 1,
                    ),
                    TimelineFileChange(
                        sourceScopedId = "request-1:item_5:1",
                        path = "/tmp/Util.kt",
                        displayName = "Util.kt",
                        kind = TimelineFileChangeKind.CREATE,
                        addedLines = 8,
                        deletedLines = 0,
                    ),
                ),
                status = ItemStatus.SUCCESS,
            ),
        )

        val activities = reducer.state.nodes.filterIsInstance<TimelineNode.FileChangeNode>()
        assertEquals(2, activities.size)
        assertEquals(
            listOf("Edited hello.java", "Created Util.kt"),
            activities.map { it.title },
        )
        assertEquals(
            listOf("hello.java", "Util.kt"),
            activities.map { it.titleTargetLabel },
        )
        assertEquals(
            listOf("/tmp/hello.java", "/tmp/Util.kt"),
            activities.map { it.titleTargetPath },
        )
        assertEquals(listOf(1, 1), activities.map { it.changes.size })
        assertEquals(listOf(null, null), activities.map { it.collapsedSummary })
    }

    @Test
    fun `tool call collapsed summary prefers result wording over raw body`() {
        val reducer = TimelineNodeReducer()

        reducer.accept(TimelineMutation.TurnStarted(turnId = "turn_tool_1", threadId = "thread_1"))
        reducer.accept(
            TimelineMutation.UpsertToolCall(
                sourceId = "tool-1",
                title = "Read TimelineCommonViews.kt",
                titleTargetLabel = "TimelineCommonViews.kt",
                titleTargetPath = "/tmp/TimelineCommonViews.kt",
                body = "sed -n '1,200p' /tmp/TimelineCommonViews.kt",
                status = ItemStatus.SUCCESS,
            ),
        )

        val activity = assertIs<TimelineNode.ToolCallNode>(reducer.state.nodes.single())
        assertEquals("Opened TimelineCommonViews.kt", activity.collapsedSummary)
    }

    @Test
    fun `context compaction lifecycle stays on a single dedicated node`() {
        val reducer = TimelineNodeReducer()

        reducer.accept(TimelineMutation.TurnStarted(turnId = "turn_context_1", threadId = "thread_1"))
        reducer.accept(
            TimelineMutation.UpsertContextCompaction(
                sourceId = "request-1:ctx-1",
                title = "Context Compaction",
                body = "Compacting context",
                status = ItemStatus.RUNNING,
            ),
        )
        reducer.accept(
            TimelineMutation.UpsertContextCompaction(
                sourceId = "request-1:ctx-1",
                title = "Context Compaction",
                body = "Context compacted",
                status = ItemStatus.SUCCESS,
            ),
        )
        reducer.accept(
            TimelineMutation.UpsertContextCompaction(
                sourceId = "request-1:ctx-1",
                title = "Context Compaction",
                body = "Context compacted",
                status = ItemStatus.SUCCESS,
            ),
        )

        val nodes = reducer.state.nodes.filterIsInstance<TimelineNode.ContextCompactionNode>()
        assertEquals(1, nodes.size)
        assertEquals(ItemStatus.SUCCESS, nodes.single().status)
        assertEquals("Context compacted", nodes.single().body)
    }

    @Test
    fun `engine switch mutation appends a system node without clearing previous messages`() {
        val reducer = TimelineNodeReducer()

        reducer.accept(
            TimelineMutation.UpsertMessage(
                sourceId = "local-user-1",
                role = MessageRole.USER,
                text = "old prompt",
                status = ItemStatus.SUCCESS,
            ),
        )
        reducer.accept(
            TimelineMutation.AppendEngineSwitched(
                sourceId = "engine-switch-1",
                targetEngineLabel = "Claude",
                body = "已切换到 Claude，以下内容将作为新会话继续。",
                timestamp = 123L,
            ),
        )

        assertEquals(2, reducer.state.nodes.size)
        val switchNode = assertIs<TimelineNode.EngineSwitchedNode>(reducer.state.nodes.last())
        assertEquals("Claude", switchNode.targetEngineLabel)
        assertEquals("已切换到 Claude，以下内容将作为新会话继续。", switchNode.body)
    }

    @Test
    fun `assistant answer and activities stay in flat timeline order`() {
        val reducer = TimelineNodeReducer()

        reducer.accept(
            TimelineMutation.UpsertMessage(
                sourceId = "local-user-1",
                role = MessageRole.USER,
                text = "hello",
                status = ItemStatus.SUCCESS,
                turnId = "local-turn-1",
            ),
        )
        reducer.accept(TimelineMutation.TurnStarted(turnId = "turn_1", threadId = "thread_1"))
        reducer.accept(
            TimelineMutation.UpsertReasoning(
                sourceId = "reasoning-1",
                body = "Thinking through the request",
                status = ItemStatus.RUNNING,
            ),
        )
        reducer.accept(
            TimelineMutation.UpsertCommand(
                sourceId = "cmd_1",
                title = "Run command",
                body = "ls",
                status = ItemStatus.RUNNING,
            ),
        )
        reducer.accept(
            TimelineMutation.UpsertMessage(
                sourceId = "assistant-1",
                role = MessageRole.ASSISTANT,
                text = "Here is the answer",
                status = ItemStatus.RUNNING,
            ),
        )

        assertEquals(4, reducer.state.nodes.size)
        assertIs<TimelineNode.MessageNode>(reducer.state.nodes[0])
        val reasoning = assertIs<TimelineNode.ReasoningNode>(reducer.state.nodes[1])
        val command = assertIs<TimelineNode.CommandNode>(reducer.state.nodes[2])
        val answer = assertIs<TimelineNode.MessageNode>(reducer.state.nodes[3])
        assertEquals("Thinking through the request", reasoning.body)
        assertEquals("ls", command.body)
        assertEquals("Here is the answer", answer.text)
    }

    @Test
    fun `turn completed updates flat activity nodes and keeps assistant answer visible`() {
        val reducer = TimelineNodeReducer()

        reducer.accept(TimelineMutation.TurnStarted(turnId = "turn_1", threadId = "thread_1"))
        reducer.accept(
            TimelineMutation.UpsertCommand(
                sourceId = "cmd_1",
                title = "Run command",
                body = "ls",
                status = ItemStatus.RUNNING,
            ),
        )
        reducer.accept(
            TimelineMutation.UpsertMessage(
                sourceId = "assistant-1",
                role = MessageRole.ASSISTANT,
                text = "Finished",
                status = ItemStatus.RUNNING,
            ),
        )

        reducer.accept(TimelineMutation.TurnCompleted(turnId = "turn_1", outcome = TurnOutcome.SUCCESS))

        assertEquals(2, reducer.state.nodes.size)
        val process = assertIs<TimelineNode.CommandNode>(reducer.state.nodes[0])
        val answer = assertIs<TimelineNode.MessageNode>(reducer.state.nodes[1])
        assertEquals(ItemStatus.SUCCESS, process.status)
        assertEquals(null, process.collapsedSummary)
        assertEquals(ItemStatus.SUCCESS, answer.status)
        assertFalse(reducer.state.isRunning)
    }

    @Test
    fun `failed activity without matching turn completion still clears running when no nodes remain running`() {
        val reducer = TimelineNodeReducer()

        reducer.accept(TimelineMutation.TurnStarted(turnId = "turn_parent", threadId = "thread_1"))
        reducer.accept(
            TimelineMutation.UpsertToolCall(
                sourceId = "tool-wait-1",
                title = "Wait",
                body = "Waiting for child agent",
                status = ItemStatus.RUNNING,
            ),
        )

        assertTrue(reducer.state.isRunning)

        reducer.accept(
            TimelineMutation.UpsertToolCall(
                sourceId = "tool-wait-1",
                title = "Wait",
                body = "Child agent failed",
                status = ItemStatus.FAILED,
            ),
        )

        val tool = assertIs<TimelineNode.ToolCallNode>(reducer.state.nodes.single())
        assertEquals(ItemStatus.FAILED, tool.status)
        assertFalse(reducer.state.isRunning)
    }

    @Test
    fun `running command keeps compact running summary and separates command from output`() {
        val reducer = TimelineNodeReducer()

        reducer.accept(TimelineMutation.TurnStarted(turnId = "turn_command_running", threadId = "thread_1"))
        reducer.accept(
            TimelineMutation.UpsertCommand(
                sourceId = "cmd_running",
                title = "Run Gradle Test",
                body = """
                    > Task :prepareTest
                    > Task :test
                """.trimIndent(),
                commandText = "./gradlew --no-daemon test",
                status = ItemStatus.RUNNING,
            ),
        )

        val process = assertIs<TimelineNode.CommandNode>(reducer.state.nodes.single())
        assertEquals("./gradlew --no-daemon test", process.commandText)
        assertEquals("> Task :prepareTest\n> Task :test", process.outputText)
        assertEquals("Working", process.collapsedSummary)
    }

    @Test
    fun `failed command relies on status dot instead of failed summary`() {
        val reducer = TimelineNodeReducer()

        reducer.accept(TimelineMutation.TurnStarted(turnId = "turn_command_failed", threadId = "thread_1"))
        reducer.accept(
            TimelineMutation.UpsertCommand(
                sourceId = "cmd_failed",
                title = "Search java files",
                body = "rg --files -g '*.java'",
                commandText = "rg --files -g '*.java'",
                status = ItemStatus.RUNNING,
            ),
        )

        reducer.accept(TimelineMutation.AppendError(message = "command failed"))

        assertEquals(2, reducer.state.nodes.size)
        val process = assertIs<TimelineNode.CommandNode>(reducer.state.nodes.first())
        assertEquals(ItemStatus.FAILED, process.status)
        assertEquals(null, process.collapsedSummary)
        val error = assertIs<TimelineNode.ErrorNode>(reducer.state.nodes.last())
        assertEquals("command failed", error.body)
    }

    @Test
    fun `continued turn with reused turn id should append a new assistant node instead of overwriting restored history`() {
        val reducer = TimelineNodeReducer()

        reducer.replaceHistory(
            nodes = listOf(
                historyMessage(
                    id = "history-user-1",
                    sourceId = "user-1",
                    role = MessageRole.USER,
                    text = "old question",
                    turnId = "turn_1",
                    cursor = 1L,
                ),
                historyMessage(
                    id = "history-assistant-1",
                    sourceId = "$OLD_REQUEST_ID:assistant-live",
                    role = MessageRole.ASSISTANT,
                    text = "old answer",
                    turnId = "turn_1",
                    cursor = 2L,
                ),
            ),
            oldestCursor = "1",
            hasOlder = false,
        )

        reducer.accept(
            TimelineMutation.UpsertMessage(
                sourceId = "local-user-2",
                role = MessageRole.USER,
                text = "new question",
                status = ItemStatus.SUCCESS,
                turnId = "local-turn-2",
            ),
        )
        reducer.accept(TimelineMutation.TurnStarted(turnId = "turn_1", threadId = "thread_1"))
        reducer.accept(
            TimelineMutation.UpsertMessage(
                sourceId = "$NEW_REQUEST_ID:assistant-live",
                role = MessageRole.ASSISTANT,
                text = "new answer",
                status = ItemStatus.RUNNING,
            ),
        )
        reducer.accept(TimelineMutation.TurnCompleted(turnId = "turn_1", outcome = TurnOutcome.SUCCESS))

        val messages = reducer.state.nodes.filterIsInstance<TimelineNode.MessageNode>()
        assertEquals(
            listOf("old question", "old answer", "new question", "new answer"),
            messages.map { it.text },
        )
    }

    @Test
    fun `assistant delta arriving before repeated turn started must still preserve the previous assistant message`() {
        val reducer = TimelineNodeReducer()

        reducer.replaceHistory(
            nodes = listOf(
                historyMessage(
                    id = "history-user-1",
                    sourceId = "user-1",
                    role = MessageRole.USER,
                    text = "old question",
                    turnId = "turn_1",
                    cursor = 1L,
                ),
                historyMessage(
                    id = "history-assistant-1",
                    sourceId = "$OLD_REQUEST_ID:assistant-live",
                    role = MessageRole.ASSISTANT,
                    text = "old answer",
                    turnId = "turn_1",
                    cursor = 2L,
                ),
            ),
            oldestCursor = "1",
            hasOlder = false,
        )

        reducer.accept(
            TimelineMutation.UpsertMessage(
                sourceId = "local-user-2",
                role = MessageRole.USER,
                text = "new question",
                status = ItemStatus.SUCCESS,
                turnId = "local-turn-2",
            ),
        )
        reducer.accept(
            TimelineMutation.UpsertMessage(
                sourceId = "$NEW_REQUEST_ID:assistant-live",
                role = MessageRole.ASSISTANT,
                text = "new answer",
                status = ItemStatus.RUNNING,
            ),
        )
        reducer.accept(TimelineMutation.TurnStarted(turnId = "turn_1", threadId = "thread_1"))
        reducer.accept(
            TimelineMutation.UpsertMessage(
                sourceId = "$NEW_REQUEST_ID:assistant-live",
                role = MessageRole.ASSISTANT,
                text = "new answer refined",
                status = ItemStatus.RUNNING,
            ),
        )

        val messages = reducer.state.nodes.filterIsInstance<TimelineNode.MessageNode>()
        assertEquals(
            listOf("old question", "old answer", "new question", "new answer refined"),
            messages.map { it.text },
        )
    }

    @Test
    fun `assistant messages around tool append by source id while later deltas still update in place`() {
        val reducer = TimelineNodeReducer()

        reducer.accept(TimelineMutation.TurnStarted(turnId = "turn_message_boundary", threadId = "thread_1"))
        reducer.accept(
            TimelineMutation.UpsertMessage(
                sourceId = "request-1:assistant:msg_before_tool",
                role = MessageRole.ASSISTANT,
                text = "先说明一下。",
                status = ItemStatus.RUNNING,
            ),
        )
        reducer.accept(
            TimelineMutation.UpsertToolCall(
                sourceId = "request-1:tool:tooluse_1",
                title = "Read",
                body = "Input\n\n```json\n{\"file_path\":\"/tmp/demo.txt\"}\n```",
                status = ItemStatus.SUCCESS,
            ),
        )
        reducer.accept(
            TimelineMutation.UpsertMessage(
                sourceId = "request-1:assistant:msg_after_tool",
                role = MessageRole.ASSISTANT,
                text = "再给最终答复。",
                status = ItemStatus.RUNNING,
            ),
        )
        reducer.accept(
            TimelineMutation.UpsertMessage(
                sourceId = "request-1:assistant:msg_after_tool",
                role = MessageRole.ASSISTANT,
                text = "再给最终答复。已完成。",
                status = ItemStatus.SUCCESS,
            ),
        )

        assertEquals(3, reducer.state.nodes.size)
        assertIs<TimelineNode.MessageNode>(reducer.state.nodes[0])
        assertIs<TimelineNode.ToolCallNode>(reducer.state.nodes[1])
        assertIs<TimelineNode.MessageNode>(reducer.state.nodes[2])

        val messages = reducer.state.nodes.filterIsInstance<TimelineNode.MessageNode>()
        assertEquals(2, messages.size)
        assertEquals(
            listOf("先说明一下。", "再给最终答复。已完成。"),
            messages.map { it.text },
        )
        assertEquals(
            listOf("request-1:assistant:msg_before_tool", "request-1:assistant:msg_after_tool"),
            messages.map { it.sourceId },
        )
    }

    private fun TimelineAreaState.messageTexts(): List<String> {
        return nodes.filterIsInstance<TimelineNode.MessageNode>().map { it.text }
    }

    private fun historyMessage(
        id: String,
        sourceId: String,
        role: MessageRole,
        text: String,
        turnId: String,
        cursor: Long,
    ): TimelineNode.MessageNode {
        return TimelineNode.MessageNode(
            id = id,
            sourceId = sourceId,
            role = role,
            text = text,
            status = ItemStatus.SUCCESS,
            timestamp = cursor,
            turnId = turnId,
            cursor = cursor,
        )
    }
}
