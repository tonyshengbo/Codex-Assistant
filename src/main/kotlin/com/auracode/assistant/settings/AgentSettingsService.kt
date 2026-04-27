package com.auracode.assistant.settings

import com.auracode.assistant.provider.claude.ClaudeModelCatalog
import com.auracode.assistant.provider.codex.CodexModelCatalog
import com.auracode.assistant.toolwindow.eventing.SubmissionReasoning
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UiLanguageMode {
    FOLLOW_IDE,
    ZH,
    EN,
    JA,
    KO,
}

enum class UiThemeMode {
    FOLLOW_IDE,
    LIGHT,
    DARK,
}

enum class UiScaleMode {
    P80,
    P90,
    P100,
    P110,
    P120,
}

@Service(Service.Level.APP)
@State(name = "AuraCodeSettings", storages = [Storage("aura-code.xml")])
class AgentSettingsService : PersistentStateComponent<AgentSettingsService.State> {
    data class State(
        var defaultEngineId: String = "codex",
        var codexCliPath: String = "codex",
        var nodeExecutablePath: String = "",
        var engineExecutablePaths: MutableMap<String, String> = mutableMapOf(
            "codex" to "codex",
            "claude" to "claude",
        ),
        var uiLanguage: String = UiLanguageMode.FOLLOW_IDE.name,
        var uiTheme: String = UiThemeMode.FOLLOW_IDE.name,
        var uiScale: String = UiScaleMode.P100.name,
        var autoContextEnabled: Boolean = true,
        var backgroundCompletionNotificationsEnabled: Boolean = true,
        var codexCliAutoUpdateCheckEnabled: Boolean = true,
        var codexCliIgnoredVersion: String = "",
        var codexCliLastCheckAt: Long = 0L,
        var codexCliLastKnownCurrentVersion: String = "",
        var codexCliLastKnownLatestVersion: String = "",
        var codexCliLastNotifiedVersion: String = "",
        var claudeCliIgnoredVersion: String = "",
        var claudeCliLastCheckAt: Long = 0L,
        var claudeCliLastKnownCurrentVersion: String = "",
        var claudeCliLastKnownLatestVersion: String = "",
        var claudeCliLastNotifiedVersion: String = "",
        var savedAgents: MutableList<SavedAgentDefinition> = mutableListOf(),
        // Persist selected composer agents independently so selections survive resets and restarts.
        var selectedAgentIds: LinkedHashSet<String> = linkedSetOf(),
        var disabledSkillNames: MutableSet<String> = linkedSetOf(),
        var customModelIds: MutableList<String> = mutableListOf(),
        var selectedSubmissionModelsByEngine: MutableMap<String, String> = mutableMapOf(),
        var selectedSubmissionModel: String = CodexModelCatalog.defaultModel,
        var selectedSubmissionReasoning: String = SubmissionReasoning.MEDIUM.effort,
    ) {
        fun executablePathFor(engineId: String): String {
            val fromMap = engineExecutablePaths[engineId]?.trim().orEmpty()
            if (fromMap.isNotBlank()) {
                return fromMap
            }
            return if (engineId == "codex") codexCliPath.trim() else engineId
        }

        fun setExecutablePathFor(engineId: String, path: String) {
            val normalized = path.trim()
            if (engineId == "codex") {
                codexCliPath = normalized
            }
            engineExecutablePaths[engineId] = normalized
        }

        fun defaultModelFor(engineId: String): String {
            return when (engineId.trim()) {
                "claude" -> ClaudeModelCatalog.defaultModel
                else -> CodexModelCatalog.defaultModel
            }
        }
    }

    private var state = State()
    private val _languageVersion = MutableStateFlow(0L)
    val languageVersion: StateFlow<Long> = _languageVersion.asStateFlow()
    private val _appearanceVersion = MutableStateFlow(0L)
    val appearanceVersion: StateFlow<Long> = _appearanceVersion.asStateFlow()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        normalizePersistedState()
        notifyLanguageChanged()
    }

    fun uiLanguageMode(): UiLanguageMode {
        return runCatching { UiLanguageMode.valueOf(state.uiLanguage) }.getOrDefault(UiLanguageMode.FOLLOW_IDE)
    }

    fun setUiLanguageMode(mode: UiLanguageMode) {
        state.uiLanguage = mode.name
    }

    fun uiThemeMode(): UiThemeMode {
        return runCatching { UiThemeMode.valueOf(state.uiTheme) }.getOrDefault(UiThemeMode.FOLLOW_IDE)
    }

    fun setUiThemeMode(mode: UiThemeMode) {
        state.uiTheme = mode.name
    }

    fun uiScaleMode(): UiScaleMode {
        return when (state.uiScale.trim().uppercase()) {
            UiScaleMode.P80.name -> UiScaleMode.P80
            UiScaleMode.P90.name -> UiScaleMode.P90
            UiScaleMode.P100.name -> UiScaleMode.P100
            UiScaleMode.P110.name -> UiScaleMode.P110
            UiScaleMode.P120.name -> UiScaleMode.P120
            // Backward compatibility for persisted values from legacy 3-step scale.
            "SMALL" -> UiScaleMode.P90
            "NORMAL" -> UiScaleMode.P100
            "LARGE" -> UiScaleMode.P110
            else -> UiScaleMode.P100
        }
    }

    fun setUiScaleMode(mode: UiScaleMode) {
        state.uiScale = mode.name
    }

    fun autoContextEnabled(): Boolean = state.autoContextEnabled

    fun setAutoContextEnabled(enabled: Boolean) {
        state.autoContextEnabled = enabled
    }

    fun backgroundCompletionNotificationsEnabled(): Boolean = state.backgroundCompletionNotificationsEnabled

    fun setBackgroundCompletionNotificationsEnabled(enabled: Boolean) {
        state.backgroundCompletionNotificationsEnabled = enabled
    }

    fun codexCliAutoUpdateCheckEnabled(): Boolean = state.codexCliAutoUpdateCheckEnabled

    fun setCodexCliAutoUpdateCheckEnabled(enabled: Boolean) {
        state.codexCliAutoUpdateCheckEnabled = enabled
    }

    fun codexCliIgnoredVersion(): String = state.codexCliIgnoredVersion.trim()

    fun setCodexCliIgnoredVersion(version: String) {
        state.codexCliIgnoredVersion = version.trim()
    }

    fun codexCliLastCheckAt(): Long = state.codexCliLastCheckAt

    fun setCodexCliLastCheckAt(value: Long) {
        state.codexCliLastCheckAt = value.coerceAtLeast(0L)
    }

    fun codexCliLastKnownCurrentVersion(): String = state.codexCliLastKnownCurrentVersion.trim()

    fun setCodexCliLastKnownCurrentVersion(version: String) {
        state.codexCliLastKnownCurrentVersion = version.trim()
    }

    fun codexCliLastKnownLatestVersion(): String = state.codexCliLastKnownLatestVersion.trim()

    fun setCodexCliLastKnownLatestVersion(version: String) {
        state.codexCliLastKnownLatestVersion = version.trim()
    }

    fun codexCliLastNotifiedVersion(): String = state.codexCliLastNotifiedVersion.trim()

    fun setCodexCliLastNotifiedVersion(version: String) {
        state.codexCliLastNotifiedVersion = version.trim()
    }

    /** Returns the ignored Claude CLI version marker persisted for notifications and UI state. */
    fun claudeCliIgnoredVersion(): String = state.claudeCliIgnoredVersion.trim()

    /** Persists the ignored Claude CLI version marker. */
    fun setClaudeCliIgnoredVersion(version: String) {
        state.claudeCliIgnoredVersion = version.trim()
    }

    /** Returns the last timestamp when Claude version metadata was refreshed. */
    fun claudeCliLastCheckAt(): Long = state.claudeCliLastCheckAt

    /** Persists the last timestamp when Claude version metadata was refreshed. */
    fun setClaudeCliLastCheckAt(value: Long) {
        state.claudeCliLastCheckAt = value.coerceAtLeast(0L)
    }

    /** Returns the most recently cached installed Claude CLI version. */
    fun claudeCliLastKnownCurrentVersion(): String = state.claudeCliLastKnownCurrentVersion.trim()

    /** Persists the most recently cached installed Claude CLI version. */
    fun setClaudeCliLastKnownCurrentVersion(version: String) {
        state.claudeCliLastKnownCurrentVersion = version.trim()
    }

    /** Returns the most recently cached latest Claude CLI version. */
    fun claudeCliLastKnownLatestVersion(): String = state.claudeCliLastKnownLatestVersion.trim()

    /** Persists the most recently cached latest Claude CLI version. */
    fun setClaudeCliLastKnownLatestVersion(version: String) {
        state.claudeCliLastKnownLatestVersion = version.trim()
    }

    /** Returns the last Claude CLI version that already triggered a notification. */
    fun claudeCliLastNotifiedVersion(): String = state.claudeCliLastNotifiedVersion.trim()

    /** Persists the last Claude CLI version that already triggered a notification. */
    fun setClaudeCliLastNotifiedVersion(version: String) {
        state.claudeCliLastNotifiedVersion = version.trim()
    }

    fun nodeExecutablePath(): String = state.nodeExecutablePath.trim()

    fun setNodeExecutablePath(path: String) {
        state.nodeExecutablePath = path.trim()
    }

    fun defaultEngineId(): String = state.defaultEngineId.trim().ifBlank { "codex" }

    fun setDefaultEngineId(engineId: String) {
        state.defaultEngineId = engineId.trim().ifBlank { "codex" }
    }

    fun notifyLanguageChanged() {
        _languageVersion.value = _languageVersion.value + 1
    }

    fun notifyAppearanceChanged() {
        _appearanceVersion.value = _appearanceVersion.value + 1
    }

    fun savedAgents(): List<SavedAgentDefinition> = state.savedAgents.toList()

    fun selectedAgentIds(): List<String> = state.selectedAgentIds.toList()

    fun selectAgent(id: String) {
        val normalized = id.trim()
        if (normalized.isBlank()) return
        state.selectedAgentIds.add(normalized)
    }

    fun deselectAgent(id: String) {
        val normalized = id.trim()
        if (normalized.isBlank()) return
        state.selectedAgentIds.remove(normalized)
    }

    fun disabledSkillNames(): Set<String> = state.disabledSkillNames.toSet()

    fun customModelIds(): List<String> = state.customModelIds.toList()

    fun setCustomModelIds(values: List<String>) {
        state.customModelIds = values.toMutableList()
    }

    fun selectedSubmissionModel(): String = selectedSubmissionModel(defaultEngineId())

    fun selectedSubmissionModel(engineId: String): String {
        val normalizedEngineId = engineId.trim().ifBlank { defaultEngineId() }
        val fromMap = state.selectedSubmissionModelsByEngine[normalizedEngineId]?.trim().orEmpty()
        if (fromMap.isNotBlank()) return normalizeSubmissionModel(normalizedEngineId, fromMap)
        return when (normalizedEngineId) {
            "codex" -> state.selectedSubmissionModel.trim().ifBlank { CodexModelCatalog.defaultModel }
            else -> state.defaultModelFor(normalizedEngineId)
        }
    }

    fun setSelectedSubmissionModel(model: String) {
        setSelectedSubmissionModel(defaultEngineId(), model)
    }

    fun setSelectedSubmissionModel(engineId: String, model: String) {
        val normalizedEngineId = engineId.trim().ifBlank { defaultEngineId() }
        val normalizedModel = normalizeSubmissionModel(
            engineId = normalizedEngineId,
            model = model.trim().ifBlank { state.defaultModelFor(normalizedEngineId) },
        )
        if (normalizedEngineId == "codex") {
            state.selectedSubmissionModel = normalizedModel
        }
        state.selectedSubmissionModelsByEngine[normalizedEngineId] = normalizedModel
    }

    /** 对持久化状态执行轻量迁移，避免旧模型值持续污染运行时。 */
    private fun normalizePersistedState() {
        state.selectedSubmissionModelsByEngine = state.selectedSubmissionModelsByEngine
            .mapValuesTo(linkedMapOf()) { (engineId, model) ->
                normalizeSubmissionModel(engineId = engineId, model = model)
            }
    }

    /** 根据引擎类型归一化当前选中的模型值。 */
    private fun normalizeSubmissionModel(engineId: String, model: String): String {
        return when (engineId.trim()) {
            "claude" -> ClaudeModelCatalog.normalize(model)
            else -> model.trim()
        }
    }

    fun selectedSubmissionReasoning(): String = state.selectedSubmissionReasoning.trim().ifBlank { SubmissionReasoning.MEDIUM.effort }

    fun setSelectedSubmissionReasoning(reasoning: String) {
        state.selectedSubmissionReasoning = reasoning.trim().ifBlank { SubmissionReasoning.MEDIUM.effort }
    }

    fun disableSkill(name: String) {
        val normalized = name.trim()
        if (normalized.isBlank()) return
        state.disabledSkillNames.add(normalized)
    }

    fun enableSkill(name: String) {
        val normalized = name.trim()
        if (normalized.isBlank()) return
        state.disabledSkillNames.remove(normalized)
    }

    companion object {
        fun getInstance(): AgentSettingsService {
            return ApplicationManager.getApplication().getService(AgentSettingsService::class.java)
        }
    }
}
