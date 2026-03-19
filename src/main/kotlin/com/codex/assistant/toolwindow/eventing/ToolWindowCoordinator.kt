package com.codex.assistant.toolwindow.eventing

import com.codex.assistant.model.ChatMessage
import com.codex.assistant.model.ContextFile
import com.codex.assistant.model.FileAttachment
import com.codex.assistant.model.ImageAttachment
import com.codex.assistant.model.MessageRole
import com.codex.assistant.context.MentionFileWhitelist
import com.codex.assistant.persistence.chat.PersistedAttachmentKind
import com.codex.assistant.persistence.chat.PersistedMessageAttachment
import com.codex.assistant.protocol.TurnOutcome
import com.codex.assistant.protocol.UnifiedEvent
import com.codex.assistant.settings.SavedAgentDefinition
import com.codex.assistant.toolwindow.composer.AttachmentEntry
import com.codex.assistant.service.AgentChatService
import com.codex.assistant.settings.AgentSettingsService
import com.codex.assistant.toolwindow.composer.ContextEntry
import com.codex.assistant.toolwindow.composer.AttachmentKind
import com.codex.assistant.toolwindow.composer.ComposerAreaStore
import com.codex.assistant.toolwindow.drawer.RightDrawerKind
import com.codex.assistant.toolwindow.drawer.RightDrawerAreaStore
import com.codex.assistant.toolwindow.header.HeaderAreaStore
import com.codex.assistant.toolwindow.status.StatusAreaStore
import com.codex.assistant.toolwindow.timeline.TimelineAreaStore
import com.codex.assistant.toolwindow.timeline.TimelineFileChange
import com.codex.assistant.toolwindow.timeline.TimelineMutation
import com.codex.assistant.toolwindow.timeline.TimelineNodeMapper
import com.intellij.codeInsight.navigation.LOG
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import java.security.MessageDigest
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

internal class ToolWindowCoordinator(
    private val chatService: AgentChatService,
    private val settingsService: AgentSettingsService,
    private val eventHub: ToolWindowEventHub,
    private val headerStore: HeaderAreaStore,
    private val statusStore: StatusAreaStore,
    private val timelineStore: TimelineAreaStore,
    private val composerStore: ComposerAreaStore,
    private val rightDrawerStore: RightDrawerAreaStore,
    private val pickAttachments: () -> List<String> = { emptyList() },
    private val searchProjectFiles: (String, Int) -> List<String> = { _, _ -> emptyList() },
    private val isMentionCandidateFile: (String) -> Boolean = { path -> MentionFileWhitelist.allowPath(path) },
    private val readFileContent: (String) -> String? = { path -> readFileContentDefault(path) },
    private val openTimelineFileChange: (TimelineFileChange) -> Unit = {},
    private val onSessionSnapshotPublished: () -> Unit = {},
    private val historyPageSize: Int = 40,
) : Disposable {
    companion object {
        private const val MENTION_LIMIT: Int = 10
    }
    private val ceh = CoroutineExceptionHandler { ctx, e ->
        LOG.error("Coroutine failed: $ctx", e)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default+ceh).apply {

    }
    private val recentFocusedFiles = ArrayDeque<String>()

    init {
        scope.launch {
            eventHub.stream.collect { event ->
                headerStore.onEvent(event)
                statusStore.onEvent(event)
                timelineStore.onEvent(event)
                composerStore.onEvent(event)
                rightDrawerStore.onEvent(event)
                if (event is AppEvent.UiIntentPublished) {
                    handleUiIntent(event.intent)
                }
            }
        }

        publishSessionSnapshot()
        publishSettingsSnapshot()
        restoreCurrentSessionHistory()
    }

    private fun handleUiIntent(intent: UiIntent) {
        when (intent) {
            UiIntent.ToggleSettings -> {
                if (rightDrawerStore.state.value.kind == RightDrawerKind.SETTINGS) {
                    publishSettingsSnapshot()
                }
            }

            UiIntent.SendPrompt -> submitPromptIfAllowed()
            UiIntent.CancelRun -> cancelPromptRun()
            is UiIntent.DeleteSession -> deleteSession(intent.sessionId)
            is UiIntent.OpenTimelineFileChange -> openTimelineFileChange(intent.change)
            UiIntent.LoadOlderMessages -> loadOlderMessages()
            UiIntent.OpenAttachmentPicker -> {
                val selected = pickAttachments()
                if (selected.isNotEmpty()) {
                    eventHub.publishUiIntent(UiIntent.AddAttachments(selected))
                }
            }
            UiIntent.PasteImageFromClipboard -> pasteImageFromClipboard()
            is UiIntent.RequestMentionSuggestions -> {
                val query = intent.query.trim()
                val paths = if (query.isBlank()) {
                    recentFocusedFiles.toList().take(MENTION_LIMIT)
                } else {
                    searchProjectFiles(query, MENTION_LIMIT)
                }
                val suggestions = paths.map { toContextEntry(it) }
                eventHub.publish(
                    AppEvent.MentionSuggestionsUpdated(
                        query = query,
                        documentVersion = intent.documentVersion,
                        suggestions = suggestions,
                    ),
                )
            }
            is UiIntent.RequestAgentSuggestions -> {
                val query = intent.query.trim()
                val suggestions = settingsService.savedAgents()
                    .filter { agent ->
                        query.isBlank() || agent.name.contains(query, ignoreCase = true)
                    }
                    .take(MENTION_LIMIT)
                eventHub.publish(
                    AppEvent.AgentSuggestionsUpdated(
                        query = query,
                        documentVersion = intent.documentVersion,
                        suggestions = suggestions,
                    ),
                )
            }
            is UiIntent.UpdateFocusedContextFile -> recordFocusedFile(intent.path)
            is UiIntent.EditSettingsLanguageMode -> applyLanguagePreview(intent.mode)
            is UiIntent.EditSettingsThemeMode -> applyThemePreview(intent.mode)
            UiIntent.SaveAgentDraft -> saveAgentDraft()
            is UiIntent.DeleteSavedAgent -> deleteSavedAgent(intent.id)
            UiIntent.SaveSettings -> saveSettings()
            else -> Unit
        }
    }

    private fun applyLanguagePreview(mode: com.codex.assistant.settings.UiLanguageMode) {
        if (settingsService.uiLanguageMode() == mode) return
        settingsService.setUiLanguageMode(mode)
        settingsService.notifyLanguageChanged()
        publishSettingsSnapshot()
    }

    private fun applyThemePreview(mode: com.codex.assistant.settings.UiThemeMode) {
        if (settingsService.uiThemeMode() == mode) return
        settingsService.setUiThemeMode(mode)
        settingsService.notifyAppearanceChanged()
        publishSettingsSnapshot()
    }

    private fun saveSettings() {
        val drawerState = rightDrawerStore.state.value
        val oldLanguage = settingsService.uiLanguageMode()
        val oldTheme = settingsService.uiThemeMode()
        val state = settingsService.state
        state.setExecutablePathFor("codex", drawerState.codexCliPath.trim())
        settingsService.setUiLanguageMode(drawerState.languageMode)
        settingsService.setUiThemeMode(drawerState.themeMode)
        if (oldLanguage != settingsService.uiLanguageMode()) {
            settingsService.notifyLanguageChanged()
        }
        if (oldTheme != settingsService.uiThemeMode()) {
            settingsService.notifyAppearanceChanged()
        }
        publishSettingsSnapshot()
    }

    private fun saveAgentDraft() {
        val drawerState = rightDrawerStore.state.value
        val id = drawerState.editingAgentId?.takeIf { it.isNotBlank() }
        val name = drawerState.agentDraftName.trim()
        val prompt = drawerState.agentDraftPrompt.trim()
        if (name.isBlank() || prompt.isBlank()) {
            eventHub.publish(AppEvent.StatusTextUpdated(com.codex.assistant.toolwindow.shared.UiText.raw("Agent name and prompt are required.")))
            return
        }
        val state = settingsService.state
        val duplicate = state.savedAgents.any { agent ->
            agent.id != id && agent.name.trim().equals(name, ignoreCase = true)
        }
        if (duplicate) {
            eventHub.publish(AppEvent.StatusTextUpdated(com.codex.assistant.toolwindow.shared.UiText.raw("Agent name must be unique.")))
            return
        }
        val saved = SavedAgentDefinition(
            id = id ?: java.util.UUID.randomUUID().toString(),
            name = name,
            prompt = prompt,
        )
        val updated = state.savedAgents.toMutableList()
        val index = updated.indexOfFirst { it.id == saved.id }
        if (index >= 0) {
            updated[index] = saved
        } else {
            updated += saved
        }
        state.savedAgents = updated
        publishSettingsSnapshot()
        eventHub.publishUiIntent(UiIntent.SelectSavedAgentForEdit(saved.id))
    }

    private fun deleteSavedAgent(id: String) {
        val state = settingsService.state
        val updated = state.savedAgents.filterNot { it.id == id }.toMutableList()
        if (updated.size == state.savedAgents.size) return
        state.savedAgents = updated
        publishSettingsSnapshot()
    }

    private fun deleteSession(sessionId: String) {
        if (!chatService.deleteSession(sessionId)) return
        publishSessionSnapshot()
        restoreCurrentSessionHistory()
    }

    private fun recordFocusedFile(path: String?) {
        val normalized = path?.trim().orEmpty()
        if (normalized.isBlank() || !isMentionCandidateFile(normalized)) return
        recentFocusedFiles.remove(normalized)
        recentFocusedFiles.addFirst(normalized)
        while (recentFocusedFiles.size > 64) {
            recentFocusedFiles.removeLast()
        }
    }

    private fun submitPromptIfAllowed() {
        if (timelineStore.state.value.isRunning) return
        val composerState = composerStore.state.value
        val prompt = composerState.serializedPrompt()
        val systemInstructions = composerState.serializedSystemInstructions()
        if (prompt.isBlank() && systemInstructions.isEmpty()) return
        val localTurnId = "local-turn-${System.currentTimeMillis()}"
        val storedAttachments = stageAttachments(
            sessionId = chatService.getCurrentSessionId(),
            attachments = composerState.attachments,
        )

        val persistedMessage = if (prompt.isBlank()) {
            null
        } else {
            chatService.recordUserMessage(
                prompt = prompt,
                turnId = localTurnId,
                attachments = storedAttachments,
            )
        }
        eventHub.publish(AppEvent.PromptAccepted(prompt))
        persistedMessage?.let { entry ->
            eventHub.publish(
                AppEvent.TimelineMutationApplied(
                    TimelineNodeMapper.localUserMessageMutation(entry),
                ),
            )
        }
        publishSessionSnapshot()

        val contextFiles = buildContextFiles(
            contextEntries = composerState.contextEntries,
            attachments = storedAttachments,
        )
        val imageAttachments = storedAttachments.filter { it.kind == PersistedAttachmentKind.IMAGE }.map {
            ImageAttachment(path = it.assetPath, name = it.displayName, mimeType = it.mimeType.ifBlank { "image/png" })
        }
        val fileAttachments = storedAttachments.filter { it.kind == PersistedAttachmentKind.FILE }.map {
            FileAttachment(path = it.assetPath, name = it.displayName, mimeType = it.mimeType.ifBlank { "application/octet-stream" })
        }

        chatService.runAgent(
            engineId = chatService.defaultEngineId(),
            model = composerState.selectedModel,
            reasoningEffort = composerState.selectedReasoning.effort,
            prompt = prompt,
            systemInstructions = systemInstructions,
            localTurnId = localTurnId,
            contextFiles = contextFiles,
            imageAttachments = imageAttachments,
            fileAttachments = fileAttachments,
            onTurnPersisted = { publishSessionSnapshot() },
            onUnifiedEvent = ::publishUnifiedEvent,
        ) { }
    }

    private fun cancelPromptRun() {
        chatService.cancelCurrent()
        publishUnifiedEvent(
            UnifiedEvent.TurnCompleted(
                turnId = "",
                outcome = TurnOutcome.CANCELLED,
                usage = null,
            ),
        )
    }

    private fun publishSessionSnapshot() {
        eventHub.publish(
            AppEvent.SessionSnapshotUpdated(
                sessions = chatService.listSessions(),
                activeSessionId = chatService.getCurrentSessionId(),
            ),
        )
        onSessionSnapshotPublished()
    }

    private fun publishSettingsSnapshot() {
        val state = settingsService.state
        eventHub.publish(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = state.executablePathFor("codex"),
                languageMode = settingsService.uiLanguageMode(),
                themeMode = settingsService.uiThemeMode(),
                savedAgents = state.savedAgents.toList(),
            ),
        )
    }

    fun onSessionActivated() {
        publishSessionSnapshot()
        restoreCurrentSessionHistory()
    }

    fun onSessionSwitched(sessionId: String) {
        eventHub.publishUiIntent(UiIntent.SwitchSession(sessionId))
    }

    override fun dispose() {
        scope.cancel()
    }

    private fun toContextEntry(path: String): ContextEntry {
        val p = runCatching { Path.of(path) }.getOrNull()
        val name = p?.name ?: path.substringAfterLast('/').substringAfterLast('\\')
        val tail = p?.parent?.fileName?.toString().orEmpty()
        return ContextEntry(path = path, displayName = name.ifBlank { path }, tailPath = tail)
    }

    private fun pasteImageFromClipboard() {
        val image = readImageFromClipboard() ?: return
        val tempPath = writeClipboardImage(image) ?: return
        eventHub.publishUiIntent(UiIntent.AddAttachments(listOf(tempPath)))
    }

    private fun readImageFromClipboard(): BufferedImage? {
        return runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
            val image = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
            if (image is BufferedImage) return image
            val buffered = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
            val g = buffered.createGraphics()
            g.drawImage(image, 0, 0, null)
            g.dispose()
            buffered
        }.getOrNull()
    }

    private fun writeClipboardImage(image: BufferedImage): String? {
        val path = runCatching {
            Files.createTempFile("codex-clip-", ".png")
        }.getOrNull() ?: return null
        return runCatching {
            Files.newOutputStream(path, StandardOpenOption.WRITE).use { out ->
                ImageIO.write(image, "png", out)
            }
            path.toAbsolutePath().toString()
        }.getOrNull()
    }

    private fun restoreCurrentSessionHistory() {
        eventHub.publish(AppEvent.ConversationReset)
        val page = chatService.loadCurrentSessionTimeline(limit = historyPageSize)
        eventHub.publish(
            AppEvent.TimelineHistoryLoaded(
                nodes = page.entries.map(TimelineNodeMapper::fromHistory),
                oldestCursor = page.entries.firstOrNull()?.cursor,
                hasOlder = page.hasOlder,
                prepend = false,
            ),
        )
    }

    private fun loadOlderMessages() {
        val state = timelineStore.state.value
        if (!state.hasOlder || state.isLoadingOlder) return
        val beforeCursor = state.oldestCursor ?: return
        eventHub.publish(AppEvent.TimelineOlderLoadingChanged(loading = true))
        val page = chatService.loadOlderTimeline(
            beforeCursorExclusive = beforeCursor,
            limit = historyPageSize,
        )
        eventHub.publish(
            AppEvent.TimelineHistoryLoaded(
                nodes = page.entries.map(TimelineNodeMapper::fromHistory),
                oldestCursor = page.entries.firstOrNull()?.cursor,
                hasOlder = page.hasOlder,
                prepend = true,
            ),
        )
    }

    private fun publishUnifiedEvent(event: UnifiedEvent) {
        eventHub.publishUnifiedEvent(event)
        TimelineNodeMapper.fromUnifiedEvent(event)?.let { mutation ->
            eventHub.publish(AppEvent.TimelineMutationApplied(mutation))
        }
    }

    private fun buildContextFiles(
        contextEntries: List<ContextEntry>,
        attachments: List<PersistedMessageAttachment>,
    ): List<ContextFile> {
        val merged = LinkedHashMap<String, ContextFile>()
        contextEntries.forEach { entry ->
            readFileContent(entry.path)?.let { content ->
                merged.putIfAbsent(entry.path, ContextFile(path = entry.path, content = content))
            }
        }
        attachments
            .filter { it.kind == PersistedAttachmentKind.TEXT }
            .forEach { attachment ->
                readFileContent(attachment.assetPath)?.let { content ->
                    val displayPath = attachment.originalPath.ifBlank { attachment.assetPath }
                    merged.putIfAbsent(displayPath, ContextFile(path = displayPath, content = content))
                }
            }
        return merged.values.toList()
    }

    private fun stageAttachments(
        sessionId: String,
        attachments: List<AttachmentEntry>,
    ): List<PersistedMessageAttachment> {
        if (sessionId.isBlank() || attachments.isEmpty()) return emptyList()
        return attachments.mapNotNull { attachment ->
            val source = runCatching { Path.of(attachment.path) }.getOrNull() ?: return@mapNotNull null
            if (!source.isRegularFile()) return@mapNotNull null
            val bytes = runCatching { Files.readAllBytes(source) }.getOrNull() ?: return@mapNotNull null
            val sha = sha256(bytes)
            val ext = attachment.displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
            val fileName = buildString {
                append(sha)
                if (!ext.isNullOrBlank()) {
                    append('.')
                    append(ext)
                }
            }
            val targetDir = Path.of(PathManager.getSystemPath(), "codex-assistant", "chat-assets", sessionId)
            runCatching { Files.createDirectories(targetDir) }.getOrNull() ?: return@mapNotNull null
            val target = targetDir.resolve(fileName)
            runCatching {
                if (!Files.exists(target)) {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }.getOrNull() ?: return@mapNotNull null

            PersistedMessageAttachment(
                kind = attachment.kind.toPersistedAttachmentKind(),
                displayName = attachment.displayName,
                assetPath = target.toAbsolutePath().toString(),
                originalPath = attachment.path,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.sizeBytes,
                sha256 = sha,
            )
        }
    }

    private fun AttachmentKind.toPersistedAttachmentKind(): PersistedAttachmentKind {
        return when (this) {
            AttachmentKind.IMAGE -> PersistedAttachmentKind.IMAGE
            AttachmentKind.TEXT -> PersistedAttachmentKind.TEXT
            AttachmentKind.BINARY -> PersistedAttachmentKind.FILE
        }
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}

private fun readFileContentDefault(path: String): String? {
    val maxFileBytes = 128 * 1024
    val file = runCatching { Path.of(path) }.getOrNull() ?: return null
    if (!file.isRegularFile()) return null
    val bytes = runCatching { Files.readAllBytes(file) }.getOrNull() ?: return null
    if (bytes.isEmpty() || bytes.any { it == 0.toByte() }) return null
    val clipped = if (bytes.size > maxFileBytes) bytes.copyOf(maxFileBytes) else bytes
    return runCatching { clipped.toString(Charsets.UTF_8) }.getOrNull()
}
