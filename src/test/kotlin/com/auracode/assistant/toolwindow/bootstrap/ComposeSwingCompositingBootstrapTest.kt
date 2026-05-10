package com.auracode.assistant.toolwindow.bootstrap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeSwingCompositingBootstrapTest {
    @Test
    fun `enables compose swing graphics rendering when property is missing`() {
        val updates = mutableMapOf<String, String>()

        val changed = ComposeSwingCompositingBootstrap.configureIfNeeded(
            currentPropertyValue = null,
            setProperty = { key, value -> updates[key] = value },
        )

        assertTrue(changed)
        assertEquals("true", updates["compose.swing.render.on.graphics"])
    }

    @Test
    fun `does not overwrite compose swing graphics rendering when already enabled`() {
        val updates = mutableMapOf<String, String>()

        val changed = ComposeSwingCompositingBootstrap.configureIfNeeded(
            currentPropertyValue = "true",
            setProperty = { key, value -> updates[key] = value },
        )

        assertFalse(changed)
        assertTrue(updates.isEmpty())
    }
}
