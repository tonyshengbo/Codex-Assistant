package com.auracode.assistant.provider.claude

import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.model.ImageAttachment
import com.auracode.assistant.provider.codex.CodexEnvironmentStatus
import com.auracode.assistant.provider.runtime.RuntimeLaunchResolution
import com.auracode.assistant.provider.runtime.RuntimeLaunchResolver
import com.auracode.assistant.settings.AgentSettingsService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeCliLauncherTest {
    @Test
    fun `start resolves default claude alias through shared runtime resolver before launch`() {
        val process = FakeProcess(
            input = ByteArrayInputStream(ByteArray(0)),
            error = ByteArrayInputStream(ByteArray(0)),
            output = TrackingOutputStream(),
        )
        val captured = mutableListOf<LaunchCapture>()
        val launcher = DefaultClaudeCliLauncher(
            runtimeLaunchResolver = RuntimeLaunchResolver { _, _, _ ->
                RuntimeLaunchResolution(
                    cliPath = "C:\\Users\\demo\\AppData\\Roaming\\npm\\claude.cmd",
                    cliStatus = CodexEnvironmentStatus.DETECTED,
                    nodePath = "C:\\Program Files\\nodejs\\node.exe",
                    nodeStatus = CodexEnvironmentStatus.DETECTED,
                    shellEnvironment = mapOf(
                        "PATH" to "C:\\Users\\demo\\AppData\\Roaming\\npm;C:\\Program Files\\nodejs",
                    ),
                    environmentOverrides = mapOf(
                        "PATH" to "C:\\Users\\demo\\AppData\\Roaming\\npm;C:\\Program Files\\nodejs",
                        "HOME" to "C:\\Users\\demo",
                    ),
                )
            },
            processStarter = ClaudeProcessStarter { command, workingDirectory, environmentOverrides ->
                captured += LaunchCapture(command, workingDirectory, environmentOverrides)
                process
            },
        )

        launcher.start(
            request = AgentRequest(
                engineId = "claude",
                prompt = "Say hello",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
            settings = AgentSettingsService().apply {
                loadState(
                    AgentSettingsService.State(
                        engineExecutablePaths = mutableMapOf("claude" to "claude"),
                    ),
                )
            },
        )

        assertEquals(1, captured.size)
        assertEquals("C:\\Users\\demo\\AppData\\Roaming\\npm\\claude.cmd", captured.single().command.first())
        assertEquals(File(".").absolutePath, captured.single().workingDirectory.absolutePath)
        assertEquals(
            "C:\\Users\\demo\\AppData\\Roaming\\npm;C:\\Program Files\\nodejs",
            captured.single().environmentOverrides["PATH"],
        )
    }

    @Test
    fun `build command includes verbose for stream json mode`() {
        val launcher = DefaultClaudeCliLauncher()

        val command = launcher.buildCommand(
            request = AgentRequest(
                engineId = "claude",
                prompt = "Say hello",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
            executable = "claude",
        )

        assertContains(command, "--verbose")
    }

    @Test
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
            executable = "claude",
        )

        val permissionIndex = command.indexOf("--permission-mode")
        assertTrue(permissionIndex >= 0)
        assertEquals("plan", command[permissionIndex + 1])
        assertContains(command, "--permission-prompt-tool")
    }

    @Test
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
            executable = "claude",
        )

        val effortIndex = command.indexOf("--effort")
        assertTrue(effortIndex >= 0)
        assertEquals("high", command[effortIndex + 1])
    }

    @Test
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
            executable = "claude",
        )

        val effortIndex = command.indexOf("--effort")
        assertTrue(effortIndex >= 0)
        assertEquals("high", command[effortIndex + 1])
    }

    @Test
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
            executable = "claude",
        )

        assertFalse(command.contains("--effort"))
    }

    @Test
    fun `start closes stdin after process launch`() {
        val stdin = TrackingOutputStream()
        val process = FakeProcess(
            input = ByteArrayInputStream(ByteArray(0)),
            error = ByteArrayInputStream(ByteArray(0)),
            output = stdin,
        )
        val launcher = DefaultClaudeCliLauncher(
            runtimeLaunchResolver = RuntimeLaunchResolver { _, _, _ ->
                RuntimeLaunchResolution(
                    cliPath = "claude",
                    cliStatus = CodexEnvironmentStatus.DETECTED,
                    nodePath = "",
                    nodeStatus = CodexEnvironmentStatus.MISSING,
                    shellEnvironment = emptyMap(),
                    environmentOverrides = emptyMap(),
                )
            },
            processStarter = ClaudeProcessStarter { _, _, _ -> process },
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

        assertTrue(stdin.closed)
    }

    @Test
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
            executable = "claude",
        )

        assertContains(command, "--input-format")
        assertContains(command, "stream-json")
        assertFalse(command.contains("Describe this image"))
    }

    @Test
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
            executable = "claude",
        )

        assertContains(command, "--input-format")
        assertContains(command, "stream-json")
        assertFalse(command.contains("--image"))
        assertFalse(command.contains("Plan based on this screenshot"))
    }

    @Test
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
            executable = "claude",
        )

        assertFalse(command.any { it.contains("Plan a refactor") })
        assertContains(command, "--input-format")
        assertContains(command, "stream-json")
        assertContains(command, "--permission-prompt-tool")
        assertContains(command, "stdio")
    }

    private data class LaunchCapture(
        val command: List<String>,
        val workingDirectory: File,
        val environmentOverrides: Map<String, String>,
    )

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FakeProcess(
        private val input: ByteArrayInputStream,
        private val error: ByteArrayInputStream,
        private val output: OutputStream,
    ) : Process() {
        override fun getInputStream() = input
        override fun getErrorStream() = error
        override fun getOutputStream() = output
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit?) = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun isAlive(): Boolean = false
        override fun destroyForcibly(): Process = this
    }
}
