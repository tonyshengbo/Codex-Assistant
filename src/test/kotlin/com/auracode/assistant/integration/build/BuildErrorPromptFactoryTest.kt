package com.auracode.assistant.integration.build

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildErrorPromptFactoryTest {
    @Test
    fun `prompt includes normalized build error fields`() {
        val prompt = BuildErrorPromptFactory.create(
            BuildErrorSnapshot(
                title = "Unresolved reference: foo",
                detail = "e: file:///src/Main.kt:12:9 Unresolved reference: foo",
                source = "Gradle Build",
                filePath = "/src/Main.kt",
                line = 12,
                column = 9,
            ),
        )

        assertTrue(prompt.contains("Analyze this build error"))
        assertTrue(prompt.contains("Build Source: Gradle Build"))
        assertTrue(prompt.contains("Error Title: Unresolved reference: foo"))
        assertTrue(prompt.contains("File: /src/Main.kt"))
        assertTrue(prompt.contains("Line: 12, Column: 9"))
        assertTrue(prompt.contains("Unresolved reference: foo"))
    }

    @Test
    fun `prompt omits file block when location is unavailable`() {
        val prompt = BuildErrorPromptFactory.create(
            BuildErrorSnapshot(
                title = "Compilation failed",
                detail = "Compilation failed with exit code 1",
                source = "Build",
            ),
        )

        assertFalse(prompt.contains("File:"))
        assertFalse(prompt.contains("Line:"))
        assertTrue(prompt.contains("Compilation failed with exit code 1"))
    }
}
