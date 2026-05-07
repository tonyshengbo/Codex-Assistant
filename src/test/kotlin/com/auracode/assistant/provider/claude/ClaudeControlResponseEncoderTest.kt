package com.auracode.assistant.provider.claude

import com.auracode.assistant.protocol.ProviderToolUserInputAnswerDraft
import com.auracode.assistant.toolwindow.execution.ApprovalAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeControlResponseEncoderTest {
    private val encoder = ClaudeControlResponseEncoder()

    @Test
    /** Verifies that a one-time approval uses the smallest stable Claude control response. */
    fun `approval allow omits updated permissions`() {
        val encoded = encoder.encodeApproval(
            approvalRequestId = "approval-1",
            decision = ApprovalAction.ALLOW,
            permissionSuggestions = listOf("""{"tool":"Bash"}"""),
        )

        val root = parse(encoded.json)
        val response = root["response"]!!.jsonObject
        assertEquals("control_response", root["type"].toString().trim('"'))
        assertEquals("approval-1", root["request_id"].toString().trim('"'))
        assertEquals("allow", response["behavior"].toString().trim('"'))
        assertFalse(response.containsKey("updatedPermissions"))
        assertFalse(encoded.includesUpdatedPermissions)
    }

    @Test
    /** Verifies that a rejection sends only the deny behavior expected by Claude. */
    fun `approval reject encodes deny without permissions`() {
        val encoded = encoder.encodeApproval(
            approvalRequestId = "approval-1",
            decision = ApprovalAction.REJECT,
            permissionSuggestions = listOf("""{"tool":"Bash"}"""),
        )

        val response = parse(encoded.json)["response"]!!.jsonObject
        assertEquals("deny", response["behavior"].toString().trim('"'))
        assertFalse(response.containsKey("updatedPermissions"))
        assertFalse(encoded.includesUpdatedPermissions)
    }

    @Test
    /** Verifies that session approval keeps only valid Claude permission suggestions. */
    fun `approval for session includes valid updated permissions`() {
        val encoded = encoder.encodeApproval(
            approvalRequestId = "approval-1",
            decision = ApprovalAction.ALLOW_FOR_SESSION,
            permissionSuggestions = listOf("""{"tool":"Bash","rule":"allow"}""", "not-json"),
        )

        val response = parse(encoded.json)["response"]!!.jsonObject
        assertEquals("allow", response["behavior"].toString().trim('"'))
        assertTrue(response.containsKey("updatedPermissions"))
        assertTrue(encoded.includesUpdatedPermissions)
    }

    @Test
    /** Verifies that malformed session suggestions fall back to a one-time allow response. */
    fun `approval for session falls back when suggestions are invalid`() {
        val encoded = encoder.encodeApproval(
            approvalRequestId = "approval-1",
            decision = ApprovalAction.ALLOW_FOR_SESSION,
            permissionSuggestions = listOf("not-json"),
        )

        val response = parse(encoded.json)["response"]!!.jsonObject
        assertEquals("allow", response["behavior"].toString().trim('"'))
        assertFalse(response.containsKey("updatedPermissions"))
        assertFalse(encoded.includesUpdatedPermissions)
    }

    @Test
    /** Verifies that AskUserQuestion answers keep the current Claude control response shape. */
    fun `tool user input encodes answers`() {
        val encoded = encoder.encodeToolUserInput(
            controlRequestId = "question-1",
            answers = mapOf("choice" to ProviderToolUserInputAnswerDraft(answers = listOf("A"))),
        )

        val response = parse(encoded.json)["response"]!!.jsonObject
        assertEquals("allow", response["behavior"].toString().trim('"'))
        assertEquals("A", response["answers"]!!.jsonObject["choice"].toString().trim('"'))
    }

    private fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
}
