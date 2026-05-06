package com.auracode.assistant.toolwindow.settings

/**
 * Stores one aggregated historical overview for the selected engine and range.
 */
internal data class TokenUsageOverview(
    val inputTokens: Long = 0L,
    val cachedInputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val coveredSessionCount: Int = 0,
    val coveredDayCount: Int = 0,
    val includesLegacyFallback: Boolean = false,
) {
    /** Returns the historical total token count using the shared aggregate semantics. */
    val totalTokens: Long
        get() = inputTokens + outputTokens
}

/**
 * Stores one model-level aggregate row for the selected engine and range.
 */
internal data class TokenUsageModelBreakdown(
    val model: String = "",
    val inputTokens: Long = 0L,
    val cachedInputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val includesBaseline: Boolean = false,
) {
    /** Returns the model-level total token count using the shared aggregate semantics. */
    val totalTokens: Long
        get() = inputTokens + outputTokens
}

/**
 * Stores one daily trend point prepared for the Token Usage settings page.
 */
internal data class TokenUsageDailyPoint(
    val dayStartEpochMillis: Long = 0L,
    val inputTokens: Long = 0L,
    val cachedInputTokens: Long = 0L,
    val outputTokens: Long = 0L,
) {
    /** Returns the daily total token count using the shared aggregate semantics. */
    val totalTokens: Long
        get() = inputTokens + outputTokens
}

/**
 * Stores the full read-only historical Token Usage snapshot for one engine and one range.
 */
internal data class TokenUsageStatsSnapshot(
    val engineId: String = TokenUsageSettingsTab.CODEX.engineId,
    val range: TokenUsageRange = TokenUsageRange.LAST_7_DAYS,
    val overview: TokenUsageOverview = TokenUsageOverview(),
    val modelBreakdowns: List<TokenUsageModelBreakdown> = emptyList(),
    val dailyPoints: List<TokenUsageDailyPoint> = emptyList(),
    val hasHistoricalData: Boolean = false,
)
