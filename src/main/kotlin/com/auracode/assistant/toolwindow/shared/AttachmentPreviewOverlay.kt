package com.auracode.assistant.toolwindow.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.auracode.assistant.i18n.AuraCodeBundle
import java.io.FileInputStream

internal data class AttachmentPreviewPayload(
    val assetPath: String,
    val isImage: Boolean,
)

/**
 * Describes the interaction chrome used by the unified attachment preview overlay.
 */
internal data class AttachmentPreviewPresentation(
    val displaysImageContent: Boolean,
    val showsTitle: Boolean,
    val showsSecondaryActions: Boolean,
    val dismissOnScrimClick: Boolean,
)

/**
 * Resolves the presentation rules shared by timeline and composer previews.
 */
internal fun attachmentPreviewPresentation(isImage: Boolean): AttachmentPreviewPresentation {
    return AttachmentPreviewPresentation(
        displaysImageContent = isImage,
        showsTitle = false,
        showsSecondaryActions = false,
        dismissOnScrimClick = true,
    )
}

/**
 * Renders a single full-screen overlay for attachment previews without title or action rows.
 */
@Composable
internal fun AttachmentPreviewOverlay(
    palette: DesignPalette,
    preview: AttachmentPreviewPayload?,
    onDismiss: () -> Unit,
) {
    preview ?: return
    val t = assistantUiTokens()
    val bitmap = rememberAttachmentPreviewBitmap(preview.assetPath)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        val scrimInteractionSource = remember { MutableInteractionSource() }
        val contentInteractionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.82f))
                .clickable(
                    interactionSource = scrimInteractionSource,
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp)
                    .clickable(
                        interactionSource = contentInteractionSource,
                        indication = null,
                        onClick = {},
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (preview.isImage && bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    androidx.compose.material.Icon(
                        painter = painterResource("/icons/attach-file.svg"),
                        contentDescription = null,
                        tint = palette.textMuted,
                        modifier = Modifier.size(72.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = t.spacing.md, end = t.spacing.md),
            ) {
                AttachmentPreviewCloseButton(
                    palette = palette,
                    onClick = onDismiss,
                )
            }
        }
    }
}

/**
 * Loads preview images from disk for both composer and timeline attachments.
 */
@Composable
internal fun rememberAttachmentPreviewBitmap(path: String): ImageBitmap? {
    return remember(path) {
        runCatching {
            FileInputStream(path).use(::loadImageBitmap)
        }.getOrNull()
    }
}

/**
 * Keeps the preview overlay action limited to a single close affordance.
 */
@Composable
private fun AttachmentPreviewCloseButton(
    palette: DesignPalette,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(palette.topStripBg.copy(alpha = 0.96f), androidx.compose.foundation.shape.RoundedCornerShape(t.spacing.sm))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material.Icon(
            painter = painterResource("/icons/close.svg"),
            contentDescription = AuraCodeBundle.message("common.close"),
            tint = palette.textPrimary,
            modifier = Modifier.size(t.controls.iconLg),
        )
    }
}
