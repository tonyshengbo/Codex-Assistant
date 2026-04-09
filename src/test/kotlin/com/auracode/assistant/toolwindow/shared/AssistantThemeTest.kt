package com.auracode.assistant.toolwindow.shared

import com.auracode.assistant.settings.UiThemeMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AssistantThemeTest {
    @Test
    fun `follow ide resolves to current ide darkness`() {
        assertEquals(EffectiveTheme.DARK, resolveEffectiveTheme(UiThemeMode.FOLLOW_IDE, ideDark = true))
        assertEquals(EffectiveTheme.LIGHT, resolveEffectiveTheme(UiThemeMode.FOLLOW_IDE, ideDark = false))
    }

    @Test
    fun `manual theme overrides ide darkness`() {
        assertEquals(EffectiveTheme.DARK, resolveEffectiveTheme(UiThemeMode.DARK, ideDark = false))
        assertEquals(EffectiveTheme.LIGHT, resolveEffectiveTheme(UiThemeMode.LIGHT, ideDark = true))
    }

    @Test
    fun `light and dark palettes use different key colors`() {
        val light = assistantPalette(EffectiveTheme.LIGHT)
        val dark = assistantPalette(EffectiveTheme.DARK)

        assertNotEquals(light.appBg, dark.appBg)
        assertNotEquals(light.textPrimary, dark.textPrimary)
        assertNotEquals(light.userBubbleBg, dark.userBubbleBg)
    }

    @Test
    fun `dark palette stays close to ide surfaces and avoids harsh contrast`() {
        val dark = assistantPalette(EffectiveTheme.DARK)

        assertTrue(dark.appBg.red > 0.08f)
        assertTrue(dark.topBarBg.red > dark.appBg.red)
        assertTrue(dark.textPrimary.red < 0.9f)
        assertTrue(dark.timelinePlainText.red < dark.textPrimary.red)
        assertTrue(dark.markdownCodeBg.red > dark.appBg.red)
        assertTrue(dark.markdownTableBg.red >= dark.markdownCodeBg.red)
        assertTrue(dark.userBubbleBg.blue > dark.userBubbleBg.red)
        assertTrue((dark.userBubbleBg.blue - dark.userBubbleBg.red) < 0.14f)
        assertTrue(dark.timelineCardBg.red > dark.appBg.red)
    }

    @Test
    fun `timeline reading tokens stay slightly relaxed without inflating labels`() {
        val tokens = assistantUiTokens()
        val typography = assistantTypography(tokens)

        assertEquals(14.sp, tokens.type.body)
        assertEquals(10.dp, tokens.markdown.codePadding)
        assertEquals(8.dp, tokens.markdown.blockSpacing)
        assertEquals(6.dp, tokens.markdown.listSpacing)
        assertEquals(4.dp, tokens.markdown.listItemTop)
        assertEquals(6.dp, tokens.markdown.listItemBottom)
        assertEquals(12.dp, tokens.markdown.tableCellPadding)
        assertEquals(21.sp, typography.body1.fontSize)
        assertEquals(16.sp, typography.body2.fontSize)
        assertEquals(14.sp, typography.caption.fontSize)
    }

    @Test
    fun `timeline palettes preserve readable text layering in both themes`() {
        val light = assistantPalette(EffectiveTheme.LIGHT)
        val dark = assistantPalette(EffectiveTheme.DARK)

        assertTrue(dark.timelineCardText.red > dark.timelinePlainText.red)
        assertTrue(dark.timelinePlainText.red > dark.textSecondary.red)
        assertTrue(light.timelineCardText.red < light.timelinePlainText.red)
        assertTrue(light.timelinePlainText.red < light.textSecondary.red)
    }
}
