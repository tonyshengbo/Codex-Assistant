package com.auracode.assistant.toolwindow.submission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantBodyTextStyle
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

internal const val INLINE_INPUT_MAX_LINES: Int = 4

internal enum class InlineInputKey {
    UP,
    DOWN,
}

internal enum class InlineInputKeyAction {
    NONE,
    MOVE_PREVIOUS,
    MOVE_NEXT,
}

internal fun canSubmitInlineTextField(value: TextFieldValue): Boolean = value.composition == null

internal fun inlineInputKeyAction(
    value: TextFieldValue,
    key: InlineInputKey,
): InlineInputKeyAction {
    if (value.composition != null) return InlineInputKeyAction.NONE
    return when (key) {
        InlineInputKey.UP -> InlineInputKeyAction.MOVE_PREVIOUS
        InlineInputKey.DOWN -> InlineInputKeyAction.MOVE_NEXT
    }
}

internal fun shouldShowToolUserInputKeyboardHint(
    kind: com.auracode.assistant.toolwindow.execution.ToolUserInputChoiceKind,
    index: Int,
    activeChoiceIndex: Int,
): Boolean {
    return index == activeChoiceIndex
}

internal fun shouldShowPlanCompletionKeyboardHint(
    action: PlanCompletionAction,
    selectedAction: PlanCompletionAction,
): Boolean {
    return action == selectedAction
}

@Composable
internal fun rememberInlineTextFieldValue(
    identityKey: Any,
    text: String,
): MutableState<TextFieldValue> {
    val state = remember(identityKey) {
        mutableStateOf(
            TextFieldValue(
                text = text,
                selection = TextRange(text.length),
            ),
        )
    }
    LaunchedEffect(identityKey, text) {
        if (state.value.text != text && state.value.composition == null) {
            state.value = TextFieldValue(
                text = text,
                selection = TextRange(text.length),
            )
        }
    }
    return state
}

@Composable
internal fun InlineSubmissionInputChoice(
    value: TextFieldValue,
    placeholder: String,
    emphasized: Boolean,
    isSecret: Boolean,
    p: DesignPalette,
    modifier: Modifier = Modifier,
    showKeyboardHintIcon: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onValueChange: (TextFieldValue) -> Unit,
    onMovePrevious: (() -> Boolean)? = null,
    onMoveNext: (() -> Boolean)? = null,
    onSubmit: () -> Boolean,
    onCancel: () -> Boolean,
) {
    val t = assistantUiTokens()
    val borderColor = if (emphasized) p.accent else p.markdownDivider.copy(alpha = 0.9f)
    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    Column(
        modifier = modifier
            .background(p.appBg, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = t.spacing.sm, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(focusModifier)
                    .onFocusChanged { onFocusChanged?.invoke(it.isFocused) }
                    .padding(end = if (showKeyboardHintIcon) t.controls.iconMd + t.spacing.sm else 0.dp)
                    .padding(vertical = 2.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> when (inlineInputKeyAction(value, InlineInputKey.UP)) {
                                InlineInputKeyAction.MOVE_PREVIOUS -> onMovePrevious?.invoke() ?: false
                                else -> false
                            }

                            Key.DirectionDown -> when (inlineInputKeyAction(value, InlineInputKey.DOWN)) {
                                InlineInputKeyAction.MOVE_NEXT -> onMoveNext?.invoke() ?: false
                                else -> false
                            }

                            Key.Enter,
                            Key.NumPadEnter,
                            -> if (canSubmitInlineTextField(value)) {
                                onSubmit()
                            } else {
                                false
                            }

                            Key.Escape -> onCancel()
                            else -> false
                        }
                    },
                minLines = 1,
                maxLines = INLINE_INPUT_MAX_LINES,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                textStyle = assistantBodyTextStyle(t).copy(color = p.textPrimary),
                cursorBrush = SolidColor(p.accent),
                visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                decorationBox = { innerTextField ->
                    if (value.text.isBlank()) {
                        Text(
                            text = placeholder,
                            color = p.textMuted,
                        )
                    }
                    innerTextField()
                },
            )
            if (showKeyboardHintIcon) {
                Icon(
                    painter = painterResource("/icons/swap-vert.svg"),
                    contentDescription = null,
                    tint = p.textMuted,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(t.controls.iconMd),
                )
            }
        }
    }
}
