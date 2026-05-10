package com.auracode.assistant.provider.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShellEnvironmentResolverTest {
    @Test
    fun `windows shell candidates include cmd environment when available`() {
        val candidates = resolveShellEnvironmentCandidates(
            systemEnvironment = mapOf(
                "PATH" to "C:\\Users\\Administrator\\AppData\\Roaming\\npm;C:\\Program Files\\nodejs",
                "ComSpec" to "\"C:\\Windows\\System32\\cmd.exe\"",
                "SystemRoot" to "\"C:\\Windows\"",
            ),
            operatingSystemName = "Windows 11",
            loadCandidate = { command ->
                if (command.first().endsWith("cmd.exe", ignoreCase = true) && command.drop(1) == listOf("/d", "/c", "set")) {
                    mapOf(
                        "PATH" to "C:\\Windows\\System32;C:\\Users\\Administrator\\AppData\\Roaming\\npm;C:\\Program Files\\nodejs",
                        "ComSpec" to "C:\\Windows\\System32\\cmd.exe",
                    )
                } else {
                    null
                }
            },
        )

        assertEquals(2, candidates.size)
        assertTrue(candidates.last().getValue("PATH").contains("C:\\Windows\\System32"))
    }

    @Test
    fun `preferred shell environment favors richer windows cmd path`() {
        val resolved = resolvePreferredShellEnvironment(
            systemEnvironment = mapOf(
                "PATH" to "C:\\Users\\Administrator\\AppData\\Roaming\\npm;C:\\Program Files\\nodejs",
                "ComSpec" to "\"C:\\Windows\\System32\\cmd.exe\"",
                "PATHEXT" to ".COM;.EXE;.BAT;.CMD",
            ),
            operatingSystemName = "Windows 11",
            loadCandidates = {
                resolveShellEnvironmentCandidates(
                    systemEnvironment = it,
                    operatingSystemName = "Windows 11",
                    loadCandidate = { _ ->
                        mapOf(
                            "PATH" to "C:\\Windows\\System32;C:\\Users\\Administrator\\AppData\\Roaming\\npm;C:\\Program Files\\nodejs",
                            "PATHEXT" to ".COM;.EXE;.BAT;.CMD",
                        )
                    },
                )
            },
        )

        assertTrue(resolved.getValue("PATH").startsWith("C:\\Windows\\System32"))
    }
}
