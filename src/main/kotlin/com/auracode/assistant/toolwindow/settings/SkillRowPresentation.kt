package com.auracode.assistant.toolwindow.settings

import com.auracode.assistant.settings.skills.ManagedSkillEntry
import com.auracode.assistant.settings.skills.SkillManagementMode

/** Describes the display-only data needed to render one skill row. */
internal data class SkillRowPresentation(
    val title: String,
    val secondaryText: String,
    val chips: List<String>,
)

/** Maps a managed skill entry into the compact row presentation used by the settings list. */
internal fun ManagedSkillEntry.toSkillRowPresentation(canUninstall: Boolean): SkillRowPresentation {
    val chips = buildList {
        if (scopeLabel.isNotBlank()) add(scopeLabel)
        if (managementMode == SkillManagementMode.LOCAL && !canUninstall) add("built-in")
    }
    return SkillRowPresentation(
        title = name,
        secondaryText = path,
        chips = chips,
    )
}
