package com.auracode.assistant.settings.skills

import com.auracode.assistant.provider.CodexProviderFactory
import com.auracode.assistant.provider.claude.ClaudeProviderFactory
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.provider.codex.CodexSkillsManagementAdapter
import com.auracode.assistant.provider.claude.ClaudeSkillsManagementAdapter

/** Selects which skill should be updated by a runtime management adapter. */
internal sealed interface SkillSelector {
    data class ByName(val name: String) : SkillSelector
    data class ByPath(val path: String) : SkillSelector
}

/** Minimal runtime skill representation returned by an engine adapter. */
internal data class RuntimeSkillRecord(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val path: String,
    val scopeLabel: String,
)

/**
 * Defines the engine-specific runtime skills management contract.
 *
 * Implementations must only describe runtime-visible skills for a given engine
 * and working directory. File-system import/delete remain outside this adapter.
 */
internal interface SkillsManagementAdapter {
    val engineId: String

    /** Returns true when the engine can enumerate and toggle runtime skills. */
    fun supportsRuntimeSkills(): Boolean

    /** Lists runtime-visible skills for the supplied working directory. */
    suspend fun listRuntimeSkills(
        cwd: String,
        forceReload: Boolean = false,
    ): List<RuntimeSkillRecord>

    /** Updates the enabled state for the selected runtime skill. */
    suspend fun setSkillEnabled(
        cwd: String,
        selector: SkillSelector,
        enabled: Boolean,
    )
}

/** Resolves engine-specific skills management adapters. */
internal class SkillsManagementAdapterRegistry(
    private val adapters: Map<String, SkillsManagementAdapter>,
    private val defaultEngineId: String = CodexProviderFactory.ENGINE_ID,
) {
    constructor(settings: AgentSettingsService) : this(
        adapters = mapOf(
            CodexProviderFactory.ENGINE_ID to CodexSkillsManagementAdapter(settings),
            ClaudeProviderFactory.ENGINE_ID to ClaudeSkillsManagementAdapter(settings),
        ),
    )

    fun defaultAdapter(): SkillsManagementAdapter {
        return adapterFor(defaultEngineId)
            ?: error("No skills management adapter registered for engine '$defaultEngineId'.")
    }

    fun adapterFor(engineId: String): SkillsManagementAdapter? = adapters[engineId]
}
