package com.auracode.assistant.toolwindow.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Describes one action rendered in the shared dialog footer. */
internal data class AssistantDialogAction(
    val label: String,
    val emphasized: Boolean,
    val tone: AssistantDialogTone = AssistantDialogTone.NEUTRAL,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** Renders the shared modal shell used across Aura dialogs. */
@Composable
internal fun AssistantDialogFrame(
    palette: DesignPalette,
    onDismissRequest: (() -> Unit)?,
    modifier: Modifier = Modifier,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { onDismissRequest?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = dismissOnBackPress && onDismissRequest != null,
            dismissOnClickOutside = dismissOnClickOutside && onDismissRequest != null,
            usePlatformDefaultWidth = false,
        ),
    ) {
        AssistantDialogSurface(
            palette = palette,
            modifier = modifier,
            content = content,
        )
    }
}

/** Draws the shared elevated panel surface used by compact and editor dialogs. */
@Composable
internal fun AssistantDialogSurface(
    palette: DesignPalette,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = assistantUiTokens()
    val shape = RoundedCornerShape(t.spacing.lg)
    Column(
        modifier = modifier
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        palette.topBarBg.copy(alpha = 0.98f),
                        palette.appBg.copy(alpha = 0.98f),
                    ),
                ),
                shape = shape,
            )
            .border(1.dp, palette.markdownDivider.copy(alpha = 0.42f), shape)
            .padding(horizontal = t.spacing.lg, vertical = t.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
        content = content,
    )
}

/** Renders a compact message or confirmation dialog using the shared Aura chrome. */
@Composable
internal fun AssistantMessageDialog(
    palette: DesignPalette,
    title: String,
    message: String,
    presentation: AssistantDialogStatePresentation,
    confirmAction: AssistantDialogAction? = null,
    dismissAction: AssistantDialogAction? = null,
    onDismissRequest: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    AlertDialog(
        onDismissRequest = {
            if (presentation.allowsDismiss) {
                onDismissRequest?.invoke()
            }
        },
        modifier = Modifier.widthIn(min = 360.dp, max = 460.dp),
        shape = shape,
        backgroundColor = palette.timelineCardBg,
        title = {
            Text(
                text = title,
                color = palette.textPrimary,
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            AssistantDialogBody(
                palette = palette,
                message = message,
                presentation = presentation,
            )
        },
        confirmButton = {
            confirmAction?.let { action ->
                AssistantDialogTextButton(
                    palette = palette,
                    action = action,
                )
            }
        },
        dismissButton = {
            dismissAction?.let { action ->
                AssistantDialogTextButton(
                    palette = palette,
                    action = action,
                )
            }
        },
    )
}

/** Renders the message body using the compact system dialog structure. */
@Composable
private fun AssistantDialogBody(
    palette: DesignPalette,
    message: String,
    presentation: AssistantDialogStatePresentation,
) {
    if (presentation.showsProgressIndicator) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = palette.accent,
                strokeWidth = 2.dp,
            )
            Text(
                text = message,
                color = palette.textSecondary,
                style = MaterialTheme.typography.body2,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        return
    }
    Text(
        text = message,
        color = palette.textSecondary,
        style = MaterialTheme.typography.body2,
    )
}

/** Renders one system-style action button with Aura semantic colors. */
@Composable
private fun AssistantDialogTextButton(
    palette: DesignPalette,
    action: AssistantDialogAction,
) {
    TextButton(
        onClick = action.onClick,
        enabled = action.enabled,
    ) {
        Text(
            text = action.label,
            color = assistantDialogActionColor(
                palette = palette,
                action = action,
            ),
            style = MaterialTheme.typography.button,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Resolves the button text color for the themed system dialog actions. */
private fun assistantDialogActionColor(
    palette: DesignPalette,
    action: AssistantDialogAction,
): androidx.compose.ui.graphics.Color {
    if (!action.enabled) {
        return palette.textMuted
    }
    return when (action.tone) {
        AssistantDialogTone.DANGER -> palette.danger
        AssistantDialogTone.ACCENT -> palette.accent
        AssistantDialogTone.NEUTRAL -> if (action.emphasized) palette.accent else palette.textSecondary
    }
}
