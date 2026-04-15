package com.auracode.assistant.commit

internal data class CommitMessageGenerationContext(
    val changeSummary: String?,
    val includedFilePaths: List<String>,
)
