package com.auracode.assistant.toolwindow.composer

import com.auracode.assistant.settings.UiScaleMode
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the shared file icon sizing contract used by composer file rows and chips.
 */
class ComposerFileTypeIconTest {
    /**
     * Ensures composer file icons reuse the shared medium control size.
     */
    @Test
    fun `composer file icons reuse medium control sizing`() {
        val tokens = assistantUiTokens(UiScaleMode.P100)

        assertEquals(tokens.controls.iconMd, composerFileTypeIconSize(tokens))
    }

    /**
     * Ensures composer file icon sizing follows the current UI scale mode.
     */
    @Test
    fun `composer file icon size tracks ui scale changes`() {
        val base = composerFileTypeIconSize(assistantUiTokens(UiScaleMode.P100))
        val small = composerFileTypeIconSize(assistantUiTokens(UiScaleMode.P80))
        val large = composerFileTypeIconSize(assistantUiTokens(UiScaleMode.P120))

        assertTrue(small < base)
        assertTrue(large > base)
    }
}
