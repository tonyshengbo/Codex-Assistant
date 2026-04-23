package com.auracode.assistant.provider.codex

import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.UnifiedApprovalRequestKind
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.toolwindow.approval.ApprovalAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证桥接层对旧审批别名的兼容行为，避免被误判成可自动接受的普通请求。
 */
class CodexAppServerConversationBridgeTest {
    @Test
    fun `legacy exec command approval request emits approval event`() = runBlocking {
        val session = RecordingBridgeSession()
        val approvalEvent = CompletableDeferred<UnifiedEvent.ApprovalRequested>()
        val active = ActiveRequest(process = NoOpProcess(), threadId = "thread-1", turnId = "turn-1")
        val request = AgentRequest(
            engineId = "codex",
            prompt = "hello",
            contextFiles = emptyList(),
            workingDirectory = "/tmp/project",
        )
        val bridge = CodexAppServerConversationBridge(
            request = request,
            active = active,
            session = session,
            client = CodexAppServerClient(session = session, diagnosticLogger = {}),
            diagnosticLogger = {},
            emitUnified = { event ->
                if (event is UnifiedEvent.ApprovalRequested && !approvalEvent.isCompleted) {
                    approvalEvent.complete(event)
                }
            },
        )

        bridge.handleServerRequest(
            id = JsonPrimitive("7"),
            method = "execCommandApproval",
            params = buildJsonObject {
                put("conversationId", "thread-1")
                put("callId", "call-7")
                put("approvalId", "approval-7")
                put("cwd", "/tmp/project")
                put("reason", "Need to run a command")
                put(
                    "command",
                    buildJsonArray {
                        add(JsonPrimitive("echo"))
                        add(JsonPrimitive("hello"))
                    },
                )
            },
        )

        val approval = withTimeout(1_000) { approvalEvent.await() }.request
        assertEquals(UnifiedApprovalRequestKind.COMMAND, approval.kind)
        assertEquals("echo hello", approval.command)
        assertEquals("/tmp/project", approval.cwd)

        assertTrue(active.submitApprovalDecision("7", ApprovalAction.ALLOW_FOR_SESSION))

        val response = withTimeout(1_000) { session.response.await() }
        assertEquals("approved_for_session", response.getValue("decision").jsonPrimitive.content)
    }

    @Test
    fun `legacy apply patch approval request emits approval event`() = runBlocking {
        val session = RecordingBridgeSession()
        val approvalEvent = CompletableDeferred<UnifiedEvent.ApprovalRequested>()
        val active = ActiveRequest(process = NoOpProcess(), threadId = "thread-1", turnId = "turn-1")
        val request = AgentRequest(
            engineId = "codex",
            prompt = "hello",
            contextFiles = emptyList(),
            workingDirectory = "/tmp/project",
        )
        val bridge = CodexAppServerConversationBridge(
            request = request,
            active = active,
            session = session,
            client = CodexAppServerClient(session = session, diagnosticLogger = {}),
            diagnosticLogger = {},
            emitUnified = { event ->
                if (event is UnifiedEvent.ApprovalRequested && !approvalEvent.isCompleted) {
                    approvalEvent.complete(event)
                }
            },
        )

        bridge.handleServerRequest(
            id = JsonPrimitive("8"),
            method = "applyPatchApproval",
            params = buildJsonObject {
                put("conversationId", "thread-1")
                put("callId", "call-8")
                put("reason", "Need to apply a patch")
                putJsonObject("fileChanges") {
                    putJsonObject("/tmp/project/App.kt") {
                        put("kind", "update")
                    }
                }
            },
        )

        val approval = withTimeout(1_000) { approvalEvent.await() }.request
        assertEquals(UnifiedApprovalRequestKind.FILE_CHANGE, approval.kind)
        assertEquals("Apply file changes", approval.title)

        assertTrue(active.submitApprovalDecision("8", ApprovalAction.ALLOW))

        val response = withTimeout(1_000) { session.response.await() }
        assertEquals("approved", response.getValue("decision").jsonPrimitive.content)
    }
}

/**
 * 记录桥接层回写给 app-server 的审批响应。
 */
private class RecordingBridgeSession : CodexAppServerSession {
    val response = CompletableDeferred<JsonObject>()

    override fun start() = Unit

    override suspend fun initialize() = Unit

    override suspend fun request(method: String, params: JsonObject): JsonObject = buildJsonObject {}

    override suspend fun notify(method: String, params: JsonObject) = Unit

    override suspend fun respond(serverRequestId: JsonElement, result: JsonObject) {
        if (!response.isCompleted) {
            response.complete(result)
        }
    }
}

/**
 * 为桥接测试提供一个不会触发真实进程行为的占位进程。
 */
private class NoOpProcess : Process() {
    private val emptyInput = ByteArrayInputStream(ByteArray(0))
    private val output = ByteArrayOutputStream()

    override fun getOutputStream() = output

    override fun getInputStream() = emptyInput

    override fun getErrorStream() = emptyInput

    override fun waitFor(): Int = 0

    override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true

    override fun exitValue(): Int = 0

    override fun destroy() = Unit

    override fun destroyForcibly(): Process = this

    override fun isAlive(): Boolean = false
}
