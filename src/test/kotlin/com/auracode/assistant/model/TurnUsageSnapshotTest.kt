package com.auracode.assistant.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnUsageSnapshotTest {
    @Test
    fun `used percent includes cached and cache creation tokens`() {
        val snapshot = TurnUsageSnapshot(
            model = "gpt-5.4",
            contextWindow = 100,
            inputTokens = 46,
            cachedInputTokens = 10,
            outputTokens = 24,
        )
        // usedTokens = 46 + 10 + 0 + 24 = 80
        assertEquals(80, snapshot.usedPercent())
    }

    @Test
    fun `used percent returns null when context window is not positive`() {
        val snapshot = TurnUsageSnapshot(
            model = "gpt-5.4",
            contextWindow = 0,
            inputTokens = 10,
            cachedInputTokens = 0,
            outputTokens = 5,
        )

        assertNull(snapshot.usedPercent())
    }

    @Test
    fun `used percent caps at 100 when usage exceeds context window`() {
        val snapshot = TurnUsageSnapshot(
            model = "gpt-5.4",
            contextWindow = 100,
            inputTokens = 90,
            cachedInputTokens = 0,
            outputTokens = 35,
        )
        // usedTokens = 125, min(125, 100) = 100 → 100%
        assertEquals(100, snapshot.usedPercent())
    }

    @Test
    fun `used fraction includes cached tokens`() {
        val snapshot = TurnUsageSnapshot(
            model = "gpt-5.4",
            contextWindow = 200,
            inputTokens = 60,
            cachedInputTokens = 10,
            outputTokens = 40,
        )
        // usedTokens = 60 + 10 + 0 + 40 = 110, 110/200 = 0.55
        assertEquals(0.55f, snapshot.usedFraction())
    }

    @Test
    fun `used fraction caps at 1 when total usage exceeds context window`() {
        val snapshot = TurnUsageSnapshot(
            model = "gpt-5.4",
            contextWindow = 100,
            inputTokens = 180,
            cachedInputTokens = 0,
            outputTokens = 45,
        )
        // usedTokens = 225, min(225, 100) = 100 → 1.0
        assertEquals(1.0f, snapshot.usedFraction())
    }

    @Test
    fun `context usage tooltip shows capped usage against context window`() {
        val snapshot = TurnUsageSnapshot(
            model = "gpt-5.4",
            contextWindow = 200_000,
            inputTokens = 100_000,
            cachedInputTokens = 50_000,
            outputTokens = 20_000,
        )
        // usedTokens = 100000 + 50000 + 0 + 20000 = 170000, 170000/200000 = 85%
        val tooltip = snapshot.contextUsageTooltipText()

        assertTrue(tooltip.contains("Used 85%"))
        assertTrue(tooltip.contains("170,000 / 200,000 tokens"))
        assertTrue(tooltip.contains("Input 100,000"))
        assertTrue(tooltip.contains("Output 20,000"))
        assertTrue(tooltip.contains("Cached 50,000"))
        assertTrue(tooltip.contains("Model gpt-5.4"))
    }

    @Test
    fun `context usage tooltip includes cache write tokens`() {
        val snapshot = TurnUsageSnapshot(
            model = "claude-sonnet-4-6",
            contextWindow = 200_000,
            inputTokens = 10_000,
            cachedInputTokens = 40_000,
            cacheCreationInputTokens = 20_000,
            outputTokens = 5_000,
        )
        // usedTokens = 10000 + 40000 + 20000 + 5000 = 75000, 75000/200000 = 37.5% → 38%
        val tooltip = snapshot.contextUsageTooltipText()

        assertTrue(tooltip.contains("Used 38%"))
        assertTrue(tooltip.contains("75,000 / 200,000 tokens"))
        assertTrue(tooltip.contains("Cache write 20,000"))
    }
}
