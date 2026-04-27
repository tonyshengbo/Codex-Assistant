package com.auracode.assistant.toolwindow.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.HoverTooltip
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun SessionTabsRegion(
    p: DesignPalette,
    state: SessionTabsAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showNewTabConfirmDialog by remember { mutableStateOf(false) }
    val displayTitle = state.title.trim().ifBlank { AuraCodeBundle.message("header.newChat") }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(AuraCodeBundle.message("header.newDialog.title")) },
            text = { Text(AuraCodeBundle.message("header.newDialog.text")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        onIntent(UiIntent.NewSession)
                    },
                ) {
                    Text(AuraCodeBundle.message("header.newDialog.confirm"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(AuraCodeBundle.message("common.cancel"))
                }
            },
        )
    }

    if (showNewTabConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showNewTabConfirmDialog = false },
            title = { Text(AuraCodeBundle.message("header.newTabDialog.title")) },
            text = { Text(AuraCodeBundle.message("header.newTabDialog.text")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNewTabConfirmDialog = false
                        onIntent(UiIntent.NewTab)
                    },
                ) {
                    Text(AuraCodeBundle.message("header.newTabDialog.confirm"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewTabConfirmDialog = false }) {
                    Text(AuraCodeBundle.message("common.cancel"))
                }
            },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().background(p.topBarBg).padding(horizontal = t.spacing.md, vertical = t.spacing.xs + t.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayTitle,
                color = p.textPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                style = androidx.compose.material.MaterialTheme.typography.h5,
            )
        }
        Spacer(modifier = Modifier.width(t.spacing.xs))
        SessionTabActionChip(
            p = p,
            iconPath = "/icons/add.svg",
            description = AuraCodeBundle.message("header.action.newSession"),
            enabled = state.canCreateNewSession,
        ) {
            showConfirmDialog = true
        }
        Spacer(modifier = Modifier.width(t.spacing.xs))
        SessionTabActionChip(p, "/icons/split.svg", AuraCodeBundle.message("header.action.newTab")) { showNewTabConfirmDialog = true }
        Spacer(modifier = Modifier.width(t.spacing.xs))
        SessionTabActionChip(p, "/icons/history.svg", AuraCodeBundle.message("header.action.history")) { onIntent(UiIntent.ToggleHistory) }
        Spacer(modifier = Modifier.width(t.spacing.xs))
        SessionTabActionChip(p, "/icons/settings.svg", AuraCodeBundle.message("header.action.settings")) { onIntent(UiIntent.ToggleSettings) }
    }
}

@Composable
internal fun SessionTabActionChip(
    p: DesignPalette,
    iconPath: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    HoverTooltip(text = description) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .clip(RoundedCornerShape(t.spacing.xs))
                .clickable(enabled = enabled, onClick = onClick)
                .size(t.controls.headerActionTouch)
                .padding(t.spacing.xs),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconPath),
                contentDescription = description,
                tint = if (enabled) p.textSecondary else p.textMuted,
                modifier = Modifier.size(t.controls.iconLg),
            )
        }
    }
}
