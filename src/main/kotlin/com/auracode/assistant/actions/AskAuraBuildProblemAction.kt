package com.auracode.assistant.actions

import com.auracode.assistant.integration.build.BuildErrorAuraRequest
import com.auracode.assistant.integration.build.BuildErrorPromptFactory
import com.auracode.assistant.integration.build.BuildErrorSnapshot
import com.auracode.assistant.integration.build.BuildErrorSnapshotService
import com.auracode.assistant.toolwindow.external.ToolWindowExternalRequestBridge
import com.auracode.assistant.toolwindow.shared.ToolWindowUiText
import com.intellij.analysis.problemsView.Problem
import com.intellij.analysis.problemsView.toolWindow.ProblemNode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import javax.swing.JTree

/**
 * Sends build and compile problems from the Problems view directly to Aura.
 */
class AskAuraBuildProblemAction : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = selectedProblemNode(e)?.let { isBuildProblem(it.problem) } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val node = selectedProblemNode(e) ?: return
        val problem = node.problem
        if (!isBuildProblem(problem)) return

        val project = problem.provider.project
        val snapshot = BuildErrorSnapshot(
            title = problem.text,
            detail = problem.description?.ifBlank { problem.text } ?: problem.text,
            source = problem.provider.javaClass.simpleName.ifBlank { "Build" },
            filePath = node.file.path,
            line = node.line.takeIf { it >= 0 },
            column = node.column.takeIf { it >= 0 },
        )
        project.getService(BuildErrorSnapshotService::class.java).remember(snapshot)
        project.getService(ToolWindowExternalRequestBridge::class.java).submitBuildErrorRequest(
            BuildErrorAuraRequest(
                snapshot = snapshot,
                prompt = BuildErrorPromptFactory.create(snapshot),
            ),
        )
        ToolWindowManager.getInstance(project).getToolWindow(ToolWindowUiText.PRIMARY_TOOL_WINDOW_ID)?.show()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /**
     * Mirrors the official Problems view action pattern by reading the selected tree node
     * from the popup context component instead of relying on internal helper classes.
     */
    private fun selectedProblemNode(event: AnActionEvent): ProblemNode? {
        val tree = event.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) as? JTree ?: return null
        return tree.selectionPath?.lastPathComponent as? ProblemNode
    }

    private fun isBuildProblem(problem: Problem): Boolean {
        return problem.provider.javaClass.name.contains("BuildViewProblemsService")
    }
}
