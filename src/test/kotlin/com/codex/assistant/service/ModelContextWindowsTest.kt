package com.codex.assistant.service

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelContextWindowsTest {
    @Test
    fun `resolves official codex mini context window separately`() {
        assertEquals(200_000, ModelContextWindows.resolve("codex-mini-latest"))
    }

    @Test
    fun `keeps gpt 5 family context window at four hundred thousand`() {
        assertEquals(400_000, ModelContextWindows.resolve("gpt-5.2-codex"))
        assertEquals(400_000, ModelContextWindows.resolve("gpt-5-codex"))
        assertEquals(400_000, ModelContextWindows.resolve("gpt-5.3-codex"))
    }
}
