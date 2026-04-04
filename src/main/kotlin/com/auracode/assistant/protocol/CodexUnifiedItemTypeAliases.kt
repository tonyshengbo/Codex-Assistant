package com.auracode.assistant.protocol

internal object CodexUnifiedItemTypeAliases {
    const val AGENT_MESSAGE = "agent_message"
    const val REASONING = "reasoning"
    const val COMMAND_EXECUTION = "command_execution"
    const val FILE_CHANGE = "file_change"
    const val CONTEXT_COMPACTION = "context_compaction"
    const val WEB_SEARCH = "web_search"
    const val MCP_TOOL_CALL = "mcp_tool_call"
    const val PLAN = "plan"
    const val USER_MESSAGE = "user_message"

    private val aliases = mapOf(
        "agentmessage" to AGENT_MESSAGE,
        "agent_message" to AGENT_MESSAGE,
        "agent-message" to AGENT_MESSAGE,
        "reasoning" to REASONING,
        "commandexecution" to COMMAND_EXECUTION,
        "command_execution" to COMMAND_EXECUTION,
        "command-execution" to COMMAND_EXECUTION,
        "filechange" to FILE_CHANGE,
        "file_change" to FILE_CHANGE,
        "file-change" to FILE_CHANGE,
        "contextcompaction" to CONTEXT_COMPACTION,
        "context_compaction" to CONTEXT_COMPACTION,
        "context-compaction" to CONTEXT_COMPACTION,
        "websearch" to WEB_SEARCH,
        "web_search" to WEB_SEARCH,
        "web-search" to WEB_SEARCH,
        "mcptoolcall" to MCP_TOOL_CALL,
        "mcp_tool_call" to MCP_TOOL_CALL,
        "mcp-tool-call" to MCP_TOOL_CALL,
        "plan" to PLAN,
        "usermessage" to USER_MESSAGE,
        "user_message" to USER_MESSAGE,
        "user-message" to USER_MESSAGE,
    )

    /**
     * {"method":"item/started","params":{"item":{"type":"agentMessage","id":"msg_09a942ea3f6ca2c70169cde70921d48199b69e918ccb2361b7","text":"","phase":"final_answer"},"threadId":"019d4c4a-bb04-7323-8ac9-67304be49170","turnId":"019d4c4a-bb0a-7ef0-baf2-8d8532016052"}}
     * {"method":"item/started","params":{"item":{"type":"webSearch","id":"ws_05ad61008443747e0169cde801777881989bf6af25c5af80c6","query":"","action":{"type":"other"}},"threadId":"019d4c4a-bb04-7323-8ac9-67304be49170","turnId":"019d4c51-f951-7ba3-9d88-618a99c65ad2"}}
     */
    fun canonicalType(rawType: String?): String? {
        val normalized = rawType
            ?.trim()
            ?.lowercase()
            ?.replace("-", "_")
            .orEmpty()
        if (normalized.isBlank()) return null
        return aliases[normalized] ?: normalized
    }
}
