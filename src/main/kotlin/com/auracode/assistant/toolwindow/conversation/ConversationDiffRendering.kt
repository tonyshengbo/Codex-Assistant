package com.auracode.assistant.toolwindow.conversation

internal enum class ConversationDiffLineKind {
    META,
    HUNK,
    ADDITION,
    DELETION,
    CONTEXT,
}

internal fun conversationDiffLineKind(line: String): ConversationDiffLineKind {
    return when {
        line.startsWith("diff --git ") ||
            line.startsWith("index ") ||
            line.startsWith("--- ") ||
            line.startsWith("+++ ") ||
            line.startsWith("new file mode") ||
            line.startsWith("deleted file mode") -> ConversationDiffLineKind.META
        line.startsWith("@@") -> ConversationDiffLineKind.HUNK
        line.startsWith("+") -> ConversationDiffLineKind.ADDITION
        line.startsWith("-") -> ConversationDiffLineKind.DELETION
        else -> ConversationDiffLineKind.CONTEXT
    }
}

