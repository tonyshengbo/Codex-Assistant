package com.auracode.assistant.protocol

internal class CodexUnifiedParserState {
    var activeTurnId: String? = null
    var activeThreadId: String? = null

    val narrativeBuffers = mutableMapOf<String, StringBuilder>()
    val activityOutputBuffers = mutableMapOf<String, StringBuilder>()
    val planBuffers = mutableMapOf<String, StringBuilder>()
    val itemSnapshots = mutableMapOf<String, UnifiedItem>()
    val contextCompactionItemIdsByTurnId = mutableMapOf<String, String>()

    fun planItemId(turnId: String?): String {
        val normalized = turnId?.trim().orEmpty()
        return if (normalized.isBlank()) "plan" else "plan:$normalized"
    }
}
