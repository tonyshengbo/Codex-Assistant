package com.auracode.assistant.toolwindow.submission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

/**
 * Shows the expanded per-agent details inline so tray users can inspect progress without leaving the composer.
 */
@Composable
internal fun ComposerSubagentDetailCard(
    p: DesignPalette,
    subagent: SessionSubagentUiModel,
) {
    val t = assistantUiTokens()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.composerBg.copy(alpha = 0.96f), RoundedCornerShape(t.spacing.sm))
            .border(1.dp, subagentStatusColor(status = subagent.status, p = p).copy(alpha = 0.22f), RoundedCornerShape(t.spacing.sm))
            .padding(horizontal = t.spacing.sm, vertical = t.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
    ) {
        Text(
            text = subagent.summary?.takeIf { it.isNotBlank() } ?: subagent.displayName,
            color = p.textPrimary,
            style = androidx.compose.material.MaterialTheme.typography.body2.copy(fontWeight = FontWeight.Medium),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(t.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = localizedSubagentState(subagent.status),
                color = subagentStatusColor(status = subagent.status, p = p),
                style = androidx.compose.material.MaterialTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
            )
            subagent.statusText
                .takeIf { it.isNotBlank() }
                ?.let { rawStatus ->
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = rawStatus,
                        color = p.textSecondary,
                        style = androidx.compose.material.MaterialTheme.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
        }
        Text(
            text = subagent.threadId,
            color = p.textMuted,
            style = androidx.compose.material.MaterialTheme.typography.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
