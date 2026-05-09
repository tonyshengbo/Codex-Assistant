package com.auracode.assistant.toolwindow.sessions

import com.auracode.assistant.provider.PromptContextStripper

/**
 * Normalizes session tab titles so header UI never exposes provider-injected prompt metadata.
 */
internal object SessionTabTitleFormatter {
    private const val MAX_VISIBLE_TITLE_LENGTH: Int = 18
    private const val MAX_OVERFLOW_TITLE_LENGTH: Int = 48
    private val whitespaceRegex = "\\s+".toRegex()

    /**
     * Builds the clean single-line title used as the canonical tab label.
     */
    fun normalizeTitle(rawTitle: String, fallbackIndex: Int): String {
        val normalizedTitle = rawTitle
            .trim()
            .let(PromptContextStripper::stripClaudeContextPrefix)
            .let(PromptContextStripper::stripCodexContextSuffixes)
            .replace(whitespaceRegex, " ")
            .trim()
        return normalizedTitle.ifBlank { "T${fallbackIndex + 1}" }
    }

    /**
     * Builds the compact title shown directly inside the visible tab strip.
     */
    fun visibleTitle(title: String): String = truncateTitle(title, MAX_VISIBLE_TITLE_LENGTH)

    /**
     * Builds the medium-length title shown inside the overflow popup.
     */
    fun overflowTitle(title: String): String = truncateTitle(title, MAX_OVERFLOW_TITLE_LENGTH)

    /**
     * Truncates one title with ellipsis while preserving a minimum readable prefix.
     */
    internal fun truncateTitle(title: String, maxLength: Int): String {
        if (title.length <= maxLength) {
            return title
        }
        val safeLength = maxLength.coerceAtLeast(4)
        return title.take(safeLength - 3).trimEnd() + "..."
    }
}
