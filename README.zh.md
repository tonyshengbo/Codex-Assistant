# Aura Code（中文文档）

英文默认文档请见：[README.md](README.md)

## 项目简介

Aura Code 是一个由 Aura Code 运行时驱动的 IntelliJ IDEA 插件。它在 IDEA 内提供原生对话工具窗口，保存项目级会话历史，并通过保存的 `thread_id` 实现原生多轮续聊。

## 当前功能

- `Aura Code` 工具窗口
- 流式输出与取消
- 基于保存的 `thread_id` 的多轮续聊
- 当前文件、手动上下文、mention、附件支持
- 项目级本地会话持久化与恢复
- 模型、reasoning、模式与待发送队列控制
- 本地 MCP JSON 管理
- 本地 Skills 管理与 slash 入口
- 编辑文件聚合与 diff 入口
- Cmd/Ctrl+K 快捷打开

## 环境要求

- IntelliJ IDEA 2023.3+
- JDK 17
- 本地可用的 `codex` CLI，或者
- 在 `Settings -> Tools -> Aura Code` 中配置 Aura Code 运行时路径

## 构建与安装

1. 构建插件：
```bash
./gradlew buildPlugin
```
2. 在 `build/distributions/` 找到插件 ZIP。
3. IDEA 中安装 ZIP。
4. 如果 Aura Code 运行时不在系统 `PATH` 上，打开 `Settings -> Tools -> Aura Code` 配置运行时路径。

## 使用说明

1. 打开 `View -> Tool Windows -> Aura Code`
2. 如有需要，先配置本地 Aura Code 运行时路径
3. 输入提示词并发送
4. 可选：添加上下文文件或附件
5. 在同一个 session 内连续追问，验证上下文会被原生续接

## 调试方法

### 1) 本地插件调试（推荐）

```bash
./gradlew runIde
```

- 会启动一个沙盒 IDEA 实例。
- 在沙盒中打开任意项目并测试 Tool Window。
- 适合调试 UI 交互、消息流、命令确认、Diff 应用流程。

### 2) 构建失败排查

```bash
./gradlew buildPlugin --stacktrace
./gradlew buildPlugin --info
```

重点检查：
- `build.gradle.kts` 中 IntelliJ 平台版本与插件版本是否兼容
- 本地 JDK 是否为 17
- 网络是否可访问 JetBrains Maven 仓库

### 3) Aura Code 运行时调用失败排查

在插件设置和本地环境中确认：
- Aura Code 运行时路径正确
- 本地执行 `codex exec --help` 正常
- CLI 登录态已在插件外完成

### 4) 运行时问题定位

在沙盒 IDEA 中查看日志：
- `Help -> Show Log in Finder/Explorer`
- 重点关注异常栈、`Codex cli raw:` 原始事件日志和 `Codex cli summary:` 摘要日志

### 5) 会话与状态问题

- 关闭并重开项目，验证项目级会话是否恢复。
- 流式输出中点击取消，验证请求是否终止。
- 在同一个 session 中继续追问，验证是否复用已保存的 CLI `thread_id`。
- 重启 IDEA 后验证模型与 reasoning 选择是否恢复。

## 安全策略

- 会话历史只在当前保存的 session 内生效。
- 原生续聊锚点使用该 session 中保存的 `thread_id`。

## 当前范围

- 当前版本仅聚焦 IntelliJ IDEA。
- 本轮仅准备本地 ZIP 打包，不包含 Marketplace 签名与发布。
