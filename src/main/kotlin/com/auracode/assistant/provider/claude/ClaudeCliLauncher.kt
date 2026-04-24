package com.auracode.assistant.provider.claude

import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.settings.AgentSettingsService
import java.io.File

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
        // 授权模式需要保持 stdin 开放，以便写回 control_response；其它模式立即关闭。
        if (request.approvalMode != AgentApprovalMode.REQUIRE_CONFIRMATION) {
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
            add(renderPrompt(request))
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
        return request.approvalMode == AgentApprovalMode.REQUIRE_CONFIRMATION &&
            request.collaborationMode != AgentCollaborationMode.PLAN
    }

    /** 将上下文文件与附件整理成 Claude CLI 可直接消费的纯文本 prompt。 */
    private fun renderPrompt(request: AgentRequest): String {
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
        val attachmentLines = buildList {
            request.imageAttachments.forEach { attachment ->
                add("Image: ${attachment.name} (${attachment.path})")
            }
            request.fileAttachments.forEach { attachment ->
                add("File: ${attachment.name} (${attachment.path})")
            }
        }
        val attachmentSection = attachmentLines
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

    /** 关闭 Claude CLI 的标准输入，明确告知不会再有补充输入，避免进程卡在等待 EOF。 */
    private fun closeProcessInput(process: Process) {
        runCatching { process.outputStream.close() }
            .getOrElse { error ->
                process.destroyForcibly()
                throw error
            }
    }
}
