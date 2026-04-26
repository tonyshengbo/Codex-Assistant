package com.auracode.assistant.toolwindow.shell

import com.auracode.assistant.i18n.AuraCodeBundle
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel

class AgentToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val toolWindowTitle = AuraCodeBundle.message("plugin.name")
        toolWindow.setTitle(toolWindowTitle)
        toolWindow.setStripeTitle(toolWindowTitle)
        val panel = try {
            ComposeToolWindowPanel(project, toolWindow)
        } catch (t: Throwable) {
            LOG.error("Failed to create Aura Code panel", t)
            buildFallbackPanel(t)
        }
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        ToolWindowPrimaryContentPresentation.configure(toolWindow.component, content)
        if (panel is Disposable) {
            content.setDisposer(panel)
        }
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    private fun buildFallbackPanel(t: Throwable): JPanel {
        val area = JBTextArea()
        area.isEditable = false
        area.text = buildString {
            appendLine("Aura Code failed to initialize.")
            appendLine()
            appendLine("${t::class.java.simpleName}: ${t.message}")
            appendLine()
            appendLine("Check IDE logs: Help -> Show Log in Finder/Explorer")
        }
        val panel = JPanel(BorderLayout())
        panel.add(JBScrollPane(area), BorderLayout.CENTER)
        return panel
    }

    companion object {
        private val LOG = Logger.getInstance(AgentToolWindowFactory::class.java)
    }
}
