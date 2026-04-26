package com.auracode.assistant.session.normalizer

import com.auracode.assistant.protocol.UnifiedFileChange
import com.auracode.assistant.session.kernel.SessionFileChange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses provider-specific file-change payloads into shared session file-change models.
 */
internal class FileChangeSemanticParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Parses one Claude file-mutation tool payload into shared file-change models. */
    fun parseClaudeTool(
        toolName: String,
        inputJson: String,
        outputText: String?,
    ): List<SessionFileChange> {
        val payload = runCatching { json.parseToJsonElement(inputJson).jsonObject }.getOrNull() ?: return emptyList()
        val path = payload["file_path"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return emptyList()
        val kind = when (toolName.trim().lowercase()) {
            "write" -> if (outputText.orEmpty().contains("updated", ignoreCase = true)) "update" else "create"
            "edit" -> "update"
            else -> return emptyList()
        }
        return listOf(
            SessionFileChange(
                path = path,
                kind = kind,
                summary = "$kind $path",
            ),
        )
    }

    /** Parses unified file-change objects into shared session file-change models. */
    fun parseUnifiedFileChanges(changes: List<UnifiedFileChange>): List<SessionFileChange> {
        return changes.map { change ->
            SessionFileChange(
                path = change.path,
                kind = change.kind,
                summary = "${change.kind} ${change.path}",
            )
        }
    }

    /** Parses fallback plain-text file-change summaries into shared file-change models. */
    fun parseSummaryBody(body: String): List<SessionFileChange> {
        return body.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { line ->
                val splitIndex = line.indexOf(' ')
                val kind = if (splitIndex > 0) line.substring(0, splitIndex).trim() else "update"
                val path = if (splitIndex > 0 && splitIndex < line.lastIndex) {
                    line.substring(splitIndex + 1).trim()
                } else {
                    line
                }
                SessionFileChange(
                    path = path,
                    kind = kind,
                    summary = "$kind $path",
                )
            }
            .toList()
    }
}
