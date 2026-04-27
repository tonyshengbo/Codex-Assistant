package com.auracode.assistant.toolwindow.sessions

import com.auracode.assistant.service.AgentChatService

/**
 * Describes a single rendered tab item in the tool window header.
 */
internal data class SessionTab(
    val sessionId: String,
    val fullTitle: String,
    val displayTitle: String,
    val active: Boolean,
    val closable: Boolean,
    val running: Boolean,
    val hasUnreadCompletion: Boolean = false,
)

/**
 * Splits header tabs into visible and overflow buckets for the fixed-width tool window chrome.
 */
internal data class SessionTabsLayout(
    val visibleTabs: List<SessionTab>,
    val overflowTabs: List<SessionTab>,
) {
    val hasOverflow: Boolean
        get() = overflowTabs.isNotEmpty()
}

/**
 * Builds stable tab view models from session state and lightweight attention metadata.
 */
internal object SessionTabsModel {
    private const val MAX_VISIBLE_TABS: Int = 3
    private const val MAX_VISIBLE_TITLE_LENGTH: Int = 18

    fun buildTabs(
        openSessionIds: List<String>,
        activeSessionId: String,
        sessions: List<AgentChatService.SessionSummary>,
        unreadCompletionSessionIds: Set<String> = emptySet(),
    ): SessionTabsLayout {
        val sessionsById = sessions.associateBy { it.id }
        val closable = openSessionIds.size > 1
        val allTabs = openSessionIds.mapIndexedNotNull { index, sessionId ->
            val summary = sessionsById[sessionId] ?: return@mapIndexedNotNull null
            val fullTitle = normalizeSessionTabTitle(
                rawTitle = summary.title,
                fallbackIndex = index,
            )
            val decoratedTitle = decorateSessionTabTitle(
                title = fullTitle,
                hasUnreadCompletion = unreadCompletionSessionIds.contains(sessionId),
            )
            SessionTab(
                sessionId = sessionId,
                fullTitle = decoratedTitle,
                displayTitle = truncateSessionTabTitle(decoratedTitle),
                active = sessionId == activeSessionId,
                closable = closable,
                running = summary.isRunning,
                hasUnreadCompletion = unreadCompletionSessionIds.contains(sessionId),
            )
        }
        if (allTabs.size <= MAX_VISIBLE_TABS) {
            return SessionTabsLayout(
                visibleTabs = allTabs,
                overflowTabs = emptyList(),
            )
        }

        val activeIndex = allTabs.indexOfFirst { it.active }
        val visibleIndexes = linkedSetOf<Int>()
        allTabs.indices.take(MAX_VISIBLE_TABS).forEach { visibleIndexes += it }
        if (activeIndex >= MAX_VISIBLE_TABS) {
            // Keep the active tab discoverable even when it would normally fall into overflow.
            visibleIndexes.remove(visibleIndexes.last())
            visibleIndexes += activeIndex
        }

        val visibleTabs = visibleIndexes
            .sorted()
            .map(allTabs::get)
        val overflowTabs = allTabs.filterIndexed { index, _ -> index !in visibleIndexes }
        return SessionTabsLayout(
            visibleTabs = visibleTabs,
            overflowTabs = overflowTabs,
        )
    }

    /**
     * Normalizes blank titles into deterministic fallback labels for stable tab rendering.
     */
    internal fun normalizeSessionTabTitle(
        rawTitle: String,
        fallbackIndex: Int,
    ): String {
        return rawTitle.trim().ifBlank { "T${fallbackIndex + 1}" }
    }

    /**
     * Applies lightweight status decoration that should remain visible in the tab title.
     */
    internal fun decorateSessionTabTitle(
        title: String,
        hasUnreadCompletion: Boolean = false,
    ): String {
        return when {
            hasUnreadCompletion -> "$title (Done)"
            else -> title
        }
    }

    /**
     * Truncates tab titles to the header budget while preserving the full title separately.
     */
    internal fun truncateSessionTabTitle(
        title: String,
        maxLength: Int = MAX_VISIBLE_TITLE_LENGTH,
    ): String {
        if (title.length <= maxLength) {
            return title
        }
        val safeLength = maxLength.coerceAtLeast(4)
        // The header uses a fixed-width Swing action area, so we normalize truncation in the model
        // instead of relying on platform-specific label ellipsis behavior.
        return title.take(safeLength - 3).trimEnd() + "..."
    }

}
