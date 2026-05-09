package com.auracode.assistant.toolwindow.sessions

import com.auracode.assistant.service.AgentChatService

/**
 * Describes a single rendered tab item in the tool window header.
 */
internal data class SessionTab(
    val sessionId: String,
    val tooltipTitle: String,
    val displayTitle: String,
    val overflowTitle: String,
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
    private const val MIN_VISIBLE_TABS: Int = 1
    private const val MIN_TAB_WIDTH_PX: Int = 92
    private const val BASE_TAB_WIDTH_PX: Int = 48
    private const val TAB_CHAR_WIDTH_PX: Int = 7
    private const val OVERFLOW_BUTTON_WIDTH_PX: Int = 30

    fun buildTabs(
        openSessionIds: List<String>,
        activeSessionId: String,
        sessions: List<AgentChatService.SessionSummary>,
        availableWidthPx: Int,
        unreadCompletionSessionIds: Set<String> = emptySet(),
    ): SessionTabsLayout {
        val sessionsById = sessions.associateBy { it.id }
        val closable = openSessionIds.size > 1
        val allTabs = openSessionIds.mapIndexedNotNull { index, sessionId ->
            val summary = sessionsById[sessionId] ?: return@mapIndexedNotNull null
            val cleanTitle = SessionTabTitleFormatter.normalizeTitle(
                rawTitle = summary.title,
                fallbackIndex = index,
            )
            SessionTab(
                sessionId = sessionId,
                tooltipTitle = cleanTitle,
                displayTitle = SessionTabTitleFormatter.visibleTitle(cleanTitle),
                overflowTitle = SessionTabTitleFormatter.overflowTitle(cleanTitle),
                active = sessionId == activeSessionId,
                closable = closable,
                running = summary.isRunning,
                hasUnreadCompletion = unreadCompletionSessionIds.contains(sessionId),
            )
        }
        if (allTabs.isEmpty()) {
            return SessionTabsLayout(
                visibleTabs = emptyList(),
                overflowTabs = emptyList(),
            )
        }
        val widthBudget = availableWidthPx.coerceAtLeast(MIN_TAB_WIDTH_PX)
        if (estimatedLayoutWidthPx(allTabs) <= widthBudget) {
            return SessionTabsLayout(
                visibleTabs = allTabs,
                overflowTabs = emptyList(),
            )
        }

        val visibleIndexes = selectVisibleIndexes(
            tabs = allTabs,
            widthBudget = widthBudget,
            reservedTrailingWidthPx = OVERFLOW_BUTTON_WIDTH_PX,
        )
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
     * Estimates one tab width so overflow decisions can track the current header budget.
     */
    private fun estimatedTabWidthPx(tab: SessionTab): Int {
        val titleWidth = tab.displayTitle.length * TAB_CHAR_WIDTH_PX
        val statusWidth = if (tab.running || tab.hasUnreadCompletion) 12 else 0
        val closeWidth = if (tab.closable) 18 else 0
        return (BASE_TAB_WIDTH_PX + titleWidth + statusWidth + closeWidth).coerceAtLeast(MIN_TAB_WIDTH_PX)
    }

    /**
     * Estimates the width required to show one full tab layout without overflow.
     */
    private fun estimatedLayoutWidthPx(tabs: List<SessionTab>): Int = tabs.sumOf(::estimatedTabWidthPx)

    /**
     * Selects the visible tab indexes while keeping the active tab discoverable.
     */
    private fun selectVisibleIndexes(
        tabs: List<SessionTab>,
        widthBudget: Int,
        reservedTrailingWidthPx: Int,
    ): Set<Int> {
        val activeIndex = tabs.indexOfFirst { it.active }
        val visibleIndexes = linkedSetOf<Int>()
        var consumedWidth = reservedTrailingWidthPx

        if (activeIndex >= 0) {
            visibleIndexes += activeIndex
            consumedWidth += estimatedTabWidthPx(tabs[activeIndex])
        }

        for (index in tabs.indices) {
            if (index == activeIndex) continue
            val tabWidth = estimatedTabWidthPx(tabs[index])
            val nextWidth = consumedWidth + tabWidth
            if (visibleIndexes.size < MIN_VISIBLE_TABS || nextWidth <= widthBudget) {
                visibleIndexes += index
                consumedWidth = nextWidth
            }
        }

        if (visibleIndexes.isEmpty()) {
            visibleIndexes += activeIndex.takeIf { it >= 0 } ?: 0
        }
        return visibleIndexes
    }

}
