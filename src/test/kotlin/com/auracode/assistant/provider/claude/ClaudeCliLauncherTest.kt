package com.auracode.assistant.provider.claude

import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.settings.AgentSettingsService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContains
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
    /** 验证 plan 协作模式下命令包含 --permission-mode plan，确保 Claude CLI 进入只规划不执行的模式。 */
    fun `build command includes permission-mode plan when collaboration mode is PLAN`() {
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
        assertContains(command, "plan")
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
