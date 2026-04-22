package com.auracode.assistant.provider.claude

import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 验证 ClaudeLocalHistoryReader 能正确从本地 JSONL 文件中还原历史会话事件。
 */
class ClaudeLocalHistoryReaderTest {

    @Test
    /** 会话文件不存在时返回空历史，不抛出异常。 */
    fun `readHistory returns empty when session file not found`() {
        val projectsDir = Files.createTempDirectory("claude-projects")
        val reader = ClaudeLocalHistoryReader(claudeProjectsDir = projectsDir)

        val result = reader.readHistory("nonexistent-session-id")

        assertTrue(result.events.isEmpty())
        assertEquals(false, result.hasOlder)
    }

    @Test
    /** 正常的 user+assistant 对话被还原为完整的 turn 事件序列。 */
    fun `readHistory parses user and assistant messages into turn events`() {
        val projectsDir = Files.createTempDirectory("claude-projects")
        val projectDir = projectsDir.resolve("-Users-test-Project").also { Files.createDirectory(it) }
        val sessionId = "test-session-abc123"

        projectDir.resolve("$sessionId.jsonl").toFile().writeText(
            """
            {"type":"queue-operation","operation":"enqueue","timestamp":"2026-01-01T00:00:00Z","sessionId":"$sessionId"}
            {"parentUuid":null,"isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"text","text":"Hello Claude"}]},"uuid":"user-uuid-1","timestamp":"2026-01-01T00:00:01Z","sessionId":"$sessionId"}
            {"parentUuid":"user-uuid-1","isSidechain":false,"type":"assistant","message":{"content":[{"type":"text","text":"Hello! How can I help?"}],"id":"msg_1","role":"assistant"},"uuid":"assistant-uuid-1","timestamp":"2026-01-01T00:00:02Z","sessionId":"$sessionId"}
            """.trimIndent(),
        )

        val reader = ClaudeLocalHistoryReader(claudeProjectsDir = projectsDir)
        val result = reader.readHistory(sessionId)

        // TurnStarted + user ItemUpdated + assistant ItemUpdated + TurnCompleted
        assertEquals(4, result.events.size)

        val turnStarted = assertIs<UnifiedEvent.TurnStarted>(result.events[0])
        assertEquals("user-uuid-1", turnStarted.turnId)
        assertEquals(sessionId, turnStarted.threadId)

        val userItem = assertIs<UnifiedEvent.ItemUpdated>(result.events[1])
        assertEquals("user_message", userItem.item.name)
        assertEquals("Hello Claude", userItem.item.text)
        assertEquals(ItemKind.NARRATIVE, userItem.item.kind)
        assertEquals(ItemStatus.SUCCESS, userItem.item.status)

        val assistantItem = assertIs<UnifiedEvent.ItemUpdated>(result.events[2])
        assertEquals("message", assistantItem.item.name)
        assertEquals("Hello! How can I help?", assistantItem.item.text)
        assertEquals(ItemKind.NARRATIVE, assistantItem.item.kind)

        val turnCompleted = assertIs<UnifiedEvent.TurnCompleted>(result.events[3])
        assertEquals("user-uuid-1", turnCompleted.turnId)
        assertEquals(TurnOutcome.SUCCESS, turnCompleted.outcome)
    }

    @Test
    /** 多轮对话按顺序还原，每轮都有独立的 TurnStarted/TurnCompleted。 */
    fun `readHistory restores multiple turns in order`() {
        val projectsDir = Files.createTempDirectory("claude-projects")
        val projectDir = projectsDir.resolve("-Users-test-Project").also { Files.createDirectory(it) }
        val sessionId = "multi-turn-session"

        projectDir.resolve("$sessionId.jsonl").toFile().writeText(
            """
            {"parentUuid":null,"isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"text","text":"First question"}]},"uuid":"user-1","sessionId":"$sessionId"}
            {"parentUuid":"user-1","isSidechain":false,"type":"assistant","message":{"content":[{"type":"text","text":"First answer"}],"id":"msg_1","role":"assistant"},"uuid":"assistant-1","sessionId":"$sessionId"}
            {"parentUuid":"assistant-1","isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"text","text":"Second question"}]},"uuid":"user-2","sessionId":"$sessionId"}
            {"parentUuid":"user-2","isSidechain":false,"type":"assistant","message":{"content":[{"type":"text","text":"Second answer"}],"id":"msg_2","role":"assistant"},"uuid":"assistant-2","sessionId":"$sessionId"}
            """.trimIndent(),
        )

        val reader = ClaudeLocalHistoryReader(claudeProjectsDir = projectsDir)
        val result = reader.readHistory(sessionId)

        // 2 turns × 4 events each = 8 events
        assertEquals(8, result.events.size)

        val firstTurn = assertIs<UnifiedEvent.TurnStarted>(result.events[0])
        assertEquals("user-1", firstTurn.turnId)

        val firstUser = assertIs<UnifiedEvent.ItemUpdated>(result.events[1])
        assertEquals("First question", firstUser.item.text)

        val firstAssistant = assertIs<UnifiedEvent.ItemUpdated>(result.events[2])
        assertEquals("First answer", firstAssistant.item.text)

        assertIs<UnifiedEvent.TurnCompleted>(result.events[3])

        val secondTurn = assertIs<UnifiedEvent.TurnStarted>(result.events[4])
        assertEquals("user-2", secondTurn.turnId)

        val secondUser = assertIs<UnifiedEvent.ItemUpdated>(result.events[5])
        assertEquals("Second question", secondUser.item.text)

        val secondAssistant = assertIs<UnifiedEvent.ItemUpdated>(result.events[6])
        assertEquals("Second answer", secondAssistant.item.text)

        assertIs<UnifiedEvent.TurnCompleted>(result.events[7])
    }

    @Test
    /** isSidechain=true 的条目（子 agent 消息）不出现在历史中。 */
    fun `readHistory skips sidechain entries`() {
        val projectsDir = Files.createTempDirectory("claude-projects")
        val projectDir = projectsDir.resolve("-Users-test-Project").also { Files.createDirectory(it) }
        val sessionId = "sidechain-session"

        projectDir.resolve("$sessionId.jsonl").toFile().writeText(
            """
            {"parentUuid":null,"isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"text","text":"Main message"}]},"uuid":"user-1","sessionId":"$sessionId"}
            {"parentUuid":"user-1","isSidechain":true,"type":"user","message":{"role":"user","content":[{"type":"text","text":"Subagent message"}]},"uuid":"sub-1","sessionId":"$sessionId"}
            {"parentUuid":"user-1","isSidechain":false,"type":"assistant","message":{"content":[{"type":"text","text":"Main response"}],"id":"msg_1","role":"assistant"},"uuid":"assistant-1","sessionId":"$sessionId"}
            """.trimIndent(),
        )

        val reader = ClaudeLocalHistoryReader(claudeProjectsDir = projectsDir)
        val result = reader.readHistory(sessionId)

        assertEquals(4, result.events.size)
        val userItem = assertIs<UnifiedEvent.ItemUpdated>(result.events[1])
        assertEquals("Main message", userItem.item.text)
    }

    @Test
    /** assistant 同时包含 tool_use 与 text 时，历史会保留结构化工具节点且正文仍然可见。 */
    fun `readHistory ignores non-text content blocks`() {
        val projectsDir = Files.createTempDirectory("claude-projects")
        val projectDir = projectsDir.resolve("-Users-test-Project").also { Files.createDirectory(it) }
        val sessionId = "tool-use-session"

        projectDir.resolve("$sessionId.jsonl").toFile().writeText(
            """
            {"parentUuid":null,"isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"text","text":"Run a tool"}]},"uuid":"user-1","sessionId":"$sessionId"}
            {"parentUuid":"user-1","isSidechain":false,"type":"assistant","message":{"content":[{"type":"tool_use","id":"tool_1","name":"Bash","input":{"command":"ls"}},{"type":"text","text":"Done."}],"id":"msg_1","role":"assistant"},"uuid":"assistant-1","sessionId":"$sessionId"}
            """.trimIndent(),
        )

        val reader = ClaudeLocalHistoryReader(claudeProjectsDir = projectsDir)
        val result = reader.readHistory(sessionId)

        assertEquals(4, result.events.size)
        val toolItem = assertIs<UnifiedEvent.ItemUpdated>(result.events[2])
        assertEquals(ItemKind.TOOL_CALL, toolItem.item.kind)
        val assistantItem = assertIs<UnifiedEvent.ItemUpdated>(result.events[3])
        assertEquals("Done.", assistantItem.item.text)
    }

    @Test
    /** 格式错误的 JSON 行被静默跳过，不影响其他行的解析。 */
    fun `readHistory skips malformed json lines gracefully`() {
        val projectsDir = Files.createTempDirectory("claude-projects")
        val projectDir = projectsDir.resolve("-Users-test-Project").also { Files.createDirectory(it) }
        val sessionId = "malformed-session"

        projectDir.resolve("$sessionId.jsonl").toFile().writeText(
            """
            this is not json
            {"parentUuid":null,"isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"text","text":"Valid message"}]},"uuid":"user-1","sessionId":"$sessionId"}
            {broken json here
            {"parentUuid":"user-1","isSidechain":false,"type":"assistant","message":{"content":[{"type":"text","text":"Valid response"}],"id":"msg_1","role":"assistant"},"uuid":"assistant-1","sessionId":"$sessionId"}
            """.trimIndent(),
        )

        val reader = ClaudeLocalHistoryReader(claudeProjectsDir = projectsDir)
        val result = reader.readHistory(sessionId)

        assertEquals(4, result.events.size)
        val userItem = assertIs<UnifiedEvent.ItemUpdated>(result.events[1])
        assertEquals("Valid message", userItem.item.text)
    }

    @Test
    /** 验证历史回放会跳过 TodoWrite timeline 节点，但继续恢复 Read、Write、Edit 等结构化工具节点。 */
    fun `readHistory skips todo timeline item and restores other structured tool items`() {
        val projectsDir = Files.createTempDirectory("claude-projects")
        val projectDir = projectsDir.resolve("-Users-test-Project").also { Files.createDirectory(it) }
        val sessionId = "structured-history-session"

        projectDir.resolve("$sessionId.jsonl").toFile().writeText(
            """
            {"parentUuid":null,"isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"text","text":"请继续执行"}]},"uuid":"user-1","sessionId":"$sessionId"}
            {"parentUuid":"user-1","isSidechain":false,"type":"assistant","message":{"content":[{"id":"tooluse_todo","input":{"todos":[{"content":"Create ClaudeLocalHistoryReader.kt","status":"completed"},{"content":"Override loadInitialHistory() in ClaudeCliProvider","status":"in_progress"},{"content":"Add ClaudeLocalHistoryReaderTest","status":"pending"}]},"name":"TodoWrite","type":"tool_use"}],"id":"msg_todo","role":"assistant"},"uuid":"assistant-tool-1","sessionId":"$sessionId"}
            {"parentUuid":"assistant-tool-1","isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"Todos have been modified successfully","tool_use_id":"tooluse_todo"}]},"uuid":"tool-result-1","sessionId":"$sessionId"}
            {"parentUuid":"assistant-tool-1","isSidechain":false,"type":"assistant","message":{"content":[{"id":"tooluse_read","input":{"file_path":"/tmp/ClaudeCliProviderTest.kt"},"name":"Read","type":"tool_use"}],"id":"msg_read","role":"assistant"},"uuid":"assistant-tool-2","sessionId":"$sessionId"}
            {"parentUuid":"assistant-tool-2","isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"1→class ClaudeCliProviderTest","tool_use_id":"tooluse_read"}]},"uuid":"tool-result-2","sessionId":"$sessionId"}
            {"parentUuid":"assistant-tool-2","isSidechain":false,"type":"assistant","message":{"content":[{"id":"tooluse_write","input":{"file_path":"/tmp/ClaudeLocalHistoryReader.kt","content":"class ClaudeLocalHistoryReader"},"name":"Write","type":"tool_use"}],"id":"msg_write","role":"assistant"},"uuid":"assistant-tool-3","sessionId":"$sessionId"}
            {"parentUuid":"assistant-tool-3","isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"File created successfully at: /tmp/ClaudeLocalHistoryReader.kt","tool_use_id":"tooluse_write"}]},"uuid":"tool-result-3","sessionId":"$sessionId"}
            {"parentUuid":"assistant-tool-3","isSidechain":false,"type":"assistant","message":{"content":[{"id":"tooluse_edit","input":{"replace_all":false,"file_path":"/tmp/ClaudeCliProvider.kt","old_string":"old text","new_string":"new text"},"name":"Edit","type":"tool_use"}],"id":"msg_edit","role":"assistant"},"uuid":"assistant-tool-4","sessionId":"$sessionId"}
            {"parentUuid":"assistant-tool-4","isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"The file /tmp/ClaudeCliProvider.kt has been updated successfully.","tool_use_id":"tooluse_edit"}]},"uuid":"tool-result-4","sessionId":"$sessionId"}
            {"parentUuid":"tool-result-4","isSidechain":false,"type":"assistant","message":{"content":[{"type":"text","text":"全部完成。"}],"id":"msg_final","role":"assistant"},"uuid":"assistant-final","sessionId":"$sessionId"}
            """.trimIndent(),
        )

        val reader = ClaudeLocalHistoryReader(claudeProjectsDir = projectsDir)
        val result = reader.readHistory(sessionId)
        val itemUpdates = result.events.filterIsInstance<UnifiedEvent.ItemUpdated>()

        assertTrue(itemUpdates.none { it.item.id.contains("tooluse_todo") })

        val readItem = itemUpdates.firstOrNull { it.item.id.contains("tooluse_read") }
            ?: error("Missing Read history item.")
        assertEquals(ItemKind.COMMAND_EXEC, readItem.item.kind)
        assertTrue(readItem.item.command.orEmpty().contains("/tmp/ClaudeCliProviderTest.kt"))

        val writeItem = itemUpdates.firstOrNull { it.item.id.contains("tooluse_write") && it.item.status == ItemStatus.SUCCESS }
            ?: error("Missing Write history item.")
        assertEquals(ItemKind.DIFF_APPLY, writeItem.item.kind)
        assertEquals("/tmp/ClaudeLocalHistoryReader.kt", writeItem.item.fileChanges.single().path)

        val editItem = itemUpdates.firstOrNull { it.item.id.contains("tooluse_edit") && it.item.status == ItemStatus.SUCCESS }
            ?: error("Missing Edit history item.")
        assertEquals(ItemKind.DIFF_APPLY, editItem.item.kind)
        assertEquals("/tmp/ClaudeCliProvider.kt", editItem.item.fileChanges.single().path)
    }
}
