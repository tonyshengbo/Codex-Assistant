package com.codex.assistant.toolwindow

enum class ToolWindowView(
    val title: String,
) {
    CONSOLE("Codex Assistant"),
    SESSION_HISTORY("Session History"),
    SETTINGS("Settings"),
    CONTEXT_FILES("Context Files"),
}

class ToolWindowWorkspaceState {
    var currentView: ToolWindowView = ToolWindowView.CONSOLE
        private set

    val showBackAction: Boolean
        get() = currentView != ToolWindowView.CONSOLE

    val showRunContextBar: Boolean
        get() = currentView == ToolWindowView.CONSOLE

    val showComposerDock: Boolean
        get() = currentView == ToolWindowView.CONSOLE

    val currentTitle: String
        get() = currentView.title

    fun navigateTo(view: ToolWindowView) {
        currentView = view
    }

    fun navigateBack() {
        currentView = ToolWindowView.CONSOLE
    }
}
