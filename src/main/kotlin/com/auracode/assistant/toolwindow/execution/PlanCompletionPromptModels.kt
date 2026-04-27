package com.auracode.assistant.toolwindow.execution

import com.auracode.assistant.toolwindow.eventing.SubmissionMode

internal data class PlanCompletionPromptUiModel(
    val turnId: String,
    val threadId: String?,
    val body: String,
    val preferredExecutionMode: SubmissionMode,
)

internal data class PlanCompletionPromptState(
    val current: PlanCompletionPromptUiModel? = null,
    val visible: Boolean = false,
)

internal data class PlanCompletionPromptPreview(
    val title: String,
    val summary: String,
)

internal fun PlanCompletionPromptUiModel.compactPreview(): PlanCompletionPromptPreview {
    val normalizedLines = body.lineSequence()
        .mapNotNull(::normalizePlanPromptLine)
        .toList()
    val title = normalizedLines.firstOrNull().orEmpty()
    val summary = normalizedLines.drop(1)
        .take(2)
        .joinToString(" ")
    return PlanCompletionPromptPreview(
        title = title,
        summary = summary,
    )
}

private fun normalizePlanPromptLine(line: String): String? {
    val trimmed = line.trim()
    if (trimmed.isBlank()) return null
    if (trimmed.isGenericPlanSectionHeader()) return null

    val normalized = trimmed
        .replace(Regex("^#{1,6}\\s+"), "")
        .replace(Regex("^\\d+\\.\\s+"), "")
        .replace(Regex("^[-*]\\s+\\[[^\\]]+\\]\\s+"), "")
        .replace(Regex("^[-*]\\s+"), "")
        .replace("`", "")
        .trim()
    return normalized.takeIf { it.isNotBlank() && !it.isGenericPlanSectionHeader() }
}

private fun String.isGenericPlanSectionHeader(): Boolean {
    return lowercase() in setOf(
        "summary",
        "key changes",
        "test plan",
        "assumptions",
        "assumptions and defaults",
    )
}
