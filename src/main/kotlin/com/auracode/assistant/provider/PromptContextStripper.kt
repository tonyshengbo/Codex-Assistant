package com.auracode.assistant.provider

/**
 * Strips injected context metadata from stored prompt text before displaying it in history.
 *
 * When a user sends a message with context files attached, the launcher prepends/appends
 * structured metadata blocks to the raw prompt before sending it to the provider. These
 * blocks are stored verbatim in the conversation history, so they must be stripped when
 * restoring history to show only the user's original input.
 *
 * Claude layout  : [context block] + [user prompt]   — prefix must be stripped
 * Codex layout   : [user prompt] + [context blocks]  — suffixes must be stripped
 */
internal object PromptContextStripper {

    // ── Claude prefix markers (from ClaudeCliLauncher.buildPromptText) ──────────────
    private const val CLAUDE_CONTEXT_PREFIX = "Context files:\n"

    // ── Codex suffix markers (from CodexRuntimeLaunchSupport.buildPrompt) ───────────
    private val CODEX_SUFFIX_MARKERS = listOf(
        "\n\nContext snippets:\n",
        "\n\nContext files (read by path):\n",
        "\n\n##Agent Role and Instructions\n",
        "\n\nAttached non-text files (read by path):\n",
    )

    /**
     * Strips the "Context files:" prefix block that ClaudeCliLauncher prepends.
     *
     * The prefix format is:
     *   Context files:\n
     *   Path: /some/file\n
     *   ```\n...\n```\n
     *   \n
     *   <actual user prompt>
     *
     * We find the end of the last context block (the blank line separator) and return
     * everything after it. If no prefix is found the original text is returned unchanged.
     */
    fun stripClaudeContextPrefix(text: String): String {
        if (!text.startsWith(CLAUDE_CONTEXT_PREFIX)) return text
        // The context section ends with "\n\n" before the actual user prompt.
        val separatorIdx = text.indexOf("\n\n")
        if (separatorIdx == -1) return text
        // Walk forward past all context blocks — they are separated by blank lines too,
        // so we keep advancing until we find a segment that is not a path/code block.
        var searchFrom = 0
        var lastSeparator = -1
        while (true) {
            val idx = text.indexOf("\n\n", searchFrom)
            if (idx == -1) break
            lastSeparator = idx
            searchFrom = idx + 2
            // If the remaining text no longer starts with "Path:" or a code fence,
            // we've passed all context blocks.
            val remaining = text.substring(searchFrom).trimStart()
            if (!remaining.startsWith("Path:") && !remaining.startsWith("```")) break
        }
        if (lastSeparator == -1) return text
        return text.substring(lastSeparator + 2).trim()
    }

    /**
     * Strips Codex context suffix blocks that CodexRuntimeLaunchSupport.buildPrompt appends.
     *
     * The user's original prompt is always first; context sections follow separated by "\n\n".
     * We find the earliest occurrence of any known section marker and truncate there.
     */
    fun stripCodexContextSuffixes(text: String): String {
        val cutIdx = CODEX_SUFFIX_MARKERS
            .mapNotNull { marker -> text.indexOf(marker).takeIf { it >= 0 } }
            .minOrNull()
            ?: return text
        return text.substring(0, cutIdx).trim()
    }
}
