package com.auracode.assistant.toolwindow.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import com.auracode.assistant.toolwindow.shared.rememberAttachmentPreviewBitmap

@Composable
internal fun ConversationMessageItem(
    node: ConversationActivityItem.MessageNode,
    palette: DesignPalette,
    onPreviewAttachment: (ConversationMessageAttachment) -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    val t = assistantUiTokens()
    val copyText = conversationActivityCopyText(node)
    val interactionSource = remember(node.id) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    if (isUserMessageNode(node)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .wrapContentWidth()
                    .hoverable(interactionSource),
            ) {
                Box(
                    modifier = Modifier
                        .wrapContentWidth()
                        .background(palette.userBubbleBg, RoundedCornerShape(t.spacing.sm + t.spacing.xs))
                        .padding(horizontal = t.spacing.md, vertical = t.spacing.xs + t.spacing.xs),
                ) {
                    ConversationCollapsibleMessageContent(
                        messageId = node.id,
                        palette = palette,
                    ) {
                        ConversationMessageContent(
                            node = node,
                            palette = palette,
                            onPreviewAttachment = onPreviewAttachment,
                            onOpenMarkdownFilePath = onOpenMarkdownFilePath,
                        )
                    }
                }
                copyText?.let { text ->
                    ConversationCopyActionButton(
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
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(interactionSource),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        palette.timelineCardBg.copy(alpha = 0.72f),
                        RoundedCornerShape(t.spacing.md),
                    )
                    .border(
                        width = 1.dp,
                        color = palette.markdownDivider.copy(alpha = 0.26f),
                        shape = RoundedCornerShape(t.spacing.md),
                    )
                    .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
            ) {
                ConversationMessageContent(
                    node = node,
                    palette = palette,
                    onPreviewAttachment = onPreviewAttachment,
                    onOpenMarkdownFilePath = onOpenMarkdownFilePath,
                )
            }
            copyText?.let { text ->
                ConversationCopyActionButton(
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
private fun ConversationMessageContent(
    node: ConversationActivityItem.MessageNode,
    palette: DesignPalette,
    onPreviewAttachment: (ConversationMessageAttachment) -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    val t = assistantUiTokens()
    Column {
        if (node.attachments.isNotEmpty()) {
            ConversationAttachmentRow(
                attachments = node.attachments,
                palette = palette,
                onPreviewAttachment = onPreviewAttachment,
            )
            if (node.text.isNotBlank()) {
                Spacer(Modifier.height(t.spacing.sm))
            }
        }
        if (node.text.isNotBlank()) {
            ConversationMarkdown(
                text = node.text,
                palette = palette,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 2.dp),
                onOpenFilePath = onOpenMarkdownFilePath,
            )
        }
    }
}

@Composable
private fun ConversationAttachmentRow(
    attachments: List<ConversationMessageAttachment>,
    palette: DesignPalette,
    onPreviewAttachment: (ConversationMessageAttachment) -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
    ) {
        attachments.forEach { attachment ->
            ConversationAttachmentCard(
                attachment = attachment,
                palette = palette,
                onClick = { onPreviewAttachment(attachment) },
            )
        }
    }
}

@Composable
private fun ConversationAttachmentCard(
    attachment: ConversationMessageAttachment,
    palette: DesignPalette,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    val bitmap = if (attachment.kind == ConversationAttachmentKind.IMAGE) {
        rememberAttachmentPreviewBitmap(attachment.assetPath)
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
        if (attachment.kind == ConversationAttachmentKind.IMAGE && bitmap != null) {
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

internal fun isUserMessageNode(node: ConversationActivityItem): Boolean {
    return node is ConversationActivityItem.MessageNode && node.role == MessageRole.USER
}
