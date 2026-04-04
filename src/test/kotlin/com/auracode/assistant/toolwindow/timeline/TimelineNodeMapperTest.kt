package com.auracode.assistant.toolwindow.timeline

import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TimelineNodeMapperTest {
    @Test
    fun `terminal unified errors map to timeline error mutations`() {
        val mutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.Error(message = "boom", terminal = true),
        )

        val error = assertIs<TimelineMutation.AppendError>(mutation)
        assertEquals("boom", error.message)
    }

    @Test
    fun `retryable unified errors stay out of the timeline`() {
        val mutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.Error(message = "retrying", terminal = false),
        )

        assertNull(mutation)
    }

    @Test
    fun `assistant reasoning narrative maps to reasoning mutation instead of top level message`() {
        val mutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-reasoning",
                    kind = ItemKind.NARRATIVE,
                    status = ItemStatus.RUNNING,
                    name = "reasoning",
                    text = "Inspecting repository structure",
                ),
            ),
        )

        val reasoning = assertIs<TimelineMutation.UpsertReasoning>(mutation)
        assertEquals("request-1:item-reasoning", reasoning.sourceId)
        assertEquals("Inspecting repository structure", reasoning.body)
    }

    @Test
    fun `user input item maps to dedicated user input mutation`() {
        val mutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-user-input",
                    kind = ItemKind.USER_INPUT,
                    status = ItemStatus.SUCCESS,
                    name = "User Input",
                    text = "Target\n\nReuse existing demo",
                ),
            ),
        )

        val userInput = assertIs<TimelineMutation.UpsertUserInput>(mutation)
        assertEquals("request-1:item-user-input", userInput.sourceId)
        assertEquals("User Input", userInput.title)
        assertEquals("Target\n\nReuse existing demo", userInput.body)
    }

    @Test
    fun `context compaction item maps to dedicated context compaction mutation`() {
        val mutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-context",
                    kind = ItemKind.CONTEXT_COMPACTION,
                    status = ItemStatus.RUNNING,
                    name = "Context Compaction",
                    text = "Compacting context",
                ),
            ),
        )

        val activity = assertIs<TimelineMutation.UpsertContextCompaction>(mutation)
        assertEquals("request-1:item-context", activity.sourceId)
        assertEquals("Context Compaction", activity.title)
        assertEquals("Compacting context", activity.body)
    }

    @Test
    fun `diff apply item maps to file change mutation`() {
        val mutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-5",
                    kind = ItemKind.DIFF_APPLY,
                    status = ItemStatus.RUNNING,
                    name = "File Changes (2)",
                    text = "update /tmp/hello.java\ncreate /tmp/Util.kt",
                    fileChanges = listOf(
                        com.auracode.assistant.protocol.UnifiedFileChange(
                            sourceScopedId = "request-1:item-5:0",
                            path = "/tmp/hello.java",
                            kind = "update",
                            addedLines = 3,
                            deletedLines = 1,
                        ),
                        com.auracode.assistant.protocol.UnifiedFileChange(
                            sourceScopedId = "request-1:item-5:1",
                            path = "/tmp/Util.kt",
                            kind = "create",
                        ),
                    ),
                ),
            ),
        )

        val diff = assertIs<TimelineMutation.UpsertFileChange>(mutation)
        assertEquals("request-1:item-5", diff.sourceId)
        assertEquals("Changed 2 files", diff.title)
        assertEquals(
            listOf(TimelineFileChangeKind.UPDATE, TimelineFileChangeKind.CREATE),
            diff.changes.map { it.kind },
        )
        assertEquals(
            listOf("hello.java", "Util.kt"),
            diff.changes.map { it.displayName },
        )
        assertEquals(3, diff.changes.first().addedLines)
        assertEquals(1, diff.changes.first().deletedLines)
    }

    @Test
    fun `command item maps to human readable title`() {
        val mutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-1",
                    kind = ItemKind.COMMAND_EXEC,
                    status = ItemStatus.RUNNING,
                    command = "/bin/zsh -lc 'cat /tmp/b.java'",
                ),
            ),
        )

        val command = assertIs<TimelineMutation.UpsertCommand>(mutation)
        assertEquals("Read b.java", command.title)
        assertEquals("b.java", command.titleTargetLabel)
        assertEquals("/tmp/b.java", command.titleTargetPath)
    }

    @Test
    fun `shell tool item prefers command summary over shell display name`() {
        val mutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-tool",
                    kind = ItemKind.TOOL_CALL,
                    status = ItemStatus.RUNNING,
                    name = "Cd WeeklyReport",
                    text = "/bin/sh -lc 'cd /Users/me/WeeklyReport && rg --files -g \"*.java\"'",
                ),
            ),
        )

        val tool = assertIs<TimelineMutation.UpsertToolCall>(mutation)
        assertEquals("Search java files", tool.title)
        assertEquals(null, tool.titleTargetLabel)
        assertEquals(null, tool.titleTargetPath)
    }

    @Test
    fun `mcp tool item maps to mcp specific title instead of generic tool call`() {
        val mutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-mcp",
                    kind = ItemKind.TOOL_CALL,
                    status = ItemStatus.SUCCESS,
                    name = "mcp:cloudview-gray",
                    text = """
                        - Server: `cloudview-gray`
                        - Tool: `get_figma_node`
                        
                        **Result**

                        ```json
                        {"name":"多窗口"}
                        ```
                    """.trimIndent(),
                ),
            ),
        )

        val tool = assertIs<TimelineMutation.UpsertToolCall>(mutation)
        assertEquals("Call MCP · cloudview-gray · get_figma_node", tool.title)
        assertEquals(null, tool.titleTargetLabel)
        assertEquals(null, tool.titleTargetPath)
        assertEquals(true, tool.body.contains("**Result**"))
    }

    @Test
    fun `skill file read command exposes clickable title target`() {
        val mutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-skill",
                    kind = ItemKind.COMMAND_EXEC,
                    status = ItemStatus.RUNNING,
                    command = "/bin/zsh -lc 'sed -n ''1,140p'' /Users/tonysheng/.codex/superpowers/skills/using-superpowers/SKILL.md'",
                ),
            ),
        )

        val command = assertIs<TimelineMutation.UpsertCommand>(mutation)
        assertEquals("Read using-superpowers/SKILL.md", command.title)
        assertEquals("using-superpowers/SKILL.md", command.titleTargetLabel)
        assertEquals("/Users/tonysheng/.codex/superpowers/skills/using-superpowers/SKILL.md", command.titleTargetPath)
    }

    @Test
    fun `python heredoc command falls back to safe summary instead of shell wrapper`() {
        val mutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-python",
                    kind = ItemKind.COMMAND_EXEC,
                    status = ItemStatus.RUNNING,
                    command = """
                        /bin/zsh -lc "python3 - <<'PY'
                        from pathlib import Path
                        root = Path('/Users/tonysheng/AndroidStudioProjects/WeeklyReport')
                        patterns = ['*.java','*.class']
                        for pat in patterns:
                            for p in root.glob(pat):
                                if p.is_file():
                                    p.unlink()
                                    print(f'deleted {p.name}')
                        PY"
                    """.trimIndent(),
                ),
            ),
        )

        val command = assertIs<TimelineMutation.UpsertCommand>(mutation)
        assertEquals("Delete java and class files", command.title)
    }

    @Test
    fun `grouped ripgrep command ignores shell braces and summarizes searched kinds`() {
        val mutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-rg",
                    kind = ItemKind.COMMAND_EXEC,
                    status = ItemStatus.RUNNING,
                    command = "/bin/zsh -lc \"cd /Users/tonysheng/AndroidStudioProjects/WeeklyReport && { rg --files -g '.java' || true; } && { rg --files -g '.class' || true; }\"",
                ),
            ),
        )

        val command = assertIs<TimelineMutation.UpsertCommand>(mutation)
        assertEquals("Search java and class files", command.title)
    }

    @Test
    fun `heredoc file write command is summarized as edit file`() {
        val mutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-write",
                    kind = ItemKind.COMMAND_EXEC,
                    status = ItemStatus.RUNNING,
                    command = """
                        /bin/zsh -lc "cat > /Users/tonysheng/AndroidStudioProjects/WeeklyReport/b.java <<'EOF'
                        public class b {}
                        EOF"
                    """.trimIndent(),
                ),
            ),
        )

        val command = assertIs<TimelineMutation.UpsertCommand>(mutation)
        assertEquals("Edit b.java", command.title)
    }

    @Test
    fun `windows cmd type command is summarized as read file`() {
        val mutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-cmd",
                    kind = ItemKind.COMMAND_EXEC,
                    status = ItemStatus.RUNNING,
                    command = """cmd /c type C:\Users\tonysheng\WeeklyReport\b.java""",
                ),
            ),
        )

        val command = assertIs<TimelineMutation.UpsertCommand>(mutation)
        assertEquals("Read b.java", command.title)
    }

    @Test
    fun `windows powershell content and search commands are summarized cross platform`() {
        val writeMutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-ps-write",
                    kind = ItemKind.COMMAND_EXEC,
                    status = ItemStatus.RUNNING,
                    command = """powershell -Command Set-Content C:\Users\tonysheng\WeeklyReport\b.java @'class b {}'@""",
                ),
            ),
        )
        val searchMutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-ps-search",
                    kind = ItemKind.COMMAND_EXEC,
                    status = ItemStatus.RUNNING,
                    command = """pwsh -Command Get-ChildItem -Recurse -Filter *.java""",
                ),
            ),
        )

        assertEquals("Edit b.java", assertIs<TimelineMutation.UpsertCommand>(writeMutation).title)
        assertEquals("Search java files", assertIs<TimelineMutation.UpsertCommand>(searchMutation).title)
    }

    @Test
    fun `web search tool item maps to tool call instead of unknown activity`() {
        val runningMutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-web-running",
                    kind = ItemKind.TOOL_CALL,
                    status = ItemStatus.RUNNING,
                    name = "web_search",
                    text = "OpenAI Codex latest version official",
                ),
            ),
        )
        val successMutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-web-success",
                    kind = ItemKind.TOOL_CALL,
                    status = ItemStatus.SUCCESS,
                    name = "web_search",
                    text = "kotlin compose ime",
                ),
            ),
        )

        val runningTool = assertIs<TimelineMutation.UpsertToolCall>(runningMutation)
        val successTool = assertIs<TimelineMutation.UpsertToolCall>(successMutation)
        assertEquals("Searching the web", runningTool.title)
        assertEquals("Searched", successTool.title)
        assertEquals("OpenAI Codex latest version official", runningTool.body)
        assertEquals("kotlin compose ime", successTool.body)
        assertNull(runningTool.titleTargetLabel)
        assertNull(runningTool.titleTargetPath)
    }

    @Test
    fun `empty web search tool body does not fall back to item id`() {
        val runningMutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "ws_opaque_id",
                    kind = ItemKind.TOOL_CALL,
                    status = ItemStatus.RUNNING,
                    name = "web_search",
                    text = null,
                ),
            ),
        )

        val runningTool = assertIs<TimelineMutation.UpsertToolCall>(runningMutation)
        assertEquals("Searching the web", runningTool.title)
        assertEquals("", runningTool.body)
    }

    @Test
    fun `unknown activity keeps raw type name without humanization`() {
        val mutation = TimelineNodeMapper.fromUnifiedEvent(
            UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "request-1:item-unknown",
                    kind = ItemKind.UNKNOWN,
                    status = ItemStatus.RUNNING,
                    name = "fooBarBaz",
                    text = "payload body",
                ),
            ),
        )

        val unknown = assertIs<TimelineMutation.UpsertUnknownActivity>(mutation)
        assertEquals("fooBarBaz", unknown.title)
        assertEquals("payload body", unknown.body)
    }

}
