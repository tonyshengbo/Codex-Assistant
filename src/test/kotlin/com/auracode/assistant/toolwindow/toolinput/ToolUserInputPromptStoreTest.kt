package com.auracode.assistant.toolwindow.execution

import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.eventing.UiIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolUserInputPromptStoreTest {
    @Test
    fun `tool user input prompts are queued and current draft starts empty`() {
        val store = ToolUserInputPromptStore()

        store.onEvent(AppEvent.ToolUserInputRequested(prompt("req-1", "Question one")))
        store.onEvent(AppEvent.ToolUserInputRequested(prompt("req-2", "Question two")))

        assertTrue(store.state.value.visible)
        assertEquals("req-1", store.state.value.current?.requestId)
        assertEquals(2, store.state.value.queue.size)
        assertEquals(0, store.state.value.activeChoiceIndex)
        assertFalse(store.state.value.freeformActive)
        assertEquals("", store.state.value.answerDrafts.getValue("builder_demo_target").textValue)
    }

    @Test
    fun `selecting option and resolving prompt advances queue`() {
        val store = ToolUserInputPromptStore()
        store.onEvent(AppEvent.ToolUserInputRequested(prompt("req-1", "Question one")))
        store.onEvent(AppEvent.ToolUserInputRequested(prompt("req-2", "Question two")))

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.SelectToolUserInputOption(
                    questionId = "builder_demo_target",
                    optionLabel = "Reuse existing demo",
                ),
            ),
        )
        assertTrue(store.state.value.canSubmit)

        store.onEvent(AppEvent.ToolUserInputResolved("req-1"))

        assertEquals("req-2", store.state.value.current?.requestId)
        assertEquals(1, store.state.value.queue.size)
        assertEquals("", store.state.value.answerDrafts.getValue("builder_demo_target").textValue)
        assertEquals(0, store.state.value.activeQuestionIndex)
    }

    @Test
    fun `multi question prompt advances one page at a time`() {
        val store = ToolUserInputPromptStore()
        store.onEvent(AppEvent.ToolUserInputRequested(multiQuestionPrompt()))

        assertEquals(0, store.state.value.activeQuestionIndex)
        assertEquals("target", store.state.value.activeQuestion?.id)
        assertFalse(store.state.value.canSubmit)

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.SelectToolUserInputOption(
                    questionId = "target",
                    optionLabel = "Reuse existing demo",
                ),
            ),
        )
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.AdvanceToolUserInputPrompt))

        assertEquals(1, store.state.value.activeQuestionIndex)
        assertEquals("notes", store.state.value.activeQuestion?.id)
        assertFalse(store.state.value.canSubmit)

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.EditToolUserInputAnswer(
                    questionId = "notes",
                    value = "Ship this incrementally",
                ),
            ),
        )

        assertTrue(store.state.value.canSubmit)
    }

    @Test
    fun `keyboard selection moves highlight before committing answer`() {
        val store = ToolUserInputPromptStore()
        store.onEvent(AppEvent.ToolUserInputRequested(otherLikePrompt()))

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.MoveToolUserInputSelectionNext))

        assertEquals(1, store.state.value.activeChoiceIndex)
        assertEquals(null, store.state.value.answerDrafts.getValue("pattern_meaning").selectedOptionLabel)
        assertFalse(store.state.value.freeformActive)
    }

    @Test
    fun `moving highlight onto other like option immediately enters freeform mode`() {
        val store = ToolUserInputPromptStore()
        store.onEvent(AppEvent.ToolUserInputRequested(otherLikePrompt()))

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.MoveToolUserInputSelectionNext))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.MoveToolUserInputSelectionNext))

        assertTrue(store.state.value.freeformActive)
        assertEquals(2, store.state.value.activeChoiceIndex)
        assertEquals(null, store.state.value.answerDrafts.getValue("pattern_meaning").selectedOptionLabel)
        assertFalse(store.state.value.canSubmit)
    }

    @Test
    fun `typing into third option can advance without explicit option selection`() {
        val store = ToolUserInputPromptStore()
        store.onEvent(AppEvent.ToolUserInputRequested(twoStepOtherLikePrompt()))

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.MoveToolUserInputSelectionNext))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.MoveToolUserInputSelectionNext))
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.EditToolUserInputAnswer(
                    questionId = "pattern_meaning",
                    value = "我要的是策略模式 demo",
                ),
            ),
        )

        assertTrue(store.state.value.canAdvance)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.AdvanceToolUserInputPrompt))

        assertEquals(1, store.state.value.activeQuestionIndex)
        assertEquals("scope", store.state.value.activeQuestion?.id)
    }

    @Test
    fun `leaving other like option keeps typed text and exits freeform mode`() {
        val store = ToolUserInputPromptStore()
        store.onEvent(AppEvent.ToolUserInputRequested(otherLikePrompt()))

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.MoveToolUserInputSelectionNext))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.MoveToolUserInputSelectionNext))
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.EditToolUserInputAnswer(
                    questionId = "pattern_meaning",
                    value = "我要的是策略模式 demo",
                ),
            ),
        )
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.MoveToolUserInputSelectionPrevious))

        assertFalse(store.state.value.freeformActive)
        assertEquals(1, store.state.value.activeChoiceIndex)
        assertEquals("我要的是策略模式 demo", store.state.value.answerDrafts.getValue("pattern_meaning").textValue)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.MoveToolUserInputSelectionNext))

        assertTrue(store.state.value.freeformActive)
        assertEquals(2, store.state.value.activeChoiceIndex)
        assertEquals("我要的是策略模式 demo", store.state.value.answerDrafts.getValue("pattern_meaning").textValue)
    }

    @Test
    fun `choosing other like option enters freeform mode without synthetic other option`() {
        val store = ToolUserInputPromptStore()
        store.onEvent(AppEvent.ToolUserInputRequested(otherLikePrompt()))

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.SelectToolUserInputOption(
                    questionId = "pattern_meaning",
                    optionLabel = "别的模式",
                ),
            ),
        )

        assertTrue(store.state.value.freeformActive)
        assertEquals(2, store.state.value.activeChoiceIndex)
        assertFalse(store.state.value.canSubmit)
        assertEquals(
            listOf("工厂模式 (Recommended)", "模板工程项目", "别的模式"),
            store.state.value.activeQuestion?.presentedChoices()?.map { it.label },
        )
    }

    private fun prompt(requestId: String, question: String): ToolUserInputPromptUiModel {
        return ToolUserInputPromptUiModel(
            requestId = requestId,
            threadId = "thread-1",
            turnId = "turn-1",
            itemId = "call-1",
            questions = listOf(
                ToolUserInputQuestionUiModel(
                    id = "builder_demo_target",
                    header = "Target",
                    question = question,
                    options = listOf(
                        ToolUserInputOptionUiModel(
                            label = "Reuse existing demo",
                            description = "Keep the current file and refine it",
                        ),
                    ),
                    isOther = true,
                    isSecret = false,
                ),
            ),
        )
    }

    private fun multiQuestionPrompt(): ToolUserInputPromptUiModel {
        return ToolUserInputPromptUiModel(
            requestId = "req-1",
            threadId = "thread-1",
            turnId = "turn-1",
            itemId = "call-1",
            questions = listOf(
                ToolUserInputQuestionUiModel(
                    id = "target",
                    header = "Target",
                    question = "Question one",
                    options = listOf(
                        ToolUserInputOptionUiModel(
                            label = "Reuse existing demo",
                            description = "Keep the current file and refine it",
                        ),
                    ),
                ),
                ToolUserInputQuestionUiModel(
                    id = "notes",
                    header = "Notes",
                    question = "Question two",
                    options = emptyList(),
                ),
            ),
        )
    }

    private fun otherLikePrompt(): ToolUserInputPromptUiModel {
        return ToolUserInputPromptUiModel(
            requestId = "req-3",
            threadId = "thread-1",
            turnId = "turn-1",
            itemId = "call-3",
            questions = listOf(
                ToolUserInputQuestionUiModel(
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
                ),
            ),
        )
    }

    private fun twoStepOtherLikePrompt(): ToolUserInputPromptUiModel {
        return ToolUserInputPromptUiModel(
            requestId = "req-4",
            threadId = "thread-1",
            turnId = "turn-1",
            itemId = "call-4",
            questions = listOf(
                otherLikePrompt().questions.first(),
                ToolUserInputQuestionUiModel(
                    id = "scope",
                    header = "范围",
                    question = "这是 demo 还是完整项目？",
                    options = listOf(
                        ToolUserInputOptionUiModel(
                            label = "Demo",
                            description = "",
                        ),
                    ),
                ),
            ),
        )
    }
}
