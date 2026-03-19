package com.codex.assistant.toolwindow.shared

import androidx.compose.material.Colors
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.codex.assistant.settings.UiThemeMode
import com.intellij.util.ui.StartupUiUtil

internal enum class EffectiveTheme {
    LIGHT,
    DARK,
}

internal data class DesignPalette(
    val appBg: Color,
    val topBarBg: Color,
    val topStripBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val timelineCardBg: Color,
    val timelineCardText: Color,
    val timelinePlainText: Color,
    val userBubbleBg: Color,
    val markdownCodeBg: Color,
    val markdownInlineCodeBg: Color,
    val markdownCodeText: Color,
    val markdownQuoteText: Color,
    val markdownDivider: Color,
    val markdownTableBg: Color,
    val linkColor: Color,
    val composerBg: Color,
    val accent: Color,
    val success: Color,
    val danger: Color,
)

internal fun resolveEffectiveTheme(mode: UiThemeMode, ideDark: Boolean): EffectiveTheme {
    return when (mode) {
        UiThemeMode.FOLLOW_IDE -> if (ideDark) EffectiveTheme.DARK else EffectiveTheme.LIGHT
        UiThemeMode.LIGHT -> EffectiveTheme.LIGHT
        UiThemeMode.DARK -> EffectiveTheme.DARK
    }
}

internal fun currentIdeDarkTheme(): Boolean = StartupUiUtil.isUnderDarcula

internal fun assistantPalette(theme: EffectiveTheme): DesignPalette {
    return when (theme) {
        EffectiveTheme.DARK -> DesignPalette(
            appBg = Color(0xFF26282C),
            topBarBg = Color(0xFF2B2D31),
            topStripBg = Color(0xFF31343A),
            textPrimary = Color(0xFFD0D5DD),
            textSecondary = Color(0xFFA7AFBB),
            textMuted = Color(0xFF7F8896),
            timelineCardBg = Color(0xFF343840),
            timelineCardText = Color(0xFFD3D9E2),
            timelinePlainText = Color(0xFFBEC5CF),
            userBubbleBg = Color(0xFF3A4350),
            markdownCodeBg = Color(0xFF2D3138),
            markdownInlineCodeBg = Color(0xFF383D46),
            markdownCodeText = Color(0xFFD7DCE5),
            markdownQuoteText = Color(0xFFADB5C1),
            markdownDivider = Color(0xFF4F5662),
            markdownTableBg = Color(0xFF30343B),
            linkColor = Color(0xFF6EA8FF),
            composerBg = Color(0xFF2A2D31),
            accent = Color(0xFF4C8DFF),
            success = Color(0xFF67C27A),
            danger = Color(0xFFE07A84),
        )

        EffectiveTheme.LIGHT -> DesignPalette(
            appBg = Color(0xFFF5F7FB),
            topBarBg = Color(0xFFECEFF6),
            topStripBg = Color(0xFFE6EAF3),
            textPrimary = Color(0xFF182030),
            textSecondary = Color(0xFF4B5870),
            textMuted = Color(0xFF68758F),
            timelineCardBg = Color(0xFFFFFFFF),
            timelineCardText = Color(0xFF22304A),
            timelinePlainText = Color(0xFF263248),
            userBubbleBg = Color(0xFFDCEAFF),
            markdownCodeBg = Color(0xFFF2F6FF),
            markdownInlineCodeBg = Color(0xFFEAF1FF),
            markdownCodeText = Color(0xFF21406E),
            markdownQuoteText = Color(0xFF52607A),
            markdownDivider = Color(0xFFD4DCEB),
            markdownTableBg = Color(0xFFF8FAFF),
            linkColor = Color(0xFF2E6BDE),
            composerBg = Color(0xFFF0F3FA),
            accent = Color(0xFF2E6BDE),
            success = Color(0xFF3DAA55),
            danger = Color(0xFFD74D58),
        )
    }
}

internal fun assistantMaterialColors(theme: EffectiveTheme, palette: DesignPalette): Colors {
    return when (theme) {
        EffectiveTheme.DARK -> darkColors(
            primary = palette.accent,
            primaryVariant = palette.userBubbleBg,
            secondary = palette.accent,
            secondaryVariant = palette.userBubbleBg,
            background = palette.appBg,
            surface = palette.timelineCardBg,
            error = palette.danger,
            onPrimary = palette.textPrimary,
            onSecondary = palette.textPrimary,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onError = palette.textPrimary,
        )

        EffectiveTheme.LIGHT -> lightColors(
            primary = palette.accent,
            primaryVariant = palette.userBubbleBg,
            secondary = palette.accent,
            secondaryVariant = palette.userBubbleBg,
            background = palette.appBg,
            surface = palette.timelineCardBg,
            error = palette.danger,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onError = Color.White,
        )
    }
}

@Composable
internal fun assistantPalette(mode: UiThemeMode): DesignPalette {
    return assistantPalette(resolveEffectiveTheme(mode, currentIdeDarkTheme()))
}
