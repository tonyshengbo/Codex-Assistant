package com.auracode.assistant.toolwindow.submission

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

class SubmissionPopupAnchorTest {
    @Test
    fun `popup anchor follows caret at line start`() {
        val anchor = calculateSubmissionPopupAnchor(
            layout = SubmissionPopupAnchorLayout(
                rootLeft = 20f,
                rootTop = 100f,
                editorLeft = 36f,
                editorTop = 124f,
                editorHeight = 28f,
            ),
            cursorRect = Rect(left = 0f, top = 0f, right = 1f, bottom = 20f),
        )

        assertEquals(SubmissionPopupAnchor(x = 16, y = 44), anchor)
    }

    @Test
    fun `popup anchor follows caret as the cursor moves across the line`() {
        val layout = SubmissionPopupAnchorLayout(
            rootLeft = 20f,
            rootTop = 100f,
            editorLeft = 36f,
            editorTop = 124f,
            editorHeight = 28f,
        )

        val midLineAnchor = calculateSubmissionPopupAnchor(
            layout = layout,
            cursorRect = Rect(left = 96f, top = 0f, right = 97f, bottom = 20f),
        )
        val lineEndAnchor = calculateSubmissionPopupAnchor(
            layout = layout,
            cursorRect = Rect(left = 188f, top = 0f, right = 189f, bottom = 20f),
        )

        assertEquals(SubmissionPopupAnchor(x = 112, y = 44), midLineAnchor)
        assertEquals(SubmissionPopupAnchor(x = 204, y = 44), lineEndAnchor)
    }

    @Test
    fun `popup anchor falls back to the editable region when caret layout is unavailable`() {
        val anchor = calculateSubmissionPopupAnchor(
            layout = SubmissionPopupAnchorLayout(
                rootLeft = 40f,
                rootTop = 220f,
                editorLeft = 64f,
                editorTop = 248f,
                editorHeight = 54f,
            ),
            cursorRect = null,
        )

        assertEquals(SubmissionPopupAnchor(x = 24, y = 82), anchor)
    }

    @Test
    fun `popup anchor clamps negative offsets back into the local root`() {
        val anchor = calculateSubmissionPopupAnchor(
            layout = SubmissionPopupAnchorLayout(
                rootLeft = 120f,
                rootTop = 80f,
                editorLeft = 100f,
                editorTop = 92f,
                editorHeight = 24f,
            ),
            cursorRect = Rect(left = -12f, top = 0f, right = -11f, bottom = 18f),
        )

        assertEquals(SubmissionPopupAnchor(x = 0, y = 30), anchor)
    }
}
