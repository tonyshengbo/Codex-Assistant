package com.codex.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "CodexAssistantSettings", storages = [Storage("codex-assistant.xml")])
class AgentSettingsService : PersistentStateComponent<AgentSettingsService.State> {
    data class State(
        var codexCliPath: String = "codex",
        var engineExecutablePaths: MutableMap<String, String> = mutableMapOf("codex" to "codex"),
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

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): AgentSettingsService {
            return ApplicationManager.getApplication().getService(AgentSettingsService::class.java)
        }
    }
}
