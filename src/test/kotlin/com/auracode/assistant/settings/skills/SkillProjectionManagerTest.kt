package com.auracode.assistant.settings.skills

import kotlin.io.path.exists
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillProjectionManagerTest {
    @Test
    fun `projection manager copies scanned skills into the selected engine directory only`() {
        val home = createTempDirectory("projection-home")
        val sourceRoot = createTempDirectory("projection-source")
        val sourceDir = sourceRoot.resolve("brainstorming").createDirectories()
        sourceDir.resolve("SKILL.md").writeText(
            """
            ---
            name: brainstorming
            description: "Explore requirements."
            ---
            """.trimIndent(),
        )
        val untouchedCodexDir = home.resolve(".codex/skills/brainstorming").createDirectories()
        untouchedCodexDir.resolve("marker.txt").writeText("keep")

        val manager = SkillProjectionManager(
            directoryResolver = EngineSkillDirectoryResolver(homeDir = home),
        )

        manager.projectForEngine(
            engineId = "claude",
            skills = listOf(
                ScannedSkillRootEntry(
                    name = "brainstorming",
                    description = "Explore requirements.",
                    skillDir = sourceDir,
                    skillFile = sourceDir.resolve("SKILL.md"),
                ),
            ),
        )

        assertTrue(home.resolve(".claude/skills/brainstorming/SKILL.md").exists())
        assertEquals("keep", untouchedCodexDir.resolve("marker.txt").readText())
    }

    @Test
    fun `projection manager replaces existing target skill directory before copying`() {
        val home = createTempDirectory("projection-home")
        val sourceRoot = createTempDirectory("projection-source")
        val sourceDir = sourceRoot.resolve("brainstorming").createDirectories()
        sourceDir.resolve("SKILL.md").writeText(
            """
            ---
            name: brainstorming
            description: "Updated description."
            ---
            """.trimIndent(),
        )
        sourceDir.resolve("notes.txt").writeText("new")
        val existingDir = home.resolve(".codex/skills/brainstorming").createDirectories()
        existingDir.resolve("SKILL.md").writeText(
            """
            ---
            name: brainstorming
            description: "Old description."
            ---
            """.trimIndent(),
        )
        existingDir.resolve("stale.txt").writeText("old")

        val manager = SkillProjectionManager(
            directoryResolver = EngineSkillDirectoryResolver(homeDir = home),
        )

        manager.projectForEngine(
            engineId = "codex",
            skills = listOf(
                ScannedSkillRootEntry(
                    name = "brainstorming",
                    description = "Updated description.",
                    skillDir = sourceDir,
                    skillFile = sourceDir.resolve("SKILL.md"),
                ),
            ),
        )

        val targetDir = home.resolve(".codex/skills/brainstorming")
        assertTrue(targetDir.exists())
        assertEquals("new", targetDir.resolve("notes.txt").readText())
        assertFalse(targetDir.resolve("stale.txt").exists())
    }
}
