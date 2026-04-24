package com.auracode.assistant.provider.claude

import com.auracode.assistant.provider.codex.CodexExecutableResolver
import com.auracode.assistant.settings.AgentSettingsService
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies Claude CLI version upgrades resolve the package manager from the configured runtime environment. */
class ClaudeCliVersionServiceTest {
    /** Ensures Claude upgrades resolve npm from the configured Node directory before launching the command. */
    @Test
    fun `upgrade resolves npm from configured node path`() {
        val runtimeDirectory = createTempDirectory(prefix = "claude-version-test")
        val claudePath = createExecutablePath(runtimeDirectory, "claude")
        val nodePath = createExecutablePath(runtimeDirectory, "node")
        val npmPath = createExecutablePath(runtimeDirectory, "npm")
        val settings = AgentSettingsService().apply {
            loadState(
                AgentSettingsService.State(
                    nodeExecutablePath = nodePath,
                    engineExecutablePaths = mutableMapOf("claude" to claudePath),
                    claudeCliLastKnownCurrentVersion = "2.1.74",
                    claudeCliLastKnownLatestVersion = "2.1.119",
                    claudeCliLastCheckAt = 42L,
                ),
            )
        }
        var installedVersion = "2.1.74 (Claude Code)"
        var executedUpgradeCommand = ""
        val service = ClaudeCliVersionService(
            settingsService = settings,
            sourceDetector = npmSourceDetector(),
            executableResolver = CodexExecutableResolver(commonSearchPaths = emptyList()),
            shellEnvironmentLoader = { emptyMap() },
            commandRunner = { command, _ ->
                when {
                    command.lastOrNull() == "--version" -> ClaudeCliCommandResult(0, installedVersion, "")
                    command.drop(1) == listOf("install", "-g", "@anthropic-ai/claude-code@latest") -> {
                        executedUpgradeCommand = command.first()
                        installedVersion = "2.1.119 (Claude Code)"
                        ClaudeCliCommandResult(0, "", "")
                    }
                    else -> ClaudeCliCommandResult(1, "", "unexpected command")
                }
            },
            latestVersionFetcher = { error("network unavailable") },
        )

        val snapshot = service.upgrade()

        assertEquals(npmPath, executedUpgradeCommand)
        assertEquals(ClaudeCliVersionCheckStatus.UPGRADE_SUCCEEDED, snapshot.checkStatus)
        assertEquals("2.1.119", snapshot.currentVersion)
        assertTrue(snapshot.message.contains("upgraded successfully"))
    }

    /** Creates one executable file used by the temporary runtime fixture. */
    private fun createExecutablePath(directory: java.nio.file.Path, name: String): String {
        val executable = directory.resolve(name)
        Files.writeString(executable, "#!/bin/sh\nexit 0\n")
        executable.toFile().setExecutable(true)
        return executable.toAbsolutePath().toString()
    }

    /** Forces the version service to treat the Claude binary as an npm installation. */
    private fun npmSourceDetector(): ClaudeCliUpgradeSourceDetector {
        return ClaudeCliUpgradeSourceDetector(
            pathInspector = object : com.auracode.assistant.provider.codex.CodexCliExecutablePathInspector() {
                override fun inspect(codexPath: String): com.auracode.assistant.provider.codex.CodexCliExecutablePaths {
                    return com.auracode.assistant.provider.codex.CodexCliExecutablePaths(
                        entryPath = codexPath,
                        resolvedPath = "/tmp/node_modules/@anthropic-ai/claude-code/cli.js",
                    )
                }
            },
        )
    }
}
