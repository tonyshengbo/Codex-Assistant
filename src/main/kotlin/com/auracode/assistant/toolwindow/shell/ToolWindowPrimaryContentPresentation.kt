package com.auracode.assistant.toolwindow.shell

import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.ui.content.Content
import javax.swing.JComponent

internal object ToolWindowPrimaryContentPresentation {
    fun configure(toolWindowComponent: JComponent, content: Content) {
        toolWindowComponent.putClientProperty(ToolWindowContentUi.HIDE_ID_LABEL, true)
        content.setDisplayName("")
        content.setTabName("")
        content.setToolwindowTitle("")
    }
}
