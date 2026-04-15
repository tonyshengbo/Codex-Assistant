package com.auracode.assistant.commit

/**
 * Builds the prompt used for commit-message generation requests.
 */
internal object CommitMessagePromptFactory {
    fun create(context: CommitMessageGenerationContext): String {
        val fileSummary = context.includedFilePaths.joinToString(separator = "\n") { "- $it" }
            .ifBlank { "- <none>" }
        val diffHint = if (context.changeSummary.isNullOrBlank()) {
            "No detailed VCS change summary is attached. Rely on the included file list."
        } else {
            "A VCS change summary is attached as context."
        }
        return buildString {
            appendLine("Generate a git commit message for the changes selected for the commit.")
            appendLine("Return exactly one line in Conventional Commit format: type: subject")
            appendLine("Allowed types: feat, fix, refactor, chore, docs, test, build, ci, perf")
            appendLine("Do not include a body, bullets, quotes, code fences, or explanations.")
            appendLine("Use concise English and keep the subject specific to the actual changes.")
            appendLine("If uncertain, prefer chore: or refactor:.")
            appendLine()
            appendLine(diffHint)
            appendLine("Included files:")
            appendLine(fileSummary)
            if (!context.changeSummary.isNullOrBlank()) {
                appendLine()
                appendLine("The following VCS change summary is attached:")
                appendLine(context.changeSummary)
            }
        }.trim()
    }
}
