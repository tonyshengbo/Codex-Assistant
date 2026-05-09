package com.auracode.assistant.toolwindow.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

/**
 * Renders one lightweight divider-style turn duration summary inside the timeline.
 */
@Composable
internal fun ConversationTurnDurationDivider(
    clockText: String,
    durationText: String,
    palette: DesignPalette,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = t.spacing.xs, vertical = t.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(t.spacing.xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimelineTurnDurationLine(
            color = palette.markdownDivider.copy(alpha = 0.26f),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = buildAnnotatedString {
                append(clockText)
                append(" · ")
                withStyle(
                    SpanStyle(
                        color = palette.textPrimary.copy(alpha = 0.94f),
                        fontWeight = FontWeight.SemiBold,
                    ),
                ) {
                    append(durationText)
                }
            },
            color = palette.textSecondary.copy(alpha = 0.88f),
            style = MaterialTheme.typography.caption,
        )
        TimelineTurnDurationLine(
            color = palette.markdownDivider.copy(alpha = 0.26f),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Renders one subtle horizontal line used by the turn duration divider.
 */
@Composable
private fun TimelineTurnDurationLine(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(1.dp)
            .background(color),
    )
}
