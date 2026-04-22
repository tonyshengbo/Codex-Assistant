package com.auracode.assistant.toolwindow.toolinput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolUserInputPromptModelsTest {
    @Test
    fun `fixed choice shows inline description only when focused`() {
        val fixedChoice = ToolUserInputChoiceUiModel(
            label = "工厂模式 (Recommended)",
            description = "最常见，Factory/Abstract Factory 一类 demo",
            kind = ToolUserInputChoiceKind.FIXED,
        )
        val freeformChoice = ToolUserInputChoiceUiModel(
            label = "别的模式",
            description = "不是上面两种，我会按你的补充描述来规划",
            kind = ToolUserInputChoiceKind.FREEFORM,
        )

        assertEquals("最常见，Factory/Abstract Factory 一类 demo", fixedChoice.inlineDescription(isFocused = true))
        assertEquals("", fixedChoice.inlineDescription(isFocused = false))
        assertEquals("", freeformChoice.inlineDescription(isFocused = true))
    }

    @Test
    fun `semantic other option is treated as freeform answer source`() {
        val question = ToolUserInputQuestionUiModel(
            id = "pattern_meaning",
            header = "模式含义",
            question = "你说的“工程模式”具体是指哪一种？",
            options = listOf(
                ToolUserInputOptionUiModel(
                    label = "工厂模式 (Recommended)",
                    description = "最常见，Factory/Abstract Factory 一类 demo",
                ),
                ToolUserInputOptionUiModel(
                    label = "模板工程项目",
                    description = "想要一个可运行的小工程骨架，而不是设计模式 demo",
                ),
                ToolUserInputOptionUiModel(
                    label = "别的模式",
                    description = "不是上面两种，我会按你的补充描述来规划",
                ),
            ),
            isOther = true,
        )

        assertEquals(
            listOf("工厂模式 (Recommended)", "模板工程项目", "别的模式"),
            question.presentedChoices().map { it.label },
        )
        assertTrue(question.optionRequiresText("别的模式"))
        assertEquals(
            listOf("我要的是策略模式 demo"),
            ToolUserInputAnswerDraftUiModel(
                selectedOptionLabel = "别的模式",
                textValue = "我要的是策略模式 demo",
            ).toAnswers(question),
        )
    }

    @Test
    fun `sync selection derives freeform mode from highlighted other like choice`() {
        val question = ToolUserInputQuestionUiModel(
            id = "pattern_meaning",
            header = "模式含义",
            question = "你说的“工程模式”具体是指哪一种？",
            options = listOf(
                ToolUserInputOptionUiModel(
                    label = "工厂模式 (Recommended)",
                    description = "最常见，Factory/Abstract Factory 一类 demo",
                ),
                ToolUserInputOptionUiModel(
                    label = "模板工程项目",
                    description = "想要一个可运行的小工程骨架，而不是设计模式 demo",
                ),
                ToolUserInputOptionUiModel(
                    label = "别的模式",
                    description = "不是上面两种，我会按你的补充描述来规划",
                ),
            ),
            isOther = true,
        )

        val inactive = ToolUserInputPromptState(
            current = ToolUserInputPromptUiModel(
                requestId = "req-1",
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "item-1",
                questions = listOf(question),
            ),
            answerDrafts = mapOf(question.id to ToolUserInputAnswerDraftUiModel()),
            activeChoiceIndex = 1,
        ).syncSelection()

        val active = inactive.copy(activeChoiceIndex = 2).syncSelection()

        assertFalse(inactive.freeformActive)
        assertTrue(active.freeformActive)
    }
}
