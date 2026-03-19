package com.codex.assistant.toolwindow.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.codex.assistant.toolwindow.shared.DesignPalette
import com.codex.assistant.toolwindow.shared.FileTypeIcon
import com.codex.assistant.toolwindow.shared.HoverTooltip
import com.codex.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun TimelineFileChangeItem(
    node: TimelineNode.FileChangeNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenChange: (TimelineFileChange) -> Unit,
) {
    TimelineExpandableCard(
        title = node.title,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(assistantUiTokens().spacing.sm),
        ) {
            node.changes.forEach { change ->
                FileChangeRow(change = change, palette = palette, onOpenChange = onOpenChange)
            }
        }
    }
}

@Composable
private fun FileChangeRow(
    change: TimelineFileChange,
    palette: DesignPalette,
    onOpenChange: (TimelineFileChange) -> Unit,
) {
    val t = assistantUiTokens()
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val linkText = buildAnnotatedString {
        withLink(
            LinkAnnotation.Clickable(
                tag = change.path,
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = palette.linkColor,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.Medium,
                    ),
                ),
                linkInteractionListener = { onOpenChange(change) },
            ),
        ) {
            append(change.displayName)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (hovered) palette.topBarBg.copy(alpha = 0.92f) else palette.topBarBg.copy(alpha = 0.72f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(t.spacing.sm),
            )
            .hoverable(interactionSource = interactionSource)
            .clickable(onClick = { onOpenChange(change) })
            .padding(horizontal = t.spacing.sm, vertical = t.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
    ) {
        FileTypeIcon(
            fileName = change.displayName,
            modifier = Modifier.size(t.controls.iconMd),
        )
        Box(modifier = Modifier.weight(1f)) {
            HoverTooltip(change.path) {
                Text(
                    text = linkText,
                    style = androidx.compose.material.MaterialTheme.typography.body2.copy(color = palette.linkColor),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TimelineFileChangeStats(change = change, palette = palette)
    }
}

@Composable
private fun TimelineFileChangeStats(
    change: TimelineFileChange,
    palette: DesignPalette,
) {
    val t = assistantUiTokens()
    Row(
        horizontalArrangement = Arrangement.spacedBy(t.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        change.addedLines?.takeIf { it > 0 }?.let { added ->
            Text(
                text = "+$added",
                color = palette.success,
                style = androidx.compose.material.MaterialTheme.typography.caption,
                fontWeight = FontWeight.Medium,
            )
        }
        change.deletedLines?.takeIf { it > 0 }?.let { deleted ->
            Text(
                text = "-$deleted",
                color = palette.danger,
                style = androidx.compose.material.MaterialTheme.typography.caption,
                fontWeight = FontWeight.Medium,
            )
        }
        if ((change.addedLines ?: 0) <= 0 && (change.deletedLines ?: 0) <= 0) {
            Box(
                modifier = Modifier
                    .background(Color.Transparent)
                    .padding(horizontal = t.spacing.xs),
            )
        }
    }
}
