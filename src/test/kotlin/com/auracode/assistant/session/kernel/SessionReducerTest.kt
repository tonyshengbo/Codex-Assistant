package com.auracode.assistant.session.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies that the session reducer builds feature-organized state from domain events.
 */
class SessionReducerTest {
    /**
     * Verifies that the reducer captures runtime, conversation, and execution state in one session model.
     */
    @Test
    fun `reducer applies core session domain events into feature state`() {
        val reducer = SessionReducer()
        val state = reducer.reduceAll(
            initialState = SessionState.empty(
                sessionId = "session-1",
                engineId = "codex",
            ),
            events = sampleDomainEvents(),
        )

        assertEquals("thread-1", state.runtime.activeThreadId)
        assertEquals("turn-1", state.runtime.activeTurnId)
        assertEquals(SessionRunStatus.IDLE, state.runtime.runStatus)
        assertEquals(SessionTurnOutcome.SUCCESS, state.runtime.lastOutcome)

        assertEquals(listOf("message-1", "command-1", "tool-1", "approval-1", "input-1"), state.conversation.order)

        val message = assertIs<SessionConversationEntry.Message>(
            state.conversation.entries.getValue("message-1"),
        )
        assertEquals(SessionMessageRole.ASSISTANT, message.role)
        assertEquals("Kernel bootstrapped.", message.text)

        val command = assertIs<SessionConversationEntry.Command>(
            state.conversation.entries.getValue("command-1"),
        )
        assertEquals(SessionActivityStatus.SUCCESS, command.status)
        assertEquals(SessionCommandKind.SHELL, command.commandKind)
        assertEquals("git status --short", command.command)
        assertEquals("/tmp/project", command.cwd)
        assertEquals("Working tree clean.", command.outputText)

        val tool = assertIs<SessionConversationEntry.Tool>(
            state.conversation.entries.getValue("tool-1"),
        )
        assertEquals(SessionActivityStatus.RUNNING, tool.status)
        assertEquals(SessionToolKind.FILE_READ, tool.toolKind)
        assertEquals("Read", tool.toolName)
        assertEquals("Inspecting README.md", tool.summary)

        val approval = assertIs<SessionConversationEntry.Approval>(
            state.conversation.entries.getValue("approval-1"),
        )
        assertEquals(SessionApprovalRequestKind.COMMAND, approval.request.kind)
        assertEquals("session.execution.approval.runCommand", approval.request.titleKey)

        val userInput = assertIs<SessionConversationEntry.ToolUserInput>(
            state.conversation.entries.getValue("input-1"),
        )
        assertEquals("input-1", userInput.request.requestId)
        assertEquals(1, userInput.request.questions.size)
        assertEquals("sandbox", userInput.request.questions.single().id)
        assertTrue(state.execution.approvalRequests.isEmpty())
        assertTrue(state.execution.toolUserInputs.isEmpty())

        assertTrue(state.history.loadedHistory)
    }
}

/**
 * Builds a representative sequence of session domain events for reducer and kernel tests.
 */
internal fun sampleDomainEvents(): List<SessionDomainEvent> {
    return listOf(
        SessionDomainEvent.ThreadStarted(
            threadId = "thread-1",
        ),
        SessionDomainEvent.TurnStarted(
            turnId = "turn-1",
            threadId = "thread-1",
        ),
        SessionDomainEvent.MessageAppended(
            messageId = "message-1",
            turnId = "turn-1",
            role = SessionMessageRole.ASSISTANT,
            text = "Kernel bootstrapped.",
        ),
        SessionDomainEvent.CommandUpdated(
            itemId = "command-1",
            turnId = "turn-1",
            status = SessionActivityStatus.SUCCESS,
            commandKind = SessionCommandKind.SHELL,
            command = "git status --short",
            cwd = "/tmp/project",
            outputText = "Working tree clean.",
        ),
        SessionDomainEvent.ToolUpdated(
            itemId = "tool-1",
            turnId = "turn-1",
            status = SessionActivityStatus.RUNNING,
            toolKind = SessionToolKind.FILE_READ,
            toolName = "Read",
            summary = "Inspecting README.md",
        ),
        SessionDomainEvent.ApprovalRequested(
            request = SessionApprovalRequest(
                requestId = "approval-1",
                turnId = "turn-1",
                itemId = "command-1",
                kind = SessionApprovalRequestKind.COMMAND,
                titleKey = "session.execution.approval.runCommand",
                body = "git status --short",
            ),
        ),
        SessionDomainEvent.ToolUserInputRequested(
            request = SessionToolUserInputRequest(
                requestId = "input-1",
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "tool-1",
                questions = listOf(
                    SessionToolUserInputQuestion(
                        id = "sandbox",
                        headerKey = "session.execution.input.sandbox.header",
                        promptKey = "session.execution.input.sandbox.prompt",
                        options = listOf(
                            SessionToolUserInputOption(
                                label = "workspace-write",
                                description = "Only allow workspace writes",
                            ),
                            SessionToolUserInputOption(
                                label = "danger-full-access",
                                description = "Allow unrestricted access",
                            ),
                        ),
                    ),
                ),
            ),
        ),
        SessionDomainEvent.TurnCompleted(
            turnId = "turn-1",
            outcome = SessionTurnOutcome.SUCCESS,
        ),
    )
}
