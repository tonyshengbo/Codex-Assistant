package com.auracode.assistant.provider.claude

import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.provider.diagnostics.ProviderDiagnosticFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies that the recorded Claude diagnostic fixture still replays into the expected
 * parser, accumulator, and unified-event outputs.
 */
class ClaudeDiagnosticReplayTest {
    /**
     * Ensures the recorded Claude diagnostic stream fixture exposes raw lines in order.
     */
    @Test
    fun `loads claude diagnostic raw fixture from classpath`() {
        val fixture = ProviderDiagnosticFixture.load("/provider/claude/claude-diagnostic-stream.jsonl")

        assertTrue(fixture.lines.isNotEmpty())
    }

    /**
     * Replays the recorded Claude diagnostic stream through the parser, accumulator, and mapper.
     */
    @Test
    fun `replays claude diagnostic fixture into expected unified events`() {
        val events = replayFixture(
            fixture = ProviderDiagnosticFixture.load("/provider/claude/claude-diagnostic-stream.jsonl"),
        )

        assertTrue(events.any { event ->
            event is UnifiedEvent.ItemUpdated &&
                event.item.kind == ItemKind.COMMAND_EXEC &&
                event.item.status == ItemStatus.FAILED &&
                event.item.text?.contains("File does not exist") == true
        })
        assertTrue(events.any { event ->
            event is UnifiedEvent.ItemUpdated &&
                event.item.kind == ItemKind.NARRATIVE &&
                event.item.name == "reasoning" &&
                event.item.text == "Inspecting repository structure"
        })
        assertTrue(events.any { event ->
            event is UnifiedEvent.ItemUpdated &&
                event.item.kind == ItemKind.NARRATIVE &&
                event.item.name == "message" &&
                event.item.text == "工程已全部创建完毕。"
        })

        val finalUsage = events.filterIsInstance<UnifiedEvent.ThreadTokenUsageUpdated>().last()
        assertEquals(200000, finalUsage.contextWindow)
        assertEquals(12125, finalUsage.outputTokens)

        val completed = assertIs<UnifiedEvent.TurnCompleted>(events.last())
        assertEquals(TurnOutcome.SUCCESS, completed.outcome)
    }

    /**
     * Replays one diagnostic fixture into unified events using the production Claude replay chain.
     */
    private fun replayFixture(fixture: ProviderDiagnosticFixture): List<UnifiedEvent> {
        val parser = ClaudeStreamEventParser()
        val accumulator = ClaudeStreamAccumulator()
        val mapper = ClaudeUnifiedEventMapper(
            request = AgentRequest(
                requestId = "req-claude-diagnostic",
                engineId = "claude",
                model = "claude-sonnet-4-6",
                prompt = "Replay Claude diagnostic fixture",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        )

        return fixture.lines.flatMap { line ->
            parser.parse(line)
                ?.let(accumulator::accumulate)
                ?.flatMap(mapper::map)
                .orEmpty()
        }
    }
}
