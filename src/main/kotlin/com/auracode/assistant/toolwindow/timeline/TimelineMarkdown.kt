package com.auracode.assistant.toolwindow.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantMonospaceStyle
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import com.mikepenz.markdown.m2.Markdown
import com.mikepenz.markdown.m2.markdownColor
import com.mikepenz.markdown.m2.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import java.awt.Desktop
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Composable
internal fun TimelineMarkdown(
    text: String,
    palette: DesignPalette,
    modifier: Modifier = Modifier,
    onOpenFilePath: ((String) -> Unit)? = null,
) {
    val tokens = assistantUiTokens()
    val typographyScale = tokens.type.body.value / 14f
    fun scaledSp(value: Float) = (value * typographyScale).sp
    val bodyLineHeight = timelineMarkdownBodyLineHeightSp(tokens.type.body.value)
    val externalUriHandler = LocalUriHandler.current
    val uriHandler = rememberTimelineMarkdownUriHandler(
        externalUriHandler = externalUriHandler,
        onOpenFilePath = onOpenFilePath,
    )
    TimelineSelectableText {
        CompositionLocalProvider(LocalUriHandler provides uriHandler) {
            Markdown(
                content = text,
                colors = markdownColor(
                    text = palette.timelinePlainText,
                    codeText = palette.markdownCodeText,
                    inlineCodeText = palette.markdownCodeText,
                    linkText = palette.linkColor,
                    codeBackground = palette.markdownCodeBg,
                    inlineCodeBackground = palette.markdownInlineCodeBg,
                    dividerColor = palette.markdownDivider.copy(alpha = 0.82f),
                    tableText = palette.timelineCardText,
                    tableBackground = palette.markdownTableBg,
                ),
                typography = markdownTypography(
                    h1 = androidx.compose.material.MaterialTheme.typography.h5.copy(
                        color = palette.timelineCardText,
                        fontWeight = FontWeight.Bold,
                        lineHeight = scaledSp(24f),
                    ),
                    h2 = androidx.compose.material.MaterialTheme.typography.h6.copy(
                        color = palette.timelineCardText,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = scaledSp(21f),
                    ),
                    h3 = androidx.compose.material.MaterialTheme.typography.subtitle1.copy(
                        color = palette.timelineCardText,
                        fontWeight = FontWeight.Medium,
                        lineHeight = scaledSp(18f),
                    ),
                    h4 = androidx.compose.material.MaterialTheme.typography.subtitle1.copy(
                        color = palette.timelineCardText,
                        fontWeight = FontWeight.Medium,
                        lineHeight = scaledSp(18f),
                    ),
                    h5 = androidx.compose.material.MaterialTheme.typography.body1.copy(
                        color = palette.timelineCardText,
                        fontWeight = FontWeight.Medium,
                        lineHeight = scaledSp(20f),
                    ),
                    h6 = androidx.compose.material.MaterialTheme.typography.body2.copy(
                        color = palette.timelineCardText,
                        fontWeight = FontWeight.Medium,
                        lineHeight = scaledSp(16f),
                    ),
                    text = androidx.compose.material.MaterialTheme.typography.body1.copy(
                        color = palette.timelinePlainText,
                        lineHeight = bodyLineHeight.sp,
                    ),
                    paragraph = androidx.compose.material.MaterialTheme.typography.body1.copy(
                        color = palette.timelinePlainText,
                        lineHeight = bodyLineHeight.sp,
                    ),
                    ordered = androidx.compose.material.MaterialTheme.typography.body1.copy(
                        color = palette.timelinePlainText,
                        lineHeight = bodyLineHeight.sp,
                    ),
                    bullet = androidx.compose.material.MaterialTheme.typography.body1.copy(
                        color = palette.timelinePlainText,
                        lineHeight = bodyLineHeight.sp,
                    ),
                    list = androidx.compose.material.MaterialTheme.typography.body1.copy(
                        color = palette.timelinePlainText,
                        lineHeight = bodyLineHeight.sp,
                    ),
                    quote = androidx.compose.material.MaterialTheme.typography.body2.copy(
                        color = palette.markdownQuoteText,
                        lineHeight = scaledSp(18f),
                    ),
                    code = assistantMonospaceStyle(tokens).copy(
                        color = palette.markdownCodeText,
                        lineHeight = scaledSp(18f),
                    ),
                    inlineCode = assistantMonospaceStyle(tokens).copy(
                        color = palette.markdownCodeText,
                        fontWeight = FontWeight.Medium,
                    ),
                    link = androidx.compose.material.MaterialTheme.typography.body1.copy(
                        color = palette.linkColor,
                        fontWeight = FontWeight.Medium,
                        textDecoration = TextDecoration.Underline,
                        lineHeight = bodyLineHeight.sp,
                    ),
                    textLink = TextLinkStyles(
                        style = SpanStyle(
                            color = palette.linkColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium,
                        ),
                    ),
                ),
                padding = markdownPadding(
                    block = tokens.markdown.blockSpacing,
                    list = tokens.markdown.listSpacing,
                    listItemTop = tokens.markdown.listItemTop,
                    listItemBottom = tokens.markdown.listItemBottom,
                    listIndent = tokens.markdown.listIndent,
                    codeBlock = androidx.compose.foundation.layout.PaddingValues(tokens.markdown.codePadding),
                    blockQuote = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = tokens.markdown.quoteIndent + 2.dp,
                        vertical = 2.dp,
                    ),
                    blockQuoteText = androidx.compose.foundation.layout.PaddingValues(vertical = 2.dp),
                    blockQuoteBar = androidx.compose.foundation.layout.PaddingValues.Absolute(
                        left = 0.dp,
                        top = 2.dp,
                        right = tokens.spacing.xs,
                        bottom = 2.dp,
                    ),
                ),
                dimens = markdownDimens(
                    codeBackgroundCornerSize = tokens.markdown.codeCorner,
                    blockQuoteThickness = tokens.markdown.quoteThickness,
                    tableCellPadding = tokens.markdown.tableCellPadding,
                    tableCornerSize = tokens.markdown.codeCorner,
                ),
                modifier = modifier,
            )
        }
    }
}

internal fun timelineMarkdownBodyLineHeightSp(bodyFontSizeSp: Float): Float {
    return bodyFontSizeSp * 1.5f
}

@Composable
private fun rememberTimelineMarkdownUriHandler(
    externalUriHandler: UriHandler,
    onOpenFilePath: ((String) -> Unit)?,
): UriHandler {
    return object : UriHandler {
        override fun openUri(target: String) {
            val localPath = resolveTimelineMarkdownFilePath(target)
            when {
                localPath != null && onOpenFilePath != null -> onOpenFilePath(localPath)
                isSafeHttpUrl(target) -> externalUriHandler.openUri(target)
                else -> Unit
            }
        }
    }
}

internal fun isSafeHttpUrl(url: String): Boolean {
    return try {
        val uri = URI(url.trim())
        val scheme = uri.scheme?.lowercase()
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }
}

internal fun resolveTimelineMarkdownFilePath(target: String): String? {
    val normalized = target.trim()
    if (normalized.isBlank()) return null
    if (isSafeHttpUrl(normalized)) return null

    if (normalized.startsWith("file://")) {
        return runCatching {
            val uri = URI(normalized)
            uri.path?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    val withoutFragment = normalized.substringBefore('#').substringBefore('?')
    if (withoutFragment.startsWith("/")) {
        return decodeTimelinePath(withoutFragment)
    }

    val windowsPathPattern = Regex("""^[A-Za-z]:[\\/].+""")
    if (windowsPathPattern.matches(withoutFragment)) {
        return decodeTimelinePath(withoutFragment)
    }

    return null
}

private fun decodeTimelinePath(path: String): String {
    return runCatching {
        URLDecoder.decode(path, StandardCharsets.UTF_8)
    }.getOrDefault(path)
}

internal fun openExternalUrlSafely(url: String) {
    if (!isSafeHttpUrl(url)) return
    runCatching {
        if (!Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE)) {
            desktop.browse(URI(url))
        }
    }
}
