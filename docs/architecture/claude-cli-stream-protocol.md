# Claude CLI Stream Protocol

## 目标

这份文档记录 Aura 当前已经在真实 `claude --output-format stream-json` 日志中验证过的流协议形态，以及 Aura 如何把这些事件映射到统一事件和 Timeline UI。

本文档只描述已在 `build/idea-sandbox/IC-2024.3.1/log/idea.log` 中确认过的协议，不推测未观测到的字段。

## 外层包络

Claude CLI 每一行都是独立 JSON，顶层常见字段如下：

| 字段 | 说明 |
| --- | --- |
| `type` | 顶层事件类型，当前已观测到 `system`、`stream_event`、`assistant`、`user`、`result`、`error` |
| `session_id` | Claude 远端会话标识，对应 Aura 的 `threadId` |
| `event` | 仅在 `type=stream_event` 时存在，内部再通过 `event.type` 区分细粒度流事件 |
| `message` | 仅在 `assistant` / `user` 快照时存在 |

## 已验证的真实事件

### 1. system/init

用于返回会话初始化信息。

```json
{"type":"system","subtype":"init","session_id":"19cc...0635","model":"claude-sonnet-4-6"}
```

Aura 映射：

- 建立 Claude thread
- 发出 `UnifiedEvent.ThreadStarted`

### 2. stream_event.message_start

表示一条 assistant message 生命周期开始。

```json
{"type":"stream_event","event":{"type":"message_start","message":{"id":"msg_xxx","model":"claude-sonnet-4-6","usage":{"input_tokens":32982,"output_tokens":0}}},"session_id":"19cc...0635"}
```

Aura 用途：

- 建立当前 message 上下文
- 不直接渲染 Timeline 节点

### 3. stream_event.content_block_start

已验证三种块类型：

- `tool_use`
- `thinking`
- `text`

示例：

```json
{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tooluse_xxx","name":"Read","input":{}}},"session_id":"19cc...0635"}
```

```json
{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}},"session_id":"19cc...0635"}
```

```json
{"type":"stream_event","event":{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}},"session_id":"19cc...0635"}
```

Aura 映射：

- `tool_use` -> 建立运行中的 `TOOL_CALL`
- `thinking` -> 建立 reasoning 聚合状态
- `text` -> 建立 assistant 正文聚合状态

### 4. stream_event.content_block_delta

已验证四种增量：

- `input_json_delta`
- `thinking_delta`
- `text_delta`
- `signature_delta`

示例：

```json
{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"file_path\":\"/tmp/demo.txt\"}"}},"session_id":"19cc...0635"}
```

```json
{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Inspecting repository structure"}},"session_id":"19cc...0635"}
```

```json
{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"工程已全部创建完毕。"}},"session_id":"19cc...0635"}
```

Aura 映射：

- `input_json_delta` -> 持续更新同一个工具节点的输入 JSON
- `thinking_delta` -> `UnifiedItem(kind=NARRATIVE, name="reasoning")`
- `text_delta` -> 更新同一个 assistant message 节点
- `signature_delta` -> 当前只记录协议，Aura 不进 UI

### 5. stream_event.content_block_stop

```json
{"type":"stream_event","event":{"type":"content_block_stop","index":0},"session_id":"19cc...0635"}
```

Aura 映射：

- `thinking` 块 stop 时，将 reasoning 节点标记为成功
- `tool_use` 和 `text` 不在这里结束 turn，仍等待后续 `tool_result` 或 `result`

### 6. assistant 快照

这类快照是对当前 assistant message 的补全视图，不应在 UI 中重复生成第二份内容。

已验证快照内容类型：

- `tool_use`
- `thinking`
- `text`

示例：

```json
{"type":"assistant","message":{"id":"msg_tool","content":[{"type":"tool_use","id":"tooluse_xxx","name":"Read","input":{"file_path":"/tmp/demo.txt"}}]},"session_id":"19cc...0635"}
```

```json
{"type":"assistant","message":{"id":"msg_answer","content":[{"type":"text","text":"工程已全部创建完毕。"}]},"session_id":"19cc...0635"}
```

Aura 规则：

- 作为增量缺失时的兜底快照
- 若与已有 delta 聚合结果相同，则不重复发射 UI 更新
- 若顶层带 `error`，仅记录错误文本，不渲染普通 assistant 消息

### 7. user.tool_result

Claude 工具执行完成后，结果不是通过 assistant message 回来，而是经常以 `type=user` 的 tool result 返回。

```json
{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tooluse_xxx","content":"File does not exist","is_error":true}]},"session_id":"19cc...0635"}
```

Aura 映射：

- 更新同一个 `tooluse_xxx` 对应的 `TOOL_CALL`
- `is_error=true` 时节点状态改为 `FAILED`
- 不额外生成用户气泡

### 8. stream_event.message_delta

用于携带 `stop_reason` 和当下 usage。

```json
{"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"input_tokens":32982,"cache_read_input_tokens":0,"output_tokens":30}},"session_id":"19cc...0635"}
```

```json
{"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":46300,"cache_read_input_tokens":0,"output_tokens":808}},"session_id":"19cc...0635"}
```

Aura 映射：

- 发出 `UnifiedEvent.ThreadTokenUsageUpdated`
- `stop_reason=tool_use` 不是 turn 结束，只表示当前消息准备进入工具阶段
- `stop_reason=end_turn` 仍要等待 `result`

### 9. stream_event.message_stop

```json
{"type":"stream_event","event":{"type":"message_stop"},"session_id":"19cc...0635"}
```

Aura 用途：

- 结束当前 message 级聚合上下文
- 不直接等价于 turn 完成

### 10. result

`result` 才是 turn 的最终收尾事件。

```json
{"type":"result","subtype":"success","session_id":"19cc...0635","result":"工程已全部创建完毕。","is_error":false,"usage":{"input_tokens":539211,"cache_read_input_tokens":0,"output_tokens":12125},"modelUsage":{"claude-sonnet-4-6":{"inputTokens":539211,"outputTokens":12125,"contextWindow":200000,"maxOutputTokens":32000}}}
```

Aura 映射：

- 最终 assistant 文本收尾
- 最终 usage 更新
- `UnifiedEvent.TurnCompleted`
- `modelUsage.*.contextWindow` 优先作为上下文窗口；若缺失，回退到 `ClaudeModelCatalog.contextWindow(model)`

### 11. error

```json
{"type":"error","message":"Claude CLI returned an error.","session_id":"19cc...0635"}
```

Aura 映射：

- `UnifiedEvent.Error`
- `UnifiedEvent.TurnCompleted(FAILED)`

## Aura 的统一映射规则

| Claude 事件 | Aura 统一事件 | Timeline 表现 |
| --- | --- | --- |
| `system/init` | `ThreadStarted` | 不单独出卡片 |
| `thinking_delta` / thinking 快照 | `ItemUpdated(NARRATIVE, name=reasoning)` | Reasoning 节点 |
| `tool_use` + `input_json_delta` | `ItemUpdated(TOOL_CALL, RUNNING)` | 工具调用节点 |
| `user.tool_result` | `ItemUpdated(TOOL_CALL, SUCCESS/FAILED)` | 更新同一工具节点 |
| `text_delta` / text 快照 | `ItemUpdated(NARRATIVE, name=message)` | assistant 消息流式更新 |
| `message_delta.usage` / `result.usage` | `ThreadTokenUsageUpdated` | 更新顶部 usage，不新增 Timeline 卡片 |
| `result` | `TurnCompleted` | 结束 loading |

## 当前明确不进 UI 的字段

- `signature_delta`
- `parent_tool_use_id`
- `uuid`
- `permission_denials`
- `fast_mode_state`

这些字段仍可保留在日志中，但当前 Aura 不渲染对应 Timeline 节点。

## 实现边界

当前 Claude 流实现分三层：

1. `ClaudeStreamEventParser`
   - 只负责单行 JSON -> 原始事件
2. `ClaudeStreamAccumulator`
   - 只负责 message/block/tool 级状态聚合
3. `ClaudeUnifiedEventMapper`
   - 只负责语义事件 -> `UnifiedEvent`

不要再把协议拼接逻辑塞回 `ClaudeCliProvider` 或 Timeline 层。
