# Aura Code

[English](README.md) | [中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md)

Aura Code 是一个 IntelliJ IDEA 插件，用来把本地 Codex 运行时直接带进 IDE。它希望把对话、计划、审批、Diff 审阅、工具调用和本地开发流整合到同一个项目内工作台里，减少在终端、浏览器和编辑器之间来回切换。
![Aura Code Preview](docs/img.png)
![Aura Code Preview](docs/img_1.png)
## 当前能力

- IntelliJ IDEA 内原生 `Aura Code` 工具窗口
- 项目级聊天会话，本地持久化，并支持远端会话续接
- 多标签会话工作流，支持后台运行中的会话切换
- 流式响应、主动取消、历史消息分页加载
- 输入框支持 `@` 文件引用、附件、`#` 本地智能体、`/` Slash Commands
- Plan 模式、Approval 模式、工具用户输入、运行中计划反馈
- 当前对话改动文件聚合，支持 Diff 预览、打开、接受、回退入口
- MCP 服务管理，支持本地 `stdio` 和远端 streamable HTTP
- 本地 Skills 发现、导入、启停、Slash 暴露、卸载
- 从 IntelliJ Problems 视图通过 `Ask Aura` 直接分析构建错误
- 会话在后台完成时可触发 IDE 通知提醒
- 会话导出为 Markdown
- 中英日韩界面，以及浅色 / 深色 / 跟随 IDE 主题

## 当前项目形态

Aura Code 目前聚焦 IntelliJ IDEA，并依赖本地 Codex 安装运行。插件内部围绕 Codex app-server 工作流实现，同时维护项目本地状态；当运行时支持时，也能加载远端会话历史并继续续聊。

从当前代码看，项目已经具备这些基础设施：

- 基于 Compose 的 Tool Window 界面
- 基于 SQLite 的项目本地会话仓储
- `codex` 与 `node` 的环境检测与启动前校验
- 面向计划、审批、工具调用、文件改动、用户输入的统一事件解析层
- Runtime、Saved Agents、Skills、MCP、主题、语言、通知等设置页

## 环境要求

- 可运行 IntelliJ IDEA 与本地 Codex 的 macOS / Linux / Windows 环境
- JDK 17
- 兼容插件 `sinceBuild = 233` 的 IntelliJ IDEA
- 本地存在 `codex` 可执行文件，或在 `Settings -> Aura Code` 中手动配置
- 当 Codex app-server 依赖 Node 时，本地需要可用的 `node`

## 本地安装

1. 构建插件 ZIP：

```bash
./gradlew buildPlugin
```

2. 在 `build/distributions/` 中找到产物。
3. 打开 IntelliJ IDEA，进入 `Settings -> Plugins -> Install Plugin from Disk...`。
4. 选择生成的 ZIP 安装。
5. 打开 `Settings -> Aura Code`，确认 `Codex Runtime Path` 和可选的 `Node Path`。

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

## 使用方式

1. 打开 `View -> Tool Windows -> Aura Code`。
2. 首次使用时先检查运行时设置。
3. 在输入框中输入任务并发送。
4. 可通过 `@` 添加上下文文件、添加文件或图片附件、通过 `#` 选择本地智能体，或使用 `/plan`、`/auto`、`/new` 等 Slash Commands。
5. 在工具窗口内查看时间线、审批请求、计划确认、工具输入提示和文件 Diff。
6. 需要时可从 History 恢复历史会话，或将会话导出为 Markdown。

## 关键工作流

### 会话与聊天

- 会话按项目隔离，并持久化到本地 SQLite
- 工具窗口支持多个会话标签页
- 切换标签页时，后台会话可继续执行
- 非前台会话完成时，可产生 IntelliJ 通知提醒

### 计划与执行控制

- 输入框支持 `Auto` 与 `Approval` 两种执行模式
- `Plan` 模式支持生成计划、要求修订、直接执行
- 工具用户输入可以在 IDE 内暂停当前任务并收集答案

### 上下文与文件改动

- 自动上下文可跟随当前编辑器文件和选中文本
- 支持手动文件上下文、文件 mention 与附件
- 每次对话中的改动文件会被聚合，并提供 Diff / 打开 / 回退操作

### Skills 与 MCP

- 可从标准本地目录发现可用 Skills
- Skills 支持导入、启用、停用、打开、定位、卸载
- MCP 服务支持 JSON 统一管理、启停、刷新状态、认证与测试
- 同时支持 `stdio` 与 streamable HTTP 两种传输方式

### 构建错误分析

- IntelliJ Problems 视图中提供 `Ask Aura` 动作
- 可将选中的构建/编译错误连同文件路径和行列信息直接发送到 Aura Code

## 项目结构

```text
src/main/kotlin/com/auracode/assistant/
  actions/         IntelliJ 动作，例如快速打开、构建错误转交
  provider/        Codex Provider、app-server 桥接与引擎集成
  service/         聊天、会话、运行时编排服务
  persistence/     基于 SQLite 的本地会话存储
  toolwindow/      Compose UI，包括输入区、时间线、设置、历史、审批等
  settings/        持久化设置、Skills、MCP、本地智能体
  protocol/        统一事件模型与解析层
  integration/     与 IDE 集成的能力，例如构建错误采集
src/test/kotlin/com/auracode/assistant/
  ...              服务、协议解析、UI Store 和核心流程测试
```

## 调试说明

如果插件无法连接 Codex：

- 确认 `codex` 可执行
- 如果配置了 `node`，确认其可执行
- 使用 `Settings -> Aura Code -> Test Environment`
- 从 `Help -> Show Log in Finder/Explorer` 查看 IDE 日志

如果会话历史或续接不符合预期：

- 确认运行时已在插件外完成认证
- 确认恢复的是同一个会话
- 分别检查远端会话历史加载与本地持久化是否正常

## 开源现状

- 当前仓库主要面向 IntelliJ IDEA
- 已支持本地 ZIP 安装
- Marketplace 签名与发布流程尚未接入当前仓库

## License

Aura Code 基于 Apache License 2.0 开源发布。完整许可证内容请查看项目根目录下的 `LICENSE` 文件。
