# Codex Assistant（中文文档）

英文默认文档请见：[README.md](README.md)

## 项目简介

Codex Assistant 是一个 IntelliJ IDEA 插件，提供统一的 Agent 交互入口，底层通过可扩展 Provider 架构接入引擎。当前 Codex 集成走本地 Codex CLI，并使用 CLI 会话 `thread_id` 做原生多轮续聊。

## 已实现功能

- 统一 Tool Window（Codex Chat）
- 基于 Provider Registry 的引擎选择（当前引擎：`codex`）
- 流式输出 + 可取消
- 基于原生 CLI `thread_id` 的会话内多轮续聊
- 动作类型：`CHAT`
- 上下文输入：当前文件 + 手动附加文件
- 项目级本地会话持久化
- 设置页可配置本地 Codex CLI 路径

## 环境要求

- IntelliJ IDEA 2023.3+
- JDK 17
- 本地可用的 `codex` CLI，或者
- 在 `Settings -> Tools -> Codex Assistant` 中配置 Codex CLI 路径

## 构建与安装

1. 构建插件：
```bash
./gradlew buildPlugin
```
2. 在 `build/distributions/` 找到插件 ZIP。
3. IDEA 中安装 ZIP。
4. 如果 `codex` 不在系统 `PATH` 上，打开 `Settings -> Tools -> Codex Assistant` 配置 CLI 路径。

## 使用说明

1. 打开 `View -> Tool Windows -> Codex Chat`
2. 选择引擎
3. 输入提示词并发送
4. 可选：附加上下文文件
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

### 3) Codex CLI 调用失败排查

在插件设置和本地环境中确认：
- Codex CLI 路径正确
- 本地执行 `codex exec --help` 正常
- CLI 登录态已在插件外完成

### 4) 运行时问题定位

在沙盒 IDEA 中查看日志：
- `Help -> Show Log in Finder/Explorer`
- 重点关注异常栈、`Codex cli raw:` 原始事件日志和 `Codex cli summary:` 摘要日志

### 5) 会话与状态问题

- 关闭并重开项目，验证项目级会话是否恢复。
- 切换引擎后再次请求，验证引擎切换是否生效。
- 流式输出中点击取消，验证请求是否终止。
- 在同一个 session 中继续追问，验证是否复用已保存的 CLI `thread_id`。

## 安全策略

- 会话历史只在当前保存的 session 内生效。
- 原生续聊锚点使用该 session 中保存的 Codex CLI `thread_id`。

## 当前范围

- 当前版本仅聚焦 IntelliJ IDEA。
- 多 JetBrains IDE 兼容将在后续版本扩展。
