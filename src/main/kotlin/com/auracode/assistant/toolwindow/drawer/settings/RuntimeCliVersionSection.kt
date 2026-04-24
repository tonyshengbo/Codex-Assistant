package com.auracode.assistant.toolwindow.drawer.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.provider.claude.ClaudeCliVersionCheckStatus
import com.auracode.assistant.provider.claude.ClaudeCliVersionSnapshot
import com.auracode.assistant.provider.codex.CodexCliVersionCheckStatus
import com.auracode.assistant.provider.codex.CodexCliVersionSnapshot
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

/** Describes the shared runtime version card consumed by Codex and Claude tabs. */
internal data class RuntimeCliVersionPanelModel(
    val sectionTitle: String,
    val sectionHint: String,
    val currentVersionText: String,
    val latestVersionText: String?,
    val statusText: String?,
    val displayCommand: String,
    val showPrimaryAction: Boolean,
    val primaryActionLabel: String,
    val primaryActionIntent: UiIntent?,
    val isPrimaryActionEmphasized: Boolean,
    val showManualUpgradeHint: Boolean,
    val showVersionCommand: Boolean,
    val isBusy: Boolean,
)

/** Renders the shared version card layout for the active runtime tab. */
@Composable
internal fun RuntimeCliVersionSection(
    p: DesignPalette,
    model: RuntimeCliVersionPanelModel,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    SettingsField(
        p = p,
        title = model.sectionTitle,
        description = model.sectionHint,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(t.spacing.md)) {
            RuntimeVersionRow(
                p = p,
                title = AuraCodeBundle.message("settings.runtime.version.current"),
                value = model.currentVersionText,
            )
            model.latestVersionText?.let {
                RuntimeVersionRow(
                    p = p,
                    title = AuraCodeBundle.message("settings.runtime.version.latest"),
                    value = it,
                )
            }
            model.statusText?.let {
                RuntimeVersionRow(
                    p = p,
                    title = AuraCodeBundle.message("settings.runtime.version.status"),
                    value = it,
                )
            }
            if (model.showManualUpgradeHint) {
                SettingsInlineMessage(
                    p = p,
                    text = AuraCodeBundle.message("settings.runtime.version.manual"),
                )
            }
            if (model.showVersionCommand) {
                Text(
                    text = model.displayCommand,
                    color = p.textSecondary,
                    style = MaterialTheme.typography.body2,
                )
            }
            if (model.showPrimaryAction) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsActionButton(
                        p = p,
                        text = model.primaryActionLabel,
                        emphasized = model.isPrimaryActionEmphasized,
                        enabled = !model.isBusy,
                        onClick = {
                            model.primaryActionIntent?.let(onIntent)
                        },
                    )
                }
            }
        }
    }
}

/** Builds the shared version-card model for the Codex runtime tab. */
internal fun buildCodexRuntimeCliVersionPanelModel(
    snapshot: CodexCliVersionSnapshot,
): RuntimeCliVersionPanelModel {
    val currentVersionText = snapshot.currentVersion.ifBlank {
        AuraCodeBundle.message("settings.runtime.version.unknown")
    }
    val latestVersionText = snapshot.latestVersion.trim().ifBlank { null }
    val isBusy = snapshot.checkStatus == CodexCliVersionCheckStatus.CHECKING ||
        snapshot.checkStatus == CodexCliVersionCheckStatus.UPGRADE_IN_PROGRESS
    val showUpgradeAction = snapshot.checkStatus == CodexCliVersionCheckStatus.UPDATE_AVAILABLE &&
        snapshot.isUpgradeSupported
    val showManualUpgradeHint = snapshot.checkStatus == CodexCliVersionCheckStatus.UPDATE_AVAILABLE &&
        !snapshot.isUpgradeSupported
    val showVersionCommand = showManualUpgradeHint && snapshot.displayCommand.isNotBlank()
    val primaryActionLabel = when {
        snapshot.checkStatus == CodexCliVersionCheckStatus.CHECKING -> AuraCodeBundle.message("settings.runtime.version.action.checking")
        snapshot.checkStatus == CodexCliVersionCheckStatus.UPGRADE_IN_PROGRESS -> AuraCodeBundle.message("settings.runtime.version.action.upgrading")
        showUpgradeAction -> AuraCodeBundle.message("settings.runtime.version.action.upgrade")
        snapshot.checkStatus == CodexCliVersionCheckStatus.UP_TO_DATE ||
            snapshot.checkStatus == CodexCliVersionCheckStatus.UPGRADE_SUCCEEDED -> AuraCodeBundle.message("settings.runtime.version.action.upToDate")
        else -> AuraCodeBundle.message("settings.runtime.version.action.check")
    }
    val primaryActionIntent = when {
        snapshot.checkStatus == CodexCliVersionCheckStatus.CHECKING -> null
        snapshot.checkStatus == CodexCliVersionCheckStatus.UPGRADE_IN_PROGRESS -> null
        showUpgradeAction -> UiIntent.UpgradeCodexCli
        else -> UiIntent.CheckCodexCliVersion
    }
    return RuntimeCliVersionPanelModel(
        sectionTitle = AuraCodeBundle.message("settings.runtime.codexVersion.label"),
        sectionHint = AuraCodeBundle.message("settings.runtime.version.hint"),
        currentVersionText = currentVersionText,
        latestVersionText = latestVersionText,
        statusText = codexCliVersionStatusText(snapshot),
        displayCommand = snapshot.displayCommand,
        showPrimaryAction = true,
        primaryActionLabel = primaryActionLabel,
        primaryActionIntent = primaryActionIntent,
        isPrimaryActionEmphasized = showUpgradeAction,
        showManualUpgradeHint = showManualUpgradeHint,
        showVersionCommand = showVersionCommand,
        isBusy = isBusy,
    )
}

/** Builds the shared version-card model for the Claude runtime tab. */
internal fun buildClaudeRuntimeCliVersionPanelModel(
    snapshot: ClaudeCliVersionSnapshot,
): RuntimeCliVersionPanelModel {
    val currentVersionText = snapshot.currentVersion.ifBlank {
        AuraCodeBundle.message("settings.runtime.version.unknown")
    }
    val latestVersionText = snapshot.latestVersion.trim().ifBlank { null }
    val isBusy = snapshot.checkStatus == ClaudeCliVersionCheckStatus.CHECKING ||
        snapshot.checkStatus == ClaudeCliVersionCheckStatus.UPGRADE_IN_PROGRESS
    val showUpgradeAction = snapshot.checkStatus == ClaudeCliVersionCheckStatus.UPDATE_AVAILABLE &&
        snapshot.isUpgradeSupported
    val showManualUpgradeHint = snapshot.checkStatus == ClaudeCliVersionCheckStatus.UPDATE_AVAILABLE &&
        !snapshot.isUpgradeSupported
    val showVersionCommand = showManualUpgradeHint && snapshot.displayCommand.isNotBlank()
    val primaryActionLabel = when {
        snapshot.checkStatus == ClaudeCliVersionCheckStatus.CHECKING -> AuraCodeBundle.message("settings.runtime.version.action.checking")
        snapshot.checkStatus == ClaudeCliVersionCheckStatus.UPGRADE_IN_PROGRESS -> AuraCodeBundle.message("settings.runtime.version.action.upgrading")
        showUpgradeAction -> AuraCodeBundle.message("settings.runtime.version.action.upgrade")
        snapshot.checkStatus == ClaudeCliVersionCheckStatus.UP_TO_DATE ||
            snapshot.checkStatus == ClaudeCliVersionCheckStatus.UPGRADE_SUCCEEDED -> AuraCodeBundle.message("settings.runtime.version.action.upToDate")
        else -> AuraCodeBundle.message("settings.runtime.version.action.check")
    }
    val primaryActionIntent = when {
        snapshot.checkStatus == ClaudeCliVersionCheckStatus.CHECKING -> null
        snapshot.checkStatus == ClaudeCliVersionCheckStatus.UPGRADE_IN_PROGRESS -> null
        showUpgradeAction -> UiIntent.UpgradeClaudeCli
        else -> UiIntent.CheckClaudeCliVersion
    }
    return RuntimeCliVersionPanelModel(
        sectionTitle = AuraCodeBundle.message("settings.runtime.claudeVersion.label"),
        sectionHint = AuraCodeBundle.message("settings.runtime.version.hint"),
        currentVersionText = currentVersionText,
        latestVersionText = latestVersionText,
        statusText = claudeCliVersionStatusText(snapshot),
        displayCommand = snapshot.displayCommand,
        showPrimaryAction = true,
        primaryActionLabel = primaryActionLabel,
        primaryActionIntent = primaryActionIntent,
        isPrimaryActionEmphasized = showUpgradeAction,
        showManualUpgradeHint = showManualUpgradeHint,
        showVersionCommand = showVersionCommand,
        isBusy = isBusy,
    )
}

/** Maps the latest Codex CLI version snapshot into localized runtime status text. */
private fun codexCliVersionStatusText(snapshot: CodexCliVersionSnapshot): String? {
    return when (snapshot.checkStatus) {
        CodexCliVersionCheckStatus.IDLE -> snapshot.message.ifBlank { null }
        CodexCliVersionCheckStatus.CHECKING -> AuraCodeBundle.message("settings.runtime.version.status.checking")
        CodexCliVersionCheckStatus.UP_TO_DATE -> AuraCodeBundle.message("settings.runtime.version.status.upToDate")
        CodexCliVersionCheckStatus.UPDATE_AVAILABLE -> when {
            snapshot.ignoredVersion.isNotBlank() && snapshot.ignoredVersion == snapshot.latestVersion ->
                AuraCodeBundle.message("settings.runtime.version.status.ignored", snapshot.latestVersion)
            else -> AuraCodeBundle.message("settings.runtime.version.status.updateAvailable")
        }
        CodexCliVersionCheckStatus.LOCAL_VERSION_UNAVAILABLE -> AuraCodeBundle.message("settings.runtime.version.status.localUnavailable")
        CodexCliVersionCheckStatus.REMOTE_CHECK_FAILED -> AuraCodeBundle.message("settings.runtime.version.status.remoteFailed")
        CodexCliVersionCheckStatus.UPGRADE_IN_PROGRESS -> AuraCodeBundle.message("settings.runtime.version.status.upgrading")
        CodexCliVersionCheckStatus.UPGRADE_SUCCEEDED -> AuraCodeBundle.message("settings.runtime.version.status.upgradeSucceeded")
        CodexCliVersionCheckStatus.UPGRADE_FAILED -> snapshot.message.ifBlank {
            AuraCodeBundle.message("settings.runtime.version.status.upgradeFailed")
        }
    }
}

/** Maps the latest Claude CLI version snapshot into localized runtime status text. */
private fun claudeCliVersionStatusText(snapshot: ClaudeCliVersionSnapshot): String? {
    return when (snapshot.checkStatus) {
        ClaudeCliVersionCheckStatus.IDLE -> snapshot.message.ifBlank { null }
        ClaudeCliVersionCheckStatus.CHECKING -> AuraCodeBundle.message("settings.runtime.version.status.checking")
        ClaudeCliVersionCheckStatus.UP_TO_DATE -> AuraCodeBundle.message("settings.runtime.version.status.upToDate")
        ClaudeCliVersionCheckStatus.UPDATE_AVAILABLE -> when {
            snapshot.ignoredVersion.isNotBlank() && snapshot.ignoredVersion == snapshot.latestVersion ->
                AuraCodeBundle.message("settings.runtime.version.status.ignored", snapshot.latestVersion)
            else -> AuraCodeBundle.message("settings.runtime.version.status.updateAvailable")
        }
        ClaudeCliVersionCheckStatus.LOCAL_VERSION_UNAVAILABLE -> AuraCodeBundle.message("settings.runtime.version.status.localUnavailable")
        ClaudeCliVersionCheckStatus.REMOTE_CHECK_FAILED -> AuraCodeBundle.message("settings.runtime.version.status.remoteFailed")
        ClaudeCliVersionCheckStatus.UPGRADE_IN_PROGRESS -> AuraCodeBundle.message("settings.runtime.version.status.upgrading")
        ClaudeCliVersionCheckStatus.UPGRADE_SUCCEEDED -> AuraCodeBundle.message("settings.runtime.version.status.upgradeSucceeded")
        ClaudeCliVersionCheckStatus.UPGRADE_FAILED -> snapshot.message.ifBlank {
            AuraCodeBundle.message("settings.runtime.version.status.upgradeFailed")
        }
    }
}

/** Draws one key-value row inside the shared runtime version card. */
@Composable
private fun RuntimeVersionRow(
    p: DesignPalette,
    title: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = p.textPrimary,
            style = MaterialTheme.typography.body2,
        )
        Text(
            text = value,
            color = p.textSecondary,
            style = MaterialTheme.typography.body2,
        )
    }
}
