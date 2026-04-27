package com.auracode.assistant.toolwindow.submission

import com.auracode.assistant.settings.SavedAgentDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SubmissionPopupContentTest {
    @Test
    fun `slash popup content maps selected suggestion after section headers`() {
        val content = buildSubmissionPopupContent(
            slashSuggestions = listOf(
                SlashSuggestionItem.Command(
                    command = "/plan",
                    title = "/plan",
                    description = "Switch the composer into plan mode.",
                    enabled = true,
                ),
                SlashSuggestionItem.Skill(
                    name = "brainstorming",
                    description = "Explore requirements.",
                ),
            ),
            activeSlashIndex = 1,
            mentionSuggestions = emptyList(),
            activeMentionIndex = 0,
            agentSuggestions = emptyList(),
            activeAgentIndex = 0,
            mode = SubmissionPopupMode.SLASH,
        )

        assertEquals(4, content.rows.size)
        assertEquals(3, content.selectedRowIndex)
        assertIs<SubmissionPopupRow.Header>(content.rows[0])
        assertIs<SubmissionPopupRow.SlashItem>(content.rows[1])
        assertIs<SubmissionPopupRow.Header>(content.rows[2])
        assertIs<SubmissionPopupRow.SlashItem>(content.rows[3])
    }

    @Test
    fun `mention popup content keeps selected row aligned with suggestion index`() {
        val content = buildSubmissionPopupContent(
            slashSuggestions = emptyList(),
            activeSlashIndex = 0,
            mentionSuggestions = listOf(
                MentionSuggestion.File(ContextEntry(path = "/tmp/Foo.kt", displayName = "Foo.kt", tailPath = "")),
                MentionSuggestion.File(ContextEntry(path = "/tmp/Bar.kt", displayName = "Bar.kt", tailPath = "")),
            ),
            activeMentionIndex = 1,
            agentSuggestions = emptyList(),
            activeAgentIndex = 0,
            mode = SubmissionPopupMode.MENTION,
        )

        assertEquals(3, content.rows.size)
        assertEquals(2, content.selectedRowIndex)
        assertIs<SubmissionPopupRow.MentionFileItem>(content.rows[2])
    }

    @Test
    fun `agent popup content keeps selected row aligned with suggestion index`() {
        val content = buildSubmissionPopupContent(
            slashSuggestions = emptyList(),
            activeSlashIndex = 0,
            mentionSuggestions = emptyList(),
            activeMentionIndex = 0,
            agentSuggestions = listOf(
                SavedAgentDefinition(id = "a1", name = "Planner", prompt = "Plan."),
                SavedAgentDefinition(id = "a2", name = "Reviewer", prompt = "Review."),
            ),
            activeAgentIndex = 1,
            mode = SubmissionPopupMode.AGENT,
        )

        assertEquals(2, content.rows.size)
        assertEquals(1, content.selectedRowIndex)
        assertIs<SubmissionPopupRow.AgentItem>(content.rows[1])
    }

    @Test
    fun `mention popup content groups subagents before files`() {
        val content = buildSubmissionPopupContent(
            slashSuggestions = emptyList(),
            activeSlashIndex = 0,
            mentionSuggestions = listOf(
                MentionSuggestion.Agent(
                    SessionSubagentUiModel(
                        threadId = "thread-review-1",
                        displayName = "Review Agent",
                        mentionSlug = "review-agent",
                        status = SessionSubagentStatus.ACTIVE,
                        statusText = "active",
                        summary = "Reviewing",
                        updatedAt = 10L,
                    ),
                ),
                MentionSuggestion.File(
                    ContextEntry(path = "/tmp/App.kt", displayName = "App.kt", tailPath = "tmp"),
                ),
            ),
            activeMentionIndex = 0,
            agentSuggestions = emptyList(),
            activeAgentIndex = 0,
            mode = SubmissionPopupMode.MENTION,
        )

        assertEquals(4, content.rows.size)
        assertEquals(1, content.selectedRowIndex)
        assertIs<SubmissionPopupRow.Header>(content.rows[0])
        assertIs<SubmissionPopupRow.MentionAgentItem>(content.rows[1])
        assertIs<SubmissionPopupRow.Header>(content.rows[2])
        assertIs<SubmissionPopupRow.MentionFileItem>(content.rows[3])
    }
}
