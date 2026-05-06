package com.auracode.assistant.service

import com.auracode.assistant.model.TurnUsageSnapshot
import com.auracode.assistant.persistence.chat.PersistedSessionUsageLedgerEntry
import com.auracode.assistant.toolwindow.settings.TokenUsageDailyPoint
import com.auracode.assistant.toolwindow.settings.TokenUsageModelBreakdown
import com.auracode.assistant.toolwindow.settings.TokenUsageOverview
import com.auracode.assistant.toolwindow.settings.TokenUsageRange
import com.auracode.assistant.toolwindow.settings.TokenUsageSettingsTab
import com.auracode.assistant.toolwindow.settings.TokenUsageStatsSnapshot
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Builds the Token Usage settings snapshot from ledger records and legacy session fallbacks.
 */
internal class TokenUsageStatsService(
    private val loadLedgerRecords: (String) -> List<PersistedSessionUsageLedgerEntry>,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    /**
     * Loads one complete Token Usage snapshot for the selected engine and range.
     */
    fun load(
        sessions: List<AgentChatService.SessionSummary>,
        engineId: String,
        range: TokenUsageRange,
    ): TokenUsageStatsSnapshot {
        val normalizedEngineId = engineId.trim().ifBlank { TokenUsageSettingsTab.CODEX.engineId }
        val engineSessions = sessions.filter { it.providerId == normalizedEngineId }
        val ledgerRecords = loadLedgerRecords(normalizedEngineId)
        val aggregation = aggregateHistoricalUsage(
            sessions = engineSessions,
            ledgerRecords = ledgerRecords,
            range = range,
        )
        return TokenUsageStatsSnapshot(
            engineId = normalizedEngineId,
            range = range,
            overview = aggregation.toOverview(),
            modelBreakdowns = aggregation.toModelBreakdowns(),
            dailyPoints = aggregation.toDailyPoints(),
            hasHistoricalData = aggregation.hasHistoricalData(),
        )
    }

    /**
     * Aggregates historical totals, model breakdowns, and daily trend points for one engine.
     */
    private fun aggregateHistoricalUsage(
        sessions: List<AgentChatService.SessionSummary>,
        ledgerRecords: List<PersistedSessionUsageLedgerEntry>,
        range: TokenUsageRange,
    ): HistoricalAggregation {
        val aggregation = HistoricalAggregation()
        val ledgerSessionIds = ledgerRecords.asSequence().map { it.sessionId }.toSet()
        ledgerRecords.groupBy { it.sessionId }.forEach { (sessionId, sessionRecords) ->
            aggregateSessionLedger(
                aggregation = aggregation,
                sessionId = sessionId,
                records = sessionRecords.sortedWith(compareBy(PersistedSessionUsageLedgerEntry::capturedAt, PersistedSessionUsageLedgerEntry::id)),
                range = range,
            )
        }
        sessions.asSequence()
            .filter { it.id !in ledgerSessionIds }
            .mapNotNull { session -> session.usageSnapshot?.let { snapshot -> session to snapshot } }
            .filter { (_, snapshot) -> range.contains(snapshot.capturedAt, clock) }
            .forEach { (session, snapshot) ->
                val contribution = snapshot.toUsageContribution()
                aggregation.addHistoricalContribution(
                    sessionId = session.id,
                    capturedAt = snapshot.capturedAt,
                    model = snapshot.model,
                    contribution = contribution,
                    includesBaseline = false,
                    legacyFallback = !contribution.isZero(),
                )
            }
        return aggregation
    }

    /**
     * Aggregates one session's ordered ledger history into overview and trend contributions.
     */
    private fun aggregateSessionLedger(
        aggregation: HistoricalAggregation,
        sessionId: String,
        records: List<PersistedSessionUsageLedgerEntry>,
        range: TokenUsageRange,
    ) {
        if (records.isEmpty()) return
        records.firstOrNull()?.takeIf { !it.isBaseline && range.contains(it.capturedAt, clock) }?.let { first ->
            val firstContribution = first.toUsageContribution()
            if (!firstContribution.isZero()) {
                aggregation.addHistoricalContribution(
                    sessionId = sessionId,
                    capturedAt = first.capturedAt,
                    model = first.model,
                    contribution = firstContribution,
                    includesBaseline = false,
                    legacyFallback = false,
                )
                aggregation.addDailyTrendContribution(
                    capturedAt = first.capturedAt,
                    contribution = firstContribution,
                )
            }
        }
        if (range == TokenUsageRange.ALL) {
            records.firstOrNull()?.takeIf { it.isBaseline }?.let { baseline ->
                aggregation.addHistoricalContribution(
                    sessionId = sessionId,
                    capturedAt = baseline.capturedAt,
                    model = baseline.model,
                    contribution = baseline.toUsageContribution(),
                    includesBaseline = true,
                    legacyFallback = false,
                )
            }
        }
        var previous = records.first()
        records.drop(1).forEach { current ->
            val delta = previous.deltaTo(current)
            previous = current
            if (current.isBaseline) return@forEach
            if (!range.contains(current.capturedAt, clock) || delta.isZero()) return@forEach
            aggregation.addHistoricalContribution(
                sessionId = sessionId,
                capturedAt = current.capturedAt,
                model = current.model,
                contribution = delta,
                includesBaseline = false,
                legacyFallback = false,
            )
            aggregation.addDailyTrendContribution(
                capturedAt = current.capturedAt,
                contribution = delta,
            )
        }
    }

    /**
     * Converts one usage snapshot into a long-based contribution.
     */
    private fun TurnUsageSnapshot.toUsageContribution(): UsageContribution {
        return UsageContribution(
            inputTokens = inputTokens.toLong(),
            cachedInputTokens = cachedInputTokens.toLong(),
            outputTokens = outputTokens.toLong(),
        )
    }

    /**
     * Converts one ledger record into a long-based contribution.
     */
    private fun PersistedSessionUsageLedgerEntry.toUsageContribution(): UsageContribution {
        return UsageContribution(
            inputTokens = inputTokens.toLong(),
            cachedInputTokens = cachedInputTokens.toLong(),
            outputTokens = outputTokens.toLong(),
        )
    }

    /**
     * Converts two ordered ledger snapshots into one non-negative delta contribution.
     */
    private fun PersistedSessionUsageLedgerEntry.deltaTo(next: PersistedSessionUsageLedgerEntry): UsageContribution {
        return UsageContribution(
            inputTokens = (next.inputTokens - inputTokens).toLong().coerceAtLeast(0L),
            cachedInputTokens = (next.cachedInputTokens - cachedInputTokens).toLong().coerceAtLeast(0L),
            outputTokens = (next.outputTokens - outputTokens).toLong().coerceAtLeast(0L),
        )
    }

    /**
     * Resolves whether one timestamp belongs to the selected historical range.
     */
    private fun TokenUsageRange.contains(capturedAt: Long, clock: Clock): Boolean {
        if (this == TokenUsageRange.ALL) return true
        if (capturedAt <= 0L) return false
        return capturedAt >= lowerBoundMillis(clock)
    }

    /**
     * Resolves the inclusive lower-bound timestamp for one non-ALL range.
     */
    private fun TokenUsageRange.lowerBoundMillis(clock: Clock): Long {
        val zoneId = clock.zone
        val today = LocalDate.now(clock)
        val startDay = when (this) {
            TokenUsageRange.LAST_7_DAYS -> today.minusDays(6)
            TokenUsageRange.LAST_30_DAYS -> today.minusDays(29)
            TokenUsageRange.ALL -> LocalDate.ofEpochDay(0)
        }
        return startDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    /**
     * Resolves the local date bucket for one timestamp.
     */
    private fun toLocalDate(capturedAt: Long): LocalDate {
        return Instant.ofEpochMilli(capturedAt).atZone(clock.zone).toLocalDate()
    }

    /**
     * Stores one long-based usage contribution used during aggregation.
     */
    private data class UsageContribution(
        val inputTokens: Long,
        val cachedInputTokens: Long,
        val outputTokens: Long,
    ) {
        /**
         * Returns whether this contribution carries any observable usage.
         */
        fun isZero(): Boolean {
            return inputTokens == 0L && cachedInputTokens == 0L && outputTokens == 0L
        }
    }

    /**
     * Stores one mutable model aggregate during snapshot construction.
     */
    private data class MutableModelAggregate(
        var inputTokens: Long = 0L,
        var cachedInputTokens: Long = 0L,
        var outputTokens: Long = 0L,
        var includesBaseline: Boolean = false,
    )

    /**
     * Stores one mutable full-page historical aggregate during snapshot construction.
     */
    private inner class HistoricalAggregation {
        private val zoneId: ZoneId = clock.zone
        private val overview = MutableModelAggregate()
        private val models = linkedMapOf<String, MutableModelAggregate>()
        private val dailyTrend = linkedMapOf<LocalDate, MutableModelAggregate>()
        private val coveredSessionIds = linkedSetOf<String>()
        private val coveredDays = linkedSetOf<LocalDate>()
        private var includesLegacyFallback: Boolean = false

        /**
         * Adds one overview and model contribution.
         */
        fun addHistoricalContribution(
            sessionId: String,
            capturedAt: Long,
            model: String,
            contribution: UsageContribution,
            includesBaseline: Boolean,
            legacyFallback: Boolean,
        ) {
            if (contribution.isZero()) return
            overview.inputTokens += contribution.inputTokens
            overview.cachedInputTokens += contribution.cachedInputTokens
            overview.outputTokens += contribution.outputTokens
            overview.includesBaseline = overview.includesBaseline || includesBaseline
            val bucket = models.getOrPut(normalizeModel(model)) { MutableModelAggregate() }
            bucket.inputTokens += contribution.inputTokens
            bucket.cachedInputTokens += contribution.cachedInputTokens
            bucket.outputTokens += contribution.outputTokens
            bucket.includesBaseline = bucket.includesBaseline || includesBaseline
            coveredSessionIds += sessionId
            if (capturedAt > 0L) {
                coveredDays += toLocalDate(capturedAt)
            }
            includesLegacyFallback = includesLegacyFallback || legacyFallback
        }

        /**
         * Adds one daily trend contribution.
         */
        fun addDailyTrendContribution(
            capturedAt: Long,
            contribution: UsageContribution,
        ) {
            if (contribution.isZero() || capturedAt <= 0L) return
            val day = Instant.ofEpochMilli(capturedAt).atZone(zoneId).toLocalDate()
            val bucket = dailyTrend.getOrPut(day) { MutableModelAggregate() }
            bucket.inputTokens += contribution.inputTokens
            bucket.cachedInputTokens += contribution.cachedInputTokens
            bucket.outputTokens += contribution.outputTokens
        }

        /**
         * Converts the mutable overview aggregate into the page overview model.
         */
        fun toOverview(): TokenUsageOverview {
            return TokenUsageOverview(
                inputTokens = overview.inputTokens,
                cachedInputTokens = overview.cachedInputTokens,
                outputTokens = overview.outputTokens,
                coveredSessionCount = coveredSessionIds.size,
                coveredDayCount = coveredDays.size,
                includesLegacyFallback = includesLegacyFallback,
            )
        }

        /**
         * Converts the mutable model aggregates into sorted read-only breakdown rows.
         */
        fun toModelBreakdowns(): List<TokenUsageModelBreakdown> {
            return models.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, MutableModelAggregate>> {
                        it.value.inputTokens + it.value.outputTokens
                    }.thenBy { it.key },
                )
                .map { (model, aggregate) ->
                    TokenUsageModelBreakdown(
                        model = model,
                        inputTokens = aggregate.inputTokens,
                        cachedInputTokens = aggregate.cachedInputTokens,
                        outputTokens = aggregate.outputTokens,
                        includesBaseline = aggregate.includesBaseline,
                    )
                }
        }

        /**
         * Converts the mutable daily trend aggregates into reverse-chronological rows.
         */
        fun toDailyPoints(): List<TokenUsageDailyPoint> {
            return dailyTrend.entries
                .sortedByDescending { it.key }
                .map { (day, aggregate) ->
                    TokenUsageDailyPoint(
                        dayStartEpochMillis = day.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                        inputTokens = aggregate.inputTokens,
                        cachedInputTokens = aggregate.cachedInputTokens,
                        outputTokens = aggregate.outputTokens,
                    )
                }
        }

        /**
         * Returns whether any historical contribution was aggregated.
         */
        fun hasHistoricalData(): Boolean {
            return coveredSessionIds.isNotEmpty()
        }

        /**
         * Normalizes empty model identifiers into the shared Unknown bucket.
         */
        private fun normalizeModel(model: String): String {
            return model.trim()
        }
    }
}
