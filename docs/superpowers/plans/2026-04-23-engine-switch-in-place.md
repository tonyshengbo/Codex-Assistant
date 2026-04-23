# Engine Switch In Place Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户在已有内容的会话中切换引擎时留在当前 tab，保留当前可见 timeline，并把后续请求作为新会话启动。

**Architecture:** 在现有单 session 模型上保留“一个 session 只绑定一个当前 provider / remoteConversationId”的假设，不做混合会话。实现上拆成三层：协调器原地切引擎并清空远端会话标识、timeline 注入本地系统切换节点、UI 与测试回归同步改为“无确认弹窗、无新 tab 分叉”。

**Tech Stack:** Kotlin、Jetpack Compose for Desktop、IntelliJ Platform plugin APIs、项目现有 Timeline reducer/store、kotlin.test、Gradle test

---

## 文件结构

### 需要修改的文件

- `src/main/kotlin/com/auracode/assistant/service/AgentChatService.kt`
  - 增加“非空 session 原地切换 provider 并清空 remoteConversationId”的服务方法。
- `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt`
  - 移除 populated session 切引擎时的新 tab 分叉流程，改为当前 session 原地重置语义并发布 timeline 系统节点。
- `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ConversationHistoryHandler.kt`
  - 在切换引擎后恢复 / 加载历史时继续遵守“只看当前 provider + remoteConversationId”的单会话逻辑，必要时加注释或最小状态清理入口。
- `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowEvents.kt`
  - 如有必要，补充专用 UI intent 或 timeline mutation 类型。
- `src/main/kotlin/com/auracode/assistant/toolwindow/composer/ComposerAreaStore.kt`
  - 去掉“已有消息时切引擎需要确认”的状态流转。
- `src/main/kotlin/com/auracode/assistant/toolwindow/composer/ComposerControlBar.kt`
  - 让引擎菜单点击始终直接派发 `UiIntent.SelectEngine(...)`，不再走确认分支。
- `src/main/kotlin/com/auracode/assistant/toolwindow/composer/ComposerRegion.kt`
  - 删除引擎切换确认弹窗的渲染入口与相关注释。
- `src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineNodeModels.kt`
  - 新增本地“引擎已切换”系统节点与对应 mutation。
- `src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineNodeReducer.kt`
  - 支持插入并保留新的系统节点。
- `src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineAreaStore.kt`
  - 如有必要，补充默认展开 / 收起策略，确保系统节点表现稳定。
- `src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineRegion.kt`
  - 为新节点接入渲染分支。
- `src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineActivityItems.kt`
  - 实现“已切换到 xxx 引擎”的系统提示卡片，图标使用 `/icons/swap-horiz.svg`。
- `src/main/resources/messages/AuraCodeBundle.properties`
- `src/main/resources/messages/AuraCodeBundle_zh.properties`
- `src/main/resources/messages/AuraCodeBundle_ja.properties`
- `src/main/resources/messages/AuraCodeBundle_ko.properties`
  - 新增切换提示文案；确认弹窗文案视是否仍被引用决定是否保留。

### 重点测试文件

- `src/test/kotlin/com/auracode/assistant/toolwindow/composer/ComposerControlBarTest.kt`
- `src/test/kotlin/com/auracode/assistant/toolwindow/composer/ComposerAreaStoreTest.kt`
- `src/test/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinatorMultiSessionTest.kt`
- `src/test/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineNodeReducerTest.kt`
- `src/test/kotlin/com/auracode/assistant/service/AgentChatServiceMultiSessionRunTest.kt`

---

## Chunk 1: 原地切引擎并重置会话语义

### Task 1: 固化“切引擎不再需要确认”的 UI 入口

**Files:**
- Modify: `src/test/kotlin/com/auracode/assistant/toolwindow/composer/ComposerControlBarTest.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/composer/ComposerControlBar.kt`

- [ ] **Step 1: 先改控制条测试，表达新的直接切换语义**

```kotlin
@Test
fun `switching engine from a populated session selects immediately`() {
    val intent = resolveEngineSelectionIntent(
        state = ComposerAreaState(
            selectedEngineId = "codex",
            activeSessionMessageCount = 2,
        ),
        engineId = "claude",
    )

    assertEquals(UiIntent.SelectEngine("claude"), intent)
}
```

- [ ] **Step 2: 运行单测，确认当前实现仍然失败**

Run:

```bash
./gradlew --no-daemon test --tests "com.auracode.assistant.toolwindow.composer.ComposerControlBarTest"
```

Expected:

```text
FAIL
Expected UiIntent.SelectEngine("claude"), actual UiIntent.RequestEngineSwitch("claude")
```

- [ ] **Step 3: 最小修改控制条逻辑，移除 populated session 的确认分支**

```kotlin
internal fun resolveEngineSelectionIntent(
    state: ComposerAreaState,
    engineId: String,
): UiIntent {
    val normalizedEngineId = engineId.trim().ifBlank { state.selectedEngineId }
    return UiIntent.SelectEngine(normalizedEngineId)
}
```

- [ ] **Step 4: 重新运行控制条测试，确认通过**

Run:

```bash
./gradlew --no-daemon test --tests "com.auracode.assistant.toolwindow.composer.ComposerControlBarTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: 提交这一小步**

```bash
git add src/main/kotlin/com/auracode/assistant/toolwindow/composer/ComposerControlBar.kt \
        src/test/kotlin/com/auracode/assistant/toolwindow/composer/ComposerControlBarTest.kt
git commit -m "test: remove engine switch confirmation intent"
```

### Task 2: 删除 composer 层的确认状态和弹窗渲染

**Files:**
- Modify: `src/test/kotlin/com/auracode/assistant/toolwindow/composer/ComposerAreaStoreTest.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/composer/ComposerAreaStore.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/composer/ComposerRegion.kt`

- [ ] **Step 1: 先把 store 测试改成“请求切换不会再挂起确认态”**

```kotlin
@Test
fun `requesting engine switch on populated session no longer creates confirmation state`() {
    val store = ComposerAreaStore()
    store.onEvent(
        AppEvent.SessionSnapshotUpdated(
            sessions = listOf(
                AgentChatService.SessionSummary(
                    id = "session-a",
                    title = "A",
                    updatedAt = 1L,
                    messageCount = 2,
                    remoteConversationId = "thread-1",
                    providerId = "codex",
                ),
            ),
            activeSessionId = "session-a",
        ),
    )

    store.onEvent(AppEvent.UiIntentPublished(UiIntent.RequestEngineSwitch("claude")))

    assertNull(store.state.value.engineSwitchConfirmation)
}
```

- [ ] **Step 2: 运行 composer store 测试，确认失败**

Run:

```bash
./gradlew --no-daemon test --tests "com.auracode.assistant.toolwindow.composer.ComposerAreaStoreTest"
```

Expected:

```text
FAIL
engineSwitchConfirmation was not null
```

- [ ] **Step 3: 最小实现**

```kotlin
is UiIntent.RequestEngineSwitch -> {
    _state.value = _state.value.copy(
        engineMenuExpanded = false,
        modelMenuExpanded = false,
        reasoningMenuExpanded = false,
        engineSwitchConfirmation = null,
    )
}
```

并在 `ComposerRegion.kt` 删除：

```kotlin
ComposerEngineSwitchConfirmationDialog(...)
```

以及整段 `ComposerEngineSwitchConfirmationDialog` 组件。

- [ ] **Step 4: 重新运行 composer 相关测试**

Run:

```bash
./gradlew --no-daemon test --tests "com.auracode.assistant.toolwindow.composer.ComposerAreaStoreTest" \
                         --tests "com.auracode.assistant.toolwindow.composer.ComposerControlBarTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: 提交这一小步**

```bash
git add src/main/kotlin/com/auracode/assistant/toolwindow/composer/ComposerAreaStore.kt \
        src/main/kotlin/com/auracode/assistant/toolwindow/composer/ComposerRegion.kt \
        src/test/kotlin/com/auracode/assistant/toolwindow/composer/ComposerAreaStoreTest.kt \
        src/test/kotlin/com/auracode/assistant/toolwindow/composer/ComposerControlBarTest.kt
git commit -m "refactor: remove engine switch confirmation dialog"
```

### Task 3: 在服务层支持“当前 session 原地切 provider 并清空 remoteConversationId”

**Files:**
- Modify: `src/test/kotlin/com/auracode/assistant/service/AgentChatServiceMultiSessionRunTest.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/service/AgentChatService.kt`

- [ ] **Step 1: 先写服务测试，固定语义**

```kotlin
@Test
fun `resetting populated session for engine switch updates provider and clears remote conversation`() {
    val service = createService()
    val sessionId = service.getCurrentSessionId()

    // 通过现有测试夹具把 session 置为非空、且带远端会话 id
    seedSession(
        service = service,
        sessionId = sessionId,
        providerId = "codex",
        remoteConversationId = "thread-old",
        messageCount = 3,
    )

    val updated = service.resetSessionForEngineSwitch(
        sessionId = sessionId,
        providerId = "claude",
    )

    assertTrue(updated)
    val session = service.listSessions().first { it.id == sessionId }
    assertEquals("claude", session.providerId)
    assertEquals("thread-old", "thread-old") // 断言由测试夹具读取真实值替换
}
```

实现时把最后的占位断言替换成对 `remoteConversationId == ""`、`messageCount` 保持不变或按现有模型约定断言。

- [ ] **Step 2: 运行服务测试，确认失败**

Run:

```bash
./gradlew --no-daemon test --tests "com.auracode.assistant.service.AgentChatServiceMultiSessionRunTest"
```

Expected:

```text
FAIL
Unresolved reference: resetSessionForEngineSwitch
```

- [ ] **Step 3: 最小实现服务方法**

```kotlin
fun resetSessionForEngineSwitch(
    sessionId: String = getCurrentSessionId(),
    providerId: String,
): Boolean {
    val normalizedProviderId = providerId.trim()
    if (normalizedProviderId.isBlank()) return false
    val updated = synchronized(stateLock) {
        val session = sessions[sessionId] ?: return@synchronized false
        session.providerId = normalizedProviderId
        session.remoteConversationId = ""
        session.updatedAt = System.currentTimeMillis()
        true
    }
    if (updated) {
        persistSessionSnapshot(sessionId)
    }
    return updated
}
```

- [ ] **Step 4: 跑服务测试确认通过**

Run:

```bash
./gradlew --no-daemon test --tests "com.auracode.assistant.service.AgentChatServiceMultiSessionRunTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: 提交这一小步**

```bash
git add src/main/kotlin/com/auracode/assistant/service/AgentChatService.kt \
        src/test/kotlin/com/auracode/assistant/service/AgentChatServiceMultiSessionRunTest.kt
git commit -m "feat: reset session state for in-place engine switch"
```

### Task 4: 协调器改成原地切换，不再新开 tab

**Files:**
- Modify: `src/test/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinatorMultiSessionTest.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt`

- [ ] **Step 1: 先改多会话测试，表达新的行为**

把现有：

```kotlin
fun `selecting a different engine from a populated session creates a new session instead of mutating the old one`()
```

改为：

```kotlin
fun `selecting a different engine from a populated session keeps the same session and clears remote conversation`() {
    val harness = MultiEngineCoordinatorHarness()
    val originalSessionId = harness.service.getCurrentSessionId()

    // 先发一条消息，让 session 成为 populated session
    harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-codex", TextRange(9))))
    harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
    harness.waitUntil { harness.codexProvider.requests.size == 1 }

    harness.eventHub.publishUiIntent(UiIntent.SelectEngine("claude"))
    harness.waitUntil { harness.composerStore.state.value.selectedEngineId == "claude" }

    assertEquals(originalSessionId, harness.service.getCurrentSessionId())
    assertEquals("claude", harness.service.listSessions().first { it.id == originalSessionId }.providerId)
    assertTrue(harness.openedSessionIds.isEmpty())
}
```

再补断言：
- `remoteConversationId` 已清空
- draft / attachment / context 文件仍留在当前 composer

- [ ] **Step 2: 跑协调器测试，确认失败**

Run:

```bash
./gradlew --no-daemon test --tests "com.auracode.assistant.toolwindow.eventing.ToolWindowCoordinatorMultiSessionTest"
```

Expected:

```text
FAIL
Expected same session id, but got a new session id
```

- [ ] **Step 3: 最小实现协调器切换流程**

目标重构：

```kotlin
private fun handleEngineSelection(engineId: String) {
    val normalizedEngineId = engineId.trim().ifBlank { chatService.defaultEngineId() }
    val currentSessionId = activeSessionId()
    val previousEngineId = chatService.sessionProviderId(currentSessionId)
    if (previousEngineId == normalizedEngineId) {
        publishSettingsSnapshot()
        publishConversationCapabilities()
        publishSessionSnapshot()
        return
    }

    settingsService.setDefaultEngineId(normalizedEngineId)
    val updatedInPlace = when {
        chatService.setSessionProviderIfEmpty(sessionId = currentSessionId, providerId = normalizedEngineId) -> true
        else -> chatService.resetSessionForEngineSwitch(sessionId = currentSessionId, providerId = normalizedEngineId)
    }

    if (updatedInPlace) {
        // 后续 task 再补 timeline 系统节点
    }

    publishSettingsSnapshot()
    publishConversationCapabilities()
    publishSessionSnapshot()
}
```

同时删除或停用：
- `branchSessionForEngineSwitch(...)`
- `restorePreviousSessionComposerState(...)`
- `createBranchedComposerState(...)`

- [ ] **Step 4: 跑协调器测试确认通过**

Run:

```bash
./gradlew --no-daemon test --tests "com.auracode.assistant.toolwindow.eventing.ToolWindowCoordinatorMultiSessionTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: 提交这一小步**

```bash
git add src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt \
        src/test/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinatorMultiSessionTest.kt
git commit -m "feat: switch engines in place for populated sessions"
```

---

## Chunk 2: 为 timeline 增加“已切换引擎”系统节点

### Task 5: 先为 reducer 增加系统节点和 mutation

**Files:**
- Modify: `src/test/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineNodeReducerTest.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineNodeModels.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineNodeReducer.kt`

- [ ] **Step 1: 先写 reducer 测试**

```kotlin
@Test
fun `engine switch mutation appends a system node without clearing previous messages`() {
    val reducer = TimelineNodeReducer()

    reducer.accept(
        TimelineMutation.UpsertMessage(
            sourceId = "user-1",
            role = MessageRole.USER,
            text = "old prompt",
            status = ItemStatus.SUCCESS,
        ),
    )
    reducer.accept(
        TimelineMutation.AppendEngineSwitched(
            sourceId = "engine-switch-1",
            targetEngineLabel = "Claude",
            body = "已切换到 Claude，以下内容将作为新会话继续。",
            timestamp = 123L,
        ),
    )

    assertEquals(2, reducer.state.nodes.size)
    assertIs<TimelineNode.EngineSwitchedNode>(reducer.state.nodes.last())
}
```

- [ ] **Step 2: 跑 reducer 测试，确认失败**

Run:

```bash
./gradlew --no-daemon test --tests "com.auracode.assistant.toolwindow.timeline.TimelineNodeReducerTest"
```

Expected:

```text
FAIL
Unresolved reference: AppendEngineSwitched / EngineSwitchedNode
```

- [ ] **Step 3: 最小实现节点与 mutation**

在 `TimelineNodeModels.kt` 中新增：

```kotlin
data class EngineSwitchedNode(
    override val id: String,
    override val sourceId: String,
    val title: String,
    val body: String,
    val iconPath: String,
    val timestamp: Long?,
    override val status: ItemStatus,
    override val turnId: String?,
) : TimelineNode
```

以及：

```kotlin
data class AppendEngineSwitched(
    val sourceId: String,
    val targetEngineLabel: String,
    val body: String,
    val timestamp: Long? = null,
) : TimelineMutation
```

在 reducer 中实现简单 append：

```kotlin
is TimelineMutation.AppendEngineSwitched -> acceptEngineSwitched(mutation)
```

- [ ] **Step 4: 跑 reducer 测试确认通过**

Run:

```bash
./gradlew --no-daemon test --tests "com.auracode.assistant.toolwindow.timeline.TimelineNodeReducerTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: 提交这一小步**

```bash
git add src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineNodeModels.kt \
        src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineNodeReducer.kt \
        src/test/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineNodeReducerTest.kt
git commit -m "feat: add engine switched timeline node"
```

### Task 6: 接上 timeline 渲染，并在协调器切换成功后注入系统节点

**Files:**
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineRegion.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineActivityItems.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt`
- Modify: `src/main/resources/messages/AuraCodeBundle.properties`
- Modify: `src/main/resources/messages/AuraCodeBundle_zh.properties`
- Modify: `src/main/resources/messages/AuraCodeBundle_ja.properties`
- Modify: `src/main/resources/messages/AuraCodeBundle_ko.properties`

- [ ] **Step 1: 先在协调器测试里补上系统节点断言**

在 `ToolWindowCoordinatorMultiSessionTest.kt` 的原地切换用例追加：

```kotlin
val switchNode = harness.timelineStore.state.value.nodes.last() as TimelineNode.EngineSwitchedNode
assertEquals("Claude", switchNode.title)
assertTrue(switchNode.body.contains("以下内容将作为新会话继续"))
```

如果当前测试夹具里引擎展示名是小写 id，就按实际展示标签断言。

- [ ] **Step 2: 跑协调器测试，确认失败**

Run:

```bash
./gradlew --no-daemon test --tests "com.auracode.assistant.toolwindow.eventing.ToolWindowCoordinatorMultiSessionTest"
```

Expected:

```text
FAIL
Last node was not EngineSwitchedNode
```

- [ ] **Step 3: 实现最小渲染与注入**

在 `ToolWindowCoordinator.handleEngineSelection(...)` 中，切换成功后发布：

```kotlin
eventDispatcher.dispatchSessionEvent(
    currentSessionId,
    AppEvent.TimelineMutationApplied(
        TimelineMutation.AppendEngineSwitched(
            sourceId = "engine-switch-${System.currentTimeMillis()}",
            targetEngineLabel = chatService.engineDescriptor(normalizedEngineId)?.displayName ?: normalizedEngineId,
            body = AuraCodeBundle.message("timeline.system.engineSwitched", targetEngineLabel),
            timestamp = System.currentTimeMillis(),
        ),
    ),
)
```

在 `TimelineRegion.kt` 增加分支：

```kotlin
is TimelineNode.EngineSwitchedNode -> TimelineEngineSwitchedItem(node = node, p = p)
```

在 `TimelineActivityItems.kt` 实现一个轻量信息卡片：

```kotlin
@Composable
internal fun TimelineEngineSwitchedItem(
    node: TimelineNode.EngineSwitchedNode,
    p: DesignPalette,
) { ... }
```

图标固定使用：

```kotlin
"/icons/swap-horiz.svg"
```

- [ ] **Step 4: 跑测试确认通过**

Run:

```bash
./gradlew --no-daemon test --tests "com.auracode.assistant.toolwindow.eventing.ToolWindowCoordinatorMultiSessionTest" \
                         --tests "com.auracode.assistant.toolwindow.timeline.TimelineNodeReducerTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: 提交这一小步**

```bash
git add src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt \
        src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineRegion.kt \
        src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineActivityItems.kt \
        src/main/resources/messages/AuraCodeBundle.properties \
        src/main/resources/messages/AuraCodeBundle_zh.properties \
        src/main/resources/messages/AuraCodeBundle_ja.properties \
        src/main/resources/messages/AuraCodeBundle_ko.properties \
        src/test/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinatorMultiSessionTest.kt
git commit -m "feat: show engine switch marker in timeline"
```

---

## Chunk 3: 补齐发送链路与回归验证

### Task 7: 固定“切换后首条发送一定是新会话启动”语义

**Files:**
- Modify: `src/test/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinatorMultiSessionTest.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ConversationHistoryHandler.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/service/AgentChatService.kt`

- [ ] **Step 1: 先补集成测试**

新增测试思路：

```kotlin
@Test
fun `sending after in-place engine switch starts a fresh remote conversation`() {
    val harness = MultiEngineCoordinatorHarness()

    harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-codex", TextRange(9))))
    harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
    harness.waitUntil { harness.codexProvider.requests.size == 1 }

    harness.eventHub.publishUiIntent(UiIntent.SelectEngine("claude"))
    harness.waitUntil { harness.composerStore.state.value.selectedEngineId == "claude" }

    harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-claude", TextRange(10))))
    harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
    harness.waitUntil { harness.claudeProvider.requests.size == 1 }

    assertEquals(null, harness.claudeProvider.requests.single().remoteConversationId)
}
```

- [ ] **Step 2: 跑目标测试，确认失败**

Run:

```bash
./gradlew --no-daemon test --tests "com.auracode.assistant.toolwindow.eventing.ToolWindowCoordinatorMultiSessionTest"
```

Expected:

```text
FAIL
Expected remoteConversationId to be null/blank, but was old thread id
```

- [ ] **Step 3: 补最小状态清理**

如果 Task 4 已经清空 `remoteConversationId`，这里只需要确认：
- 协调器在切换后不主动恢复旧历史；
- `ConversationHistoryHandler` 不会因为同步流程把旧 thread 再写回当前 session；
- 如有缓存的历史游标或恢复入口，切换后不触发 `restoreCurrentSessionHistory()` 覆盖当前内存 timeline。

必要时在协调器中显式避免这类调用。

- [ ] **Step 4: 重新运行测试**

Run:

```bash
./gradlew --no-daemon test --tests "com.auracode.assistant.toolwindow.eventing.ToolWindowCoordinatorMultiSessionTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: 提交这一小步**

```bash
git add src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt \
        src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ConversationHistoryHandler.kt \
        src/main/kotlin/com/auracode/assistant/service/AgentChatService.kt \
        src/test/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinatorMultiSessionTest.kt
git commit -m "fix: start fresh remote thread after in-place engine switch"
```

### Task 8: 清理遗留文案和最终回归

**Files:**
- Modify: `src/main/resources/messages/AuraCodeBundle.properties`
- Modify: `src/main/resources/messages/AuraCodeBundle_zh.properties`
- Modify: `src/main/resources/messages/AuraCodeBundle_ja.properties`
- Modify: `src/main/resources/messages/AuraCodeBundle_ko.properties`
- Modify: 任何因为删除确认弹窗而产生未使用引用的 Kotlin 文件

- [ ] **Step 1: 搜索遗留引用并补最终断言**

Run:

```bash
rg -n "engineSwitchConfirmation|composer\\.engineSwitch\\.confirm|RequestEngineSwitch" \
   src/main/kotlin src/test/kotlin src/main/resources/messages
```

Expected:

```text
只剩运行中阻断场景的合理引用，或完全消失
```

- [ ] **Step 2: 删除无用引用或保留必要兼容注释**

目标是让代码语义清晰，不保留误导性的“open in new tab”注释和测试名。

- [ ] **Step 3: 运行完整目标回归集**

Run:

```bash
./gradlew --no-daemon test \
  --tests "com.auracode.assistant.toolwindow.composer.ComposerControlBarTest" \
  --tests "com.auracode.assistant.toolwindow.composer.ComposerAreaStoreTest" \
  --tests "com.auracode.assistant.toolwindow.eventing.ToolWindowCoordinatorMultiSessionTest" \
  --tests "com.auracode.assistant.toolwindow.timeline.TimelineNodeReducerTest" \
  --tests "com.auracode.assistant.service.AgentChatServiceMultiSessionRunTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: 如时间允许，跑更宽一层工具窗口回归**

Run:

```bash
./gradlew --no-daemon test --tests "com.auracode.assistant.toolwindow.*"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: 提交最终整理**

```bash
git add src/main/kotlin/com/auracode/assistant \
        src/main/resources/messages \
        src/test/kotlin/com/auracode/assistant
git commit -m "feat: switch engines in place within current tab"
```

---

## 执行注意事项

- 不要把“切换前保留显示的旧 timeline”误做成可恢复、可导出的混合会话。
- 不要引入新的持久化 schema；本计划的目标就是避免 segment 模型扩散。
- 所有新增或修改的类、方法都要补简洁注释，遵守当前会话要求。
- 图标优先使用本地已有 `/icons/swap-horiz.svg`，不要新增外部资源，除非实现中确认本地图标无法满足。
- 如果运行中切引擎的阻断逻辑已经存在，优先复用，不要顺手扩需求。
