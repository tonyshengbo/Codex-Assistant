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
import androidx.compose.material.Icon
import androidx.compose.material.Text
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
import com.auracode.assistant.toolwindow.shared.AssistantDialogAction
import com.auracode.assistant.toolwindow.shared.AssistantDialogStatePresentation
import com.auracode.assistant.toolwindow.shared.AssistantDialogTone
import com.auracode.assistant.toolwindow.shared.AssistantMessageDialog
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
        AssistantMessageDialog(
            palette = p,
            title = AuraCodeBundle.message("header.newDialog.title"),
            message = AuraCodeBundle.message("header.newDialog.text"),
            presentation = AssistantDialogStatePresentation(
                tone = AssistantDialogTone.NEUTRAL,
                showsProgressIndicator = false,
                showsStatusBadge = false,
                allowsDismiss = true,
            ),
            confirmAction = AssistantDialogAction(
                label = AuraCodeBundle.message("header.newDialog.confirm"),
                emphasized = true,
                tone = AssistantDialogTone.ACCENT,
                onClick = {
                    showConfirmDialog = false
                    onIntent(UiIntent.NewSession)
                },
            ),
            dismissAction = AssistantDialogAction(
                label = AuraCodeBundle.message("common.cancel"),
                emphasized = false,
                onClick = { showConfirmDialog = false },
            ),
            onDismissRequest = { showConfirmDialog = false },
        )
    }

    if (showNewTabConfirmDialog) {
        AssistantMessageDialog(
            palette = p,
            title = AuraCodeBundle.message("header.newTabDialog.title"),
            message = AuraCodeBundle.message("header.newTabDialog.text"),
            presentation = AssistantDialogStatePresentation(
                tone = AssistantDialogTone.NEUTRAL,
                showsProgressIndicator = false,
                showsStatusBadge = false,
                allowsDismiss = true,
            ),
            confirmAction = AssistantDialogAction(
                label = AuraCodeBundle.message("header.newTabDialog.confirm"),
                emphasized = true,
                tone = AssistantDialogTone.ACCENT,
                onClick = {
                    showNewTabConfirmDialog = false
                    onIntent(UiIntent.NewTab)
                },
            ),
            dismissAction = AssistantDialogAction(
                label = AuraCodeBundle.message("common.cancel"),
                emphasized = false,
                onClick = { showNewTabConfirmDialog = false },
            ),
            onDismissRequest = { showNewTabConfirmDialog = false },
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
            iconPath = "/icons/refresh.svg",
            description = AuraCodeBundle.message("header.action.newSession"),
            enabled = state.canCreateNewSession,
        ) {
            showConfirmDialog = true
        }
        Spacer(modifier = Modifier.width(t.spacing.xs))
        SessionTabActionChip(p, "/icons/add.svg", AuraCodeBundle.message("header.action.newTab")) { showNewTabConfirmDialog = true }
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
