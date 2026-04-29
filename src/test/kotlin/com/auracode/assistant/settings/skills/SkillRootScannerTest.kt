package com.auracode.assistant.settings.skills

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillRootScannerTest {
    @Test
    fun `scanner finds root and nested skills`() {
        val root = createTempDirectory("skill-root-scan")
        createSkill(root, "alpha", "alpha", "Alpha skill")
        createSkill(root, "nested/beta", "beta", "Beta skill")

        val result = SkillRootScanner().scan(root)

        assertEquals(setOf("alpha", "beta"), result.skills.map { it.name }.toSet())
        assertEquals(emptyList(), result.invalidSkillFiles)
    }

    private fun createSkill(root: java.nio.file.Path, relativeDir: String, name: String, description: String) {
        val dir = root.resolve(relativeDir)
        dir.createDirectories()
        dir.resolve("SKILL.md").writeText(
            """
            ---
            name: $name
            description: "$description"
            ---
            """.trimIndent(),
        )
    }
}
