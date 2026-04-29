package com.auracode.assistant.toolwindow.shared

import com.auracode.assistant.toolwindow.shell.SkillImportDialogPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantDialogStateModelTest {

    @Test
    fun `skill import progress phase should disable dismissal and show spinner`() {
        val presentation = skillImportDialogPresentation(SkillImportDialogPhase.IN_PROGRESS)

        assertEquals(AssistantDialogTone.ACCENT, presentation.tone)
        assertTrue(presentation.showsProgressIndicator)
        assertFalse(presentation.showsStatusBadge)
        assertFalse(presentation.allowsDismiss)
    }

    @Test
    fun `skill import success phase should allow dismissal without danger styling`() {
        val presentation = skillImportDialogPresentation(SkillImportDialogPhase.SUCCEEDED)

        assertEquals(AssistantDialogTone.ACCENT, presentation.tone)
        assertFalse(presentation.showsProgressIndicator)
        assertFalse(presentation.showsStatusBadge)
        assertTrue(presentation.allowsDismiss)
    }

    @Test
    fun `skill import failure phase should use danger styling`() {
        val presentation = skillImportDialogPresentation(SkillImportDialogPhase.FAILED)

        assertEquals(AssistantDialogTone.DANGER, presentation.tone)
        assertFalse(presentation.showsProgressIndicator)
        assertFalse(presentation.showsStatusBadge)
        assertTrue(presentation.allowsDismiss)
    }
}
