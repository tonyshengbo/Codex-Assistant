package com.auracode.assistant.toolwindow.settings

/** Represents the active runtime engine tab rendered inside the Runtime settings page. */
internal enum class RuntimeSettingsTab(
    val engineId: String,
) {
    CODEX(engineId = "codex"),
    CLAUDE(engineId = "claude"),
}
