package com.codex.assistant.util

import com.codex.assistant.model.AgentEvent

private val commandRegex = Regex("^COMMAND:\\s*(.+)$", RegexOption.MULTILINE)
private val diffBlockRegex = Regex(
    "DIFF_FILE:\\s*(.+?)\\R\\s*DIFF_CONTENT_START\\R([\\s\\S]*?)\\R\\s*DIFF_CONTENT_END",
    setOf(RegexOption.MULTILINE),
)

fun extractCommandProposals(text: String, cwd: String): List<AgentEvent.CommandProposal> {
    return commandRegex.findAll(text).map { match ->
        AgentEvent.CommandProposal(match.groupValues[1].trim(), cwd)
    }.toList()
}

fun extractDiffProposal(text: String): AgentEvent.DiffProposal? {
    return extractDiffProposals(text).firstOrNull()
}

fun extractDiffProposals(text: String): List<AgentEvent.DiffProposal> {
    return diffBlockRegex.findAll(text).mapNotNull { match ->
        val filePath = match.groupValues[1].trim()
        if (!isConcreteDiffPath(filePath)) {
            return@mapNotNull null
        }
        val newContent = match.groupValues[2]
        AgentEvent.DiffProposal(filePath = filePath, newContent = newContent)
    }.toList()
}

private fun isConcreteDiffPath(path: String): Boolean {
    if (path.isBlank()) return false
    val normalized = path.trim().lowercase()
    if (normalized.startsWith("<") && normalized.endsWith(">")) return false
    if (normalized == "path" || normalized == "file" || normalized == "filepath") return false
    if (normalized.contains("path/to/") || normalized.contains("your/file")) return false
    return true
}
