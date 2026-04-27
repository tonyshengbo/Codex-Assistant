package com.auracode.assistant.provider.codex

import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.settings.skills.RuntimeSkillRecord
import com.auracode.assistant.settings.skills.SkillSelector
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Exposes Codex app-server features through one shared high-level client. */
internal class CodexRuntimeClient(
    private val session: CodexRuntimeSession,
    private val diagnosticLogger: (String) -> Unit,
) {
    /** Starts or resumes the target thread and returns its thread id. */
    suspend fun ensureThread(request: AgentRequest): String {
        val existingThreadId = request.remoteConversationId?.trim().orEmpty()
        val executionProfile = resolveCodexRuntimeExecutionProfile(request.approvalMode)
        val result = if (existingThreadId.isBlank()) {
            session.request(
                method = "thread/start",
                params = buildJsonObject {
                    request.model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
                    put("cwd", request.workingDirectory)
                    put("approvalPolicy", executionProfile.approvalPolicy)
                    put("sandbox", buildThreadSandboxMode(executionProfile.sandboxMode))
                },
            )
        } else {
            session.request(
                method = "thread/resume",
                params = buildJsonObject {
                    put("threadId", existingThreadId)
                    put("cwd", request.workingDirectory)
                    request.model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
                    put("approvalPolicy", executionProfile.approvalPolicy)
                    put("sandbox", buildThreadSandboxMode(executionProfile.sandboxMode))
                },
            )
        }
        val threadId = result.objectValue("thread")?.string("id") ?: existingThreadId
        if (threadId.isBlank()) {
            error("App-server did not return a thread id.")
        }
        return threadId
    }

    /** Starts one conversation turn and returns the server-issued turn id if present. */
    suspend fun startTurn(request: AgentRequest, threadId: String): String? {
        val executionProfile = resolveCodexRuntimeExecutionProfile(request.approvalMode)
        val result = session.request(
            method = "turn/start",
            params = buildJsonObject {
                put("threadId", threadId)
                put("input", buildInputPayload(request))
                request.model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
                request.reasoningEffort?.takeIf { it.isNotBlank() }?.let { put("effort", it) }
                buildCollaborationModePayload(request.collaborationMode, request.model, request.reasoningEffort)
                    ?.let { put("collaborationMode", it) }
                put("cwd", request.workingDirectory)
                put("approvalPolicy", executionProfile.approvalPolicy)
                put("sandboxPolicy", buildTurnSandboxPolicy(request.workingDirectory, executionProfile.sandboxMode))
            },
        )
        return result.objectValue("turn")?.string("id")
    }

    /** Reads all historical turns for the supplied thread id. */
    suspend fun readThreadTurns(threadId: String): List<JsonObject> {
        val result = session.request(
            method = "thread/read",
            params = buildJsonObject {
                put("threadId", threadId)
                put("includeTurns", true)
            },
        )
        return result.objectValue("thread")
            ?.let { thread -> thread["turns"] as? JsonArray }
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
    }

    /** Reads remote thread summaries using the server pagination contract. */
    suspend fun readThreadSummaries(
        cursor: String?,
        limit: Int,
        cwd: String?,
        searchTerm: String?,
    ): Pair<List<JsonObject>, String?> {
        val result = session.request(
            method = "thread/list",
            params = buildJsonObject {
                cursor?.takeIf { it.isNotBlank() }?.let { put("cursor", it) }
                if (limit > 0) {
                    put("limit", JsonPrimitive(limit))
                }
                put("sortKey", "updated_at")
                cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
                searchTerm?.takeIf { it.isNotBlank() }?.let { put("searchTerm", it) }
            },
        )
        val threads = (result["data"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        return threads to result.string("nextCursor")
    }

    /** Lists runtime skills visible to the supplied working directory. */
    suspend fun listSkills(
        cwd: String,
        forceReload: Boolean,
    ): List<RuntimeSkillRecord> {
        val result = session.request(
            method = "skills/list",
            params = buildJsonObject {
                put("cwds", buildJsonArray {
                    add(JsonPrimitive(cwd))
                })
                if (forceReload) {
                    put("forceReload", true)
                }
            },
        )
        val entry = result.arrayValue("data")
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull()
            ?: return emptyList()
        return entry.arrayValue("skills")
            ?.mapNotNull { raw ->
                val skill = raw as? JsonObject ?: return@mapNotNull null
                RuntimeSkillRecord(
                    name = skill.string("name").orEmpty(),
                    description = skill.string("description").orEmpty().ifBlank { skill.string("name").orEmpty() },
                    enabled = skill["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
                    path = skill.string("path").orEmpty(),
                    scopeLabel = skill.string("scope").orEmpty().ifBlank { "user" },
                )
            }
            .orEmpty()
    }

    /** Updates one skill enablement entry using the Codex runtime config API. */
    suspend fun setSkillEnabled(
        selector: SkillSelector,
        enabled: Boolean,
    ) {
        when (selector) {
            is SkillSelector.ByPath -> {
                session.request(
                    method = "skills/config/write",
                    params = buildJsonObject {
                        put("path", selector.path)
                        put("enabled", enabled)
                    },
                )
            }
            is SkillSelector.ByName -> {
                session.request(
                    method = "skills/config/write",
                    params = buildJsonObject {
                        put("name", selector.name)
                        put("enabled", enabled)
                    },
                )
            }
        }
    }

    private fun buildCollaborationModePayload(
        mode: AgentCollaborationMode,
        model: String?,
        reasoningEffort: String?,
    ): JsonObject? {
        return buildCollaborationModePayloadForMode(
            mode = mode,
            model = model,
            reasoningEffort = reasoningEffort,
        )
    }

    private fun buildInputPayload(request: AgentRequest): JsonArray {
        val promptText = buildPrompt(request)
        return buildJsonArray {
            if (promptText.isNotBlank()) {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", promptText)
                    },
                )
            }
            request.imageAttachments.forEach { image ->
                add(
                    buildJsonObject {
                        put("type", "localImage")
                        put("path", image.path)
                    },
                )
            }
        }
    }
}
