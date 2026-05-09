package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import kotlin.math.min

/**
 * Renders one responsive row header that keeps the primary value readable across narrow widths.
 */
@Composable
internal fun TokenUsageResponsiveHeader(
    p: DesignPalette,
    title: String,
    primaryLabel: String,
    primaryValue: String,
) {
    val t = assistantUiTokens()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        val stacked = shouldStackTokenUsageHeader(availableWidth)
        if (stacked) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
            ) {
                TokenUsageHeaderTitle(
                    p = p,
                    title = title,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                TokenUsageHeaderPrimaryValue(
                    p = p,
                    primaryLabel = primaryLabel,
                    primaryValue = primaryValue,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
            ) {
                TokenUsageHeaderTitle(
                    p = p,
                    title = title,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                TokenUsageHeaderPrimaryValue(
                    p = p,
                    primaryLabel = primaryLabel,
                    primaryValue = primaryValue,
                    modifier = Modifier.widthIn(max = availableWidth * 0.42f),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/**
 * Renders one responsive secondary metrics grid for Token Usage list rows.
 */
@Composable
internal fun TokenUsageSecondaryMetricsGrid(
    p: DesignPalette,
    items: List<Pair<String, String>>,
) {
    if (items.isEmpty()) return
    val t = assistantUiTokens()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        val columns = tokenUsageSecondaryMetricColumns(
            maxWidth = availableWidth,
            itemCount = items.size,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
        ) {
            items.chunked(columns).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    rowItems.forEach { (label, value) ->
                        TokenUsageSecondaryMetricItem(
                            p = p,
                            label = label,
                            value = value,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Renders one primary title cell inside a responsive Token Usage header.
 */
@Composable
private fun TokenUsageHeaderTitle(
    p: DesignPalette,
    title: String,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        color = p.textPrimary,
        style = MaterialTheme.typography.body2,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Renders one accent-colored primary value inside a responsive Token Usage header.
 */
@Composable
private fun TokenUsageHeaderPrimaryValue(
    p: DesignPalette,
    primaryLabel: String,
    primaryValue: String,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "$primaryLabel: $primaryValue",
        color = p.accent,
        style = MaterialTheme.typography.body2,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

/**
 * Renders one secondary key-value metric with stable wrapping semantics.
 */
@Composable
private fun TokenUsageSecondaryMetricItem(
    p: DesignPalette,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            color = p.textMuted,
            style = MaterialTheme.typography.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            color = p.textSecondary,
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Resolves whether the Token Usage header should switch to a stacked layout.
 */
private fun shouldStackTokenUsageHeader(maxWidth: Dp): Boolean {
    return maxWidth < 420.dp
}

/**
 * Resolves how many secondary metric columns fit into the current width.
 */
private fun tokenUsageSecondaryMetricColumns(
    maxWidth: Dp,
    itemCount: Int,
): Int {
    if (itemCount <= 1) return 1
    if (maxWidth < 360.dp) return 1
    return min(2, itemCount)
}
