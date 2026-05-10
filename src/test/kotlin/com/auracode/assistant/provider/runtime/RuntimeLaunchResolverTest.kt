package com.auracode.assistant.provider.runtime

import com.auracode.assistant.provider.codex.CodexEnvironmentStatus
import com.auracode.assistant.provider.codex.CodexExecutableResolver
import java.io.File
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeLaunchResolverTest {
    @Test
    fun `resolve uses shell PATH for default alias and prepends configured node dir`() {
        val cliDir = createTempDirectory("runtime-launch-cli")
        val nodeDir = createTempDirectory("runtime-launch-node")
        val claudePath = createExecutable(cliDir.toFile(), "claude.cmd")
        val nodePath = createExecutable(nodeDir.toFile(), "node.exe")
        val resolver = DefaultRuntimeLaunchResolver(
            executableResolver = CodexExecutableResolver(
                commonSearchPaths = emptyList(),
                operatingSystemName = "Windows 11",
                pathExt = ".COM;.EXE;.BAT;.CMD",
            ),
            shellEnvironmentLoader = {
                mapOf(
                    "PATH" to "${cliDir.absolutePathString()}${File.pathSeparator}C:\\Windows\\System32",
                    "HOME" to "C:\\Users\\demo",
                )
            },
        )

        val result = resolver.resolve(
            commandName = "claude",
            configuredCliPath = "claude",
            configuredNodePath = nodePath,
        )

        assertEquals(claudePath.absolutePath, result.cliPath)
        assertEquals(CodexEnvironmentStatus.DETECTED, result.cliStatus)
        assertEquals(nodePath, result.nodePath)
        assertEquals(CodexEnvironmentStatus.CONFIGURED, result.nodeStatus)
        assertTrue(result.environmentOverrides["PATH"].orEmpty().startsWith(nodeDir.absolutePathString()))
        assertEquals("C:\\Users\\demo", result.environmentOverrides["HOME"])
    }

    @Test
    fun `resolve keeps configured path and failed status for invalid node path`() {
        val resolver = DefaultRuntimeLaunchResolver(
            executableResolver = CodexExecutableResolver(
                commonSearchPaths = emptyList(),
                operatingSystemName = "Linux",
                pathExt = "",
            ),
            shellEnvironmentLoader = { emptyMap() },
        )

        val result = resolver.resolve(
            commandName = "claude",
            configuredCliPath = "/custom/bin/claude",
            configuredNodePath = "/missing/node",
        )

        assertEquals("/custom/bin/claude", result.cliPath)
        assertEquals(CodexEnvironmentStatus.FAILED, result.cliStatus)
        assertEquals("", result.nodePath)
        assertEquals(CodexEnvironmentStatus.FAILED, result.nodeStatus)
    }

    @Test
    fun `resolve prefers richer windows shell environment candidate that resolves cli and node`() {
        val cliDir = createTempDirectory("runtime-launch-candidate-cli")
        val nodeDir = createTempDirectory("runtime-launch-candidate-node")
        val claudePath = createExecutable(cliDir.toFile(), "claude.cmd")
        val nodePath = createExecutable(nodeDir.toFile(), "node.exe")
        val resolver = DefaultRuntimeLaunchResolver(
            executableResolver = CodexExecutableResolver(
                commonSearchPaths = emptyList(),
                operatingSystemName = "Windows 11",
                pathExt = ".COM;.EXE;.BAT;.CMD",
            ),
            shellEnvironmentLoader = {
                mapOf(
                    "PATH" to "C:\\Windows\\System32",
                    "ComSpec" to "C:\\Windows\\System32\\cmd.exe",
                )
            },
            shellEnvironmentCandidatesLoader = {
                listOf(
                    mapOf(
                        "PATH" to "C:\\Windows\\System32",
                        "ComSpec" to "C:\\Windows\\System32\\cmd.exe",
                    ),
                    mapOf(
                        "PATH" to "${cliDir.absolutePathString()}${File.pathSeparator}${nodeDir.absolutePathString()}",
                        "ComSpec" to "C:\\Windows\\System32\\cmd.exe",
                    ),
                )
            },
        )

        val result = resolver.resolve(
            commandName = "claude",
            configuredCliPath = "claude",
            configuredNodePath = "",
        )

        assertEquals(claudePath.absolutePath, result.cliPath)
        assertEquals(CodexEnvironmentStatus.DETECTED, result.cliStatus)
        assertEquals(nodePath.absolutePath, result.nodePath)
        assertEquals(CodexEnvironmentStatus.DETECTED, result.nodeStatus)
        assertTrue(result.shellEnvironment["PATH"].orEmpty().startsWith(cliDir.absolutePathString()))
    }

    private fun createExecutable(directory: File, name: String): File {
        val file = directory.resolve(name)
        Files.createDirectories(directory.toPath())
        Files.writeString(file.toPath(), "#!/bin/sh\nexit 0\n")
        file.setExecutable(true)
        return file
    }
}
