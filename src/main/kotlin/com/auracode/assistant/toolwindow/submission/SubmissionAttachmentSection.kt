package com.auracode.assistant.toolwindow.submission

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
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.AttachmentPreviewOverlay
import com.auracode.assistant.toolwindow.shared.AttachmentPreviewPayload
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.HoverTooltip
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import com.auracode.assistant.toolwindow.shared.rememberAttachmentPreviewBitmap

@Composable
internal fun SubmissionAttachmentPreviewDialog(
    p: DesignPalette,
    state: SubmissionAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val previewAttachment = state.attachments.firstOrNull { it.id == state.previewAttachmentId } ?: return
    AttachmentPreviewOverlay(
        palette = p,
        preview = AttachmentPreviewPayload(
            assetPath = previewAttachment.path,
            isImage = previewAttachment.kind == AttachmentKind.IMAGE,
        ),
        onDismiss = { onIntent(UiIntent.CloseAttachmentPreview) },
    )
}

@Composable
internal fun AttachmentStrip(
    p: DesignPalette,
    state: SubmissionAreaState,
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
    val bitmap = rememberAttachmentPreviewBitmap(attachment.path)
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
        HoverTooltip(text = AuraCodeBundle.message("common.delete")) {
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
                    contentDescription = AuraCodeBundle.message("common.delete"),
                    tint = p.textMuted,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}
