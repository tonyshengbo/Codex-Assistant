package com.auracode.assistant.toolwindow.session

import com.auracode.assistant.service.AgentChatService

internal data class ToolWindowHeaderTab(
    val sessionId: String,
    val fullTitle: String,
    val displayTitle: String,
    val active: Boolean,
    val closable: Boolean,
    val running: Boolean,
)

internal data class ToolWindowHeaderTabsLayout(
    val visibleTabs: List<ToolWindowHeaderTab>,
    val overflowTabs: List<ToolWindowHeaderTab>,
) {
    val hasOverflow: Boolean
        get() = overflowTabs.isNotEmpty()
}

internal object ToolWindowHeaderTabsModel {
    private const val MAX_VISIBLE_TABS: Int = 3
    private const val MAX_VISIBLE_TITLE_LENGTH: Int = 18

    fun buildTabs(
        openSessionIds: List<String>,
        activeSessionId: String,
        sessions: List<AgentChatService.SessionSummary>,
    ): ToolWindowHeaderTabsLayout {
        val sessionsById = sessions.associateBy { it.id }
        val closable = openSessionIds.size > 1
        val allTabs = openSessionIds.mapIndexedNotNull { index, sessionId ->
            val summary = sessionsById[sessionId] ?: return@mapIndexedNotNull null
            val fullTitle = normalizeHeaderTabTitle(
                rawTitle = summary.title,
                fallbackIndex = index,
            )
            val decoratedTitle = decorateHeaderTabTitle(
                title = fullTitle,
                running = summary.isRunning,
            )
            ToolWindowHeaderTab(
                sessionId = sessionId,
                fullTitle = decoratedTitle,
                displayTitle = truncateHeaderTabTitle(decoratedTitle),
                active = sessionId == activeSessionId,
                closable = closable,
                running = summary.isRunning,
            )
        }
        if (allTabs.size <= MAX_VISIBLE_TABS) {
            return ToolWindowHeaderTabsLayout(
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
        return ToolWindowHeaderTabsLayout(
            visibleTabs = visibleTabs,
            overflowTabs = overflowTabs,
        )
    }

    internal fun normalizeHeaderTabTitle(
        rawTitle: String,
        fallbackIndex: Int,
    ): String {
        return rawTitle.trim().ifBlank { "T${fallbackIndex + 1}" }
    }

    internal fun decorateHeaderTabTitle(
        title: String,
        running: Boolean,
    ): String {
        return if (running) "$title (Running)" else title
    }

    internal fun truncateHeaderTabTitle(
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
