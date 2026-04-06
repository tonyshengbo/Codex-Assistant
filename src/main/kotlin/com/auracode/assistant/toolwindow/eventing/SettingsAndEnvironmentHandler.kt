package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.provider.codex.CodexModelCatalog
import com.auracode.assistant.settings.SavedAgentDefinition
import com.auracode.assistant.settings.mcp.McpAuthActionResult
import com.auracode.assistant.settings.mcp.McpBusyState
import com.auracode.assistant.settings.mcp.McpManagementAdapter
import com.auracode.assistant.settings.mcp.McpServerDraft
import com.auracode.assistant.settings.mcp.McpTestResult
import com.auracode.assistant.settings.mcp.McpValidationErrors
import com.auracode.assistant.settings.mcp.validate
import com.auracode.assistant.settings.skills.SkillSelector
import com.auracode.assistant.toolwindow.drawer.SettingsSection
import com.auracode.assistant.toolwindow.shared.UiText
import java.util.UUID
import kotlinx.coroutines.launch

internal class SettingsAndEnvironmentHandler(
    private val context: ToolWindowCoordinatorContext,
) {
    fun applyLanguagePreview(mode: com.auracode.assistant.settings.UiLanguageMode) {
        if (context.settingsService.uiLanguageMode() == mode) return
        context.settingsService.setUiLanguageMode(mode)
        context.settingsService.notifyLanguageChanged()
        context.publishSettingsSnapshot()
    }

    fun applyThemePreview(mode: com.auracode.assistant.settings.UiThemeMode) {
        if (context.settingsService.uiThemeMode() == mode) return
        context.settingsService.setUiThemeMode(mode)
        context.settingsService.notifyAppearanceChanged()
        context.publishSettingsSnapshot()
    }

    fun applyAutoContextPreference(enabled: Boolean) {
        if (context.settingsService.autoContextEnabled() == enabled) return
        context.settingsService.setAutoContextEnabled(enabled)
        context.publishSettingsSnapshot()
    }

    fun applyBackgroundCompletionNotificationPreference(enabled: Boolean) {
        if (context.settingsService.backgroundCompletionNotificationsEnabled() == enabled) return
        context.settingsService.setBackgroundCompletionNotificationsEnabled(enabled)
        context.publishSettingsSnapshot()
    }

    fun saveSettings() {
        val drawerState = context.rightDrawerStore.state.value
        val oldLanguage = context.settingsService.uiLanguageMode()
        val oldTheme = context.settingsService.uiThemeMode()
        val state = context.settingsService.state
        state.setExecutablePathFor("codex", drawerState.codexCliPath.trim())
        context.settingsService.setNodeExecutablePath(drawerState.nodePath.trim())
        context.settingsService.setUiLanguageMode(drawerState.languageMode)
        context.settingsService.setUiThemeMode(drawerState.themeMode)
        context.settingsService.setAutoContextEnabled(drawerState.autoContextEnabled)
        context.settingsService.setBackgroundCompletionNotificationsEnabled(
            drawerState.backgroundCompletionNotificationsEnabled,
        )
        if (oldLanguage != context.settingsService.uiLanguageMode()) {
            context.settingsService.notifyLanguageChanged()
        }
        if (oldTheme != context.settingsService.uiThemeMode()) {
            context.settingsService.notifyAppearanceChanged()
        }
        context.publishSettingsSnapshot()
    }

    fun detectCodexEnvironment() {
        val drawerState = context.rightDrawerStore.state.value
        context.eventHub.publish(AppEvent.CodexEnvironmentCheckRunning(true))
        context.scope.launch {
            runCatching {
                context.codexEnvironmentDetector.autoDetect(
                    configuredCodexPath = drawerState.codexCliPath,
                    configuredNodePath = drawerState.nodePath,
                )
            }.onSuccess { result ->
                context.eventHub.publish(
                    AppEvent.CodexEnvironmentCheckUpdated(
                        result = result,
                        updateDraftPaths = true,
                    ),
                )
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(result.message)))
            }.onFailure { error ->
                context.eventHub.publish(AppEvent.CodexEnvironmentCheckRunning(false))
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        UiText.raw(error.message ?: "Failed to detect Codex environment."),
                    ),
                )
            }
        }
    }

    fun testCodexEnvironment() {
        val drawerState = context.rightDrawerStore.state.value
        context.eventHub.publish(AppEvent.CodexEnvironmentCheckRunning(true))
        context.scope.launch {
            runCatching {
                context.codexEnvironmentDetector.testEnvironment(
                    configuredCodexPath = drawerState.codexCliPath,
                    configuredNodePath = drawerState.nodePath,
                )
            }.onSuccess { result ->
                context.eventHub.publish(AppEvent.CodexEnvironmentCheckUpdated(result = result))
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(result.message)))
            }.onFailure { error ->
                context.eventHub.publish(AppEvent.CodexEnvironmentCheckRunning(false))
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        UiText.raw(error.message ?: "Failed to test Codex environment."),
                    ),
                )
            }
        }
    }

    fun saveAgentDraft() {
        val drawerState = context.rightDrawerStore.state.value
        val id = drawerState.editingAgentId?.takeIf { it.isNotBlank() }
        val name = drawerState.agentDraftName.trim()
        val prompt = drawerState.agentDraftPrompt.trim()
        if (name.isBlank() || prompt.isBlank()) {
            context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw("Agent name and prompt are required.")))
            return
        }
        val state = context.settingsService.state
        val duplicate = state.savedAgents.any { agent ->
            agent.id != id && agent.name.trim().equals(name, ignoreCase = true)
        }
        if (duplicate) {
            context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw("Agent name must be unique.")))
            return
        }
        val saved = SavedAgentDefinition(
            id = id ?: UUID.randomUUID().toString(),
            name = name,
            prompt = prompt,
        )
        val updated = state.savedAgents.toMutableList()
        val index = updated.indexOfFirst { it.id == saved.id }
        if (index >= 0) {
            updated[index] = saved
        } else {
            updated += saved
        }
        state.savedAgents = updated
        context.publishSettingsSnapshot()
        context.eventHub.publishUiIntent(UiIntent.ShowAgentSettingsList)
    }

    fun saveCustomModel() {
        val draft = context.composerStore.state.value.customModelDraft.trim()
        if (draft.isBlank()) {
            context.eventHub.publish(
                AppEvent.StatusTextUpdated(UiText.raw(AuraCodeBundle.message("composer.model.custom.error.blank"))),
            )
            return
        }
        val existing = context.settingsService.customModelIds()
        if (CodexModelCatalog.ids().contains(draft) || existing.contains(draft)) {
            context.eventHub.publish(
                AppEvent.StatusTextUpdated(UiText.raw(AuraCodeBundle.message("composer.model.custom.error.duplicate"))),
            )
            return
        }
        context.settingsService.setCustomModelIds(existing + draft)
        context.publishSettingsSnapshot()
        context.eventHub.publishUiIntent(UiIntent.SelectModel(draft))
    }

    fun deleteCustomModel(model: String) {
        val normalized = model.trim()
        if (normalized.isBlank()) return
        val existing = context.settingsService.customModelIds()
        val updated = existing.filterNot { it == normalized }
        if (updated.size == existing.size) return
        context.settingsService.setCustomModelIds(updated)
        context.publishSettingsSnapshot()
        if (context.composerStore.state.value.selectedModel == normalized) {
            context.eventHub.publishUiIntent(UiIntent.SelectModel(CodexModelCatalog.defaultModel))
        }
    }

    fun deleteSavedAgent(id: String) {
        val state = context.settingsService.state
        val updated = state.savedAgents.filterNot { it.id == id }.toMutableList()
        if (updated.size == state.savedAgents.size) return
        state.savedAgents = updated
        context.settingsService.deselectAgent(id)
        context.publishSettingsSnapshot()
        context.eventHub.publishUiIntent(UiIntent.ShowAgentSettingsList)
    }

    fun persistSelectedAgent(id: String) {
        context.settingsService.selectAgent(id)
        context.publishSettingsSnapshot()
    }

    fun persistDeselectedAgent(id: String) {
        context.settingsService.deselectAgent(id)
        context.publishSettingsSnapshot()
    }

    fun loadSkills(forceReload: Boolean = false) {
        context.eventHub.publish(AppEvent.SkillsLoadingChanged(loading = true))
        context.coroutineLauncher.launch("loadSkills") {
            runCatching {
                publishSkillsSnapshot(forceReload = forceReload)
            }.onFailure { error ->
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to load runtime skills.")),
                )
            }
            context.eventHub.publish(AppEvent.SkillsLoadingChanged(loading = false))
        }
    }

    /** Preloads runtime skills in the background for later composer usage. */
    fun warmSkillsRuntimeCache() {
        context.coroutineLauncher.launch("warmSkillsRuntimeCache") {
            runCatching {
                publishSkillsSnapshot(forceReload = false)
            }
        }
    }

    /** Reads the current runtime snapshot and publishes the unified skills page state. */
    private suspend fun publishSkillsSnapshot(forceReload: Boolean) {
        val snapshot = context.skillsRuntimeService.getSkills(
            engineId = context.chatService.defaultEngineId(),
            cwd = context.chatService.currentWorkingDirectory(),
            forceReload = forceReload,
        )
        context.eventHub.publish(
            AppEvent.SkillsLoaded(
                engineId = snapshot.engineId,
                cwd = snapshot.cwd,
                skills = snapshot.skills,
                supportsRuntimeSkills = snapshot.supportsRuntimeSkills,
                stale = snapshot.stale,
                errorMessage = snapshot.errorMessage,
            ),
        )
    }

    fun toggleSkillEnabled(name: String, path: String, enabled: Boolean) {
        if (name.isBlank() || path.isBlank()) return
        context.eventHub.publish(AppEvent.SkillsLoadingChanged(loading = true, activePath = path))
        context.coroutineLauncher.launch("toggleSkillEnabled($name,$enabled)") {
            runCatching {
                val snapshot = context.skillsRuntimeService.setSkillEnabled(
                    engineId = context.chatService.defaultEngineId(),
                    cwd = context.chatService.currentWorkingDirectory(),
                    selector = SkillSelector.ByPath(path),
                    enabled = enabled,
                )
                context.eventHub.publish(
                    AppEvent.SkillsLoaded(
                        engineId = snapshot.engineId,
                        cwd = snapshot.cwd,
                        skills = snapshot.skills,
                        supportsRuntimeSkills = snapshot.supportsRuntimeSkills,
                        stale = snapshot.stale,
                        errorMessage = snapshot.errorMessage,
                    ),
                )
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        UiText.raw(if (enabled) "Enabled skill '$name'." else "Disabled skill '$name'."),
                    ),
                )
            }.onFailure { error ->
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to update skill '$name'.")),
                )
            }
            context.eventHub.publish(AppEvent.SkillsLoadingChanged(loading = false))
        }
    }

    fun openSkillPath(path: String) {
        if (path.isBlank()) return
        context.openTimelineFilePath(path)
    }

    fun revealSkillPath(path: String) {
        if (path.isBlank()) return
        if (!context.revealPathInFileManager(path)) {
            context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw("Failed to reveal skill location.")))
        }
    }

    fun uninstallSkill(name: String, path: String) {
        if (name.isBlank() || path.isBlank()) return
        context.eventHub.publish(AppEvent.SkillsLoadingChanged(loading = true, activePath = path))
        context.coroutineLauncher.launch("uninstallSkill($name)") {
            runCatching {
                context.localSkillInstallPolicy.uninstall(path).getOrThrow()
                publishSkillsSnapshot(forceReload = true)
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw("Uninstalled local skill '$name'.")))
            }.onFailure { error ->
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to uninstall skill '$name'.")),
                )
            }
            context.eventHub.publish(AppEvent.SkillsLoadingChanged(loading = false))
        }
    }

    fun loadMcpServers() {
        updateMcpBusy { copy(loading = true) }
        logMcpDiagnostic("MCP coroutine scheduled: label=loadMcpServers | ${mcpContextSnapshot()}")
        context.coroutineLauncher.launch("loadMcpServers") {
            logMcpDiagnostic("MCP coroutine start: label=loadMcpServers | ${mcpContextSnapshot()}")
            runCatching {
                val adapter = mcpAdapter()
                logMcpDiagnostic("MCP adapter call begin: label=loadMcpServers step=listServers | ${mcpContextSnapshot()}")
                val servers = adapter.listServers()
                logMcpDiagnostic("MCP adapter call success: label=loadMcpServers step=listServers count=${servers.size} | ${mcpContextSnapshot()}")
                logMcpDiagnostic("MCP adapter call begin: label=loadMcpServers step=refreshStatuses | ${mcpContextSnapshot()}")
                val statuses = adapter.refreshStatuses()
                logMcpDiagnostic("MCP adapter call success: label=loadMcpServers step=refreshStatuses count=${statuses.size} | ${mcpContextSnapshot()}")
                context.eventHub.publish(AppEvent.McpServersLoaded(servers))
                context.eventHub.publish(AppEvent.McpStatusesUpdated(statuses))
            }.onFailure { error ->
                logMcpDiagnostic(
                    message = "MCP coroutine handled failure: label=loadMcpServers | ${mcpContextSnapshot()}",
                    error = error,
                )
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to load MCP servers.")),
                )
            }
            updateMcpBusy { copy(loading = false) }
            logMcpDiagnostic("MCP coroutine finish: label=loadMcpServers | ${mcpContextSnapshot()}")
        }
    }

    fun refreshMcpStatuses() {
        updateMcpBusy { copy(loading = true) }
        logMcpDiagnostic("MCP coroutine scheduled: label=refreshMcpStatuses | ${mcpContextSnapshot()}")
        context.coroutineLauncher.launch("refreshMcpStatuses") {
            logMcpDiagnostic("MCP coroutine start: label=refreshMcpStatuses | ${mcpContextSnapshot()}")
            runCatching {
                logMcpDiagnostic("MCP adapter call begin: label=refreshMcpStatuses step=refreshStatuses | ${mcpContextSnapshot()}")
                val statuses = mcpAdapter().refreshStatuses()
                logMcpDiagnostic("MCP adapter call success: label=refreshMcpStatuses step=refreshStatuses count=${statuses.size} | ${mcpContextSnapshot()}")
                context.eventHub.publish(AppEvent.McpStatusesUpdated(statuses))
            }.onFailure { error ->
                logMcpDiagnostic(
                    message = "MCP coroutine handled failure: label=refreshMcpStatuses | ${mcpContextSnapshot()}",
                    error = error,
                )
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to refresh MCP runtime status.")),
                )
            }
            updateMcpBusy { copy(loading = false) }
            logMcpDiagnostic("MCP coroutine finish: label=refreshMcpStatuses | ${mcpContextSnapshot()}")
        }
    }

    fun loadMcpEditorDraft() {
        updateMcpBusy { copy(loading = true) }
        logMcpDiagnostic("MCP coroutine scheduled: label=loadMcpEditorDraft | ${mcpContextSnapshot()}")
        context.coroutineLauncher.launch("loadMcpEditorDraft") {
            logMcpDiagnostic("MCP coroutine start: label=loadMcpEditorDraft | ${mcpContextSnapshot()}")
            runCatching {
                logMcpDiagnostic("MCP adapter call begin: label=loadMcpEditorDraft step=getEditorDraft | ${mcpContextSnapshot()}")
                val draft = mcpAdapter().getEditorDraft()
                logMcpDiagnostic("MCP adapter call success: label=loadMcpEditorDraft step=getEditorDraft | ${mcpContextSnapshot()}")
                context.eventHub.publish(AppEvent.McpDraftLoaded(draft))
            }.onFailure { error ->
                logMcpDiagnostic(
                    message = "MCP coroutine handled failure: label=loadMcpEditorDraft | ${mcpContextSnapshot()}",
                    error = error,
                )
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to load MCP server JSON.")),
                )
            }
            updateMcpBusy { copy(loading = false) }
            logMcpDiagnostic("MCP coroutine finish: label=loadMcpEditorDraft | ${mcpContextSnapshot()}")
        }
    }

    fun saveMcpDraft(afterSave: (suspend (List<String>) -> Unit)? = null) {
        val normalized = context.rightDrawerStore.state.value.mcpDraft.normalized()
        val validation = normalized.validate()
        context.eventHub.publish(AppEvent.McpValidationErrorsUpdated(validation))
        if (validation.hasAny()) {
            context.eventHub.publish(AppEvent.McpFeedbackUpdated(message = "Fix the MCP config errors before saving.", isError = true))
            context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw("Please fix the MCP config errors before saving.")))
            return
        }
        val entries = normalized.parseServerEntries().getOrElse { error ->
            context.eventHub.publish(
                AppEvent.McpValidationErrorsUpdated(McpValidationErrors(json = error.message ?: "Invalid MCP JSON.")),
            )
            return
        }
        updateMcpBusy { copy(saving = true) }
        logMcpDiagnostic("MCP coroutine scheduled: label=saveMcpDraft(count=${entries.size}) | ${mcpContextSnapshot()}")
        context.coroutineLauncher.launch("saveMcpDraft(count=${entries.size})") {
            logMcpDiagnostic("MCP coroutine start: label=saveMcpDraft(count=${entries.size}) | ${mcpContextSnapshot()}")
            runCatching {
                val adapter = mcpAdapter()
                val drafts = entries.map { entry ->
                    McpServerDraft(
                        name = entry.name,
                        configJson = McpServerDraft.entryConfigJson(entry.name, entry.config),
                    )
                }
                val savedNames = drafts.map { it.name.trim() }
                logMcpDiagnostic("MCP adapter call begin: label=saveMcpDraft(count=${entries.size}) step=saveServers names=${savedNames.joinToString(",")} | ${mcpContextSnapshot()}")
                adapter.saveServers(drafts)
                logMcpDiagnostic("MCP adapter call success: label=saveMcpDraft(count=${entries.size}) step=saveServers names=${savedNames.joinToString(",")} | ${mcpContextSnapshot()}")
                context.eventHub.publish(
                    AppEvent.McpDraftLoaded(
                        normalized.copy(
                            originalName = null,
                            name = "",
                            configJson = McpServerDraft.serversConfigJson(entries),
                        ),
                    ),
                )
                logMcpDiagnostic("MCP adapter call begin: label=saveMcpDraft(count=${entries.size}) step=listServers | ${mcpContextSnapshot()}")
                context.eventHub.publish(AppEvent.McpServersLoaded(adapter.listServers()))
                logMcpDiagnostic("MCP adapter call success: label=saveMcpDraft(count=${entries.size}) step=listServers | ${mcpContextSnapshot()}")
                logMcpDiagnostic("MCP adapter call begin: label=saveMcpDraft(count=${entries.size}) step=refreshStatuses | ${mcpContextSnapshot()}")
                context.eventHub.publish(AppEvent.McpStatusesUpdated(adapter.refreshStatuses()))
                logMcpDiagnostic("MCP adapter call success: label=saveMcpDraft(count=${entries.size}) step=refreshStatuses | ${mcpContextSnapshot()}")
                val feedbackMessage = when (savedNames.size) {
                    1 -> "Saved MCP server '${savedNames.single()}'."
                    else -> "Saved ${savedNames.size} MCP servers."
                }
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(feedbackMessage)))
                context.eventHub.publish(AppEvent.McpFeedbackUpdated(message = feedbackMessage, isError = false))
                afterSave?.invoke(savedNames)
            }.onFailure { error ->
                logMcpDiagnostic(
                    message = "MCP coroutine handled failure: label=saveMcpDraft(count=${entries.size}) | ${mcpContextSnapshot()}",
                    error = error,
                )
                context.eventHub.publish(
                    AppEvent.McpFeedbackUpdated(message = error.message ?: "Failed to save MCP server.", isError = true),
                )
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to save MCP server.")),
                )
            }
            updateMcpBusy { copy(saving = false) }
            logMcpDiagnostic("MCP coroutine finish: label=saveMcpDraft(count=${entries.size}) | ${mcpContextSnapshot()}")
        }
    }

    fun deleteMcpServer(name: String) {
        if (name.isBlank()) return
        updateMcpBusy { copy(deletingName = name) }
        context.coroutineLauncher.launch("deleteMcpServer($name)") {
            runCatching {
                if (!mcpAdapter().deleteServer(name)) return@runCatching
                context.eventHub.publishUiIntent(UiIntent.ShowMcpSettingsList)
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw("Deleted MCP server '$name'.")))
                context.eventHub.publishUiIntent(UiIntent.LoadMcpServers)
            }.onFailure { error ->
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to delete MCP server '$name'.")),
                )
            }
            updateMcpBusy { copy(deletingName = null) }
        }
    }

    fun toggleMcpServerEnabled(name: String, enabled: Boolean) {
        if (name.isBlank()) return
        updateMcpBusy { copy(loading = true) }
        context.coroutineLauncher.launch("toggleMcpServerEnabled($name,$enabled)") {
            runCatching {
                val adapter = mcpAdapter()
                adapter.setServerEnabled(name, enabled)
                context.eventHub.publish(AppEvent.McpServersLoaded(adapter.listServers()))
            }.onFailure { error ->
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to update MCP server '$name'.")),
                )
            }
            updateMcpBusy { copy(loading = false) }
        }
    }

    fun testMcpServer(name: String?) {
        val savedName = name?.trim().takeUnless { it.isNullOrBlank() }
        if (savedName != null) {
            runMcpServerTest(savedName)
            return
        }
        saveMcpDraft(afterSave = { draftNames ->
            draftNames.firstOrNull()?.let(::runMcpServerTest)
        })
    }

    fun authenticateMcpServer(name: String, login: Boolean) {
        if (name.isBlank()) return
        updateMcpBusy { copy(authenticatingName = name) }
        logMcpDiagnostic("MCP coroutine scheduled: label=authenticateMcpServer($name, login=$login) | ${mcpContextSnapshot(requestedName = name)}")
        context.coroutineLauncher.launch("authenticateMcpServer($name, login=$login)") {
            logMcpDiagnostic("MCP coroutine start: label=authenticateMcpServer($name, login=$login) | ${mcpContextSnapshot(requestedName = name)}")
            runCatching {
                val adapter = mcpAdapter()
                logMcpDiagnostic("MCP adapter call begin: label=authenticateMcpServer($name, login=$login) step=${if (login) "login" else "logout"} | ${mcpContextSnapshot(requestedName = name)}")
                val result: McpAuthActionResult = if (login) adapter.login(name) else adapter.logout(name)
                logMcpDiagnostic("MCP adapter call success: label=authenticateMcpServer($name, login=$login) step=${if (login) "login" else "logout"} | ${mcpContextSnapshot(requestedName = name)}")
                logMcpDiagnostic("MCP adapter call begin: label=authenticateMcpServer($name, login=$login) step=refreshStatuses | ${mcpContextSnapshot(requestedName = name)}")
                context.eventHub.publish(AppEvent.McpStatusesUpdated(adapter.refreshStatuses()))
                logMcpDiagnostic("MCP adapter call success: label=authenticateMcpServer($name, login=$login) step=refreshStatuses | ${mcpContextSnapshot(requestedName = name)}")
                val openedInBrowser = result.authorizationUrl?.let(context.openExternalUrl) == true
                val suffix = when {
                    openedInBrowser -> " Opened the authorization page in your browser."
                    !result.authorizationUrl.isNullOrBlank() -> " ${result.authorizationUrl}"
                    else -> ""
                }
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(result.message + suffix)))
            }.onFailure { error ->
                logMcpDiagnostic(
                    message = "MCP coroutine handled failure: label=authenticateMcpServer($name, login=$login) | ${mcpContextSnapshot(requestedName = name)}",
                    error = error,
                )
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        UiText.raw(error.message ?: "Failed to update MCP authentication for '$name'."),
                    ),
                )
            }
            updateMcpBusy { copy(authenticatingName = null) }
            logMcpDiagnostic("MCP coroutine finish: label=authenticateMcpServer($name, login=$login) | ${mcpContextSnapshot(requestedName = name)}")
        }
    }

    fun isMcpIntent(intent: UiIntent): Boolean {
        return when (intent) {
            UiIntent.LoadMcpServers,
            UiIntent.RefreshMcpStatuses,
            UiIntent.CreateNewMcpDraft,
            UiIntent.ShowMcpSettingsList,
            UiIntent.SaveMcpDraft -> true
            is UiIntent.SelectMcpServerForEdit,
            is UiIntent.EditMcpDraftName,
            is UiIntent.EditMcpDraftJson,
            is UiIntent.ToggleMcpServerEnabled,
            is UiIntent.DeleteMcpServer,
            is UiIntent.TestMcpServer,
            is UiIntent.LoginMcpServer,
            is UiIntent.LogoutMcpServer -> true
            else -> false
        }
    }

    fun formatMcpIntent(intent: UiIntent): String {
        return when (intent) {
            UiIntent.LoadMcpServers -> "LoadMcpServers"
            UiIntent.RefreshMcpStatuses -> "RefreshMcpStatuses"
            UiIntent.CreateNewMcpDraft -> "CreateNewMcpDraft"
            UiIntent.ShowMcpSettingsList -> "ShowMcpSettingsList"
            UiIntent.SaveMcpDraft -> "SaveMcpDraft"
            is UiIntent.TestMcpServer -> "TestMcpServer(name=${intent.name ?: "<draft>"})"
            is UiIntent.SelectMcpServerForEdit -> "SelectMcpServerForEdit(name=${intent.name})"
            is UiIntent.EditMcpDraftName -> "EditMcpDraftName"
            is UiIntent.EditMcpDraftJson -> "EditMcpDraftJson"
            is UiIntent.ToggleMcpServerEnabled -> "ToggleMcpServerEnabled(name=${intent.name}, enabled=${intent.enabled})"
            is UiIntent.DeleteMcpServer -> "DeleteMcpServer(name=${intent.name})"
            is UiIntent.LoginMcpServer -> "LoginMcpServer(name=${intent.name})"
            is UiIntent.LogoutMcpServer -> "LogoutMcpServer(name=${intent.name})"
            else -> intent::class.simpleName ?: "UnknownMcpIntent"
        }
    }

    fun onSettingsDrawerOpened() {
        context.publishSettingsSnapshot()
        when (context.rightDrawerStore.state.value.settingsSection) {
            SettingsSection.MCP -> loadMcpServers()
            SettingsSection.SKILLS -> loadSkills()
            SettingsSection.GENERAL -> detectCodexEnvironment()
            SettingsSection.AGENTS,
            SettingsSection.TOKEN_USAGE,
            SettingsSection.ABOUT,
            -> Unit
        }
    }

    fun onSettingsSectionSelected(section: SettingsSection) {
        when (section) {
            SettingsSection.MCP -> loadMcpServers()
            SettingsSection.SKILLS -> loadSkills()
            SettingsSection.GENERAL -> {
                if (context.rightDrawerStore.state.value.kind == com.auracode.assistant.toolwindow.drawer.RightDrawerKind.SETTINGS) {
                    detectCodexEnvironment()
                }
            }
            SettingsSection.AGENTS,
            SettingsSection.TOKEN_USAGE,
            SettingsSection.ABOUT,
            -> Unit
        }
    }

    fun mcpContextSnapshotForLog(): String = mcpContextSnapshot()

    private fun runMcpServerTest(name: String) {
        updateMcpBusy { copy(testingName = name) }
        logMcpDiagnostic("MCP coroutine scheduled: label=runMcpServerTest($name) | ${mcpContextSnapshot(requestedName = name)}")
        context.coroutineLauncher.launch("runMcpServerTest($name)") {
            logMcpDiagnostic("MCP coroutine start: label=runMcpServerTest($name) | ${mcpContextSnapshot(requestedName = name)}")
            runCatching {
                val adapter = mcpAdapter()
                logMcpDiagnostic("MCP adapter call begin: label=runMcpServerTest($name) step=testServer | ${mcpContextSnapshot(requestedName = name)}")
                val result: McpTestResult = adapter.testServer(name)
                logMcpDiagnostic("MCP adapter call success: label=runMcpServerTest($name) step=testServer success=${result.success} | ${mcpContextSnapshot(requestedName = name)}")
                context.eventHub.publish(AppEvent.McpTestResultUpdated(name = name, result = result))
                logMcpDiagnostic("MCP adapter call begin: label=runMcpServerTest($name) step=refreshStatuses | ${mcpContextSnapshot(requestedName = name)}")
                context.eventHub.publish(AppEvent.McpStatusesUpdated(adapter.refreshStatuses()))
                logMcpDiagnostic("MCP adapter call success: label=runMcpServerTest($name) step=refreshStatuses | ${mcpContextSnapshot(requestedName = name)}")
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        UiText.raw(
                            if (result.success) "MCP server '$name' refreshed: ${result.summary}"
                            else "MCP server '$name' test failed: ${result.summary}",
                        ),
                    ),
                )
                context.eventHub.publish(
                    AppEvent.McpFeedbackUpdated(
                        message = if (result.success) "Test succeeded: ${result.summary}" else "Test failed: ${result.summary}",
                        isError = !result.success,
                    ),
                )
            }.onFailure { error ->
                logMcpDiagnostic(
                    message = "MCP coroutine handled failure: label=runMcpServerTest($name) | ${mcpContextSnapshot(requestedName = name)}",
                    error = error,
                )
                context.eventHub.publish(
                    AppEvent.McpFeedbackUpdated(message = error.message ?: "Failed to test MCP server '$name'.", isError = true),
                )
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to test MCP server '$name'.")),
                )
            }
            updateMcpBusy { copy(testingName = null) }
            logMcpDiagnostic("MCP coroutine finish: label=runMcpServerTest($name) | ${mcpContextSnapshot(requestedName = name)}")
        }
    }

    private fun updateMcpBusy(transform: McpBusyState.() -> McpBusyState) {
        context.eventHub.publish(
            AppEvent.McpBusyStateUpdated(context.rightDrawerStore.state.value.mcpBusyState.transform()),
        )
    }

    private fun mcpAdapter(): McpManagementAdapter = context.mcpAdapterRegistry.defaultAdapter()

    private fun mcpContextSnapshot(requestedName: String? = null): String {
        val state = context.rightDrawerStore.state.value
        val draftName = state.mcpDraft.name.ifBlank { "<blank>" }
        val editingName = state.editingMcpName ?: "<none>"
        val preview = state.mcpDraft.configJson
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(180)
            .ifBlank { "<empty>" }
        return buildList {
            add("settingsSection=${state.settingsSection}")
            add("mcpSettingsPage=${state.mcpSettingsPage}")
            add("editingMcpName=$editingName")
            add("draftName=$draftName")
            requestedName?.let { add("requestedName=$it") }
            add("busy=${state.mcpBusyState}")
            add("draftJsonPreview=$preview")
        }.joinToString(" | ")
    }

    private fun logMcpDiagnostic(message: String, error: Throwable? = null) {
        context.diagnosticLog(message, error)
    }
}

private fun McpServerDraft.normalized(): McpServerDraft {
    return copy(
        configJson = configJson.trim(),
        name = name.trim(),
        originalName = originalName?.trim()?.ifBlank { null },
    )
}
