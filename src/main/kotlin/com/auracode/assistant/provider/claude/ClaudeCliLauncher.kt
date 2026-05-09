package com.auracode.assistant.provider.claude

import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.settings.AgentSettingsService
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal interface ClaudeCliLauncher {
    /** 启动一条 Claude CLI 会话，并返回对应的流式会话句柄。 */
    fun start(
        request: AgentRequest,
        settings: AgentSettingsService,
    ): ClaudeStreamJsonSession
}

/**
 * 负责拼装 Claude CLI 命令，并创建对应的流式会话。
 */
internal class DefaultClaudeCliLauncher(
    private val processStarter: ClaudeProcessStarter = DefaultClaudeProcessStarter(),
) : ClaudeCliLauncher {
    /** 启动 Claude CLI 进程，并根据模式决定 stdin 的初始化策略。 */
    override fun start(
        request: AgentRequest,
        settings: AgentSettingsService,
    ): ClaudeStreamJsonSession {
        val process = processStarter.start(
            command = buildCommand(request, settings),
            workingDirectory = File(request.workingDirectory),
        )
        if (needsPermissionPromptTool(request)) {
            // 控制通道模式：stdin 保持打开用于后续 control_response 写入。
            // 始终通过 stdin 发送 prompt（stream-json 格式），避免 CLI 的 stdin 3s 超时。
            writeMultimodalMessage(process, request)
        } else {
            // 无控制通道：有图片时发送多模态消息后关闭，无图片时直接关闭。
            if (request.imageAttachments.isNotEmpty() && usesStdinForImages(request)) {
                writeMultimodalMessage(process, request)
            }
            closeProcessInput(process)
        }
        return ProcessClaudeStreamJsonSession(process)
    }

    /** 生成 Claude CLI 的完整命令行参数。 */
    internal fun buildCommand(
        request: AgentRequest,
        settings: AgentSettingsService,
    ): List<String> {
        val executable = settings.state.executablePathFor(ClaudeProviderFactory.ENGINE_ID)
            .trim()
            .ifBlank { ClaudeProviderFactory.ENGINE_ID }
        val hasImages = request.imageAttachments.isNotEmpty()
        val useStdinForImages = hasImages && usesStdinForImages(request)
        return buildList {
            add(executable)
            add("-p")
            // Claude CLI 要求 stream-json 打印模式必须显式开启 verbose。
            add("--verbose")
            add("--output-format")
            add("stream-json")
            add("--include-partial-messages")
            add("--permission-mode")
            add(resolvePermissionMode(request))
//            if (needsPermissionPromptTool(request)) {
                // 启用 stdio 控制通道，CLI 会通过 stdout 发出 control_request，通过 stdin 接收 control_response。
                add("--permission-prompt-tool")
                add("stdio")
//            }
            request.model?.trim()?.takeIf { it.isNotBlank() }?.let { model ->
                add("--model")
                add(model)
            }
            request.reasoningEffort?.trim()?.takeIf { it.isNotBlank() }?.let { effort ->
                add("--effort")
                add(mapReasoningEffort(effort))
            }
            request.remoteConversationId?.trim()?.takeIf { it.isNotBlank() }?.let { sessionId ->
                add("--resume")
                add(sessionId)
            }
            request.systemInstructions
                .map(String::trim)
                .filter(String::isNotBlank)
                .forEach { instruction ->
                    add("--append-system-prompt")
                    add(instruction)
                }
            if (needsPermissionPromptTool(request) || useStdinForImages) {
                // 控制通道模式或多模态模式：prompt 通过 stdin stream-json 发送，不作为 CLI 参数传入。
                add("--input-format")
                add("stream-json")
            } else {
                // 无控制通道且无图片时，prompt 直接作为 CLI 参数传入。
                add(renderPrompt(request))
            }
        }
    }

    /** 将内部 effort 值映射为 Claude CLI 可接受的参数值（xhigh 映射为 high）。 */
    private fun mapReasoningEffort(effort: String): String = when (effort) {
        "xhigh" -> "high"
        else -> effort
    }

    /** 将 IDE 审批模式映射为 Claude CLI 需要的 permission-mode。
     *  Plan 协作模式优先使用 CLI 原生的 plan 权限模式，强制 Claude 只读和探索，不执行修改。
     *  计划完成后 CLI 通过 AskUserQuestion 控制通道让用户选择执行模式。
     */
    private fun resolvePermissionMode(request: AgentRequest): String {
        if (request.collaborationMode == AgentCollaborationMode.PLAN) {
            return "plan"
        }
        return when (request.approvalMode) {
            AgentApprovalMode.AUTO -> "auto"
            AgentApprovalMode.REQUIRE_CONFIRMATION -> "default"
        }
    }

    /** 只有授权模式（REQUIRE_CONFIRMATION）或 Plan 协作模式需要启用 stdio 控制通道。
     *  Plan 模式下 AskUserQuestion 工具需要通过控制通道与 IDE 交互。
     */
    private fun needsPermissionPromptTool(request: AgentRequest): Boolean {
        return request.approvalMode == AgentApprovalMode.REQUIRE_CONFIRMATION ||
            request.collaborationMode == AgentCollaborationMode.PLAN
    }

    /** 判断是否需要通过 stdin stream-json 发送图片。
     *  仅在无控制通道（纯 AUTO 模式）或 Plan 模式下使用 stdin 发送图片。
     *  REQUIRE_CONFIRMATION 模式的 stdin 专用于控制通道，不能混用。
     */
    private fun usesStdinForImages(request: AgentRequest): Boolean {
        return request.approvalMode != AgentApprovalMode.REQUIRE_CONFIRMATION
    }

    /** 将上下文文件与附件整理成 Claude CLI 可直接消费的纯文本 prompt。 */
    private fun renderPrompt(request: AgentRequest): String {
        return buildPromptText(request)
    }

    /**
     * 构建纯文本部分的 prompt，不含图片路径引用（图片通过 stream-json 内容块单独发送）。
     */
    internal fun buildPromptText(request: AgentRequest): String {
        val promptBody = request.prompt.trim()
        val contextSection = request.contextFiles
            .map { context ->
                buildString {
                    append("Path: ").append(context.path)
                    context.content?.trim()?.takeIf { it.isNotBlank() }?.let { content ->
                        append("\n```")
                        append("\n")
                        append(content)
                        append("\n```")
                    }
                }
            }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n\n", prefix = "Context files:\n", postfix = "\n\n")
            .orEmpty()
        val fileAttachmentLines = request.fileAttachments.map { "File: ${it.name} (${it.path})" }
        val attachmentSection = fileAttachmentLines
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n", prefix = "Local attachments:\n", postfix = "\n\n")
            .orEmpty()
        val explicitPrompt = promptBody.ifBlank { "Follow the provided system instructions and context." }
        return buildString {
            append(contextSection)
            append(attachmentSection)
            append(explicitPrompt)
        }
    }

    /**
     * 将 prompt 文本与图片附件序列化为 stream-json 用户消息，写入进程 stdin。
     * Claude CLI 以 --input-format stream-json 启动时从 stdin 读取此消息。
     */
    private fun writeMultimodalMessage(process: Process, request: AgentRequest) {
        val textContent = buildPromptText(request)
        val contentArray = buildJsonArray {
            if (textContent.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", textContent)
                })
            }
            request.imageAttachments.forEach { attachment ->
                val imageFile = File(attachment.path)
                if (imageFile.exists()) {
                    val base64Data = Base64.getEncoder().encodeToString(imageFile.readBytes())
                    add(buildJsonObject {
                        put("type", "image")
                        put("source", buildJsonObject {
                            put("type", "base64")
                            put("media_type", attachment.mimeType)
                            put("data", base64Data)
                        })
                    })
                }
            }
        }
        val message = buildJsonObject {
            put("type", "user")
            put("message", buildJsonObject {
                put("role", "user")
                put("content", contentArray)
            })
        }
        process.outputStream.bufferedWriter(Charsets.UTF_8).apply {
            write(message.toString())
            newLine()
            flush()
        }
    }

    /** 关闭 Claude CLI 的标准输入，明确告知不会再有补充输入，避免进程卡在等待 EOF。 */
    private fun closeProcessInput(process: Process) {
        runCatching { process.outputStream.close() }
            .getOrElse { error ->
                process.destroyForcibly()
                throw error
            }
    }
}
