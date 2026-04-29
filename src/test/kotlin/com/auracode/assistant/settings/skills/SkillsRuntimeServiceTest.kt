package com.auracode.assistant.settings.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkillsRuntimeServiceTest {
    @Test
    fun `registry exposes claude adapter`() {
        val registry = SkillsManagementAdapterRegistry(
            adapters = mapOf("claude" to FakeSkillsManagementAdapter(records = emptyList(), engineId = "claude")),
            defaultEngineId = "claude",
        )

        assertNotNull(registry.adapterFor("claude"))
    }

    @Test
    fun `get skills returns runtime records only and caches result`() = kotlinx.coroutines.runBlocking {
        val adapter = FakeSkillsManagementAdapter(
            records = listOf(
                RuntimeSkillRecord(
                    name = "brainstorming",
                    description = "Runtime brainstorming",
                    enabled = false,
                    path = "/runtime/brainstorming/SKILL.md",
                    scopeLabel = "system",
                ),
            ),
        )
        val service = SkillsRuntimeService(
            adapterRegistry = SkillsManagementAdapterRegistry(mapOf("codex" to adapter), "codex"),
        )

        val first = service.getSkills(engineId = "codex", cwd = "/project", forceReload = false)
        val second = service.getSkills(engineId = "codex", cwd = "/project", forceReload = false)

        assertEquals(1, first.skills.size)
        assertEquals(1, adapter.listCalls)
        assertEquals(first, second)
        assertTrue(first.skills.any { it.name == "brainstorming" && !it.enabled })
    }

    @Test
    fun `set skill enabled refreshes cache and updates slash suggestions`() = kotlinx.coroutines.runBlocking {
        val adapter = FakeSkillsManagementAdapter(
            records = listOf(
                RuntimeSkillRecord(
                    name = "brainstorming",
                    description = "Brainstorming",
                    enabled = true,
                    path = "/runtime/brainstorming/SKILL.md",
                    scopeLabel = "system",
                ),
            ),
        )
        val service = SkillsRuntimeService(
            adapterRegistry = SkillsManagementAdapterRegistry(mapOf("codex" to adapter), "codex"),
        )

        service.getSkills(engineId = "codex", cwd = "/project")
        adapter.records = listOf(
            RuntimeSkillRecord(
                name = "brainstorming",
                description = "Brainstorming",
                enabled = false,
                path = "/runtime/brainstorming/SKILL.md",
                scopeLabel = "system",
            ),
        )

        val refreshed = service.setSkillEnabled(
            engineId = "codex",
            cwd = "/project",
            selector = SkillSelector.ByPath("/runtime/brainstorming/SKILL.md"),
            enabled = false,
        )

        assertEquals(listOf("/runtime/brainstorming/SKILL.md:false"), adapter.toggleCalls)
        assertTrue(refreshed.skills.any { it.name == "brainstorming" && !it.enabled })
        assertFalse(service.enabledSlashSkills(engineId = "codex", cwd = "/project").any { it.name == "brainstorming" })
        assertEquals(listOf("brainstorming"), service.findDisabledSkillMentions("codex", "/project", "\$brainstorming refine the spec"))
    }

    @Test
    fun `set skill enabled fails when refreshed state does not change`() = kotlinx.coroutines.runBlocking {
        val adapter = FakeSkillsManagementAdapter(
            records = listOf(
                RuntimeSkillRecord(
                    name = "brainstorming",
                    description = "Brainstorming",
                    enabled = true,
                    path = "/runtime/brainstorming/SKILL.md",
                    scopeLabel = "system",
                ),
            ),
        )
        val service = SkillsRuntimeService(
            adapterRegistry = SkillsManagementAdapterRegistry(mapOf("codex" to adapter), "codex"),
        )

        service.getSkills(engineId = "codex", cwd = "/project")

        val error = assertFailsWith<IllegalStateException> {
            service.setSkillEnabled(
                engineId = "codex",
                cwd = "/project",
                selector = SkillSelector.ByPath("/runtime/brainstorming/SKILL.md"),
                enabled = false,
            )
        }

        assertEquals(
            "Skill 'brainstorming' did not update to disabled.",
            error.message,
        )
        assertEquals(listOf("/runtime/brainstorming/SKILL.md:false"), adapter.toggleCalls)
    }

    private class FakeSkillsManagementAdapter(
        var records: List<RuntimeSkillRecord>,
        override val engineId: String = "codex",
    ) : SkillsManagementAdapter {
        var listCalls: Int = 0
        val toggleCalls = mutableListOf<String>()

        override fun supportsRuntimeSkills(): Boolean = true

        override suspend fun listRuntimeSkills(cwd: String, forceReload: Boolean): List<RuntimeSkillRecord> {
            listCalls += 1
            return records
        }

        override suspend fun setSkillEnabled(cwd: String, selector: SkillSelector, enabled: Boolean) {
            val path = (selector as SkillSelector.ByPath).path
            toggleCalls += "$path:$enabled"
        }
    }
}
