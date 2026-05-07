package com.auracode.assistant.provider.claude

import com.auracode.assistant.protocol.ProviderToolUserInputAnswerDraft
import com.auracode.assistant.toolwindow.execution.ApprovalAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class ClaudeEncodedControlResponse(
    val json: String,
    val includesUpdatedPermissions: Boolean = false,
)

internal class ClaudeControlResponseEncoder(
    private val json: Json = Json,
) {
    /** Encodes a Claude tool approval response for the stdio permission prompt channel. */
    fun encodeApproval(
        approvalRequestId: String,
        decision: ApprovalAction,
        permissionSuggestions: List<String> = emptyList(),
    ): ClaudeEncodedControlResponse {
        val behavior = when (decision) {
            ApprovalAction.ALLOW, ApprovalAction.ALLOW_FOR_SESSION -> "allow"
            ApprovalAction.REJECT -> "deny"
        }
        val updatedPermissions = when (decision) {
            ApprovalAction.ALLOW_FOR_SESSION -> parsePermissionSuggestions(permissionSuggestions)
            ApprovalAction.ALLOW, ApprovalAction.REJECT -> emptyList()
        }
        val response = buildJsonObject {
            put("type", "control_response")
            put("request_id", approvalRequestId)
            put("response", buildJsonObject {
                put("behavior", behavior)
                if (behavior == "allow" && updatedPermissions.isNotEmpty()) {
                    put("updatedPermissions", JsonArray(updatedPermissions))
                }
            })
        }
        return ClaudeEncodedControlResponse(
            json = response.toString(),
            includesUpdatedPermissions = updatedPermissions.isNotEmpty(),
        )
    }

    /** Encodes answers for Claude's AskUserQuestion control request. */
    fun encodeToolUserInput(
        controlRequestId: String,
        answers: Map<String, ProviderToolUserInputAnswerDraft>,
    ): ClaudeEncodedControlResponse {
        val response = buildJsonObject {
            put("type", "control_response")
            put("request_id", controlRequestId)
            put("response", buildJsonObject {
                put("behavior", "allow")
                put("answers", buildJsonObject {
                    answers.forEach { (questionId, draft) ->
                        put(questionId, draft.answers.firstOrNull().orEmpty())
                    }
                })
            })
        }
        return ClaudeEncodedControlResponse(json = response.toString())
    }

    /** Parses Claude-provided permission suggestions and drops malformed entries. */
    private fun parsePermissionSuggestions(permissionSuggestions: List<String>): List<JsonObject> {
        return permissionSuggestions.mapNotNull { raw ->
            runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        }
    }
}
