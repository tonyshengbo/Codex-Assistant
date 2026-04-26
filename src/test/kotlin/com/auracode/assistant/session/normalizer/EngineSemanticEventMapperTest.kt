package com.auracode.assistant.session.normalizer

import com.auracode.assistant.provider.claude.ClaudeConversationEvent
import com.auracode.assistant.provider.claude.semantic.ClaudeSemanticEventExtractor
import com.auracode.assistant.provider.codex.semantic.CodexSemanticEventExtractor
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.UnifiedApprovalRequest
import com.auracode.assistant.protocol.UnifiedApprovalRequestKind
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedFileChange
import com.auracode.assistant.protocol.UnifiedItem
import com.auracode.assistant.protocol.UnifiedRunningPlanStep
import com.auracode.assistant.protocol.UnifiedToolUserInputOption
import com.auracode.assistant.protocol.UnifiedToolUserInputPrompt
import com.auracode.assistant.protocol.UnifiedToolUserInputQuestion
import com.auracode.assistant.session.kernel.SessionApprovalRequestKind
import com.auracode.assistant.session.kernel.SessionCommandKind
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.session.kernel.SessionToolKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies that provider semantic records normalize into shared session domain events.
 */
class EngineSemanticEventMapperTest {
    private val mapper = EngineSemanticEventMapper()

    /**
     * Verifies that Claude read tool calls normalize into structured read-file command events.
     */
    @Test
    fun `normalizes claude read tool call into command event with structured kind`() {
        val records = ClaudeSemanticEventExtractor().extract(
            ClaudeConversationEvent.ToolCallUpdated(
                toolUseId = "tool-1",
                toolName = "Read",
                inputJson = """{"file_path":"/tmp/demo.txt"}""",
                outputText = "File does not exist",
                isError = true,
                completed = true,
            ),
        )

        val event = assertIs<SessionDomainEvent.CommandUpdated>(
            mapper.map(records.single()).single(),
        )
        assertEquals(SessionCommandKind.READ_FILE, event.commandKind)
        assertEquals("cat /tmp/demo.txt", event.command)
        assertEquals(com.auracode.assistant.session.kernel.SessionActivityStatus.FAILED, event.status)
    }

    /**
     * Verifies that Claude edit tool calls normalize into structured file-change events.
     */
    @Test
    fun `normalizes claude edit tool call into structured file change event`() {
        val records = ClaudeSemanticEventExtractor().extract(
            ClaudeConversationEvent.ToolCallUpdated(
                toolUseId = "tool-2",
                toolName = "Edit",
                inputJson = """{"file_path":"/tmp/README.md","old_string":"old","new_string":"new"}""",
                outputText = "Updated /tmp/README.md",
                isError = false,
                completed = true,
            ),
        )

        val event = assertIs<SessionDomainEvent.FileChangesUpdated>(
            mapper.map(records.single()).single(),
        )
        assertEquals("update /tmp/README.md", event.summary)
        assertEquals("/tmp/README.md", event.changes.single().path)
        assertEquals("update", event.changes.single().kind)
    }

    /**
     * Verifies that unified tool items normalize into shared tool kinds instead of UI-only labels.
     */
    @Test
    fun `normalizes codex tool item into shared tool kind`() {
        val records = CodexSemanticEventExtractor().extract(
            UnifiedEvent.ItemUpdated(
                item = UnifiedItem(
                    id = "tool-3",
                    kind = ItemKind.TOOL_CALL,
                    status = ItemStatus.RUNNING,
                    name = "Read",
                    text = "Inspecting README.md",
                ),
            ),
        )

        val event = assertIs<SessionDomainEvent.ToolUpdated>(
            mapper.map(records.single()).single(),
        )
        assertEquals(SessionToolKind.FILE_READ, event.toolKind)
        assertEquals("Read", event.toolName)
    }

    /**
     * Verifies that approval and tool-input prompts normalize into explicit execution events.
     */
    @Test
    fun `normalizes codex approval and tool user input into explicit execution events`() {
        val approvalEvents = CodexSemanticEventExtractor().extract(
            UnifiedEvent.ApprovalRequested(
                request = UnifiedApprovalRequest(
                    requestId = "approval-1",
                    turnId = "turn-1",
                    itemId = "diff-1",
                    kind = UnifiedApprovalRequestKind.FILE_CHANGE,
                    title = "Apply file changes",
                    body = "update /tmp/design.md",
                    fileChanges = listOf(
                        UnifiedFileChange(
                            sourceScopedId = "diff-1:0",
                            path = "/tmp/design.md",
                            kind = "update",
                        ),
                    ),
                ),
            ),
        )
        val approvalEvent = assertIs<SessionDomainEvent.ApprovalRequested>(
            mapper.map(approvalEvents.single()).single(),
        )
        assertEquals(SessionApprovalRequestKind.FILE_CHANGE, approvalEvent.request.kind)
        assertTrue(approvalEvent.request.body.contains("update /tmp/design.md"))

        val toolInputEvents = CodexSemanticEventExtractor().extract(
            UnifiedEvent.ToolUserInputRequested(
                prompt = UnifiedToolUserInputPrompt(
                    requestId = "input-1",
                    threadId = "thread-1",
                    turnId = "turn-1",
                    itemId = "tool-1",
                    questions = listOf(
                        UnifiedToolUserInputQuestion(
                            id = "sandbox",
                            header = "Sandbox",
                            question = "Choose a sandbox mode",
                            options = listOf(
                                UnifiedToolUserInputOption(
                                    label = "workspace-write",
                                    description = "Only allow workspace writes",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val toolInputEvent = assertIs<SessionDomainEvent.ToolUserInputRequested>(
            mapper.map(toolInputEvents.single()).single(),
        )
        assertEquals("sandbox", toolInputEvent.request.questions.single().id)
        assertEquals("Sandbox", toolInputEvent.request.questions.single().headerKey)
    }

    /**
     * Verifies that provider running-plan payloads normalize into explicit session plan events.
     */
    @Test
    fun `normalizes codex running plan update into explicit session plan event`() {
        val records = CodexSemanticEventExtractor().extract(
            UnifiedEvent.RunningPlanUpdated(
                threadId = "thread-1",
                turnId = "turn-1",
                explanation = "Finish normalization",
                steps = listOf(
                    UnifiedRunningPlanStep(
                        step = "Add semantic records",
                        status = "completed",
                    ),
                    UnifiedRunningPlanStep(
                        step = "Map provider events into session domain events",
                        status = "in_progress",
                    ),
                ),
                body = "- [completed] Add semantic records\n- [in_progress] Map provider events into session domain events",
            ),
        )

        val event = assertIs<SessionDomainEvent.RunningPlanUpdated>(
            mapper.map(records.single()).single(),
        )
        assertEquals("turn-1", event.plan.turnId)
        assertEquals(2, event.plan.steps.size)
        assertEquals("Add semantic records", event.plan.steps.first().step)
    }
}
