package com.auracode.assistant.provider.codex

import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.UnifiedApprovalRequestKind
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedToolUserInputAnswerDraft
import com.auracode.assistant.toolwindow.execution.ApprovalAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies Codex app-server bridge behavior for approval and tool-input server requests.
 */
class CodexAppServerConversationBridgeTest {
    /** Verifies that the legacy command-approval alias still emits a command approval event. */
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

    /** Verifies that the legacy apply-patch alias still emits a file-change approval event. */
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

    /**
     * Verifies that the v2 command-approval request keeps an immediate allow-for-session decision
     * even when the UI submits it during the approval callback.
     */
    @Test
    fun `v2 command approval preserves immediate allow for session decision`() = runBlocking {
        val session = RecordingBridgeSession()
        val approvalEvent = CompletableDeferred<UnifiedEvent.ApprovalRequested>()
        val active = ActiveRequest(process = NoOpProcess(), threadId = "thread-1", turnId = "turn-1")
        val bridge = createBridge(
            session = session,
            active = active,
            requestId = "req-bridge",
            emitUnified = { event ->
                if (event is UnifiedEvent.ApprovalRequested) {
                    if (!approvalEvent.isCompleted) {
                        approvalEvent.complete(event)
                    }
                    assertTrue(active.submitApprovalDecision(event.request.requestId, ApprovalAction.ALLOW_FOR_SESSION))
                }
            },
        )

        bridge.handleServerRequest(
            id = JsonPrimitive("17"),
            method = "item/commandExecution/requestApproval",
            params = buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("itemId", "call-17")
                put("reason", "Need to inspect the repository state")
                put("command", "git status --short")
                put("cwd", "/tmp/project")
            },
        )

        val approval = withTimeout(1_000) { approvalEvent.await() }.request
        assertEquals(UnifiedApprovalRequestKind.COMMAND, approval.kind)
        assertEquals("req-bridge:call-17", approval.itemId)
        assertEquals("git status --short", approval.command)
        assertEquals("/tmp/project", approval.cwd)

        val response = withTimeout(1_000) { session.response.await() }
        assertEquals("acceptForSession", response.getValue("decision").jsonPrimitive.content)
    }

    /** Verifies that the v2 file-change approval exposes structured file changes to the UI. */
    @Test
    fun `v2 file change approval emits structured file changes`() = runBlocking {
        val session = RecordingBridgeSession()
        val approvalEvent = CompletableDeferred<UnifiedEvent.ApprovalRequested>()
        val active = ActiveRequest(process = NoOpProcess(), threadId = "thread-1", turnId = "turn-1")
        val bridge = createBridge(
            session = session,
            active = active,
            requestId = "req-bridge",
            emitUnified = { event ->
                if (event is UnifiedEvent.ApprovalRequested && !approvalEvent.isCompleted) {
                    approvalEvent.complete(event)
                }
            },
        )

        bridge.handleServerRequest(
            id = JsonPrimitive("18"),
            method = "item/fileChange/requestApproval",
            params = buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("itemId", "call-18")
                put("reason", "Need to update the design note")
                putJsonObject("fileChanges") {
                    putJsonObject("/tmp/project/docs/design.md") {
                        put("kind", "update")
                        put("oldContent", "line one")
                        put("newContent", "line one\nline two")
                        put("diff", "@@ -1 +1,2 @@\n line one\n+line two\n")
                    }
                }
            },
        )

        val approval = withTimeout(1_000) { approvalEvent.await() }.request
        assertEquals(UnifiedApprovalRequestKind.FILE_CHANGE, approval.kind)
        assertEquals("req-bridge:call-18", approval.itemId)
        assertTrue(approval.body.contains("update /tmp/project/docs/design.md"))

        val fileChange = approval.fileChanges.singleOrNull()
        assertNotNull(fileChange)
        assertEquals("/tmp/project/docs/design.md", fileChange.path)
        assertEquals("update", fileChange.kind)
        assertEquals(1, fileChange.addedLines)
        assertEquals(0, fileChange.deletedLines)

        assertTrue(active.submitApprovalDecision("18", ApprovalAction.ALLOW))

        val response = withTimeout(1_000) { session.response.await() }
        assertEquals("accept", response.getValue("decision").jsonPrimitive.content)
    }

    /** Verifies that tool-user-input requests are emitted and responded to with the submitted answers. */
    @Test
    fun `tool user input request emits prompt and returns submitted answers`() = runBlocking {
        val session = RecordingBridgeSession()
        val requestedEvent = CompletableDeferred<UnifiedEvent.ToolUserInputRequested>()
        val active = ActiveRequest(process = NoOpProcess(), threadId = "thread-1", turnId = "turn-1")
        val bridge = createBridge(
            session = session,
            active = active,
            requestId = "req-bridge",
            emitUnified = { event ->
                if (event is UnifiedEvent.ToolUserInputRequested) {
                    if (!requestedEvent.isCompleted) {
                        requestedEvent.complete(event)
                    }
                    assertTrue(
                        active.submitToolUserInput(
                            requestId = event.prompt.requestId,
                            answers = mapOf(
                                "sandbox" to UnifiedToolUserInputAnswerDraft(
                                    answers = listOf("danger-full-access"),
                                ),
                            ),
                        ),
                    )
                }
            },
        )

        bridge.handleServerRequest(
            id = JsonPrimitive("19"),
            method = "item/tool/requestUserInput",
            params = buildToolUserInputParams(
                itemId = "tool-19",
                questionId = "sandbox",
                header = "Sandbox",
                question = "Choose a sandbox mode",
            ),
        )

        val prompt = withTimeout(1_000) { requestedEvent.await() }.prompt
        assertEquals("19", prompt.requestId)
        assertEquals("thread-1", prompt.threadId)
        assertEquals("turn-1", prompt.turnId)
        assertEquals("tool-19", prompt.itemId)
        assertEquals("sandbox", prompt.questions.single().id)

        val response = withTimeout(1_000) { session.response.await() }
        val submittedAnswers = response.getValue("answers")
            .jsonObject
            .getValue("sandbox")
            .jsonObject
            .getValue("answers")
            .jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("danger-full-access"), submittedAnswers)
    }

    /** Verifies that resolved notifications clear pending tool-input requests and emit a resolved event. */
    @Test
    fun `server request resolved emits tool user input resolved event`() = runBlocking {
        val session = RecordingBridgeSession()
        val requestedEvent = CompletableDeferred<UnifiedEvent.ToolUserInputRequested>()
        val resolvedEvent = CompletableDeferred<UnifiedEvent.ToolUserInputResolved>()
        val active = ActiveRequest(process = NoOpProcess(), threadId = "thread-1", turnId = "turn-1")
        val bridge = createBridge(
            session = session,
            active = active,
            requestId = "req-bridge",
            emitUnified = { event ->
                when (event) {
                    is UnifiedEvent.ToolUserInputRequested -> {
                        if (!requestedEvent.isCompleted) {
                            requestedEvent.complete(event)
                        }
                        assertTrue(
                            active.submitToolUserInput(
                                requestId = event.prompt.requestId,
                                answers = mapOf(
                                    "sandbox" to UnifiedToolUserInputAnswerDraft(
                                        answers = listOf("workspace-write"),
                                    ),
                                ),
                            ),
                        )
                    }

                    is UnifiedEvent.ToolUserInputResolved -> {
                        if (!resolvedEvent.isCompleted) {
                            resolvedEvent.complete(event)
                        }
                    }

                    else -> Unit
                }
            },
        )

        bridge.handleServerRequest(
            id = JsonPrimitive("20"),
            method = "item/tool/requestUserInput",
            params = buildToolUserInputParams(
                itemId = "tool-20",
                questionId = "sandbox",
                header = "Sandbox",
                question = "Choose a sandbox mode",
            ),
        )

        withTimeout(1_000) { requestedEvent.await() }
        withTimeout(1_000) { session.response.await() }
        assertTrue(active.pendingToolUserInputs.containsKey("20"))

        bridge.handleNotification(
            method = "serverRequest/resolved",
            params = buildJsonObject {
                put("requestId", "20")
            },
        )

        val resolved = withTimeout(1_000) { resolvedEvent.await() }
        assertEquals("20", resolved.requestId)
        assertFalse(active.pendingToolUserInputs.containsKey("20"))
    }

    /** Creates a bridge instance with a stable test request and injected callback. */
    private fun createBridge(
        session: CodexAppServerSession,
        active: ActiveRequest,
        requestId: String,
        emitUnified: suspend (UnifiedEvent) -> Unit,
    ): CodexAppServerConversationBridge {
        return CodexAppServerConversationBridge(
            request = AgentRequest(
                requestId = requestId,
                engineId = "codex",
                prompt = "hello",
                contextFiles = emptyList(),
                workingDirectory = "/tmp/project",
            ),
            active = active,
            session = session,
            client = CodexAppServerClient(session = session, diagnosticLogger = {}),
            diagnosticLogger = {},
            emitUnified = emitUnified,
        )
    }

    /** Builds a stable tool-user-input payload for bridge tests. */
    private fun buildToolUserInputParams(
        itemId: String,
        questionId: String,
        header: String,
        question: String,
    ): JsonObject {
        return buildJsonObject {
            put("threadId", "thread-1")
            put("turnId", "turn-1")
            put("itemId", itemId)
            put(
                "questions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", questionId)
                            put("header", header)
                            put("question", question)
                            put(
                                "options",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("label", "workspace-write")
                                            put("description", "Only allow writing inside the workspace")
                                        },
                                    )
                                    add(
                                        buildJsonObject {
                                            put("label", "danger-full-access")
                                            put("description", "Allow full filesystem access")
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
    }
}

/**
 * Records the approval or tool-input response written back to the app-server.
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
 * Provides a process stub that never performs real work for bridge tests.
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
