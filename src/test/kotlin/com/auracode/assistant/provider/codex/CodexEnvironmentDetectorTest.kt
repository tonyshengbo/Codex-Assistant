package com.auracode.assistant.provider.codex

import java.io.File
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexEnvironmentDetectorTest {
    @Test
    fun `resolver finds configured command from PATH`() {
        val tempDir = createTempDirectory("codex-env-path")
        val codex = createExecutable(tempDir.toFile(), "codex")
        val resolver = CodexExecutableResolver(
            commonSearchPaths = emptyList(),
            operatingSystemName = "Linux",
            pathExt = "",
        )

        val resolved = resolver.resolve(
            configuredPath = "codex",
            commandName = "codex",
            shellEnvironment = mapOf("PATH" to tempDir.absolutePathString()),
        )

        assertEquals(codex.absolutePath, resolved.path)
        assertEquals(CodexEnvironmentStatus.CONFIGURED, resolved.status)
    }

    @Test
    fun `resolver finds Windows executable using PATHEXT`() {
        val tempDir = createTempDirectory("codex-env-win")
        val codex = createExecutable(tempDir.toFile(), "codex.cmd")
        val resolver = CodexExecutableResolver(
            commonSearchPaths = emptyList(),
            operatingSystemName = "Windows 11",
            pathExt = ".COM;.EXE;.BAT;.CMD",
        )

        val resolved = resolver.resolve(
            configuredPath = "",
            commandName = "codex",
            shellEnvironment = mapOf("PATH" to tempDir.absolutePathString()),
        )

        assertEquals(codex.absolutePath, resolved.path)
        assertEquals(CodexEnvironmentStatus.DETECTED, resolved.status)
    }

    @Test
    fun `launch environment builder prepends node dir and falls back to system PATH`() {
        val builder = CodexLaunchEnvironmentBuilder(
            systemEnvironment = {
                mapOf(
                    "PATH" to "/usr/bin:/bin",
                    "HOME" to "/tmp/home",
                )
            },
        )

        val overrides = builder.build(
            shellEnvironment = emptyMap(),
            nodePath = "/opt/node/bin/node",
        )

        assertEquals("/opt/node/bin${File.pathSeparator}/usr/bin:/bin", overrides["PATH"])
        assertEquals("/tmp/home", overrides["HOME"])
    }

    @Test
    fun `probe reports success when initialize succeeds`() {
        val probe = CodexAppServerProbe(
            runner = { _, _ ->
                CodexAppServerProbeExecution(initialized = true)
            },
        )

        val result = probe.probe(
            codexPath = "/resolved/codex",
            environmentOverrides = emptyMap(),
        )

        assertEquals(CodexEnvironmentStatus.DETECTED, result.status)
        assertTrue(result.message.contains("look available"))
    }

    @Test
    fun `auto detect does not fail when node is missing but not configured`() {
        val tempDir = createTempDirectory("codex-env-auto")
        createExecutable(tempDir.toFile(), "codex")
        val detector = CodexEnvironmentDetector(
            shellEnvironmentLoader = {
                mapOf("PATH" to tempDir.absolutePathString())
            },
            commonSearchPaths = emptyList(),
            executableResolver = CodexExecutableResolver(
                commonSearchPaths = emptyList(),
                operatingSystemName = "Linux",
                pathExt = "",
            ),
            appServerProbe = CodexAppServerProbe(
                runner = { _, _ ->
                    CodexAppServerProbeExecution(initialized = true)
                },
            ),
        )

        val result = detector.autoDetect(
            configuredCodexPath = "",
            configuredNodePath = "",
        )

        assertEquals(CodexEnvironmentStatus.DETECTED, result.codexStatus)
        assertEquals(CodexEnvironmentStatus.MISSING, result.nodeStatus)
        assertEquals(CodexEnvironmentStatus.DETECTED, result.appServerStatus)
    }

    @Test
    fun `auto detect fails when configured node path is invalid`() {
        val tempDir = createTempDirectory("codex-env-invalid-node")
        createExecutable(tempDir.toFile(), "codex")
        val detector = CodexEnvironmentDetector(
            shellEnvironmentLoader = {
                mapOf("PATH" to tempDir.absolutePathString())
            },
            commonSearchPaths = emptyList(),
            executableResolver = CodexExecutableResolver(
                commonSearchPaths = emptyList(),
                operatingSystemName = "Linux",
                pathExt = "",
            ),
            appServerProbe = CodexAppServerProbe(
                runner = { _, _ ->
                    CodexAppServerProbeExecution(initialized = true)
                },
            ),
        )

        val result = detector.autoDetect(
            configuredCodexPath = "",
            configuredNodePath = "/missing/node",
        )

        assertEquals(CodexEnvironmentStatus.FAILED, result.nodeStatus)
        assertEquals(CodexEnvironmentStatus.FAILED, result.appServerStatus)
    }

    @Test
    fun `resolve for launch keeps codex status when executable is missing`() {
        val detector = CodexEnvironmentDetector(
            shellEnvironmentLoader = { emptyMap() },
            commonSearchPaths = emptyList(),
            executableResolver = CodexExecutableResolver(
                commonSearchPaths = emptyList(),
                operatingSystemName = "Linux",
                pathExt = "",
            ),
        )

        val result = detector.resolveForLaunch(
            configuredCodexPath = "",
            configuredNodePath = "",
        )

        assertEquals("codex", result.codexPath)
        assertEquals(CodexEnvironmentStatus.MISSING, result.codexStatus)
    }

    private fun createExecutable(directory: File, name: String): File {
        val file = directory.resolve(name)
        Files.createDirectories(directory.toPath())
        file.toPath().writeText("#!/bin/sh\nexit 0\n")
        file.setExecutable(true)
        return file
    }
}
