package com.auracode.assistant.provider.codex

import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.ProviderItem
import com.auracode.assistant.protocol.ProviderMessageAttachment
import com.intellij.openapi.application.PathManager
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Builds normalized provider items for Codex image-generation lifecycle events.
 */
internal object CodexImageGenerationSupport {
    /**
     * Creates one assistant-style message item that reuses the existing attachment UI pipeline.
     */
    fun buildProviderItem(
        item: JsonObject,
        itemId: String,
        status: ItemStatus,
        assetNamespace: String,
    ): ProviderItem {
        val attachment = resolveAttachment(
            item = item,
            itemId = itemId,
            assetNamespace = assetNamespace,
        )
        return ProviderItem(
            id = itemId,
            kind = ItemKind.NARRATIVE,
            status = status,
            name = "message",
            text = imageGenerationText(status),
            attachments = listOfNotNull(attachment),
        )
    }

    /**
     * Resolves the generated image into a persisted attachment, preferring actual base64 payloads.
     */
    fun resolveAttachment(
        item: JsonObject,
        itemId: String,
        assetNamespace: String,
    ): ProviderMessageAttachment? {
        val decodedPath = item.string("result")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { base64 ->
                persistBase64Image(
                    base64 = base64,
                    itemId = itemId,
                    assetNamespace = assetNamespace,
                )
            }
        val imagePath = decodedPath
            ?: item.string("savedPath")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }
            ?: return null
        if (!imagePath.isRegularFile()) return null
        val absolutePath = imagePath.toAbsolutePath().toString()
        return ProviderMessageAttachment(
            id = "$itemId:image",
            kind = "image",
            displayName = imagePath.name.ifBlank { "generated.png" },
            assetPath = absolutePath,
            originalPath = absolutePath,
            mimeType = imageMimeType(imagePath),
            sizeBytes = runCatching { imagePath.fileSize() }.getOrDefault(0L),
            status = ItemStatus.SUCCESS,
        )
    }

    /**
     * Normalizes the user-facing assistant text for image generation status.
     */
    fun imageGenerationText(status: ItemStatus): String {
        return when (status) {
            ItemStatus.RUNNING -> "Generating image"
            ItemStatus.SUCCESS -> "Generated image"
            ItemStatus.FAILED -> "Image generation failed"
            ItemStatus.SKIPPED -> "Image generation skipped"
        }
    }

    /**
     * Writes the received base64 image into Aura's system asset directory.
     */
    private fun persistBase64Image(
        base64: String,
        itemId: String,
        assetNamespace: String,
    ): Path? {
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: return null
        val targetDir = runCatching {
            Path.of(PathManager.getSystemPath(), "aura-code", "generated-images", assetNamespace)
        }.getOrNull() ?: return null
        runCatching { Files.createDirectories(targetDir) }.getOrNull() ?: return null
        val safeFileName = itemId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = targetDir.resolve("$safeFileName.png")
        return runCatching {
            Files.write(
                target,
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            target
        }.getOrNull()
    }

    /**
     * Infers a stable mime type from the generated image path.
     */
    private fun imageMimeType(path: Path): String {
        return when (path.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/png"
        }
    }

    /**
     * Reads one optional string field from a Kotlin serialization object payload.
     */
    private fun JsonObject.string(key: String): String? {
        return this[key]?.toString()?.trim('"')
    }
}
