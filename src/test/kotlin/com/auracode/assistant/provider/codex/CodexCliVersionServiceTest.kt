package com.auracode.assistant.provider.codex

import com.auracode.assistant.settings.AgentSettingsService
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CodexCliVersionServiceTest {
    @Test
    fun `refresh without auto check returns restored cached update state`() {
        val codexPath = createExecutablePath("codex")
        val settings = AgentSettingsService().apply {
            loadState(
                AgentSettingsService.State(
                    codexCliPath = codexPath,
                    engineExecutablePaths = mutableMapOf("codex" to codexPath),
                    codexCliAutoUpdateCheckEnabled = false,
                    codexCliLastKnownCurrentVersion = "0.118.0",
                    codexCliLastKnownLatestVersion = "0.120.0",
                    codexCliLastCheckAt = 42L,
                ),
            )
        }

        val service = CodexCliVersionService(
            settingsService = settings,
            environmentDetector = testEnvironmentDetector(codexPath),
            sourceDetector = npmSourceDetector(),
        )

        val snapshot = service.refresh(force = false)

        assertEquals(CodexCliVersionCheckStatus.UPDATE_AVAILABLE, snapshot.checkStatus)
        assertEquals("0.118.0", snapshot.currentVersion)
        assertEquals("0.120.0", snapshot.latestVersion)
        assertTrue(snapshot.isUpgradeSupported)
    }

    @Test
    fun `upgrade succeeds when local version advances even if remote confirmation fails`() {
        val codexPath = createExecutablePath("codex")
        val settings = AgentSettingsService().apply {
            loadState(
                AgentSettingsService.State(
                    codexCliPath = codexPath,
                    engineExecutablePaths = mutableMapOf("codex" to codexPath),
                    codexCliLastKnownCurrentVersion = "0.118.0",
                    codexCliLastKnownLatestVersion = "0.120.0",
                    codexCliLastCheckAt = 42L,
                ),
            )
        }
        var installedVersion = "codex-cli 0.118.0"
        val service = CodexCliVersionService(
            settingsService = settings,
            environmentDetector = testEnvironmentDetector(codexPath),
            sourceDetector = npmSourceDetector(),
            commandRunner = { command, _ ->
                    when {
                        command.lastOrNull() == "--version" -> CodexCliCommandResult(0, installedVersion, "")
                        isNpmCommand(command.firstOrNull()) -> {
                            installedVersion = "codex-cli 0.120.0"
                            CodexCliCommandResult(0, "", "")
                        }
                    else -> CodexCliCommandResult(1, "", "unexpected command")
                }
            },
            latestVersionFetcher = { error("network unavailable") },
        )

        val snapshot = service.upgrade()

        assertEquals(CodexCliVersionCheckStatus.UPGRADE_SUCCEEDED, snapshot.checkStatus)
        assertEquals("0.120.0", snapshot.currentVersion)
        assertEquals("", snapshot.latestVersion)
        assertEquals(
            "Codex CLI was upgraded successfully, but the latest version could not be confirmed.",
            snapshot.message,
        )
    }

    @Test
    fun `refresh treats default codex alias resolved from common search path as installed`() {
        val runtimeDirectory = createTempDirectory(prefix = "codex-version-default-alias")
        val codexPath = createExecutablePath(runtimeDirectory, "codex.cmd")
        val settings = AgentSettingsService().apply {
            loadState(
                AgentSettingsService.State(
                    codexCliPath = "codex",
                    engineExecutablePaths = mutableMapOf("codex" to "codex"),
                    codexCliAutoUpdateCheckEnabled = true,
                ),
            )
        }
        val service = CodexCliVersionService(
            settingsService = settings,
            environmentDetector = CodexEnvironmentDetector(
                shellEnvironmentLoader = { mapOf("PATH" to "C:\\Windows\\System32") },
                shellEnvironmentCandidatesLoader = { emptyList() },
                commonSearchPaths = listOf(runtimeDirectory.toAbsolutePath().toString()),
                executableResolver = CodexExecutableResolver(
                    commonSearchPaths = listOf(runtimeDirectory.toAbsolutePath().toString()),
                    operatingSystemName = "Windows 11",
                    pathExt = ".COM;.EXE;.BAT;.CMD",
                ),
            ),
            sourceDetector = npmSourceDetector(),
            commandRunner = { command, _ ->
                if (command.firstOrNull() == codexPath && command.lastOrNull() == "--version") {
                    CodexCliCommandResult(0, "codex-cli 0.130.0", "")
                } else {
                    CodexCliCommandResult(1, "", "unexpected command")
                }
            },
            latestVersionFetcher = { "0.130.0" },
        )

        val snapshot = service.refresh(force = true)

        assertEquals(CodexCliVersionCheckStatus.UP_TO_DATE, snapshot.checkStatus)
        assertEquals("0.130.0", snapshot.currentVersion)
        assertFalse(snapshot.message.contains("not installed", ignoreCase = true))
    }

    @Test
    fun `refresh prefers runtime draft override over persisted settings`() {
        val runtimeDirectory = createTempDirectory(prefix = "codex-version-draft-override")
        val codexPath = createExecutablePath(runtimeDirectory, "codex.cmd")
        val settings = AgentSettingsService().apply {
            loadState(
                AgentSettingsService.State(
                    codexCliPath = "",
                    engineExecutablePaths = mutableMapOf("codex" to ""),
                    nodeExecutablePath = "",
                    codexCliAutoUpdateCheckEnabled = true,
                ),
            )
        }
        val service = CodexCliVersionService(
            settingsService = settings,
            environmentDetector = CodexEnvironmentDetector(
                shellEnvironmentLoader = { mapOf("PATH" to "C:\\Windows\\System32") },
                shellEnvironmentCandidatesLoader = { emptyList() },
                commonSearchPaths = listOf(runtimeDirectory.toAbsolutePath().toString()),
                executableResolver = CodexExecutableResolver(
                    commonSearchPaths = listOf(runtimeDirectory.toAbsolutePath().toString()),
                    operatingSystemName = "Windows 11",
                    pathExt = ".COM;.EXE;.BAT;.CMD",
                ),
            ),
            sourceDetector = npmSourceDetector(),
            commandRunner = { command, _ ->
                if (command.firstOrNull() == codexPath && command.lastOrNull() == "--version") {
                    CodexCliCommandResult(0, "codex-cli 0.130.0", "")
                } else {
                    CodexCliCommandResult(1, "", "unexpected command")
                }
            },
            latestVersionFetcher = { "0.130.0" },
        )

        val snapshot = service.refresh(
            force = true,
            configuredCodexPathOverride = codexPath,
            configuredNodePathOverride = "",
        )

        assertEquals(CodexCliVersionCheckStatus.UP_TO_DATE, snapshot.checkStatus)
        assertEquals("0.130.0", snapshot.currentVersion)
    }

    @Test
    fun `refresh keeps upgrade in progress snapshot during non forced refresh`() {
        val codexPath = createExecutablePath("codex")
        val settings = AgentSettingsService().apply {
            loadState(
                AgentSettingsService.State(
                    codexCliPath = codexPath,
                    engineExecutablePaths = mutableMapOf("codex" to codexPath),
                    codexCliLastKnownCurrentVersion = "0.118.0",
                    codexCliLastKnownLatestVersion = "0.120.0",
                    codexCliLastCheckAt = 42L,
                ),
            )
        }
        var installedVersion = "codex-cli 0.118.0"
        var executedUpgradeCommand = false
        lateinit var service: CodexCliVersionService
        service = CodexCliVersionService(
            settingsService = settings,
            environmentDetector = testEnvironmentDetector(codexPath),
            sourceDetector = npmSourceDetector(),
            commandRunner = { command, _ ->
                when {
                    command.lastOrNull() == "--version" -> CodexCliCommandResult(0, installedVersion, "")
                    isNpmCommand(command.firstOrNull()) -> {
                        executedUpgradeCommand = true
                        val interimSnapshot = service.refresh(force = false)
                        assertEquals(CodexCliVersionCheckStatus.UPGRADE_IN_PROGRESS, interimSnapshot.checkStatus)
                        assertEquals("Upgrading Codex CLI...", interimSnapshot.message)
                        assertSame(interimSnapshot, service.snapshot())
                        installedVersion = "codex-cli 0.120.0"
                        CodexCliCommandResult(0, "", "")
                    }
                    else -> CodexCliCommandResult(1, "", "unexpected command")
                }
            },
            latestVersionFetcher = { "0.120.0" },
        )

        val snapshot = service.upgrade()

        assertTrue(executedUpgradeCommand)
        assertEquals(CodexCliVersionCheckStatus.UPGRADE_SUCCEEDED, snapshot.checkStatus)
        assertEquals("0.120.0", snapshot.currentVersion)
    }

    private fun createExecutablePath(name: String): String {
        val directory = createTempDirectory(prefix = "codex-version-test")
        return createExecutablePath(directory, name)
    }

    private fun createExecutablePath(directory: java.nio.file.Path, name: String): String {
        val executable = directory.resolve(name)
        Files.writeString(executable, "#!/bin/sh\nexit 0\n")
        executable.toFile().setExecutable(true)
        return executable.toAbsolutePath().toString()
    }

    private fun testEnvironmentDetector(codexPath: String): CodexEnvironmentDetector {
        return CodexEnvironmentDetector(
            shellEnvironmentLoader = { emptyMap() },
            shellEnvironmentCandidatesLoader = { emptyList() },
            commonSearchPaths = emptyList(),
        )
    }

    /** Matches npm whether the upgrade service kept the bare command or resolved an absolute executable path. */
    private fun isNpmCommand(command: String?): Boolean {
        val normalized = command?.lowercase().orEmpty()
        return normalized == "npm" ||
            normalized.endsWith("/npm") ||
            normalized.endsWith("\\npm") ||
            normalized.endsWith("/npm.cmd") ||
            normalized.endsWith("\\npm.cmd")
    }

    private fun npmSourceDetector(): CodexCliUpgradeSourceDetector {
        return CodexCliUpgradeSourceDetector(
            pathInspector = object : CodexCliExecutablePathInspector() {
                override fun inspect(codexPath: String): CodexCliExecutablePaths {
                    return CodexCliExecutablePaths(
                        entryPath = codexPath,
                        resolvedPath = "/opt/homebrew/lib/node_modules/@openai/codex/bin/codex.js",
                    )
                }
            },
        )
    }
}
