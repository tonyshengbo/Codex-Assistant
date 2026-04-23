package com.auracode.assistant.toolwindow.composer

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.auracode.assistant.toolwindow.shared.AssistantUiTokens
import com.auracode.assistant.toolwindow.shared.FileTypeIcon
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

/**
 * Returns the fixed file icon size used by composer chips and suggestion rows.
 */
internal fun composerFileTypeIconSize(tokens: AssistantUiTokens): Dp = tokens.controls.iconMd

/**
 * Renders a composer file icon with the shared fixed-size contract.
 */
@Composable
internal fun ComposerFileTypeIcon(
    fileName: String,
    tint: Color,
    tokens: AssistantUiTokens = assistantUiTokens(),
) {
    FileTypeIcon(
        fileName = fileName,
        modifier = Modifier.size(composerFileTypeIconSize(tokens)),
        tint = tint,
    )
}
