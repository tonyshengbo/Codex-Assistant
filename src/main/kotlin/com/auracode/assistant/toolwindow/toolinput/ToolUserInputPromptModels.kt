package com.auracode.assistant.toolwindow.toolinput

import com.auracode.assistant.protocol.UnifiedToolUserInputAnswerDraft
import com.auracode.assistant.protocol.UnifiedToolUserInputPrompt

internal const val TOOL_USER_INPUT_OTHER_OPTION: String = "__tool_user_input_other__"

internal data class ToolUserInputOptionUiModel(
    val label: String,
    val description: String,
)

internal enum class ToolUserInputChoiceKind {
    FIXED,
    FREEFORM,
}

internal data class ToolUserInputChoiceUiModel(
    val label: String,
    val description: String,
    val kind: ToolUserInputChoiceKind,
)

internal fun ToolUserInputChoiceUiModel.inlineDescription(isFocused: Boolean): String {
    return if (kind == ToolUserInputChoiceKind.FIXED && isFocused) description else ""
}

internal data class ToolUserInputQuestionUiModel(
    val id: String,
    val header: String,
    val question: String,
    val options: List<ToolUserInputOptionUiModel> = emptyList(),
    val isOther: Boolean = false,
    val isSecret: Boolean = false,
)

internal data class ToolUserInputPromptUiModel(
    val requestId: String,
    val threadId: String,
    val turnId: String?,
    val itemId: String,
    val questions: List<ToolUserInputQuestionUiModel>,
    val queuePosition: Int = 1,
    val queueSize: Int = 1,
)

internal data class ToolUserInputAnswerDraftUiModel(
    val selectedOptionLabel: String? = null,
    val textValue: String = "",
)

internal data class ToolUserInputPromptState(
    val queue: List<ToolUserInputPromptUiModel> = emptyList(),
    val current: ToolUserInputPromptUiModel? = null,
    val answerDrafts: Map<String, ToolUserInputAnswerDraftUiModel> = emptyMap(),
    val activeQuestionIndex: Int = 0,
    val activeChoiceIndex: Int = 0,
    val freeformActive: Boolean = false,
    val visible: Boolean = false,
    val canSubmit: Boolean = false,
) {
    val activeQuestion: ToolUserInputQuestionUiModel?
        get() = current?.questions?.getOrNull(activeQuestionIndex)

    val canAdvance: Boolean
        get() = activeQuestion?.let { question ->
            answerDrafts[question.id].orEmpty().toAnswers(question).isNotEmpty()
        } == true && !isLastQuestion

    val canRetreat: Boolean
        get() = activeQuestionIndex > 0

    val isLastQuestion: Boolean
        get() = current?.questions?.lastIndex == activeQuestionIndex

    val activeChoice: ToolUserInputChoiceUiModel?
        get() = activeQuestion?.presentedChoices()?.getOrNull(activeChoiceIndex)
}

internal fun ToolUserInputPromptState.toSubmissionAnswers(): Map<String, UnifiedToolUserInputAnswerDraft> {
    val prompt = current ?: return emptyMap()
    return prompt.questions.mapNotNull { question ->
        val draft = answerDrafts[question.id] ?: ToolUserInputAnswerDraftUiModel()
        val answers = draft.toAnswers(question)
        answers.takeIf { it.isNotEmpty() }?.let { question.id to UnifiedToolUserInputAnswerDraft(it) }
    }.toMap()
}

internal fun ToolUserInputPromptUiModel.emptyDrafts(): Map<String, ToolUserInputAnswerDraftUiModel> {
    return questions.associate { question ->
        question.id to ToolUserInputAnswerDraftUiModel()
    }
}

internal fun ToolUserInputPromptState.recomputeCanSubmit(): ToolUserInputPromptState {
    val prompt = current
    val canSubmitValue = prompt != null && prompt.questions.all { question ->
        answerDrafts[question.id].orEmpty().toAnswers(question).isNotEmpty()
    }
    return copy(canSubmit = canSubmitValue)
}

internal fun ToolUserInputQuestionUiModel.presentedChoices(): List<ToolUserInputChoiceUiModel> {
    val explicitOtherLabels = explicitOtherLikeLabels()
    val fixedChoices = options.map { option ->
        ToolUserInputChoiceUiModel(
            label = option.label,
            description = option.description,
            kind = if (option.label in explicitOtherLabels) {
                ToolUserInputChoiceKind.FREEFORM
            } else {
                ToolUserInputChoiceKind.FIXED
            },
        )
    }
    if (!isOther || explicitOtherLabels.isNotEmpty()) return fixedChoices
    return fixedChoices + ToolUserInputChoiceUiModel(
        label = TOOL_USER_INPUT_OTHER_OPTION,
        description = "",
        kind = ToolUserInputChoiceKind.FREEFORM,
    )
}

internal fun ToolUserInputQuestionUiModel.optionRequiresText(label: String?): Boolean {
    if (label.isNullOrBlank()) return options.isEmpty()
    return when {
        options.isEmpty() -> true
        label == TOOL_USER_INPUT_OTHER_OPTION -> true
        label in explicitOtherLikeLabels() -> isOther
        else -> false
    }
}

internal fun ToolUserInputPromptState.syncSelection(): ToolUserInputPromptState {
    val question = activeQuestion ?: return copy(
        activeChoiceIndex = 0,
        freeformActive = false,
    )
    val choices = question.presentedChoices()
    if (question.options.isEmpty()) {
        return copy(
            activeChoiceIndex = 0,
            freeformActive = true,
        ).recomputeCanSubmit()
    }
    val selectedLabel = answerDrafts[question.id]?.selectedOptionLabel
    val selectedIndex = choices.indexOfFirst { it.label == selectedLabel }
    val safeIndex = when {
        activeChoiceIndex in choices.indices -> activeChoiceIndex
        selectedIndex >= 0 -> selectedIndex
        else -> 0
    }
    return copy(
        activeChoiceIndex = safeIndex,
        freeformActive = choices.getOrNull(safeIndex)?.kind == ToolUserInputChoiceKind.FREEFORM,
    ).recomputeCanSubmit()
}

internal fun UnifiedToolUserInputPrompt.toUiModel(): ToolUserInputPromptUiModel {
    return ToolUserInputPromptUiModel(
        requestId = requestId,
        threadId = threadId,
        turnId = turnId,
        itemId = itemId,
        questions = questions.map { question ->
            ToolUserInputQuestionUiModel(
                id = question.id,
                header = question.header,
                question = question.question,
                options = question.options.map { option ->
                    ToolUserInputOptionUiModel(
                        label = option.label,
                        description = option.description,
                    )
                },
                isOther = question.isOther,
                isSecret = question.isSecret,
            )
        },
    )
}

private fun ToolUserInputAnswerDraftUiModel?.orEmpty(): ToolUserInputAnswerDraftUiModel {
    return this ?: ToolUserInputAnswerDraftUiModel()
}

internal fun ToolUserInputAnswerDraftUiModel.toAnswers(
    question: ToolUserInputQuestionUiModel,
): List<String> {
    return when {
        question.options.isEmpty() -> textValue.trim().takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
        question.optionRequiresText(selectedOptionLabel) ->
            textValue.trim().takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()

        !selectedOptionLabel.isNullOrBlank() -> listOf(selectedOptionLabel)
        else -> emptyList()
    }
}

private fun ToolUserInputQuestionUiModel.explicitOtherLikeLabels(): Set<String> {
    if (!isOther) return emptySet()
    return options.mapNotNull { option ->
        option.label.takeIf { label ->
            OTHER_LIKE_LABEL_PATTERNS.any { pattern -> pattern.containsMatchIn(label) } ||
                OTHER_LIKE_DESC_PATTERNS.any { pattern -> pattern.containsMatchIn(option.description) }
        }
    }.toSet()
}

private val OTHER_LIKE_LABEL_PATTERNS = listOf(
    Regex("\\bother\\b", RegexOption.IGNORE_CASE),
    Regex("其他"),
    Regex("别的"),
    Regex("自定义"),
)

private val OTHER_LIKE_DESC_PATTERNS = listOf(
    Regex("\\bcustom\\b", RegexOption.IGNORE_CASE),
    Regex("\\bother\\b", RegexOption.IGNORE_CASE),
    Regex("补充描述"),
    Regex("不是上面"),
    Regex("自定义"),
)
