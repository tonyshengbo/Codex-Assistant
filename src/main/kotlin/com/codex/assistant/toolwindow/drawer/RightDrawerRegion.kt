package com.codex.assistant.toolwindow.drawer

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
import com.codex.assistant.toolwindow.drawer.settings.SettingsOverlay
import com.codex.assistant.toolwindow.eventing.UiIntent
import com.codex.assistant.toolwindow.shared.DesignPalette
import com.codex.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun RightDrawerRegion(
    p: DesignPalette,
    state: RightDrawerAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val shape = RoundedCornerShape(t.spacing.lg)
    val isSettings = state.kind == RightDrawerKind.SETTINGS
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.appBg, if (isSettings) RoundedCornerShape(0.dp) else shape)
            .then(
                if (isSettings) {
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
                RightDrawerKind.HISTORY -> HistoryOverlay(p = p, state = state, onIntent = onIntent)
                RightDrawerKind.SETTINGS -> SettingsOverlay(p = p, state = state, onIntent = onIntent)
                RightDrawerKind.NONE -> Unit
            }
        }
    }
}
