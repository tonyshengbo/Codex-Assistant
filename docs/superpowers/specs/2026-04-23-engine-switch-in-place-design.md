# 引擎切换原地完成设计

## 背景

当前 Aura Code 在工具窗口内切换引擎时，如果当前会话已经有内容，会弹出确认框，并在新的 tab 中创建 sibling session。该行为可以保护现有 `providerId + remoteConversationId` 的单会话语义，但会让用户在“只是想换个引擎继续聊”时被迫跳转到新 tab，交互较重。

本次需求是优化这一流程：切换引擎时不再新开 tab，而是在当前窗口内完成切换。

## 目标

- 切换引擎时不再弹出 `Open in a new tab?` 确认框。
- 切换引擎后继续停留在当前 tab。
- 当前 `timeline` 中已有内容继续保留，提供视觉连续性。
- 在时间线中插入一条清晰的系统提示，说明引擎已经切换，后续内容属于新会话。
- 切换后的后续请求按“新会话”语义处理，不续接旧的远端会话。
- 尽量不改动现有历史恢复、历史导出、远端分页的主链路。

## 非目标

- 不实现一个真正的“混合会话”模型。
- 不把切换前后不同引擎的历史揉成一个可恢复、可导出的统一远端会话。
- 不为切换事件引入多段持久化或 segment 数据结构。
- 不保证重新打开会话、重启 IDE、重新恢复历史后，仍能看到切换前保留在当前界面里的旧时间线内容。

## 方案选择

最终采用：

1. 切换引擎时直接在当前 tab 完成。
2. 保留当前界面的 `timeline` 内容，不立即清空。
3. 在时间线末尾插入一条系统节点，例如：`已切换到 Claude，以下内容将作为新会话继续。`
4. 底层会话语义立即重置：
   - 当前 session 的 `providerId` 更新为目标引擎。
   - 当前 session 的 `remoteConversationId` 清空。
   - 后续第一条消息按新的远端会话启动。

这个方案本质上是：

- UI 视觉连续。
- 会话语义断开。
- 历史恢复和导出仍然只服务于切换后的真实会话。

## 用户体验设计

### 切换前提

- 如果当前没有进行中的运行，用户可以直接切换引擎。
- 如果当前存在进行中的请求，本次方案建议延续当前保护逻辑：
  - 禁止切换，或者
  - 要求用户先停止当前运行再切换。

本次设计优先推荐“运行中禁止切换”，避免一次 turn 中途更换 provider，降低状态错乱风险。

### 切换后的可见反馈

- 顶部 composer 中的引擎选择立即更新为新引擎。
- 模型选择同时切换到该引擎对应的默认模型或已记忆模型。
- 时间线末尾插入系统节点，明确提示切换已经发生。
- 系统节点应明显区别于普通 user / assistant 消息。

推荐文案：

- `已切换到 {引擎名}，以下内容将作为新会话继续。`

### 视觉表现

- 图标优先使用本地已有资源：`/icons/swap-horiz.svg`
- 样式上使用弱强调信息卡片，避免看起来像错误态。
- 建议节点包含：
  - 图标
  - 标题或正文
  - 可选的次级说明，例如 “之前的内容仅在当前界面保留显示”

是否展示次级说明可以根据界面密度决定。如果要尽量轻量，可先只放一行主文案。

## 会话语义设计

### 核心原则

切换引擎后，当前 tab 不再代表原有远端会话的继续，而是从当前界面起点开始承载一个新的真实会话。

### 切换时状态变化

当用户选择新的引擎后：

1. 保留当前 timeline 内存态，不执行清空。
2. 将当前 session 的 `providerId` 更新为目标引擎。
3. 将当前 session 的 `remoteConversationId` 清空。
4. 将和当前远端会话绑定的恢复游标状态视为失效。
5. 插入一条本地系统节点，作为视觉断点。

### 切换后发送消息

- 后续第一条用户消息不应携带旧 `remoteConversationId`。
- Provider 启动流按“新 thread / 新 conversation”处理。
- 新远端会话建立后，再把新的 `remoteConversationId` 记录回当前 session。

## 历史恢复与导出边界

### 历史恢复

当前工程的历史恢复依赖单一 session 上的：

- `providerId`
- `remoteConversationId`

因此本方案明确保持这一假设不变：

- 切换引擎后，历史恢复只面向切换后的新会话。
- 切换前保留在当前界面的旧 timeline，仅作为当前内存界面的视觉连续内容。
- 重新恢复会话、重新打开 tab、重启 IDE 后，旧 timeline 允许消失。

### 导出

当前导出逻辑基于远端会话读取和格式化，而不是基于当前界面的混合 timeline。

因此本方案中：

- 导出只导出切换后的真实远端会话内容。
- 不要求把切换前显示在当前界面中的旧内容一起导出。

这不是缺陷，而是本方案的明确取舍。

## 实现设计

### 1. 引擎切换协调逻辑

当前 `ToolWindowCoordinator.handleEngineSelection` 的逻辑是：

- 空会话原地切换。
- 非空会话调用 `branchSessionForEngineSwitch`，创建 sibling session 并尝试在新 tab 打开。

需要调整为：

- 无论当前 session 是否已有内容，都不再创建新 tab。
- 在当前 session 上直接完成 provider 切换。
- 非空会话切换时，执行“语义重开”：
  - 更新 provider
  - 清空 remote conversation
  - 插入系统节点
  - 保留 timeline 不清空

建议把该流程单独抽成一个新的方法，避免继续复用 `branchSessionForEngineSwitch` 的语义。

### 2. Composer 状态

`ComposerAreaStore` 目前会在已有消息时触发 `engineSwitchConfirmation`。

本次需要调整为：

- 不再为“已有消息的会话切引擎”弹出确认对话框。
- `RequestEngineSwitch` 可以简化为直接切换，或只保留给运行中阻断场景。
- 切换后立即同步：
  - `selectedEngineId`
  - `selectedModel`
  - 关闭 `engineMenuExpanded`
  - 清理 `engineSwitchConfirmation`

### 3. Timeline 系统节点

需要新增一个轻量系统节点类型，表示“引擎已切换”。

节点至少包含：

- 节点类型：`engine_switched`
- 展示文案
- 目标引擎标签
- 时间戳
- 图标路径

该节点只要求在当前会话时间线中可见，不要求进入远端恢复协议。

如果当前 timeline 结构已经支持本地注入型系统节点，应复用现有通道；否则新增一个最小模型即可。

### 4. 历史状态重置

引擎切换完成后，需要确保旧远端会话相关状态不会继续污染新会话：

- 清空当前 session 的 `remoteConversationId`
- 清理当前会话对应的历史分页上下文
- 清理基于旧会话的恢复游标或“load older messages”前提条件

目标是确保切换后的第一次发送一定走“新会话”路径。

### 5. 文案

当前资源里存在：

- `composer.engineSwitch.confirm.title`
- `composer.engineSwitch.confirm.message`

本次要么移除使用，要么保留但不再走该流程。

新增建议文案键：

- `timeline.system.engineSwitched=已切换到 {0}，以下内容将作为新会话继续。`

如需英文及其他语言，可补充对应翻译。

## 风险与取舍

### 风险一：用户误以为历史仍然属于同一个真实会话

这是该方案最大的语义风险，因此必须通过系统节点明确提示“以下内容为新会话”。

### 风险二：刷新或恢复后，旧 timeline 消失

这是方案 1 的已知取舍，不应在实现中掩盖。必要时可以在后续版本升级为“本地保留但不导出”的方案 2。

### 风险三：导出结果与当前界面所见不完全一致

这同样是已知取舍。当前产品语义应定义为：

- 导出的是“真实远端会话”
- 当前界面展示的是“临时视觉连续历史”

## 测试建议

### 单元测试

- 已有消息的 session 切换引擎时，不再创建新 session。
- 切换后当前 session 的 `providerId` 已更新。
- 切换后当前 session 的 `remoteConversationId` 被清空。
- 切换后 composer 的 `selectedEngineId` 与 `selectedModel` 已同步。
- 切换后 timeline 新增一条 `engine_switched` 系统节点。
- 切换后首次发送请求走新会话启动路径，而不是 resume 路径。

### 回归测试

- 空会话切换引擎仍然正常。
- 运行中会话的切换阻断逻辑仍然正确。
- 历史恢复仍只依赖当前 session 的单一 provider / remote conversation。
- 导出逻辑不受影响。
- 切换引擎后历史抽屉、session tab、标题展示没有异常。

## 实施范围

优先修改以下区域：

- `toolwindow/eventing/ToolWindowCoordinator.kt`
- `toolwindow/composer/ComposerAreaStore.kt`
- `toolwindow/composer/ComposerControlBar.kt`
- `toolwindow/timeline/*`
- `service/AgentChatService.kt`
- `messages/AuraCodeBundle*.properties`

## 结论

本设计采用最轻量的“原地切换引擎”方案：

- 不新开 tab。
- 不清空当前 timeline。
- 插入系统切换节点。
- 后续逻辑按新会话处理。
- 不引入混合会话恢复和导出。

它牺牲了恢复与导出的完全一致性，换取更顺滑的主交互和较低的实现复杂度，符合当前代码结构与需求优先级。
