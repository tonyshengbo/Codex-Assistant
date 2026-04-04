package com.auracode.assistant.integration.ide

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdePromptFactoryTest {
    @Test
    fun `selected code prompt includes location and intent`() {
        val prompt = IdePromptFactory.explainSelection(
            filePath = "/src/Main.kt",
            startLine = 10,
            endLine = 18,
        )

        assertTrue(prompt.contains("Explain the selected code"))
        assertTrue(prompt.contains("File: /src/Main.kt"))
        assertTrue(prompt.contains("Lines: 10-18"))
        assertTrue(prompt.contains("why it exists"))
    }

    @Test
    fun `selected code prompt omits lines when unavailable`() {
        val prompt = IdePromptFactory.explainSelection(
            filePath = "/src/Main.kt",
            startLine = null,
            endLine = null,
        )

        assertTrue(prompt.contains("File: /src/Main.kt"))
        assertFalse(prompt.contains("Lines:"))
    }

    @Test
    fun `current file prompt includes requested review areas`() {
        val prompt = IdePromptFactory.explainFile("/src/FeatureService.kt")

        assertTrue(prompt.contains("Explain this file"))
        assertTrue(prompt.contains("File: /src/FeatureService.kt"))
        assertTrue(prompt.contains("key responsibilities"))
        assertTrue(prompt.contains("risks"))
    }
}
