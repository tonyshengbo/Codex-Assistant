package com.codex.assistant.toolwindow.composer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.codex.assistant.i18n.CodexBundle
import com.codex.assistant.toolwindow.eventing.UiIntent
import com.codex.assistant.toolwindow.shared.DesignPalette
import com.codex.assistant.toolwindow.shared.HoverTooltip
import com.codex.assistant.toolwindow.shared.assistantUiTokens
import java.io.FileInputStream

@Composable
internal fun ComposerAttachmentPreviewDialog(
    p: DesignPalette,
    state: ComposerAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val previewAttachment = state.attachments.firstOrNull { it.id == state.previewAttachmentId } ?: return
    val bitmap = rememberAttachmentBitmap(previewAttachment.path)

    AlertDialog(
        onDismissRequest = { onIntent(UiIntent.CloseAttachmentPreview) },
        title = { Text(previewAttachment.displayName) },
        text = {
            Box(
                modifier = Modifier
                    .size(420.dp)
                    .background(p.topBarBg, RoundedCornerShape(t.spacing.sm)),
                contentAlignment = Alignment.Center,
            ) {
                if (previewAttachment.kind == AttachmentKind.IMAGE && bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.size(400.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        painter = painterResource("/icons/attach-file.svg"),
                        contentDescription = null,
                        tint = p.textMuted,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onIntent(UiIntent.CloseAttachmentPreview) }) {
                Text(CodexBundle.message("common.close"))
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(UiIntent.RemoveAttachment(previewAttachment.id)) }) {
                Text(CodexBundle.message("common.delete"))
            }
        },
    )
}

@Composable
internal fun AttachmentStrip(
    p: DesignPalette,
    state: ComposerAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    if (state.attachments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.topBarBg.copy(alpha = 0.72f), RoundedCornerShape(t.spacing.sm))
            .padding(horizontal = t.spacing.sm, vertical = 3.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.attachments.forEach { attachment ->
            AttachmentCard(attachment = attachment, p = p, onIntent = onIntent)
            Spacer(Modifier.width(t.spacing.xs))
        }
    }
}

@Composable
private fun AttachmentCard(
    attachment: AttachmentEntry,
    p: DesignPalette,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val bitmap = rememberAttachmentBitmap(attachment.path)
    Box(
        modifier = Modifier
            .size(58.dp)
            .background(p.topStripBg, RoundedCornerShape(t.spacing.sm))
            .clickable { onIntent(UiIntent.OpenAttachmentPreview(attachment.id)) }
            .padding(5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(p.timelineCardBg, RoundedCornerShape(t.spacing.sm)),
            contentAlignment = Alignment.Center,
        ) {
            if (attachment.kind == AttachmentKind.IMAGE && bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    painter = painterResource("/icons/document.svg"),
                    contentDescription = null,
                    tint = p.textMuted,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        HoverTooltip(text = CodexBundle.message("common.delete")) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(15.dp)
                    .background(p.topBarBg, RoundedCornerShape(999.dp))
                    .clickable { onIntent(UiIntent.RemoveAttachment(attachment.id)) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource("/icons/delete.svg"),
                    contentDescription = CodexBundle.message("common.delete"),
                    tint = p.textMuted,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}

@Composable
private fun rememberAttachmentBitmap(path: String): ImageBitmap? {
    return remember(path) {
        runCatching {
            FileInputStream(path).use(::loadImageBitmap)
        }.getOrNull()
    }
}
