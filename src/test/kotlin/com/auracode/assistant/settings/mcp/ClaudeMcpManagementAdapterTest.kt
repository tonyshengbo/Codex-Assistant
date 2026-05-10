package com.auracode.assistant.settings.mcp

import com.auracode.assistant.provider.codex.CodexEnvironmentStatus
import com.auracode.assistant.provider.runtime.RuntimeLaunchResolution
import com.auracode.assistant.provider.runtime.RuntimeLaunchResolver
import com.auracode.assistant.settings.AgentSettingsService
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ClaudeMcpManagementAdapterTest {
    @Test
    fun `login uses resolved claude executable and launch environment`() {
        val tempHome = Files.createTempDirectory("claude-mcp-home")
        val configFile = tempHome.resolve(".claude.json")
        Files.writeString(
            configFile,
            """
            {
              "mcpServers": {
                "demo": {
                  "type": "http",
                  "url": "https://example.com/mcp",
                  "oauth": {
                    "clientId": "demo-client",
                    "callbackPort": 9999
                  }
                }
              }
            }
            """.trimIndent(),
        )
        val originalHome = System.getProperty("user.home")
        var commandPath = ""
        var launchPath = ""
        try {
            System.setProperty("user.home", tempHome.toString())
            val adapter = ClaudeMcpManagementAdapter(
                settings = AgentSettingsService().apply {
                    loadState(
                        AgentSettingsService.State(
                            engineExecutablePaths = mutableMapOf("claude" to "claude"),
                        ),
                    )
                },
                runtimeLaunchResolver = RuntimeLaunchResolver { _, _, _ ->
                    RuntimeLaunchResolution(
                        cliPath = "C:\\Users\\demo\\AppData\\Roaming\\npm\\claude.cmd",
                        cliStatus = CodexEnvironmentStatus.DETECTED,
                        nodePath = "C:\\Program Files\\nodejs\\node.exe",
                        nodeStatus = CodexEnvironmentStatus.DETECTED,
                        shellEnvironment = emptyMap(),
                        environmentOverrides = mapOf(
                            "PATH" to "C:\\Users\\demo\\AppData\\Roaming\\npm;C:\\Program Files\\nodejs",
                        ),
                    )
                },
                commandRunner = { request ->
                    commandPath = request.command.first()
                    launchPath = request.environmentOverrides["PATH"].orEmpty()
                    CommandExecutionResult(
                        exitCode = 0,
                        stdout = "",
                        stderr = "",
                    )
                },
            )

            runBlocking {
                adapter.login("demo")
            }
        } finally {
            System.setProperty("user.home", originalHome)
        }

        assertEquals("C:\\Users\\demo\\AppData\\Roaming\\npm\\claude.cmd", commandPath)
        assertEquals("C:\\Users\\demo\\AppData\\Roaming\\npm;C:\\Program Files\\nodejs", launchPath)
    }
}
