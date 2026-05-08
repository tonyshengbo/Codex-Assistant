# 多引擎渲染内核设计

**日期：** 2026-04-25

**状态：** 对话中方案已确认，待书面 spec 评审

## 目标

重建 Aura 的多引擎渲染架构，使其满足以下目标：

- 各引擎特有的输出格式在 UI 层之外完成解析
- 实时流式事件、历史回放、后台会话统一走一条状态管线
- tool window UI 只消费 projection，不再自行解释运行时语义
- 新增一个引擎时，只需要在引擎适配层和语义归一化层做局部改动，而不是修改整片 UI
- 包结构和模块边界表达的是业务职责，而不是界面上的摆放位置

这份设计是强收敛、单向切换的方案。目标分支在最终合并前，应完全切换到新架构，并删除旧的渲染链路。

## 当前问题

当前代码库已经同时支持 Codex 和 Claude，但多引擎继续扩展时，会被以下四类结构性问题卡住：

1. 同一份运行时信息会经过多条不同的翻译链路。
2. 语义解释仍然发生在贴近 timeline UI 的位置。
3. 后台会话缓存的是 UI store 副本，而不是 session 级别的真实状态。
4. UI 包名表达的是摆放位置或控件形态，而不是功能职责。

现在最明显的外在表现，就是命令和文字渲染效果不稳定：

- 标题会从自由格式的命令文本里推断
- 文件改动有时会回退到从 body 字符串里解析
- message、tool、command 的摘要部分仍然在 UI 邻近代码中生成
- 不同引擎输出的原始格式不同，导致 timeline 质量也会随引擎漂移

这说明当前架构还没有建立可靠的语义边界。

## 设计原则

### 1. One Session, One Kernel

每个 chat session 必须只拥有一个运行时内核，以及一份唯一可信的 session 状态。

以下几种场景不允许再各自存在一套平行的翻译管线：

- 实时事件
- 历史回放
- 后台缓存会话
- 切换标签页后的恢复

### 2. UI Never Guesses Semantics

UI 可以负责格式化和渲染，但不能再从原始命令字符串、不透明 id、provider 特有 payload 中推断业务语义。

允许留在 UI 的职责：

- markdown 渲染
- 文本选择
- 展开与折叠行为
- 图标、badge、颜色展示
- 对已结构化目标执行点击处理

不允许再留在 UI 的职责：

- 猜测一条命令是不是“读文件”
- 猜测一次 tool call 实际影响了哪个文件
- 从文本块中解析兜底 file-change 行
- 根据展示文本反推出引擎特有的 activity 语义

### 3. Parse Early, Normalize Once

Provider 特有的解析，应在各引擎包内部尽早完成。

跨引擎统一，只允许在 normalization 层做一次。

完成归一化之后，产品其他层只能继续使用共享的 domain event 和 session state。

### 4. Projection Is Read-Only

Projection 层负责把 domain state 转成 UI 需要的展示模型。

Projection 不是第二套 reducer 树，也不是另一份状态真相。

### 5. Packages Must Describe Responsibilities

UI 包名必须表达功能职责，而不是布局区域。

推荐使用的命名：

- `sessions`
- `conversation`
- `submission`
- `execution`
- `history`
- `settings`

不再推荐的命名：

- `header`
- `drawer`
- `timeline`
- `composer`
- `status`

## 最终架构

最终架构分成五层。

### 1. 引擎层

Engine 层负责原始传输和引擎特有解析。

职责包括：

- 启动运行时进程或协议客户端
- 接收引擎原始输出
- 将原始输出解析成 engine semantic records
- 暴露引擎能力和元数据
- 提交引擎特有的控制动作，例如 approval 或 tool-input 响应

Engine 层不应该知道 tool window UI 最终如何展示 conversation 内容。

### 2. Session Normalization 层

这一层负责把 engine semantic records 转成共享的 session domain events。

职责包括：

- 将 Codex 和 Claude 的概念统一到同一套领域词汇
- 统一 command kind、tool kind、file-change 结构
- 保留结构化信息，而不是重新压回泛化文本

这一层是 provider 差异和产品行为之间的语义边界。

### 3. Session Kernel 层

这是新的运行时核心，也是单个 session 唯一的状态权威。

职责包括：

- 接收 commands
- 应用 domain events
- 维护 session state
- 协调实时运行、审批、tool user input、plan 更新、历史回放和 session 切换

每个活跃会话和后台会话都应该拥有一个 kernel 实例。

### 4. Projection 层

这一层从 session state 生成只读的展示模型。

职责包括：

- 派生 conversation 列表项
- 派生 submission 控件状态
- 派生 execution 状态和交互卡片
- 派生 session 导航指示信息
- 派生 history 和 settings 的展示模型
- 从结构化状态中解析本地化 UI 文案

Projection 可以决定措辞和视觉展示，但不能重新解释原始引擎语法。

### 5. UI 层

UI 层被收缩为 projection 的消费者和 user intent 的生产者。

职责包括：

- 渲染 projection 模型
- 派发 user intent
- 承载 shell 布局和 overlay

UI 层不应拥有 session 真状态，也不应解析 engine 输出。

## 最终目录结构

```text
src/main/kotlin/com/auracode/assistant/
  provider/
    engine/
      EngineCapabilities.kt
      EngineDescriptorRegistry.kt
    claude/
      raw/
      semantic/
      gateway/
    codex/
      raw/
      semantic/
      gateway/

  session/
    kernel/
      SessionKernel.kt
      SessionKernelManager.kt
      SessionCommand.kt
      SessionDomainEvent.kt
      SessionState.kt
      SessionReducer.kt
      SessionRuntimeRegistry.kt
    normalizer/
      EngineSemanticEventMapper.kt
      CommandSemanticClassifier.kt
      ToolSemanticClassifier.kt
      FileChangeSemanticParser.kt
    projection/
      SessionProjection.kt
      sessions/
      conversation/
      submission/
      execution/
      history/
      settings/

  toolwindow/
    bootstrap/
    shell/
    sessions/
    conversation/
    submission/
    execution/
    history/
    settings/
    shared/
```

## 领域模型

新的设计会用结构化 domain event 替代当前弱结构、偏文本优先的渲染契约。

### Engine Semantic Records

每个引擎包都应该先暴露引擎本地的 semantic records，然后再进入跨引擎 normalization。

例如：

- `AssistantMessageSemanticRecord`
- `ReasoningSemanticRecord`
- `CommandExecutionSemanticRecord`
- `ToolInvocationSemanticRecord`
- `FileMutationSemanticRecord`
- `ApprovalRequestSemanticRecord`
- `ToolUserInputSemanticRecord`
- `RunningPlanSemanticRecord`
- `SubagentSemanticRecord`

这些 record 可以保留引擎特有形状，但它们必须已经比原始字符串更结构化。

### Session Domain Events

完成 normalization 之后，产品其他层只允许继续使用共享 domain event。

代表性的事件族包括：

- `SessionDomainEvent.MessageAppended`
- `SessionDomainEvent.ReasoningUpdated`
- `SessionDomainEvent.CommandUpdated`
- `SessionDomainEvent.ToolUpdated`
- `SessionDomainEvent.FileChangesUpdated`
- `SessionDomainEvent.ApprovalRequested`
- `SessionDomainEvent.ToolUserInputRequested`
- `SessionDomainEvent.ToolUserInputResolved`
- `SessionDomainEvent.RunningPlanUpdated`
- `SessionDomainEvent.PlanCompletionReady`
- `SessionDomainEvent.SubagentsUpdated`
- `SessionDomainEvent.TurnStarted`
- `SessionDomainEvent.TurnCompleted`
- `SessionDomainEvent.ThreadStarted`
- `SessionDomainEvent.SessionErrorRaised`
- `SessionDomainEvent.EngineSwitched`

### Structured Activity Fields

以下信息必须从“展示期猜测”改成显式结构字段：

- command kind
- tool kind
- message format
- activity target file or directory
- file change kind
- engine identity
- provider-native status
- normalized status
- `displaySafeSummaries`

command kind 示例：

- `READ_FILE`
- `WRITE_FILE`
- `SEARCH_FILES`
- `LIST_FILES`
- `RUN_TEST`
- `RUN_BUILD`
- `RUN_GIT`
- `RUN_SHELL`
- `UNKNOWN`

tool kind 示例：

- `MCP_CALL`
- `WEB_SEARCH`
- `PATCH_APPLY`
- `PLAN_UPDATE`
- `USER_INPUT`
- `UNKNOWN`

message format 示例：

- `PLAIN_TEXT`
- `MARKDOWN`
- `CODE_BLOCK`

## 语义解析边界

这份设计会明确修复当前渲染质量不稳定的问题，方法就是把语义解析彻底从 UI 邻近代码中挪走。

### 需要移出 UI 的解析逻辑

以下行为必须离开当前 UI 侧 timeline 链路：

- 从命令文本推断标题
- 从命令文本推断目标文件
- 从泛化的 `text`、`command`、`filePath`、`id` 重新拼装 body
- 从展示 body 字符串中兜底解析 file-change
- 基于不透明 activity 文本再生成 collapsed summary

### 新的解析路径

新的解析管线必须变成：

1. 原始引擎输出
2. 引擎语义提取
3. 共享 normalization
4. session domain events
5. projection 格式化
6. UI 渲染

这里的关键架构约束是：

`UI 不应再收到任何仍然需要语义猜测的 provider payload。`

## Session Kernel

### Kernel Ownership

每个 session 应该拥有：

- 一个 session id
- 一个 kernel 实例
- 一份 session state
- 一个当前绑定的 engine
- 一条 command queue
- 一个运行中的 runtime binding（如果当前存在 active run）

后台标签页应该通过 kernel 和 state 持续演进，而不是复制整套 UI store。

### Commands

Session kernel 应该接收强类型 command，例如：

- `SubmitPrompt`
- `ReplayHistory`
- `LoadOlderHistory`
- `SubmitApprovalDecision`
- `SubmitToolUserInput`
- `RequestEngineSwitch`
- `CancelRun`
- `OpenRemoteConversation`
- `DeleteSession`
- `RestoreSession`

### State

`SessionState` 应至少包含：

- session identity
- engine binding
- 实时运行状态
- 当前 thread 和 turn 引用
- conversation activity state
- submission draft state
- execution interaction state
- edited file state
- usage state
- subagent state
- history paging state
- 必要时用于展示的本地化 `metadata key`

状态模型应该按功能组织，而不是按 area-store 组织。

## Projection 模型

Projection 应按功能域拆分，而不是按控件名称拆分。

### Session Navigation Projection

负责：

- session tabs
- unread 和 attention 标记
- active session 指示信息
- 每个 session 的 engine badge

### Conversation Projection

负责：

- conversation render items
- message cards
- activity cards
- file-change cards
- 默认展开策略
- load-older 入口

### Submission Projection

负责：

- input draft
- context chips
- attachment chips
- engine 和 model selectors
- slash 与 mention 数据
- pending submission queue

### Execution Projection

负责：

- run status
- approval prompts
- tool-input prompts
- running plan 数据
- plan completion actions
- toast

### History Projection

负责：

- remote conversation 列表
- search state
- pagination state
- export 入口

### Settings Projection

负责：

- runtime settings
- skills settings
- MCP settings
- appearance 和 language 偏好

## UI 包重命名

旧的 UI 包命名表达的是摆放位置，而不是业务职责，因此应整体替换。

### 最终 UI 包名

```text
toolwindow/
  shell/
  sessions/
  conversation/
  submission/
  execution/
  history/
  settings/
  shared/
```

### 当前包到新包的映射

- `header` -> `sessions`
- `session` -> `sessions`
- `timeline` -> `conversation`
- `composer` -> 拆成 `submission` 和 `execution`
- `approval` -> `execution`
- `toolinput` -> `execution`
- `status` -> `execution`
- `plan` -> `execution`
- `drawer` 下的 history 相关部分 -> `history`
- `drawer/settings` 下的 settings 相关部分 -> `settings`
- drawer 壳层容器 -> `shell`

### 命名规则

包名必须回答：

`这段代码支持的是什么功能？`

而不是回答：

`这段代码画在界面的什么位置？`

## 迁移策略

这个分支可以按强约束阶段推进，但最终合并结果必须只保留一套架构。

### Phase 1: 建立新的跨层契约

先建立：

- `SessionDomainEvent`
- `SessionState`
- `SessionKernel`
- `SessionProjection`

这一步不要先做视觉重写，而是优先替换跨层契约。

### Phase 2: 将语义解析移出 UI

新增：

- `provider/claude/semantic/*`
- `provider/codex/semantic/*`
- `session/normalizer/*`

并移除 timeline 侧的语义解释逻辑。

这一步必须在功能域重命名前完成，因为它才是真正修复渲染质量问题的核心步骤。

### Phase 3: 引入 kernel 驱动的 session ownership

新增：

- `SessionKernelManager`
- `SessionRuntimeRegistry`
- 围绕 kernel 的按功能域拆分的 command controllers

替换掉：

- event 广播式协调
- 后台 UI store 克隆
- session-scoped event dispatchers

### Phase 4: 用 projection 消费者替换 Area Store

UI 入口需要从：

- 订阅按区域拆分的 stores

切换成：

- 消费从 session state 派生出来的 feature projections

### Phase 5: 按功能域重命名 UI 包

当 projection 稳定后，再把 UI 代码迁移到：

- `shell`
- `sessions`
- `conversation`
- `submission`
- `execution`
- `history`
- `settings`

### Phase 6: 删除旧的渲染链路

在合并前，必须删除旧路径，确保分支最终只保留一套架构。

必须删除的目标包括：

- `conversation/translation/*`
- 旧的 timeline mutation 和 reducer 链
- 旧的 area-store 体系
- 旧的后台 session UI cache 和 scoped dispatcher
- 重复的 engine presentation helpers
- 所有仍然按布局位置命名的旧 UI 包

## 明确删除目标

以下旧结构不应在最终合并结果中继续存在：

- `src/main/kotlin/com/auracode/assistant/conversation/translation/*`
- `toolwindow/timeline/TimelineNodeMapper.kt`
- `toolwindow/timeline/TimelineNodeReducer.kt`
- 仅服务旧渲染链的 timeline mutation models
- `toolwindow/eventing/SessionScopedEventDispatcher.kt`
- `toolwindow/eventing/SessionUiStateCache.kt`
- 所有只负责把事件扇出到按区域拆分 reducer 的旧 area-store 契约
- `toolwindow/EngineUiPresentation.kt`

任何仍然按布局位置而不是按功能职责命名的旧 UI 包，也应在迁移完成后一并删除。

## History 与 Live 一致性规则

实时流、历史回放、后台恢复，必须收敛到同一种状态形态。

这意味着：

- 回放历史事件时，必须能够还原出与实时累积一致的 conversation state
- 后台 session 必须通过 kernel 演进，而不是通过 UI cache 演进
- 切换标签页时，必须从 kernel state 恢复 projection，而不是从复制的 area store 中恢复

## 国际化规则

由于 Aura 的 UI 本身仍然是多语言的，本地化应当在 projection 层解决，而不是埋进 engine parsing 逻辑里。

规则如下：

- engine 和 semantic parsing 产出的是结构化语义，而不是最终本地化句子
- projection 负责选择本地化 labels、summaries、badges
- UI 组件消费的是已本地化的 projection 值，或者本地化 key

这样可以把 i18n 与 provider parsing 彻底解耦。

## 风险

### 1. Big-Bang Cutover Risk

由于目标分支要求在合并前删除旧链路，如果 kernel 和 projection 尚未稳定，整个分支会有较高失稳风险。

缓解方式：

- 严格按阶段推进迁移
- 在删除旧代码前，先用基于回放的测试验证行为等价性

### 2. Semantic Misclassification Risk

Claude 的部分流程仍可能需要从文本中提取语义，因为上游未必总是提供足够结构化字段。

缓解方式：

- 将 engine-specific semantic extractors 严格限制在各自 provider 内
- 对低置信度分类做显式标记，而不是默默伪装成精确结构

### 3. Projection Drift Risk

如果 projection 层重新开始做语义再解释，架构就会再次退化。

缓解方式：

- 强约束 projection 只做 formatting
- 在评审中检查 UI-facing code 是否重新引入解析逻辑

## 测试策略

新架构至少需要五类测试。

### 1. Engine Semantic Extraction Tests

针对每个引擎，验证代表性的原始输出是否能被提取成预期的 semantic records。

### 2. Normalization Tests

验证 Codex 和 Claude 中语义等价的 semantic records，是否会被归一化成同一类 session domain events。

### 3. Replay Consistency Tests

验证以下三种路径：

- 实时事件累积
- 初始历史恢复
- older-message prepend

最终都能收敛到一致的 session state 形态。

### 4. Projection Tests

验证相同的 session state 能稳定生成一致的：

- conversation titles
- collapsed summaries
- badges
- clickable targets
- execution prompts

### 5. Feature Flow Tests

验证以下核心功能流：

- session switching
- prompt submission
- engine switching
- approval workflows
- tool user input
- running plan 和 plan completion
- history open 和 export

## 非目标

这份设计不打算在本阶段解决以下问题：

- 重做 tool window 的视觉外观
- 在这一阶段为用户引入全新的交互模型
- 保证对所有自由格式 shell command 都做到完美语义识别
- 在合并后继续保留旧渲染链作为兜底方案

## 成功标准

当满足以下条件时，这次重构可以视为成功：

- command、tool、file-change 的渲染质量不再依赖 UI 侧猜测
- Codex 与 Claude 的差异被限制在 engine 和 normalization 层
- 实时、历史、后台会话统一走 kernel 驱动的状态管线
- feature package 的命名表达职责，而不是表达布局位置
- 新增第三个引擎时，不再需要为 timeline 或 composer 单独补解析逻辑
- 旧的 translation 和 area-store 渲染链在最终合并分支中被完全删除
