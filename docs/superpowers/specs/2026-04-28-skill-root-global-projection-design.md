# Skill Root 全局投影设计

**日期：** 2026-04-28

**状态：** 对话中方案已确认，待书面 spec 评审

## 背景

当前 Aura 的 skill 管理链路并不统一：

- `codex` 通过 `SkillsRuntimeService -> CodexSkillsManagementAdapter` 读取 runtime 可见 skill。
- `claude` 通过 `LocalSkillCatalog` 直接扫描本地目录，再结合 Aura 本地设置构造 skill 列表。

这带来两个结构问题：

1. 同一个“设置页 skill 列表”在不同引擎下含义不一致。
2. 新增“导入本地目录 skill”后，如果继续沿用现状，会同时叠加 runtime 分支和本地分支，结构会继续发散。

本次需求的目标不是做一套 Aura 自己解释 `$skill` 的 overlay 运行时，而是：

- 导入一个本地 skill root。
- root 下允许包含多个 skill 子目录。
- Aura 不复制 skill 内容。
- Aura 将这些 skill 以目录投影的方式暴露给各引擎。
- Codex / Claude 继续沿用各自原生 skill 发现和 `$name` 使用链路。

最终效果是：

- 导入发生在 Aura 层。
- 运行时 skill 发现和最终使用仍然复用引擎现有机制。

## 目标

- 统一 Codex 与 Claude 的 skill 管理主链路。
- 支持导入一个本地 skill root，并递归发现其下多个 skill 子目录。
- 导入后对所有受支持引擎执行全局投影，而不是只对当前引擎生效。
- macOS / Linux 使用目录 symlink，Windows 使用目录 junction。
- 设置页继续平铺展示 skill 列表，不做来源分组，也不对同名 skill 去重。
- 运行时 skill 列表、slash 候选、提交前 skill 校验，尽量复用现有 adapter / runtime 读取链路。

## 非目标

- 不实现 Aura 侧的 `$skill` prompt 预展开机制。
- 不复制 skill 内容到 Aura 私有目录作为真实源。
- 不在设置页提供“删除源 skill 目录”能力。
- 不把外部 skill 作为一套独立于 runtime 的第二份运行时视图长期维护。

## 最终方案

最终采用：

1. 先将 `claude` 与 `codex` 收敛到同一套 `SkillsManagementAdapter` 架构。
2. 新增一个全局“skill root 导入”能力。
3. 导入时递归扫描 root 下所有合法 `SKILL.md`。
4. 为每个受支持引擎在其受管 skill 目录中创建目录级投影：
   - macOS / Linux：`symlink`
   - Windows：`junction`
5. skill 的加载、展示、slash 暴露、提交使用全部继续走引擎现有 skill 机制。

这个方案的核心定义是：

- 外部 root 是真实源。
- Aura 只负责导入、投影与移除。
- 引擎仍然消费“它自己目录里可见的 skill”。

## 核心设计原则

### 1. Import Once, Reuse Runtime Flow

导入逻辑与运行时使用逻辑分离。

- 导入阶段由 Aura 完成 root 扫描和投影建立。
- 导入成功后，后续 skill 读取和使用应尽可能复用现有 runtime 链路。

### 2. One Real Source, Many Projections

外部 root 是唯一真实源。

- 不复制 skill 内容。
- 每个引擎只拥有自己的目录投影视图。

### 3. Projection Is Engine-Specific

投影结果必须按引擎分别建立，而不是混用一个公共投影目录。

- Codex 使用自己的受管目录。
- Claude 使用自己的受管目录。

### 4. UI Stays Flat

设置页保持平铺 skill 列表：

- 不按 root 分组。
- 不按来源分组。
- 同名 skill 全部展示。

来源差异只用于行级动作和轻量辅助信息。

## Claude 与 Codex 的统一主链路

### 当前问题

当前 `EngineSkillsService` 通过 `usesRuntimeManagement(engineId)` 进行分叉：

- `codex` 走 runtime adapter
- `claude` 走 `LocalSkillCatalog`

这使得：

- 设置页加载路径不统一
- 开关路径不统一
- slash skill 来源不统一
- 提交前禁用校验不统一

### 调整方案

保留 `EngineSkillsService` 作为上层 façade，但去掉 `claude/local` 特殊分支。

统一后的主链路为：

- `EngineSkillsService`
- `SkillsRuntimeService`
- `SkillsManagementAdapterRegistry`
- `CodexSkillsManagementAdapter`
- `ClaudeSkillsManagementAdapter`

其中：

- `CodexSkillsManagementAdapter` 继续复用现有 Codex runtime 技术路径。
- `ClaudeSkillsManagementAdapter` 新增，负责读取 Claude 可见 skill 目录并执行开关操作。

### Claude 目录约定

Claude 的用户级 skill 目录采用：

- Unix-like：`~/.claude/skills`
- Windows：`%USERPROFILE%\\.claude\\skills`

这里的设计基于 Claude Code 用户目录约定 `~/.claude/...`，并在 Windows 上按用户主目录展开为本地路径。

## Skill Root 导入模型

### 导入对象

导入对象是一个 **skill root**，而不是单个 skill 目录。

该 root 允许：

- root 本身就是一个 skill 目录
- root 下存在多个 skill 子目录
- root 下存在嵌套目录中的 skill

### 扫描规则

导入时递归查找 `SKILL.md`：

- 每个 `SKILL.md` 的父目录视为一个独立 skill
- 必须能解析出合法 front matter `name`
- 非法 skill 跳过，并在导入结果中返回诊断信息

## Projection 策略

### 平台策略

- macOS / Linux：创建目录 `symlink`
- Windows：创建目录 `junction`

这两者在上层统一抽象为目录投影，不让业务层直接感知平台差异。

### Projection 粒度

投影粒度为 **目录级 skill projection**：

- 一个 skill 目录对应目标引擎目录中的一个 projection 目录项
- 不做单文件级投影

### 全局投影

导入成功后，Aura 为所有受支持引擎建立投影。

当前范围至少包括：

- `codex`
- `claude`

这意味着：

- root 导入是全局行为
- 不依赖当前设置页选中的 engine

### 同名冲突

导入时如果发现目标引擎目录中已存在同名 skill，直接报错。

本次不做：

- 覆盖
- 自动重命名
- 跳过后继续导入

冲突检测应在 projection 创建前完成，避免部分成功、部分失败导致目录状态不一致。

### 移除语义

不提供“删除源目录”。

对已导入 skill，只提供当前 engine 视角下的删除：

- 删除当前 engine 的 skill 入口

其语义是：

1. 由 adapter 返回一条具体 skill 记录
2. 设置页对该记录返回的 `path` 直接执行删除
3. 本期不额外区分该路径是 projection 路径还是源路径

本期不保留 root 级导入记录，因此也不提供“按 root 整体移除”的能力。

## Settings 页面模型

### 展示原则

设置页按最终运行时可见 skill 平铺展示：

- 不按 root 分组
- 不按引擎来源分组
- 不对同名 skill 去重

### 记录 identity

由于同名 skill 可并存，设置页每一条记录不能只靠 `name` 标识。

建议 `ManagedSkillEntry` 最终具备稳定 identity，例如：

- `entryId`
- `engineId`
- `path`

### 行级动作

所有记录都支持：

- `Open`
- `Reveal`

所有删除、打开、定位行为都直接作用在 adapter 返回的 `path` 上。

本期不新增：

- `sourcePath`
- projection path 与 source path 的区分语义
- UI 层二次判定

### 关于 Enable / Disable

本次设计不新增 Aura 自己的 skill enablement 逻辑。

导入完成后：

- skill 是否可见
- skill 是否能被 runtime 发现
- skill 的路径如何返回

都交由底层引擎 adapter 与现有 runtime 机制决定。

因此本次不新增：

- 独立 Aura disabled 状态
- 独立 Aura source-path/source-of-truth 逻辑

## 运行时链路复用边界

### 可以直接复用的部分

一旦 projection 建立成功，以下链路应直接复用现有实现：

- skill 列表加载
- slash skill 候选
- 提交前 disabled skill 校验
- 最终 `$name` 发送与引擎原生解析

### 仍需 Aura 负责的部分

即使运行时链路复用成功，Aura 仍需负责：

- 导入 root
- 扫描 root 下 skill
- 为各 engine 建立 projection
- 删除当前 engine skill 入口时执行已有删除行为

所以本方案不是“完全不读目录”，而是：

- **不再为了运行时使用去读目录**
- **仍然要为了导入与投影管理读目录**

## 需要新增或调整的组件

### 1. ClaudeSkillsManagementAdapter

新增 `ClaudeSkillsManagementAdapter`，实现 `SkillsManagementAdapter`：

- 读取 Claude 可见 skill 目录
- 切换 skill 启用状态
- 返回统一 `RuntimeSkillRecord`

### 2. SkillRootScanner

新增 root 扫描组件：

- 递归查找 `SKILL.md`
- 解析 skill descriptor
- 返回导入结果和诊断信息

### 3. SkillProjectionManager

新增 projection 协调组件：

- 按平台创建 `symlink` / `junction`
- 为全部受支持引擎建立 projection
- 为导入动作提供冲突检测
- 为删除动作复用现有 skill 入口删除行为

### 4. EngineSkillDirectoryResolver

新增目录 resolver：

- 返回 Codex 受管 skill 目录：`~/.codex/skills/`
- 返回 Claude 默认本地技能目录：`~/.claude/skills/`
- 返回 Claude 插件市场技能目录：`~/.claude/plugins/cache/`

### 5. LocalSkillCatalog 的处理

`LocalSkillCatalog` 不再保留为顶层业务入口。

其中可复用的能力应拆到更底层组件，例如：

- `SkillDescriptorParser`
- `DirectorySkillScanner`
- `ManagedSkillPathPolicy`

避免 Claude adapter 与 root 扫描重复实现目录解析逻辑。

## 风险与注意点

### 1. 同名 skill 的运行时歧义

设置页可以展示多个同名 skill，但底层引擎原生 `$name` 解析是否能正确处理同名项，取决于引擎自己的发现规则。

本次设计不引入 Aura 侧 prompt 展开，因此需要接受一个前提：

- 运行时唯一性主要依赖引擎自身对 skill 目录的处理能力

如果后续验证发现引擎不能稳定处理同名 skill，则需要补充更严格的 projection 命名或导入校验策略。

### 2. Projection 与原生安装目录的边界

projection 直接写入引擎真实用户 skill 目录：

- Codex：`~/.codex/skills/`
- Claude：`~/.claude/skills/`

本期不增加额外投影元数据层，因此删除行为完全依赖 adapter 返回的实际路径。

### 3. 断链与失效投影

当源 root 被移动、删除或部分子 skill 消失时：

- symlink / junction 可能失效
- adapter 读到的 skill 列表会变化

设置页刷新时应允许执行一次投影修复或最小清理。

### 4. Windows 特性差异

Windows 下明确使用 junction，而不是 symlink：

- 避免 symlink 权限和开发者模式要求
- 更适合目录级投影

## 实施顺序

### 阶段一：统一 Claude / Codex skill 主链路

1. 新增 `ClaudeSkillsManagementAdapter`
2. 在 `SkillsManagementAdapterRegistry` 中注册 Claude
3. 去掉 `EngineSkillsService` 中 `claude -> LocalSkillCatalog` 特殊分支
4. 让 skill 列表、slash、禁用校验全部走统一 adapter 链路

### 阶段二：增加全局 root 导入与 projection

1. 新增 `SkillRootScanner`
2. 新增 `EngineSkillDirectoryResolver`
3. 新增 `SkillProjectionManager`
4. 设置页增加“导入本地目录”入口
5. 导入时扫描 root 并为全部引擎建立 projection
6. 同名 skill 冲突时直接报错
7. 删除、打开、定位继续按 adapter 返回 path 复用现有行为

## 国际化要求

本次新增或调整的设置页文案、错误文案、确认提示都必须补齐 i18n 资源。

至少需要覆盖：

- 导入本地目录
- 导入成功
- 导入失败
- root 中未发现合法 skill
- 同名 skill 冲突
- projection 创建失败 / 权限失败 / 路径冲突

## 不需要改动的边界

以下边界不属于本次设计范围：

- provider 协议事件映射
- 会话 domain event 结构
- `ProviderProtocolDomainMapper`

原因是：

- skill root 导入与 projection 发生在运行时会话发起之前
- 该能力不改变 provider 输出协议，只改变引擎启动前可见的 skill 文件系统布局
