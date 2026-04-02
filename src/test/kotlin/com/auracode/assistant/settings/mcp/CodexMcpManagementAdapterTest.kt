package com.auracode.assistant.settings.mcp

import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.provider.codex.CodexEnvironmentResolution
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertSame

class CodexMcpManagementAdapterTest {
    @Test
    fun `list servers uses resolved launch environment overrides`() = runBlocking {
        var recordedRequest: CommandExecutionRequest? = null
        val adapter = CodexMcpManagementAdapter(
            settings = settings("/usr/local/bin/codex"),
            launchEnvironmentResolver = environmentResolver(
                CodexEnvironmentResolution(
                    codexPath = "/resolved/codex",
                    nodePath = "/resolved/node",
                    shellEnvironment = mapOf("PATH" to "/usr/bin"),
                    environmentOverrides = mapOf("PATH" to "/resolved:/usr/bin", "HOME" to "/tmp/home"),
                ),
            ),
            commandRunner = { request ->
                recordedRequest = request
                CommandExecutionResult(
                    exitCode = 0,
                    stdout = "[]",
                )
            },
            appServerClientFactory = { _, _ -> FakeCodexMcpAppServerClient() },
        )

        adapter.listServers()

        assertNotNull(recordedRequest)
        assertEquals(listOf("/resolved/codex", "mcp", "list", "--json"), recordedRequest?.command)
        assertEquals("/resolved:/usr/bin", recordedRequest?.environmentOverrides?.get("PATH"))
        assertEquals("/tmp/home", recordedRequest?.environmentOverrides?.get("HOME"))
    }

    @Test
    fun `refresh statuses passes resolved environment into MCP app server client`() = runBlocking {
        val expectedEnvironment = mapOf("PATH" to "/resolved:/usr/bin")
        val client = FakeCodexMcpAppServerClient()
        var appServerBinary: String? = null
        var appServerEnvironment: Map<String, String>? = null
        val adapter = CodexMcpManagementAdapter(
            settings = settings("/usr/local/bin/codex"),
            launchEnvironmentResolver = environmentResolver(
                CodexEnvironmentResolution(
                    codexPath = "/resolved/codex",
                    nodePath = "/resolved/node",
                    shellEnvironment = mapOf("PATH" to "/usr/bin"),
                    environmentOverrides = expectedEnvironment,
                ),
            ),
            commandRunner = FakeCommandRunner(
                mapOf(
                    listOf("/resolved/codex", "mcp", "list", "--json") to CommandExecutionResult(
                        exitCode = 0,
                        stdout = "[]",
                    ),
                ),
            )::run,
            appServerClientFactory = { binary, environment ->
                appServerBinary = binary
                appServerEnvironment = environment
                client
            },
        )

        adapter.refreshStatuses()

        assertEquals("/resolved/codex", appServerBinary)
        assertSame(expectedEnvironment, appServerEnvironment)
    }

    @Test
    fun `get server flattens streamable http config into draft json`() = runBlocking {
        val adapter = CodexMcpManagementAdapter(
            settings = settings("/usr/local/bin/codex"),
            commandRunner = FakeCommandRunner(
                mapOf(
                    listOf("/usr/local/bin/codex", "mcp", "get", "docs", "--json") to CommandExecutionResult(
                        exitCode = 0,
                        stdout = """
                        {
                          "name": "docs",
                          "enabled": true,
                          "transport": {
                            "type": "streamable_http",
                            "url": "https://example.com/mcp",
                            "bearer_token_env_var": "DOCS_TOKEN"
                          }
                        }
                        """.trimIndent(),
                    ),
                ),
            )::run,
            appServerClientFactory = { _, _ -> FakeCodexMcpAppServerClient() },
        )

        val draft = adapter.getServer("docs")

        assertNotNull(draft)
        assertEquals("docs", draft.name)
        val transport = assertIs<McpTransportDraft.StreamableHttp>(draft.transport)
        assertEquals("https://example.com/mcp", transport.url)
        assertEquals("DOCS_TOKEN", transport.bearerTokenEnvVar)
        val config = draft.parsedConfigJson().getOrThrow()
        assertEquals("https://example.com/mcp", config.jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals("DOCS_TOKEN", config.jsonObject.getValue("bearer_token_env_var").jsonPrimitive.content)
    }

    @Test
    fun `save server rename writes flat stdio config accepted by codex`() = runBlocking {
        val client = FakeCodexMcpAppServerClient()
        val adapter = CodexMcpManagementAdapter(
            settings = settings("/usr/local/bin/codex"),
            launchEnvironmentResolver = environmentResolver(
                CodexEnvironmentResolution(
                    codexPath = "/usr/local/bin/codex",
                    nodePath = null,
                    shellEnvironment = emptyMap(),
                    environmentOverrides = emptyMap(),
                ),
            ),
            commandRunner = FakeCommandRunner(
                mapOf(
                    listOf("/usr/local/bin/codex", "mcp", "list", "--json") to CommandExecutionResult(
                        exitCode = 0,
                        stdout = """
                        [
                          {
                            "name": "docs",
                            "enabled": true,
                            "transport": {
                              "type": "stdio",
                              "command": "npx",
                              "args": ["-y", "@acme/docs-mcp"]
                            }
                          }
                        ]
                        """.trimIndent(),
                    ),
                ),
            )::run,
            appServerClientFactory = { _, _ -> client },
        )

        adapter.saveServer(
            McpServerDraft(
                originalName = "docs-old",
                name = "docs",
                configJson = """
                    {
                      "command": "npx",
                      "args": ["-y", "@acme/docs-mcp"],
                      "env": {
                        "DOCS_TOKEN": "secret"
                      },
                      "cwd": "/tmp/docs"
                    }
                """.trimIndent(),
            ),
        )

        assertEquals(1, client.batchWrites.size)
        assertEquals(2, client.batchWrites.single().size)
        assertEquals("mcp_servers.docs", client.batchWrites.single()[0].keyPath)
        assertEquals("mcp_servers.docs-old", client.batchWrites.single()[1].keyPath)
        val savedConfig = assertIs<JsonObject>(client.batchWrites.single()[0].value)
        assertEquals("npx", savedConfig.getValue("command").jsonPrimitive.content)
        assertEquals("@acme/docs-mcp", savedConfig.getValue("args").jsonArray[1].jsonPrimitive.content)
        assertTrue("transport" !in savedConfig)
        assertEquals(1, client.reloadCalls)
    }

    @Test
    fun `get editor draft aggregates current servers into wrapped json document`() = runBlocking {
        val adapter = CodexMcpManagementAdapter(
            settings = settings("/usr/local/bin/codex"),
            commandRunner = FakeCommandRunner(
                mapOf(
                    listOf("/usr/local/bin/codex", "mcp", "list", "--json") to CommandExecutionResult(
                        exitCode = 0,
                        stdout = """
                        [
                          {
                            "name": "docs",
                            "enabled": true,
                            "transport": {
                              "type": "stdio",
                              "command": "npx",
                              "args": ["-y", "@acme/docs-mcp"]
                            }
                          },
                          {
                            "name": "figma",
                            "enabled": true,
                            "transport": {
                              "type": "streamable_http",
                              "url": "https://example.com/mcp"
                            }
                          }
                        ]
                        """.trimIndent(),
                    ),
                    listOf("/usr/local/bin/codex", "mcp", "get", "docs", "--json") to CommandExecutionResult(
                        exitCode = 0,
                        stdout = """
                        {
                          "name": "docs",
                          "enabled": true,
                          "transport": {
                            "type": "stdio",
                            "command": "npx",
                            "args": ["-y", "@acme/docs-mcp"]
                          }
                        }
                        """.trimIndent(),
                    ),
                    listOf("/usr/local/bin/codex", "mcp", "get", "figma", "--json") to CommandExecutionResult(
                        exitCode = 0,
                        stdout = """
                        {
                          "name": "figma",
                          "enabled": true,
                          "transport": {
                            "type": "streamable_http",
                            "url": "https://example.com/mcp"
                          }
                        }
                        """.trimIndent(),
                    ),
                ),
            )::run,
            appServerClientFactory = { _, _ -> FakeCodexMcpAppServerClient() },
        )

        val draft = adapter.getEditorDraft()
        val entries = draft.parseServerEntries().getOrThrow()

        assertEquals(2, entries.size)
        assertEquals("docs", entries[0].name)
        assertEquals("figma", entries[1].name)
        assertTrue(draft.configJson.contains("\"mcpServers\""))
    }

    @Test
    fun `save servers writes one config edit per server and reloads once`() = runBlocking {
        val client = FakeCodexMcpAppServerClient()
        val adapter = CodexMcpManagementAdapter(
            settings = settings("/usr/local/bin/codex"),
            launchEnvironmentResolver = environmentResolver(
                CodexEnvironmentResolution(
                    codexPath = "/usr/local/bin/codex",
                    nodePath = null,
                    shellEnvironment = emptyMap(),
                    environmentOverrides = emptyMap(),
                ),
            ),
            commandRunner = FakeCommandRunner(emptyMap())::run,
            appServerClientFactory = { _, _ -> client },
        )

        adapter.saveServers(
            listOf(
                McpServerDraft(
                    name = "docs",
                    configJson = """
                        {
                          "mcpServers": {
                            "docs": {
                              "command": "npx",
                              "args": ["-y", "@acme/docs-mcp"]
                            }
                          }
                        }
                    """.trimIndent(),
                ),
                McpServerDraft(
                    name = "figma",
                    configJson = """
                        {
                          "mcpServers": {
                            "figma": {
                              "url": "https://example.com/mcp"
                            }
                          }
                        }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(1, client.batchWrites.size)
        assertEquals(2, client.batchWrites.single().size)
        assertEquals("mcp_servers.docs", client.batchWrites.single()[0].keyPath)
        assertEquals("mcp_servers.figma", client.batchWrites.single()[1].keyPath)
        assertEquals(1, client.reloadCalls)
    }

    @Test
    fun `refresh statuses maps tools resources and auth state`() = runBlocking {
        val client = FakeCodexMcpAppServerClient().apply {
            statuses = mapOf(
                "docs" to McpRuntimeStatus(
                    authState = McpAuthState.OAUTH,
                    tools = listOf("search_docs"),
                    resources = listOf("mcp://docs/index"),
                    resourceTemplates = listOf("mcp://docs/{slug}"),
                ),
            )
        }
        val adapter = CodexMcpManagementAdapter(
            settings = settings("/usr/local/bin/codex"),
            launchEnvironmentResolver = environmentResolver(
                CodexEnvironmentResolution(
                    codexPath = "/usr/local/bin/codex",
                    nodePath = null,
                    shellEnvironment = emptyMap(),
                    environmentOverrides = emptyMap(),
                ),
            ),
            commandRunner = FakeCommandRunner(
                mapOf(
                    listOf("/usr/local/bin/codex", "mcp", "list", "--json") to CommandExecutionResult(
                        exitCode = 0,
                        stdout = """
                        [
                          {
                            "name": "docs",
                            "enabled": true,
                            "transport": {
                              "type": "stdio",
                              "command": "npx",
                              "args": ["-y", "@acme/docs-mcp"]
                            }
                          }
                        ]
                        """.trimIndent(),
                    ),
                ),
            )::run,
            appServerClientFactory = { _, _ -> client },
        )

        val statuses = adapter.refreshStatuses()

        assertEquals(McpAuthState.OAUTH, statuses.getValue("docs").authState)
        assertEquals(listOf("search_docs"), statuses.getValue("docs").tools)
        assertEquals(listOf("mcp://docs/index"), statuses.getValue("docs").resources)
        assertTrue(client.initializeCalls > 0)
    }

    @Test
    fun `refresh statuses remaps sanitized status names back to configured server names`() = runBlocking {
        val client = FakeCodexMcpAppServerClient().apply {
            statuses = mapOf(
                "mastergo_magic_mcp" to McpRuntimeStatus(
                    authState = McpAuthState.UNSUPPORTED,
                    tools = listOf(
                        "getDsl",
                        "getMeta",
                        "getD2c",
                        "getComponentGenerator",
                        "getComponentLink",
                    ),
                ),
            )
        }
        val adapter = CodexMcpManagementAdapter(
            settings = settings("/usr/local/bin/codex"),
            launchEnvironmentResolver = environmentResolver(
                CodexEnvironmentResolution(
                    codexPath = "/usr/local/bin/codex",
                    nodePath = null,
                    shellEnvironment = emptyMap(),
                    environmentOverrides = emptyMap(),
                ),
            ),
            commandRunner = FakeCommandRunner(
                mapOf(
                    listOf("/usr/local/bin/codex", "mcp", "list", "--json") to CommandExecutionResult(
                        exitCode = 0,
                        stdout = """
                        [
                          {
                            "name": "mastergo-magic-mcp",
                            "enabled": true,
                            "transport": {
                              "type": "stdio",
                              "command": "npx",
                              "args": ["-y", "@mastergo/magic-mcp"]
                            }
                          }
                        ]
                        """.trimIndent(),
                    ),
                ),
            )::run,
            appServerClientFactory = { _, _ -> client },
        )

        val statuses = adapter.refreshStatuses()

        assertEquals(5, statuses.getValue("mastergo-magic-mcp").tools.size)
        assertTrue("mastergo_magic_mcp" !in statuses)
    }

    private fun settings(path: String): AgentSettingsService {
        return AgentSettingsService().apply {
            loadState(
                AgentSettingsService.State(
                    codexCliPath = path,
                    engineExecutablePaths = mutableMapOf("codex" to path),
                ),
            )
        }
    }

    private class FakeCommandRunner(
        private val responses: Map<List<String>, CommandExecutionResult>,
    ) {
        fun run(request: CommandExecutionRequest): CommandExecutionResult {
            return responses[request.command]
                ?: error("Unexpected command: ${request.command}")
        }
    }

    private fun environmentResolver(
        resolution: CodexEnvironmentResolution,
    ): (String, String) -> CodexEnvironmentResolution {
        return { _, _ -> resolution }
    }

    private class FakeCodexMcpAppServerClient : CodexMcpAppServerClient {
        val batchWrites: MutableList<List<ConfigEditRequest>> = mutableListOf()
        var reloadCalls: Int = 0
        var initializeCalls: Int = 0
        var statuses: Map<String, McpRuntimeStatus> = emptyMap()

        override suspend fun initialize() {
            initializeCalls += 1
        }

        override suspend fun batchWrite(edits: List<ConfigEditRequest>) {
            batchWrites += edits
        }

        override suspend fun reloadMcpServers() {
            reloadCalls += 1
        }

        override suspend fun listStatuses(): Map<String, McpRuntimeStatus> = statuses

        override suspend fun startOAuthLogin(name: String): String? = null

        override fun close() = Unit
    }
}
