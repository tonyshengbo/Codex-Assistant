package com.codex.assistant.provider

import com.codex.assistant.model.EngineEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class CliStructuredEventParserTest {
    @Test
    fun `parses codex thread started event as session ready`() {
        val line = """{"type":"thread.started","thread_id":"019ce1dc-0150-7c82-b666-00b68f3024ea"}"""

        val event = CliStructuredEventParser.parseCodexLine(line)

        val session = assertIs<EngineEvent.SessionReady>(event)
        assertEquals("019ce1dc-0150-7c82-b666-00b68f3024ea", session.sessionId)
    }

    @Test
    fun `parses command proposal from codex json event`() {
        val line = """{"type":"command.proposal","command":"npm test","cwd":"/tmp/project"}"""

        val event = CliStructuredEventParser.parseCodexLine(line)

        val proposal = assertIs<EngineEvent.CommandProposal>(event)
        assertEquals("npm test", proposal.command)
        assertEquals("/tmp/project", proposal.cwd)
    }

    @Test
    fun `parses diff proposal from codex json event`() {
        val line = """{"type":"patch.proposal","file_path":"src/App.kt","new_content":"class App"}"""

        val event = CliStructuredEventParser.parseCodexLine(line)

        val proposal = assertIs<EngineEvent.DiffProposal>(event)
        assertEquals("src/App.kt", proposal.filePath)
        assertEquals("class App", proposal.newContent)
    }

    @Test
    fun `parses item level command proposal`() {
        val line = """{"type":"item.emitted","item":{"type":"command_proposal","command":"./gradlew test","cwd":"."}}"""

        val event = CliStructuredEventParser.parseCodexLine(line)

        val proposal = assertIs<EngineEvent.CommandProposal>(event)
        assertEquals("./gradlew test", proposal.command)
        assertEquals(".", proposal.cwd)
    }

    @Test
    fun `keeps tool events unaffected`() {
        val line = """{"type":"tool.call","tool_name":"shell","input":"ls"}"""

        val event = CliStructuredEventParser.parseCodexLine(line)

        assertNotNull(event)
        assertIs<EngineEvent.ToolCallStarted>(event)
    }

    @Test
    fun `parses turn completed usage snapshot`() {
        val line = """{"type":"turn.completed","usage":{"input_tokens":116986,"cached_input_tokens":93440,"output_tokens":3202}}"""

        val event = CliStructuredEventParser.parseCodexLine(line)

        val usage = assertIs<EngineEvent.TurnUsage>(event)
        assertEquals(116986, usage.inputTokens)
        assertEquals(93440, usage.cachedInputTokens)
        assertEquals(3202, usage.outputTokens)
    }
}
