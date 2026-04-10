package com.auracode.assistant.toolwindow.shared

import androidx.compose.material.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.settings.UiScaleMode

internal data class AssistantSpacing(
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
)

internal data class AssistantTypeScale(
    val meta: TextUnit,
    val body: TextUnit,
    val label: TextUnit,
    val sectionTitle: TextUnit,
    val title: TextUnit,
)

internal data class AssistantControlScale(
    val iconSm: Dp,
    val iconMd: Dp,
    val iconLg: Dp,
    val headerActionTouch: Dp,
    val sendButton: Dp,
    val railItem: Dp,
    val attachmentCard: Dp,
)

internal data class AssistantMarkdownScale(
    val codePadding: Dp,
    val codeCorner: Dp,
    val quoteIndent: Dp,
    val quoteThickness: Dp,
    val blockSpacing: Dp,
    val listSpacing: Dp,
    val listItemTop: Dp,
    val listItemBottom: Dp,
    val listIndent: Dp,
    val tableCellPadding: Dp,
)

internal data class AssistantUiTokens(
    val spacing: AssistantSpacing,
    val type: AssistantTypeScale,
    val controls: AssistantControlScale,
    val markdown: AssistantMarkdownScale,
)

internal fun assistantUiTokens(): AssistantUiTokens {
    val mode = runCatching { AgentSettingsService.getInstance().uiScaleMode() }
        .getOrDefault(UiScaleMode.P100)
    return assistantUiTokens(mode)
}

internal fun assistantUiTokens(mode: UiScaleMode): AssistantUiTokens {
    val scale = when (mode) {
        UiScaleMode.P80 -> 0.8f
        UiScaleMode.P90 -> 0.9f
        UiScaleMode.P100 -> 1.0f
        UiScaleMode.P110 -> 1.1f
        UiScaleMode.P120 -> 1.2f
    }
    return assistantUiTokens(scale)
}

private fun assistantUiTokens(scale: Float): AssistantUiTokens {
    fun scaledDp(value: Float, min: Float = 1f): Dp = (value * scale).coerceAtLeast(min).dp
    fun scaledText(value: Float): TextUnit = (value * scale).sp
    fun scaledControl(value: Float, min: Float): Dp = (value * scale).coerceIn(min, value * 1.35f).dp
    fun scaledIcon(value: Float): Dp = (value * (0.95f + ((scale - 1f) * 0.6f))).coerceAtLeast(10f).dp

    return AssistantUiTokens(
        spacing = AssistantSpacing(
            xs = scaledDp(4f, min = 3f),
            sm = scaledDp(8f, min = 6f),
            md = scaledDp(12f, min = 9f),
            lg = scaledDp(16f, min = 12f),
            xl = scaledDp(20f, min = 16f),
        ),
        type = AssistantTypeScale(
            meta = scaledText(11f),
            body = scaledText(14f),
            label = scaledText(12f),
            sectionTitle = scaledText(14f),
            title = scaledText(15f),
        ),
        controls = AssistantControlScale(
            iconSm = scaledIcon(12f),
            iconMd = scaledIcon(14f),
            iconLg = scaledIcon(18f),
            headerActionTouch = scaledControl(24f, min = 22f),
            sendButton = scaledControl(38f, min = 34f),
            railItem = scaledControl(40f, min = 36f),
            attachmentCard = scaledControl(44f, min = 38f),
        ),
        markdown = AssistantMarkdownScale(
            codePadding = scaledDp(8f, min = 6f),
            codeCorner = 8.dp,
            quoteIndent = scaledDp(8f, min = 6f),
            quoteThickness = scaledDp(3f, min = 2f),
            blockSpacing = scaledDp(6f, min = 4f),
            listSpacing = scaledDp(4f, min = 3f),
            listItemTop = scaledDp(3f, min = 2f),
            listItemBottom = scaledDp(4f, min = 3f),
            listIndent = scaledDp(12f, min = 9f),
            tableCellPadding = scaledDp(8f, min = 6f),
        ),
    )
}

internal fun assistantTypography(tokens: AssistantUiTokens = assistantUiTokens()): Typography {
    fun scaledLineHeight(fontSize: TextUnit, multiplier: Float): TextUnit = (fontSize.value * multiplier).sp
    val body = TextStyle(fontSize = tokens.type.body, lineHeight = scaledLineHeight(tokens.type.body, 1.5f))
    val bodyCompact = TextStyle(fontSize = tokens.type.label, lineHeight = scaledLineHeight(tokens.type.label, 1.33f))
    return Typography(
        h4 = TextStyle(fontSize = (16f * (tokens.type.body.value / 14f)).sp, lineHeight = scaledLineHeight((16f * (tokens.type.body.value / 14f)).sp, 1.37f), fontWeight = FontWeight.SemiBold),
        h5 = TextStyle(fontSize = tokens.type.title, lineHeight = scaledLineHeight(tokens.type.title, 1.33f), fontWeight = FontWeight.SemiBold),
        h6 = TextStyle(fontSize = tokens.type.sectionTitle, lineHeight = scaledLineHeight(tokens.type.sectionTitle, 1.28f), fontWeight = FontWeight.SemiBold),
        subtitle1 = TextStyle(fontSize = (13f * (tokens.type.body.value / 14f)).sp, lineHeight = scaledLineHeight((13f * (tokens.type.body.value / 14f)).sp, 1.31f), fontWeight = FontWeight.Medium),
        subtitle2 = TextStyle(fontSize = tokens.type.meta, lineHeight = scaledLineHeight(tokens.type.meta, 1.36f), fontWeight = FontWeight.Medium),
        body1 = body,
        body2 = bodyCompact,
        button = TextStyle(fontSize = tokens.type.label, lineHeight = scaledLineHeight(tokens.type.label, 1.33f), fontWeight = FontWeight.Medium),
        caption = TextStyle(fontSize = tokens.type.meta, lineHeight = scaledLineHeight(tokens.type.meta, 1.27f)),
        overline = TextStyle(fontSize = (10f * (tokens.type.body.value / 14f)).sp, lineHeight = scaledLineHeight((10f * (tokens.type.body.value / 14f)).sp, 1.2f), fontWeight = FontWeight.Medium),
    )
}

internal fun assistantMonospaceStyle(tokens: AssistantUiTokens = assistantUiTokens()): TextStyle {
    return TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = tokens.type.label,
        lineHeight = (tokens.type.label.value * 1.5f).sp,
    )
}
