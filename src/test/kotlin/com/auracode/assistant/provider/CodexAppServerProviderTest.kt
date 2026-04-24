package com.auracode.assistant.provider.codex

import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.model.ContextFile
import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedAgentStatus
import com.auracode.assistant.protocol.UnifiedToolUserInputAnswerDraft
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.settings.skills.RuntimeSkillRecord
import com.auracode.assistant.settings.skills.SkillSelector
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexAppServerProviderTest {
    @Test
    fun `stream returns early error when configured codex path is invalid`() = runBlocking {
        val provider = CodexAppServerProvider(
            settings = providerSettings("/missing/codex"),
            environmentDetector = CodexEnvironmentDetector(
                shellEnvironmentLoader = { emptyMap() },
                commonSearchPaths = emptyList(),
                executableResolver = CodexExecutableResolver(
                    commonSearchPaths = emptyList(),
                    operatingSystemName = "Linux",
                    pathExt = "",
                ),
            ),
            diagnosticLogger = {},
        )

        val events = provider.stream(
            AgentRequest(
                engineId = "codex",
                prompt = "hello",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        ).toList()

        val error = assertIs<UnifiedEvent.Error>(events.single())
        assertEquals("Configured Codex Runtime Path is not executable. Update Settings and try again.", error.message)
    }

    @Test
    fun `thread start sandbox mode defaults to full access protocol enum`() {
        assertEquals("danger-full-access", buildThreadSandboxMode())
    }

    @Test
    fun `turn start sandbox policy defaults to full access payload`() {
        val payload = buildTurnSandboxPolicy("/tmp/project")

        assertEquals("dangerFullAccess", payload.getValue("type").jsonPrimitive.content)
        assertFalse("writableRoots" in payload)
        assertFalse("networkAccess" in payload)
    }

    @Test
    fun `workspace write turn sandbox policy keeps network restricted and writable roots payload`() {
        val payload = buildTurnSandboxPolicy(
            workingDirectory = "/tmp/project",
            mode = CodexAppServerSandboxMode.WORKSPACE_WRITE,
        )

        assertEquals("workspaceWrite", payload.getValue("type").jsonPrimitive.content)
        assertEquals(false, payload.getValue("networkAccess").jsonPrimitive.content.toBoolean())
        assertEquals(listOf("/tmp/project"), payload.getValue("writableRoots").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `skill config write payload omits path when using name selector`() {
        val payload = buildSkillConfigWriteParams(
            CodexSkillConfigSyncEntry(name = "brainstorming", enabled = false),
        )

        assertFalse("path" in payload)
        assertEquals("brainstorming", payload.getValue("name").jsonPrimitive.content)
        assertEquals(false, payload.getValue("enabled").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `plan collaboration mode serializes required settings payload`() {
        val payload = buildCollaborationModePayloadForTest(
            mode = AgentCollaborationMode.PLAN,
            model = "gpt-5.3-codex",
            reasoningEffort = "medium",
        )

        assertEquals("plan", payload?.get("mode")?.toString()?.trim('"'))
        val settings = payload?.get("settings")?.jsonObject
        assertEquals("gpt-5.3-codex", settings?.get("model")?.toString()?.trim('"'))
        assertEquals("medium", settings?.get("reasoning_effort")?.toString()?.trim('"'))
        assertEquals("null", settings?.get("developer_instructions")?.toString())
    }

    @Test
    fun `file change kind parser accepts object payload`() {
        val json = Json.parseToJsonElement(
            """{"type":"update","move_path":null}""",
        ).jsonObject

        assertEquals("update", extractFileChangeKindForTest(json))
    }

    @Test
    fun `command execution write does not emit additional diff apply item`() {
        val events = parseAppServerNotificationForTest(
            requestId = "req-1",
            method = "item/completed",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "commandExecution")
                        put("id", "call_1")
                        put("status", "completed")
                        put("command", "/bin/zsh -lc \"cat > /tmp/SingletonDemo.java <<'EOF'\nclass A {}\nEOF\"")
                        put("cwd", "/tmp")
                        put("aggregatedOutput", "")
                        put(
                            "commandActions",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "unknown")
                                        put("command", "cat > /tmp/SingletonDemo.java <<'EOF'\nclass A {}\nEOF")
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        assertEquals(1, events.size)
        val commandEvent = assertIs<UnifiedEvent.ItemUpdated>(events.single())
        assertEquals(ItemKind.COMMAND_EXEC, commandEvent.item.kind)
    }

    @Test
    fun `file change completion preserves previously parsed structured changes when completed payload omits changes`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        val started = parser.parseNotification(
            method = "item/started",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "fileChange")
                        put("id", "fc_1")
                        put("status", "started")
                        put(
                            "changes",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("path", "/tmp/hello.kt")
                                        put("kind", "update")
                                        put("oldContent", "fun a() = 1\n")
                                        put("newContent", "fun a() = 2\nfun b() = 3\n")
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        val completed = parser.parseNotification(
            method = "item/completed",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "fileChange")
                        put("id", "fc_1")
                        put("status", "completed")
                        put("output", "update /tmp/hello.kt")
                    },
                )
            },
        )

        val startedItem = assertIs<UnifiedEvent.ItemUpdated>(started.single()).item
        val completedItem = assertIs<UnifiedEvent.ItemUpdated>(completed.single()).item
        assertEquals(ItemKind.DIFF_APPLY, startedItem.kind)
        assertEquals(1, startedItem.fileChanges.size)
        assertEquals(1, completedItem.fileChanges.size)
        val change = completedItem.fileChanges.single()
        assertEquals("/tmp/hello.kt", change.path)
        assertEquals("fun a() = 1\n", change.oldContent)
        assertEquals("fun a() = 2\nfun b() = 3\n", change.newContent)
        assertEquals(2, change.addedLines)
        assertEquals(1, change.deletedLines)
    }

    @Test
    fun `file change parser reads nested payload changes`() {
        val events = parseAppServerNotificationForTest(
            requestId = "req-1",
            method = "item/completed",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "fileChange")
                        put("id", "fc_2")
                        put("status", "completed")
                        put(
                            "payload",
                            buildJsonObject {
                                put(
                                    "changes",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("path", "/tmp/nested.kt")
                                                put("kind", "update")
                                                put("old_content", "a\nb\n")
                                                put("new_content", "a\nc\n")
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(events.single()).item
        val change = assertNotNull(item.fileChanges.singleOrNull())
        assertEquals("/tmp/nested.kt", change.path)
        assertEquals(1, change.addedLines)
        assertEquals(1, change.deletedLines)
    }

    @Test
    fun `turn plan updated notification maps to running plan event`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        parser.parseNotification(
            method = "turn/started",
            params = buildJsonObject {
                put(
                    "turn",
                    buildJsonObject {
                        put("id", "turn-1")
                        put("threadId", "thread-1")
                    },
                )
            },
        )
        val updated = parser.parseNotification(
            method = "turn/plan/updated",
            params = buildJsonObject {
                put("turnId", "turn-1")
                put("explanation", "Plan updated")
                put(
                    "plan",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("status", "pending")
                                put("step", "First step")
                            },
                        )
                    },
                )
            },
        )

        val runningPlan = assertIs<UnifiedEvent.RunningPlanUpdated>(updated.single())
        assertEquals("thread-1", runningPlan.threadId)
        assertEquals("turn-1", runningPlan.turnId)
        assertEquals("Plan updated", runningPlan.explanation)
        assertEquals(1, runningPlan.steps.size)
        assertEquals("pending", runningPlan.steps.single().status)
        assertEquals("First step", runningPlan.steps.single().step)
        assertTrue(runningPlan.body.contains("- [pending] First step"))
    }

    @Test
    fun `turn diff updated notification maps to unified event`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        val events = parser.parseNotification(
            method = "turn/diff/updated",
            params = buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("diff", "diff --git a/foo.kt b/foo.kt")
            },
        )

        val updated = assertIs<UnifiedEvent.TurnDiffUpdated>(events.single())
        assertEquals("thread-1", updated.threadId)
        assertEquals("turn-1", updated.turnId)
        assertEquals("diff --git a/foo.kt b/foo.kt", updated.diff)
    }

    @Test
    fun `retryable app server error is parsed as non terminal unified error`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        val events = parser.parseNotification(
            method = "error",
            params = buildJsonObject {
                put("willRetry", true)
                put(
                    "error",
                    buildJsonObject {
                        put("message", "Reconnecting... 1/5")
                        put("additionalDetails", "stream disconnected before completion")
                    },
                )
            },
        )

        val error = assertIs<UnifiedEvent.Error>(events.single())
        assertEquals("Reconnecting... 1/5", error.message)
        assertFalse(error.terminal)
    }

    @Test
    fun `request completion only accepts matching active turn id`() {
        assertTrue(
            shouldCompleteActiveTurnForTest(
                activeTurnId = "turn-parent",
                event = UnifiedEvent.TurnCompleted(
                    turnId = "turn-parent",
                    outcome = TurnOutcome.SUCCESS,
                ),
            ),
        )
        assertFalse(
            shouldCompleteActiveTurnForTest(
                activeTurnId = "turn-parent",
                event = UnifiedEvent.TurnCompleted(
                    turnId = "turn-child",
                    outcome = TurnOutcome.FAILED,
                ),
            ),
        )
        assertFalse(
            shouldCompleteActiveTurnForTest(
                activeTurnId = "turn-parent",
                event = UnifiedEvent.Error("boom"),
            ),
        )
        assertFalse(
            shouldCompleteActiveTurnForTest(
                activeTurnId = null,
                event = UnifiedEvent.TurnCompleted(
                    turnId = "turn-parent",
                    outcome = TurnOutcome.SUCCESS,
                ),
            ),
        )
    }

    @Test
    fun `collab agent tool call completion emits timeline item update and subagent snapshot update`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        val events = parser.parseNotification(
            method = "item/completed",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "collabAgentToolCall")
                        put("id", "call_1")
                        put("tool", "spawnAgent")
                        put("status", "completed")
                        put("prompt", "Perform a code review of the latest diff.")
                        put(
                            "receiverThreadIds",
                            buildJsonArray {
                                add(JsonPrimitive("thread-review-1"))
                            },
                        )
                        put(
                            "agentsStates",
                            buildJsonObject {
                                put(
                                    "thread-review-1",
                                    buildJsonObject {
                                        put("status", "pendingInit")
                                        put("message", "Waiting for initialization")
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        assertEquals(2, events.size)
        val toolCall = assertIs<UnifiedEvent.ItemUpdated>(events.first())
        assertEquals(ItemKind.TOOL_CALL, toolCall.item.kind)
        assertEquals(ItemStatus.SUCCESS, toolCall.item.status)
        assertEquals("Dispatch Agent", toolCall.item.name)
        assertEquals(true, toolCall.item.text.orEmpty().contains("Agent Threads"))
        val updated = assertIs<UnifiedEvent.SubagentsUpdated>(events.last())
        assertEquals(1, updated.agents.size)
        val agent = updated.agents.single()
        assertEquals("thread-review-1", agent.threadId)
        assertEquals("review-agent", agent.mentionSlug)
        assertEquals("Review Agent", agent.displayName)
        assertEquals(UnifiedAgentStatus.PENDING, agent.status)
        assertEquals("Waiting for initialization", agent.summary)
    }

    @Test
    fun `collab agent tool call failure still emits timeline item update`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        val events = parser.parseNotification(
            method = "item/completed",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "collabAgentToolCall")
                        put("id", "call_wait_1")
                        put("tool", "wait")
                        put("status", "failed")
                        put(
                            "receiverThreadIds",
                            buildJsonArray {
                                add(JsonPrimitive("thread-review-1"))
                            },
                        )
                        put(
                            "agentsStates",
                            buildJsonObject {
                                put(
                                    "thread-review-1",
                                    buildJsonObject {
                                        put("status", "errored")
                                        put("message", "unexpected status 502 Bad Gateway")
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        assertEquals(2, events.size)
        val toolCall = assertIs<UnifiedEvent.ItemUpdated>(events.first())
        assertEquals(ItemKind.TOOL_CALL, toolCall.item.kind)
        assertEquals(ItemStatus.FAILED, toolCall.item.status)
        assertEquals("Wait Agent", toolCall.item.name)
        val updated = assertIs<UnifiedEvent.SubagentsUpdated>(events.last())
        assertEquals(UnifiedAgentStatus.FAILED, updated.agents.single().status)
    }

    @Test
    fun `wait completion with empty receiver thread ids reuses tracked child threads for subagent update`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        parser.parseNotification(
            method = "item/completed",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "collabAgentToolCall")
                        put("id", "call_wait_1")
                        put("tool", "wait")
                        put("status", "running")
                        put("prompt", "Wait for the review agent to finish.")
                        put(
                            "receiverThreadIds",
                            buildJsonArray {
                                add(JsonPrimitive("thread-review-1"))
                            },
                        )
                        put(
                            "agentsStates",
                            buildJsonObject {
                                put(
                                    "thread-review-1",
                                    buildJsonObject {
                                        put("status", "active")
                                        put("message", "Still reviewing")
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        val events = parser.parseNotification(
            method = "item/completed",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "collabAgentToolCall")
                        put("id", "call_wait_1")
                        put("tool", "wait")
                        put("status", "failed")
                        put(
                            "agentsStates",
                            buildJsonObject {
                                put(
                                    "thread-review-1",
                                    buildJsonObject {
                                        put("status", "errored")
                                        put("message", "unexpected status 502 Bad Gateway")
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        assertEquals(2, events.size)
        val toolCall = assertIs<UnifiedEvent.ItemUpdated>(events.first())
        assertEquals(ItemKind.TOOL_CALL, toolCall.item.kind)
        assertEquals(ItemStatus.FAILED, toolCall.item.status)
        assertEquals("Wait Agent", toolCall.item.name)
        val updated = assertIs<UnifiedEvent.SubagentsUpdated>(events.last())
        assertEquals("thread-review-1", updated.agents.single().threadId)
        assertEquals(UnifiedAgentStatus.FAILED, updated.agents.single().status)
        assertEquals("unexpected status 502 Bad Gateway", updated.agents.single().summary)
    }

    @Test
    fun `wait completion with new call id reuses previously spawned child threads for subagent update`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        parser.parseNotification(
            method = "turn/started",
            params = buildJsonObject {
                put(
                    "turn",
                    buildJsonObject {
                        put("id", "turn-parent")
                        put("threadId", "thread-main-1")
                    },
                )
            },
        )
        parser.parseNotification(
            method = "item/completed",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "collabAgentToolCall")
                        put("id", "call_spawn_1")
                        put("tool", "spawnAgent")
                        put("status", "completed")
                        put("senderThreadId", "thread-main-1")
                        put("prompt", "Perform a code review of the latest diff.")
                        put(
                            "receiverThreadIds",
                            buildJsonArray {
                                add(JsonPrimitive("thread-review-1"))
                            },
                        )
                        put(
                            "agentsStates",
                            buildJsonObject {
                                put(
                                    "thread-review-1",
                                    buildJsonObject {
                                        put("status", "pendingInit")
                                        put("message", "Waiting for initialization")
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        val events = parser.parseNotification(
            method = "item/completed",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "collabAgentToolCall")
                        put("id", "call_wait_2")
                        put("tool", "wait")
                        put("status", "completed")
                        put("senderThreadId", "thread-main-1")
                        put(
                            "receiverThreadIds",
                            buildJsonArray { },
                        )
                    },
                )
            },
        )

        assertEquals(2, events.size)
        val toolCall = assertIs<UnifiedEvent.ItemUpdated>(events.first())
        assertEquals(ItemKind.TOOL_CALL, toolCall.item.kind)
        assertEquals(ItemStatus.SUCCESS, toolCall.item.status)
        assertEquals("Wait Agent", toolCall.item.name)
        assertEquals(true, toolCall.item.text.orEmpty().contains("thread-review-1"))
        val updated = assertIs<UnifiedEvent.SubagentsUpdated>(events.last())
        assertEquals(1, updated.agents.size)
        assertEquals("thread-review-1", updated.agents.single().threadId)
        assertEquals(UnifiedAgentStatus.PENDING, updated.agents.single().status)
        assertEquals("Waiting for initialization", updated.agents.single().summary)
    }

    @Test
    fun `thread status changed refreshes existing subagent snapshot`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        parser.parseNotification(
            method = "item/completed",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "collabAgentToolCall")
                        put("id", "call_1")
                        put("tool", "spawnAgent")
                        put("status", "completed")
                        put("prompt", "Perform a code review of the latest diff.")
                        put(
                            "receiverThreadIds",
                            buildJsonArray {
                                add(JsonPrimitive("thread-review-1"))
                            },
                        )
                        put(
                            "agentsStates",
                            buildJsonObject {
                                put(
                                    "thread-review-1",
                                    buildJsonObject {
                                        put("status", "pendingInit")
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        val events = parser.parseNotification(
            method = "thread/status/changed",
            params = buildJsonObject {
                put("threadId", "thread-review-1")
                put(
                    "status",
                    buildJsonObject {
                        put("type", "active")
                    },
                )
            },
        )

        val updated = assertIs<UnifiedEvent.SubagentsUpdated>(events.single())
        assertEquals(UnifiedAgentStatus.ACTIVE, updated.agents.single().status)
        assertEquals("active", updated.agents.single().statusText)
    }

    @Test
    fun `thread status system error maps to failed subagent state and completes parent turn`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        parser.parseNotification(
            method = "turn/started",
            params = buildJsonObject {
                put(
                    "turn",
                    buildJsonObject {
                        put("id", "turn-parent")
                        put("threadId", "thread-main-1")
                    },
                )
            },
        )
        parser.parseNotification(
            method = "item/completed",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "collabAgentToolCall")
                        put("id", "call_1")
                        put("tool", "spawnAgent")
                        put("status", "completed")
                        put("prompt", "Perform a code review of the latest diff.")
                        put(
                            "receiverThreadIds",
                            buildJsonArray {
                                add(JsonPrimitive("thread-review-1"))
                            },
                        )
                    },
                )
            },
        )

        val events = parser.parseNotification(
            method = "thread/status/changed",
            params = buildJsonObject {
                put("threadId", "thread-review-1")
                put(
                    "status",
                    buildJsonObject {
                        put("type", "systemError")
                    },
                )
            },
        )

        val updated = assertIs<UnifiedEvent.SubagentsUpdated>(events.first())
        assertEquals(UnifiedAgentStatus.FAILED, updated.agents.single().status)
        assertEquals("systemError", updated.agents.single().statusText)
        val completed = assertIs<UnifiedEvent.TurnCompleted>(events.last())
        assertEquals("turn-parent", completed.turnId)
        assertEquals(TurnOutcome.FAILED, completed.outcome)
    }

    @Test
    fun `child turn completed failed refreshes subagent snapshot and completes parent turn`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        parser.parseNotification(
            method = "turn/started",
            params = buildJsonObject {
                put(
                    "turn",
                    buildJsonObject {
                        put("id", "turn-parent")
                        put("threadId", "thread-main-1")
                    },
                )
            },
        )
        parser.parseNotification(
            method = "item/completed",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "collabAgentToolCall")
                        put("id", "call_1")
                        put("tool", "spawnAgent")
                        put("status", "completed")
                        put("prompt", "Perform a code review of the latest diff.")
                        put(
                            "receiverThreadIds",
                            buildJsonArray {
                                add(JsonPrimitive("thread-review-1"))
                            },
                        )
                    },
                )
            },
        )

        val events = parser.parseNotification(
            method = "turn/completed",
            params = buildJsonObject {
                put("threadId", "thread-review-1")
                put(
                    "turn",
                    buildJsonObject {
                        put("id", "turn-child-1")
                        put("status", "failed")
                    },
                )
            },
        )

        assertEquals(2, events.size)
        val updated = assertIs<UnifiedEvent.SubagentsUpdated>(events.first())
        assertEquals(UnifiedAgentStatus.FAILED, updated.agents.single().status)
        assertEquals("failed", updated.agents.single().statusText)
        val completed = assertIs<UnifiedEvent.TurnCompleted>(events.last())
        assertEquals("turn-parent", completed.turnId)
        assertEquals(TurnOutcome.FAILED, completed.outcome)
    }

    @Test
    fun `context compaction lifecycle notifications map to dedicated item kind`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        parser.parseNotification(
            method = "turn/started",
            params = buildJsonObject {
                put(
                    "turn",
                    buildJsonObject {
                        put("id", "turn-ctx-1")
                        put("threadId", "thread-ctx-1")
                    },
                )
            },
        )

        val started = parser.parseNotification(
            method = "item/started",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "contextCompaction")
                        put("id", "ctx-1")
                    },
                )
            },
        )
        val completed = parser.parseNotification(
            method = "item/completed",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "contextCompaction")
                        put("id", "ctx-1")
                    },
                )
            },
        )

        val startedItem = assertIs<UnifiedEvent.ItemUpdated>(started.single()).item
        val completedItem = assertIs<UnifiedEvent.ItemUpdated>(completed.single()).item
        assertEquals("req-1:ctx-1", startedItem.id)
        assertEquals(ItemKind.CONTEXT_COMPACTION, startedItem.kind)
        assertEquals(ItemKind.CONTEXT_COMPACTION, completedItem.kind)
        assertEquals("Context Compaction", startedItem.name)
        assertEquals("Compacting context", startedItem.text)
        assertEquals("Context compacted", completedItem.text)
    }

    @Test
    fun `thread compacted updates existing context compaction item instead of creating a second node`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        parser.parseNotification(
            method = "turn/started",
            params = buildJsonObject {
                put(
                    "turn",
                    buildJsonObject {
                        put("id", "turn-ctx-2")
                        put("threadId", "thread-ctx-2")
                    },
                )
            },
        )
        val started = parser.parseNotification(
            method = "item/started",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "contextCompaction")
                        put("id", "ctx-2")
                    },
                )
            },
        )
        val compacted = parser.parseNotification(
            method = "thread/compacted",
            params = buildJsonObject {
                put("threadId", "thread-ctx-2")
                put("turnId", "turn-ctx-2")
            },
        )

        val startedItem = assertIs<UnifiedEvent.ItemUpdated>(started.single()).item
        val compactedItem = assertIs<UnifiedEvent.ItemUpdated>(compacted.single()).item
        assertEquals(startedItem.id, compactedItem.id)
        assertEquals(ItemKind.CONTEXT_COMPACTION, compactedItem.kind)
        assertEquals(ItemStatus.SUCCESS, compactedItem.status)
        assertEquals("Context compacted", compactedItem.text)
    }

    @Test
    fun `web search lifecycle notifications keep started body empty and summarize completion`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        val started = parser.parseNotification(
            method = "item/started",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "webSearch")
                        put("id", "ws_1")
                        put("query", "")
                        put(
                            "action",
                            buildJsonObject {
                                put("type", "other")
                            },
                        )
                    },
                )
            },
        )
        val completed = parser.parseNotification(
            method = "item/completed",
            params = buildJsonObject {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "webSearch")
                        put("id", "ws_1")
                        put("query", "https://openai.com/index/introducing-the-codex-app/")
                        put(
                            "action",
                            buildJsonObject {
                                put("type", "openPage")
                                put("url", "https://openai.com/index/introducing-the-codex-app/")
                            },
                        )
                    },
                )
            },
        )

        val startedItem = assertIs<UnifiedEvent.ItemUpdated>(started.single()).item
        val completedItem = assertIs<UnifiedEvent.ItemUpdated>(completed.single()).item
        assertEquals(ItemKind.TOOL_CALL, startedItem.kind)
        assertEquals(ItemKind.TOOL_CALL, completedItem.kind)
        assertEquals(ItemStatus.RUNNING, startedItem.status)
        assertEquals(ItemStatus.SUCCESS, completedItem.status)
        assertEquals("web_search", startedItem.name)
        assertEquals("web_search", completedItem.name)
        assertEquals(null, startedItem.text)
        assertEquals("Opened openai.com", completedItem.text)
    }

    @Test
    fun `historical web search item restores as tool call instead of unknown activity`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        val events = parser.parseHistoricalTurn(
            buildJsonObject {
                put("id", "turn-history-1")
                put("threadId", "thread-history-1")
                put("status", "completed")
                put(
                    "items",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "webSearch")
                                put("id", "ws_history_1")
                                put("status", "completed")
                                put("query", "kotlin compose ime")
                                put(
                                    "action",
                                    buildJsonObject {
                                        put("type", "search")
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        val restored = events.filterIsInstance<UnifiedEvent.ItemUpdated>()
            .single { it.item.id == "req-1:ws_history_1" }
            .item
        assertEquals(ItemKind.TOOL_CALL, restored.kind)
        assertEquals("web_search", restored.name)
        assertEquals("kotlin compose ime", restored.text)
    }

    @Test
    fun `request user input response preserves numeric json-rpc id type`() {
        val response = buildRequestUserInputResponseForTest(JsonPrimitive(0))

        assertEquals("0", response.getValue("id").jsonPrimitive.content)
        assertFalse(response.getValue("id").jsonPrimitive.isString)
    }

    @Test
    fun `tool user input prompt parser maps request metadata and question fields`() {
        val prompt = buildToolUserInputPromptForTest(
            serverRequestId = JsonPrimitive(0),
            params = buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("itemId", "call-1")
                put(
                    "questions",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "builder_demo_target")
                                put("header", "Target")
                                put("question", "How should I handle the builder demo?")
                                put("isOther", true)
                                put("isSecret", false)
                                put(
                                    "options",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("label", "Reuse existing demo")
                                                put("description", "Keep the current file and refine it")
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        assertEquals("0", prompt.requestId)
        assertEquals("thread-1", prompt.threadId)
        assertEquals("turn-1", prompt.turnId)
        assertEquals("call-1", prompt.itemId)
        assertEquals(1, prompt.questions.size)
        val question = prompt.questions.single()
        assertEquals("builder_demo_target", question.id)
        assertTrue(question.isOther)
        assertFalse(question.isSecret)
        assertEquals("Reuse existing demo", question.options.single().label)
    }

    @Test
    fun `tool user input response serializes answers map preserving numeric json rpc id type`() {
        val response = buildToolUserInputResponseForTest(
            serverRequestId = JsonPrimitive(0),
            submission = mapOf(
                "builder_demo_target" to UnifiedToolUserInputAnswerDraft(
                    answers = listOf("Reuse existing demo"),
                ),
            ),
        )

        assertEquals("0", response.getValue("id").jsonPrimitive.content)
        assertFalse(response.getValue("id").jsonPrimitive.isString)
        val answers = response.getValue("result").jsonObject.getValue("answers").jsonObject
        val builderAnswer = answers.getValue("builder_demo_target").jsonObject
        assertEquals(listOf("Reuse existing demo"), builderAnswer.getValue("answers").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `historical request user input item restores dedicated user input summary`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        val events = parser.parseHistoricalTurn(
            buildJsonObject {
                put("id", "turn-1")
                put("threadId", "thread-1")
                put("status", "completed")
                put(
                    "items",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "requestUserInput")
                                put("id", "call-1")
                                put("status", "completed")
                                put(
                                    "questions",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("id", "builder_demo_target")
                                                put("header", "Target")
                                                put("question", "How should I handle the builder demo?")
                                                put("isSecret", false)
                                            },
                                        )
                                    },
                                )
                                put(
                                    "answers",
                                    buildJsonObject {
                                        put(
                                            "builder_demo_target",
                                            buildJsonObject {
                                                put(
                                                    "answers",
                                                    buildJsonArray {
                                                        add(JsonPrimitive("Reuse existing demo"))
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        val restored = events.filterIsInstance<UnifiedEvent.ItemUpdated>()
            .single { it.item.kind == ItemKind.USER_INPUT }
            .item
        assertEquals("req-1:call-1", restored.id)
        assertTrue(restored.text.orEmpty().contains("Target"))
        assertTrue(restored.text.orEmpty().contains("Reuse existing demo"))
    }

    @Test
    fun `thread token usage update parser reads total usage and context window`() {
        val parser = CodexAppServerProvider.AppServerNotificationParser(
            requestId = "req-1",
            diagnosticLogger = {},
        )

        val events = parser.parseNotification(
            method = "thread/tokenUsage/updated",
            params = buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put(
                    "tokenUsage",
                    buildJsonObject {
                        put("modelContextWindow", 258400)
                        put(
                            "total",
                            buildJsonObject {
                                put("inputTokens", 615513)
                                put("cachedInputTokens", 546304)
                                put("outputTokens", 6617)
                            },
                        )
                        put(
                            "last",
                            buildJsonObject {
                                put("inputTokens", 35869)
                                put("cachedInputTokens", 35584)
                                put("outputTokens", 5)
                            },
                        )
                    }
                )
            }
        )

        val usage = assertIs<UnifiedEvent.ThreadTokenUsageUpdated>(events.single())
        assertEquals("thread-1", usage.threadId)
        assertEquals("turn-1", usage.turnId)
        assertEquals(258400, usage.contextWindow)
        assertEquals(615513, usage.inputTokens)
        assertEquals(546304, usage.cachedInputTokens)
        assertEquals(6617, usage.outputTokens)
    }

    @Test
    fun `prompt groups inline snippets separately from read by path contexts`() {
        val prompt = buildPromptForTest(
            AgentRequest(
                engineId = "codex",
                prompt = "summarize",
                contextFiles = listOf(
                    ContextFile(path = "/tmp/Foo.kt:10-12", content = "fun selected() = true"),
                    ContextFile(path = "/tmp/Bar.kt", content = null),
                ),
                workingDirectory = "/tmp",
            ),
        )

        assertTrue(prompt.contains("Context snippets:"))
        assertTrue(prompt.contains("FILE: /tmp/Foo.kt:10-12\nfun selected() = true"))
        assertTrue(prompt.contains("Context files (read by path):"))
        assertTrue(prompt.contains("- /tmp/Bar.kt"))
    }

    @Test
    fun `silent app server exit after turn start synthesizes successful turn completion`() {
        val event = syntheticTurnCompletionForSilentExit(
            turnId = "turn-1",
            turnAlreadyCompleted = false,
            cancelledLocally = false,
        )

        val completion = assertIs<UnifiedEvent.TurnCompleted>(event)
        assertEquals("turn-1", completion.turnId)
        assertEquals(TurnOutcome.SUCCESS, completion.outcome)
    }

    @Test
    fun `silent app server exit does not synthesize completion when turn already completed or cancelled`() {
        assertEquals(
            null,
            syntheticTurnCompletionForSilentExit(
                turnId = "turn-1",
                turnAlreadyCompleted = true,
                cancelledLocally = false,
            ),
        )
        assertEquals(
            null,
            syntheticTurnCompletionForSilentExit(
                turnId = "turn-1",
                turnAlreadyCompleted = false,
                cancelledLocally = true,
            ),
        )
        assertEquals(
            null,
            syntheticTurnCompletionForSilentExit(
                turnId = "",
                turnAlreadyCompleted = false,
                cancelledLocally = false,
            ),
        )
    }

    @Test
    fun `shared codex client starts a new thread when no remote conversation id exists`() {
        val session = FakeCodexAppServerSession().apply {
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
        val client = CodexAppServerClient(session = session, diagnosticLogger = {})

        val threadId = runBlockingTest {
            client.ensureThread(
                AgentRequest(
                    engineId = "codex",
                    prompt = "hello",
                    contextFiles = emptyList(),
                    workingDirectory = "/tmp/project",
                    approvalMode = AgentApprovalMode.REQUIRE_CONFIRMATION,
                ),
            )
        }

        assertEquals("thread-new", threadId)
        assertEquals("thread/start", session.requestMethods.single())
    }

    @Test
    fun `shared codex client maps runtime skills from skills list response`() {
        val session = FakeCodexAppServerSession().apply {
            response(
                "skills/list",
                buildJsonObject {
                    put(
                        "data",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put(
                                        "skills",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("name", "brainstorming")
                                                    put("description", "Explore requirements.")
                                                    put("enabled", false)
                                                    put("path", "/tmp/brainstorming/SKILL.md")
                                                    put("scope", "user")
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        val client = CodexAppServerClient(session = session, diagnosticLogger = {})

        val skills = runBlockingTest {
            client.listSkills(cwd = "/tmp/project", forceReload = true)
        }

        assertEquals(
            listOf(
                RuntimeSkillRecord(
                    name = "brainstorming",
                    description = "Explore requirements.",
                    enabled = false,
                    path = "/tmp/brainstorming/SKILL.md",
                    scopeLabel = "user",
                ),
            ),
            skills,
        )
        assertEquals("skills/list", session.requestMethods.single())
    }

    @Test
    fun `conversation history page returns latest turns first when cursor is empty`() {
        val page = buildConversationHistoryPage(
            turns = listOf(
                historicalTurn(id = "turn-1", message = "first"),
                historicalTurn(id = "turn-2", message = "second"),
                historicalTurn(id = "turn-3", message = "third"),
            ),
            pageSize = 2,
            cursor = null,
            providerId = "codex",
            diagnosticLogger = {},
        )

        assertTrue(page.hasOlder)
        assertEquals("1", page.olderCursor)
        val items = page.events.filterIsInstance<UnifiedEvent.ItemUpdated>()
        assertEquals(listOf("second", "third"), items.map { it.item.text })
    }

    @Test
    fun `conversation history page respects cursor boundaries`() {
        val page = buildConversationHistoryPage(
            turns = listOf(
                historicalTurn(id = "turn-1", message = "first"),
                historicalTurn(id = "turn-2", message = "second"),
                historicalTurn(id = "turn-3", message = "third"),
            ),
            pageSize = 2,
            cursor = "2",
            providerId = "codex",
            diagnosticLogger = {},
        )

        assertFalse(page.hasOlder)
        assertEquals(null, page.olderCursor)
        val items = page.events.filterIsInstance<UnifiedEvent.ItemUpdated>()
        assertEquals(listOf("first", "second"), items.map { it.item.text })
    }
}

private fun buildCollaborationModePayloadForTest(
    mode: AgentCollaborationMode,
    model: String?,
    reasoningEffort: String?,
) = buildCollaborationModePayloadForMode(mode, model, reasoningEffort)

private fun extractFileChangeKindForTest(json: kotlinx.serialization.json.JsonObject) =
    extractFileChangeKind(json)

private fun parseAppServerNotificationForTest(
    requestId: String,
    method: String,
    params: kotlinx.serialization.json.JsonObject,
): List<UnifiedEvent> {
    return CodexAppServerProvider.AppServerNotificationParser(
        requestId = requestId,
        diagnosticLogger = {},
    ).parseNotification(method, params)
}

private fun shouldCompleteActiveTurnForTest(
    activeTurnId: String?,
    event: UnifiedEvent,
) = shouldCompleteActiveTurn(activeTurnId, event)

private fun buildRequestUserInputResponseForTest(serverRequestId: JsonPrimitive) =
    buildRequestUserInputResponse(serverRequestId)

private fun buildToolUserInputPromptForTest(
    serverRequestId: JsonPrimitive,
    params: kotlinx.serialization.json.JsonObject,
) = buildToolUserInputPrompt(serverRequestId, params)

private fun buildToolUserInputResponseForTest(
    serverRequestId: JsonPrimitive,
    submission: Map<String, UnifiedToolUserInputAnswerDraft>,
) = buildToolUserInputResponse(serverRequestId, submission)

private fun buildPromptForTest(request: AgentRequest) = buildPrompt(request)

private fun runBlockingTest(block: suspend () -> Any?): Any? = kotlinx.coroutines.runBlocking { block() }

private fun historicalTurn(id: String, message: String) = buildJsonObject {
    put("id", id)
    put("threadId", "thread-1")
    put("status", "completed")
    put(
        "items",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "message")
                    put("id", "msg-$id")
                    put("status", "completed")
                    put("text", message)
                },
            )
        },
    )
}

private class FakeCodexAppServerSession : CodexAppServerSession {
    val requestMethods = mutableListOf<String>()
    private val responses = mutableMapOf<String, JsonObject>()

    override fun start() = Unit

    override suspend fun initialize() = Unit

    override suspend fun request(method: String, params: JsonObject): JsonObject {
        requestMethods += method
        return responses.getValue(method)
    }

    override suspend fun notify(method: String, params: JsonObject) = Unit

    override suspend fun respond(serverRequestId: JsonElement, result: JsonObject) = Unit

    fun response(method: String, result: JsonObject) {
        responses[method] = result
    }
}

private fun providerSettings(path: String): AgentSettingsService {
        return AgentSettingsService().apply {
            loadState(
                AgentSettingsService.State(
                    codexCliPath = path,
                    engineExecutablePaths = mutableMapOf("codex" to path),
                ),
            )
        }
}
