package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders the explanatory note that clarifies the layered historical semantics.
 */
@Composable
internal fun TokenUsageInfoBanner(
    p: DesignPalette,
    text: String,
) {
    TokenUsageSectionCard(p = p) {
        Text(
            text = text,
            color = p.textMuted,
            style = MaterialTheme.typography.body2,
        )
    }
}

/** Renders the historical overview section for the selected engine and range. */
@Composable
internal fun TokenUsageHistoricalOverviewSection(
    p: DesignPalette,
    snapshot: TokenUsageStatsSnapshot?,
) {
    TokenUsageSection(
        p = p,
        title = AuraCodeBundle.message("settings.usage.overview.title"),
    ) {
        if (snapshot == null || !snapshot.hasHistoricalData) {
            SettingsInlineMessage(p = p, text = AuraCodeBundle.message("settings.usage.overview.empty"))
            return@TokenUsageSection
        }
        val overview = snapshot.overview
        TokenUsageSectionCard(p = p) {
            TokenUsageKeyValueGrid(
                p = p,
                items = listOf(
                    AuraCodeBundle.message("settings.usage.label.input") to formatCount(overview.inputTokens),
                    AuraCodeBundle.message("settings.usage.label.cached") to formatCount(overview.cachedInputTokens),
                    AuraCodeBundle.message("settings.usage.label.output") to formatCount(overview.outputTokens),
                    AuraCodeBundle.message("settings.usage.label.total") to formatCount(overview.totalTokens),
                    AuraCodeBundle.message("settings.usage.label.sessions") to formatCount(overview.coveredSessionCount.toLong()),
                    AuraCodeBundle.message("settings.usage.label.days") to formatCount(overview.coveredDayCount.toLong()),
                ),
            )
            if (overview.includesLegacyFallback) {
                Spacer(Modifier.height(assistantUiTokens().spacing.sm))
                SettingsInlineMessage(
                    p = p,
                    text = AuraCodeBundle.message("settings.usage.overview.legacy"),
                )
            }
        }
    }
}

/** Renders the model breakdown section for the selected engine and range. */
@Composable
internal fun TokenUsageModelBreakdownSection(
    p: DesignPalette,
    snapshot: TokenUsageStatsSnapshot?,
) {
    TokenUsageSection(
        p = p,
        title = AuraCodeBundle.message("settings.usage.models.title"),
    ) {
        val breakdowns = snapshot?.modelBreakdowns.orEmpty()
        if (breakdowns.isEmpty()) {
            SettingsInlineMessage(p = p, text = AuraCodeBundle.message("settings.usage.models.empty"))
            return@TokenUsageSection
        }
        TokenUsageSectionCard(p = p) {
            breakdowns.forEachIndexed { index, breakdown ->
                if (index > 0) {
                    TokenUsageDivider(p = p)
                }
                TokenUsageMetricRow(
                    p = p,
                    title = displayModel(breakdown.model),
                    primary = AuraCodeBundle.message("settings.usage.label.total"),
                    primaryValue = formatCount(breakdown.totalTokens),
                    secondary = listOf(
                        AuraCodeBundle.message("settings.usage.label.input") to formatCount(breakdown.inputTokens),
                        AuraCodeBundle.message("settings.usage.label.cached") to formatCount(breakdown.cachedInputTokens),
                        AuraCodeBundle.message("settings.usage.label.output") to formatCount(breakdown.outputTokens),
                    ),
                )
            }
        }
    }
}

/** Renders the daily trend section for the selected engine and range. */
@Composable
internal fun TokenUsageDailyTrendSection(
    p: DesignPalette,
    snapshot: TokenUsageStatsSnapshot?,
) {
    TokenUsageSection(
        p = p,
        title = AuraCodeBundle.message("settings.usage.trend.title"),
        description = AuraCodeBundle.message("settings.usage.trend.note"),
    ) {
        val dailyPoints = snapshot?.dailyPoints.orEmpty()
        if (dailyPoints.isEmpty()) {
            SettingsInlineMessage(p = p, text = AuraCodeBundle.message("settings.usage.trend.empty"))
            return@TokenUsageSection
        }
        val maxTotalTokens = dailyPoints.maxOfOrNull(TokenUsageDailyPoint::totalTokens) ?: 0L
        TokenUsageSectionCard(p = p) {
            dailyPoints.forEachIndexed { index, point ->
                if (index > 0) {
                    TokenUsageDivider(p = p)
                }
                TokenUsageTrendRow(
                    p = p,
                    dayLabel = formatDay(point.dayStartEpochMillis),
                    totalLabel = AuraCodeBundle.message("settings.usage.label.total"),
                    totalValue = formatCount(point.totalTokens),
                    totalTokens = point.totalTokens,
                    maxTotalTokens = maxTotalTokens,
                    secondary = listOf(
                        AuraCodeBundle.message("settings.usage.label.input") to formatCount(point.inputTokens),
                        AuraCodeBundle.message("settings.usage.label.cached") to formatCount(point.cachedInputTokens),
                        AuraCodeBundle.message("settings.usage.label.output") to formatCount(point.outputTokens),
                    ),
                )
            }
        }
    }
}

/**
 * Renders one titled section block inside the Token Usage page.
 */
@Composable
private fun TokenUsageSection(
    p: DesignPalette,
    title: String,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    val t = assistantUiTokens()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
    ) {
        Text(
            text = title,
            color = p.textPrimary,
            style = MaterialTheme.typography.subtitle1,
            fontWeight = FontWeight.SemiBold,
        )
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                color = p.textMuted,
                style = MaterialTheme.typography.caption,
            )
        }
        content()
    }
}

/**
 * Renders one shared card container used by all Token Usage sections.
 */
@Composable
private fun TokenUsageSectionCard(
    p: DesignPalette,
    content: @Composable () -> Unit,
) {
    val t = assistantUiTokens()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.topBarBg.copy(alpha = 0.72f), RoundedCornerShape(t.spacing.lg))
            .border(1.dp, p.markdownDivider.copy(alpha = 0.42f), RoundedCornerShape(t.spacing.lg))
            .padding(horizontal = t.spacing.md, vertical = t.spacing.md),
    ) {
        content()
    }
}

/**
 * Renders one simple responsive key-value grid.
 */
@Composable
private fun TokenUsageKeyValueGrid(
    p: DesignPalette,
    items: List<Pair<String, String>>,
) {
    val t = assistantUiTokens()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
    ) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
            ) {
                rowItems.forEach { (label, value) ->
                    TokenUsageKeyValueItem(
                        p = p,
                        label = label,
                        value = value,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Renders one compact key-value item.
 */
@Composable
private fun TokenUsageKeyValueItem(
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
        )
        Text(
            text = value,
            color = p.textPrimary,
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Renders one list row with a strong primary metric and compact secondary metrics.
 */
@Composable
private fun TokenUsageMetricRow(
    p: DesignPalette,
    title: String,
    primary: String,
    primaryValue: String,
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
                text = title,
                color = p.textPrimary,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$primary: $primaryValue",
                color = p.accent,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        Text(
            text = secondary.joinToString("   ") { (label, value) -> "$label: $value" },
            color = p.textSecondary,
            style = MaterialTheme.typography.caption,
        )
    }
}

/**
 * Renders one subtle divider between list rows.
 */
@Composable
private fun TokenUsageDivider(
    p: DesignPalette,
) {
    val t = assistantUiTokens()
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(p.markdownDivider.copy(alpha = 0.18f)),
    )
}

/**
 * Formats one token count using the current locale's grouped integer style.
 */
private fun formatCount(value: Long): String {
    return NumberFormat.getIntegerInstance().format(value)
}

/**
 * Formats one local day using a stable year-month-day pattern.
 */
private fun formatDay(epochMillis: Long): String {
    if (epochMillis <= 0L) return "-"
    return DateTimeFormatter.ofPattern("yyyy-MM-dd").format(
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate(),
    )
}

/**
 * Resolves the visible model label, including the shared Unknown fallback bucket.
 */
private fun displayModel(model: String): String {
    return model.trim().ifBlank { AuraCodeBundle.message("settings.usage.model.unknown") }
}
