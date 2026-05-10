package com.auracode.assistant.provider.claude

import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.provider.codex.CodexEnvironmentStatus
import com.auracode.assistant.provider.runtime.DefaultRuntimeLaunchResolver
import com.auracode.assistant.provider.runtime.RuntimeLaunchResolution
import com.auracode.assistant.provider.runtime.RuntimeLaunchResolver
import com.auracode.assistant.settings.AgentSettingsService
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal interface ClaudeCliLauncher {
    fun start(
        request: AgentRequest,
        settings: AgentSettingsService,
    ): ClaudeStreamJsonSession
}

internal class DefaultClaudeCliLauncher(
    private val runtimeLaunchResolver: RuntimeLaunchResolver = DefaultRuntimeLaunchResolver(),
    private val processStarter: ClaudeProcessStarter = DefaultClaudeProcessStarter(),
) : ClaudeCliLauncher {
    override fun start(
        request: AgentRequest,
        settings: AgentSettingsService,
    ): ClaudeStreamJsonSession {
        val resolution = resolveLaunch(settings)
        require(resolution.cliStatus != CodexEnvironmentStatus.MISSING) {
            "Claude CLI was not found. Install the CLI first or configure a valid executable path in Settings."
        }
        require(resolution.cliStatus != CodexEnvironmentStatus.FAILED) {
            "Configured Claude CLI path is not executable. Update Settings and try again."
        }
        if (settings.nodeExecutablePath().isNotBlank()) {
            require(resolution.nodeStatus != CodexEnvironmentStatus.FAILED) {
                "Configured Node Path is not executable. Update Settings and try again."
            }
        }
        val process = processStarter.start(
            command = buildCommand(request, resolution.cliPath),
            workingDirectory = File(request.workingDirectory),
            environmentOverrides = resolution.environmentOverrides,
        )
        if (needsPermissionPromptTool(request)) {
            writeMultimodalMessage(process, request)
        } else {
            if (request.imageAttachments.isNotEmpty() && usesStdinForImages(request)) {
                writeMultimodalMessage(process, request)
            }
            closeProcessInput(process)
        }
        return ProcessClaudeStreamJsonSession(process)
    }

    internal fun buildCommand(
        request: AgentRequest,
        executable: String,
    ): List<String> {
        val hasImages = request.imageAttachments.isNotEmpty()
        val useStdinForImages = hasImages && usesStdinForImages(request)
        return buildList {
            add(executable)
            add("-p")
            add("--verbose")
            add("--output-format")
            add("stream-json")
            add("--include-partial-messages")
            add("--permission-mode")
            add(resolvePermissionMode(request))
            if (needsPermissionPromptTool(request)) {
                add("--permission-prompt-tool")
                add("stdio")
            }
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
                add("--input-format")
                add("stream-json")
            } else {
                add(renderPrompt(request))
            }
        }
    }

    private fun mapReasoningEffort(effort: String): String = when (effort) {
        "xhigh" -> "high"
        else -> effort
    }

    private fun resolvePermissionMode(request: AgentRequest): String {
        if (request.collaborationMode == AgentCollaborationMode.PLAN) {
            return "plan"
        }
        return when (request.approvalMode) {
            AgentApprovalMode.AUTO -> "auto"
            AgentApprovalMode.REQUIRE_CONFIRMATION -> "default"
        }
    }

    private fun needsPermissionPromptTool(request: AgentRequest): Boolean {
        return request.approvalMode == AgentApprovalMode.REQUIRE_CONFIRMATION ||
            request.collaborationMode == AgentCollaborationMode.PLAN
    }

    private fun usesStdinForImages(request: AgentRequest): Boolean {
        return request.approvalMode != AgentApprovalMode.REQUIRE_CONFIRMATION
    }

    private fun renderPrompt(request: AgentRequest): String {
        return buildPromptText(request)
    }

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

    private fun closeProcessInput(process: Process) {
        runCatching { process.outputStream.close() }
            .getOrElse { error ->
                process.destroyForcibly()
                throw error
            }
    }

    private fun resolveLaunch(settings: AgentSettingsService): RuntimeLaunchResolution {
        return runtimeLaunchResolver.resolve(
            commandName = ClaudeProviderFactory.ENGINE_ID,
            configuredCliPath = settings.state.executablePathFor(ClaudeProviderFactory.ENGINE_ID),
            configuredNodePath = settings.nodeExecutablePath(),
        )
    }
}
