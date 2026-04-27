package com.auracode.assistant.toolwindow.settings

import com.auracode.assistant.provider.codex.CodexCliUpgradeSource
import com.auracode.assistant.provider.codex.CodexCliVersionCheckStatus
import com.auracode.assistant.provider.codex.CodexCliVersionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexCliVersionPanelModelTest {
    @Test
    fun `up to date state keeps only the check action and hides manual upgrade metadata`() {
        val model = buildCodexRuntimeCliVersionPanelModel(
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
        assertEquals("Up to date", model.statusText)
        assertTrue(model.showPrimaryAction)
        assertFalse(model.showManualUpgradeHint)
        assertFalse(model.showVersionCommand)
    }

    @Test
    fun `unsupported update available state keeps manual upgrade guidance without auto upgrade action`() {
        val model = buildCodexRuntimeCliVersionPanelModel(
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
        assertEquals("Update available", model.statusText)
        assertTrue(model.showPrimaryAction)
        assertTrue(model.showManualUpgradeHint)
        assertTrue(model.showVersionCommand)
        assertEquals("0.118.0", model.currentVersionText)
        assertEquals("0.119.0", model.latestVersionText)
    }

    @Test
    fun `supported update available state shows upgrade action and hides manual hint`() {
        val model = buildCodexRuntimeCliVersionPanelModel(
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
        assertEquals("Update available", model.statusText)
        assertTrue(model.showPrimaryAction)
        assertFalse(model.showManualUpgradeHint)
    }

    @Test
    fun `checking state moves feedback onto the primary button`() {
        val model = buildCodexRuntimeCliVersionPanelModel(
            CodexCliVersionSnapshot(
                checkStatus = CodexCliVersionCheckStatus.CHECKING,
                currentVersion = "0.118.0",
                latestVersion = "0.118.0",
            ),
        )

        assertEquals("Checking...", model.primaryActionLabel)
        assertEquals("Checking", model.statusText)
        assertTrue(model.isBusy)
    }

    @Test
    fun `ignored update state surfaces ignored status text`() {
        val model = buildCodexRuntimeCliVersionPanelModel(
            CodexCliVersionSnapshot(
                checkStatus = CodexCliVersionCheckStatus.UPDATE_AVAILABLE,
                currentVersion = "0.118.0",
                latestVersion = "0.120.0",
                ignoredVersion = "0.120.0",
                upgradeSource = CodexCliUpgradeSource.UNKNOWN,
                displayCommand = "npm install -g @openai/codex@latest",
                isUpgradeSupported = false,
            ),
        )

        assertEquals("Update available (ignored: 0.120.0)", model.statusText)
    }
}
