package com.auracode.assistant.commit

import kotlin.test.Test
import kotlin.test.assertTrue

class CommitMessagePromptFactoryTest {
    @Test
    fun `prompt constrains output to one conventional commit line and references generic vcs context`() {
        val prompt = CommitMessagePromptFactory.create(
            CommitMessageGenerationContext(
                changeSummary = "MODIFICATION: src/Main.kt\n- old\n+ new",
                includedFilePaths = listOf("src/Main.kt", "README.md"),
            ),
        )

        assertTrue(prompt.contains("Return exactly one line"))
        assertTrue(prompt.contains("Conventional Commit format"))
        assertTrue(prompt.contains("type: subject"))
        assertTrue(prompt.contains("Do not include a body"))
        assertTrue(prompt.contains("If uncertain, prefer"))
        assertTrue(prompt.contains("selected for the commit"))
        assertTrue(prompt.contains("change summary is attached"))
        assertTrue(!prompt.contains("Current branch"))
    }
}
