package com.auracode.assistant.toolwindow.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.HoverTooltip
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import androidx.compose.ui.res.painterResource

@Composable
internal fun LoadOlderHistoryButton(
    loading: Boolean,
    p: DesignPalette,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.topBarBg.copy(alpha = 0.74f), RoundedCornerShape(t.spacing.md))
            .border(1.dp, p.markdownDivider.copy(alpha = 0.5f), RoundedCornerShape(t.spacing.md))
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
    ) {
        Text(
            text = if (loading) AuraCodeBundle.message("timeline.loadingOlder") else AuraCodeBundle.message("timeline.loadOlder"),
            color = p.textSecondary,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
internal fun TimelineExpandableCard(
    title: String,
    titleIconPath: String? = null,
    headerBadge: String? = null,
    titleTargetLabel: String? = null,
    titleTargetPath: String? = null,
    collapsedSummary: String? = null,
    status: ItemStatus,
    expanded: Boolean,
    palette: DesignPalette,
    onToggleExpanded: () -> Unit,
    onOpenTitleTarget: ((String) -> Unit)? = null,
    expandedBodyMaxHeight: Dp? = timelineExpandedBodyMaxHeight(),
    accentColor: androidx.compose.ui.graphics.Color? = null,
    cardBackground: androidx.compose.ui.graphics.Color? = null,
    bodyBackground: androidx.compose.ui.graphics.Color? = null,
    useBodyContainer: Boolean = true,
    copyText: String? = null,
    content: @Composable () -> Unit,
) {
    val t = assistantUiTokens()
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val statusColor = when (status) {
        ItemStatus.FAILED -> palette.danger
        ItemStatus.RUNNING -> palette.accent
        else -> palette.success
    }
    val indicatorColor = accentColor ?: statusColor
    HoverTooltip(text = if (expanded) AuraCodeBundle.message("timeline.collapse") else AuraCodeBundle.message("timeline.expand")) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(interactionSource),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardBackground ?: palette.timelineCardBg, RoundedCornerShape(t.spacing.sm))
                    .border(
                        width = 1.dp,
                        color = (accentColor ?: palette.markdownDivider).copy(alpha = if (accentColor != null) 0.26f else 0.42f),
                        shape = RoundedCornerShape(t.spacing.sm),
                    )
                    .clickable(enabled = !expanded, onClick = onToggleExpanded)
                    .padding(horizontal = t.spacing.sm + 2.dp, vertical = t.spacing.xs + 3.dp),
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onToggleExpanded),
                    ) {
                        Box(
                            modifier = Modifier.size(22.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TimelineChevronGlyph(
                                expanded = expanded,
                                palette = palette,
                            )
                        }
                        TimelineExpandableCardTitle(
                            title = title,
                            titleIconPath = titleIconPath,
                            titleTargetLabel = titleTargetLabel,
                            titleTargetPath = titleTargetPath,
                            palette = palette,
                            onOpenTitleTarget = onOpenTitleTarget,
                        )
                        if (!headerBadge.isNullOrBlank()) {
                            Spacer(Modifier.width(t.spacing.xs))
                            TimelineHeaderBadge(
                                text = headerBadge,
                                palette = palette,
                                accentColor = accentColor ?: palette.accent,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .size(t.spacing.sm)
                                .background(indicatorColor, CircleShape),
                        )
                    }
                    if (expanded) {
                        Spacer(Modifier.height(t.spacing.xs + 2.dp))
                        TimelineExpandableCardBody(
                            palette = palette,
                            accentColor = accentColor,
                            bodyBackground = bodyBackground,
                            expandedBodyMaxHeight = expandedBodyMaxHeight,
                            useBodyContainer = useBodyContainer,
                            content = content,
                        )
                    } else if (!collapsedSummary.isNullOrBlank()) {
                        Spacer(Modifier.height(t.spacing.xs))
                        TimelineSelectableText(
                            selectionColors = rememberTimelineMarkdownSelectionColors(palette),
                        ) {
                            Text(
                                text = collapsedSummary,
                                color = palette.textSecondary.copy(alpha = 0.92f),
                                style = androidx.compose.material.MaterialTheme.typography.caption,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    start = 30.dp,
                                    end = t.spacing.md,
                                ),
                            )
                        }
                    }
                }
            }
            copyText?.let { text ->
                TimelineCopyActionButton(
                    visible = hovered,
                    copyText = text,
                    palette = palette,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 4.dp, end = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun TimelineMarkdownActivityBody(
    title: String,
    titleIconPath: String? = null,
    headerBadge: String? = null,
    titleTargetLabel: String? = null,
    titleTargetPath: String? = null,
    body: String,
    collapsedSummary: String? = null,
    status: ItemStatus,
    expanded: Boolean,
    palette: DesignPalette,
    onToggleExpanded: () -> Unit,
    onOpenTitleTarget: ((String) -> Unit)? = null,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
    expandedBodyMaxHeight: Dp? = timelineExpandedBodyMaxHeight(),
    accentColor: androidx.compose.ui.graphics.Color? = null,
    cardBackground: androidx.compose.ui.graphics.Color? = null,
    bodyBackground: androidx.compose.ui.graphics.Color? = null,
    useBodyContainer: Boolean = true,
    copyText: String? = body.takeIf { it.isNotBlank() },
) {
    TimelineExpandableCard(
        title = title,
        titleIconPath = titleIconPath,
        headerBadge = headerBadge,
        titleTargetLabel = titleTargetLabel,
        titleTargetPath = titleTargetPath,
        collapsedSummary = collapsedSummary,
        status = status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
        onOpenTitleTarget = onOpenTitleTarget,
        expandedBodyMaxHeight = expandedBodyMaxHeight,
        accentColor = accentColor,
        cardBackground = cardBackground,
        bodyBackground = bodyBackground,
        useBodyContainer = useBodyContainer,
        copyText = copyText,
    ) {
        TimelineMarkdown(
            text = body,
            palette = palette,
            onOpenFilePath = onOpenMarkdownFilePath,
        )
    }
}

@Composable
private fun TimelineChevronGlyph(
    expanded: Boolean,
    palette: DesignPalette,
) {
    Text(
        text = if (expanded) "⌄" else "›",
        color = palette.textSecondary,
        style = androidx.compose.material.MaterialTheme.typography.body2,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun TimelineExpandableCardBody(
    palette: DesignPalette,
    accentColor: androidx.compose.ui.graphics.Color?,
    bodyBackground: androidx.compose.ui.graphics.Color?,
    expandedBodyMaxHeight: Dp?,
    useBodyContainer: Boolean,
    content: @Composable () -> Unit,
) {
    val t = assistantUiTokens()
    val shouldScroll = expandedBodyMaxHeight != null
    val scrollState = rememberScrollState()

    if (useBodyContainer) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bodyBackground ?: palette.topStripBg.copy(alpha = 0.62f), RoundedCornerShape(t.spacing.sm))
                .border(
                    width = 1.dp,
                    color = (accentColor ?: palette.markdownDivider).copy(alpha = if (accentColor != null) 0.18f else 0.24f),
                    shape = RoundedCornerShape(t.spacing.sm),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { modifier ->
                        expandedBodyMaxHeight?.let { modifier.heightIn(max = it) } ?: modifier
                    }
                    .padding(horizontal = t.spacing.sm, vertical = t.spacing.sm),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { modifier ->
                            if (shouldScroll) modifier.verticalScroll(scrollState) else modifier
                        },
                ) {
                    content()
                }
                if (shouldScroll && scrollState.canScrollForward) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(18.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        palette.topStripBg.copy(alpha = 0f),
                                        palette.topStripBg.copy(alpha = 0.92f),
                                    ),
                                ),
                            ),
                    )
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background((accentColor ?: palette.markdownDivider).copy(alpha = 0.14f)),
        )
        Spacer(Modifier.height(t.spacing.sm))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .let { modifier ->
                    expandedBodyMaxHeight?.let { modifier.heightIn(max = it) } ?: modifier
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { modifier ->
                        if (shouldScroll) modifier.verticalScroll(scrollState) else modifier
                    },
            ) {
                content()
            }
            if (shouldScroll && scrollState.canScrollForward) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    palette.topStripBg.copy(alpha = 0f),
                                    palette.topStripBg.copy(alpha = 0.92f),
                                ),
                            ),
                        ),
                )
            }
        }
    }
}

@Composable
private fun TimelineHeaderBadge(
    text: String,
    palette: DesignPalette,
    accentColor: androidx.compose.ui.graphics.Color,
) {
    val t = assistantUiTokens()
    Box(
        modifier = Modifier
            .background(accentColor.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .border(1.dp, accentColor.copy(alpha = 0.26f), RoundedCornerShape(999.dp))
            .padding(horizontal = t.spacing.xs, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = accentColor,
            style = androidx.compose.material.MaterialTheme.typography.caption,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TimelineExpandableCardTitle(
    title: String,
    titleIconPath: String?,
    titleTargetLabel: String?,
    titleTargetPath: String?,
    palette: DesignPalette,
    onOpenTitleTarget: ((String) -> Unit)?,
) {
    val targetLabel = titleTargetLabel
    val targetPath = titleTargetPath
    Row(verticalAlignment = Alignment.CenterVertically) {
        titleIconPath?.let { iconPath ->
            Icon(
                painter = painterResource(iconPath),
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        if (targetLabel.isNullOrBlank() || targetPath.isNullOrBlank() || onOpenTitleTarget == null) {
            Text(
                text = title,
                color = palette.timelineCardText,
                fontWeight = FontWeight.Medium,
                style = androidx.compose.material.MaterialTheme.typography.subtitle1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            return@Row
        }

        val prefix = title.substringBefore(targetLabel).ifBlank { title.removeSuffix(targetLabel).trimEnd() }
        val suffix = title.substringAfter(targetLabel, "")
        val annotated = buildAnnotatedString {
            append(prefix)
            if (prefix.isNotBlank() && !prefix.endsWith(" ")) append(" ")
            withLink(
                LinkAnnotation.Clickable(
                    tag = targetPath,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = palette.linkColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium,
                        ),
                    ),
                    linkInteractionListener = { onOpenTitleTarget(targetPath) },
                ),
            ) {
                append(targetLabel)
            }
            append(suffix)
        }
        Text(
            text = annotated,
            color = palette.timelineCardText,
            fontWeight = FontWeight.Medium,
            style = androidx.compose.material.MaterialTheme.typography.subtitle1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun timelineExpandedBodyMaxHeight(): Dp = 220.dp

internal fun timelineCommandExpandedBodyMaxHeight(): Dp = 240.dp

internal fun timelinePlanExpandedBodyMaxHeight(): Dp? = null
