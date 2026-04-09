package com.auracode.assistant.settings

import com.auracode.assistant.provider.codex.CodexModelCatalog
import com.auracode.assistant.toolwindow.eventing.ComposerReasoning
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

@Service(Service.Level.APP)
@State(name = "AuraCodeSettings", storages = [Storage("aura-code.xml")])
class AgentSettingsService : PersistentStateComponent<AgentSettingsService.State> {
    data class State(
        var codexCliPath: String = "codex",
        var nodeExecutablePath: String = "",
        var engineExecutablePaths: MutableMap<String, String> = mutableMapOf("codex" to "codex"),
        var uiLanguage: String = UiLanguageMode.FOLLOW_IDE.name,
        var uiTheme: String = UiThemeMode.FOLLOW_IDE.name,
        var autoContextEnabled: Boolean = true,
        var backgroundCompletionNotificationsEnabled: Boolean = true,
        var codexCliAutoUpdateCheckEnabled: Boolean = true,
        var codexCliIgnoredVersion: String = "",
        var codexCliLastCheckAt: Long = 0L,
        var codexCliLastKnownCurrentVersion: String = "",
        var codexCliLastKnownLatestVersion: String = "",
        var codexCliLastNotifiedVersion: String = "",
        var savedAgents: MutableList<SavedAgentDefinition> = mutableListOf(),
        // Persist selected composer agents independently so selections survive resets and restarts.
        var selectedAgentIds: LinkedHashSet<String> = linkedSetOf(),
        var disabledSkillNames: MutableSet<String> = linkedSetOf(),
        var customModelIds: MutableList<String> = mutableListOf(),
        var selectedComposerModel: String = CodexModelCatalog.defaultModel,
        var selectedComposerReasoning: String = ComposerReasoning.MEDIUM.effort,
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
    }

    private var state = State()
    private val _languageVersion = MutableStateFlow(0L)
    val languageVersion: StateFlow<Long> = _languageVersion.asStateFlow()
    private val _appearanceVersion = MutableStateFlow(0L)
    val appearanceVersion: StateFlow<Long> = _appearanceVersion.asStateFlow()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
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

    fun nodeExecutablePath(): String = state.nodeExecutablePath.trim()

    fun setNodeExecutablePath(path: String) {
        state.nodeExecutablePath = path.trim()
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

    fun selectedComposerModel(): String = state.selectedComposerModel.trim().ifBlank { CodexModelCatalog.defaultModel }

    fun setSelectedComposerModel(model: String) {
        state.selectedComposerModel = model.trim().ifBlank { CodexModelCatalog.defaultModel }
    }

    fun selectedComposerReasoning(): String = state.selectedComposerReasoning.trim().ifBlank { ComposerReasoning.MEDIUM.effort }

    fun setSelectedComposerReasoning(reasoning: String) {
        state.selectedComposerReasoning = reasoning.trim().ifBlank { ComposerReasoning.MEDIUM.effort }
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
