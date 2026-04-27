package com.auracode.assistant.actions

import com.auracode.assistant.context.EditorContextProvider
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.integration.ide.IdeContextFiles
import com.auracode.assistant.integration.ide.IdeExternalRequest
import com.auracode.assistant.integration.ide.IdePromptFactory
import com.auracode.assistant.integration.ide.IdeRequestSource
import com.auracode.assistant.toolwindow.external.ExternalRequestRouter
import com.auracode.assistant.toolwindow.shared.AssistantUiText
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Sends the current editor selection to Aura for explanation.
 */
class AskAuraSelectedCodeAction : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        val snapshot = project?.let { EditorContextProvider.getInstance(it).getFocusedContextSnapshot() }
        val hasSelection = !snapshot?.selectedText.isNullOrBlank()
        e.presentation.text = AuraCodeBundle.message("action.ask.aura.selected.code.text")
        e.presentation.description = AuraCodeBundle.message("action.ask.aura.selected.code.description")
        e.presentation.isEnabledAndVisible = project != null && hasSelection
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val snapshot = EditorContextProvider.getInstance(project).getFocusedContextSnapshot() ?: return
        val selectedText = snapshot.selectedText?.takeIf { it.isNotBlank() } ?: return
        val request = IdeExternalRequest(
            source = IdeRequestSource.EDITOR_SELECTION,
            title = "Explain Selected Code",
            prompt = IdePromptFactory.explainSelection(
                filePath = snapshot.path,
                startLine = snapshot.startLine,
                endLine = snapshot.endLine,
            ),
            contextFiles = IdeContextFiles.fromFocusedSnapshot(
                snapshot.copy(selectedText = selectedText),
            ),
        )
        project.getService(ExternalRequestRouter::class.java).submitRequest(request)
        ToolWindowManager.getInstance(project).getToolWindow(AssistantUiText.PRIMARY_TOOL_WINDOW_ID)?.show()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
