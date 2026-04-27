package com.auracode.assistant.diff

import kotlin.test.Test
import kotlin.test.assertEquals

class FileChangeMetricsTest {
    @Test
    fun `computes added and deleted lines from content snapshots`() {
        val stats = FileChangeMetrics.fromContents(
            oldContent = "a\nb\nc",
            newContent = "a\nb2\nc\nd",
        )

        assertEquals(2, stats?.addedLines)
        assertEquals(1, stats?.deletedLines)
    }
}
