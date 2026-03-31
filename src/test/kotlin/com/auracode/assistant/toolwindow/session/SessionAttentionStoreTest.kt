package com.auracode.assistant.toolwindow.session

import com.auracode.assistant.protocol.TurnOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionAttentionStoreTest {
    @Test
    fun `marking a session completion stores unread state`() {
        val store = SessionAttentionStore()

        assertTrue(store.markCompleted(sessionId = "s1", outcome = TurnOutcome.SUCCESS, completedAt = 123L))

        val snapshot = store.snapshot("s1")
        assertTrue(snapshot.hasUnreadCompletion)
        assertEquals(TurnOutcome.SUCCESS, snapshot.lastOutcome)
        assertEquals(123L, snapshot.lastCompletedAt)
    }

    @Test
    fun `clearing one session does not affect another session`() {
        val store = SessionAttentionStore()
        store.markCompleted(sessionId = "s1", outcome = TurnOutcome.SUCCESS, completedAt = 1L)
        store.markCompleted(sessionId = "s2", outcome = TurnOutcome.FAILED, completedAt = 2L)

        assertTrue(store.clear("s1"))
        assertFalse(store.snapshot("s1").hasUnreadCompletion)
        assertTrue(store.snapshot("s2").hasUnreadCompletion)
        assertEquals(TurnOutcome.FAILED, store.snapshot("s2").lastOutcome)
    }

    @Test
    fun `dropping a session removes its attention state`() {
        val store = SessionAttentionStore()
        store.markCompleted(sessionId = "s1", outcome = TurnOutcome.SUCCESS, completedAt = 1L)

        assertTrue(store.drop("s1"))
        assertFalse(store.snapshot("s1").hasUnreadCompletion)
        assertNull(store.snapshot("s1").lastOutcome)
        assertNull(store.snapshot("s1").lastCompletedAt)
    }
}
