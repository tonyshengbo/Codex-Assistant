package com.auracode.assistant.settings.skills

import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.submission.SlashSkillDescriptor

/** Describes which skills management path the current engine uses. */
internal enum class SkillManagementMode {
    RUNTIME,
    LOCAL,
}

/** Represents one skill entry rendered in settings and reused by composer helpers. */
internal data class ManagedSkillEntry(
    val engineId: String,
    val cwd: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val path: String,
    val scopeLabel: String,
    val managementMode: SkillManagementMode,
) {
    /** Converts the skill entry into a slash-command suggestion when enabled. */
    fun toSlashSkillDescriptor(): SlashSkillDescriptor {
        return SlashSkillDescriptor(
            name = name,
            description = description,
        )
    }
}

/** Stores one resolved skills snapshot for the active engine and working directory. */
internal data class ManagedSkillsSnapshot(
    val engineId: String,
    val cwd: String,
    val skills: List<ManagedSkillEntry>,
    val managementMode: SkillManagementMode,
    val supportsRuntimeSkills: Boolean,
    val stale: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Resolves skill loading and mutation against the appropriate backend for each engine.
 *
 * Codex keeps using runtime skill APIs, while Claude falls back to Aura's local
 * filesystem catalog and persisted enablement settings.
 */
internal class EngineSkillsService(
    private val settings: AgentSettingsService,
    private val runtimeService: SkillsRuntimeService,
    private val localSkillCatalog: SkillCatalog = LocalSkillCatalog(settings),
) {
    /** Loads the visible skills list for the supplied engine and working directory. */
    suspend fun loadSkills(
        engineId: String,
        cwd: String,
        forceReload: Boolean = false,
    ): ManagedSkillsSnapshot {
        return if (usesRuntimeManagement(engineId)) {
            val snapshot = runtimeService.getSkills(
                engineId = engineId,
                cwd = cwd,
                forceReload = forceReload,
            )
            ManagedSkillsSnapshot(
                engineId = snapshot.engineId,
                cwd = snapshot.cwd,
                skills = snapshot.skills.map { it.toManagedSkillEntry() },
                managementMode = SkillManagementMode.RUNTIME,
                supportsRuntimeSkills = snapshot.supportsRuntimeSkills,
                stale = snapshot.stale,
                errorMessage = snapshot.errorMessage,
            )
        } else {
            ManagedSkillsSnapshot(
                engineId = engineId,
                cwd = cwd,
                skills = localSkillCatalog.listSkills().map { it.toManagedSkillEntry(engineId, cwd) },
                managementMode = SkillManagementMode.LOCAL,
                supportsRuntimeSkills = false,
                stale = false,
                errorMessage = null,
            )
        }
    }

    /** Applies one enablement change and returns a refreshed snapshot for the same engine. */
    suspend fun setSkillEnabled(
        engineId: String,
        cwd: String,
        name: String,
        path: String,
        enabled: Boolean,
    ): ManagedSkillsSnapshot {
        return if (usesRuntimeManagement(engineId)) {
            runtimeService.setSkillEnabled(
                engineId = engineId,
                cwd = cwd,
                selector = SkillSelector.ByPath(path),
                enabled = enabled,
            ).let { refreshed ->
                ManagedSkillsSnapshot(
                    engineId = refreshed.engineId,
                    cwd = refreshed.cwd,
                    skills = refreshed.skills.map { it.toManagedSkillEntry() },
                    managementMode = SkillManagementMode.RUNTIME,
                    supportsRuntimeSkills = refreshed.supportsRuntimeSkills,
                    stale = refreshed.stale,
                    errorMessage = refreshed.errorMessage,
                )
            }
        } else {
            localSkillCatalog.setSkillEnabled(name = name, enabled = enabled)
            loadSkills(engineId = engineId, cwd = cwd, forceReload = true)
        }
    }

    /** Returns the enabled skills that should surface as slash suggestions. */
    fun enabledSlashSkills(
        engineId: String,
        cwd: String,
    ): List<SlashSkillDescriptor> {
        return if (usesRuntimeManagement(engineId)) {
            runtimeService.enabledSlashSkills(engineId = engineId, cwd = cwd)
        } else {
            localSkillCatalog.listEnabledSkills()
        }
    }

    /** Resolves disabled skill mentions for composer validation. */
    fun findDisabledSkillMentions(
        engineId: String,
        cwd: String,
        text: String,
    ): List<String> {
        return if (usesRuntimeManagement(engineId)) {
            runtimeService.findDisabledSkillMentions(engineId = engineId, cwd = cwd, text = text)
        } else {
            localSkillCatalog.findDisabledSkillMentions(text)
        }
    }

    /** Returns true when the engine is expected to expose runtime skill APIs. */
    fun usesRuntimeManagement(engineId: String): Boolean = engineId.trim() == "codex"
}

/** Maps one runtime skill entry into the shared UI representation. */
private fun SkillRuntimeEntry.toManagedSkillEntry(): ManagedSkillEntry {
    return ManagedSkillEntry(
        engineId = engineId,
        cwd = cwd,
        name = name,
        description = description,
        enabled = enabled,
        path = path,
        scopeLabel = scopeLabel,
        managementMode = SkillManagementMode.RUNTIME,
    )
}

/** Maps one local catalog entry into the shared UI representation. */
private fun SkillSummary.toManagedSkillEntry(
    engineId: String,
    cwd: String,
): ManagedSkillEntry {
    return ManagedSkillEntry(
        engineId = engineId,
        cwd = cwd,
        name = name,
        description = description,
        enabled = enabled,
        path = effectivePath,
        scopeLabel = effectiveSource.name.lowercase(),
        managementMode = SkillManagementMode.LOCAL,
    )
}
