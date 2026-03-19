package com.codex.assistant.toolwindow.shared

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.intellij.openapi.fileTypes.FileTypeManager
import java.awt.image.BufferedImage

@Composable
internal fun FileTypeIcon(
    fileName: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val bitmap = remember(fileName) { fileTypeBitmap(fileName) }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier,
        alpha = if (tint == Color.Unspecified) 1f else tint.alpha,
    )
}

private fun fileTypeBitmap(fileName: String): ImageBitmap {
    val icon = FileTypeManager.getInstance().getFileTypeByFileName(fileName).icon
    val width = icon.iconWidth.coerceAtLeast(1)
    val height = icon.iconHeight.coerceAtLeast(1)
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        icon.paintIcon(null, graphics, 0, 0)
    } finally {
        graphics.dispose()
    }
    return image.toComposeImageBitmap()
}
