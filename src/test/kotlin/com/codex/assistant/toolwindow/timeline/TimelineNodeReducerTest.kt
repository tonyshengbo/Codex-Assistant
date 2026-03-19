package com.codex.assistant.toolwindow.timeline

import com.codex.assistant.model.MessageRole
import com.codex.assistant.protocol.ItemStatus
import com.codex.assistant.protocol.TurnOutcome
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
            oldestCursor = 3L,
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
            oldestCursor = 1L,
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
    fun `activity nodes update in place and failed turn marks running nodes failed`() {
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
        reducer.accept(TimelineMutation.Error(message = "boom"))

        val activity = assertIs<TimelineNode.ToolCallNode>(reducer.state.nodes.single())
        assertEquals("ls\nBUILD FAILED", activity.body)
        assertEquals(ItemStatus.FAILED, activity.status)
        assertEquals("boom", reducer.state.latestError)
        assertFalse(reducer.state.isRunning)
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
            oldestCursor = 1L,
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
            oldestCursor = 1L,
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
