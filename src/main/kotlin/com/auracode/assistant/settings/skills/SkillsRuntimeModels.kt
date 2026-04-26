package com.auracode.assistant.settings.skills

import com.auracode.assistant.toolwindow.submission.SlashSkillDescriptor

/** Represents one runtime-visible skill returned by the active runtime adapter. */
internal data class SkillRuntimeEntry(
    val engineId: String,
    val cwd: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val path: String,
    val scopeLabel: String,
) {
    /** Converts the runtime entry into a slash-command suggestion when enabled. */
    fun toSlashSkillDescriptor(): SlashSkillDescriptor {
        return SlashSkillDescriptor(
            name = name,
            description = description,
        )
    }
}

/** Stores one cached runtime snapshot for an engine/cwd combination. */
internal data class SkillsRuntimeSnapshot(
    val engineId: String,
    val cwd: String,
    val skills: List<SkillRuntimeEntry>,
    val supportsRuntimeSkills: Boolean,
    val stale: Boolean = false,
    val errorMessage: String? = null,
    val loadedAtMillis: Long = System.currentTimeMillis(),
) {
    companion object {
        /** Creates an unsupported snapshot for engines without runtime skills support. */
        fun unsupported(
            engineId: String,
            cwd: String,
            errorMessage: String? = null,
        ): SkillsRuntimeSnapshot {
            return SkillsRuntimeSnapshot(
                engineId = engineId,
                cwd = cwd,
                skills = emptyList(),
                supportsRuntimeSkills = false,
                stale = false,
                errorMessage = errorMessage,
            )
        }
    }
}

/** Keys the runtime cache by engine id and current working directory. */
internal data class SkillsRuntimeCacheKey(
    val engineId: String,
    val cwd: String,
)
