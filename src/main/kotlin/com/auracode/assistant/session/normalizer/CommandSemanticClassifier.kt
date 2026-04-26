package com.auracode.assistant.session.normalizer

import com.auracode.assistant.session.kernel.SessionCommandKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Classifies structured command-like payloads into shared session command categories.
 */
internal class CommandSemanticClassifier(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Classifies one command-like payload into a shared command kind. */
    fun classify(
        command: String?,
        toolName: String? = null,
        filePath: String? = null,
    ): SessionCommandKind {
        return when {
            !filePath.isNullOrBlank() -> SessionCommandKind.READ_FILE
            toolName?.trim()?.equals("read", ignoreCase = true) == true -> SessionCommandKind.READ_FILE
            command?.trim()?.startsWith("cat ") == true -> SessionCommandKind.READ_FILE
            else -> SessionCommandKind.SHELL
        }
    }

    /** Builds a normalized command descriptor from one Claude tool-call payload. */
    fun buildClaudeToolCommand(
        toolName: String,
        inputJson: String,
    ): CommandSemanticDescriptor? {
        val normalizedTool = toolName.trim()
        if (!normalizedTool.equals("read", ignoreCase = true)) {
            return null
        }
        val filePath = runCatching {
            json.parseToJsonElement(inputJson).jsonObject["file_path"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return CommandSemanticDescriptor(
            kind = SessionCommandKind.READ_FILE,
            command = "cat $filePath",
            cwd = null,
        )
    }
}

/**
 * Stores the normalized command information produced by a classifier.
 */
internal data class CommandSemanticDescriptor(
    val kind: SessionCommandKind,
    val command: String?,
    val cwd: String?,
)
