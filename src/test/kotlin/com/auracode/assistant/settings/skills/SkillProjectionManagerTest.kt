package com.auracode.assistant.settings.skills

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkillProjectionManagerTest {
    @Test
    fun `projection manager rejects duplicate skill names in target engine directory`() {
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
        home.resolve(".codex/skills/brainstorming").createDirectories()

        val manager = SkillProjectionManager(
            directoryResolver = EngineSkillDirectoryResolver(homeDir = home),
            operatingSystemName = "Linux",
        )

        val error = assertFailsWith<IllegalStateException> {
            manager.projectAll(
                skills = listOf(
                    ScannedSkillRootEntry(
                        name = "brainstorming",
                        description = "Explore requirements.",
                        skillDir = sourceDir,
                        skillFile = sourceDir.resolve("SKILL.md"),
                    ),
                ),
            )
        }

        assertTrue(error.message!!.contains("already exists"))
    }
}
