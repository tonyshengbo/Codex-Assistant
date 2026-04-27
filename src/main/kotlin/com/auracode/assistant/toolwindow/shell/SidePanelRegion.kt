package com.auracode.assistant.toolwindow.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.auracode.assistant.toolwindow.history.HistoryOverlay
import com.auracode.assistant.toolwindow.settings.SettingsOverlay
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun SidePanelRegion(
    p: DesignPalette,
    state: SidePanelAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val shape = RoundedCornerShape(t.spacing.lg)
    val isFullBleed = state.kind == SidePanelKind.SETTINGS || state.kind == SidePanelKind.HISTORY
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.appBg, if (isFullBleed) RoundedCornerShape(0.dp) else shape)
            .then(
                if (isFullBleed) {
                    Modifier
                } else {
                    Modifier.border(1.dp, p.markdownDivider.copy(alpha = 0.72f), shape)
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = p.appBg,
            contentColor = p.textPrimary,
            elevation = 0.dp,
        ) {
            when (state.kind) {
                SidePanelKind.HISTORY -> HistoryOverlay(p = p, state = state, onIntent = onIntent)
                SidePanelKind.SETTINGS -> SettingsOverlay(p = p, state = state, onIntent = onIntent)
                SidePanelKind.NONE -> Unit
            }
        }
    }
}
