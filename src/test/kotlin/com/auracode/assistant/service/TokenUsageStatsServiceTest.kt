package com.auracode.assistant.service

import com.auracode.assistant.model.TurnUsageSnapshot
import com.auracode.assistant.persistence.chat.PersistedChatSession
import com.auracode.assistant.persistence.chat.PersistedSessionUsageLedgerEntry
import com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository
import com.auracode.assistant.toolwindow.settings.TokenUsageRange
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenUsageStatsServiceTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `all range includes baseline in overview and model breakdown but excludes it from trend`() {
        val repository = SQLiteChatSessionRepository(createTempDirectory("token-usage-stats-all").resolve("chat.db"))
        repository.upsertSession(
            PersistedChatSession(
                id = "session-1",
                providerId = "codex",
                title = "Session 1",
                createdAt = dayMillis("2026-04-20"),
                updatedAt = dayMillis("2026-05-04"),
                messageCount = 1,
                remoteConversationId = "thread-1",
                usageSnapshot = null,
                isActive = true,
            ),
        )
        repository.appendRecord(
            PersistedSessionUsageLedgerEntry(
                id = "ledger-1",
                sessionId = "session-1",
                providerId = "codex",
                model = "gpt-5.4",
                contextWindow = 400_000,
                inputTokens = 100,
                cachedInputTokens = 20,
                outputTokens = 10,
                capturedAt = dayMillis("2026-04-20"),
                sourceTurnId = "turn-1",
                isBaseline = true,
            ),
        )
        repository.appendRecord(
            PersistedSessionUsageLedgerEntry(
                id = "ledger-2",
                sessionId = "session-1",
                providerId = "codex",
                model = "gpt-5.4",
                contextWindow = 400_000,
                inputTokens = 160,
                cachedInputTokens = 35,
                outputTokens = 25,
                capturedAt = dayMillis("2026-05-04"),
                sourceTurnId = "turn-2",
                isBaseline = false,
            ),
        )

        val snapshot = createService(repository).load(
            sessions = listOf(sessionSummary(id = "session-1", providerId = "codex")),
            engineId = "codex",
            range = TokenUsageRange.ALL,
        )

        assertEquals(160L, snapshot.overview.inputTokens)
        assertEquals(35L, snapshot.overview.cachedInputTokens)
        assertEquals(25L, snapshot.overview.outputTokens)
        assertEquals(1, snapshot.overview.coveredSessionCount)
        assertEquals(2, snapshot.overview.coveredDayCount)
        assertEquals(1, snapshot.modelBreakdowns.size)
        assertTrue(snapshot.modelBreakdowns.single().includesBaseline)
        assertEquals(60L, snapshot.dailyPoints.single().inputTokens)
        assertEquals(dayMillis("2026-05-04"), snapshot.dailyPoints.single().dayStartEpochMillis)
    }

    @Test
    fun `last 7 days keeps legacy snapshot fallback in totals while leaving daily trend empty`() {
        val repository = SQLiteChatSessionRepository(createTempDirectory("token-usage-stats-fallback").resolve("chat.db"))
        repository.upsertSession(
            PersistedChatSession(
                id = "session-1",
                providerId = "codex",
                title = "Recent session",
                createdAt = dayMillis("2026-05-04"),
                updatedAt = dayMillis("2026-05-04"),
                messageCount = 1,
                remoteConversationId = "thread-1",
                usageSnapshot = TurnUsageSnapshot(
                    model = "",
                    contextWindow = 400_000,
                    inputTokens = 50,
                    cachedInputTokens = 5,
                    outputTokens = 10,
                    capturedAt = dayMillis("2026-05-04"),
                ),
                isActive = true,
            ),
        )
        repository.upsertSession(
            PersistedChatSession(
                id = "session-2",
                providerId = "codex",
                title = "Old session",
                createdAt = dayMillis("2026-04-20"),
                updatedAt = dayMillis("2026-04-20"),
                messageCount = 1,
                remoteConversationId = "thread-2",
                usageSnapshot = TurnUsageSnapshot(
                    model = "gpt-5.4",
                    contextWindow = 400_000,
                    inputTokens = 80,
                    cachedInputTokens = 8,
                    outputTokens = 12,
                    capturedAt = dayMillis("2026-04-20"),
                ),
                isActive = false,
            ),
        )

        val snapshot = createService(repository).load(
            sessions = listOf(
                sessionSummary(
                    id = "session-1",
                    providerId = "codex",
                    usageSnapshot = TurnUsageSnapshot(
                        model = "",
                        contextWindow = 400_000,
                        inputTokens = 50,
                        cachedInputTokens = 5,
                        outputTokens = 10,
                        capturedAt = dayMillis("2026-05-04"),
                    ),
                ),
                sessionSummary(
                    id = "session-2",
                    providerId = "codex",
                    usageSnapshot = TurnUsageSnapshot(
                        model = "gpt-5.4",
                        contextWindow = 400_000,
                        inputTokens = 80,
                        cachedInputTokens = 8,
                        outputTokens = 12,
                        capturedAt = dayMillis("2026-04-20"),
                    ),
                ),
            ),
            engineId = "codex",
            range = TokenUsageRange.LAST_7_DAYS,
        )

        assertEquals(50L, snapshot.overview.inputTokens)
        assertEquals(5L, snapshot.overview.cachedInputTokens)
        assertEquals(10L, snapshot.overview.outputTokens)
        assertTrue(snapshot.overview.includesLegacyFallback)
        assertEquals(1, snapshot.modelBreakdowns.size)
        assertEquals("", snapshot.modelBreakdowns.single().model)
        assertTrue(snapshot.dailyPoints.isEmpty())
        assertTrue(snapshot.hasHistoricalData)
    }

    @Test
    fun `first non baseline ledger record contributes to short range totals and trend`() {
        val repository = SQLiteChatSessionRepository(createTempDirectory("token-usage-stats-first-record").resolve("chat.db"))
        repository.upsertSession(
            PersistedChatSession(
                id = "session-1",
                providerId = "codex",
                title = "Session 1",
                createdAt = dayMillis("2026-05-04"),
                updatedAt = dayMillis("2026-05-04"),
                messageCount = 1,
                remoteConversationId = "thread-1",
                usageSnapshot = null,
                isActive = true,
            ),
        )
        repository.appendRecord(
            PersistedSessionUsageLedgerEntry(
                id = "ledger-1",
                sessionId = "session-1",
                providerId = "codex",
                model = "gpt-5.4",
                contextWindow = 400_000,
                inputTokens = 75,
                cachedInputTokens = 6,
                outputTokens = 12,
                capturedAt = dayMillis("2026-05-04"),
                sourceTurnId = "turn-1",
                isBaseline = false,
            ),
        )

        val snapshot = createService(repository).load(
            sessions = listOf(sessionSummary(id = "session-1", providerId = "codex")),
            engineId = "codex",
            range = TokenUsageRange.LAST_7_DAYS,
        )

        assertEquals(75L, snapshot.overview.inputTokens)
        assertEquals(6L, snapshot.overview.cachedInputTokens)
        assertEquals(12L, snapshot.overview.outputTokens)
        assertEquals(1, snapshot.dailyPoints.size)
        assertEquals(75L, snapshot.dailyPoints.single().inputTokens)
        assertTrue(snapshot.hasHistoricalData)
    }

    @Test
    fun `clamps negative deltas to zero for overview and trend`() {
        val repository = SQLiteChatSessionRepository(createTempDirectory("token-usage-stats-negative").resolve("chat.db"))
        repository.upsertSession(
            PersistedChatSession(
                id = "session-1",
                providerId = "codex",
                title = "Session 1",
                createdAt = dayMillis("2026-05-01"),
                updatedAt = dayMillis("2026-05-02"),
                messageCount = 1,
                remoteConversationId = "thread-1",
                usageSnapshot = null,
                isActive = true,
            ),
        )
        repository.appendRecord(
            PersistedSessionUsageLedgerEntry(
                id = "ledger-1",
                sessionId = "session-1",
                providerId = "codex",
                model = "gpt-5.4",
                contextWindow = 400_000,
                inputTokens = 100,
                cachedInputTokens = 20,
                outputTokens = 10,
                capturedAt = dayMillis("2026-05-01"),
                sourceTurnId = "turn-1",
                isBaseline = true,
            ),
        )
        repository.appendRecord(
            PersistedSessionUsageLedgerEntry(
                id = "ledger-2",
                sessionId = "session-1",
                providerId = "codex",
                model = "gpt-5.4",
                contextWindow = 400_000,
                inputTokens = 80,
                cachedInputTokens = 10,
                outputTokens = 5,
                capturedAt = dayMillis("2026-05-02"),
                sourceTurnId = "turn-2",
                isBaseline = false,
            ),
        )

        val snapshot = createService(repository).load(
            sessions = listOf(sessionSummary(id = "session-1", providerId = "codex")),
            engineId = "codex",
            range = TokenUsageRange.LAST_7_DAYS,
        )

        assertEquals(0L, snapshot.overview.inputTokens)
        assertEquals(0L, snapshot.overview.cachedInputTokens)
        assertEquals(0L, snapshot.overview.outputTokens)
        assertTrue(snapshot.dailyPoints.isEmpty())
        assertFalse(snapshot.hasHistoricalData)
    }

    private fun createService(repository: SQLiteChatSessionRepository): TokenUsageStatsService {
        return TokenUsageStatsService(
            loadLedgerRecords = { providerId -> repository.listRecordsByProvider(providerId) },
            clock = clock,
        )
    }

    private fun sessionSummary(
        id: String,
        providerId: String,
        usageSnapshot: TurnUsageSnapshot? = null,
    ): AgentChatService.SessionSummary {
        return AgentChatService.SessionSummary(
            id = id,
            title = id,
            updatedAt = usageSnapshot?.capturedAt ?: dayMillis("2026-05-05"),
            messageCount = 1,
            remoteConversationId = "thread-$id",
            usageSnapshot = usageSnapshot,
            isRunning = false,
            providerId = providerId,
        )
    }

    private fun dayMillis(day: String): Long {
        return LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}
