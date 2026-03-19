package com.codex.assistant.toolwindow.timeline

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.assistant.model.MessageRole
import com.codex.assistant.toolwindow.shared.DesignPalette
import com.codex.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun TimelineMessageItem(
    node: TimelineNode.MessageNode,
    palette: DesignPalette,
    onPreviewAttachment: (TimelineMessageAttachment) -> Unit,
) {
    val t = assistantUiTokens()
    if (isUserMessageNode(node)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .wrapContentWidth()
                    .background(palette.userBubbleBg, RoundedCornerShape(t.spacing.sm + t.spacing.xs))
                    .padding(horizontal = t.spacing.md, vertical = t.spacing.xs + t.spacing.xs),
            ) {
                TimelineMessageContent(node = node, palette = palette, onPreviewAttachment = onPreviewAttachment)
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    palette.topBarBg.copy(alpha = 0.5f),
                    RoundedCornerShape(t.spacing.md),
                )
                .border(
                    width = 1.dp,
                    color = palette.markdownDivider.copy(alpha = 0.34f),
                    shape = RoundedCornerShape(t.spacing.md),
                )
                .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
        ) {
            TimelineMessageContent(node = node, palette = palette, onPreviewAttachment = onPreviewAttachment)
        }
    }
}

@Composable
private fun TimelineMessageContent(
    node: TimelineNode.MessageNode,
    palette: DesignPalette,
    onPreviewAttachment: (TimelineMessageAttachment) -> Unit,
) {
    val t = assistantUiTokens()
    Column {
        if (node.attachments.isNotEmpty()) {
            TimelineAttachmentRow(
                attachments = node.attachments,
                palette = palette,
                onPreviewAttachment = onPreviewAttachment,
            )
            if (node.text.isNotBlank()) {
                Spacer(Modifier.height(t.spacing.sm))
            }
        }
        if (node.text.isNotBlank()) {
            TimelineMarkdown(
                text = node.text,
                palette = palette,
                modifier = Modifier.fillMaxWidth().padding(end = t.spacing.sm),
            )
        }
    }
}

@Composable
private fun TimelineAttachmentRow(
    attachments: List<TimelineMessageAttachment>,
    palette: DesignPalette,
    onPreviewAttachment: (TimelineMessageAttachment) -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
    ) {
        attachments.forEach { attachment ->
            TimelineAttachmentCard(
                attachment = attachment,
                palette = palette,
                onClick = { onPreviewAttachment(attachment) },
            )
        }
    }
}

@Composable
private fun TimelineAttachmentCard(
    attachment: TimelineMessageAttachment,
    palette: DesignPalette,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    val bitmap = if (attachment.kind == TimelineAttachmentKind.IMAGE) {
        rememberTimelineAttachmentBitmap(attachment.assetPath)
    } else {
        null
    }
    Column(
        modifier = Modifier
            .width(140.dp)
            .background(palette.topStripBg.copy(alpha = 0.94f), RoundedCornerShape(t.spacing.sm))
            .border(1.dp, palette.markdownDivider.copy(alpha = 0.42f), RoundedCornerShape(t.spacing.sm))
            .clickable(onClick = onClick)
            .padding(t.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
    ) {
        if (attachment.kind == TimelineAttachmentKind.IMAGE && bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .background(palette.topBarBg, RoundedCornerShape(t.spacing.sm)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(palette.topBarBg, RoundedCornerShape(t.spacing.sm)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource("/icons/document.svg"),
                    contentDescription = null,
                    tint = palette.textMuted,
                    modifier = Modifier.size(t.controls.iconLg),
                )
            }
        }
        Text(
            text = attachment.displayName,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = androidx.compose.material.MaterialTheme.typography.body2,
        )
    }
}

internal fun isUserMessageNode(node: TimelineNode): Boolean {
    return node is TimelineNode.MessageNode && node.role == MessageRole.USER
}
