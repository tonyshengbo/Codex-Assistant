package com.auracode.assistant.commit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommitMessageOutputSanitizerTest {
    @Test
    fun `extracts first conventional commit line from verbose output`() {
        val sanitized = CommitMessageOutputSanitizer.sanitize(
            """
            Here is the commit message:
            feat: add Aura commit message generation
            
            This summarizes the selected changes.
            """.trimIndent(),
        )

        assertEquals("feat: add Aura commit message generation", sanitized)
    }

    @Test
    fun `rejects output without conventional commit subject`() {
        assertFailsWith<IllegalArgumentException> {
            CommitMessageOutputSanitizer.sanitize("add Aura commit message generation")
        }
    }
}
