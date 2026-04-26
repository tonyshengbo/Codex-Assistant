package com.auracode.assistant.toolwindow.conversation

internal enum class TimelineDiffLineKind {
    META,
    HUNK,
    ADDITION,
    DELETION,
    CONTEXT,
}

internal fun timelineDiffLineKind(line: String): TimelineDiffLineKind {
    return when {
        line.startsWith("diff --git ") ||
            line.startsWith("index ") ||
            line.startsWith("--- ") ||
            line.startsWith("+++ ") ||
            line.startsWith("new file mode") ||
            line.startsWith("deleted file mode") -> TimelineDiffLineKind.META
        line.startsWith("@@") -> TimelineDiffLineKind.HUNK
        line.startsWith("+") -> TimelineDiffLineKind.ADDITION
        line.startsWith("-") -> TimelineDiffLineKind.DELETION
        else -> TimelineDiffLineKind.CONTEXT
    }
}

