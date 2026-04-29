package com.auracode.assistant.settings.mcp

import com.auracode.assistant.provider.CodexProviderFactory
import com.auracode.assistant.settings.AgentSettingsService

internal interface McpManagementAdapter {
    suspend fun listServers(): List<McpServerSummary>
    suspend fun getEditorDraft(): McpServerDraft
    suspend fun getServer(name: String): McpServerDraft?
    suspend fun saveServers(drafts: List<McpServerDraft>)
    suspend fun saveServer(draft: McpServerDraft)
    suspend fun deleteServer(name: String): Boolean
    suspend fun setServerEnabled(name: String, enabled: Boolean)
    suspend fun refreshStatuses(): Map<String, McpRuntimeStatus>
    suspend fun testServer(name: String): McpTestResult
    suspend fun login(
        name: String,
        onAuthorizationUrl: suspend (String?) -> Unit = {},
    ): McpAuthActionResult
    suspend fun cancelLogin(name: String): McpAuthActionResult
    suspend fun logout(name: String): McpAuthActionResult
}

internal class McpManagementAdapterRegistry(
    private val adapters: Map<String, McpManagementAdapter>,
    private val defaultEngineId: String = CodexProviderFactory.ENGINE_ID,
) {
    constructor(settings: AgentSettingsService) : this(
        adapters = mapOf(
            CodexProviderFactory.ENGINE_ID to CodexMcpManagementAdapter(settings),
        ),
    )

    fun defaultAdapter(): McpManagementAdapter = adapterFor(defaultEngineId)

    fun adapterFor(engineId: String): McpManagementAdapter {
        return adapters[engineId]
            ?: error("No MCP management adapter registered for engine '$engineId'.")
    }
}
