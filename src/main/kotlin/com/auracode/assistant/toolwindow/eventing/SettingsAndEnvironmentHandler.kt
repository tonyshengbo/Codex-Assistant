package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.notification.AuraNotificationGroup
import com.auracode.assistant.service.TokenUsageStatsService
import com.auracode.assistant.provider.claude.ClaudeCliVersionCheckStatus
import com.auracode.assistant.provider.claude.ClaudeCliVersionSnapshot
import com.auracode.assistant.provider.claude.claudeCliInstallActionFor
import com.auracode.assistant.provider.codex.CodexCliVersionCheckStatus
import com.auracode.assistant.provider.codex.CodexCliVersionSnapshot
import com.auracode.assistant.provider.codex.CodexModelCatalogRefreshResult
import com.auracode.assistant.provider.codex.codexCliInstallActionFor
import com.auracode.assistant.provider.runtime.RuntimePackageManager
import com.auracode.assistant.provider.runtime.RuntimePackageManagerDetector
import com.auracode.assistant.settings.SavedAgentDefinition
import com.auracode.assistant.settings.mcp.McpAuthActionResult
import com.auracode.assistant.settings.mcp.McpBusyState
import com.auracode.assistant.settings.mcp.McpManagementAdapter
import com.auracode.assistant.settings.mcp.McpServerDraft
import com.auracode.assistant.settings.mcp.McpTestResult
import com.auracode.assistant.settings.mcp.McpValidationErrors
import com.auracode.assistant.settings.mcp.validate
import com.auracode.assistant.toolwindow.settings.RuntimeSettingsTab
import com.auracode.assistant.toolwindow.settings.SettingsSection
import com.auracode.assistant.toolwindow.settings.SkillsSettingsTab
import com.auracode.assistant.toolwindow.settings.McpSettingsTab
import com.auracode.assistant.toolwindow.settings.mcpSettingsTabForEngine
import com.auracode.assistant.toolwindow.settings.skillsSettingsTabForEngine
import com.auracode.assistant.toolwindow.settings.TokenUsageRange
import com.auracode.assistant.toolwindow.settings.TokenUsageSettingsTab
import com.auracode.assistant.toolwindow.settings.tokenUsageScopeKey
import com.auracode.assistant.toolwindow.settings.tokenUsageSettingsTabForEngine
import com.auracode.assistant.toolwindow.shell.SidePanelKind
import com.auracode.assistant.toolwindow.shell.RuntimeCliInstallDialogState
import com.auracode.assistant.toolwindow.shell.RuntimeCliInstallOption
import com.auracode.assistant.toolwindow.shell.SkillImportDialogPhase
import com.auracode.assistant.toolwindow.shell.SkillImportDialogState
import com.auracode.assistant.toolwindow.shared.UiText
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import java.nio.file.Path
import java.util.UUID

internal class SettingsAndEnvironmentHandler(
    private val context: ToolWindowCoordinatorContext,
) {
    private val tokenUsageStatsService = TokenUsageStatsService(
        loadLedgerRecords = { engineId -> context.chatService.listUsageLedgerRecords(engineId) },
    )
    private val runtimePackageManagerDetector = RuntimePackageManagerDetector()
    private var lastTokenUsageRefreshSignature: String? = null

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

    fun applyUiScalePreview(mode: com.auracode.assistant.settings.UiScaleMode) {
        if (context.settingsService.uiScaleMode() == mode) return
        context.settingsService.setUiScaleMode(mode)
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

    /** Applies the persisted CLI debug logging preference and refreshes the settings snapshot. */
    fun applyCliDebugLoggingPreference(enabled: Boolean) {
        if (context.settingsService.cliDebugLoggingEnabled() == enabled) return
        context.settingsService.setCliDebugLoggingEnabled(enabled)
        context.publishSettingsSnapshot()
    }

    fun applyCodexCliAutoUpdatePreference(enabled: Boolean) {
        if (context.settingsService.codexCliAutoUpdateCheckEnabled() == enabled) return
        context.settingsService.setCodexCliAutoUpdateCheckEnabled(enabled)
        context.publishSettingsSnapshot()
    }

    fun saveSettings() {
        val sidePanelState = context.sidePanelStore.state.value
        val oldLanguage = context.settingsService.uiLanguageMode()
        val oldTheme = context.settingsService.uiThemeMode()
        val oldUiScale = context.settingsService.uiScaleMode()
        val state = context.settingsService.state
        state.setExecutablePathFor("codex", sidePanelState.codexCliPath.trim())
        state.setExecutablePathFor("claude", sidePanelState.claudeCliPath.trim())
        context.settingsService.setNodeExecutablePath(sidePanelState.nodePath.trim())
        context.settingsService.setUiLanguageMode(sidePanelState.languageMode)
        context.settingsService.setUiThemeMode(sidePanelState.themeMode)
        context.settingsService.setUiScaleMode(sidePanelState.uiScaleMode)
        context.settingsService.setAutoContextEnabled(sidePanelState.autoContextEnabled)
        context.settingsService.setBackgroundCompletionNotificationsEnabled(
            sidePanelState.backgroundCompletionNotificationsEnabled,
        )
        context.settingsService.setCliDebugLoggingEnabled(sidePanelState.cliDebugLoggingEnabled)
        context.settingsService.setCodexCliAutoUpdateCheckEnabled(sidePanelState.codexCliAutoUpdateCheckEnabled)
        if (oldLanguage != context.settingsService.uiLanguageMode()) {
            context.settingsService.notifyLanguageChanged()
        }
        if (oldTheme != context.settingsService.uiThemeMode() || oldUiScale != context.settingsService.uiScaleMode()) {
            context.settingsService.notifyAppearanceChanged()
        }
        context.publishSettingsSnapshot()
        refreshRuntimeChecksForCurrentState()
    }

    fun detectCodexEnvironment() {
        val sidePanelState = context.sidePanelStore.state.value
        context.eventHub.publish(AppEvent.CodexEnvironmentCheckRunning(true))
        context.coroutineLauncher.launch("detectCodexEnvironment") {
            runCatching {
                context.codexEnvironmentDetector.autoDetect(
                    configuredCodexPath = sidePanelState.codexCliPath,
                    configuredNodePath = sidePanelState.nodePath,
                )
            }.onSuccess { result ->
                context.eventHub.publish(
                    AppEvent.CodexEnvironmentCheckUpdated(
                        result = result,
                        updateDraftPaths = true,
                    ),
                )
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
        val sidePanelState = context.sidePanelStore.state.value
        context.eventHub.publish(AppEvent.CodexEnvironmentCheckRunning(true))
        context.coroutineLauncher.launch("testCodexEnvironment") {
            runCatching {
                context.codexEnvironmentDetector.testEnvironment(
                    configuredCodexPath = sidePanelState.codexCliPath,
                    configuredNodePath = sidePanelState.nodePath,
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

    /** Opens the runtime install dialog for the requested engine using current package-manager availability. */
    fun openRuntimeCliInstallDialog(engineId: String) {
        val options = buildRuntimeCliInstallOptions(engineId)
        val selectedPackageManagerId = options.firstOrNull { it.available }?.packageManagerId
            ?: options.firstOrNull()?.packageManagerId
            ?: return
        context.eventHub.publish(AppEvent.RuntimeCliInstallFeedbackChanged(null))
        context.eventHub.publish(
            AppEvent.RuntimeCliInstallDialogStateChanged(
                RuntimeCliInstallDialogState(
                    engineId = engineId,
                    options = options,
                    selectedPackageManagerId = selectedPackageManagerId,
                ),
            ),
        )
    }

    /** Updates the selected package manager inside the open runtime install dialog. */
    fun selectRuntimeCliInstallPackageManager(packageManagerId: String) {
        val current = context.sidePanelStore.state.value.runtimeCliInstallDialogState ?: return
        if (current.options.none { it.packageManagerId == packageManagerId }) return
        context.eventHub.publish(
            AppEvent.RuntimeCliInstallDialogStateChanged(
                current.copy(selectedPackageManagerId = packageManagerId),
            ),
        )
    }

    /** Closes the runtime install dialog without mutating any runtime state. */
    fun dismissRuntimeCliInstallDialog() {
        context.eventHub.publish(AppEvent.RuntimeCliInstallDialogStateChanged(null))
    }

    /** Refreshes the Codex CLI version snapshot and publishes the latest UI state. */
    fun refreshCodexCliVersion(force: Boolean = false, announceResult: Boolean = true) {
        val sidePanelState = context.sidePanelStore.state.value
        val checking = context.codexCliVersionService.snapshot().copy(
            checkStatus = CodexCliVersionCheckStatus.CHECKING,
            message = "Checking Codex CLI version...",
        )
        context.eventHub.publish(AppEvent.CodexCliVersionSnapshotUpdated(checking))
        context.coroutineLauncher.launch("refreshCodexCliVersion(force=$force)") {
            runCatching {
                context.codexCliVersionService.refresh(
                    force = force,
                    configuredCodexPathOverride = sidePanelState.codexCliPath,
                    configuredNodePathOverride = sidePanelState.nodePath,
                )
            }.onSuccess { snapshot ->
                context.eventHub.publish(AppEvent.CodexCliVersionSnapshotUpdated(snapshot))
                context.publishSettingsSnapshot()
                if (announceResult) {
                    context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(snapshot.message)))
                }
                notifyCodexCliUpdateIfNeeded(snapshot)
            }.onFailure { error ->
                val failed = CodexCliVersionSnapshot(
                    checkStatus = CodexCliVersionCheckStatus.REMOTE_CHECK_FAILED,
                    currentVersion = context.codexCliVersionService.snapshot().currentVersion,
                    latestVersion = context.codexCliVersionService.snapshot().latestVersion,
                    ignoredVersion = context.codexCliVersionService.snapshot().ignoredVersion,
                    upgradeSource = context.codexCliVersionService.snapshot().upgradeSource,
                    displayCommand = context.codexCliVersionService.snapshot().displayCommand,
                    isUpgradeSupported = context.codexCliVersionService.snapshot().isUpgradeSupported,
                    lastCheckedAt = context.codexCliVersionService.snapshot().lastCheckedAt,
                    message = error.message ?: "Failed to check Codex CLI version.",
                )
                context.eventHub.publish(AppEvent.CodexCliVersionSnapshotUpdated(failed))
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(failed.message)))
            }
        }
    }

    /** Executes the best-effort Codex CLI upgrade flow and republishes the version snapshot. */
    fun upgradeCodexCli() {
        val sidePanelState = context.sidePanelStore.state.value
        context.eventHub.publish(AppEvent.RuntimeCliInstallFeedbackChanged(null))
        context.eventHub.publish(
            AppEvent.CodexCliVersionSnapshotUpdated(
                context.codexCliVersionService.snapshot().copy(
                    checkStatus = CodexCliVersionCheckStatus.UPGRADE_IN_PROGRESS,
                    message = "Upgrading Codex CLI...",
                ),
            ),
        )
        context.coroutineLauncher.launch("upgradeCodexCli") {
            runCatching {
                context.codexCliVersionService.upgrade(
                    configuredCodexPathOverride = sidePanelState.codexCliPath,
                    configuredNodePathOverride = sidePanelState.nodePath,
                )
            }.onSuccess { snapshot ->
                context.eventHub.publish(AppEvent.CodexCliVersionSnapshotUpdated(snapshot))
                context.publishSettingsSnapshot()
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(snapshot.message)))
            }.onFailure { error ->
                val failed = context.codexCliVersionService.snapshot().copy(
                    checkStatus = CodexCliVersionCheckStatus.UPGRADE_FAILED,
                    message = error.message ?: "Failed to upgrade Codex CLI.",
                )
                context.eventHub.publish(AppEvent.CodexCliVersionSnapshotUpdated(failed))
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(failed.message)))
            }
        }
    }

    /** Ignores the provided Codex CLI version and republishes the cached snapshot. */
    fun ignoreCodexCliVersion(version: String) {
        if (version.isBlank()) return
        val snapshot = context.codexCliVersionService.ignoreVersion(version)
        context.eventHub.publish(AppEvent.CodexCliVersionSnapshotUpdated(snapshot))
        context.publishSettingsSnapshot()
        context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw("Ignored Codex CLI version '$version'.")))
    }

    /** Refreshes the Claude CLI version snapshot and publishes the latest UI state. */
    fun refreshClaudeCliVersion(force: Boolean = false, announceResult: Boolean = true) {
        val checking = context.claudeCliVersionService.snapshot().copy(
            checkStatus = ClaudeCliVersionCheckStatus.CHECKING,
            message = "Checking Claude CLI version...",
        )
        context.eventHub.publish(AppEvent.ClaudeCliVersionSnapshotUpdated(checking))
        context.coroutineLauncher.launch("refreshClaudeCliVersion(force=$force)") {
            runCatching {
                context.claudeCliVersionService.refresh(force = force)
            }.onSuccess { snapshot ->
                context.eventHub.publish(AppEvent.ClaudeCliVersionSnapshotUpdated(snapshot))
                context.publishSettingsSnapshot()
                if (announceResult) {
                    context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(snapshot.message)))
                }
            }.onFailure { error ->
                val failed = ClaudeCliVersionSnapshot(
                    checkStatus = ClaudeCliVersionCheckStatus.REMOTE_CHECK_FAILED,
                    currentVersion = context.claudeCliVersionService.snapshot().currentVersion,
                    latestVersion = context.claudeCliVersionService.snapshot().latestVersion,
                    ignoredVersion = context.claudeCliVersionService.snapshot().ignoredVersion,
                    upgradeSource = context.claudeCliVersionService.snapshot().upgradeSource,
                    displayCommand = context.claudeCliVersionService.snapshot().displayCommand,
                    isUpgradeSupported = context.claudeCliVersionService.snapshot().isUpgradeSupported,
                    lastCheckedAt = context.claudeCliVersionService.snapshot().lastCheckedAt,
                    message = error.message ?: "Failed to check Claude CLI version.",
                )
                context.eventHub.publish(AppEvent.ClaudeCliVersionSnapshotUpdated(failed))
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(failed.message)))
            }
        }
    }

    /** Executes the best-effort Claude CLI upgrade flow and republishes the version snapshot. */
    fun upgradeClaudeCli() {
        context.eventHub.publish(AppEvent.RuntimeCliInstallFeedbackChanged(null))
        context.eventHub.publish(
            AppEvent.ClaudeCliVersionSnapshotUpdated(
                context.claudeCliVersionService.snapshot().copy(
                    checkStatus = ClaudeCliVersionCheckStatus.UPGRADE_IN_PROGRESS,
                    message = "Upgrading Claude CLI...",
                ),
            ),
        )
        context.coroutineLauncher.launch("upgradeClaudeCli") {
            runCatching {
                context.claudeCliVersionService.upgrade()
            }.onSuccess { snapshot ->
                context.eventHub.publish(AppEvent.ClaudeCliVersionSnapshotUpdated(snapshot))
                context.publishSettingsSnapshot()
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(snapshot.message)))
            }.onFailure { error ->
                val failed = context.claudeCliVersionService.snapshot().copy(
                    checkStatus = ClaudeCliVersionCheckStatus.UPGRADE_FAILED,
                    message = error.message ?: "Failed to upgrade Claude CLI.",
                )
                context.eventHub.publish(AppEvent.ClaudeCliVersionSnapshotUpdated(failed))
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(failed.message)))
            }
        }
    }

    /** Installs Codex CLI through the selected package manager and refreshes runtime diagnostics afterwards. */
    fun installCodexCli(packageManagerId: String) {
        val sidePanelState = context.sidePanelStore.state.value
        val packageManager = RuntimePackageManager.fromId(packageManagerId) ?: return
        context.eventHub.publish(AppEvent.RuntimeCliInstallDialogStateChanged(null))
        context.eventHub.publish(AppEvent.RuntimeCliInstallFeedbackChanged(null))
        context.eventHub.publish(
            AppEvent.CodexCliVersionSnapshotUpdated(
                context.codexCliVersionService.snapshot().copy(
                    checkStatus = CodexCliVersionCheckStatus.INSTALL_IN_PROGRESS,
                    message = AuraCodeBundle.message("settings.runtime.version.status.installing"),
                ),
            ),
        )
        context.coroutineLauncher.launch("installCodexCli(${packageManager.id})") {
            runCatching {
                context.codexCliVersionService.install(
                    packageManager = packageManager,
                    configuredCodexPathOverride = sidePanelState.codexCliPath,
                    configuredNodePathOverride = sidePanelState.nodePath,
                )
            }.onSuccess { snapshot ->
                context.eventHub.publish(AppEvent.CodexCliVersionSnapshotUpdated(snapshot))
                context.publishSettingsSnapshot()
                detectCodexEnvironment()
                if (snapshot.checkStatus == CodexCliVersionCheckStatus.INSTALL_FAILED) {
                    context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(snapshot.message)))
                } else {
                    val successMessage = AuraCodeBundle.message(
                        "settings.runtime.install.success",
                        AuraCodeBundle.message("settings.runtime.tab.codex"),
                    )
                    context.eventHub.publish(AppEvent.RuntimeCliInstallFeedbackChanged(successMessage))
                    context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(successMessage)))
                }
            }.onFailure { error ->
                val failed = context.codexCliVersionService.snapshot().copy(
                    checkStatus = CodexCliVersionCheckStatus.INSTALL_FAILED,
                    message = error.message ?: AuraCodeBundle.message("settings.runtime.install.failed"),
                )
                context.eventHub.publish(AppEvent.CodexCliVersionSnapshotUpdated(failed))
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(failed.message)))
            }
        }
    }

    /** Installs Claude CLI through the selected package manager and refreshes runtime diagnostics afterwards. */
    fun installClaudeCli(packageManagerId: String) {
        val packageManager = RuntimePackageManager.fromId(packageManagerId) ?: return
        context.eventHub.publish(AppEvent.RuntimeCliInstallDialogStateChanged(null))
        context.eventHub.publish(AppEvent.RuntimeCliInstallFeedbackChanged(null))
        context.eventHub.publish(
            AppEvent.ClaudeCliVersionSnapshotUpdated(
                context.claudeCliVersionService.snapshot().copy(
                    checkStatus = ClaudeCliVersionCheckStatus.INSTALL_IN_PROGRESS,
                    message = AuraCodeBundle.message("settings.runtime.version.status.installing"),
                ),
            ),
        )
        context.coroutineLauncher.launch("installClaudeCli(${packageManager.id})") {
            runCatching {
                context.claudeCliVersionService.install(packageManager)
            }.onSuccess { snapshot ->
                context.eventHub.publish(AppEvent.ClaudeCliVersionSnapshotUpdated(snapshot))
                context.publishSettingsSnapshot()
                refreshClaudeRuntimeCheck()
                if (snapshot.checkStatus == ClaudeCliVersionCheckStatus.INSTALL_FAILED) {
                    context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(snapshot.message)))
                } else {
                    val successMessage = AuraCodeBundle.message(
                        "settings.runtime.install.success",
                        AuraCodeBundle.message("settings.runtime.tab.claude"),
                    )
                    context.eventHub.publish(AppEvent.RuntimeCliInstallFeedbackChanged(successMessage))
                    context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(successMessage)))
                }
            }.onFailure { error ->
                val failed = context.claudeCliVersionService.snapshot().copy(
                    checkStatus = ClaudeCliVersionCheckStatus.INSTALL_FAILED,
                    message = error.message ?: AuraCodeBundle.message("settings.runtime.install.failed"),
                )
                context.eventHub.publish(AppEvent.ClaudeCliVersionSnapshotUpdated(failed))
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(failed.message)))
            }
        }
    }

    fun saveAgentDraft() {
        val sidePanelState = context.sidePanelStore.state.value
        val id = sidePanelState.editingAgentId?.takeIf { it.isNotBlank() }
        val name = sidePanelState.agentDraftName.trim()
        val prompt = sidePanelState.agentDraftPrompt.trim()
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
        val draft = context.submissionStore.state.value.customModelDraft.trim()
        if (draft.isBlank()) {
            context.eventHub.publish(
                AppEvent.StatusTextUpdated(UiText.raw(AuraCodeBundle.message("composer.model.custom.error.blank"))),
            )
            return
        }
        val existing = context.settingsService.customModelIds()
        val currentEngineModels = context.chatService.engineDescriptor(context.chatService.defaultEngineId())?.models.orEmpty()
        if (currentEngineModels.contains(draft) || existing.contains(draft)) {
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
        if (context.submissionStore.state.value.selectedModel == normalized) {
            val fallbackModel = context.settingsService.selectedSubmissionModel(context.chatService.defaultEngineId())
            context.eventHub.publishUiIntent(UiIntent.SelectModel(fallbackModel))
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

    fun loadSkills(
        forceReload: Boolean = false,
        engineIdOverride: String? = null,
    ) {
        context.eventHub.publish(AppEvent.SkillsLoadingChanged(loading = true))
        context.coroutineLauncher.launch("loadSkills") {
            runCatching {
                publishSkillsSnapshot(
                    forceReload = forceReload,
                    engineId = engineIdOverride ?: currentSkillsEngineId(),
                )
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
        warmRuntimeSkillsCacheForEngine(
            engineId = context.currentEngineId(),
            forceReload = false,
        )
    }

    /** Preloads one engine's runtime skills for composer slash suggestions and validation. */
    fun warmRuntimeSkillsCacheForEngine(
        engineId: String,
        forceReload: Boolean = false,
    ) {
        context.coroutineLauncher.launch("warmSkillsRuntimeCache($engineId)") {
            runCatching {
                val snapshot = loadSkillsSnapshot(
                    forceReload = forceReload,
                    engineId = engineId,
                )
                publishRuntimeSkillsSnapshot(snapshot)
            }
        }
    }

    /** 后台异步刷新 Codex 模型列表，有变化时更新引擎描述符并推送设置快照。 */
    fun warmCodexModelCatalog() {
        context.coroutineLauncher.launch("warmCodexModelCatalog") {
            runCatching {
                val result = context.codexModelCatalogService.refresh()
                if (result is CodexModelCatalogRefreshResult.Changed) {
                    context.chatService.updateEngineModels(
                        engineId = "codex",
                        models = result.models.map { it.slug },
                        displayNames = result.models.associate { it.slug to it.displayName },
                    )
                    context.publishSettingsSnapshot()
                }
            }
        }
    }

    /** Preloads the latest Codex CLI version snapshot for settings and about views. */
    fun warmCodexCliVersionState() {
        context.coroutineLauncher.launch("warmCodexCliVersionState") {
            runCatching {
                if (context.codexCliVersionService.shouldRefresh(force = false)) {
                    context.eventHub.publish(
                        AppEvent.CodexCliVersionSnapshotUpdated(
                            context.codexCliVersionService.snapshot().copy(
                                checkStatus = CodexCliVersionCheckStatus.CHECKING,
                                message = "Checking Codex CLI version...",
                            ),
                        ),
                    )
                    val snapshot = context.codexCliVersionService.refresh(force = false)
                    context.eventHub.publish(AppEvent.CodexCliVersionSnapshotUpdated(snapshot))
                    context.publishSettingsSnapshot()
                    notifyCodexCliUpdateIfNeeded(snapshot)
                } else {
                    context.eventHub.publish(AppEvent.CodexCliVersionSnapshotUpdated(context.codexCliVersionService.snapshot()))
                }
            }
        }
    }

    /** Preloads the latest Claude CLI version snapshot for the runtime settings page. */
    fun warmClaudeCliVersionState() {
        context.coroutineLauncher.launch("warmClaudeCliVersionState") {
            runCatching {
                if (context.claudeCliVersionService.shouldRefresh(force = false)) {
                    context.eventHub.publish(
                        AppEvent.ClaudeCliVersionSnapshotUpdated(
                            context.claudeCliVersionService.snapshot().copy(
                                checkStatus = ClaudeCliVersionCheckStatus.CHECKING,
                                message = "Checking Claude CLI version...",
                            ),
                        ),
                    )
                    val snapshot = context.claudeCliVersionService.refresh(force = false)
                    context.eventHub.publish(AppEvent.ClaudeCliVersionSnapshotUpdated(snapshot))
                    context.publishSettingsSnapshot()
                } else {
                    context.eventHub.publish(AppEvent.ClaudeCliVersionSnapshotUpdated(context.claudeCliVersionService.snapshot()))
                }
            }
        }
    }

    /** Reads the current runtime snapshot and publishes the unified skills page state. */
    private suspend fun publishSkillsSnapshot(
        forceReload: Boolean,
        engineId: String,
    ) {
        val snapshot = loadSkillsSnapshot(
            forceReload = forceReload,
            engineId = engineId,
        )
        publishRuntimeSkillsSnapshot(snapshot)
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

    /** Loads the latest managed skills snapshot for one engine and cwd pair. */
    private suspend fun loadSkillsSnapshot(
        forceReload: Boolean,
        engineId: String,
    ) = context.engineSkillsService.loadSkills(
        engineId = engineId,
        cwd = context.chatService.currentWorkingDirectory(),
        forceReload = forceReload,
    )

    /** Broadcasts a runtime-skills cache refresh without mutating the Settings page state. */
    private fun publishRuntimeSkillsSnapshot(
        snapshot: com.auracode.assistant.settings.skills.ManagedSkillsSnapshot,
    ) {
        context.eventHub.publish(
            AppEvent.RuntimeSkillsSnapshotUpdated(
                engineId = snapshot.engineId,
                cwd = snapshot.cwd,
            ),
        )
    }

    fun toggleSkillEnabled(name: String, path: String, enabled: Boolean) {
        if (name.isBlank() || path.isBlank()) return
        context.eventHub.publish(AppEvent.SkillsLoadingChanged(loading = true, activePath = path))
        context.coroutineLauncher.launch("toggleSkillEnabled($name,$enabled)") {
            runCatching {
                val snapshot = context.engineSkillsService.setSkillEnabled(
                    engineId = currentSkillsEngineId(),
                    cwd = context.chatService.currentWorkingDirectory(),
                    name = name,
                    path = path,
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
        context.openConversationFilePath(path)
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
                publishSkillsSnapshot(forceReload = true, engineId = currentSkillsEngineId())
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw("Uninstalled local skill '$name'.")))
            }.onFailure { error ->
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to uninstall skill '$name'.")),
                )
            }
            context.eventHub.publish(AppEvent.SkillsLoadingChanged(loading = false))
        }
    }

    fun importSkillRoot(path: String) {
        val normalizedPath = path.trim()
        if (normalizedPath.isBlank()) return
        context.eventHub.publish(
            AppEvent.SkillImportDialogStateChanged(
                SkillImportDialogState(
                    phase = SkillImportDialogPhase.IN_PROGRESS,
                    title = AuraCodeBundle.message("settings.skills.import.dialog.progress.title"),
                    message = AuraCodeBundle.message("settings.skills.import.dialog.progress.message", normalizedPath),
                    sourcePath = normalizedPath,
                ),
            ),
        )
        context.eventHub.publish(AppEvent.SkillsLoadingChanged(loading = true, activePath = normalizedPath))
        context.coroutineLauncher.launch("importSkillRoot($normalizedPath)") {
            runCatching {
                val targetEngineId = currentSkillsEngineId()
                val sourcePath = Path.of(normalizedPath)
                context.skillProjectionManager.projectDirectory(targetEngineId, sourcePath)
                context.skillsRuntimeService.invalidateEngine(targetEngineId)
                publishSkillsSnapshot(forceReload = true, engineId = targetEngineId)
                val successMessage = AuraCodeBundle.message(
                    "settings.skills.import.success",
                    "1",
                    skillsEngineLabel(targetEngineId),
                )
                context.eventHub.publish(AppEvent.SkillImportDialogStateChanged(
                    SkillImportDialogState(
                        phase = SkillImportDialogPhase.SUCCEEDED,
                        title = AuraCodeBundle.message("settings.skills.import.dialog.success.title"),
                        message = successMessage,
                        sourcePath = normalizedPath,
                    ),
                ))
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(successMessage)))
            }.onFailure { error ->
                val failureMessage = error.message ?: AuraCodeBundle.message(
                    "settings.skills.import.failed",
                    skillsEngineLabel(currentSkillsEngineId()),
                )
                context.eventHub.publish(AppEvent.SkillImportDialogStateChanged(
                    SkillImportDialogState(
                        phase = SkillImportDialogPhase.FAILED,
                        title = AuraCodeBundle.message("settings.skills.import.dialog.failed.title"),
                        message = failureMessage,
                        sourcePath = normalizedPath,
                    ),
                ))
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        UiText.raw(failureMessage),
                    ),
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
                // Publish server list immediately so the UI renders before the slower app-server status check.
                context.eventHub.publish(AppEvent.McpServersLoaded(servers))
                logMcpDiagnostic("MCP adapter call begin: label=loadMcpServers step=refreshStatuses | ${mcpContextSnapshot()}")
                val statuses = adapter.refreshStatuses(servers.map { it.name })
                logMcpDiagnostic("MCP adapter call success: label=loadMcpServers step=refreshStatuses count=${statuses.size} | ${mcpContextSnapshot()}")
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

    fun loadMcpEditorDraft(name: String? = null) {
        updateMcpBusy { copy(loading = true) }
        logMcpDiagnostic(
            "MCP coroutine scheduled: label=loadMcpEditorDraft(name=${name ?: "<all>"}) | " +
                mcpContextSnapshot(requestedName = name),
        )
        context.coroutineLauncher.launch("loadMcpEditorDraft") {
            logMcpDiagnostic(
                "MCP coroutine start: label=loadMcpEditorDraft(name=${name ?: "<all>"}) | " +
                    mcpContextSnapshot(requestedName = name),
            )
            runCatching {
                val adapter = mcpAdapter()
                val draft = if (name.isNullOrBlank()) {
                    logMcpDiagnostic("MCP adapter call begin: label=loadMcpEditorDraft step=getEditorDraft | ${mcpContextSnapshot()}")
                    adapter.getEditorDraft().also {
                        logMcpDiagnostic("MCP adapter call success: label=loadMcpEditorDraft step=getEditorDraft | ${mcpContextSnapshot()}")
                    }
                } else {
                    logMcpDiagnostic(
                        "MCP adapter call begin: label=loadMcpEditorDraft step=getServer | " +
                            mcpContextSnapshot(requestedName = name),
                    )
                    adapter.getServer(name)?.also {
                        logMcpDiagnostic(
                            "MCP adapter call success: label=loadMcpEditorDraft step=getServer | " +
                                mcpContextSnapshot(requestedName = name),
                        )
                    } ?: error("Failed to load MCP server '$name'.")
                }
                context.eventHub.publish(AppEvent.McpDraftLoaded(draft))
            }.onFailure { error ->
                logMcpDiagnostic(
                    message = "MCP coroutine handled failure: label=loadMcpEditorDraft(name=${name ?: "<all>"}) | " +
                        mcpContextSnapshot(requestedName = name),
                    error = error,
                )
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to load MCP server JSON.")),
                )
            }
            updateMcpBusy { copy(loading = false) }
            logMcpDiagnostic(
                "MCP coroutine finish: label=loadMcpEditorDraft(name=${name ?: "<all>"}) | " +
                    mcpContextSnapshot(requestedName = name),
            )
        }
    }

    fun saveMcpDraft(afterSave: (suspend (List<String>) -> Unit)? = null) {
        val normalized = context.sidePanelStore.state.value.mcpDraft.normalized()
        val validation = normalized.validate()
        context.eventHub.publish(AppEvent.McpValidationErrorsUpdated(validation))
        if (validation.hasAny()) {
            context.eventHub.publish(AppEvent.McpFeedbackUpdated(message = "Fix the MCP config errors before saving.", isError = true))
            context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw("Please fix the MCP config errors before saving.")))
            return
        }
        val entry = normalized.parseSingleServerEntry().getOrElse { error ->
            context.eventHub.publish(
                AppEvent.McpValidationErrorsUpdated(McpValidationErrors(json = error.message ?: "Invalid MCP JSON.")),
            )
            return
        }
        val draft = McpServerDraft(
            originalName = normalized.originalName,
            name = entry.name,
            configJson = McpServerDraft.entryConfigJson(entry.name, entry.config),
        )
        val savedName = draft.name.trim()
        updateMcpBusy { copy(saving = true) }
        logMcpDiagnostic("MCP coroutine scheduled: label=saveMcpDraft(name=$savedName) | ${mcpContextSnapshot()}")
        context.coroutineLauncher.launch("saveMcpDraft(name=$savedName)") {
            logMcpDiagnostic("MCP coroutine start: label=saveMcpDraft(name=$savedName) | ${mcpContextSnapshot()}")
            runCatching {
                val adapter = mcpAdapter()
                logMcpDiagnostic(
                    "MCP adapter call begin: label=saveMcpDraft(name=$savedName) step=saveServer name=${draft.name} | " +
                        mcpContextSnapshot(requestedName = draft.originalName),
                )
                adapter.saveServer(draft)
                logMcpDiagnostic(
                    "MCP adapter call success: label=saveMcpDraft(name=$savedName) step=saveServer name=${draft.name} | " +
                        mcpContextSnapshot(requestedName = draft.originalName),
                )
                context.eventHub.publish(
                    AppEvent.McpDraftLoaded(
                        normalized.copy(
                            originalName = null,
                            name = "",
                            configJson = McpServerDraft.entryConfigJson(entry.name, entry.config),
                        ),
                    ),
                )
                logMcpDiagnostic("MCP adapter call begin: label=saveMcpDraft(name=$savedName) step=listServers | ${mcpContextSnapshot()}")
                context.eventHub.publish(AppEvent.McpServersLoaded(adapter.listServers()))
                logMcpDiagnostic("MCP adapter call success: label=saveMcpDraft(name=$savedName) step=listServers | ${mcpContextSnapshot()}")
                logMcpDiagnostic("MCP adapter call begin: label=saveMcpDraft(name=$savedName) step=refreshStatuses | ${mcpContextSnapshot()}")
                context.eventHub.publish(AppEvent.McpStatusesUpdated(adapter.refreshStatuses()))
                logMcpDiagnostic("MCP adapter call success: label=saveMcpDraft(name=$savedName) step=refreshStatuses | ${mcpContextSnapshot()}")
                val feedbackMessage = "Saved MCP server '$savedName'."
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(feedbackMessage)))
                context.eventHub.publish(AppEvent.McpFeedbackUpdated(message = feedbackMessage, isError = false))
                afterSave?.invoke(listOf(savedName))
            }.onFailure { error ->
                logMcpDiagnostic(
                    message = "MCP coroutine handled failure: label=saveMcpDraft(name=$savedName) | ${mcpContextSnapshot()}",
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
            logMcpDiagnostic("MCP coroutine finish: label=saveMcpDraft(name=$savedName) | ${mcpContextSnapshot()}")
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
                val result: McpAuthActionResult = if (login) {
                    adapter.login(name) { authorizationUrl ->
                        val openedInBrowser = authorizationUrl?.let(context.openExternalUrl) == true
                        val suffix = when {
                            openedInBrowser -> " Opened the authorization page in your browser."
                            !authorizationUrl.isNullOrBlank() -> " $authorizationUrl"
                            else -> ""
                        }
                        context.eventHub.publish(
                            AppEvent.StatusTextUpdated(
                                UiText.raw("Open the authorization URL for '$name'.$suffix"),
                            ),
                        )
                    }
                } else {
                    adapter.logout(name)
                }
                logMcpDiagnostic("MCP adapter call success: label=authenticateMcpServer($name, login=$login) step=${if (login) "login" else "logout"} | ${mcpContextSnapshot(requestedName = name)}")
                logMcpDiagnostic("MCP adapter call begin: label=authenticateMcpServer($name, login=$login) step=refreshStatuses | ${mcpContextSnapshot(requestedName = name)}")
                context.eventHub.publish(AppEvent.McpStatusesUpdated(adapter.refreshStatuses()))
                logMcpDiagnostic("MCP adapter call success: label=authenticateMcpServer($name, login=$login) step=refreshStatuses | ${mcpContextSnapshot(requestedName = name)}")
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(result.message)))
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

    fun cancelMcpServerAuthentication(name: String) {
        if (name.isBlank()) return
        logMcpDiagnostic("MCP coroutine scheduled: label=cancelMcpServerAuthentication($name) | ${mcpContextSnapshot(requestedName = name)}")
        context.coroutineLauncher.launch("cancelMcpServerAuthentication($name)") {
            logMcpDiagnostic("MCP coroutine start: label=cancelMcpServerAuthentication($name) | ${mcpContextSnapshot(requestedName = name)}")
            runCatching {
                val adapter = mcpAdapter()
                val result = adapter.cancelLogin(name)
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(result.message)))
            }.onFailure { error ->
                logMcpDiagnostic(
                    message = "MCP coroutine handled failure: label=cancelMcpServerAuthentication($name) | ${mcpContextSnapshot(requestedName = name)}",
                    error = error,
                )
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(
                        UiText.raw(error.message ?: "Failed to cancel MCP authentication for '$name'."),
                    ),
                )
            }
            logMcpDiagnostic("MCP coroutine finish: label=cancelMcpServerAuthentication($name) | ${mcpContextSnapshot(requestedName = name)}")
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
            is UiIntent.SelectMcpSettingsTab,
            is UiIntent.EditMcpDraftName,
            is UiIntent.EditMcpDraftJson,
            is UiIntent.ToggleMcpServerEnabled,
            is UiIntent.DeleteMcpServer,
            is UiIntent.TestMcpServer,
            is UiIntent.LoginMcpServer,
            is UiIntent.CancelMcpLogin,
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
            is UiIntent.SelectMcpSettingsTab -> "SelectMcpSettingsTab(tab=${intent.tab.name})"
            is UiIntent.TestMcpServer -> "TestMcpServer(name=${intent.name ?: "<draft>"})"
            is UiIntent.SelectMcpServerForEdit -> "SelectMcpServerForEdit(name=${intent.name})"
            is UiIntent.EditMcpDraftName -> "EditMcpDraftName"
            is UiIntent.EditMcpDraftJson -> "EditMcpDraftJson"
            is UiIntent.ToggleMcpServerEnabled -> "ToggleMcpServerEnabled(name=${intent.name}, enabled=${intent.enabled})"
            is UiIntent.DeleteMcpServer -> "DeleteMcpServer(name=${intent.name})"
            is UiIntent.LoginMcpServer -> "LoginMcpServer(name=${intent.name})"
            is UiIntent.CancelMcpLogin -> "CancelMcpLogin(name=${intent.name})"
            is UiIntent.LogoutMcpServer -> "LogoutMcpServer(name=${intent.name})"
            else -> intent::class.simpleName ?: "UnknownMcpIntent"
        }
    }

    fun onSettingsDrawerOpened() {
        context.publishSettingsSnapshot()
        when (context.sidePanelStore.state.value.settingsSection) {
            SettingsSection.MCP -> selectMcpTabForCurrentEngine()
            SettingsSection.SKILLS -> selectSkillsTabForCurrentEngine()
            SettingsSection.TOKEN_USAGE -> selectTokenUsageTabForCurrentEngine()
            SettingsSection.BASIC -> Unit
            SettingsSection.RUNTIME -> refreshRuntimeChecksForCurrentState()
            SettingsSection.AGENTS,
            -> Unit
            SettingsSection.ABOUT -> refreshCodexCliVersion(force = false, announceResult = false)
        }
    }

    fun onSettingsSectionSelected(section: SettingsSection) {
        when (section) {
            SettingsSection.MCP -> selectMcpTabForCurrentEngine()
            SettingsSection.SKILLS -> selectSkillsTabForCurrentEngine()
            SettingsSection.TOKEN_USAGE -> selectTokenUsageTabForCurrentEngine()
            SettingsSection.BASIC -> Unit
            SettingsSection.RUNTIME -> {
                if (context.sidePanelStore.state.value.kind == com.auracode.assistant.toolwindow.shell.SidePanelKind.SETTINGS) {
                    refreshRuntimeChecksForCurrentState()
                }
            }
            SettingsSection.AGENTS,
            -> Unit
            SettingsSection.ABOUT -> refreshCodexCliVersion(force = false, announceResult = false)
        }
    }

    /** Refreshes runtime validation and the active version card for the current Runtime tab. */
    fun onRuntimeSettingsTabSelected(tab: RuntimeSettingsTab) {
        when (tab) {
            RuntimeSettingsTab.CODEX -> {
                detectCodexEnvironment()
                refreshCodexCliVersion(force = false, announceResult = false)
            }
            RuntimeSettingsTab.CLAUDE -> {
                refreshClaudeRuntimeCheck()
                refreshClaudeCliVersion(force = false, announceResult = false)
            }
        }
    }

    /** Refreshes skills data using the selected Skills settings tab. */
    fun onSkillsSettingsTabSelected(tab: SkillsSettingsTab) {
        loadSkills(forceReload = false, engineIdOverride = tab.engineId)
    }

    /** Refreshes MCP data only for tabs backed by a real adapter. */
    fun onMcpSettingsTabSelected(tab: McpSettingsTab) {
        loadMcpServers()
    }

    /** Refreshes Token Usage data using the selected engine tab. */
    fun onTokenUsageSettingsTabSelected(tab: TokenUsageSettingsTab) {
        loadTokenUsageStats(engineId = tab.engineId, range = context.sidePanelStore.state.value.tokenUsageRange)
    }

    /** Refreshes Token Usage data using the selected historical range. */
    fun onTokenUsageRangeSelected(range: TokenUsageRange) {
        loadTokenUsageStats(engineId = context.sidePanelStore.state.value.tokenUsageSettingsTab.engineId, range = range)
    }

    /** Loads the current Token Usage snapshot using the active page selection. */
    fun loadTokenUsageStats() {
        val state = context.sidePanelStore.state.value
        loadTokenUsageStats(
            engineId = state.tokenUsageSettingsTab.engineId,
            range = state.tokenUsageRange,
        )
    }

    /** Forces a Token Usage refresh using the active page selection. */
    fun refreshTokenUsageStats() {
        loadTokenUsageStats()
    }

    /** Refreshes Token Usage only when the page is visible and the active usage snapshot materially changed. */
    fun refreshTokenUsageStatsIfVisible() {
        val state = context.sidePanelStore.state.value
        if (state.kind != SidePanelKind.SETTINGS || state.settingsSection != SettingsSection.TOKEN_USAGE) {
            lastTokenUsageRefreshSignature = null
            return
        }
        val activeSession = context.chatService.listSessions().firstOrNull {
            it.id == context.chatService.getCurrentSessionId()
        }
        val snapshot = activeSession?.usageSnapshot
        if (activeSession?.providerId != state.tokenUsageSettingsTab.engineId || snapshot == null) {
            lastTokenUsageRefreshSignature = null
            return
        }
        val signature = buildString {
            append(activeSession.id)
            append('|')
            append(activeSession.providerId)
            append('|')
            append(snapshot.model)
            append('|')
            append(snapshot.inputTokens)
            append('|')
            append(snapshot.cachedInputTokens)
            append('|')
            append(snapshot.outputTokens)
            append('|')
            append(snapshot.capturedAt)
        }
        if (state.tokenUsageLoading || signature == lastTokenUsageRefreshSignature) {
            return
        }
        lastTokenUsageRefreshSignature = signature
        refreshTokenUsageStats()
    }

    fun mcpContextSnapshotForLog(): String = mcpContextSnapshot()

    private fun notifyCodexCliUpdateIfNeeded(snapshot: CodexCliVersionSnapshot) {
        if (!context.codexCliVersionService.shouldNotify(snapshot)) return
        AuraNotificationGroup.codexCliVersion()
            .createNotification(
                AuraCodeBundle.message("notification.codexCliVersion.title"),
                AuraCodeBundle.message(
                    "notification.codexCliVersion.content",
                    snapshot.currentVersion.ifBlank { AuraCodeBundle.message("settings.codexVersion.unknown") },
                    snapshot.latestVersion.ifBlank { AuraCodeBundle.message("settings.codexVersion.unknown") },
                ),
                NotificationType.INFORMATION,
            )
            .addAction(
                NotificationAction.createSimpleExpiring(
                    AuraCodeBundle.message("notification.codexCliVersion.openSettings"),
                ) {
                    context.eventHub.publishUiIntent(UiIntent.SelectSettingsSection(SettingsSection.RUNTIME))
                    context.eventHub.publishUiIntent(UiIntent.SelectRuntimeSettingsTab(RuntimeSettingsTab.CODEX))
                },
            )
            .addAction(
                NotificationAction.createSimpleExpiring(
                    AuraCodeBundle.message("notification.codexCliVersion.ignore"),
                ) {
                    ignoreCodexCliVersion(snapshot.latestVersion)
                },
            )
            .notify(null)
        context.codexCliVersionService.markNotified(snapshot.latestVersion)
    }

    /** Refreshes runtime validation and version data based on the currently active Runtime tab. */
    private fun refreshRuntimeChecksForCurrentState() {
        when (context.sidePanelStore.state.value.runtimeSettingsTab) {
            RuntimeSettingsTab.CODEX -> {
                detectCodexEnvironment()
                refreshCodexCliVersion(force = false, announceResult = false)
            }
            RuntimeSettingsTab.CLAUDE -> {
                refreshClaudeRuntimeCheck()
                refreshClaudeCliVersion(force = false, announceResult = false)
            }
        }
    }

    /** Aligns the Skills page tab with the current engine before loading that engine's skills. */
    private fun selectSkillsTabForCurrentEngine() {
        val tab = skillsSettingsTabForEngine(context.currentEngineId())
        context.eventHub.publishUiIntent(UiIntent.SelectSkillsSettingsTab(tab))
        loadSkills(forceReload = false, engineIdOverride = tab.engineId)
    }

    /** Aligns the MCP page tab with the current engine before loading that engine's content. */
    private fun selectMcpTabForCurrentEngine() {
        val tab = mcpSettingsTabForEngine(context.currentEngineId())
        context.eventHub.publishUiIntent(UiIntent.SelectMcpSettingsTab(tab))
    }

    /** Aligns the Token Usage page tab with the current engine before loading that engine's content. */
    private fun selectTokenUsageTabForCurrentEngine() {
        val tab = tokenUsageSettingsTabForEngine(context.currentEngineId())
        context.eventHub.publishUiIntent(UiIntent.SelectTokenUsageSettingsTab(tab))
    }

    /** Loads one Token Usage snapshot through the dedicated historical aggregation service. */
    private fun loadTokenUsageStats(
        engineId: String,
        range: TokenUsageRange,
    ) {
        val normalizedEngineId = engineId.trim().ifBlank { TokenUsageSettingsTab.CODEX.engineId }
        val requestScopeKey = tokenUsageScopeKey(
            engineId = normalizedEngineId,
            range = range,
        )
        context.eventHub.publish(
            AppEvent.TokenUsageStatsLoadingChanged(
                loading = true,
                requestScopeKey = requestScopeKey,
            ),
        )
        context.coroutineLauncher.launch("loadTokenUsageStats($normalizedEngineId,$range)") {
            runCatching {
                tokenUsageStatsService.load(
                    sessions = context.chatService.listSessions(),
                    engineId = normalizedEngineId,
                    range = range,
                )
            }.onSuccess { snapshot ->
                context.eventHub.publish(
                    AppEvent.TokenUsageStatsUpdated(
                        snapshot = snapshot,
                        requestScopeKey = requestScopeKey,
                    ),
                )
            }.onFailure { error ->
                context.eventHub.publish(
                    AppEvent.TokenUsageStatsFailed(
                        message = error.message ?: AuraCodeBundle.message("settings.usage.error.load"),
                        requestScopeKey = requestScopeKey,
                    ),
                )
            }
        }
    }

    /** Resolves the engine currently targeted by the Skills settings page. */
    private fun currentSkillsEngineId(): String = context.sidePanelStore.state.value.skillsSettingsTab.engineId

    /** Builds the localized engine label used in Skills import status messages. */
    private fun skillsEngineLabel(engineId: String): String {
        return when (engineId.trim().lowercase()) {
            SkillsSettingsTab.CLAUDE.engineId -> AuraCodeBundle.message("settings.runtime.tab.claude")
            else -> AuraCodeBundle.message("settings.runtime.tab.codex")
        }
    }

    /** Resolves the Claude executable and shared Node path for lightweight inline validation. */
    private fun refreshClaudeRuntimeCheck() {
        val sidePanelState = context.sidePanelStore.state.value
        context.coroutineLauncher.launch("refreshClaudeRuntimeCheck") {
            val result = context.runtimeExecutableCheckService.check(
                commandName = "claude",
                configuredCliPath = sidePanelState.claudeCliPath,
                configuredNodePath = sidePanelState.nodePath,
            )
            context.eventHub.publish(AppEvent.ClaudeRuntimeExecutableCheckUpdated(result, updateDraftPaths = true))
        }
    }

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

    /** Builds the install dialog options for one runtime engine using current package-manager availability. */
    private fun buildRuntimeCliInstallOptions(engineId: String): List<RuntimeCliInstallOption> {
        return runtimePackageManagerDetector.detectAvailability().map { availability ->
            val commandPreview = when (engineId.trim().lowercase()) {
                RuntimeSettingsTab.CLAUDE.engineId -> claudeCliInstallActionFor(availability.packageManager).displayCommand
                else -> codexCliInstallActionFor(availability.packageManager).displayCommand
            }
            RuntimeCliInstallOption(
                packageManagerId = availability.packageManager.id,
                commandPreview = commandPreview,
                available = availability.available,
            )
        }
    }

    private fun updateMcpBusy(transform: McpBusyState.() -> McpBusyState) {
        context.eventHub.publish(
            AppEvent.McpBusyStateUpdated(context.sidePanelStore.state.value.mcpBusyState.transform()),
        )
    }

    private fun mcpAdapter(): McpManagementAdapter {
        val engineId = context.sidePanelStore.state.value.mcpSettingsTab.engineId
        return context.mcpAdapterRegistry.adapterFor(engineId)
    }

    private fun mcpContextSnapshot(requestedName: String? = null): String {
        val state = context.sidePanelStore.state.value
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
