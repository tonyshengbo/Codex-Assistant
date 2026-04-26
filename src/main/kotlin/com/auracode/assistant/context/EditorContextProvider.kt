package com.auracode.assistant.context

import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.auracode.assistant.toolwindow.submission.FocusedContextSnapshot

@Service(Service.Level.PROJECT)
class EditorContextProvider(private val project: Project) {

    fun getOpenFiles(): List<String> {
        val editorManager = FileEditorManager.getInstance(project)
        return editorManager.openFiles.mapNotNull { it.path }
    }

    fun getCurrentFile(): String? {
        val editorManager = FileEditorManager.getInstance(project)
        return editorManager.selectedFiles.firstOrNull()?.path
    }

    internal fun getFocusedContextSnapshot(): FocusedContextSnapshot? {
        val editorManager = FileEditorManager.getInstance(project)
        val editor = editorManager.selectedTextEditor
        val editorPath = editor?.let { FileDocumentManager.getInstance().getFile(it.document)?.path }
        val fallbackPath = editorManager.selectedFiles.firstOrNull()?.path
        val path = editorPath ?: fallbackPath ?: return null
        return editor?.toFocusedContextSnapshot(path) ?: FocusedContextSnapshot(path = path)
    }

    fun getSelectedText(): String? {
        val editorManager = FileEditorManager.getInstance(project)
        val editor = editorManager.selectedTextEditor ?: return null
        return editor.selectionModel.selectedText
    }

    private fun Editor.toFocusedContextSnapshot(path: String): FocusedContextSnapshot {
        val selectionModel = selectionModel
        val selectedText = selectionModel.selectedText
        val hasSelection = !selectedText.isNullOrBlank()
        if (!hasSelection) {
            return FocusedContextSnapshot(path = path)
        }
        val startOffset = selectionModel.selectionStart
        val endOffset = selectionModel.selectionEnd
        if (endOffset <= startOffset) {
            return FocusedContextSnapshot(path = path)
        }
        val startLine = document.getLineNumber(startOffset) + 1
        val inclusiveEndOffset = (endOffset - 1).coerceAtLeast(startOffset)
        val endLine = document.getLineNumber(inclusiveEndOffset) + 1
        return FocusedContextSnapshot(
            path = path,
            selectedText = selectedText,
            startLine = startLine,
            endLine = endLine,
        )
    }

    companion object {
        fun getInstance(project: Project): EditorContextProvider =
            project.getService(EditorContextProvider::class.java)
    }
}
