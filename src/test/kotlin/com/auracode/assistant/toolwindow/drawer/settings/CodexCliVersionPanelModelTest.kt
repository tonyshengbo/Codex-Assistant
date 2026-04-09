package com.auracode.assistant.toolwindow.drawer.settings

import com.auracode.assistant.provider.codex.CodexCliUpgradeSource
import com.auracode.assistant.provider.codex.CodexCliVersionCheckStatus
import com.auracode.assistant.provider.codex.CodexCliVersionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexCliVersionPanelModelTest {
    @Test
    fun `up to date state keeps only the check action and hides low priority metadata`() {
        val model = buildCodexCliVersionPanelModel(
            CodexCliVersionSnapshot(
                checkStatus = CodexCliVersionCheckStatus.UP_TO_DATE,
                currentVersion = "0.118.0",
                latestVersion = "0.118.0",
                upgradeSource = CodexCliUpgradeSource.BREW,
                displayCommand = "brew upgrade codex",
                isUpgradeSupported = true,
                message = "Codex CLI is up to date.",
            ),
        )

        assertEquals("Up to date", model.primaryActionLabel)
        assertTrue(model.showPrimaryAction)
        assertFalse(model.showManualUpgradeHint)
        assertFalse(model.showVersionCommand)
        assertFalse(model.showStatusBadge)
        assertFalse(model.showAutoCheckToggle)
    }

    @Test
    fun `unsupported update available state keeps manual upgrade guidance without auto upgrade action`() {
        val model = buildCodexCliVersionPanelModel(
            CodexCliVersionSnapshot(
                checkStatus = CodexCliVersionCheckStatus.UPDATE_AVAILABLE,
                currentVersion = "0.118.0",
                latestVersion = "0.119.0",
                upgradeSource = CodexCliUpgradeSource.UNKNOWN,
                displayCommand = "npm install -g @openai/codex@latest",
                isUpgradeSupported = false,
                message = "A newer Codex CLI version is available.",
            ),
        )

        assertEquals("Check for Updates", model.primaryActionLabel)
        assertTrue(model.showPrimaryAction)
        assertTrue(model.showManualUpgradeHint)
        assertTrue(model.showVersionCommand)
        assertEquals("0.118.0", model.currentVersionText)
        assertEquals("0.119.0", model.latestVersionText)
    }

    @Test
    fun `supported update available state shows upgrade action and hides manual hint`() {
        val model = buildCodexCliVersionPanelModel(
            CodexCliVersionSnapshot(
                checkStatus = CodexCliVersionCheckStatus.UPDATE_AVAILABLE,
                currentVersion = "0.118.0",
                latestVersion = "0.119.0",
                upgradeSource = CodexCliUpgradeSource.BREW,
                displayCommand = "brew upgrade codex",
                isUpgradeSupported = true,
            ),
        )

        assertEquals("Upgrade Now", model.primaryActionLabel)
        assertTrue(model.showPrimaryAction)
        assertFalse(model.showManualUpgradeHint)
        assertFalse(model.showStatusBadge)
    }

    @Test
    fun `checking state moves feedback onto the primary button`() {
        val model = buildCodexCliVersionPanelModel(
            CodexCliVersionSnapshot(
                checkStatus = CodexCliVersionCheckStatus.CHECKING,
                currentVersion = "0.118.0",
                latestVersion = "0.118.0",
            ),
        )

        assertEquals("Checking...", model.primaryActionLabel)
        assertTrue(model.isBusy)
        assertFalse(model.showStatusBadge)
    }
}
