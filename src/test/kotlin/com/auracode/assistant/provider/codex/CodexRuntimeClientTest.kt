package com.auracode.assistant.provider.codex

import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 验证 Aura 在不同执行模式下发送给 Codex app-server 的线程与 turn 参数。
 */
class CodexRuntimeClientTest {
    @Test
    fun `approval mode thread start uses on request and workspace write sandbox`() = runBlocking {
        val session = RecordingCodexRuntimeSession().apply {
            response(
                "thread/start",
                buildJsonObject {
                    put(
                        "thread",
                        buildJsonObject {
                            put("id", "thread-new")
                        },
                    )
                },
            )
        }
        val client = CodexRuntimeClient(session = session, diagnosticLogger = {})

        client.ensureThread(
            AgentRequest(
                engineId = "codex",
                prompt = "hello",
                contextFiles = emptyList(),
                workingDirectory = "/tmp/project",
                approvalMode = AgentApprovalMode.REQUIRE_CONFIRMATION,
            ),
        )

        val request = session.singleRequest()
        assertEquals("thread/start", request.method)
        assertEquals("on-request", request.params.getValue("approvalPolicy").jsonPrimitive.content)
        assertEquals("workspace-write", request.params.getValue("sandbox").jsonPrimitive.content)
    }

    @Test
    fun `approval mode thread resume reapplies approval policy and sandbox`() = runBlocking {
        val session = RecordingCodexRuntimeSession().apply {
            response("thread/resume", buildJsonObject {})
        }
        val client = CodexRuntimeClient(session = session, diagnosticLogger = {})

        client.ensureThread(
            AgentRequest(
                engineId = "codex",
                prompt = "hello",
                contextFiles = emptyList(),
                workingDirectory = "/tmp/project",
                remoteConversationId = "thread-existing",
                approvalMode = AgentApprovalMode.REQUIRE_CONFIRMATION,
            ),
        )

        val request = session.singleRequest()
        assertEquals("thread/resume", request.method)
        assertEquals("thread-existing", request.params.getValue("threadId").jsonPrimitive.content)
        assertEquals("on-request", request.params.getValue("approvalPolicy").jsonPrimitive.content)
        assertEquals("workspace-write", request.params.getValue("sandbox").jsonPrimitive.content)
    }

    @Test
    fun `approval mode turn start keeps workspace write network restricted`() = runBlocking {
        val session = RecordingCodexRuntimeSession().apply {
            response(
                "turn/start",
                buildJsonObject {
                    put(
                        "turn",
                        buildJsonObject {
                            put("id", "turn-1")
                        },
                    )
                },
            )
        }
        val client = CodexRuntimeClient(session = session, diagnosticLogger = {})

        client.startTurn(
            request = AgentRequest(
                engineId = "codex",
                prompt = "hello",
                contextFiles = emptyList(),
                workingDirectory = "/tmp/project",
                approvalMode = AgentApprovalMode.REQUIRE_CONFIRMATION,
            ),
            threadId = "thread-1",
        )

        val request = session.singleRequest()
        val sandboxPolicy = request.params.getValue("sandboxPolicy").jsonObject
        assertEquals("turn/start", request.method)
        assertEquals("on-request", request.params.getValue("approvalPolicy").jsonPrimitive.content)
        assertEquals("workspaceWrite", sandboxPolicy.getValue("type").jsonPrimitive.content)
        assertEquals(false, sandboxPolicy.getValue("networkAccess").jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf("/tmp/project"),
            sandboxPolicy.getValue("writableRoots").jsonArray.map { it.jsonPrimitive.content },
        )
    }
}

/**
 * 记录 app-server 请求参数，便于断言 Aura 发出的 JSON-RPC 载荷。
 */
private class RecordingCodexRuntimeSession : CodexRuntimeSession {
    private val responses = mutableMapOf<String, JsonObject>()
    private val requests = mutableListOf<RecordedRequest>()

    override fun start() = Unit

    override suspend fun initialize() = Unit

    override suspend fun request(method: String, params: JsonObject): JsonObject {
        requests += RecordedRequest(method = method, params = params)
        return responses.getValue(method)
    }

    override suspend fun notify(method: String, params: JsonObject) = Unit

    override suspend fun respond(serverRequestId: JsonElement, result: JsonObject) = Unit

    /** 注册某个 JSON-RPC 方法的伪响应。 */
    fun response(method: String, result: JsonObject) {
        responses[method] = result
    }

    /** 读取本次测试唯一的一次请求。 */
    fun singleRequest(): RecordedRequest = requests.single()
}

/**
 * 表示一次发往 app-server 的 JSON-RPC 请求。
 */
private data class RecordedRequest(
    val method: String,
    val params: JsonObject,
)
