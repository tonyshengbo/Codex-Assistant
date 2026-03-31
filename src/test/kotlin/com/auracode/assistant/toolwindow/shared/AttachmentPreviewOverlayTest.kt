package com.auracode.assistant.toolwindow.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentPreviewOverlayTest {
    @Test
    fun `image attachments use image-first preview presentation`() {
        val presentation = attachmentPreviewPresentation(isImage = true)

        assertTrue(presentation.displaysImageContent)
        assertFalse(presentation.showsTitle)
        assertFalse(presentation.showsSecondaryActions)
    }

    @Test
    fun `file attachments still use the same chrome-free overlay`() {
        val presentation = attachmentPreviewPresentation(isImage = false)

        assertFalse(presentation.displaysImageContent)
        assertFalse(presentation.showsTitle)
        assertFalse(presentation.showsSecondaryActions)
        assertEquals(true, presentation.dismissOnScrimClick)
    }
}
