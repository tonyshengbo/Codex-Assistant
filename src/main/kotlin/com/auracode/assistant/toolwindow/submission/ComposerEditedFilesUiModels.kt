package com.auracode.assistant.toolwindow.submission

internal data class EditedFilesPanelUiModel(
    val summary: EditedFilesSummaryUiModel,
    val files: List<EditedFileRowUiModel>,
)

internal data class EditedFilesSummaryUiModel(
    val totalFiles: Int,
)

internal data class EditedFileRowUiModel(
    val path: String,
    val displayName: String,
    val parentPath: String,
    val latestAddedLines: Int?,
    val latestDeletedLines: Int?,
)

internal fun ComposerAreaState.toEditedFilesPanelUiModel(): EditedFilesPanelUiModel {
    return EditedFilesPanelUiModel(
        summary = EditedFilesSummaryUiModel(
            totalFiles = editedFilesSummary.total,
        ),
        files = editedFiles.map { file ->
            EditedFileRowUiModel(
                path = file.path,
                displayName = file.displayName,
                parentPath = file.path.toParentDisplayPath(),
                latestAddedLines = file.latestAddedLines,
                latestDeletedLines = file.latestDeletedLines,
            )
        },
    )
}

private fun String.toParentDisplayPath(): String {
    val normalized = replace('\\', '/')
    val segments = normalized.split('/').filter { it.isNotBlank() }
    if (segments.size <= 1) return ""
    val parentSegments = segments.dropLast(1)
    return parentSegments.takeLast(3).joinToString("/")
}
