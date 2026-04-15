package com.auracode.assistant.commit

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change

@Service(Service.Level.PROJECT)
internal class CommitMessageContextService(
    project: Project? = null,
    private val changeSummaryProvider: (List<Change>) -> String? = CommitChangeSummaryRenderer::fromChanges,
    private val includedFilePathsProvider: (List<Change>) -> List<String> = CommitIncludedFilesExtractor::fromChanges,
) {
    fun collect(
        includedChanges: List<Change>,
        includedUnversionedFiles: List<String> = emptyList(),
    ): CommitMessageGenerationContext? {
        val includedFilePaths = includedFilePathsProvider(includedChanges)
            .plus(includedUnversionedFiles)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        val changeSummary = changeSummaryProvider(includedChanges)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (changeSummary == null && includedFilePaths.isEmpty()) return null
        return CommitMessageGenerationContext(
            changeSummary = changeSummary,
            includedFilePaths = includedFilePaths,
        )
    }
}
