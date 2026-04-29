package com.auracode.assistant.provider.claude

import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.settings.skills.RuntimeSkillRecord
import com.auracode.assistant.settings.skills.SkillSelector
import com.auracode.assistant.settings.skills.SkillsManagementAdapter
import com.auracode.assistant.settings.skills.discoverSkillFilesUnder
import com.auracode.assistant.settings.skills.parseSkillDescriptor
import java.nio.file.Path

/**
 * Provides Claude-specific skill discovery by scanning Claude's user-facing
 * skill directories and applying Aura's persisted enablement state.
 */
internal class ClaudeSkillsManagementAdapter(
    private val settings: AgentSettingsService,
    private val homeDir: Path = Path.of(System.getProperty("user.home").orEmpty()),
) : SkillsManagementAdapter {
    override val engineId: String = ClaudeProviderFactory.ENGINE_ID

    /** Claude exposes runtime-visible skills through local filesystem directories. */
    override fun supportsRuntimeSkills(): Boolean = true

    /** Lists skills from both the user skills directory and the plugin cache directory. */
    override suspend fun listRuntimeSkills(
        cwd: String,
        forceReload: Boolean,
    ): List<RuntimeSkillRecord> {
        val disabledNames = settings.disabledSkillNames()
        return skillRoots()
            .asSequence()
            .flatMap { root -> discoverSkillFilesUnder(root).asSequence() }
            .mapNotNull { skillFile ->
                val descriptor = parseSkillDescriptor(skillFile) ?: return@mapNotNull null
                RuntimeSkillRecord(
                    name = descriptor.name,
                    description = descriptor.description.ifBlank { descriptor.name },
                    enabled = descriptor.name !in disabledNames,
                    path = skillFile.toString(),
                    scopeLabel = scopeLabelFor(skillFile),
                )
            }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    /** Updates Claude-visible enablement using Aura's persisted disabled-skill state. */
    override suspend fun setSkillEnabled(
        cwd: String,
        selector: SkillSelector,
        enabled: Boolean,
    ) {
        val skillName = resolveSkillName(selector)
            ?: error("Claude skill '${selector.label()}' was not found.")
        if (enabled) {
            settings.enableSkill(skillName)
        } else {
            settings.disableSkill(skillName)
        }
    }

    private fun skillRoots(): List<Path> {
        return buildList {
            add(homeDir.resolve(".claude/skills"))
            add(homeDir.resolve(".claude/plugins/cache"))
        }
    }

    private fun resolveSkillName(selector: SkillSelector): String? {
        return when (selector) {
            is SkillSelector.ByName -> selector.name.takeIf { it.isNotBlank() }
            is SkillSelector.ByPath -> parseSkillDescriptor(Path.of(selector.path))?.name
        }
    }

    private fun scopeLabelFor(skillFile: Path): String {
        val normalized = skillFile.normalize()
        return when {
            normalized.startsWith(homeDir.resolve(".claude/plugins/cache").normalize()) -> "plugin"
            else -> "user"
        }
    }
}

/** Formats one selector for adapter-local diagnostics. */
private fun SkillSelector.label(): String {
    return when (this) {
        is SkillSelector.ByName -> name
        is SkillSelector.ByPath -> path
    }
}
