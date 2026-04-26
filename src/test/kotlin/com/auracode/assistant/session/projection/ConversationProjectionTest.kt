package com.auracode.assistant.session.projection

import com.auracode.assistant.session.kernel.SessionActivityStatus
import com.auracode.assistant.session.kernel.SessionApprovalRequest
import com.auracode.assistant.session.kernel.SessionApprovalRequestKind
import com.auracode.assistant.session.kernel.SessionCommandKind
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.session.kernel.SessionFileChange
import com.auracode.assistant.session.kernel.SessionMessageRole
import com.auracode.assistant.session.kernel.SessionReducer
import com.auracode.assistant.session.kernel.SessionRunningPlan
import com.auracode.assistant.session.kernel.SessionRunningPlanStep
import com.auracode.assistant.session.kernel.SessionState
import com.auracode.assistant.session.kernel.SessionToolUserInputOption
import com.auracode.assistant.session.kernel.SessionToolUserInputQuestion
import com.auracode.assistant.session.kernel.SessionToolUserInputRequest
import com.auracode.assistant.toolwindow.shared.UiText
import com.auracode.assistant.toolwindow.conversation.TimelineNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies that session projections drive timeline and execution UI models from kernel state.
 */
class ConversationProjectionTest {
    /**
     * Verifies that projected conversation nodes are built from structured kernel entries instead of timeline mappers.
     */
    @Test
    fun `projects structured conversation entries into timeline nodes`() {
        val state = SessionReducer().reduceAll(
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

        val projection = SessionProjectionBuilder().project(state)

        assertEquals(4, projection.conversation.nodes.size)
        assertEquals("Kernel projection online.", assertIs<TimelineNode.MessageNode>(projection.conversation.nodes[0]).text)
        assertEquals("Inspecting repository structure", assertIs<TimelineNode.ReasoningNode>(projection.conversation.nodes[1]).body)

        val commandNode = assertIs<TimelineNode.CommandNode>(projection.conversation.nodes[2])
        assertTrue(commandNode.title.contains("Read"))
        assertTrue(commandNode.title.contains("README.md"))
        assertEquals("README contents", commandNode.outputText)

        val fileChangeNode = assertIs<TimelineNode.FileChangeNode>(projection.conversation.nodes[3])
        assertEquals("/tmp/README.md", fileChangeNode.changes.single().path)
        assertTrue(fileChangeNode.title.contains("README.md"))
    }

    /**
     * Verifies that execution projection emits approval, tool-input, running-plan, and status models independently.
     */
    @Test
    fun `projects execution slice into approval tool input running plan and turn status`() {
        val state = SessionReducer().reduceAll(
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

        val projection = SessionProjectionBuilder().project(state)

        assertEquals(1, projection.execution.approvals.size)
        assertEquals("./gradlew test", projection.execution.approvals.single().body)

        assertEquals(1, projection.execution.toolUserInputs.size)
        assertEquals("sandbox", projection.execution.toolUserInputs.single().questions.single().id)

        assertEquals("turn-1", projection.execution.runningPlan?.turnId)
        assertEquals(2, projection.execution.runningPlan?.steps?.size)
        assertEquals(UiText.Bundle("status.waitingInput"), projection.execution.turnStatus?.label)
    }
}
