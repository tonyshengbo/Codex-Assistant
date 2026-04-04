package com.auracode.assistant.commit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommitMessageContextServiceTest {
    @Test
    fun `collects staged diff branch and included file paths`() {
        val service = CommitMessageContextService(
            stagedDiffProvider = { "diff --git a/src/Main.kt b/src/Main.kt" },
            branchNameProvider = { "feature/commit-message" },
            includedFilePathsProvider = { listOf("src/Main.kt", "README.md") },
        )

        val context = service.collect(includedChanges = emptyList())

        requireNotNull(context)
        assertEquals("feature/commit-message", context.branchName)
        assertEquals("diff --git a/src/Main.kt b/src/Main.kt", context.stagedDiff)
        assertEquals(listOf("README.md", "src/Main.kt"), context.includedFilePaths)
    }

    @Test
    fun `returns null when there is no diff and no included files`() {
        val service = CommitMessageContextService(
            stagedDiffProvider = { "" },
            branchNameProvider = { null },
            includedFilePathsProvider = { emptyList() },
        )

        val context = service.collect(includedChanges = emptyList())

        assertNull(context)
    }
}
