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
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Sends the current file to Aura for structural explanation.
 */
class AskAuraCurrentFileAction : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.text = AuraCodeBundle.message("action.ask.aura.current.file.text")
        e.presentation.description = AuraCodeBundle.message("action.ask.aura.current.file.description")
        e.presentation.isEnabledAndVisible = e.project != null && resolveFile(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = resolveFile(e) ?: return
        val request = IdeExternalRequest(
            source = IdeRequestSource.CURRENT_FILE,
            title = "Explain Current File",
            prompt = IdePromptFactory.explainFile(file.path),
            contextFiles = IdeContextFiles.fromFilePath(file.path),
        )
        project.getService(ExternalRequestRouter::class.java).submitRequest(request)
        ToolWindowManager.getInstance(project).getToolWindow(AssistantUiText.PRIMARY_TOOL_WINDOW_ID)?.show()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private fun resolveFile(e: AnActionEvent): VirtualFile? {
        val direct = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (direct != null && !direct.isDirectory) return direct
        val project = e.project ?: return null
        val path = EditorContextProvider.getInstance(project).getCurrentFile() ?: return null
        return com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path)?.takeUnless { it.isDirectory }
    }
}
