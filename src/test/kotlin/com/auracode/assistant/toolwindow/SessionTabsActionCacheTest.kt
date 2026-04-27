package com.auracode.assistant.toolwindow

import com.auracode.assistant.toolwindow.sessions.SessionTabsActionCache
import com.auracode.assistant.toolwindow.sessions.SessionTab
import com.auracode.assistant.toolwindow.sessions.SessionTabsLayout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionTabsActionCacheTest {
    @Test
    fun `reuses header tab action instances when layout structure stays the same`() {
        val cache = SessionTabsActionCache(onSelect = {}, onClose = {})
        val first = cache.update(
            SessionTabsLayout(
                visibleTabs = listOf(
                    tab("s1", active = true),
                    tab("s2", active = false),
                ),
                overflowTabs = emptyList(),
            ),
        )
        val second = cache.update(
            SessionTabsLayout(
                visibleTabs = listOf(
                    tab("s1", active = false),
                    tab("s2", active = true),
                ),
                overflowTabs = emptyList(),
            ),
        )

        assertTrue(first.structureChanged)
        assertFalse(second.structureChanged)
        assertSame(first.actions[0], second.actions[0])
        assertSame(first.actions[1], second.actions[1])
    }

    @Test
    fun `reuses overflow action instance while overflow membership remains stable`() {
        val cache = SessionTabsActionCache(onSelect = {}, onClose = {})
        val first = cache.update(
            SessionTabsLayout(
                visibleTabs = listOf(tab("s1"), tab("s2"), tab("s3")),
                overflowTabs = listOf(tab("s4")),
            ),
        )
        val second = cache.update(
            SessionTabsLayout(
                visibleTabs = listOf(tab("s1"), tab("s2"), tab("s3")),
                overflowTabs = listOf(tab("s4", active = true)),
            ),
        )

        assertTrue(first.structureChanged)
        assertFalse(second.structureChanged)
        assertSame(first.actions.last(), second.actions.last())
    }

    private fun tab(sessionId: String, active: Boolean = false): SessionTab {
        return SessionTab(
            sessionId = sessionId,
            fullTitle = sessionId.uppercase(),
            displayTitle = sessionId.uppercase(),
            active = active,
            closable = true,
            running = false,
        )
    }
}
