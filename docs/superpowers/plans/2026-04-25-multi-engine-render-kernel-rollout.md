# Multi-Engine Render Kernel Rollout Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Aura 当前多引擎渲染链路重构为单 `SessionKernel` / 单 `SessionState` / 单 projection 管线，并把 provider 原始协议解析与语义抽取彻底移出 UI。

**Architecture:** 先冻结现有 provider 行为和日志回放基线，再引入 `provider raw -> provider semantic -> session normalizer -> session kernel -> session projection -> UI` 新链路。迁移期间允许短期适配层共存，但最终合并结果只保留新架构，并删除 `conversation/translation`、旧 `timeline` reducer/mutation 链以及 session 级 UI cache。

**Tech Stack:** Kotlin, IntelliJ Platform, Compose Desktop, Kotlin coroutines/flows, kotlinx.serialization, JUnit/Kotlin test, Gradle.

---

## Implementation Ordering

不要按“先全量新建架构、最后一次替换 UI”的方式硬切，也不要继续在旧 `UnifiedEvent -> timeline/composer/status area stores` 链上追加逻辑。安全顺序是：

1. 先把日志回放校验链补齐，锁住当前 provider 输出形态。
2. 再引入 `session/kernel` 与 `session/normalizer`，让新状态契约先落地。
3. 然后让 provider 改为产出 semantic records，再归一化进 kernel。
4. 最后用 projection 驱动 UI，并删除旧 translation / area-store 链。

这意味着第一批代码改动不应该先碰 UI 视觉，而应该先补 provider 测试夹具和跨层契约。

## File Structure

### Provider replay and fixture validation

- Create: `src/test/kotlin/com/auracode/assistant/provider/diagnostics/ProviderDiagnosticFixture.kt`
  - 负责从已截取的诊断日志片段中读取 `raw / parsed / semantic / unified` 四类样本。
- Create: `src/test/kotlin/com/auracode/assistant/provider/claude/ClaudeDiagnosticReplayTest.kt`
  - 用真实 Claude 日志样本回放 `stdout -> parser -> accumulator -> semantic mapper -> unified/domain`。
- Create: `src/test/kotlin/com/auracode/assistant/provider/codex/CodexDiagnosticReplayTest.kt`
  - 用真实 Codex app-server 日志样本回放 `notification/request -> parser/bridge -> unified/domain`。
- Create: `src/test/resources/provider/claude/*.jsonl`
  - 保存从 `/Users/tonysheng/Library/Logs/Google/AndroidStudio2025.2.3/idea.12.log` 提取后的最小稳定夹具。
- Create: `src/test/resources/provider/codex/*.jsonl`
  - 保存 Codex app-server 对应最小稳定夹具。
- Modify: `src/test/kotlin/com/auracode/assistant/provider/claude/ClaudeStreamReplayTest.kt`
- Modify: `src/test/kotlin/com/auracode/assistant/provider/codex/CodexAppServerConversationBridgeTest.kt`

### Session kernel and domain contracts

- Create: `src/main/kotlin/com/auracode/assistant/session/kernel/SessionCommand.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/kernel/SessionDomainEvent.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/kernel/SessionState.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/kernel/SessionReducer.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/kernel/SessionKernel.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/kernel/SessionKernelManager.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/kernel/SessionRuntimeRegistry.kt`
- Create: `src/test/kotlin/com/auracode/assistant/session/kernel/SessionReducerTest.kt`
- Create: `src/test/kotlin/com/auracode/assistant/session/kernel/SessionKernelHistoryReplayTest.kt`

### Provider semantic extraction and normalization

- Create: `src/main/kotlin/com/auracode/assistant/provider/engine/EngineCapabilities.kt`
- Create: `src/main/kotlin/com/auracode/assistant/provider/engine/EngineDescriptorRegistry.kt`
- Create: `src/main/kotlin/com/auracode/assistant/provider/claude/raw/ClaudeRawProtocolRecord.kt`
- Create: `src/main/kotlin/com/auracode/assistant/provider/claude/semantic/ClaudeSemanticRecord.kt`
- Create: `src/main/kotlin/com/auracode/assistant/provider/claude/semantic/ClaudeSemanticEventExtractor.kt`
- Create: `src/main/kotlin/com/auracode/assistant/provider/codex/raw/CodexRawProtocolRecord.kt`
- Create: `src/main/kotlin/com/auracode/assistant/provider/codex/semantic/CodexSemanticRecord.kt`
- Create: `src/main/kotlin/com/auracode/assistant/provider/codex/semantic/CodexSemanticEventExtractor.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/normalizer/EngineSemanticEventMapper.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/normalizer/CommandSemanticClassifier.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/normalizer/ToolSemanticClassifier.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/normalizer/FileChangeSemanticParser.kt`
- Create: `src/test/kotlin/com/auracode/assistant/session/normalizer/EngineSemanticEventMapperTest.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/provider/claude/ClaudeCliProvider.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/provider/claude/ClaudeUnifiedEventMapper.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/provider/codex/CodexAppServerConversationBridge.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/provider/codex/CodexAppServerProvider.kt`

### Session projection and toolwindow migration

- Create: `src/main/kotlin/com/auracode/assistant/session/projection/SessionProjection.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/projection/sessions/SessionNavigationProjection.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/projection/conversation/ConversationProjection.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/projection/submission/SubmissionProjection.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/projection/execution/ExecutionProjection.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/projection/history/HistoryProjection.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/projection/settings/SettingsProjection.kt`
- Create: `src/test/kotlin/com/auracode/assistant/session/projection/ConversationProjectionTest.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ConversationFlowHandler.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ConversationHistoryHandler.kt`
- Modify: `src/test/kotlin/com/auracode/assistant/toolwindow/ConversationProjectionTestDriver.kt`

### UI feature-package migration and legacy deletion

- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/shell/*`
- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/sessions/*`
- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/conversation/*`
- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/submission/*`
- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/execution/*`
- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/history/*`
- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/settings/*`
- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/shared/*`
- Delete: `src/main/kotlin/com/auracode/assistant/conversation/translation/*`
- Delete: `src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineNodeMapper.kt`
- Delete: `src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineNodeReducer.kt`
- Delete: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/SessionScopedEventDispatcher.kt`
- Delete: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/SessionUiStateCache.kt`
- Delete: placement-driven 旧 UI 包与只服务旧 area-store 的契约

## Chunk 1: Replay Baseline And Diagnostic Fixtures

### Task 1: 从真实日志抽取稳定 provider 夹具

**Files:**
- Create: `src/test/kotlin/com/auracode/assistant/provider/diagnostics/ProviderDiagnosticFixture.kt`
- Create: `src/test/resources/provider/claude/claude-diagnostic-*.jsonl`
- Create: `src/test/resources/provider/codex/codex-diagnostic-*.jsonl`

- [ ] **Step 1: 先写读取失败的测试**

在新测试里先断言诊断夹具文件存在，并能按行读取出 `raw` 样本。

```kotlin
val fixture = ProviderDiagnosticFixture.load("/provider/claude/claude-diagnostic-stream.jsonl")
assertTrue(fixture.lines.isNotEmpty())
```

- [ ] **Step 2: 运行目标测试确认当前资源和读取器都不存在**

Run:

```bash
./gradlew test --tests "com.auracode.assistant.provider.claude.ClaudeDiagnosticReplayTest"
./gradlew test --tests "com.auracode.assistant.provider.codex.CodexDiagnosticReplayTest"
```

Expected:

- 测试因缺少 fixture loader / resource 而失败

- [ ] **Step 3: 实现最小 fixture loader**

仅支持：

- 从 classpath 读取 jsonl
- 过滤空行
- 保留原始行顺序
- 为不同 provider 返回最小 typed wrapper

- [ ] **Step 4: 从用户提供日志中提取最小稳定片段并保存到测试资源**

抽样规则：

- 不提交整份 IDE 日志
- 只保留能稳定复现协议行为的最短片段
- 同一行为优先保留一份成功流和一份异常流
- 夹具中保留真实协议字段，不保留无关噪声

- [ ] **Step 5: 重新运行目标测试确认资源可被稳定加载**

Run:

```bash
./gradlew test --tests "com.auracode.assistant.provider.claude.ClaudeDiagnosticReplayTest"
./gradlew test --tests "com.auracode.assistant.provider.codex.CodexDiagnosticReplayTest"
```

- [ ] **Step 6: 提交日志夹具基础设施**

```bash
git add \
  src/test/kotlin/com/auracode/assistant/provider/diagnostics/ProviderDiagnosticFixture.kt \
  src/test/resources/provider/claude \
  src/test/resources/provider/codex
git commit -m "test: add provider diagnostic replay fixtures"
```

### Task 2: 用真实日志回放冻结当前 provider 行为

**Files:**
- Create: `src/test/kotlin/com/auracode/assistant/provider/claude/ClaudeDiagnosticReplayTest.kt`
- Create: `src/test/kotlin/com/auracode/assistant/provider/codex/CodexDiagnosticReplayTest.kt`
- Modify: `src/test/kotlin/com/auracode/assistant/provider/claude/ClaudeStreamReplayTest.kt`
- Modify: `src/test/kotlin/com/auracode/assistant/provider/codex/CodexAppServerConversationBridgeTest.kt`

- [ ] **Step 1: 先写失败的回放断言**

覆盖：

- Claude: `stdout -> parsed event -> semantic event -> unified event`
- Codex: `notification/request -> approval/tool-input/item update`
- 对关键命令、file change、approval、reasoning、message 边界做断言

```kotlin
assertTrue(events.any { it is UnifiedEvent.ApprovalRequested })
assertTrue(events.any { it is UnifiedEvent.ItemUpdated && it.item.kind == ItemKind.COMMAND_EXEC })
```

- [ ] **Step 2: 运行目标测试并确认当前缺少日志回放入口**

Run:

```bash
./gradlew test --tests "com.auracode.assistant.provider.claude.ClaudeDiagnosticReplayTest"
./gradlew test --tests "com.auracode.assistant.provider.codex.CodexDiagnosticReplayTest"
```

- [ ] **Step 3: 最小实现日志回放辅助函数**

不要先改生产逻辑；优先复用现有：

- `ClaudeStreamEventParser`
- `ClaudeStreamAccumulator`
- `ClaudeUnifiedEventMapper`
- `CodexAppServerProvider.AppServerNotificationParser`
- `CodexAppServerConversationBridge`

- [ ] **Step 4: 补足断言直到回放链稳定通过**

至少覆盖：

- Claude assistant 文本边界
- Claude reasoning / tool / final usage
- Codex approval request
- Codex file change / command output
- Codex tool user input

- [ ] **Step 5: 重新运行所有 provider 相关目标测试**

Run:

```bash
./gradlew test --tests "com.auracode.assistant.provider.*"
```

- [ ] **Step 6: 提交 provider 行为冻结测试**

```bash
git add \
  src/test/kotlin/com/auracode/assistant/provider/claude/ClaudeDiagnosticReplayTest.kt \
  src/test/kotlin/com/auracode/assistant/provider/codex/CodexDiagnosticReplayTest.kt \
  src/test/kotlin/com/auracode/assistant/provider/claude/ClaudeStreamReplayTest.kt \
  src/test/kotlin/com/auracode/assistant/provider/codex/CodexAppServerConversationBridgeTest.kt
git commit -m "test: freeze provider behavior with diagnostic replays"
```

## Chunk 2: Session Kernel Contracts

### Task 3: 建立最小可运行的 session domain 契约

**Files:**
- Create: `src/main/kotlin/com/auracode/assistant/session/kernel/SessionCommand.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/kernel/SessionDomainEvent.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/kernel/SessionState.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/kernel/SessionReducer.kt`
- Test: `src/test/kotlin/com/auracode/assistant/session/kernel/SessionReducerTest.kt`

- [ ] **Step 1: 先写 reducer 失败测试**

覆盖：

- `ThreadStarted`
- `TurnStarted`
- `MessageAppended`
- `CommandUpdated`
- `ToolUpdated`
- `ApprovalRequested`
- `ToolUserInputRequested`
- `TurnCompleted`

- [ ] **Step 2: 运行 reducer 测试确认 session 契约不存在**

Run:

```bash
./gradlew test --tests "com.auracode.assistant.session.kernel.SessionReducerTest"
```

- [ ] **Step 3: 只实现最小 state/reducer 通过测试**

约束：

- 状态按功能域组织，不按 UI area 组织
- 不引入任何 Compose 或 toolwindow 依赖
- 所有文案字段只保存结构化 metadata 或 key

- [ ] **Step 4: 重新运行 reducer 测试**

Run:

```bash
./gradlew test --tests "com.auracode.assistant.session.kernel.SessionReducerTest"
```

- [ ] **Step 5: 提交 session 契约**

```bash
git add \
  src/main/kotlin/com/auracode/assistant/session/kernel \
  src/test/kotlin/com/auracode/assistant/session/kernel/SessionReducerTest.kt
git commit -m "feat: add session kernel domain contracts"
```

### Task 4: 建立 kernel 驱动的 history/live 一致性回放

**Files:**
- Create: `src/main/kotlin/com/auracode/assistant/session/kernel/SessionKernel.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/kernel/SessionKernelManager.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/kernel/SessionRuntimeRegistry.kt`
- Test: `src/test/kotlin/com/auracode/assistant/session/kernel/SessionKernelHistoryReplayTest.kt`

- [ ] **Step 1: 先写 live/replay 收敛失败测试**

断言同一组 domain events：

- 实时应用
- 初始历史恢复
- prepend 旧历史

最终得到一致 `SessionState`。

- [ ] **Step 2: 运行 kernel 测试确认当前无实现**

Run:

```bash
./gradlew test --tests "com.auracode.assistant.session.kernel.SessionKernelHistoryReplayTest"
```

- [ ] **Step 3: 实现最小 kernel / manager / runtime registry**

- [ ] **Step 4: 重新运行 kernel 测试**

Run:

```bash
./gradlew test --tests "com.auracode.assistant.session.kernel.SessionKernelHistoryReplayTest"
```

- [ ] **Step 5: 提交 kernel 基础设施**

```bash
git add \
  src/main/kotlin/com/auracode/assistant/session/kernel/SessionKernel.kt \
  src/main/kotlin/com/auracode/assistant/session/kernel/SessionKernelManager.kt \
  src/main/kotlin/com/auracode/assistant/session/kernel/SessionRuntimeRegistry.kt \
  src/test/kotlin/com/auracode/assistant/session/kernel/SessionKernelHistoryReplayTest.kt
git commit -m "feat: add session kernel runtime baseline"
```

## Chunk 3: Provider Semantic Extraction And Normalization

### Task 5: 让 provider 先产出 semantic records，再归一化为 session events

**Files:**
- Create: `src/main/kotlin/com/auracode/assistant/provider/claude/semantic/ClaudeSemanticRecord.kt`
- Create: `src/main/kotlin/com/auracode/assistant/provider/claude/semantic/ClaudeSemanticEventExtractor.kt`
- Create: `src/main/kotlin/com/auracode/assistant/provider/codex/semantic/CodexSemanticRecord.kt`
- Create: `src/main/kotlin/com/auracode/assistant/provider/codex/semantic/CodexSemanticEventExtractor.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/normalizer/EngineSemanticEventMapper.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/normalizer/CommandSemanticClassifier.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/normalizer/ToolSemanticClassifier.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/normalizer/FileChangeSemanticParser.kt`
- Test: `src/test/kotlin/com/auracode/assistant/session/normalizer/EngineSemanticEventMapperTest.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/provider/claude/ClaudeUnifiedEventMapper.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/provider/codex/CodexAppServerConversationBridge.kt`

- [ ] **Step 1: 先写 semantic -> domain 失败测试**

至少断言：

- command kind 不再依赖 UI 猜测
- tool kind 有统一分类
- file change 有结构化 summary
- approval / tool input / plan update 会被归一化成明确 domain event

- [ ] **Step 2: 运行 normalizer 测试确认现有 unified item 信息不足**

Run:

```bash
./gradlew test --tests "com.auracode.assistant.session.normalizer.EngineSemanticEventMapperTest"
```

- [ ] **Step 3: 仅增加 semantic records 与 normalizer，不先替换 UI**

- [ ] **Step 4: 用 Chunk 1 的日志回放测试回归验证 provider 输出**

Run:

```bash
./gradlew test --tests "com.auracode.assistant.provider.*"
./gradlew test --tests "com.auracode.assistant.session.normalizer.EngineSemanticEventMapperTest"
```

- [ ] **Step 5: 提交 semantic/normalizer 迁移**

```bash
git add \
  src/main/kotlin/com/auracode/assistant/provider/claude/semantic \
  src/main/kotlin/com/auracode/assistant/provider/codex/semantic \
  src/main/kotlin/com/auracode/assistant/session/normalizer \
  src/test/kotlin/com/auracode/assistant/session/normalizer/EngineSemanticEventMapperTest.kt \
  src/main/kotlin/com/auracode/assistant/provider/claude/ClaudeUnifiedEventMapper.kt \
  src/main/kotlin/com/auracode/assistant/provider/codex/CodexAppServerConversationBridge.kt
git commit -m "refactor: add provider semantic extraction and session normalization"
```

## Chunk 4: Projection-Driven Tool Window

### Task 6: 建立 projection 并接管 conversation / submission / execution

**Files:**
- Create: `src/main/kotlin/com/auracode/assistant/session/projection/SessionProjection.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/projection/conversation/ConversationProjection.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/projection/submission/SubmissionProjection.kt`
- Create: `src/main/kotlin/com/auracode/assistant/session/projection/execution/ExecutionProjection.kt`
- Test: `src/test/kotlin/com/auracode/assistant/session/projection/ConversationProjectionTest.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ConversationFlowHandler.kt`
- Modify: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ConversationHistoryHandler.kt`
- Delete after pass: `src/main/kotlin/com/auracode/assistant/conversation/translation/*`

- [ ] **Step 1: 先写 projection 失败测试**

断言 UI 消费结果中：

- conversation item title/body 不再依赖 `TimelineNodeMapper`
- approval/tool-input/running-plan 有独立 projection
- 本地化 key 或本地化文案来自 projection 而不是 parser

- [ ] **Step 2: 运行 projection 测试确认当前无新 projection**

Run:

```bash
./gradlew test --tests "com.auracode.assistant.session.projection.ConversationProjectionTest"
```

- [ ] **Step 3: 用 projection 接管 coordinator 对 UI 的输出**

注意：

- 先让旧 UI 组件消费新 projection adapter
- 不在这一阶段做视觉重排
- 不把命令/文件/文本解析回塞进 UI

- [ ] **Step 4: 删除 `conversation/translation/*` 并修复测试**

- [ ] **Step 5: 运行对话与执行相关测试**

Run:

```bash
./gradlew test --tests "com.auracode.assistant.toolwindow.*"
./gradlew test --tests "com.auracode.assistant.session.projection.*"
```

- [ ] **Step 6: 提交 projection 接管**

```bash
git add \
  src/main/kotlin/com/auracode/assistant/session/projection \
  src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt \
  src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ConversationFlowHandler.kt \
  src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ConversationHistoryHandler.kt \
  src/test/kotlin/com/auracode/assistant/session/projection/ConversationProjectionTest.kt
git commit -m "refactor: drive toolwindow from session projections"
```

## Chunk 5: Feature Package Rename And Legacy Cleanup

### Task 7: 按功能域迁移 toolwindow 包并删除旧链路

**Files:**
- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/shell/*`
- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/sessions/*`
- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/conversation/*`
- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/submission/*`
- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/execution/*`
- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/history/*`
- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/settings/*`
- Create: `src/main/kotlin/com/auracode/assistant/toolwindow/shared/*`
- Delete: `src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineNodeMapper.kt`
- Delete: `src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineNodeReducer.kt`
- Delete: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/SessionScopedEventDispatcher.kt`
- Delete: `src/main/kotlin/com/auracode/assistant/toolwindow/eventing/SessionUiStateCache.kt`

- [ ] **Step 1: 先写 package-level 回归测试**

验证：

- conversation 区域只消费 projection
- submission / execution 边界分离
- sessions / history / settings 入口名称与职责一致

- [ ] **Step 2: 运行目标测试确认旧包依赖仍存在**

- [ ] **Step 3: 迁移 UI 文件到功能域包**

约束：

- 遵守现有 UI 模式
- 不做无关视觉调整
- 涉及新增文案时同步补国际化资源

- [ ] **Step 4: 删除旧 timeline / eventing cache / placement-driven 包**

- [ ] **Step 5: 运行完整测试集**

Run:

```bash
./gradlew test
```

- [ ] **Step 6: 提交最终收敛与删除**

```bash
git add src/main/kotlin src/test/kotlin src/main/resources/messages
git commit -m "refactor: replace legacy multi-engine rendering chain"
```

## Notes And Guardrails

- 日志只作为测试样本来源，不应把整份 IDE 日志提交进仓库。
- 任何新增或修改的展示文案，都要同步考虑 `AuraCodeBundle*.properties` 国际化资源。
- 所有新的类和方法实现都补英文注释，遵守当前项目约束。
- 若发现当前未跟踪文件与本计划改动直接冲突，先停下确认，不要覆盖用户自己的试验代码。
- 在删除旧链路前，必须保证 Chunk 1 的日志回放测试、Chunk 2 的 replay consistency 测试、Chunk 4 的 projection 测试全部通过。
