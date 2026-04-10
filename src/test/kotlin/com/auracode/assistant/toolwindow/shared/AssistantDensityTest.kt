package com.auracode.assistant.toolwindow.shared

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auracode.assistant.settings.UiScaleMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistantDensityTest {
    @Test
    fun `compact ui tokens define reduced text and spacing scale`() {
        val tokens = assistantUiTokens()

        assertEquals(4.dp, tokens.spacing.xs)
        assertEquals(8.dp, tokens.spacing.sm)
        assertEquals(12.dp, tokens.spacing.md)
        assertEquals(16.dp, tokens.spacing.lg)
        assertEquals(14.sp, tokens.type.body)
        assertEquals(11.sp, tokens.type.meta)
        assertEquals(14.sp, tokens.type.sectionTitle)
        assertEquals(15.sp, tokens.type.title)
        assertEquals(24.dp, tokens.controls.headerActionTouch)
        assertEquals(18.dp, tokens.controls.iconLg)
        assertEquals(38.dp, tokens.controls.sendButton)
        assertEquals(40.dp, tokens.controls.railItem)
        assertEquals(44.dp, tokens.controls.attachmentCard)
    }

    @Test
    fun `compact markdown tokens keep code and quote surfaces tighter than defaults`() {
        val tokens = assistantUiTokens()

        assertEquals(8.dp, tokens.markdown.codePadding)
        assertEquals(8.dp, tokens.markdown.quoteIndent)
        assertEquals(8.dp, tokens.markdown.tableCellPadding)
    }

    @Test
    fun `small scale mode shrinks body typography and spacing`() {
        val base = assistantUiTokens(UiScaleMode.P100)
        val small = assistantUiTokens(UiScaleMode.P80)

        assertTrue(small.type.body < base.type.body)
        assertTrue(small.spacing.md < base.spacing.md)
        assertTrue(small.controls.sendButton < base.controls.sendButton)
    }

    @Test
    fun `large scale mode expands body typography and spacing`() {
        val base = assistantUiTokens(UiScaleMode.P100)
        val large = assistantUiTokens(UiScaleMode.P120)

        assertTrue(large.type.body > base.type.body)
        assertTrue(large.spacing.md > base.spacing.md)
        assertTrue(large.controls.sendButton > base.controls.sendButton)
    }
}
