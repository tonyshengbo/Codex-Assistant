package com.auracode.assistant.toolwindow.settings

import com.auracode.assistant.settings.skills.ManagedSkillEntry
import com.auracode.assistant.settings.skills.SkillManagementMode
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillRowPresentationModelTest {
    @Test
    fun `presentation model uses path as secondary text and keeps scope labels`() {
        val skill = ManagedSkillEntry(
            engineId = "claude",
            cwd = ".",
            name = "imagegen",
            description = "Generate and edit raster images.",
            enabled = true,
            path = "/Users/demo/.codex/skills/imagegen/SKILL.md",
            scopeLabel = "local",
            managementMode = SkillManagementMode.LOCAL,
        )

        val model = skill.toSkillRowPresentation(canUninstall = true)

        assertEquals("imagegen", model.title)
        assertEquals("/Users/demo/.codex/skills/imagegen/SKILL.md", model.secondaryText)
        assertEquals(listOf("local"), model.chips)
    }

    @Test
    fun `presentation model adds built in chip for non removable local skills`() {
        val skill = ManagedSkillEntry(
            engineId = "claude",
            cwd = ".",
            name = "brainstorming",
            description = "Explore requirements.",
            enabled = true,
            path = "/Users/demo/.codex/superpowers/skills/brainstorming/SKILL.md",
            scopeLabel = "superpowers",
            managementMode = SkillManagementMode.LOCAL,
        )

        val model = skill.toSkillRowPresentation(canUninstall = false)

        assertEquals(
            listOf("superpowers", "built-in"),
            model.chips,
        )
    }
}
