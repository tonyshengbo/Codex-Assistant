package com.auracode.assistant.integration.build

/**
 * Normalized build problem data that can be reused by UI actions and prompt builders.
 */
data class BuildErrorSnapshot(
    val title: String,
    val detail: String,
    val source: String,
    val filePath: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)
