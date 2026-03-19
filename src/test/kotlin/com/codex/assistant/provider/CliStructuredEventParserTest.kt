package com.codex.assistant.provider

import com.codex.assistant.model.EngineEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliStructuredEventParserTest {
    @Test
    fun `parses slash-style turn started event`() {
        val line = """{"type":"turn/started","turn_id":"tu_42","thread_id":"th_1"}"""

        val event = CliStructuredEventParser.parseCodexLine(line)

        val started = assertIs<EngineEvent.TurnStarted>(event)
        assertEquals("tu_42", started.turnId)
        assertEquals("th_1", started.threadId)
    }

    @Test
    fun `parses turn started without turn id using fallback id`() {
        val line = """{"type":"turn.started"}"""

        val event = CliStructuredEventParser.parseCodexLine(line)

        val started = assertIs<EngineEvent.TurnStarted>(event)
        assertTrue(started.turnId.startsWith("turn-fallback-"))
    }

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
    fun `parses diff proposal from codex json event with change list`() {
        val line = """{"type":"patch.proposal","id":"item_patch_1","changes":[{"path":"src/App.kt","kind":"update","new_content":"class App"}]}"""

        val event = CliStructuredEventParser.parseCodexLine(line)

        val proposal = assertIs<EngineEvent.DiffProposal>(event)
        assertEquals("item_patch_1", proposal.itemId)
        assertEquals(1, proposal.changes.size)
        assertEquals("src/App.kt", proposal.changes.single().path)
        assertEquals("update", proposal.changes.single().kind)
        assertEquals("class App", proposal.changes.single().newContent)
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

    @Test
    fun `parses item completed tool failure status as error result`() {
        val line = """{"type":"item.completed","item":{"type":"function_call","id":"item_1","name":"shell","input":"./gradlew test","status":"failed","output":"BUILD FAILED"}}"""

        val event = CliStructuredEventParser.parseCodexLine(line)

        val tool = assertIs<EngineEvent.ToolCallFinished>(event)
        assertEquals("item_1", tool.callId)
        assertEquals("shell", tool.name)
        assertTrue(tool.isError)
    }

    @Test
    fun `parses item completed tool success status as non error result`() {
        val line = """{"type":"item.completed","item":{"type":"function_call","id":"item_2","name":"shell","input":"./gradlew test","status":"completed","output":"BUILD SUCCESSFUL"}}"""

        val event = CliStructuredEventParser.parseCodexLine(line)

        val tool = assertIs<EngineEvent.ToolCallFinished>(event)
        assertEquals("item_2", tool.callId)
        assertFalse(tool.isError)
    }

    @Test
    fun `parses file change item events as diff proposal`() {
        val line = """{"type":"item.completed","item":{"type":"file_change","id":"item_3","changes":[{"path":"src/App.kt","kind":"update"},{"path":"src/Util.kt","kind":"create"}]}}"""

        val event = CliStructuredEventParser.parseCodexLine(line)

        val diff = assertIs<EngineEvent.DiffProposal>(event)
        assertEquals("item_3", diff.itemId)
        assertEquals(
            listOf("src/App.kt" to "update", "src/Util.kt" to "create"),
            diff.changes.map { it.path to it.kind },
        )
    }

    @Test
    fun `parses web search call events without call suffix in tool name`() {
        val line = """{"type":"item.started","item":{"type":"web_search_call","id":"item_10","input":"query: kotlin regex"}}"""

        val event = CliStructuredEventParser.parseCodexLine(line)

        val tool = assertIs<EngineEvent.ToolCallStarted>(event)
        assertEquals("item_10", tool.callId)
        assertEquals("web_search", tool.name)
    }

    @Test
    fun `parses local shell call output as tool completion with error status`() {
        val line = """{"type":"item.completed","item":{"type":"local_shell_call_output","id":"item_11","status":"failed","output":"permission denied"}}"""

        val event = CliStructuredEventParser.parseCodexLine(line)

        val tool = assertIs<EngineEvent.ToolCallFinished>(event)
        assertEquals("item_11", tool.callId)
        assertEquals("local_shell", tool.name)
        assertTrue(tool.isError)
    }

    @Test
    fun `parses remaining official tool call types from item lifecycle events`() {
        val startedLines = listOf(
            """{"type":"item.started","item":{"type":"tool_search_call","id":"item_20","input":"query: parser"}}""" to "tool_search",
            """{"type":"item.started","item":{"type":"mcp_call","id":"item_21","input":"server: docs-index"}}""" to "mcp",
            """{"type":"item.started","item":{"type":"computer_call","id":"item_22","input":"open browser"}}""" to "computer",
            """{"type":"item.started","item":{"type":"code_interpreter_call","id":"item_23","input":"print(1)"}}""" to "code_interpreter",
            """{"type":"item.started","item":{"type":"image_generation_call","id":"item_24","input":"draw cat"}}""" to "image_generation",
        )
        startedLines.forEach { (line, expectedName) ->
            val event = CliStructuredEventParser.parseCodexLine(line)
            val tool = assertIs<EngineEvent.ToolCallStarted>(event)
            assertEquals(expectedName, tool.name)
        }

        val finishedLines = listOf(
            """{"type":"item.completed","item":{"type":"tool_search_call_output","id":"item_20","status":"completed","output":"matched 4 docs"}}""" to "tool_search",
            """{"type":"item.completed","item":{"type":"mcp_call_output","id":"item_21","status":"completed","output":"ok"}}""" to "mcp",
            """{"type":"item.completed","item":{"type":"computer_call_output","id":"item_22","status":"completed","output":"screenshot.png"}}""" to "computer",
            """{"type":"item.completed","item":{"type":"code_interpreter_call_output","id":"item_23","status":"completed","output":"1"}}""" to "code_interpreter",
            """{"type":"item.completed","item":{"type":"image_generation_call_output","id":"item_24","status":"completed","output":"image_id"}}""" to "image_generation",
        )
        finishedLines.forEach { (line, expectedName) ->
            val event = CliStructuredEventParser.parseCodexLine(line)
            val tool = assertIs<EngineEvent.ToolCallFinished>(event)
            assertEquals(expectedName, tool.name)
            assertFalse(tool.isError)
        }
    }

    @Test
    fun `parses plain tool type without call suffix as tool events`() {
        val started = CliStructuredEventParser.parseCodexLine(
            """{"type":"web_search","id":"item_30","input":"query: kotlin coroutines"}""",
        )
        val startedTool = assertIs<EngineEvent.ToolCallStarted>(started)
        assertEquals("web_search", startedTool.name)

        val finished = CliStructuredEventParser.parseCodexLine(
            """{"type":"mcp_output","id":"item_31","status":"completed","output":"ok"}""",
        )
        val finishedTool = assertIs<EngineEvent.ToolCallFinished>(finished)
        assertEquals("mcp", finishedTool.name)
    }

    @Test
    fun `parses user message item as narrative item event`() {
        val line = """{"type":"item.started","item":{"id":"msg_1","type":"user_message","text":"请解释这个报错","status":"started"}}"""

        val event = CliStructuredEventParser.parseCodexLine(line)

        val narrative = assertIs<EngineEvent.NarrativeItem>(event)
        assertEquals("msg_1", narrative.itemId)
        assertEquals("请解释这个报错", narrative.text)
        assertTrue(narrative.isUser)
    }

    @Test
    fun `suppresses lifecycle json lines from unparsed fallback`() {
        assertTrue(CliStructuredEventParser.shouldSuppressUnparsedLine("""{"type":"turn.started"}"""))
        assertTrue(CliStructuredEventParser.shouldSuppressUnparsedLine("""{"type":"turn/started"}"""))
        assertTrue(CliStructuredEventParser.shouldSuppressUnparsedLine("""{"type":"item.completed"}"""))
    }

    @Test
    fun `does not suppress non lifecycle lines`() {
        assertFalse(CliStructuredEventParser.shouldSuppressUnparsedLine("""{"type":"message.delta","text":"hello"}"""))
        assertFalse(CliStructuredEventParser.shouldSuppressUnparsedLine("plain text line"))
    }

    @Test
    fun `suppresses stdin prompt status line`() {
        assertTrue(CliStructuredEventParser.shouldSuppressUnparsedLine("Reading prompt from stdin..."))
    }
}
