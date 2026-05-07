package com.auracode.assistant.provider.claude

import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.model.ImageAttachment
import com.auracode.assistant.settings.AgentSettingsService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证 Claude CLI 启动命令的关键参数拼装。
 */
class ClaudeCliLauncherTest {
    @Test
    /** 验证 stream-json 模式下会附带 Claude CLI 要求的 verbose 参数。 */
    fun `build command includes verbose for stream json mode`() {
        val launcher = DefaultClaudeCliLauncher()

        val command = launcher.buildCommand(
            request = AgentRequest(
                engineId = "claude",
                prompt = "Say hello",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
        )

        assertContains(command, "--verbose")
    }

    @Test
    /** 验证 Plan 协作模式下 permission-mode 为 plan，强制 Claude 只读探索不执行修改。 */
    fun `build command uses permission-mode plan when collaboration mode is PLAN`() {
        val launcher = DefaultClaudeCliLauncher()

        val command = launcher.buildCommand(
            request = AgentRequest(
                engineId = "claude",
                prompt = "Plan a refactor",
                contextFiles = emptyList(),
                workingDirectory = ".",
                collaborationMode = AgentCollaborationMode.PLAN,
            ),
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
        )

        val permissionIndex = command.indexOf("--permission-mode")
        assertTrue(permissionIndex >= 0, "命令应包含 --permission-mode 参数")
        assertEquals("plan", command[permissionIndex + 1])
        // Plan 模式仍需 permission-prompt-tool stdio 用于计划完成后的用户选择交互
        assertContains(command, "--permission-prompt-tool")
    }

    @Test
    /** 验证指定 reasoningEffort 时命令包含 Claude CLI 当前支持的 --effort 参数。 */
    fun `build command includes reasoning effort flag when effort is specified`() {
        val launcher = DefaultClaudeCliLauncher()

        val command = launcher.buildCommand(
            request = AgentRequest(
                engineId = "claude",
                prompt = "Check support",
                contextFiles = emptyList(),
                workingDirectory = ".",
                reasoningEffort = "high",
            ),
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
        )

        val effortIndex = command.indexOf("--effort")
        assertTrue(effortIndex >= 0, "命令应包含 --effort 参数")
        assertEquals("high", command[effortIndex + 1])
    }

    @Test
    /** 验证 xhigh（MAX 级别）被映射为 Claude CLI 可接受的 high。 */
    fun `build command maps xhigh effort to high for claude cli`() {
        val launcher = DefaultClaudeCliLauncher()

        val command = launcher.buildCommand(
            request = AgentRequest(
                engineId = "claude",
                prompt = "Check support",
                contextFiles = emptyList(),
                workingDirectory = ".",
                reasoningEffort = "xhigh",
            ),
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
        )

        val effortIndex = command.indexOf("--effort")
        assertTrue(effortIndex >= 0, "命令应包含 --effort 参数")
        assertEquals("high", command[effortIndex + 1], "xhigh 应映射为 high")
    }

    @Test
    /** 验证 reasoningEffort 为 null 时命令不包含 --effort 参数。 */
    fun `build command omits reasoning effort flag when effort is null`() {
        val launcher = DefaultClaudeCliLauncher()

        val command = launcher.buildCommand(
            request = AgentRequest(
                engineId = "claude",
                prompt = "Check support",
                contextFiles = emptyList(),
                workingDirectory = ".",
                reasoningEffort = null,
            ),
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
        )

        assertFalse(command.contains("--effort"), "reasoningEffort 为 null 时不应包含 --effort 参数")
    }

    @Test
    /** 验证启动 Claude CLI 后会主动关闭标准输入，避免 Claude 持续等待 EOF。 */
    fun `start closes stdin after process launch`() {
        val stdout = ByteArrayInputStream(ByteArray(0))
        val stderr = ByteArrayInputStream(ByteArray(0))
        val stdin = TrackingOutputStream()
        val process = FakeProcess(
            input = stdout,
            error = stderr,
            output = stdin,
        )
        val launcher = DefaultClaudeCliLauncher(
            processStarter = ClaudeProcessStarter { _, _ -> process },
        )

        launcher.start(
            request = AgentRequest(
                engineId = "claude",
                prompt = "Say hello",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
        )

        assertTrue(stdin.closed, "Claude 启动后应立刻关闭 stdin，避免 CLI 卡在等待输入结束")
    }

    @Test
    /** 验证有图片附件时命令包含 --input-format stream-json，且 prompt 不作为 CLI 参数传入。 */
    fun `build command uses stream-json input format when image attachments present`() {
        val launcher = DefaultClaudeCliLauncher()

        val command = launcher.buildCommand(
            request = AgentRequest(
                engineId = "claude",
                prompt = "Describe this image",
                contextFiles = emptyList(),
                workingDirectory = ".",
                imageAttachments = listOf(
                    ImageAttachment(path = "/tmp/test.png", name = "test.png", mimeType = "image/png"),
                ),
            ),
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
        )

        assertContains(command, "--input-format")
        assertContains(command, "stream-json")
        assertFalse(command.contains("Describe this image"), "有图片时 prompt 应通过 stdin 发送，不应出现在 CLI 参数中")
    }

    @Test
    /** 验证 plan 模式下有图片时走 stream-json stdin 路径，而非不存在的 --image 参数。 */
    fun `build command uses stream-json input format in plan mode with image attachments`() {
        val launcher = DefaultClaudeCliLauncher()

        val command = launcher.buildCommand(
            request = AgentRequest(
                engineId = "claude",
                prompt = "Plan based on this screenshot",
                contextFiles = emptyList(),
                workingDirectory = ".",
                collaborationMode = AgentCollaborationMode.PLAN,
                imageAttachments = listOf(
                    ImageAttachment(path = "/tmp/test.png", name = "test.png", mimeType = "image/png"),
                ),
            ),
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
        )

        assertContains(command, "--input-format")
        assertContains(command, "stream-json")
        assertFalse(command.contains("--image"), "Claude CLI 没有 --image 参数，plan 模式下图片应通过 stdin 发送")
        assertFalse(command.contains("Plan based on this screenshot"), "有图片时 prompt 应通过 stdin 发送，不应出现在 CLI 参数中")
    }

    @Test
    /** 验证 plan 模式下无图片时 prompt 通过 stdin stream-json 发送，不作为 CLI 参数传入。 */
    fun `build command in plan mode without images passes prompt via stdin`() {
        val launcher = DefaultClaudeCliLauncher()

        val command = launcher.buildCommand(
            request = AgentRequest(
                engineId = "claude",
                prompt = "Plan a refactor",
                contextFiles = emptyList(),
                workingDirectory = ".",
                collaborationMode = AgentCollaborationMode.PLAN,
            ),
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
        )

        assertFalse(command.any { it.contains("Plan a refactor") }, "plan 模式下 prompt 应通过 stdin 发送，不应出现在 CLI 参数中")
        assertContains(command, "--input-format")
        assertContains(command, "stream-json")
        assertContains(command, "--permission-prompt-tool")
        assertContains(command, "stdio")
    }

    /** 记录输出流是否被关闭，便于验证 Claude CLI 的 stdin 生命周期。 */
    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed: Boolean = false
            private set

        /** 标记并关闭输出流。 */
        override fun close() {
            closed = true
            super.close()
        }
    }

    /** 提供可控的假进程，便于在单测中验证启动阶段行为。 */
    private class FakeProcess(
        private val input: ByteArrayInputStream,
        private val error: ByteArrayInputStream,
        private val output: OutputStream,
    ) : Process() {
        /** 返回供会话读取的标准输出流。 */
        override fun getInputStream() = input

        /** 返回供会话读取的标准错误流。 */
        override fun getErrorStream() = error

        /** 返回供 launcher 关闭的标准输入流。 */
        override fun getOutputStream() = output

        /** 单测不关心退出值，固定返回 0。 */
        override fun waitFor(): Int = 0

        /** 单测不关心超时等待，固定视为已退出。 */
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit?): Boolean = true

        /** 单测不关心退出值，固定返回 0。 */
        override fun exitValue(): Int = 0

        /** 单测无需真实销毁进程。 */
        override fun destroy() = Unit

        /** 单测固定视为非存活，避免额外状态管理。 */
        override fun isAlive(): Boolean = false

        /** 单测无需强制销毁，直接返回当前实例。 */
        override fun destroyForcibly(): Process = this
    }
}
