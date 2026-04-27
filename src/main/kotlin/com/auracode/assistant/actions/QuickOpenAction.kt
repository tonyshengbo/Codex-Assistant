package com.auracode.assistant.actions

import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shared.AssistantUiText
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

class QuickOpenAction : AnAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.text = AuraCodeBundle.message("action.open.text")
        e.presentation.description = AuraCodeBundle.message("action.open.description")
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        toolWindow?.show()
    }

    companion object {
        val TOOL_WINDOW_ID: String = AssistantUiText.PRIMARY_TOOL_WINDOW_ID
    }
}
