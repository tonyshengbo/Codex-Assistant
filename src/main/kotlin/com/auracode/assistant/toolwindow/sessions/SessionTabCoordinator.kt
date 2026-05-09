package com.auracode.assistant.toolwindow.sessions

import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.toolwindow.shared.UiText
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import java.awt.Component
import java.awt.Container
import java.awt.Insets
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

internal class SessionTabCoordinator(
    private val chatService: AgentChatService,
    private val sessionAttentionStore: SessionAttentionStore = SessionAttentionStore(),
    private val toolWindowProvider: () -> ToolWindowEx?,
    private val onStatus: (UiText) -> Unit,
    private val onBeforeSessionActivated: (String) -> Unit,
    private val onSessionActivated: () -> Unit,
) {
    private val openSessionTabs = linkedSetOf<String>()
    private val headerActionCache = SessionTabsActionCache(
        onSelect = { sessionId -> switchToSession(sessionId) },
        onClose = { sessionId -> closeSessionTab(sessionId) },
    )
    private var activeSessionTabId: String = ""
    private var headerResizeListenerInstalled = false
    private val headerResizeListener = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent?) {
            refresh()
        }
    }

    fun initialize() {
        activeSessionTabId = chatService.getCurrentSessionId()
        if (activeSessionTabId.isNotBlank()) {
            openSessionTabs += activeSessionTabId
        }
        refresh()
    }

    fun refresh() {
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeLater { refresh() }
            return
        }
        val sessionsById = chatService.listSessions().associateBy { it.id }
        openSessionTabs.removeIf { !sessionsById.containsKey(it) }
        if (openSessionTabs.isEmpty()) {
            val currentId = chatService.getCurrentSessionId()
            if (currentId.isNotBlank()) {
                openSessionTabs += currentId
            }
        }
        activeSessionTabId = chatService.getCurrentSessionId()
        syncSessionTabs(sessionsById.values.toList())
    }

    fun startNewSession() {
        notifySessionDeactivated(activeSessionTabId)
        val id = chatService.createSession()
        replaceActiveSessionTab(id)
    }

    fun startNewWindowTab() {
        notifySessionDeactivated(activeSessionTabId)
        val id = chatService.createSession()
        openSessionTab(id)
    }

    /**
     * Opens an existing session inside a new header tab and activates it when capacity allows.
     */
    fun openExistingSessionInNewTab(sessionId: String): Boolean {
        notifySessionDeactivated(activeSessionTabId)
        return openSessionTab(sessionId)
    }

    fun switchToSession(sessionId: String) {
        if (sessionId == activeSessionTabId) return
        notifySessionDeactivated(activeSessionTabId)
        if (chatService.switchSession(sessionId)) {
            sessionAttentionStore.clear(sessionId)
            activeSessionTabId = sessionId
            onSessionActivated()
        }
    }

    fun closeSessionTab(sessionId: String) {
        if (openSessionTabs.size <= 1) {
            onStatus(UiText.bundle("session.warn.keepOneTab"))
            return
        }
        if (isSessionRunning(sessionId)) {
            chatService.cancelSessionRun(sessionId)
        }
        sessionAttentionStore.drop(sessionId)
        val ordered = openSessionTabs.toList()
        val index = ordered.indexOf(sessionId)
        if (index < 0) return
        openSessionTabs.remove(sessionId)
        if (sessionId == activeSessionTabId) {
            notifySessionDeactivated(sessionId)
            val nextSessionId = openSessionTabs.elementAtOrNull(index.coerceAtMost(openSessionTabs.size - 1))
                ?: openSessionTabs.lastOrNull()
            if (nextSessionId != null && chatService.switchSession(nextSessionId)) {
                activeSessionTabId = nextSessionId
                onSessionActivated()
                return
            }
        }
        refresh()
    }

    /** Returns the session ids that are currently represented by open header tabs. */
    fun openSessionTabIds(): Set<String> = LinkedHashSet(openSessionTabs)

    private fun openSessionTab(sessionId: String): Boolean {
        if (openSessionTabs.size >= MAX_OPEN_TABS && !openSessionTabs.contains(sessionId)) {
            onStatus(UiText.bundle("session.warn.maxTabs", MAX_OPEN_TABS))
            return false
        }
        openSessionTabs += sessionId
        switchToSession(sessionId)
        return true
    }

    private fun replaceActiveSessionTab(sessionId: String) {
        val currentActive = activeSessionTabId
        val ordered = openSessionTabs.toList().toMutableList()
        val index = ordered.indexOf(currentActive)
        if (index >= 0) {
            ordered[index] = sessionId
        } else if (ordered.isEmpty()) {
            ordered += sessionId
        } else {
            ordered[ordered.lastIndex] = sessionId
        }
        openSessionTabs.clear()
        openSessionTabs.addAll(ordered)
        activeSessionTabId = sessionId
        onSessionActivated()
    }

    private fun notifySessionDeactivated(sessionId: String) {
        if (sessionId.isNotBlank()) {
            onBeforeSessionActivated(sessionId)
        }
    }

    private fun isSessionRunning(sessionId: String): Boolean {
        return chatService.listSessions().firstOrNull { it.id == sessionId }?.isRunning == true
    }

    private fun syncSessionTabs(sessions: List<AgentChatService.SessionSummary>) {
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeLater { syncSessionTabs(sessions) }
            return
        }
        val toolWindowEx = toolWindowProvider() ?: return
        installHeaderResizeListener(toolWindowEx.component)
        val layout = SessionTabsModel.buildTabs(
            openSessionIds = openSessionTabs.toList(),
            activeSessionId = activeSessionTabId,
            sessions = sessions,
            availableWidthPx = resolveAvailableTabWidth(toolWindowEx.component),
            unreadCompletionSessionIds = sessionAttentionStore.unreadCompletionSessionIds(),
        )
        val update = headerActionCache.update(layout)
        if (update.structureChanged) {
            toolWindowEx.setTabActions(*update.actions.toTypedArray())
        }
    }

    /**
     * Hooks one resize listener into the tool window header host so tab overflow refreshes with width changes.
     */
    private fun installHeaderResizeListener(root: Component?) {
        if (headerResizeListenerInstalled || root == null) return
        findHeaderHost(root)?.addComponentListener(headerResizeListener)
        headerResizeListenerInstalled = true
    }

    /**
     * Resolves the current tab-action width budget from the real header host when available.
     */
    private fun resolveAvailableTabWidth(root: Component?): Int {
        val headerHost = findHeaderHost(root)
        val hostInsets = (headerHost as? Container)?.insets ?: Insets(0, 0, 0, 0)
        val measuredWidth = headerHost?.width?.takeIf { it > 0 } ?: root?.width?.takeIf { it > 0 }
        val fallbackWidth = DEFAULT_HEADER_WIDTH_PX
        val widthBudget = (measuredWidth ?: fallbackWidth) - hostInsets.left - hostInsets.right - HEADER_LAYOUT_PADDING_PX
        return widthBudget.coerceAtLeast(MINIMUM_TAB_BUDGET_PX)
    }

    /**
     * Walks up the tool window component tree to find a stable header-sized container for width budgeting.
     */
    private fun findHeaderHost(component: Component?): Component? {
        var current = component
        while (current != null) {
            if (current is Container && current.height in HEADER_HEIGHT_RANGE_PX) {
                return current
            }
            current = current.parent
        }
        return component
    }

    companion object {
        private const val MAX_OPEN_TABS = 10
        private const val DEFAULT_HEADER_WIDTH_PX = 420
        private const val HEADER_LAYOUT_PADDING_PX = 12
        private const val MINIMUM_TAB_BUDGET_PX = 96
        private val HEADER_HEIGHT_RANGE_PX = 28..48
    }
}
