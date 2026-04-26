package com.auracode.assistant.toolwindow.submission

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Adds mouse horizontal dragging on top of a regular horizontal scroll state.
 */
internal fun Modifier.mouseHorizontalDragScroll(
    scrollState: ScrollState,
): Modifier = composed {
    val pointerTypes = remember {
        setOf(PointerType.Mouse, PointerType.Unknown)
    }
    pointerInput(scrollState) {
        detectHorizontalDragGestures { change, dragAmount ->
            if (change.type !in pointerTypes) {
                return@detectHorizontalDragGestures
            }
            scrollState.dispatchRawDelta(-dragAmount)
        }
    }
}
