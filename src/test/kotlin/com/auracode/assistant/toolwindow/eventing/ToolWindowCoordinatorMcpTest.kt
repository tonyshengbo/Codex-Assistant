package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.settings.mcp.McpAuthActionResult
import com.auracode.assistant.settings.mcp.McpAuthState
import com.auracode.assistant.settings.mcp.McpManagementAdapter
import com.auracode.assistant.settings.mcp.McpManagementAdapterRegistry
import com.auracode.assistant.settings.mcp.McpRuntimeStatus
import com.auracode.assistant.settings.mcp.McpServerDraft
import com.auracode.assistant.settings.mcp.McpServerSummary
import com.auracode.assistant.settings.mcp.McpTestResult
import com.auracode.assistant.settings.mcp.McpTransportType
import com.auracode.assistant.toolwindow.execution.ApprovalAreaStore
import com.auracode.assistant.toolwindow.submission.ComposerAreaStore
import com.auracode.assistant.toolwindow.shell.RightDrawerAreaStore
import com.auracode.assistant.toolwindow.sessions.HeaderAreaStore
import com.auracode.assistant.toolwindow.execution.StatusAreaStore
import com.auracode.assistant.toolwindow.conversation.TimelineAreaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolWindowCoordinatorMcpTest {
    @Test
    fun `loading mcp editor draft failure logs coroutine diagnostics`() {
        val workingDir = createTempDirectory("coordinator-mcp-draft-log")
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val service = AgentChatService(
            repository = com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository(workingDir.resolve("chat.db")),
            registry = registry(),
            settings = settings,
        )
        val eventHub = ToolWindowEventHub()
        val rightDrawerStore = RightDrawerAreaStore()
        val diagnostics = CopyOnWriteArrayList<String>()
        val adapter = RecordingMcpAdapter().apply {
            getEditorDraftFailure = IllegalStateException("boom draft")
        }
        val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = TimelineAreaStore(),
            composerStore = ComposerAreaStore(),
            rightDrawerStore = rightDrawerStore,
            approvalStore = ApprovalAreaStore(),
            mcpAdapterRegistry = McpManagementAdapterRegistry(mapOf("codex" to adapter)),
            diagnosticLog = { message, _ -> diagnostics += message },
        )

        eventHub.publishUiIntent(UiIntent.SelectSettingsSection(com.auracode.assistant.toolwindow.settings.SettingsSection.MCP))
        eventHub.publishUiIntent(UiIntent.SelectMcpServerForEdit("docs"))

        waitUntil(timeoutMs = 2_000) {
            diagnostics.any { it.contains("MCP coroutine handled failure") && it.contains("loadMcpEditorDraft") }
        }

        assertTrue(diagnostics.any { it.contains("intent=SelectMcpServerForEdit(name=docs)") })
        assertTrue(diagnostics.any { it.contains("label=loadMcpEditorDraft") })
        assertTrue(diagnostics.any { it.contains("mcpSettingsPage=LIST") })

        coordinator.dispose()
        service.dispose()
    }

    @Test
    fun `showing mcp settings list logs navigation intent for back flow`() {
        val workingDir = createTempDirectory("coordinator-mcp-back-log")
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val service = AgentChatService(
            repository = com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository(workingDir.resolve("chat.db")),
            registry = registry(),
            settings = settings,
        )
        val eventHub = ToolWindowEventHub()
        val rightDrawerStore = RightDrawerAreaStore()
        val diagnostics = CopyOnWriteArrayList<String>()
        val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = TimelineAreaStore(),
            composerStore = ComposerAreaStore(),
            rightDrawerStore = rightDrawerStore,
            approvalStore = ApprovalAreaStore(),
            mcpAdapterRegistry = McpManagementAdapterRegistry(mapOf("codex" to RecordingMcpAdapter())),
            diagnosticLog = { message, _ -> diagnostics += message },
        )

        eventHub.publishUiIntent(UiIntent.SelectSettingsSection(com.auracode.assistant.toolwindow.settings.SettingsSection.MCP))
        eventHub.publishUiIntent(UiIntent.CreateNewMcpDraft)
        eventHub.publishUiIntent(UiIntent.ShowMcpSettingsList)

        waitUntil(timeoutMs = 2_000) {
            diagnostics.any { it.contains("intent=ShowMcpSettingsList") }
        }

        assertTrue(diagnostics.any { it.contains("intent=ShowMcpSettingsList") && it.contains("mcpSettingsPage=EDITOR") })

        coordinator.dispose()
        service.dispose()
    }

    @Test
    fun `selecting mcp settings loads configured servers and runtime statuses`() {
        val workingDir = createTempDirectory("coordinator-mcp-load")
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val service = AgentChatService(
            repository = com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository(workingDir.resolve("chat.db")),
            registry = registry(),
            settings = settings,
        )
        val eventHub = ToolWindowEventHub()
        val rightDrawerStore = RightDrawerAreaStore()
        val adapter = RecordingMcpAdapter().apply {
            servers = listOf(
                McpServerSummary(
                    name = "docs",
                    engineId = "codex",
                    transportType = McpTransportType.STDIO,
                    displayTarget = "npx @acme/docs-mcp",
                    authState = McpAuthState.UNSUPPORTED,
                ),
            )
            statuses = mapOf(
                "docs" to McpRuntimeStatus(
                    authState = McpAuthState.UNSUPPORTED,
                    tools = listOf("search_docs"),
                ),
            )
        }
        val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = TimelineAreaStore(),
            composerStore = ComposerAreaStore(),
            rightDrawerStore = rightDrawerStore,
            approvalStore = ApprovalAreaStore(),
            mcpAdapterRegistry = McpManagementAdapterRegistry(mapOf("codex" to adapter)),
        )

        eventHub.publishUiIntent(UiIntent.SelectSettingsSection(com.auracode.assistant.toolwindow.settings.SettingsSection.MCP))

        waitUntil(timeoutMs = 2_000) {
            rightDrawerStore.state.value.mcpServers.size == 1 &&
                rightDrawerStore.state.value.mcpStatusByName.containsKey("docs")
        }

        assertEquals(1, adapter.listCalls)
        assertEquals(1, adapter.refreshCalls)
        assertEquals("docs", rightDrawerStore.state.value.mcpServers.single().name)
        assertEquals(listOf("search_docs"), rightDrawerStore.state.value.mcpStatusByName["docs"]?.tools)

        coordinator.dispose()
        service.dispose()
    }

    @Test
    fun `testing current mcp draft saves current json draft before refreshing runtime state`() {
        val workingDir = createTempDirectory("coordinator-mcp-test")
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val service = AgentChatService(
            repository = com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository(workingDir.resolve("chat.db")),
            registry = registry(),
            settings = settings,
        )
        val eventHub = ToolWindowEventHub()
        val rightDrawerStore = RightDrawerAreaStore()
        val adapter = RecordingMcpAdapter().apply {
            testResults["docs"] = McpTestResult(success = true, summary = "Connected")
        }
        val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = TimelineAreaStore(),
            composerStore = ComposerAreaStore(),
            rightDrawerStore = rightDrawerStore,
            approvalStore = ApprovalAreaStore(),
            mcpAdapterRegistry = McpManagementAdapterRegistry(mapOf("codex" to adapter)),
        )

        eventHub.publishUiIntent(UiIntent.SelectSettingsSection(com.auracode.assistant.toolwindow.settings.SettingsSection.MCP))
        eventHub.publishUiIntent(UiIntent.CreateNewMcpDraft)
        eventHub.publishUiIntent(
            UiIntent.EditMcpDraftJson(
                """
                {
                  "mcpServers": {
                    "docs": {
                      "enabled": true,
                      "command": "npx",
                      "args": ["-y", "@acme/docs-mcp"]
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
        eventHub.publishUiIntent(UiIntent.TestMcpServer())

        waitUntil(timeoutMs = 2_000) {
            adapter.savedDrafts.isNotEmpty() &&
                adapter.testRequests.contains("docs") &&
                rightDrawerStore.state.value.mcpTestResultsByName["docs"] != null &&
                rightDrawerStore.state.value.mcpFeedbackMessage == "Test succeeded: Connected"
        }

        assertEquals("docs", adapter.savedDrafts.single().name)
        assertTrue(adapter.savedDrafts.single().configJson.contains("\"docs\""))
        assertTrue(adapter.testRequests.contains("docs"))
        assertEquals("Connected", rightDrawerStore.state.value.mcpTestResultsByName["docs"]?.summary)
        assertEquals("Test succeeded: Connected", rightDrawerStore.state.value.mcpFeedbackMessage)

        coordinator.dispose()
        service.dispose()
    }

    @Test
    fun `toggling mcp server enabled persists config state and reloads server list`() {
        val workingDir = createTempDirectory("coordinator-mcp-toggle")
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val service = AgentChatService(
            repository = com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository(workingDir.resolve("chat.db")),
            registry = registry(),
            settings = settings,
        )
        val eventHub = ToolWindowEventHub()
        val rightDrawerStore = RightDrawerAreaStore()
        val adapter = RecordingMcpAdapter().apply {
            servers = listOf(
                McpServerSummary(
                    name = "docs",
                    engineId = "codex",
                    transportType = McpTransportType.STDIO,
                    displayTarget = "npx @acme/docs-mcp",
                    enabled = true,
                    authState = McpAuthState.UNSUPPORTED,
                ),
            )
        }
        val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = TimelineAreaStore(),
            composerStore = ComposerAreaStore(),
            rightDrawerStore = rightDrawerStore,
            approvalStore = ApprovalAreaStore(),
            mcpAdapterRegistry = McpManagementAdapterRegistry(mapOf("codex" to adapter)),
        )

        eventHub.publishUiIntent(UiIntent.SelectSettingsSection(com.auracode.assistant.toolwindow.settings.SettingsSection.MCP))

        waitUntil(timeoutMs = 2_000) { rightDrawerStore.state.value.mcpServers.size == 1 }

        adapter.servers = listOf(
            McpServerSummary(
                name = "docs",
                engineId = "codex",
                transportType = McpTransportType.STDIO,
                displayTarget = "npx @acme/docs-mcp",
                enabled = false,
                authState = McpAuthState.UNSUPPORTED,
            ),
        )

        eventHub.publishUiIntent(UiIntent.ToggleMcpServerEnabled(name = "docs", enabled = false))

        waitUntil(timeoutMs = 2_000) {
            adapter.enabledUpdates.contains("docs" to false) &&
                adapter.listCalls >= 2 &&
                rightDrawerStore.state.value.mcpServers.singleOrNull()?.enabled == false
        }

        assertEquals(listOf("docs" to false), adapter.enabledUpdates)
        assertEquals(false, rightDrawerStore.state.value.mcpServers.single().enabled)

        coordinator.dispose()
        service.dispose()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Condition was not met within ${timeoutMs}ms")
            }
            Thread.sleep(20)
        }
    }

    private fun registry(): ProviderRegistry {
        return ProviderRegistry(
            descriptors = listOf(
                EngineDescriptor(
                    id = "codex",
                    displayName = "Codex",
                    models = listOf("gpt-5.3-codex"),
                    capabilities = EngineCapabilities(
                        supportsThinking = true,
                        supportsToolEvents = true,
                        supportsCommandProposal = false,
                        supportsDiffProposal = false,
                    ),
                ),
            ),
            factories = listOf(
                object : AgentProviderFactory {
                    override val engineId: String = "codex"
                    override fun create(): AgentProvider = object : AgentProvider {
                        override fun stream(request: AgentRequest): Flow<UnifiedEvent> = flow {
                            emit(UnifiedEvent.TurnCompleted(turnId = "turn-1", outcome = TurnOutcome.SUCCESS))
                        }

                        override fun cancel(requestId: String) = Unit
                    }
                },
            ),
            defaultEngineId = "codex",
        )
    }

    private class RecordingMcpAdapter : McpManagementAdapter {
        var servers: List<McpServerSummary> = emptyList()
        var statuses: Map<String, McpRuntimeStatus> = emptyMap()
        val testResults: MutableMap<String, McpTestResult> = linkedMapOf()
        val savedDrafts: MutableList<McpServerDraft> = mutableListOf()
        val testRequests: MutableList<String> = mutableListOf()
        val enabledUpdates: MutableList<Pair<String, Boolean>> = mutableListOf()
        var listCalls: Int = 0
        var refreshCalls: Int = 0
        var getEditorDraftFailure: Throwable? = null

        override suspend fun listServers(): List<McpServerSummary> {
            listCalls += 1
            return servers
        }

        override suspend fun getEditorDraft(): McpServerDraft {
            getEditorDraftFailure?.let { throw it }
            return savedDrafts.lastOrNull() ?: McpServerDraft()
        }

        override suspend fun getServer(name: String): McpServerDraft? {
            return savedDrafts.lastOrNull { it.name == name }
        }

        override suspend fun saveServers(drafts: List<McpServerDraft>) {
            savedDrafts += drafts
        }

        override suspend fun saveServer(draft: McpServerDraft) {
            savedDrafts += draft
        }

        override suspend fun deleteServer(name: String): Boolean = true

        override suspend fun setServerEnabled(name: String, enabled: Boolean) {
            enabledUpdates += name to enabled
        }

        override suspend fun refreshStatuses(): Map<String, McpRuntimeStatus> {
            refreshCalls += 1
            return statuses
        }

        override suspend fun testServer(name: String): McpTestResult {
            testRequests += name
            return testResults.getValue(name)
        }

        override suspend fun login(name: String): McpAuthActionResult {
            return McpAuthActionResult(message = "login:$name")
        }

        override suspend fun logout(name: String): McpAuthActionResult {
            return McpAuthActionResult(message = "logout:$name")
        }
    }
}
