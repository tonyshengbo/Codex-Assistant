package com.auracode.assistant.toolwindow.settings

/** Represents the active engine tab rendered inside the Token Usage settings page. */
internal enum class TokenUsageSettingsTab(
    val engineId: String,
) {
    CODEX("codex"),
    CLAUDE("claude"),
}

/** Maps an engine id to the matching Token Usage settings tab. */
internal fun tokenUsageSettingsTabForEngine(engineId: String): TokenUsageSettingsTab {
    return when (engineId.trim().lowercase()) {
        TokenUsageSettingsTab.CLAUDE.engineId -> TokenUsageSettingsTab.CLAUDE
        else -> TokenUsageSettingsTab.CODEX
    }
}

/** Describes the historical range currently selected inside the Token Usage page. */
internal enum class TokenUsageRange {
    LAST_7_DAYS,
    LAST_30_DAYS,
    ALL,
}

/** Builds the stable cache key used for one Token Usage engine-and-range selection. */
internal fun tokenUsageScopeKey(
    engineId: String,
    range: TokenUsageRange,
): String {
    val normalizedEngineId = engineId.trim().ifBlank { TokenUsageSettingsTab.CODEX.engineId }
    return "$normalizedEngineId|${range.name}"
}
