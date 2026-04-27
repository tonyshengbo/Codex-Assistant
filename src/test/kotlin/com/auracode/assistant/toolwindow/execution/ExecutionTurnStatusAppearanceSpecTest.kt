package com.auracode.assistant.toolwindow.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecutionTurnStatusAppearanceSpecTest {
    @Test
    fun `running status uses emphasized visual scale`() {
        val spec = executionTurnStatusAppearanceSpec()

        assertEquals(32, spec.minHeight.value.toInt())
        assertEquals(16, spec.indicatorSize.value.toInt())
        assertEquals(13, spec.labelFontSize.value.toInt())
        assertEquals(11, spec.elapsedFontSize.value.toInt())
        assertTrue(spec.containerAlpha > 0.95f)
        assertTrue(spec.elapsedChipAlpha > 0.08f)
    }
}
