package com.auracode.assistant.toolwindow.sessions

import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.toolwindow.shared.UiText
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ex.ToolWindowEx

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
        val layout = SessionTabsModel.buildTabs(
            openSessionIds = openSessionTabs.toList(),
            activeSessionId = activeSessionTabId,
            sessions = sessions,
            unreadCompletionSessionIds = sessionAttentionStore.unreadCompletionSessionIds(),
        )
        val update = headerActionCache.update(layout)
        if (update.structureChanged) {
            toolWindowEx.setTabActions(*update.actions.toTypedArray())
        }
    }

    companion object {
        private const val MAX_OPEN_TABS = 10
    }
}
