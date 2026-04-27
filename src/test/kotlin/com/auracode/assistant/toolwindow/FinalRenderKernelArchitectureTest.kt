package com.auracode.assistant.toolwindow

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Guards the final render-kernel cutover so legacy UI bridges cannot silently return.
 */
class FinalRenderKernelArchitectureTest {
    private val projectRoot: Path = Path.of(System.getProperty("user.dir"))

    /**
     * Verifies that the toolwindow no longer exposes legacy unified or timeline compatibility events.
     */
    @Test
    fun `toolwindow production sources do not expose legacy compatibility events`() {
        val eventsSource = read(
            "src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowEvents.kt",
        )
        val eventHubSource = read(
            "src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowEventHub.kt",
        )
        val conversationStoreSource = read(
            "src/main/kotlin/com/auracode/assistant/toolwindow/conversation/ConversationAreaStore.kt",
        )
        val submissionStoreSource = read(
            "src/main/kotlin/com/auracode/assistant/toolwindow/submission/SubmissionAreaStore.kt",
        )
        val executionStatusStoreSource = read(
            "src/main/kotlin/com/auracode/assistant/toolwindow/execution/ExecutionStatusAreaStore.kt",
        )

        assertFalse(eventsSource.contains("ProviderEventPublished"))
        assertFalse(eventsSource.contains("TimelineMutationApplied"))
        assertFalse(eventsSource.contains("TimelineHistoryLoaded"))
        assertFalse(eventsSource.contains("TurnDiffUpdated"))
        assertFalse(eventHubSource.contains("publishProviderEvent"))
        assertFalse(conversationStoreSource.contains("ConversationActivityItemReducer"))
        assertFalse(conversationStoreSource.contains("TimelineMutationApplied"))
        assertFalse(conversationStoreSource.contains("TimelineHistoryLoaded"))
        assertFalse(submissionStoreSource.contains("appliedTurnDiffs"))
        assertFalse(submissionStoreSource.contains("TurnDiffUpdated"))
        assertFalse(executionStatusStoreSource.contains("ProviderEvent"))
    }

    /**
     * Verifies that the service/provider ingress no longer depends on the deleted session normalizer bridge.
     */
    @Test
    fun `service and provider ingress no longer reference the deleted session normalizer bridge`() {
        val agentChatServiceSource = read(
            "src/main/kotlin/com/auracode/assistant/service/AgentChatService.kt",
        )
        val claudeProviderSource = read(
            "src/main/kotlin/com/auracode/assistant/provider/claude/ClaudeCliProvider.kt",
        )
        val codexProviderSource = read(
            "src/main/kotlin/com/auracode/assistant/provider/codex/CodexRuntimeProvider.kt",
        )

        assertFalse(agentChatServiceSource.contains("onProviderEvent"))
        assertFalse(agentChatServiceSource.contains("ProviderEventSessionEventMapper"))
        assertFalse(claudeProviderSource.contains("ProviderEventSessionEventMapper"))
        assertFalse(codexProviderSource.contains("ProviderEventSessionEventMapper"))
        assertFalse(
            Files.exists(
                projectRoot.resolve(
                    "src/main/kotlin/com/auracode/assistant/session/normalizer/ProviderEventSessionEventMapper.kt",
                ),
            ),
        )
    }

    /** Reads one project file as UTF-8 source text for string-based architecture assertions. */
    private fun read(relativePath: String): String {
        return Files.readString(projectRoot.resolve(relativePath))
    }
}
