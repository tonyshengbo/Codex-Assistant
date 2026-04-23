package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.persistence.chat.PersistedAttachmentKind
import com.auracode.assistant.persistence.chat.PersistedMessageAttachment
import com.auracode.assistant.toolwindow.composer.AttachmentEntry
import com.auracode.assistant.toolwindow.composer.AttachmentKind
import com.auracode.assistant.toolwindow.composer.ContextEntry
import com.auracode.assistant.toolwindow.composer.MentionSuggestion
import com.auracode.assistant.toolwindow.composer.sortSessionSubagents
import com.auracode.assistant.toolwindow.timeline.TimelineFileChange
import com.auracode.assistant.toolwindow.timeline.TimelineFileChangePreview
import com.auracode.assistant.toolwindow.shared.UiText
import com.intellij.openapi.application.PathManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

internal class WorkspaceInteractionHandler(
    private val context: ToolWindowCoordinatorContext,
) {
    fun recordFocusedFile(path: String?) {
        val normalized = path?.trim().orEmpty()
        if (normalized.isBlank() || !context.isMentionCandidateFile(normalized)) return
        context.recentFocusedFiles.remove(normalized)
        context.recentFocusedFiles.addFirst(normalized)
        while (context.recentFocusedFiles.size > 64) {
            context.recentFocusedFiles.removeLast()
        }
    }

    fun requestMentionSuggestions(query: String, documentVersion: Long, mentionLimit: Int) {
        val normalizedQuery = query.trim()
        val subagents = context.composerStore.state.value.sessionSubagents
            .filter { agent ->
                normalizedQuery.isBlank() ||
                    agent.displayName.contains(normalizedQuery, ignoreCase = true) ||
                    agent.mentionSlug.contains(normalizedQuery, ignoreCase = true)
            }
            .let(::sortSessionSubagents)
            .take(mentionLimit)
            .map(MentionSuggestion::Agent)
        val paths = if (normalizedQuery.isBlank()) {
            context.recentFocusedFiles.toList().take(mentionLimit)
        } else {
            context.searchProjectFiles(normalizedQuery, mentionLimit)
        }
        val suggestions = subagents + paths.map(::toContextEntry).map(MentionSuggestion::File)
        context.eventHub.publish(
            AppEvent.MentionSuggestionsUpdated(
                query = normalizedQuery,
                documentVersion = documentVersion,
                suggestions = suggestions,
            ),
        )
    }

    fun pasteImageFromClipboard() {
        val image = readImageFromClipboard() ?: return
        val tempPath = writeClipboardImage(image) ?: return
        context.eventHub.publishUiIntent(UiIntent.AddAttachments(listOf(tempPath)))
    }

    fun openEditedFileDiff(path: String) {
        val aggregate = context.composerStore.state.value.editedFiles.firstOrNull { it.path == path } ?: return
        val resolved = TimelineFileChangePreview.resolve(aggregate.parsedDiff)
        val change = TimelineFileChange(
            sourceScopedId = "turn:${aggregate.turnId}",
            path = aggregate.path,
            displayName = aggregate.displayName,
            kind = aggregate.parsedDiff.kind,
            timestamp = aggregate.lastUpdatedAt,
            addedLines = aggregate.latestAddedLines,
            deletedLines = aggregate.latestDeletedLines,
            unifiedDiff = aggregate.parsedDiff.unifiedDiff,
            oldContent = resolved.oldContent,
            newContent = resolved.newContent,
        )
        context.openTimelineFileChange(change)
    }

    fun revertEditedFile(path: String) {
        val aggregate = context.composerStore.state.value.editedFiles.firstOrNull { it.path == path } ?: return
        val result = TimelineFileChangePreview.revertParsedDiff(aggregate.parsedDiff)
        if (result.isSuccess) {
            context.eventHub.publishUiIntent(UiIntent.AcceptEditedFile(path))
            context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw(result.getOrDefault(""))))
        } else {
            context.eventHub.publish(
                AppEvent.StatusTextUpdated(UiText.raw(result.exceptionOrNull()?.message ?: "Revert failed.")),
            )
        }
    }

    fun revertAllEditedFiles() {
        val aggregates = context.composerStore.state.value.editedFiles
        if (aggregates.isEmpty()) return
        var success = 0
        var failed = 0
        aggregates.forEach { aggregate ->
            val result = TimelineFileChangePreview.revertParsedDiff(aggregate.parsedDiff)
            if (result.isSuccess) {
                success += 1
                context.eventHub.publishUiIntent(UiIntent.AcceptEditedFile(aggregate.path))
            } else {
                failed += 1
            }
        }
        context.eventHub.publish(
            AppEvent.StatusTextUpdated(
                UiText.raw(if (failed == 0) "Reverted $success files." else "Reverted $success files, failed $failed."),
            ),
        )
    }

    fun stageAttachments(sessionId: String, attachments: List<AttachmentEntry>): List<PersistedMessageAttachment> {
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
            val targetDir = Path.of(PathManager.getSystemPath(), "aura-code", "chat-assets", sessionId)
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

    fun buildContextFiles(
        contextEntries: List<ContextEntry>,
        attachments: List<PersistedMessageAttachment>,
    ): List<com.auracode.assistant.model.ContextFile> {
        val merged = LinkedHashMap<String, com.auracode.assistant.model.ContextFile>()
        contextEntries.forEach { entry ->
            val contextFile = if (entry.isSelectionContext && !entry.selectedText.isNullOrBlank()) {
                com.auracode.assistant.model.ContextFile(
                    path = entry.encodedContextPath(),
                    content = entry.selectedText.orEmpty(),
                )
            } else {
                com.auracode.assistant.model.ContextFile(path = entry.path)
            }
            merged.putIfAbsent(entry.path, contextFile)
        }
        attachments
            .filter { it.kind == PersistedAttachmentKind.TEXT }
            .forEach { attachment ->
                val displayPath = attachment.originalPath.ifBlank { attachment.assetPath }
                merged.putIfAbsent(displayPath, com.auracode.assistant.model.ContextFile(path = displayPath))
            }
        return merged.values.toList()
    }

    private fun toContextEntry(path: String): ContextEntry {
        val p = runCatching { Path.of(path) }.getOrNull()
        val name = p?.name ?: path.substringAfterLast('/').substringAfterLast('\\')
        val tail = p?.parent?.fileName?.toString().orEmpty()
        return ContextEntry(path = path, displayName = name.ifBlank { path }, tailPath = tail)
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
        val path = runCatching { Files.createTempFile("codex-clip-", ".png") }.getOrNull() ?: return null
        return runCatching {
            Files.newOutputStream(path, StandardOpenOption.WRITE).use { out ->
                ImageIO.write(image, "png", out)
            }
            path.toAbsolutePath().toString()
        }.getOrNull()
    }

    private fun ContextEntry.encodedContextPath(): String {
        val start = startLine ?: return path
        val end = endLine ?: return path
        return if (start == end) "$path:$start" else "$path:$start-$end"
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
