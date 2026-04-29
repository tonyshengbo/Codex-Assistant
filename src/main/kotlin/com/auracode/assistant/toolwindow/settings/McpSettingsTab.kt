package com.auracode.assistant.toolwindow.settings

/** Represents the active engine tab rendered inside the MCP settings page. */
internal enum class McpSettingsTab(
    val engineId: String,
) {
    CODEX("codex"),
    CLAUDE("claude"),
}

/** Maps an engine id to the matching MCP settings tab. */
internal fun mcpSettingsTabForEngine(engineId: String): McpSettingsTab {
    return when (engineId.trim().lowercase()) {
        McpSettingsTab.CLAUDE.engineId -> McpSettingsTab.CLAUDE
        else -> McpSettingsTab.CODEX
    }
}
