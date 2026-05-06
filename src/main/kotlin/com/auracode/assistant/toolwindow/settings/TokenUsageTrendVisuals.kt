package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

/**
 * Renders one daily trend row with a lightweight relative-strength bar.
 */
@Composable
internal fun TokenUsageTrendRow(
    p: DesignPalette,
    dayLabel: String,
    totalLabel: String,
    totalValue: String,
    totalTokens: Long,
    maxTotalTokens: Long,
    secondary: List<Pair<String, String>>,
) {
    val t = assistantUiTokens()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
        ) {
            Text(
                text = dayLabel,
                color = p.textPrimary,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$totalLabel: $totalValue",
                color = p.accent,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        TokenUsageTrendBar(
            p = p,
            totalTokens = totalTokens,
            maxTotalTokens = maxTotalTokens,
        )
        Text(
            text = secondary.joinToString("   ") { (label, value) -> "$label: $value" },
            color = p.textSecondary,
            style = MaterialTheme.typography.caption,
        )
    }
}

/**
 * Renders one low-emphasis relative-length bar for daily trend comparison.
 */
@Composable
private fun TokenUsageTrendBar(
    p: DesignPalette,
    totalTokens: Long,
    maxTotalTokens: Long,
) {
    val fraction = when {
        totalTokens <= 0L || maxTotalTokens <= 0L -> 0f
        else -> (totalTokens.toFloat() / maxTotalTokens.toFloat()).coerceIn(0f, 1f)
    }
    val visibleFraction = when {
        fraction <= 0f -> 0f
        fraction < 0.08f -> 0.08f
        else -> fraction
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(p.markdownDivider.copy(alpha = 0.18f)),
    ) {
        if (visibleFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(visibleFraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(p.accent.copy(alpha = 0.28f)),
            )
        }
    }
}
