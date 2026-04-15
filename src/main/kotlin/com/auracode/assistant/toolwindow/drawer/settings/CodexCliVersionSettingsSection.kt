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
import com.auracode.assistant.provider.codex.CodexCliVersionCheckStatus
import com.auracode.assistant.provider.codex.CodexCliVersionSnapshot
import com.auracode.assistant.toolwindow.drawer.RightDrawerAreaState
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun CodexCliVersionSettingsSection(
    p: DesignPalette,
    state: RightDrawerAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val model = buildCodexCliVersionPanelModel(
        snapshot = state.codexCliVersionSnapshot,
        autoCheckEnabled = state.codexCliAutoUpdateCheckEnabled,
    )
    val t = assistantUiTokens()
    SettingsField(
        p = p,
        title = AuraCodeBundle.message("settings.codexVersion.label"),
        description = AuraCodeBundle.message("settings.codexVersion.hint"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(t.spacing.md)) {
            VersionRow(
                p = p,
                title = AuraCodeBundle.message("settings.codexVersion.current"),
                value = model.currentVersionText,
            )
            model.latestVersionText?.let {
                VersionRow(
                    p = p,
                    title = AuraCodeBundle.message("settings.codexVersion.latest"),
                    value = it,
                )
            }
            model.statusText?.let {
                VersionRow(
                    p = p,
                    title = AuraCodeBundle.message("settings.codexVersion.status.label"),
                    value = it,
                )
            }
            if (model.showAutoCheckToggle) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = AuraCodeBundle.message("settings.codexVersion.autoCheck"),
                        color = p.textPrimary,
                        style = MaterialTheme.typography.body2,
                    )
                    SettingsToggle(
                        p = p,
                        checked = model.autoCheckEnabled,
                        onCheckedChange = { onIntent(UiIntent.EditSettingsCodexCliAutoUpdateCheckEnabled(it)) },
                    )
                }
            }
            if (model.showManualUpgradeHint) {
                Text(
                    text = AuraCodeBundle.message("settings.codexVersion.manual"),
                    color = p.textMuted,
                    style = MaterialTheme.typography.body2,
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
                            when (model.primaryActionIntent) {
                                UiIntent.CheckCodexCliVersion -> onIntent(UiIntent.CheckCodexCliVersion)
                                UiIntent.UpgradeCodexCli -> onIntent(UiIntent.UpgradeCodexCli)
                                else -> Unit
                            }
                        },
                    )
                }
            }
        }
    }
}

internal data class CodexCliVersionPanelModel(
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
    val showStatusBadge: Boolean,
    val showAutoCheckToggle: Boolean,
    val autoCheckEnabled: Boolean,
    val isBusy: Boolean,
)

internal fun buildCodexCliVersionPanelModel(
    snapshot: CodexCliVersionSnapshot,
    autoCheckEnabled: Boolean = true,
): CodexCliVersionPanelModel {
    val currentVersionText = snapshot.currentVersion.ifBlank {
        AuraCodeBundle.message("settings.codexVersion.unknown")
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
        snapshot.checkStatus == CodexCliVersionCheckStatus.CHECKING -> AuraCodeBundle.message("settings.codexVersion.action.checking")
        snapshot.checkStatus == CodexCliVersionCheckStatus.UPGRADE_IN_PROGRESS -> AuraCodeBundle.message("settings.codexVersion.action.upgrading")
        showUpgradeAction -> AuraCodeBundle.message("settings.codexVersion.upgrade")
        snapshot.checkStatus == CodexCliVersionCheckStatus.UP_TO_DATE ||
            snapshot.checkStatus == CodexCliVersionCheckStatus.UPGRADE_SUCCEEDED -> AuraCodeBundle.message("settings.codexVersion.action.upToDate")
        else -> AuraCodeBundle.message("settings.codexVersion.check")
    }
    val primaryActionIntent = when {
        snapshot.checkStatus == CodexCliVersionCheckStatus.CHECKING -> null
        snapshot.checkStatus == CodexCliVersionCheckStatus.UPGRADE_IN_PROGRESS -> null
        showUpgradeAction -> UiIntent.UpgradeCodexCli
        else -> UiIntent.CheckCodexCliVersion
    }
    return CodexCliVersionPanelModel(
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
        showStatusBadge = false,
        showAutoCheckToggle = true,
        autoCheckEnabled = autoCheckEnabled,
        isBusy = isBusy,
    )
}

private fun codexCliVersionStatusText(snapshot: CodexCliVersionSnapshot): String? {
    return when (snapshot.checkStatus) {
        CodexCliVersionCheckStatus.IDLE -> snapshot.message.ifBlank { null }
        CodexCliVersionCheckStatus.CHECKING -> AuraCodeBundle.message("settings.codexVersion.status.checking")
        CodexCliVersionCheckStatus.UP_TO_DATE -> AuraCodeBundle.message("settings.codexVersion.status.upToDate")
        CodexCliVersionCheckStatus.UPDATE_AVAILABLE -> when {
            snapshot.ignoredVersion.isNotBlank() && snapshot.ignoredVersion == snapshot.latestVersion ->
                AuraCodeBundle.message("settings.codexVersion.status.ignored", snapshot.latestVersion)
            else -> AuraCodeBundle.message("settings.codexVersion.status.updateAvailable")
        }
        CodexCliVersionCheckStatus.LOCAL_VERSION_UNAVAILABLE -> AuraCodeBundle.message("settings.codexVersion.status.localUnavailable")
        CodexCliVersionCheckStatus.REMOTE_CHECK_FAILED -> AuraCodeBundle.message("settings.codexVersion.status.remoteFailed")
        CodexCliVersionCheckStatus.UPGRADE_IN_PROGRESS -> AuraCodeBundle.message("settings.codexVersion.status.upgrading")
        CodexCliVersionCheckStatus.UPGRADE_SUCCEEDED -> AuraCodeBundle.message("settings.codexVersion.status.upgradeSucceeded")
        CodexCliVersionCheckStatus.UPGRADE_FAILED -> snapshot.message.ifBlank {
            AuraCodeBundle.message("settings.codexVersion.status.upgradeFailed")
        }
    }
}

@Composable
private fun VersionRow(
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
