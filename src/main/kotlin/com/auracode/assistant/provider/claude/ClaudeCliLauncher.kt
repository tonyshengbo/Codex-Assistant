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
    /** 启动 Claude CLI 进程，并在启动后立即关闭 stdin，避免进程等待额外输入。 */
    override fun start(
        request: AgentRequest,
        settings: AgentSettingsService,
    ): ClaudeStreamJsonSession {
        val process = processStarter.start(
            command = buildCommand(request, settings),
            workingDirectory = File(request.workingDirectory),
        )
        // 只有在不需要 stdio 控制通道时才通过 stdin 发送多模态消息。
        // 当 approvalMode=REQUIRE_CONFIRMATION 或 plan 模式时，stdin 专用于 control_response，
        // 图片已通过 --image 参数传递，无需再写 stdin。
        if (request.imageAttachments.isNotEmpty() && !needsPermissionPromptTool(request)) {
            writeMultimodalMessage(process, request)
        }
        // 授权模式和计划模式需要保持 stdin 开放，以便写回 control_response；其它模式立即关闭。
        if (request.approvalMode != AgentApprovalMode.REQUIRE_CONFIRMATION &&
            request.collaborationMode != AgentCollaborationMode.PLAN) {
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
        // 当需要 stdio 控制通道时，stdin 专用于 control_response，不能再用 --input-format stream-json。
        // 此时图片通过 --image <path> 参数传递，避免 stdin 协议冲突。
        val useStdinForImages = hasImages && !needsPermissionPromptTool(request)
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
            if (needsPermissionPromptTool(request)) {
                // 启用 stdio 控制通道，CLI 会通过 stdout 发出 control_request，通过 stdin 接收 control_response。
                add("--permission-prompt-tool")
                add("stdio")
            }
            request.model?.trim()?.takeIf { it.isNotBlank() }?.let { model ->
                add("--model")
                add(model)
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
            if (useStdinForImages) {
                // 无控制通道时，通过 stdin 以 stream-json 格式发送多模态消息，prompt 不作为 CLI 参数传入。
                add("--input-format")
                add("stream-json")
            } else {
                // 有控制通道或无图片时，prompt 直接作为 CLI 参数传入。
                add(renderPrompt(request))
                // 有图片且需要控制通道时，通过 --image 参数传递图片文件路径，避免占用 stdin。
                if (hasImages) {
                    request.imageAttachments.forEach { attachment ->
                        add("--image")
                        add(attachment.path)
                    }
                }
            }
        }
    }

    /** 将 IDE 不同协作模式映射为 Claude CLI 需要的 permission-mode。 */
    private fun resolvePermissionMode(request: AgentRequest): String {
        return when {
            request.collaborationMode == AgentCollaborationMode.PLAN -> "plan"
            request.approvalMode == AgentApprovalMode.REQUIRE_CONFIRMATION -> "default"
            else -> "auto"
        }
    }

    /** 授权模式下是否需要启用 stdio 控制通道。 */
    private fun needsPermissionPromptTool(request: AgentRequest): Boolean {
        return request.approvalMode == AgentApprovalMode.REQUIRE_CONFIRMATION ||
            request.collaborationMode == AgentCollaborationMode.PLAN
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
