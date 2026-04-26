package com.auracode.assistant.toolwindow.sessions

import com.intellij.openapi.actionSystem.AnAction

internal class ToolWindowHeaderActionCache(
    private val onSelect: (String) -> Unit,
    private val onClose: (String) -> Unit,
) {
    private val tabActionsBySessionId = linkedMapOf<String, ToolWindowHeaderTabAction>()
    private var overflowAction: ToolWindowHeaderOverflowAction? = null
    private var lastStructure: HeaderActionStructure? = null

    fun update(layout: ToolWindowHeaderTabsLayout): HeaderActionUpdate {
        val retainedIds = (layout.visibleTabs + layout.overflowTabs).map { it.sessionId }.toSet()
        tabActionsBySessionId.keys.removeIf { it !in retainedIds }

        val visibleActions = layout.visibleTabs.map { tab ->
            tabActionsBySessionId.getOrPut(tab.sessionId) {
                ToolWindowHeaderTabAction(
                    tab = tab,
                    onSelect = onSelect,
                    onClose = onClose,
                )
            }.also { it.updateTab(tab) }
        }

        val currentOverflowAction = if (layout.hasOverflow) {
            (overflowAction ?: ToolWindowHeaderOverflowAction(
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
        val structure = HeaderActionStructure(
            visibleSessionIds = layout.visibleTabs.map { it.sessionId },
            overflowSessionIds = layout.overflowTabs.map { it.sessionId },
        )
        val structureChanged = structure != lastStructure
        lastStructure = structure
        return HeaderActionUpdate(
            actions = actions,
            structureChanged = structureChanged,
        )
    }
}

internal data class HeaderActionUpdate(
    val actions: List<AnAction>,
    val structureChanged: Boolean,
)

private data class HeaderActionStructure(
    val visibleSessionIds: List<String>,
    val overflowSessionIds: List<String>,
)
