# Aura Code

[English](README.md) | [中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md)

Aura Code 是一个 IntelliJ IDEA 插件，把 Codex 和 Claude 两套本地运行时整合进同一个原生 IDE 工作流。它把多会话对话、计划、审批、文件感知执行、运行时管理和本地工具控制收拢到一个工作台里，减少在终端、浏览器和 IDE 之间来回切换。

![Aura Code Preview](docs/img.png)
![Aura Code Preview](docs/img_1.png)

## 产品定位

Aura Code 是一个面向 IntelliJ IDEA 的双引擎 AI 助手：

- 在同一个工具窗口里运行 Codex 和 Claude
- 把项目级会话、历史记录和改动文件都保留在 IDE 内
- 保留本地 CLI 工作流的可控性，同时补上审批、上下文控制和 Diff 审阅
- 在同一套界面里管理运行时、Skills、MCP 服务和 Token 使用统计

## Beta 版本

当前 `1.0.0-beta.4` 通过 GitHub prerelease ZIP 和手动上传的 Marketplace 包分发。

- 可直接下载 GitHub Release 里的插件 ZIP，或本地执行 `./gradlew buildPlugin`
- 通过 `Settings -> Plugins -> Install Plugin from Disk...` 安装
- 当前仓库不负责自动发布 Marketplace，如有需要请使用生成的 ZIP 手动上传

## 核心能力

- 在原生 `Aura Code` 工具窗口中统一管理 Codex 和 Claude 会话
- 项目级多标签会话，支持本地持久化、远端续接和历史导出
- 流式响应、后台运行感知和完成通知
- 输入框支持 `@` 文件引用、文件或图片附件、`#` 本地智能体，以及 `/plan`、`/auto`、`/init`、`/new`、`/tab` 等 Slash Commands
- 对话流内支持 Plan 模式、审批请求、工具用户输入和运行中计划反馈
- 改动文件聚合，支持 Diff 预览、打开、接受和回退
- 运行时设置页可分别配置 Codex CLI、Claude CLI，以及需要时的 Node
- 内置 CLI 版本可见性、更新检查和升级入口
- 本地 Skills 的发现、导入、启停、Slash 暴露与卸载
- MCP 服务管理，支持 `stdio` 与 streamable HTTP 传输
- 按引擎、时间范围、模型查看历史 Token 使用统计
- 从 IntelliJ Problems 通过 `Ask Aura` 直接接入构建错误分析
- 支持中英日韩界面，以及主题和 UI 缩放设置

## 架构概览

当前插件已经从单一运行时桥接，演进为围绕双引擎会话管线组织的结构。

- `provider/codex`、`provider/claude`、`provider/runtime` 负责引擎启动、协议解析、版本检查和环境解析
- `session/kernel`、`session/normalizer`、`session/projection` 负责把 Provider 事件收敛成稳定的会话状态和 UI 投影
- `persistence/chat` 负责基于 SQLite 的项目本地会话、历史和 Token ledger
- `toolwindow/submission`、`toolwindow/conversation`、`toolwindow/execution`、`toolwindow/sessions`、`toolwindow/history`、`toolwindow/settings` 负责原生 Compose 工作流界面
- `settings/skills` 和 `settings/mcp` 负责本地 Skills 与 MCP 服务配置
- `integration/build` 和 `integration/ide` 负责构建错误接入与 IDE 上下文桥接

## 环境要求

- 可运行 IntelliJ IDEA 的 macOS、Linux 或 Windows
- JDK 17
- 兼容插件 `sinceBuild = 233` 的 IntelliJ IDEA
- 本地可用的 `codex` 和/或 `claude` 可执行文件，或在 `Settings -> Aura Code -> Runtime` 中手动配置
- 当所选 Codex 运行时流程依赖 Node 时，本地需要可用的 `node`

## 本地安装

1. 构建插件 ZIP：

```bash
./gradlew buildPlugin
```

2. 在 `build/distributions/` 中找到产物。
3. 打开 IntelliJ IDEA，进入 `Settings -> Plugins -> Install Plugin from Disk...`。
4. 选择生成的 ZIP 安装。
5. 打开 `Settings -> Aura Code -> Runtime`，确认 Codex CLI、Claude CLI 和可选的 Node 路径。

## 开发运行

启动带插件的沙盒 IDE：

```bash
./gradlew runIde
```

开发阶段常用命令：

```bash
./gradlew test
./gradlew buildPlugin
./gradlew verifyPlugin
```

## 核心工作流

### 会话与引擎

- 在插件内切换 Codex 和 Claude，同时保留项目级会话状态
- 打开多个会话标签页，切换焦点时后台运行可继续执行
- 当引擎支持时，可从本地历史和远端会话标识恢复之前的工作

### 计划与执行

- 直接在输入区使用 `Plan`、`Auto` 和审批导向工作流
- 在同一条时间线里查看运行中计划、计划修订提示和结构化工具用户输入
- 把执行决策留在 IDE 内，而不是退回到原始 CLI 输出

### 上下文、文件与历史

- 自动跟随当前编辑器文件和选中文本
- 需要更强控制时，可额外注入手动文件上下文、附件和本地智能体提示词
- 支持查看改动文件、打开 Diff、复制消息内容和导出 Markdown 会话

### 运行时、Skills 与 MCP

- 在 Runtime 设置页中分别管理 Codex CLI 和 Claude CLI
- 查看版本状态、更新检查和可支持来源下的升级动作
- 不离开 IntelliJ IDEA 即可管理本地 Skills 和 MCP 服务
- 按引擎、时间范围、模型查看历史 Token 使用情况

## 项目结构

```text
src/main/kotlin/com/auracode/assistant/
  actions/            IntelliJ 入口动作，例如快速打开和构建错误转交
  provider/           Codex、Claude、runtime 及 provider-session 集成
  session/            会话内核、事件归一化和 UI 投影层
  persistence/chat/   基于 SQLite 的会话历史和 Token 使用存储
  toolwindow/         输入区、对话区、执行区、历史、会话标签和设置界面
  settings/           持久化设置，以及 Skills 和 MCP 支持
  integration/        构建错误和 IDE 上下文桥接
  protocol/           共享的 Provider 协议模型
src/test/kotlin/com/auracode/assistant/
  ...                 Provider、服务、Store 和工作流行为测试
```

## 调试说明

如果插件无法启动运行时：

- 确认 `codex` 和/或 `claude` 可执行
- 如果当前 Codex 流程配置了 `node`，确认其可执行
- 在 `Settings -> Aura Code -> Runtime` 中检查路径配置
- 从 `Help -> Show Log in Finder/Explorer` 查看 IDE 日志

如果会话历史或续接不符合预期：

- 确认当前运行时已在插件外完成认证
- 确认恢复时使用的是同一引擎
- 分别排查远端历史加载和本地持久化

## 开源现状

- 当前仓库主要聚焦 IntelliJ IDEA 支持
- 已支持 GitHub prerelease ZIP 分发和本地 ZIP 安装
- Marketplace 交付当前依赖手动上传 ZIP，而不是仓库内自动发布流程

## License

Aura Code 基于 Apache License 2.0 开源发布。完整许可证内容请查看项目根目录下的 `LICENSE` 文件。
