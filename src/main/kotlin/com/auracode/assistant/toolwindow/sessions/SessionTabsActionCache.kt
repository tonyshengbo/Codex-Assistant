package com.auracode.assistant.toolwindow.sessions

import com.intellij.openapi.actionSystem.AnAction

internal class SessionTabsActionCache(
    private val onSelect: (String) -> Unit,
    private val onClose: (String) -> Unit,
) {
    private val tabActionsBySessionId = linkedMapOf<String, SessionTabAction>()
    private var overflowAction: SessionTabsOverflowAction? = null
    private var lastStructure: SessionTabsActionStructure? = null

    fun update(layout: SessionTabsLayout): SessionTabsActionUpdate {
        val retainedIds = (layout.visibleTabs + layout.overflowTabs).map { it.sessionId }.toSet()
        tabActionsBySessionId.keys.removeIf { it !in retainedIds }

        val visibleActions = layout.visibleTabs.map { tab ->
            tabActionsBySessionId.getOrPut(tab.sessionId) {
                SessionTabAction(
                    tab = tab,
                    onSelect = onSelect,
                    onClose = onClose,
                )
            }.also { it.updateTab(tab) }
        }

        val currentOverflowAction = if (layout.hasOverflow) {
            (overflowAction ?: SessionTabsOverflowAction(
                overflowTabs = layout.overflowTabs,
                onSelect = onSelect,
            ).also { overflowAction = it })
                .also { it.updateOverflowTabs(layout.overflowTabs) }
        } else {
            overflowAction = null
            null
        }

        val actions = buildList<AnAction> {
            addAll(visibleActions)
            currentOverflowAction?.let(::add)
        }
        val structure = SessionTabsActionStructure(
            visibleSessionIds = layout.visibleTabs.map { it.sessionId },
            overflowSessionIds = layout.overflowTabs.map { it.sessionId },
        )
        val structureChanged = structure != lastStructure
        lastStructure = structure
        return SessionTabsActionUpdate(
            actions = actions,
            structureChanged = structureChanged,
        )
    }
}

internal data class SessionTabsActionUpdate(
    val actions: List<AnAction>,
    val structureChanged: Boolean,
)

private data class SessionTabsActionStructure(
    val visibleSessionIds: List<String>,
    val overflowSessionIds: List<String>,
)
