package com.auracode.assistant.provider.codex

import com.auracode.assistant.diff.FileChangeMetrics
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderFileChange
import com.auracode.assistant.protocol.ProviderToolUserInputAnswerDraft
import com.auracode.assistant.protocol.ProviderToolUserInputOption
import com.auracode.assistant.protocol.ProviderToolUserInputPrompt
import com.auracode.assistant.protocol.ProviderToolUserInputQuestion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Contains shared support helpers used by the Codex app-server integration. */
internal data class CodexRuntimeLaunchConfig(
    val command: List<String>,
    val environmentOverrides: Map<String, String>,
)

/**
 * Represents the sandbox mode Aura sends to the Codex app-server.
 *
 * Thread creation uses the kebab-case CLI-style enum, while turn execution uses
 * the structured `sandboxPolicy` object required by the app-server protocol.
 */
internal enum class CodexRuntimeSandboxMode {
    WORKSPACE_WRITE,
    DANGER_FULL_ACCESS,
}

/**
 * Keeps the plugin on the least restrictive app-server sandbox by default.
 *
 * This is intentionally hard-coded instead of being user-configurable because
 * the current rollout only changes the provider's default request payload.
 */
internal val DEFAULT_APP_SERVER_SANDBOX_MODE: CodexRuntimeSandboxMode =
    CodexRuntimeSandboxMode.DANGER_FULL_ACCESS

internal const val APP_SERVER_HANDSHAKE_TIMEOUT_MS: Long = 10000L

/** Builds the thread-level sandbox mode string expected by `thread/start`. */
internal fun buildThreadSandboxMode(
    mode: CodexRuntimeSandboxMode = DEFAULT_APP_SERVER_SANDBOX_MODE,
): String {
    return when (mode) {
        CodexRuntimeSandboxMode.WORKSPACE_WRITE -> "workspace-write"
        CodexRuntimeSandboxMode.DANGER_FULL_ACCESS -> "danger-full-access"
    }
}

/**
 * Builds the turn-level sandbox policy payload expected by `turn/start`.
 *
 * `dangerFullAccess` must be sent as its own protocol shape and must not carry
 * `workspaceWrite`-only fields such as `writableRoots`.
 */
internal fun buildTurnSandboxPolicy(
    workingDirectory: String,
    mode: CodexRuntimeSandboxMode = DEFAULT_APP_SERVER_SANDBOX_MODE,
): JsonObject {
    return when (mode) {
        CodexRuntimeSandboxMode.WORKSPACE_WRITE -> buildJsonObject {
            put("type", "workspaceWrite")
            put("networkAccess", false)
            put("writableRoots", buildJsonArray {
                add(JsonPrimitive(workingDirectory))
            })
        }

        CodexRuntimeSandboxMode.DANGER_FULL_ACCESS -> buildJsonObject {
            put("type", "dangerFullAccess")
        }
    }
}

internal fun buildCodexRuntimeLaunchConfig(
    binary: String,
    environmentOverrides: Map<String, String> = emptyMap(),
): CodexRuntimeLaunchConfig {
    val command = listOf(binary, "app-server")
    return CodexRuntimeLaunchConfig(
        command = command,
        environmentOverrides = environmentOverrides,
    )
}

/** Describes one skill enablement write Aura should synchronize to the app-server. */
internal data class CodexSkillConfigSyncEntry(
    val name: String,
    val enabled: Boolean,
)

/**
 * Builds the app-server payload for `skills/config/write` by skill name.
 *
 * The current app-server rejects an explicit `path: null` during request
 * decoding, so Aura must omit the path selector entirely when syncing by name.
 */
internal fun buildSkillConfigWriteParams(entry: CodexSkillConfigSyncEntry): JsonObject {
    return buildJsonObject {
        put("name", entry.name)
        put("enabled", entry.enabled)
    }
}


internal fun buildPrompt(request: AgentRequest): String {
    val contextSnippets = request.contextFiles.filter { !it.content.isNullOrBlank() }
    val contextFilesByPath = request.contextFiles.filter { it.content.isNullOrBlank() }
    val fileBlock = if (request.fileAttachments.isEmpty()) {
        ""
    } else {
        request.fileAttachments.joinToString("\n") { "- ${it.path}" }
    }
    return buildString {
        append(request.prompt)
        if (contextSnippets.isNotEmpty()) {
            append("\n\nContext snippets:\n")
            append(
                contextSnippets.joinToString("\n\n") { file ->
                    buildString {
                        append("FILE: ")
                        append(file.path)
                        append('\n')
                        append(file.content.orEmpty())
                    }
                },
            )
        }
        if (contextFilesByPath.isNotEmpty()) {
            append("\n\nContext files (read by path):\n")
            append(contextFilesByPath.joinToString("\n") { "- ${it.path}" })
        }
        if (request.systemInstructions.isNotEmpty()) {
            append("\n\n##Agent Role and Instructions\n")
            append(request.systemInstructions.joinToString("\n"))
        }
        if (fileBlock.isNotBlank()) {
            append("\n\nAttached non-text files (read by path):\n")
            append(fileBlock)
        }
    }.trim()
}

internal fun buildCollaborationModePayloadForMode(
    mode: AgentCollaborationMode,
    model: String?,
    reasoningEffort: String?,
): JsonObject? {
    return buildJsonObject {
        put(
            "mode",
            when (mode) {
                AgentCollaborationMode.DEFAULT -> "default"
                AgentCollaborationMode.PLAN -> "plan"
            },
        )
        putJsonObject("settings") {
            put("model", model?.takeIf { it.isNotBlank() } ?: "")
            when {
                reasoningEffort.isNullOrBlank() -> put("reasoning_effort", JsonNull)
                else -> put("reasoning_effort", reasoningEffort)
            }
            put("developer_instructions", JsonNull)
        }
    }
}

internal fun extractFileChangeKind(change: JsonObject): String {
    val rawKind = change["kind"]
    return when (rawKind) {
        is JsonObject -> rawKind.string("type").orEmpty()
        is JsonPrimitive -> rawKind.contentOrNull.orEmpty()
        else -> ""
    }.ifBlank { "update" }
}

/**
 * Parses structured file-change approval payloads into shared unified change models.
 */
internal fun buildApprovalFileChanges(
    params: JsonObject,
    sourceId: String,
): List<ProviderFileChange> {
    val fileChanges = params.objectValue("fileChanges") ?: params.objectValue("file_changes") ?: return emptyList()
    return fileChanges.entries.mapIndexedNotNull { index, (path, changeElement) ->
        path.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
        val change = changeElement as? JsonObject ?: JsonObject(emptyMap())
        val oldContent = change.string("oldContent") ?: change.string("old_content")
        val newContent = change.string("newContent") ?: change.string("new_content") ?: change.string("content")
        val computedMetrics = FileChangeMetrics.fromContents(
            oldContent = oldContent,
            newContent = newContent,
        )
        ProviderFileChange(
            sourceScopedId = "$sourceId:$index",
            path = path,
            kind = extractFileChangeKind(change),
            addedLines = change.intOrNull("addedLines", "added_lines") ?: computedMetrics?.addedLines,
            deletedLines = change.intOrNull("deletedLines", "deleted_lines") ?: computedMetrics?.deletedLines,
            unifiedDiff = change.string("diff"),
            oldContent = oldContent,
            newContent = newContent,
        )
    }
}

internal fun buildServerRequestResponse(
    serverRequestId: JsonElement,
    result: JsonObject,
): JsonObject {
    return buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", serverRequestId)
        put("result", result)
    }
}

internal fun buildRequestUserInputResponse(serverRequestId: JsonElement): JsonObject {
    return buildServerRequestResponse(
        serverRequestId = serverRequestId,
        result = buildJsonObject {
            put("decision", "cancel")
        },
    )
}

internal fun buildToolUserInputPrompt(
    serverRequestId: JsonElement,
    params: JsonObject,
): ProviderToolUserInputPrompt {
    return ProviderToolUserInputPrompt(
        requestId = jsonRpcIdKey(serverRequestId).orEmpty(),
        threadId = params.string("threadId").orEmpty(),
        turnId = params.string("turnId"),
        itemId = params.string("itemId").orEmpty(),
        questions = params.arrayValue("questions")
            ?.mapNotNull { element -> element as? JsonObject }
            ?.map { question ->
                ProviderToolUserInputQuestion(
                    id = question.string("id").orEmpty(),
                    header = question.string("header").orEmpty(),
                    question = question.string("question").orEmpty(),
                    options = question.arrayValue("options")
                        ?.mapNotNull { option -> option as? JsonObject }
                        ?.map { option ->
                            ProviderToolUserInputOption(
                                label = option.string("label").orEmpty(),
                                description = option.string("description").orEmpty(),
                            )
                        }
                        .orEmpty(),
                    isOther = question["isOther"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                    isSecret = question["isSecret"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                )
            }
            .orEmpty(),
    )
}

internal fun buildToolUserInputResponse(
    serverRequestId: JsonElement,
    submission: Map<String, ProviderToolUserInputAnswerDraft>,
): JsonObject {
    return buildServerRequestResponse(
        serverRequestId = serverRequestId,
        result = buildJsonObject {
            putJsonObject("answers") {
                submission.forEach { (questionId, answerDraft) ->
                    putJsonObject(questionId) {
                        put(
                            "answers",
                            buildJsonArray {
                                answerDraft.answers.forEach { answer ->
                                    add(JsonPrimitive(answer))
                                }
                            },
                        )
                    }
                }
            }
        },
    )
}

internal fun jsonRpcIdKey(value: JsonElement): String? {
    val primitive = value as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.takeIf { it.isNotBlank() }
}
