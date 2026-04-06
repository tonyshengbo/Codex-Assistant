package com.auracode.assistant.provider.claude

import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.settings.AgentSettingsService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClaudeCliProviderTest {
    @Test
    fun `stream returns early error when configured claude path is invalid`() = runBlocking {
        val provider = ClaudeCliProvider(
            settings = providerSettings("/missing/claude"),
            environmentDetector = ClaudeEnvironmentDetector(
                shellEnvironmentLoader = { emptyMap() },
                commonSearchPaths = emptyList(),
                executableResolver = ClaudeExecutableResolver(
                    commonSearchPaths = emptyList(),
                    operatingSystemName = "Linux",
                    pathExt = "",
                ),
            ),
            diagnosticLogger = {},
        )

        val events = provider.stream(
            AgentRequest(
                engineId = "claude",
                prompt = "hello",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        ).toList()

        val error = assertIs<UnifiedEvent.Error>(events.single())
        assertEquals("Configured Claude Code Path is not executable. Update Settings and try again.", error.message)
    }

    @Test
    fun `parser emits narrative and successful completion for plain text stream`() {
        val parser = ClaudeStreamJsonParser(
            turnId = "turn-1",
            collaborationMode = AgentCollaborationMode.DEFAULT,
            diagnosticLogger = {},
        )

        val events = buildList {
            addAll(parser.parseLine("""{"type":"system","subtype":"init","session_id":"sess-1"}"""))
            addAll(
                parser.parseLine(
                    """{"type":"stream_event","event":{"content_block":{"text":"","type":"text"},"index":0,"type":"content_block_start"},"session_id":"sess-1"}""",
                ),
            )
            addAll(
                parser.parseLine(
                    """{"type":"stream_event","event":{"delta":{"text":"Hello","type":"text_delta"},"index":0,"type":"content_block_delta"},"session_id":"sess-1"}""",
                ),
            )
            addAll(
                parser.parseLine(
                    """{"type":"stream_event","event":{"index":0,"type":"content_block_stop"},"session_id":"sess-1"}""",
                ),
            )
            addAll(
                parser.parseLine(
                    """{"type":"result","subtype":"success","is_error":false,"session_id":"sess-1","usage":{"input_tokens":10,"cache_read_input_tokens":5,"cache_creation_input_tokens":0,"output_tokens":2},"modelUsage":{"claude-sonnet-4-6":{"contextWindow":200000}}}""",
                ),
            )
        }

        assertIs<UnifiedEvent.ThreadStarted>(events[0])
        val narrative = events.filterIsInstance<UnifiedEvent.ItemUpdated>().last { it.item.kind == ItemKind.NARRATIVE }
        assertEquals("Hello", narrative.item.text)
        assertEquals(ItemStatus.SUCCESS, narrative.item.status)
        val completed = assertIs<UnifiedEvent.TurnCompleted>(events.last())
        assertEquals(TurnOutcome.SUCCESS, completed.outcome)
        assertEquals("", completed.turnId)
        assertEquals(10, completed.usage?.inputTokens)
        assertEquals(5, completed.usage?.cachedInputTokens)
        assertEquals(2, completed.usage?.outputTokens)
    }

    @Test
    fun `parser maps tool use and tool result into tool call lifecycle`() {
        val parser = ClaudeStreamJsonParser(
            turnId = "turn-1",
            collaborationMode = AgentCollaborationMode.DEFAULT,
            diagnosticLogger = {},
        )

        val events = buildList {
            addAll(parser.parseLine("""{"type":"system","subtype":"init","session_id":"sess-1"}"""))
            addAll(
                parser.parseLine(
                    """{"type":"stream_event","event":{"content_block":{"id":"tool_1","input":{},"name":"Read","type":"tool_use"},"index":0,"type":"content_block_start"},"session_id":"sess-1"}""",
                ),
            )
            addAll(
                parser.parseLine(
                    """{"type":"stream_event","event":{"delta":{"partial_json":"{\"file_path\":\"README.md\"}","type":"input_json_delta"},"index":0,"type":"content_block_delta"},"session_id":"sess-1"}""",
                ),
            )
            addAll(
                parser.parseLine(
                    """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"tool_1","type":"tool_result","content":"# Aura Code"}]},"session_id":"sess-1"}""",
                ),
            )
        }

        val toolEvents = events.filterIsInstance<UnifiedEvent.ItemUpdated>().filter { it.item.kind == ItemKind.TOOL_CALL }
        assertEquals(3, toolEvents.size)
        assertEquals(ItemStatus.RUNNING, toolEvents.first().item.status)
        assertTrue(toolEvents[1].item.text.orEmpty().contains("file_path"))
        assertEquals(ItemStatus.SUCCESS, toolEvents.last().item.status)
        assertEquals("# Aura Code", toolEvents.last().item.text)
    }

    @Test
    fun `parser extracts proposed plan block during plan mode`() {
        val parser = ClaudeStreamJsonParser(
            turnId = "turn-1",
            collaborationMode = AgentCollaborationMode.PLAN,
            diagnosticLogger = {},
        )

        val events = buildList {
            addAll(parser.parseLine("""{"type":"system","subtype":"init","session_id":"sess-1"}"""))
            addAll(
                parser.parseLine(
                    """{"type":"stream_event","event":{"content_block":{"text":"","type":"text"},"index":0,"type":"content_block_start"},"session_id":"sess-1"}""",
                ),
            )
            addAll(
                parser.parseLine(
                    """{"type":"stream_event","event":{"delta":{"text":"<proposed_plan>\n- Step A\n</proposed_plan>","type":"text_delta"},"index":0,"type":"content_block_delta"},"session_id":"sess-1"}""",
                ),
            )
            addAll(
                parser.parseLine(
                    """{"type":"stream_event","event":{"index":0,"type":"content_block_stop"},"session_id":"sess-1"}""",
                ),
            )
        }

        val planUpdate = events.filterIsInstance<UnifiedEvent.ItemUpdated>().firstOrNull { it.item.kind == ItemKind.PLAN_UPDATE }
        assertNotNull(planUpdate)
        assertEquals("- Step A", planUpdate.item.text)
        val runningPlan = events.filterIsInstance<UnifiedEvent.RunningPlanUpdated>().single()
        assertEquals("- Step A", runningPlan.body)
        assertTrue(runningPlan.steps.isEmpty())
    }

    @Test
    fun `parser can emit thread started from result session id when init is absent`() {
        val parser = ClaudeStreamJsonParser(
            turnId = "turn-1",
            collaborationMode = AgentCollaborationMode.DEFAULT,
            diagnosticLogger = {},
        )

        val events = parser.parseLine(
            """{"type":"result","subtype":"success","is_error":false,"session_id":"sess-1","usage":{"input_tokens":1,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,"output_tokens":1},"modelUsage":{"claude-sonnet-4-6":{"contextWindow":200000}}}""",
        )

        assertIs<UnifiedEvent.ThreadStarted>(events.first())
        val completed = assertIs<UnifiedEvent.TurnCompleted>(events.last())
        assertEquals(TurnOutcome.SUCCESS, completed.outcome)
    }

    private fun providerSettings(claudePath: String): AgentSettingsService {
        return AgentSettingsService().apply {
            loadState(
                AgentSettingsService.State(
                    engineExecutablePaths = mutableMapOf(
                        "codex" to "codex",
                        "claude" to claudePath,
                    ),
                ),
            )
        }
    }
}
