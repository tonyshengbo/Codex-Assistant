package com.codex.assistant.toolwindow.timeline

import com.codex.assistant.model.ChatMessage
import com.codex.assistant.model.MessageRole
import com.codex.assistant.model.TimelineAction
import com.codex.assistant.model.TimelineActionCodec
import com.codex.assistant.model.TimelineActionStatus
import com.codex.assistant.model.TimelineNarrativeKind
import java.io.File
import java.util.Base64

class ConversationTimelineBuilder(
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    fun build(
        messages: List<ChatMessage>,
        liveTurn: LiveTurnSnapshot? = null,
    ): List<TimelineTurnViewModel> {
        val turns = buildHistoricalTurns(messages).toMutableList()
        if (liveTurn == null) {
            return turns
        }

        val liveNodes = buildLiveNodes(liveTurn)
        if (turns.isEmpty()) {
            turns += createTurnViewModel(
                id = "turn-live",
                userMessage = null,
                nodes = liveNodes,
                isRunning = liveTurn.isRunning,
                statusTextOverride = liveTurn.statusText,
                startedAtMs = resolveLiveStartedAtMs(
                    liveTurn = liveTurn,
                    fallbackStartedAtMs = null,
                    nodes = liveNodes,
                ),
                completedAtMs = liveCompletedAtMs(liveTurn, liveNodes),
            )
            return turns
        }

        val lastTurn = turns.removeLast()
        val mergedNodes = mergeSequencedNodes(lastTurn.nodes + liveNodes)
        turns += createTurnViewModel(
            id = lastTurn.id,
            userMessage = lastTurn.userMessage,
            nodes = mergedNodes,
            isRunning = liveTurn.isRunning,
            statusTextOverride = liveTurn.statusText,
            startedAtMs = resolveLiveStartedAtMs(
                liveTurn = liveTurn,
                fallbackStartedAtMs = lastTurn.userMessage?.timestamp,
                nodes = mergedNodes,
            ),
            completedAtMs = liveCompletedAtMs(liveTurn, mergedNodes),
        )
        return turns
    }

    private fun buildHistoricalTurns(messages: List<ChatMessage>): List<TimelineTurnViewModel> {
        if (messages.isEmpty()) return emptyList()

        data class TurnAccumulator(
            var user: ChatMessage? = null,
            var assistant: ChatMessage? = null,
            val systems: MutableList<ChatMessage> = mutableListOf(),
        )

        val turns = mutableListOf<TimelineTurnViewModel>()
        var current = TurnAccumulator()

        fun flush() {
            if (current.user == null && current.assistant == null && current.systems.isEmpty()) {
                return
            }
            val assistantNodes = current.assistant?.let(::buildAssistantNodes).orEmpty()
            val systemNodes = current.systems.flatMap(::buildSystemNodes)
            val idSource = current.assistant?.id ?: current.user?.id ?: "turn-${turns.size + 1}"
            val nodes = assistantNodes + systemNodes
            turns += createTurnViewModel(
                id = "turn-$idSource",
                userMessage = current.user,
                nodes = nodes,
                isRunning = false,
                startedAtMs = listOfNotNull(
                    current.user?.timestamp,
                    current.assistant?.timestamp,
                    current.systems.map { it.timestamp }.minOrNull(),
                    nodes.minNodeTimestamp(),
                ).minOrNull(),
                completedAtMs = listOfNotNull(
                    current.assistant?.timestamp,
                    current.systems.map { it.timestamp }.maxOrNull(),
                    nodes.maxNodeFinishedTimestamp(),
                ).maxOrNull(),
            )
            current = TurnAccumulator()
        }

        messages.forEach { message ->
            when (message.role) {
                MessageRole.USER -> {
                    flush()
                    current.user = message
                }

                MessageRole.ASSISTANT -> {
                    if (current.assistant == null) {
                        current.assistant = message
                    } else {
                        flush()
                        current.assistant = message
                    }
                }

                MessageRole.SYSTEM -> current.systems += message
            }
        }

        flush()
        return turns
    }

    private fun buildAssistantNodes(message: ChatMessage): List<TimelineNodeViewModel> {
        val actionNodes = TimelineActionCodec.decode(message.timelineActionsPayload)
            .takeIf { it.isNotEmpty() }
            ?.let { buildActionNodes(it, isRunning = false, defaultTimestamp = message.timestamp) }
        if (actionNodes != null) {
            return actionNodes
        }

        val sections = extractSections(message.content)
        val response = sections["response"].orEmpty()
        val thinking = sections["thinking"].orEmpty()
        val narratives = parseNarrativeLines(sections["narrative"].orEmpty(), message.timestamp)
        val tools = parseToolLines(sections["tools"].orEmpty()).ifEmpty {
            inferToolLinesFromResponse(response.ifBlank { message.content })
        }
        val commands = parseCommandLines(sections["commands"].orEmpty()).ifEmpty {
            inferCommandLinesFromResponse(response.ifBlank { sanitizeNarrativeText(message.content) })
        }
        val nodes = mutableListOf<TimelineNodeViewModel>()

        if (thinking.isNotBlank()) {
            nodes += TimelineNodeViewModel(
                id = "${message.id}-thinking",
                kind = TimelineNodeKind.THINKING,
                title = "Thinking",
                body = thinking,
                status = TimelineNodeStatus.SUCCESS,
                expanded = false,
                timestamp = message.timestamp,
                origin = TimelineNodeOrigin.EVENT,
            )
        }

        val stepNodes = buildStepNodes(
            tools = tools,
            commands = commands,
            defaultTimestamp = message.timestamp,
            idPrefix = message.id,
        )

        nodes += if (narratives.isNotEmpty()) {
            mergeSequencedNodes(stepNodes + narratives.map(::toNarrativeNode))
        } else {
            interleaveResponseBlocks(
                responseText = when {
                    response.isNotBlank() -> sanitizeNarrativeText(response)
                    sections.isEmpty() -> sanitizeNarrativeText(message.content)
                    else -> ""
                },
                stepNodes = stepNodes,
                idPrefix = message.id,
                defaultTimestamp = message.timestamp,
                includeTerminalResult = true,
            )
        }

        return nodes
    }

    private fun buildSystemNodes(message: ChatMessage): List<TimelineNodeViewModel> {
        val content = message.content.trim()
        if (content.isBlank()) return emptyList()

        val isFailure = content.contains("error:", ignoreCase = true) ||
            content.contains("失败") ||
            content.contains("exit code", ignoreCase = true)

        return listOf(
            TimelineNodeViewModel(
                id = "${message.id}-${if (isFailure) "failure" else "system"}",
                kind = if (isFailure) TimelineNodeKind.FAILURE else TimelineNodeKind.SYSTEM_AUX,
                title = if (isFailure) "Failure" else "System",
                body = content,
                status = if (isFailure) TimelineNodeStatus.FAILED else TimelineNodeStatus.SUCCESS,
                expanded = !isFailure,
                timestamp = message.timestamp,
                origin = TimelineNodeOrigin.EVENT,
            ),
        )
    }

    private fun buildLiveNodes(liveTurn: LiveTurnSnapshot): List<TimelineNodeViewModel> {
        if (liveTurn.actions.isNotEmpty()) {
            return buildActionNodes(
                actions = liveTurn.actions,
                isRunning = liveTurn.isRunning,
                defaultTimestamp = null,
            )
        }

        val stepNodes = buildLiveStepNodes(liveTurn)
        val narrativeNodes = if (liveTurn.notes.isNotEmpty()) {
            liveTurn.notes
                .filterNot { shouldHideLifecycleNarrative(it.body) }
                .map(::toLiveNarrativeNode)
        } else {
            interleaveResponseBlocks(
                responseText = sanitizeNarrativeText(liveTurn.assistantContent),
                stepNodes = stepNodes,
                idPrefix = "live",
                defaultTimestamp = null,
                includeTerminalResult = !liveTurn.isRunning,
            )
        }

        val nodes = if (liveTurn.notes.isNotEmpty()) {
            mergeSequencedNodes(stepNodes + narrativeNodes)
        } else {
            narrativeNodes
        }

        if (liveTurn.thinking.isNotBlank()) {
            val thinkingNode = TimelineNodeViewModel(
                id = "live-thinking",
                kind = TimelineNodeKind.THINKING,
                title = "Thinking",
                body = liveTurn.thinking,
                status = if (liveTurn.isRunning) TimelineNodeStatus.RUNNING else TimelineNodeStatus.SUCCESS,
                expanded = liveTurn.isRunning,
                origin = TimelineNodeOrigin.EVENT,
            )
            val insertAt = if (nodes.firstOrNull()?.kind == TimelineNodeKind.ASSISTANT_NOTE) 1 else 0
            return nodes.toMutableList().apply { add(insertAt, thinkingNode) }
        }

        return nodes
    }

    private fun createTurnViewModel(
        id: String,
        userMessage: ChatMessage?,
        nodes: List<TimelineNodeViewModel>,
        isRunning: Boolean,
        statusTextOverride: String = "",
        startedAtMs: Long? = null,
        completedAtMs: Long? = null,
    ): TimelineTurnViewModel {
        val footerStatus = resolveFooterStatus(isRunning, nodes)
        val statusText = resolveStatusText(
            footerStatus = footerStatus,
            isRunning = isRunning,
            statusTextOverride = statusTextOverride,
            nodes = nodes,
        )
        val durationMs = computeDurationMs(startedAtMs, completedAtMs)
        return TimelineTurnViewModel(
            id = id,
            userMessage = userMessage,
            nodes = nodes,
            isRunning = isRunning,
            footerStatus = footerStatus,
            statusText = statusText,
            durationMs = durationMs,
        )
    }

    private fun liveCompletedAtMs(
        liveTurn: LiveTurnSnapshot,
        nodes: List<TimelineNodeViewModel>,
    ): Long? {
        return when {
            liveTurn.isRunning -> nowProvider()
            else -> nodes.maxNodeFinishedTimestamp()
        }
    }

    private fun resolveLiveStartedAtMs(
        liveTurn: LiveTurnSnapshot,
        fallbackStartedAtMs: Long?,
        nodes: List<TimelineNodeViewModel>,
    ): Long? {
        if (liveTurn.startedAtMs != null) {
            return liveTurn.startedAtMs
        }
        if (liveTurn.isRunning) {
            return null
        }
        return fallbackStartedAtMs ?: nodes.minNodeTimestamp()
    }

    private fun resolveFooterStatus(
        isRunning: Boolean,
        nodes: List<TimelineNodeViewModel>,
    ): TimelineNodeStatus {
        if (isRunning) {
            return TimelineNodeStatus.RUNNING
        }
        return when {
            nodes.any { it.status == TimelineNodeStatus.FAILED } -> TimelineNodeStatus.FAILED
            nodes.isNotEmpty() && nodes.all { it.status == TimelineNodeStatus.SKIPPED } -> TimelineNodeStatus.SKIPPED
            else -> TimelineNodeStatus.SUCCESS
        }
    }

    private fun resolveStatusText(
        footerStatus: TimelineNodeStatus,
        isRunning: Boolean,
        statusTextOverride: String,
        nodes: List<TimelineNodeViewModel>,
    ): String {
        val normalized = statusTextOverride.trim()
        if (isRunning && nodes.isEmpty() && (normalized.isBlank() || isStartupPhaseStatus(normalized))) {
            return "等待响应中"
        }
        if (normalized.isNotBlank() && !shouldHideLifecycleNarrative(normalized)) {
            return normalized
        }
        if (isRunning) {
            return "执行中..."
        }
        return when (footerStatus) {
            TimelineNodeStatus.FAILED -> "执行失败"
            TimelineNodeStatus.SKIPPED -> "已跳过"
            TimelineNodeStatus.RUNNING -> "执行中..."
            TimelineNodeStatus.SUCCESS -> "已完成"
        }
    }

    private fun computeDurationMs(
        startedAtMs: Long?,
        completedAtMs: Long?,
    ): Long? {
        if (startedAtMs == null || completedAtMs == null) return null
        if (completedAtMs < startedAtMs) return null
        return completedAtMs - startedAtMs
    }

    private fun List<TimelineNodeViewModel>.minNodeTimestamp(): Long? = mapNotNull { it.timestamp }.minOrNull()

    private fun List<TimelineNodeViewModel>.maxNodeFinishedTimestamp(): Long? = mapNotNull { it.finishedAtMs() }.maxOrNull()

    private fun TimelineNodeViewModel.finishedAtMs(): Long? {
        val baseTimestamp = timestamp ?: return null
        val extra = durationMs?.takeIf { it >= 0L } ?: 0L
        return baseTimestamp + extra
    }

    private fun buildStepNodes(
        tools: List<String>,
        commands: List<String>,
        defaultTimestamp: Long,
        idPrefix: String,
    ): List<TimelineNodeViewModel> {
        val toolNodes = tools.mapIndexed { index, line ->
            parseToolNode(
                line = line,
                idPrefix = "$idPrefix-tool-${index + 1}",
                defaultTimestamp = defaultTimestamp,
            )
        }
        val commandNodes = commands.mapIndexed { index, line ->
            parseCommandNode(
                line = line,
                idPrefix = "$idPrefix-command-${index + 1}",
                defaultTimestamp = defaultTimestamp,
            )
        }
        return mergeSequencedNodes(toolNodes + commandNodes)
    }

    private fun buildLiveStepNodes(liveTurn: LiveTurnSnapshot): List<TimelineNodeViewModel> {
        val toolNodes = liveTurn.tools.map { tool ->
            TimelineNodeViewModel(
                id = tool.id,
                kind = TimelineNodeKind.TOOL_STEP,
                title = tool.name,
                body = tool.output.ifBlank { tool.input },
                status = tool.status,
                expanded = tool.status != TimelineNodeStatus.FAILED,
                timestamp = tool.startedAtMs,
                sequence = tool.sequence,
                toolName = tool.name,
                toolInput = tool.input,
                toolOutput = tool.output,
                filePath = extractFilePath("${tool.input} ${tool.output}"),
                origin = TimelineNodeOrigin.EVENT,
            )
        }
        val commandNodes = liveTurn.commands.map { command ->
            TimelineNodeViewModel(
                id = command.id,
                kind = if (command.status == TimelineNodeStatus.FAILED) TimelineNodeKind.FAILURE else TimelineNodeKind.COMMAND_STEP,
                title = command.command.ifBlank { "Command" },
                body = command.output,
                status = command.status,
                expanded = command.status != TimelineNodeStatus.FAILED,
                timestamp = command.startedAtMs,
                sequence = command.sequence,
                command = command.command,
                cwd = command.cwd,
                exitCode = command.exitCode,
                origin = TimelineNodeOrigin.EVENT,
            )
        }
        return mergeSequencedNodes(toolNodes + commandNodes)
    }

    private fun buildActionNodes(
        actions: List<TimelineAction>,
        isRunning: Boolean,
        defaultTimestamp: Long?,
    ): List<TimelineNodeViewModel> {
        data class NarrativeState(
            val id: String,
            val kind: TimelineNarrativeKind,
            val sequence: Int,
            val timestamp: Long?,
            var text: String,
        )

        val narratives = linkedMapOf<String, NarrativeState>()
        val tools = linkedMapOf<String, TimelineAction.UpsertTool>()
        val commands = linkedMapOf<String, TimelineAction.UpsertCommand>()
        val failures = mutableListOf<TimelineNodeViewModel>()
        val thinking = StringBuilder()
        var thinkingTimestamp: Long? = defaultTimestamp

        actions.forEach { action ->
            when (action) {
                is TimelineAction.AppendNarrative -> {
                    if (shouldHideLifecycleNarrative(action.text)) {
                        return@forEach
                    }
                    val existing = narratives[action.id]
                    if (existing == null) {
                        narratives[action.id] = NarrativeState(
                            id = action.id,
                            kind = action.kind,
                            sequence = action.sequence,
                            timestamp = action.timestampMs ?: defaultTimestamp,
                            text = action.text,
                        )
                    } else {
                        existing.text = mergeText(existing.text, action.text)
                    }
                }

                is TimelineAction.AppendThinking -> {
                    thinkingTimestamp = thinkingTimestamp ?: action.timestampMs ?: defaultTimestamp
                    thinking.append(action.text)
                }

                is TimelineAction.UpsertTool -> {
                    val previous = tools[action.id]
                    tools[action.id] = action.copy(
                        input = action.input.ifBlank { previous?.input.orEmpty() },
                        output = action.output.ifBlank { previous?.output.orEmpty() },
                        sequence = previous?.sequence ?: action.sequence,
                        timestampMs = action.timestampMs ?: previous?.timestampMs ?: defaultTimestamp,
                    )
                }

                is TimelineAction.CommandProposalReceived -> {
                    val previous = commands[action.id]
                    commands[action.id] = TimelineAction.UpsertCommand(
                        id = action.id,
                        command = action.command,
                        cwd = action.cwd,
                        output = previous?.output.orEmpty(),
                        status = previous?.status ?: TimelineActionStatus.RUNNING,
                        sequence = previous?.sequence ?: action.sequence,
                        exitCode = previous?.exitCode,
                        timestampMs = previous?.timestampMs ?: action.timestampMs ?: defaultTimestamp,
                    )
                }

                is TimelineAction.UpsertCommand -> {
                    val previous = commands[action.id]
                    commands[action.id] = action.copy(
                        output = action.output.ifBlank { previous?.output.orEmpty() },
                        sequence = previous?.sequence ?: action.sequence,
                        timestampMs = action.timestampMs ?: previous?.timestampMs ?: defaultTimestamp,
                    )
                }

                is TimelineAction.MarkTurnFailed -> {
                    failures += TimelineNodeViewModel(
                        id = "failure-${action.sequence}-${failures.size + 1}",
                        kind = TimelineNodeKind.FAILURE,
                        title = "Failure",
                        body = action.message,
                        status = TimelineNodeStatus.FAILED,
                        expanded = false,
                        timestamp = action.timestampMs ?: defaultTimestamp,
                        sequence = action.sequence,
                        origin = TimelineNodeOrigin.EVENT,
                    )
                }

                is TimelineAction.DiffProposalReceived,
                TimelineAction.FinishTurn,
                -> Unit
            }
        }

        val nodes = mergeSequencedNodes(
            narratives.values.map { state ->
                val kind = if (state.kind == TimelineNarrativeKind.RESULT) {
                    TimelineNodeKind.RESULT
                } else {
                    TimelineNodeKind.ASSISTANT_NOTE
                }
                TimelineNodeViewModel(
                    id = state.id,
                    kind = kind,
                    title = if (kind == TimelineNodeKind.RESULT) "Result" else "Assistant",
                    body = state.text.trim(),
                    status = if (isRunning && kind == TimelineNodeKind.ASSISTANT_NOTE) {
                        TimelineNodeStatus.RUNNING
                    } else {
                        TimelineNodeStatus.SUCCESS
                    },
                    expanded = true,
                    timestamp = state.timestamp,
                    sequence = state.sequence,
                    origin = TimelineNodeOrigin.EVENT,
                )
            } +
                tools.values.map { action ->
                    TimelineNodeViewModel(
                        id = action.id,
                        kind = if (action.status == TimelineActionStatus.FAILED) TimelineNodeKind.FAILURE else TimelineNodeKind.TOOL_STEP,
                        title = action.name,
                        body = action.output.ifBlank { action.input },
                        status = mapActionStatus(action.status),
                        expanded = action.status != TimelineActionStatus.FAILED,
                        timestamp = action.timestampMs ?: defaultTimestamp,
                        sequence = action.sequence,
                        toolName = action.name,
                        toolInput = action.input,
                        toolOutput = action.output,
                        filePath = extractFilePath("${action.input} ${action.output}"),
                        origin = TimelineNodeOrigin.EVENT,
                    )
                } +
                commands.values.map { action ->
                    TimelineNodeViewModel(
                        id = action.id,
                        kind = if (action.status == TimelineActionStatus.FAILED) TimelineNodeKind.FAILURE else TimelineNodeKind.COMMAND_STEP,
                        title = action.command.ifBlank { "Command" },
                        body = action.output,
                        status = mapActionStatus(action.status),
                        expanded = action.status != TimelineActionStatus.FAILED,
                        timestamp = action.timestampMs ?: defaultTimestamp,
                        sequence = action.sequence,
                        command = action.command,
                        cwd = action.cwd,
                        exitCode = action.exitCode,
                        origin = TimelineNodeOrigin.EVENT,
                    )
                } +
                failures,
        )

        if (thinking.isBlank()) {
            return nodes
        }

        val thinkingNode = TimelineNodeViewModel(
            id = "thinking-action",
            kind = TimelineNodeKind.THINKING,
            title = "Thinking",
            body = thinking.toString().trim(),
            status = if (isRunning) TimelineNodeStatus.RUNNING else TimelineNodeStatus.SUCCESS,
            expanded = isRunning,
            timestamp = thinkingTimestamp,
            origin = TimelineNodeOrigin.EVENT,
        )
        val insertAt = if (nodes.firstOrNull()?.kind == TimelineNodeKind.ASSISTANT_NOTE) 1 else 0
        return nodes.toMutableList().apply { add(insertAt, thinkingNode) }
    }

    private fun mergeText(current: String, incoming: String): String {
        if (current.isBlank()) return incoming
        if (incoming.isBlank()) return current
        val separator = if (current.last().isWhitespace() || incoming.first().isWhitespace()) "" else "\n"
        return current + separator + incoming
    }

    private fun mapActionStatus(status: TimelineActionStatus): TimelineNodeStatus {
        return when (status) {
            TimelineActionStatus.RUNNING -> TimelineNodeStatus.RUNNING
            TimelineActionStatus.SUCCESS -> TimelineNodeStatus.SUCCESS
            TimelineActionStatus.FAILED -> TimelineNodeStatus.FAILED
            TimelineActionStatus.SKIPPED -> TimelineNodeStatus.SKIPPED
        }
    }

    private fun parseToolNode(
        line: String,
        idPrefix: String,
        defaultTimestamp: Long,
    ): TimelineNodeViewModel {
        val parts = line.split(" | ").map { it.trim() }
        val status = mapToolStatus(parts.firstOrNull().orEmpty())
        val id = parts.firstOrNull { it.startsWith("id:") }?.removePrefix("id:") ?: idPrefix
        val toolName = parts.getOrNull(2).orEmpty().ifBlank { "tool" }
        val input = parts.firstOrNull { it.startsWith("in: ") }?.removePrefix("in: ").orEmpty()
        val output = parts.firstOrNull { it.startsWith("out: ") }?.removePrefix("out: ").orEmpty()
        val sequence = parts.firstOrNull { it.startsWith("seq:") }?.removePrefix("seq:")?.toIntOrNull()
        val timestamp = parts.firstOrNull { it.startsWith("ts:") }?.removePrefix("ts:")?.toLongOrNull() ?: defaultTimestamp
        val durationMs = parts.firstOrNull { it.startsWith("dur:") }?.removePrefix("dur:")?.toLongOrNull()
        return TimelineNodeViewModel(
            id = id,
            kind = if (status == TimelineNodeStatus.FAILED) TimelineNodeKind.FAILURE else TimelineNodeKind.TOOL_STEP,
            title = toolName,
            body = output.ifBlank { input },
            status = status,
            expanded = status != TimelineNodeStatus.FAILED,
            timestamp = timestamp,
            durationMs = durationMs,
            sequence = sequence,
            toolName = toolName,
            toolInput = input,
            toolOutput = output,
            filePath = extractFilePath("$input $output"),
            origin = TimelineNodeOrigin.EVENT,
        )
    }

    private fun parseCommandNode(
        line: String,
        idPrefix: String,
        defaultTimestamp: Long,
    ): TimelineNodeViewModel {
        val parts = line.split(" | ").map { it.trim() }
        val id = parts.firstOrNull { it.startsWith("id:") }?.removePrefix("id:") ?: idPrefix
        val status = mapCommandStatus(parts.firstOrNull { it.startsWith("status:") }?.removePrefix("status:").orEmpty())
        val command = parts.firstOrNull { it.startsWith("cmd: ") }?.removePrefix("cmd: ").orEmpty()
        val cwd = parts.firstOrNull { it.startsWith("cwd: ") }?.removePrefix("cwd: ").orEmpty()
        val output = parts.firstOrNull { it.startsWith("out: ") }?.removePrefix("out: ").orEmpty()
        val exitCode = parts.firstOrNull { it.startsWith("exit:") }?.removePrefix("exit:")?.toIntOrNull()
        val sequence = parts.firstOrNull { it.startsWith("seq:") }?.removePrefix("seq:")?.toIntOrNull()
        val timestamp = parts.firstOrNull { it.startsWith("ts:") }?.removePrefix("ts:")?.toLongOrNull() ?: defaultTimestamp
        val durationMs = parts.firstOrNull { it.startsWith("dur:") }?.removePrefix("dur:")?.toLongOrNull()
        return TimelineNodeViewModel(
            id = id,
            kind = if (status == TimelineNodeStatus.FAILED) TimelineNodeKind.FAILURE else TimelineNodeKind.COMMAND_STEP,
            title = command.ifBlank { "Command" },
            body = output,
            status = status,
            expanded = status != TimelineNodeStatus.FAILED,
            timestamp = timestamp,
            durationMs = durationMs,
            sequence = sequence,
            command = command,
            cwd = cwd,
            exitCode = exitCode,
            origin = TimelineNodeOrigin.EVENT,
        )
    }

    private fun extractSections(content: String): Map<String, String> {
        val pattern = Regex("(?m)^###\\s+(Thinking|Response|Tools|Commands|Narrative)\\s*$")
        val matches = pattern.findAll(content).toList()
        if (matches.isEmpty()) return emptyMap()
        val sections = linkedMapOf<String, String>()
        matches.forEachIndexed { index, match ->
            val name = match.groupValues[1].lowercase()
            val start = match.range.last + 1
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else content.length
            sections[name] = content.substring(start, end).trim()
        }
        return sections
    }

    private fun parseToolLines(text: String): List<String> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { if (it.startsWith("- ")) it.removePrefix("- ").trim() else it }
    }

    private fun parseCommandLines(text: String): List<String> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { if (it.startsWith("- ")) it.removePrefix("- ").trim() else it }
    }

    private fun inferToolLinesFromResponse(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val rows = mutableListOf<String>()
        val filePattern = Regex("""([A-Za-z0-9_./-]+\.(kt|kts|java|xml|md|json|yaml|yml|js|ts|tsx|jsx|py|go|rs))""")
        filePattern.findAll(text).forEach { match ->
            val path = match.groupValues[1]
            if (rows.none { it.contains(path) }) {
                rows += "done | id:infer-tool-${rows.size + 1} | read_file | in: $path | out: inferred"
            }
        }
        if (rows.isEmpty() && text.contains("修改文件")) {
            rows += "done | id:infer-tool-1 | edit_file | in: from response | out: inferred"
        }
        return rows
    }

    private fun parseNarrativeLines(text: String, defaultTimestamp: Long?): List<ParsedNarrativeItem> {
        if (text.isBlank()) return emptyList()
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { if (it.startsWith("- ")) it.removePrefix("- ").trim() else it }
            .mapNotNull { parseNarrativeLine(it, defaultTimestamp) }
    }

    private fun parseNarrativeLine(line: String, defaultTimestamp: Long?): ParsedNarrativeItem? {
        val parts = line.split(" | ").map { it.trim() }
        val rawKind = parts.firstOrNull()?.lowercase().orEmpty()
        val kind = when (rawKind) {
            "result" -> TimelineNodeKind.RESULT
            "note" -> TimelineNodeKind.ASSISTANT_NOTE
            else -> return null
        }
        val id = parts.firstOrNull { it.startsWith("id:") }?.removePrefix("id:").orEmpty().ifBlank {
            "narrative-${parts.hashCode()}"
        }
        val sequence = parts.firstOrNull { it.startsWith("seq:") }?.removePrefix("seq:")?.trim()?.toIntOrNull()
        val origin = when (parts.firstOrNull { it.startsWith("origin:") }?.removePrefix("origin:")?.trim()?.lowercase()) {
            "inferred", "inferred_response" -> TimelineNodeOrigin.INFERRED_RESPONSE
            else -> TimelineNodeOrigin.EVENT
        }
        val timestamp = parts.firstOrNull { it.startsWith("ts:") }?.removePrefix("ts:")?.trim()?.toLongOrNull() ?: defaultTimestamp
        val body64 = parts.firstOrNull { it.startsWith("body64:") }?.removePrefix("body64:").orEmpty()
        val body = decodeBody64(body64)
        if (body.isBlank()) return null
        if (shouldHideLifecycleNarrative(body)) return null
        return ParsedNarrativeItem(
            id = id,
            kind = kind,
            body = body,
            sequence = sequence,
            timestamp = timestamp,
            origin = origin,
        )
    }

    private fun decodeBody64(body64: String): String {
        if (body64.isBlank()) return ""
        return runCatching {
            String(Base64.getDecoder().decode(body64))
        }.getOrElse { "" }
    }

    private fun mergeSequencedNodes(nodes: List<TimelineNodeViewModel>): List<TimelineNodeViewModel> {
        return nodes.sortedWith(
            compareBy<TimelineNodeViewModel> { it.sequence ?: Int.MAX_VALUE }
                .thenBy { it.timestamp ?: Long.MAX_VALUE }
                .thenBy { kindPriority(it.kind) }
                .thenBy { it.id },
        )
    }

    private fun kindPriority(kind: TimelineNodeKind): Int {
        return when (kind) {
            TimelineNodeKind.ASSISTANT_NOTE -> 0
            TimelineNodeKind.THINKING -> 1
            TimelineNodeKind.TOOL_STEP -> 2
            TimelineNodeKind.COMMAND_STEP -> 3
            TimelineNodeKind.RESULT -> 4
            TimelineNodeKind.FAILURE -> 5
            TimelineNodeKind.SYSTEM_AUX -> 6
        }
    }

    private fun toNarrativeNode(item: ParsedNarrativeItem): TimelineNodeViewModel {
        return TimelineNodeViewModel(
            id = item.id,
            kind = item.kind,
            title = if (item.kind == TimelineNodeKind.RESULT) "Result" else "Assistant",
            body = item.body,
            status = TimelineNodeStatus.SUCCESS,
            expanded = true,
            timestamp = item.timestamp,
            sequence = item.sequence,
            origin = item.origin,
        )
    }

    private fun toLiveNarrativeNode(note: LiveNarrativeTrace): TimelineNodeViewModel {
        return TimelineNodeViewModel(
            id = note.id,
            kind = note.kind,
            title = if (note.kind == TimelineNodeKind.RESULT) "Result" else "Assistant",
            body = note.body.trim(),
            status = if (note.kind == TimelineNodeKind.RESULT) TimelineNodeStatus.SUCCESS else TimelineNodeStatus.RUNNING,
            expanded = true,
            timestamp = note.timestamp,
            sequence = note.sequence,
            origin = note.origin,
        )
    }

    private fun interleaveResponseBlocks(
        responseText: String,
        stepNodes: List<TimelineNodeViewModel>,
        idPrefix: String,
        defaultTimestamp: Long?,
        includeTerminalResult: Boolean,
    ): List<TimelineNodeViewModel> {
        if (responseText.isBlank()) return stepNodes
        val blocks = splitNarrativeBlocks(responseText)
        if (blocks.isEmpty()) return stepNodes

        if (stepNodes.isEmpty()) {
            if (!includeTerminalResult) {
                return blocks.mapIndexed { index, block ->
                    inferredNarrativeNode(
                        id = "$idPrefix-note-${index + 1}",
                        kind = TimelineNodeKind.ASSISTANT_NOTE,
                        body = block,
                        timestamp = defaultTimestamp,
                    )
                }
            }
            if (blocks.size == 1) {
                return listOf(
                    inferredNarrativeNode(
                        id = "$idPrefix-result",
                        kind = TimelineNodeKind.RESULT,
                        body = blocks.single(),
                        timestamp = defaultTimestamp,
                    ),
                )
            }
        }

        if (includeTerminalResult && blocks.size == 1) {
            return stepNodes + inferredNarrativeNode(
                id = "$idPrefix-result",
                kind = TimelineNodeKind.RESULT,
                body = blocks.single(),
                timestamp = defaultTimestamp,
            )
        }

        val narrativeBlocks = if (includeTerminalResult && blocks.size > 1) blocks.dropLast(1) else blocks
        val terminalBlock = if (includeTerminalResult) blocks.lastOrNull() else null
        val ordered = mutableListOf<TimelineNodeViewModel>()
        var blockIndex = 0

        if (blockIndex < narrativeBlocks.size) {
            ordered += inferredNarrativeNode(
                id = "$idPrefix-note-${blockIndex + 1}",
                kind = TimelineNodeKind.ASSISTANT_NOTE,
                body = narrativeBlocks[blockIndex],
                timestamp = defaultTimestamp,
            )
            blockIndex += 1
        }

        stepNodes.forEach { step ->
            ordered += step
            if (blockIndex < narrativeBlocks.size) {
                ordered += inferredNarrativeNode(
                    id = "$idPrefix-note-${blockIndex + 1}",
                    kind = TimelineNodeKind.ASSISTANT_NOTE,
                    body = narrativeBlocks[blockIndex],
                    timestamp = defaultTimestamp,
                )
                blockIndex += 1
            }
        }

        while (blockIndex < narrativeBlocks.size) {
            ordered += inferredNarrativeNode(
                id = "$idPrefix-note-${blockIndex + 1}",
                kind = TimelineNodeKind.ASSISTANT_NOTE,
                body = narrativeBlocks[blockIndex],
                timestamp = defaultTimestamp,
            )
            blockIndex += 1
        }

        if (!terminalBlock.isNullOrBlank()) {
            ordered += inferredNarrativeNode(
                id = "$idPrefix-result",
                kind = if (includeTerminalResult) TimelineNodeKind.RESULT else TimelineNodeKind.ASSISTANT_NOTE,
                body = terminalBlock,
                timestamp = defaultTimestamp,
            )
        }

        return ordered
    }

    private fun inferredNarrativeNode(
        id: String,
        kind: TimelineNodeKind,
        body: String,
        timestamp: Long?,
    ): TimelineNodeViewModel {
        return TimelineNodeViewModel(
            id = id,
            kind = kind,
            title = if (kind == TimelineNodeKind.RESULT) "Result" else "Assistant",
            body = body.trim(),
            status = TimelineNodeStatus.SUCCESS,
            expanded = true,
            timestamp = timestamp,
            origin = TimelineNodeOrigin.INFERRED_RESPONSE,
        )
    }

    private fun splitNarrativeBlocks(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val lines = text.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<String>()
        val current = mutableListOf<String>()
        var inCodeBlock = false

        fun flush() {
            if (current.isEmpty()) return
            val block = current.joinToString("\n").trim()
            if (block.isNotBlank()) {
                blocks += block
            }
            current.clear()
        }

        lines.forEach { raw ->
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                current += line
                inCodeBlock = !inCodeBlock
                return@forEach
            }
            if (inCodeBlock) {
                current += line
                return@forEach
            }
            if (trimmed.isBlank()) {
                flush()
                return@forEach
            }
            current += line
        }
        flush()
        return blocks.filterNot(::shouldHideLifecycleNarrative)
    }

    private fun sanitizeNarrativeText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return ""
        if (shouldHideLifecycleNarrative(trimmed)) return ""
        return trimmed
    }

    private fun shouldHideLifecycleNarrative(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isBlank()) return false
        val lower = normalized.lowercase()
        return isStartupPhaseStatus(normalized) ||
            lower == "turn.completed" ||
            lower == "thread.completed" ||
            lower == "item.completed"
    }

    private fun isStartupPhaseStatus(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isBlank()) return false
        val lower = normalized.lowercase()
        return lower == "turn.started" ||
            lower == "turn.stated" ||
            lower == "thread.started" ||
            lower == "item.started" ||
            normalized == "正在生成响应..." ||
            normalized == "正在准备会话..." ||
            normalized == "正在执行步骤..."
    }

    private fun inferCommandLinesFromResponse(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val rows = mutableListOf<String>()
        text.lines().map { it.trim() }.forEach { line ->
            if (line.isBlank()) return@forEach
            val looksLikeCommand = line.startsWith("$ ") ||
                line.contains("/bin/zsh -lc") ||
                line.contains("javac ") ||
                line.contains("java ") ||
                line.contains("gradle") ||
                line.contains("find ") ||
                line.contains("grep ") ||
                line.contains("rg ")
            if (looksLikeCommand) {
                rows += "command | id:infer-command-${rows.size + 1} | status:done | cmd: ${line.removePrefix("$ ").trim()} | out: inferred"
            }
        }
        return rows
    }

    private fun mapToolStatus(raw: String): TimelineNodeStatus {
        return when (raw.lowercase()) {
            "done" -> TimelineNodeStatus.SUCCESS
            "failed" -> TimelineNodeStatus.FAILED
            else -> TimelineNodeStatus.RUNNING
        }
    }

    private fun mapCommandStatus(raw: String): TimelineNodeStatus {
        return when (raw.lowercase()) {
            "done" -> TimelineNodeStatus.SUCCESS
            "failed" -> TimelineNodeStatus.FAILED
            "skipped" -> TimelineNodeStatus.SKIPPED
            else -> TimelineNodeStatus.RUNNING
        }
    }

    private fun extractFilePath(text: String): String? {
        val pattern = Regex("""([A-Za-z0-9_./-]+\.(kt|kts|java|xml|md|json|yaml|yml|js|ts|tsx|jsx|py|go|rs))""")
        val path = pattern.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (path.isBlank()) return null
        return File(path).path
    }
}

data class TimelineTurnViewModel(
    val id: String,
    val userMessage: ChatMessage?,
    val nodes: List<TimelineNodeViewModel>,
    val isRunning: Boolean,
    val footerStatus: TimelineNodeStatus,
    val statusText: String,
    val durationMs: Long? = null,
)

data class TimelineNodeViewModel(
    val id: String,
    val kind: TimelineNodeKind,
    val title: String,
    val body: String,
    val status: TimelineNodeStatus,
    val expanded: Boolean,
    val timestamp: Long? = null,
    val durationMs: Long? = null,
    val sequence: Int? = null,
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val command: String? = null,
    val cwd: String? = null,
    val exitCode: Int? = null,
    val filePath: String? = null,
    val origin: TimelineNodeOrigin = TimelineNodeOrigin.EVENT,
)

enum class TimelineNodeKind {
    ASSISTANT_NOTE,
    THINKING,
    TOOL_STEP,
    COMMAND_STEP,
    RESULT,
    FAILURE,
    SYSTEM_AUX,
}

enum class TimelineNodeStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
}

enum class TimelineNodeOrigin {
    EVENT,
    INFERRED_RESPONSE,
}

data class LiveTurnSnapshot(
    val statusText: String = "",
    val assistantContent: String = "",
    val thinking: String = "",
    val actions: List<TimelineAction> = emptyList(),
    val notes: List<LiveNarrativeTrace> = emptyList(),
    val tools: List<LiveToolTrace> = emptyList(),
    val commands: List<LiveCommandTrace> = emptyList(),
    val isRunning: Boolean = false,
    val startedAtMs: Long? = null,
)

data class LiveNarrativeTrace(
    val id: String,
    val body: String,
    val sequence: Int,
    val origin: TimelineNodeOrigin,
    val kind: TimelineNodeKind = TimelineNodeKind.ASSISTANT_NOTE,
    val timestamp: Long? = null,
)

data class LiveToolTrace(
    val id: String,
    val name: String,
    val input: String,
    val output: String,
    val status: TimelineNodeStatus,
    val sequence: Int,
    val startedAtMs: Long? = null,
)

data class LiveCommandTrace(
    val id: String,
    val command: String,
    val cwd: String,
    val output: String,
    val status: TimelineNodeStatus,
    val sequence: Int,
    val exitCode: Int? = null,
    val startedAtMs: Long? = null,
)

private data class ParsedNarrativeItem(
    val id: String,
    val kind: TimelineNodeKind,
    val body: String,
    val sequence: Int?,
    val timestamp: Long?,
    val origin: TimelineNodeOrigin,
)
