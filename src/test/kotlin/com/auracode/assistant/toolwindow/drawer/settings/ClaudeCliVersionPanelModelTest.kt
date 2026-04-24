package com.auracode.assistant.toolwindow.drawer.settings

import com.auracode.assistant.provider.claude.ClaudeCliUpgradeSource
import com.auracode.assistant.provider.claude.ClaudeCliVersionCheckStatus
import com.auracode.assistant.provider.claude.ClaudeCliVersionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeCliVersionPanelModelTest {
    @Test
    fun `supported Claude update shows upgrade action`() {
        val model = buildClaudeRuntimeCliVersionPanelModel(
            ClaudeCliVersionSnapshot(
                checkStatus = ClaudeCliVersionCheckStatus.UPDATE_AVAILABLE,
                currentVersion = "2.1.74",
                latestVersion = "2.1.119",
                upgradeSource = ClaudeCliUpgradeSource.NPM,
                displayCommand = "npm install -g @anthropic-ai/claude-code@latest",
                isUpgradeSupported = true,
            ),
        )

        assertEquals("Upgrade Now", model.primaryActionLabel)
        assertEquals("Update available", model.statusText)
        assertTrue(model.showPrimaryAction)
        assertFalse(model.showManualUpgradeHint)
    }

    @Test
    fun `unsupported Claude update keeps manual command visible`() {
        val model = buildClaudeRuntimeCliVersionPanelModel(
            ClaudeCliVersionSnapshot(
                checkStatus = ClaudeCliVersionCheckStatus.UPDATE_AVAILABLE,
                currentVersion = "2.1.74",
                latestVersion = "2.1.119",
                upgradeSource = ClaudeCliUpgradeSource.UNKNOWN,
                displayCommand = "npm install -g @anthropic-ai/claude-code@latest",
                isUpgradeSupported = false,
            ),
        )

        assertEquals("Check for Updates", model.primaryActionLabel)
        assertEquals("Update available", model.statusText)
        assertTrue(model.showManualUpgradeHint)
        assertTrue(model.showVersionCommand)
    }
}
