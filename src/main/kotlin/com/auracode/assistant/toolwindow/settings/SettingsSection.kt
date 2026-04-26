package com.auracode.assistant.toolwindow.settings

internal enum class SettingsSection {
    BASIC,
    RUNTIME,
    AGENTS,
    SKILLS,
    MCP,
    TOKEN_USAGE,
    ABOUT,
}

internal data class SettingsSectionPresentation(
    val titleKey: String,
    val subtitleKey: String,
    val showHeader: Boolean = true,
    val showSidePanel: Boolean = false,
)

internal fun SettingsSection.presentation(): SettingsSectionPresentation = when (this) {
    SettingsSection.BASIC -> SettingsSectionPresentation(
        titleKey = "settings.section.basic",
        subtitleKey = "settings.section.basic.subtitle",
    )
    SettingsSection.RUNTIME -> SettingsSectionPresentation(
        titleKey = "settings.section.runtime",
        subtitleKey = "settings.section.runtime.subtitle",
    )
    SettingsSection.AGENTS -> SettingsSectionPresentation(
        titleKey = "settings.section.agents",
        subtitleKey = "settings.section.agents.subtitle",
    )
    SettingsSection.SKILLS -> SettingsSectionPresentation(
        titleKey = "settings.section.skills",
        subtitleKey = "settings.section.skills.subtitle",
    )
    SettingsSection.MCP -> SettingsSectionPresentation(
        titleKey = "settings.section.mcp",
        subtitleKey = "settings.section.mcp.subtitle",
    )
    SettingsSection.TOKEN_USAGE -> SettingsSectionPresentation(
        titleKey = "settings.section.usage",
        subtitleKey = "settings.section.usage.subtitle",
    )
    SettingsSection.ABOUT -> SettingsSectionPresentation(
        titleKey = "settings.section.about",
        subtitleKey = "settings.section.about.subtitle",
    )
}
