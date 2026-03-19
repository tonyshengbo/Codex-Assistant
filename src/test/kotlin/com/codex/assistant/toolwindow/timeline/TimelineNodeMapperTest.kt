package com.codex.assistant.toolwindow.timeline

import com.codex.assistant.protocol.ItemKind
import com.codex.assistant.protocol.ItemStatus
import com.codex.assistant.protocol.UnifiedEvent
import com.codex.assistant.protocol.UnifiedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TimelineNodeMapperTest {
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
    fun `heredoc file write command is summarized as write file`() {
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
        assertEquals("Write b.java", command.title)
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

        assertEquals("Write b.java", assertIs<TimelineMutation.UpsertCommand>(writeMutation).title)
        assertEquals("Search java files", assertIs<TimelineMutation.UpsertCommand>(searchMutation).title)
    }
}
