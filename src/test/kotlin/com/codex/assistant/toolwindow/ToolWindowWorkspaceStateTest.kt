package com.codex.assistant.toolwindow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolWindowWorkspaceStateTest {
    @Test
    fun `defaults to console workspace with run chrome visible`() {
        val state = ToolWindowWorkspaceState()

        assertEquals(ToolWindowView.CONSOLE, state.currentView)
        assertFalse(state.showBackAction)
        assertTrue(state.showRunContextBar)
        assertTrue(state.showComposerDock)
        assertEquals("Codex Assistant", state.currentTitle)
    }

    @Test
    fun `navigating to history hides console chrome until returning`() {
        val state = ToolWindowWorkspaceState()

        state.navigateTo(ToolWindowView.SESSION_HISTORY)

        assertEquals(ToolWindowView.SESSION_HISTORY, state.currentView)
        assertTrue(state.showBackAction)
        assertFalse(state.showRunContextBar)
        assertFalse(state.showComposerDock)
        assertEquals("Session History", state.currentTitle)

        state.navigateBack()

        assertEquals(ToolWindowView.CONSOLE, state.currentView)
        assertFalse(state.showBackAction)
        assertTrue(state.showRunContextBar)
        assertTrue(state.showComposerDock)
        assertEquals("Codex Assistant", state.currentTitle)
    }

    @Test
    fun `settings and context manager share embedded workspace behavior`() {
        val state = ToolWindowWorkspaceState()

        state.navigateTo(ToolWindowView.SETTINGS)
        assertEquals("Settings", state.currentTitle)
        assertFalse(state.showComposerDock)

        state.navigateTo(ToolWindowView.CONTEXT_FILES)
        assertEquals("Context Files", state.currentTitle)
        assertTrue(state.showBackAction)
        assertFalse(state.showRunContextBar)
    }
}
