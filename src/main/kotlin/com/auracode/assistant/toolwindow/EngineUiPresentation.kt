package com.auracode.assistant.toolwindow

import com.auracode.assistant.provider.EngineDescriptor

internal fun engineDisplayLabel(
    engineId: String,
    availableEngines: List<EngineDescriptor> = emptyList(),
): String {
    val normalizedEngineId = engineId.trim()
    availableEngines.firstOrNull { it.id == normalizedEngineId }?.displayName?.trim()?.takeIf { it.isNotBlank() }?.let {
        return it
    }
    return when (normalizedEngineId.lowercase()) {
        "claude" -> "Claude"
        "codex" -> "Codex"
        else -> normalizedEngineId.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }
    }
}
