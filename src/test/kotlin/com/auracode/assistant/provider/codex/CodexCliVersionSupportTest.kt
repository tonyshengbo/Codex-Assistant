package com.auracode.assistant.provider.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexCliVersionSupportTest {
    @Test
    fun `semantic version parser extracts release version from codex output`() {
        val version = parseCodexCliSemVer("codex 1.2.3")

        assertEquals(CodexCliSemVer(1, 2, 3), version)
    }

    @Test
    fun `semantic version parser keeps prerelease metadata for ordering`() {
        val version = parseCodexCliSemVer("v1.2.3-beta.4")

        assertEquals(CodexCliSemVer(1, 2, 3, "beta.4"), version)
    }

    @Test
    fun `semantic version comparison treats stable release newer than prerelease`() {
        val stable = CodexCliSemVer(1, 2, 3)
        val prerelease = CodexCliSemVer(1, 2, 3, "beta.4")

        assertTrue(stable > prerelease)
    }

    @Test
    fun `semantic version comparison orders higher patch before lower patch`() {
        val current = CodexCliSemVer(1, 2, 3)
        val latest = CodexCliSemVer(1, 2, 4)

        assertTrue(latest > current)
    }

    @Test
    fun `upgrade source detector recognizes npm global installs`() {
        val detector = CodexCliUpgradeSourceDetector()

        val source = detector.detect("/Users/test/.nvm/versions/node/v22.0.0/bin/codex")

        assertEquals(CodexCliUpgradeSource.NPM, source)
    }

    @Test
    fun `upgrade source detector recognizes homebrew installs`() {
        val detector = CodexCliUpgradeSourceDetector()

        val source = detector.detect("/opt/homebrew/bin/codex")

        assertEquals(CodexCliUpgradeSource.BREW, source)
    }

    @Test
    fun `upgrade source detector falls back to unknown for unrecognized paths`() {
        val detector = CodexCliUpgradeSourceDetector()

        val source = detector.detect("/tmp/tools/codex")

        assertEquals(CodexCliUpgradeSource.UNKNOWN, source)
    }

    @Test
    fun `upgrade action exposes executable command only for recognized sources`() {
        val npmAction = codexCliUpgradeActionFor(CodexCliUpgradeSource.NPM)
        val unknownAction = codexCliUpgradeActionFor(CodexCliUpgradeSource.UNKNOWN)

        assertTrue(npmAction.isUpgradeSupported)
        assertEquals("npm install -g @openai/codex@latest", npmAction.displayCommand)
        assertFalse(unknownAction.isUpgradeSupported)
        assertTrue(unknownAction.displayCommand.isNotBlank())
    }
}
