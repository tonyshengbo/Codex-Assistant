package com.auracode.assistant.toolwindow.submission

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.Color
import com.auracode.assistant.toolwindow.execution.ToolUserInputChoiceKind
import com.auracode.assistant.toolwindow.shared.DesignPalette
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InlineComposerInputBehaviorTest {
    @Test
    fun `inline submit is blocked while ime composition is active`() {
        val composing = TextFieldValue(
            text = "shi",
            selection = TextRange(3),
            composition = TextRange(0, 3),
        )
        val committed = TextFieldValue(
            text = "是吗",
            selection = TextRange(2),
        )

        assertFalse(canSubmitInlineTextField(composing))
        assertTrue(canSubmitInlineTextField(committed))
    }

    @Test
    fun `inline arrow navigation is enabled only when ime composition is inactive`() {
        val composing = TextFieldValue(
            text = "shi",
            selection = TextRange(3),
            composition = TextRange(0, 3),
        )
        val committed = TextFieldValue(
            text = "是吗",
            selection = TextRange(2),
        )

        assertEquals(InlineInputKeyAction.NONE, inlineInputKeyAction(composing, InlineInputKey.UP))
        assertEquals(InlineInputKeyAction.NONE, inlineInputKeyAction(composing, InlineInputKey.DOWN))
        assertEquals(InlineInputKeyAction.MOVE_PREVIOUS, inlineInputKeyAction(committed, InlineInputKey.UP))
        assertEquals(InlineInputKeyAction.MOVE_NEXT, inlineInputKeyAction(committed, InlineInputKey.DOWN))
    }

    @Test
    fun `tool user input keyboard hint is shown only for active choice`() {
        assertFalse(shouldShowToolUserInputKeyboardHint(ToolUserInputChoiceKind.FIXED, index = 0, activeChoiceIndex = 1))
        assertTrue(shouldShowToolUserInputKeyboardHint(ToolUserInputChoiceKind.FIXED, index = 1, activeChoiceIndex = 1))
        assertFalse(shouldShowToolUserInputKeyboardHint(ToolUserInputChoiceKind.FREEFORM, index = 0, activeChoiceIndex = 1))
        assertTrue(shouldShowToolUserInputKeyboardHint(ToolUserInputChoiceKind.FREEFORM, index = 1, activeChoiceIndex = 1))
    }

    @Test
    fun `subtle emphasis style uses weak accent highlight instead of filled button`() {
        val palette = testPalette()

        val weak = composerCardActionChrome(
            emphasized = true,
            emphasisStyle = ComposerCardActionEmphasisStyle.SUBTLE_HIGHLIGHT,
            enabled = true,
            danger = false,
            p = palette,
        )
        val default = composerCardActionChrome(
            emphasized = true,
            emphasisStyle = ComposerCardActionEmphasisStyle.PRIMARY_FILL,
            enabled = true,
            danger = false,
            p = palette,
        )

        assertEquals(palette.accent.copy(alpha = 0.10f), weak.background)
        assertEquals(palette.accent.copy(alpha = 0.42f), weak.border)
        assertEquals(palette.textPrimary, weak.contentColor)
        assertEquals(palette.accent, default.background)
    }

    @Test
    fun `plan completion keyboard hint is shown only for selected action`() {
        assertFalse(shouldShowPlanCompletionKeyboardHint(PlanCompletionAction.EXECUTE, PlanCompletionAction.CANCEL))
        assertFalse(shouldShowPlanCompletionKeyboardHint(PlanCompletionAction.CANCEL, PlanCompletionAction.EXECUTE))
        assertTrue(shouldShowPlanCompletionKeyboardHint(PlanCompletionAction.EXECUTE, PlanCompletionAction.EXECUTE))
        assertTrue(shouldShowPlanCompletionKeyboardHint(PlanCompletionAction.CANCEL, PlanCompletionAction.CANCEL))
        assertFalse(shouldShowPlanCompletionKeyboardHint(PlanCompletionAction.REVISION, PlanCompletionAction.EXECUTE))
        assertTrue(shouldShowPlanCompletionKeyboardHint(PlanCompletionAction.REVISION, PlanCompletionAction.REVISION))
    }

    @Test
    fun `plan completion actions use subtle highlight emphasis`() {
        assertEquals(
            ComposerCardActionEmphasisStyle.SUBTLE_HIGHLIGHT,
            planCompletionActionEmphasisStyle(),
        )
    }

    @Test
    fun `composer interaction click focus prefers active input target`() {
        assertEquals(
            ComposerInteractionFocusTarget.CARD,
            restoreComposerInteractionFocusTarget(preferInput = false),
        )
        assertEquals(
            ComposerInteractionFocusTarget.INPUT,
            restoreComposerInteractionFocusTarget(preferInput = true),
        )
    }

    @Test
    fun `plan completion focus target switches to input only for revision`() {
        assertEquals(
            ComposerInteractionFocusTarget.CARD,
            planCompletionInteractionFocusTarget(PlanCompletionAction.EXECUTE),
        )
        assertEquals(
            ComposerInteractionFocusTarget.CARD,
            planCompletionInteractionFocusTarget(PlanCompletionAction.CANCEL),
        )
        assertEquals(
            ComposerInteractionFocusTarget.INPUT,
            planCompletionInteractionFocusTarget(PlanCompletionAction.REVISION),
        )
    }

    private fun testPalette(): DesignPalette {
        return DesignPalette(
            appBg = Color(0xFFF5F7FB),
            topBarBg = Color(0xFFECEFF6),
            topStripBg = Color(0xFFE6EAF3),
            textPrimary = Color(0xFF182030),
            textSecondary = Color(0xFF4B5870),
            textMuted = Color(0xFF68758F),
            timelineCardBg = Color(0xFFFFFFFF),
            timelineCardText = Color(0xFF22304A),
            timelinePlainText = Color(0xFF263248),
            userBubbleBg = Color(0xFFDCEAFF),
            markdownCodeBg = Color(0xFFF2F6FF),
            markdownInlineCodeBg = Color(0xFFEAF1FF),
            markdownCodeText = Color(0xFF21406E),
            markdownQuoteText = Color(0xFF52607A),
            markdownDivider = Color(0xFFD4DCEB),
            markdownTableBg = Color(0xFFF8FAFF),
            linkColor = Color(0xFF2E6BDE),
            composerBg = Color(0xFFF0F3FA),
            accent = Color(0xFF2E6BDE),
            success = Color(0xFF3DAA55),
            danger = Color(0xFFD74D58),
        )
    }
}
