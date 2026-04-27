package com.auracode.assistant.diff

internal data class FileChangeLineStats(
    val addedLines: Int,
    val deletedLines: Int,
)

internal object FileChangeMetrics {
    fun fromContents(
        oldContent: String?,
        newContent: String?,
    ): FileChangeLineStats? {
        if (oldContent == null || newContent == null) return null
        val oldLines = oldContent.lineSequence().toList()
        val newLines = newContent.lineSequence().toList()
        val lcs = longestCommonSubsequenceLength(oldLines, newLines)
        return FileChangeLineStats(
            addedLines = (newLines.size - lcs).coerceAtLeast(0),
            deletedLines = (oldLines.size - lcs).coerceAtLeast(0),
        )
    }

    private fun longestCommonSubsequenceLength(
        oldLines: List<String>,
        newLines: List<String>,
    ): Int {
        if (oldLines.isEmpty() || newLines.isEmpty()) return 0
        val dp = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
        for (i in oldLines.indices.reversed()) {
            for (j in newLines.indices.reversed()) {
                dp[i][j] = if (oldLines[i] == newLines[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        return dp[0][0]
    }
}
