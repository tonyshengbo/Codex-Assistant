package com.codex.assistant.util

import kotlin.test.Test
import kotlin.test.assertEquals

class OutputParsersTest {
    @Test
    fun `extracts command proposals from text markers`() {
        val text = """
            COMMAND: npm test
            COMMAND: ./gradlew buildPlugin
        """.trimIndent()

        val proposals = extractCommandProposals(text, "/tmp/repo")

        assertEquals(2, proposals.size)
        assertEquals("npm test", proposals[0].command)
        assertEquals("/tmp/repo", proposals[0].cwd)
    }

    @Test
    fun `extracts multiple diff proposals and filters placeholders`() {
        val text = """
            DIFF_FILE: src/A.kt
            DIFF_CONTENT_START
            new A
            DIFF_CONTENT_END

            DIFF_FILE: <path>
            DIFF_CONTENT_START
            invalid
            DIFF_CONTENT_END

            DIFF_FILE: src/B.kt
            DIFF_CONTENT_START
            new B
            DIFF_CONTENT_END
        """.trimIndent()

        val proposals = extractDiffProposals(text)

        assertEquals(2, proposals.size)
        assertEquals("src/A.kt", proposals[0].filePath)
        assertEquals("src/B.kt", proposals[1].filePath)
    }
}
