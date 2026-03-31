package com.auracode.assistant.integration.build

/**
 * Builds a deterministic prompt for compile and build problems.
 */
object BuildErrorPromptFactory {
    fun create(snapshot: BuildErrorSnapshot): String {
        val location = buildString {
            snapshot.filePath?.takeIf(String::isNotBlank)?.let {
                appendLine("File: $it")
            }
            if (snapshot.line != null) {
                append("Line: ${snapshot.line}")
                snapshot.column?.let { column ->
                    append(", Column: $column")
                }
                appendLine()
            }
        }.trim()

        return buildString {
            appendLine("Analyze this build error, explain the likely root cause, and propose a fix.")
            appendLine()
            appendLine("Build Source: ${snapshot.source}")
            appendLine("Error Title: ${snapshot.title}")
            if (location.isNotBlank()) {
                appendLine(location)
            }
            appendLine()
            appendLine("Error Details:")
            appendLine(snapshot.detail)
        }.trim()
    }
}
