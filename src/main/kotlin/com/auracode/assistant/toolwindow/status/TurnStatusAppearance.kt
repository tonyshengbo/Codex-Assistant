package com.auracode.assistant.toolwindow.status

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Centralizes the visual scale of the running status bar so the component can
 * stay readable in narrow tool window layouts without scattering hard-coded
 * values across the Compose tree.
 */
internal data class TurnStatusAppearanceSpec(
    val minHeight: Dp,
    val indicatorSize: Dp,
    val labelFontSize: TextUnit,
    val elapsedFontSize: TextUnit,
    val containerAlpha: Float,
    val elapsedChipAlpha: Float,
)

internal fun turnStatusAppearanceSpec(): TurnStatusAppearanceSpec {
    return TurnStatusAppearanceSpec(
        minHeight = 32.dp,
        indicatorSize = 16.dp,
        labelFontSize = 13.sp,
        elapsedFontSize = 11.sp,
        containerAlpha = 0.98f,
        elapsedChipAlpha = 0.14f,
    )
}
