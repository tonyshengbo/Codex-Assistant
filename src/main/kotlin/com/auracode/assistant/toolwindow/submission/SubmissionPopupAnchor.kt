package com.auracode.assistant.toolwindow.submission

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * Stores the layout numbers needed to translate a caret rectangle into the popup anchor offset
 * used by the shared composer suggestion menu.
 */
internal data class SubmissionPopupAnchorLayout(
    val rootLeft: Float,
    val rootTop: Float,
    val editorLeft: Float,
    val editorTop: Float,
    val editorHeight: Float,
)

/**
 * Represents the local anchor point used by slash, mention, and agent suggestion popups.
 */
internal data class SubmissionPopupAnchor(
    val x: Int,
    val y: Int,
) {
    /** Converts the anchor into the offset format expected by Compose layout modifiers. */
    fun toIntOffset(): IntOffset = IntOffset(x = x, y = y)
}

/**
 * Calculates the popup anchor from the editor frame and the current caret rectangle.
 *
 * When the text layout has not produced a caret rectangle yet, the popup falls back to the left
 * edge of the editable region and sits below the editor content.
 */
internal fun calculateSubmissionPopupAnchor(
    layout: SubmissionPopupAnchorLayout,
    cursorRect: Rect?,
): SubmissionPopupAnchor {
    val editorOffsetX = (layout.editorLeft - layout.rootLeft).roundToInt().coerceAtLeast(0)
    val editorOffsetY = (layout.editorTop - layout.rootTop).roundToInt().coerceAtLeast(0)
    if (cursorRect == null) {
        return SubmissionPopupAnchor(
            x = editorOffsetX,
            y = (editorOffsetY + layout.editorHeight.roundToInt()).coerceAtLeast(0),
        )
    }
    return SubmissionPopupAnchor(
        x = (editorOffsetX + cursorRect.left.roundToInt()).coerceAtLeast(0),
        y = (editorOffsetY + cursorRect.bottom.roundToInt()).coerceAtLeast(0),
    )
}

/**
 * Extracts stable layout numbers from Compose coordinates before passing them to the pure anchor
 * calculator that is covered by unit tests.
 */
internal fun resolveSubmissionPopupAnchorLayout(
    rootCoordinates: LayoutCoordinates?,
    editorCoordinates: LayoutCoordinates?,
): SubmissionPopupAnchorLayout? {
    if (rootCoordinates == null || editorCoordinates == null) return null
    if (!rootCoordinates.isAttached || !editorCoordinates.isAttached) return null
    val rootPosition = rootCoordinates.positionInRoot()
    val editorPosition = editorCoordinates.positionInRoot()
    return SubmissionPopupAnchorLayout(
        rootLeft = rootPosition.x,
        rootTop = rootPosition.y,
        editorLeft = editorPosition.x,
        editorTop = editorPosition.y,
        editorHeight = editorCoordinates.size.height.toFloat(),
    )
}
