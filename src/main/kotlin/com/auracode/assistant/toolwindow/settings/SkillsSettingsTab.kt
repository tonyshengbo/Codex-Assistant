package com.auracode.assistant.toolwindow.settings

/** Represents the active engine tab rendered inside the Skills settings page. */
internal enum class SkillsSettingsTab(
    val engineId: String,
) {
    CODEX("codex"),
    CLAUDE("claude"),
}

/** Maps an engine id to the matching Skills settings tab. */
internal fun skillsSettingsTabForEngine(engineId: String): SkillsSettingsTab {
    return when (engineId.trim().lowercase()) {
        SkillsSettingsTab.CLAUDE.engineId -> SkillsSettingsTab.CLAUDE
        else -> SkillsSettingsTab.CODEX
    }
}
