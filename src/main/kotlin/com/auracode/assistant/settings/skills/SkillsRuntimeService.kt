package com.auracode.assistant.settings.skills

import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates runtime skill discovery and toggles with an in-memory cache.
 *
 * Runtime adapters are the only source of truth for skill visibility and
 * enablement. Aura does not merge local filesystem skill metadata into this
 * service.
 */
internal class SkillsRuntimeService(
    private val adapterRegistry: SkillsManagementAdapterRegistry,
) {
    private val cache = ConcurrentHashMap<SkillsRuntimeCacheKey, SkillsRuntimeSnapshot>()

    /** Loads a fresh or cached runtime snapshot for the supplied engine and cwd. */
    suspend fun getSkills(
        engineId: String,
        cwd: String,
        forceReload: Boolean = false,
    ): SkillsRuntimeSnapshot {
        val key = SkillsRuntimeCacheKey(engineId = engineId, cwd = cwd)
        if (!forceReload) {
            cache[key]?.takeUnless { it.stale }?.let { return it }
        }

        val adapter = adapterRegistry.adapterFor(engineId)
            ?: return SkillsRuntimeSnapshot.unsupported(
                engineId = engineId,
                cwd = cwd,
                errorMessage = "Runtime skills are not supported for engine '$engineId'.",
            ).also { cache[key] = it }

        if (!adapter.supportsRuntimeSkills()) {
            return SkillsRuntimeSnapshot.unsupported(
                engineId = engineId,
                cwd = cwd,
                errorMessage = "Runtime skills are not supported for engine '$engineId'.",
            ).also { cache[key] = it }
        }

        return runCatching {
            val runtimeSkills = adapter.listRuntimeSkills(cwd = cwd, forceReload = forceReload)
                .map { skill ->
                    SkillRuntimeEntry(
                        engineId = engineId,
                        cwd = cwd,
                        name = skill.name,
                        description = skill.description,
                        enabled = skill.enabled,
                        path = skill.path,
                        scopeLabel = skill.scopeLabel,
                    )
                }
            SkillsRuntimeSnapshot(
                engineId = engineId,
                cwd = cwd,
                skills = runtimeSkills.sortedBy { it.name.lowercase() },
                supportsRuntimeSkills = true,
                stale = false,
                errorMessage = null,
            )
        }.getOrElse { error ->
            SkillsRuntimeSnapshot(
                engineId = engineId,
                cwd = cwd,
                skills = emptyList(),
                supportsRuntimeSkills = true,
                stale = false,
                errorMessage = error.message ?: "Failed to load runtime skills.",
            )
        }.also { cache[key] = it }
    }

    /** Marks one cache entry stale so the next read refreshes from the engine. */
    fun invalidate(engineId: String, cwd: String) {
        val key = SkillsRuntimeCacheKey(engineId = engineId, cwd = cwd)
        cache.computeIfPresent(key) { _, snapshot -> snapshot.copy(stale = true) }
    }

    /** Marks every cached entry for the engine stale. */
    fun invalidateEngine(engineId: String) {
        cache.entries.forEach { (key, value) ->
            if (key.engineId == engineId) {
                cache[key] = value.copy(stale = true)
            }
        }
    }

    /** Applies a runtime skill toggle and refreshes the corresponding cache key. */
    suspend fun setSkillEnabled(
        engineId: String,
        cwd: String,
        selector: SkillSelector,
        enabled: Boolean,
    ): SkillsRuntimeSnapshot {
        val adapter = adapterRegistry.adapterFor(engineId)
            ?: return SkillsRuntimeSnapshot.unsupported(
                engineId = engineId,
                cwd = cwd,
                errorMessage = "Runtime skills are not supported for engine '$engineId'.",
            )
        val currentSnapshot = getSkills(engineId = engineId, cwd = cwd, forceReload = false)
        val targetSkill = currentSnapshot.skills.firstOrNull { it.matches(selector) }
        adapter.setSkillEnabled(cwd = cwd, selector = selector, enabled = enabled)
        invalidate(engineId = engineId, cwd = cwd)
        val refreshedSnapshot = getSkills(engineId = engineId, cwd = cwd, forceReload = true)
        val refreshedSkill = refreshedSnapshot.skills.firstOrNull { it.matches(selector) }
            ?: throw IllegalStateException(
                "Skill '${targetSkill?.name ?: selector.label()}' was not found after refresh.",
            )
        if (refreshedSkill.enabled != enabled) {
            throw IllegalStateException(
                "Skill '${refreshedSkill.name}' did not update to ${if (enabled) "enabled" else "disabled"}.",
            )
        }
        return refreshedSnapshot
    }

    /** Returns enabled slash skills for the current cache key without forcing a refresh. */
    fun enabledSlashSkills(
        engineId: String,
        cwd: String,
    ): List<com.auracode.assistant.toolwindow.submission.SlashSkillDescriptor> {
        return cache[SkillsRuntimeCacheKey(engineId = engineId, cwd = cwd)]
            ?.skills
            .orEmpty()
            .asSequence()
            .filter { it.enabled }
            .map { it.toSlashSkillDescriptor() }
            .toList()
    }

    /** Finds disabled skill mentions using the cached runtime snapshot. */
    fun findDisabledSkillMentions(
        engineId: String,
        cwd: String,
        text: String,
    ): List<String> {
        if (text.isBlank()) return emptyList()
        val knownSkills = cache[SkillsRuntimeCacheKey(engineId = engineId, cwd = cwd)]
            ?.skills
            ?.associateBy { it.name }
            .orEmpty()
        return SKILL_TOKEN_REGEX
            .findAll(text)
            .mapNotNull { match ->
                val name = match.groupValues[1]
                knownSkills[name]?.takeIf { !it.enabled }?.name
            }
            .distinct()
            .toList()
    }

    private companion object {
        /** Matches `$skill-name` references in prompt text for runtime validation. */
        private val SKILL_TOKEN_REGEX = Regex("(?<!\\S)\\$([A-Za-z0-9._-]+)")
    }
}

/** Matches a runtime entry against the selector used for a toggle operation. */
private fun SkillRuntimeEntry.matches(selector: SkillSelector): Boolean {
    return when (selector) {
        is SkillSelector.ByName -> name == selector.name
        is SkillSelector.ByPath -> path == selector.path
    }
}

/** Formats a selector for human-readable diagnostics when the runtime item is missing. */
private fun SkillSelector.label(): String {
    return when (this) {
        is SkillSelector.ByName -> name
        is SkillSelector.ByPath -> path
    }
}
