package com.codex.assistant.toolwindow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovalUiPolicyTest {
    @Test
    fun `command proposals always execute without approval`() {
        val policy = ApprovalUiPolicy()

        assertTrue(policy.shouldExecuteCommandProposal())
    }

    @Test
    fun `diff proposals always apply without approval`() {
        val policy = ApprovalUiPolicy()

        assertTrue(policy.shouldApplyDiffProposal())
    }

    @Test
    fun `approval chip remains as placeholder ui only`() {
        val policy = ApprovalUiPolicy()

        assertEquals("Auto", policy.chipLabel)
        assertFalse(policy.isInteractive)
    }
}
