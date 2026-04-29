package com.auracode.assistant.provider.claude

import com.auracode.assistant.settings.AgentSettingsService
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeSkillsManagementAdapterTest {
    @Test
    fun `claude adapter scans user and plugin skill directories`() = runBlocking {
        val homeDir = createTempDirectory("claude-skills-home")
        createSkill(
            root = homeDir,
            relativeDir = ".claude/skills/alpha",
            name = "alpha",
            description = "Alpha skill",
        )
        createSkill(
            root = homeDir,
            relativeDir = ".claude/plugins/cache/beta",
            name = "beta",
            description = "Beta skill",
        )

        val adapter = ClaudeSkillsManagementAdapter(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            homeDir = homeDir,
        )

        val skills = adapter.listRuntimeSkills(cwd = homeDir.toString(), forceReload = true)

        assertEquals(listOf("alpha", "beta"), skills.map { it.name }.sorted())
        assertEquals(
            listOf(
                homeDir.resolve(".claude/plugins/cache/beta/SKILL.md").toString(),
                homeDir.resolve(".claude/skills/alpha/SKILL.md").toString(),
            ).sorted(),
            skills.map { it.path }.sorted(),
        )
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
