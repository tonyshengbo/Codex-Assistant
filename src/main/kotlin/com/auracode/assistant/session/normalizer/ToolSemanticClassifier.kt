package com.auracode.assistant.session.normalizer

import com.auracode.assistant.session.kernel.SessionToolKind

/**
 * Classifies provider-specific tool names into shared session tool categories.
 */
internal class ToolSemanticClassifier {
    /** Classifies one Claude tool name into a shared tool kind. */
    fun classifyClaudeTool(toolName: String): SessionToolKind {
        return when (toolName.trim().lowercase()) {
            "read" -> SessionToolKind.FILE_READ
            "write" -> SessionToolKind.FILE_WRITE
            "edit" -> SessionToolKind.FILE_EDIT
            "todowrite" -> SessionToolKind.PLAN_UPDATE
            else -> SessionToolKind.GENERIC
        }
    }

    /** Classifies one unified tool item name into a shared tool kind. */
    fun classifyProviderTool(toolName: String?): SessionToolKind {
        return when (toolName?.trim()?.lowercase()) {
            "read" -> SessionToolKind.FILE_READ
            "write" -> SessionToolKind.FILE_WRITE
            "edit" -> SessionToolKind.FILE_EDIT
            "todowrite", "plan update" -> SessionToolKind.PLAN_UPDATE
            else -> SessionToolKind.GENERIC
        }
    }
}
