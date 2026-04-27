package com.auracode.assistant.session.projection

import com.auracode.assistant.session.kernel.SessionActivityStatus
import com.auracode.assistant.session.kernel.SessionApprovalRequest
import com.auracode.assistant.session.kernel.SessionApprovalRequestKind
import com.auracode.assistant.session.kernel.SessionCommandKind
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.session.kernel.SessionFileChange
import com.auracode.assistant.session.kernel.SessionMessageRole
import com.auracode.assistant.session.kernel.SessionStateReducer
import com.auracode.assistant.session.kernel.SessionRunningPlan
import com.auracode.assistant.session.kernel.SessionRunningPlanStep
import com.auracode.assistant.session.kernel.SessionState
import com.auracode.assistant.session.kernel.SessionToolUserInputOption
import com.auracode.assistant.session.kernel.SessionToolUserInputQuestion
import com.auracode.assistant.session.kernel.SessionToolUserInputRequest
import com.auracode.assistant.toolwindow.shared.UiText
import com.auracode.assistant.toolwindow.conversation.ConversationActivityItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies that session projections drive timeline and execution UI models from kernel state.
 */
class ConversationUiProjectionTest {
    /**
     * Verifies that projected conversation nodes are built from structured kernel entries instead of timeline mappers.
     */
    @Test
    fun `projects structured conversation entries into timeline nodes`() {
        val state = SessionStateReducer().reduceAll(
            initialState = SessionState.empty(
                sessionId = "session-1",
                engineId = "codex",
            ),
            events = listOf(
                SessionDomainEvent.TurnStarted(
                    turnId = "turn-1",
                    threadId = "thread-1",
                    startedAtMs = 1L,
                ),
                SessionDomainEvent.MessageAppended(
                    messageId = "message-1",
                    turnId = "turn-1",
                    role = SessionMessageRole.ASSISTANT,
                    text = "Kernel projection online.",
                ),
                SessionDomainEvent.ReasoningUpdated(
                    itemId = "reasoning-1",
                    turnId = "turn-1",
                    status = SessionActivityStatus.RUNNING,
                    text = "Inspecting repository structure",
                ),
                SessionDomainEvent.CommandUpdated(
                    itemId = "command-1",
                    turnId = "turn-1",
                    status = SessionActivityStatus.SUCCESS,
                    commandKind = SessionCommandKind.READ_FILE,
                    command = "cat /tmp/README.md",
                    cwd = "/tmp",
                    outputText = "README contents",
                ),
                SessionDomainEvent.FileChangesUpdated(
                    itemId = "diff-1",
                    turnId = "turn-1",
                    status = SessionActivityStatus.SUCCESS,
                    summary = "update /tmp/README.md",
                    changes = listOf(
                        SessionFileChange(
                            path = "/tmp/README.md",
                            kind = "update",
                            summary = "update /tmp/README.md",
                        ),
                    ),
                ),
            ),
        )

        val projection = SessionUiProjectionBuilder().project(state)

        assertEquals(4, projection.conversation.nodes.size)
        assertEquals("Kernel projection online.", assertIs<ConversationActivityItem.MessageNode>(projection.conversation.nodes[0]).text)
        assertEquals("Inspecting repository structure", assertIs<ConversationActivityItem.ReasoningNode>(projection.conversation.nodes[1]).body)

        val commandNode = assertIs<ConversationActivityItem.CommandNode>(projection.conversation.nodes[2])
        assertTrue(commandNode.title.contains("Read"))
        assertTrue(commandNode.title.contains("README.md"))
        assertEquals("README contents", commandNode.outputText)

        val fileChangeNode = assertIs<ConversationActivityItem.FileChangeNode>(projection.conversation.nodes[3])
        assertEquals("/tmp/README.md", fileChangeNode.changes.single().path)
        assertTrue(fileChangeNode.title.contains("README.md"))
    }

    /**
     * Verifies that execution projection emits approval, tool-input, and status models independently.
     */
    @Test
    fun `projects execution slice into approval tool input and turn status`() {
        val state = SessionStateReducer().reduceAll(
            initialState = SessionState.empty(
                sessionId = "session-1",
                engineId = "codex",
            ),
            events = listOf(
                SessionDomainEvent.TurnStarted(
                    turnId = "turn-1",
                    threadId = "thread-1",
                    startedAtMs = 10L,
                ),
                SessionDomainEvent.ApprovalRequested(
                    request = SessionApprovalRequest(
                        requestId = "approval-1",
                        turnId = "turn-1",
                        itemId = "command-1",
                        kind = SessionApprovalRequestKind.COMMAND,
                        titleKey = "session.execution.approval.runCommand",
                        body = "./gradlew test",
                        command = "./gradlew test",
                        cwd = "/workspace",
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
                                headerKey = "Sandbox",
                                promptKey = "Choose a sandbox mode",
                                options = listOf(
                                    SessionToolUserInputOption(
                                        label = "workspace-write",
                                        description = "Only allow workspace writes",
                                    ),
                                ),
                                isOther = false,
                                isSecret = false,
                            ),
                        ),
                    ),
                ),
                SessionDomainEvent.RunningPlanUpdated(
                    plan = SessionRunningPlan(
                        turnId = "turn-1",
                        explanation = "Finish projection migration",
                        steps = listOf(
                            SessionRunningPlanStep(
                                step = "Build session projection",
                                status = "completed",
                            ),
                            SessionRunningPlanStep(
                                step = "Replace timeline mapper usage",
                                status = "in_progress",
                            ),
                        ),
                        body = "- [completed] Build session projection\n- [in_progress] Replace timeline mapper usage",
                    ),
                ),
            ),
        )

        val projection = SessionUiProjectionBuilder().project(state)

        assertEquals(1, projection.execution.approvals.size)
        assertEquals("./gradlew test", projection.execution.approvals.single().body)

        assertEquals(1, projection.execution.toolUserInputs.size)
        assertEquals("sandbox", projection.execution.toolUserInputs.single().questions.single().id)

        assertEquals(UiText.Bundle("status.waitingInput"), projection.execution.turnStatus?.label)
    }

    /**
     * Verifies that running plans project into dedicated timeline plan nodes.
     */
    @Test
    fun `projects running plan into conversation timeline plan node`() {
        val state = SessionStateReducer().reduceAll(
            initialState = SessionState.empty(
                sessionId = "session-1",
                engineId = "codex",
            ),
            events = listOf(
                SessionDomainEvent.TurnStarted(
                    turnId = "turn-1",
                    threadId = "thread-1",
                    startedAtMs = 10L,
                ),
                SessionDomainEvent.RunningPlanUpdated(
                    plan = SessionRunningPlan(
                        turnId = "turn-1",
                        explanation = "Finish projection migration",
                        steps = listOf(
                            SessionRunningPlanStep(
                                step = "Build session projection",
                                status = "completed",
                            ),
                            SessionRunningPlanStep(
                                step = "Replace timeline mapper usage",
                                status = "in_progress",
                            ),
                        ),
                        body = "- [completed] Build session projection\n- [in_progress] Replace timeline mapper usage",
                    ),
                ),
                SessionDomainEvent.TurnCompleted(
                    turnId = "turn-1",
                    outcome = com.auracode.assistant.session.kernel.SessionTurnOutcome.SUCCESS,
                ),
            ),
        )

        val projection = SessionUiProjectionBuilder().project(state)

        val node = assertIs<ConversationActivityItem.PlanNode>(projection.conversation.nodes.single())
        assertEquals("Plan", node.title)
        assertEquals("- [completed] Build session projection\n- [in_progress] Replace timeline mapper usage", node.body)
        assertEquals(com.auracode.assistant.protocol.ItemStatus.SUCCESS, node.status)
    }

    /**
     * Verifies that retryable provider errors stay visible in conversation while keeping the turn running.
     */
    @Test
    fun `projects non terminal errors into conversation without ending run state`() {
        val state = SessionStateReducer().reduceAll(
            initialState = SessionState.empty(
                sessionId = "session-1",
                engineId = "claude",
            ),
            events = listOf(
                SessionDomainEvent.TurnStarted(
                    turnId = "turn-1",
                    threadId = "thread-1",
                    startedAtMs = 10L,
                ),
                SessionDomainEvent.ErrorAppended(
                    itemId = "error-1",
                    turnId = "turn-1",
                    message = "Reconnecting... 3/5",
                    terminal = false,
                ),
            ),
        )

        val projection = SessionUiProjectionBuilder().project(state)
        val errorNode = assertIs<ConversationActivityItem.ErrorNode>(projection.conversation.nodes.single())

        assertEquals("Reconnecting... 3/5", errorNode.body)
        assertTrue(projection.conversation.isRunning)
        assertEquals("Reconnecting... 3/5", projection.conversation.latestError)
    }
}
