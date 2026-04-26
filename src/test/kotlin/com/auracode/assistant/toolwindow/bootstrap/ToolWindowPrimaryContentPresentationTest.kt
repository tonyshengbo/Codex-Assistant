package com.auracode.assistant.toolwindow.shell

import com.intellij.ui.content.impl.ContentImpl
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolWindowPrimaryContentPresentationTest {
    @Test
    fun `configure hides default tool window id label and clears primary content titles`() {
        val toolWindowComponent = JPanel()
        val content = ContentImpl(JPanel(), "Aura Code", false)

        ToolWindowPrimaryContentPresentation.configure(toolWindowComponent, content)

        assertEquals(true, toolWindowComponent.getClientProperty("HideIdLabel"))
        assertEquals("", content.displayName)
        assertEquals("", content.tabName)
        assertEquals("", content.toolwindowTitle)
    }
}
