package com.auracode.assistant.commit

import kotlin.test.Test
import kotlin.test.assertTrue

class CommitMessagePromptFactoryTest {
    @Test
    fun `prompt constrains output to one conventional commit line`() {
        val prompt = CommitMessagePromptFactory.create(
            CommitMessageGenerationContext(
                branchName = "main",
                stagedDiff = "diff --git a.txt b.txt",
                includedFilePaths = listOf("src/Main.kt", "README.md"),
            ),
        )

        assertTrue(prompt.contains("Return exactly one line"))
        assertTrue(prompt.contains("Conventional Commit format"))
        assertTrue(prompt.contains("type: subject"))
        assertTrue(prompt.contains("Do not include a body"))
        assertTrue(prompt.contains("If uncertain, prefer"))
    }
}
