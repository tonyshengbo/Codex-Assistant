package com.codex.assistant.toolwindow.shared

import androidx.compose.material.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val quoteIndent: Dp,
    val tableCellPadding: Dp,
)

internal data class AssistantUiTokens(
    val spacing: AssistantSpacing,
    val type: AssistantTypeScale,
    val controls: AssistantControlScale,
    val markdown: AssistantMarkdownScale,
)

internal fun assistantUiTokens(): AssistantUiTokens {
    return AssistantUiTokens(
        spacing = AssistantSpacing(
            xs = 4.dp,
            sm = 8.dp,
            md = 12.dp,
            lg = 16.dp,
            xl = 20.dp,
        ),
        type = AssistantTypeScale(
            meta = 11.sp,
            body = 14.sp,
            label = 12.sp,
            sectionTitle = 14.sp,
            title = 15.sp,
        ),
        controls = AssistantControlScale(
            iconSm = 12.dp,
            iconMd = 14.dp,
            iconLg = 18.dp,
            headerActionTouch = 24.dp,
            sendButton = 38.dp,
            railItem = 40.dp,
            attachmentCard = 44.dp,
        ),
        markdown = AssistantMarkdownScale(
            codePadding = 8.dp,
            quoteIndent = 8.dp,
            tableCellPadding = 8.dp,
        ),
    )
}

internal fun assistantTypography(tokens: AssistantUiTokens = assistantUiTokens()): Typography {
    val body = TextStyle(fontSize = tokens.type.body, lineHeight = 21.sp)
    val bodyCompact = TextStyle(fontSize = tokens.type.label, lineHeight = 16.sp)
    return Typography(
        h4 = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        h5 = TextStyle(fontSize = tokens.type.title, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        h6 = TextStyle(fontSize = tokens.type.sectionTitle, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        subtitle1 = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
        subtitle2 = TextStyle(fontSize = tokens.type.meta, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
        body1 = body,
        body2 = bodyCompact,
        button = TextStyle(fontSize = tokens.type.label, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        caption = TextStyle(fontSize = tokens.type.meta, lineHeight = 14.sp),
        overline = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium),
    )
}

internal fun assistantMonospaceStyle(tokens: AssistantUiTokens = assistantUiTokens()): TextStyle {
    return TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = tokens.type.label,
        lineHeight = 18.sp,
    )
}
