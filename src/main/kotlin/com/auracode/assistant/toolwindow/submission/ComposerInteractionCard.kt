package com.auracode.assistant.toolwindow.submission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

internal enum class ComposerCardActionEmphasisStyle {
    PRIMARY_FILL,
    SUBTLE_HIGHLIGHT,
}

internal data class ComposerCardActionChrome(
    val background: Color,
    val contentColor: Color,
    val secondaryTextColor: Color,
    val border: Color,
)

internal enum class ComposerInteractionFocusTarget {
    CARD,
    INPUT,
}

internal fun restoreComposerInteractionFocusTarget(preferInput: Boolean): ComposerInteractionFocusTarget {
    return if (preferInput) ComposerInteractionFocusTarget.INPUT else ComposerInteractionFocusTarget.CARD
}

internal fun restoreComposerInteractionFocus(
    target: ComposerInteractionFocusTarget,
    cardFocusRequester: FocusRequester,
    inputFocusRequester: FocusRequester? = null,
) {
    when (target) {
        ComposerInteractionFocusTarget.CARD -> cardFocusRequester.requestFocus()
        ComposerInteractionFocusTarget.INPUT -> (inputFocusRequester ?: cardFocusRequester).requestFocus()
    }
}

@Composable
internal fun ComposerInteractionCard(
    p: DesignPalette,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(assistantUiTokens().spacing.md),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(assistantUiTokens().spacing.sm),
    onRequestFocus: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 440.dp)
            .background(p.timelineCardBg, RoundedCornerShape(16.dp))
            .border(1.dp, p.markdownDivider.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
            // Clicking the card shell should restore keyboard focus so arrow-key navigation
            // keeps working after the user briefly focuses another control in the tool window.
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = onRequestFocus != null,
                onClick = { onRequestFocus?.invoke() },
            )
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
    ) {
        content()
    }
}

@Composable
internal fun ComposerCardPrimaryAction(
    label: String,
    p: DesignPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ComposerCardAction(
        label = label,
        emphasized = true,
        p = p,
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
internal fun ComposerCardAction(
    label: String,
    emphasized: Boolean,
    p: DesignPalette,
    modifier: Modifier = Modifier,
    description: String = "",
    showKeyboardHintIcon: Boolean = false,
    emphasisStyle: ComposerCardActionEmphasisStyle = ComposerCardActionEmphasisStyle.PRIMARY_FILL,
    enabled: Boolean = true,
    danger: Boolean = false,
    compactVerticalPadding: Dp = 10.dp,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    val chrome = composerCardActionChrome(
        emphasized = emphasized,
        emphasisStyle = emphasisStyle,
        enabled = enabled,
        danger = danger,
        p = p,
    )

    Box(
        modifier = modifier
            .background(chrome.background, RoundedCornerShape(10.dp))
            .border(1.dp, chrome.border, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = t.spacing.sm, vertical = compactVerticalPadding),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(end = if (showKeyboardHintIcon) t.controls.iconMd + t.spacing.md else 0.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (label == com.auracode.assistant.toolwindow.execution.TOOL_USER_INPUT_OTHER_OPTION) "Other" else label,
                    color = chrome.contentColor,
                    fontWeight = if (danger || emphasized) FontWeight.SemiBold else FontWeight.Medium,
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        color = chrome.secondaryTextColor,
                    )
                }
            }
            if (showKeyboardHintIcon) {
                KeyboardHintIcon(
                    tint = keyboardHintTint(
                        enabled = enabled,
                        emphasized = emphasized,
                        emphasisStyle = emphasisStyle,
                        danger = danger,
                        p = p,
                    ),
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

internal fun composerCardActionChrome(
    emphasized: Boolean,
    emphasisStyle: ComposerCardActionEmphasisStyle,
    enabled: Boolean,
    danger: Boolean,
    p: DesignPalette,
): ComposerCardActionChrome {
    if (!enabled) {
        return ComposerCardActionChrome(
            background = p.topStripBg.copy(alpha = 0.75f),
            contentColor = p.textMuted,
            secondaryTextColor = p.textMuted,
            border = p.markdownDivider.copy(alpha = 0.48f),
        )
    }
    if (danger) {
        return ComposerCardActionChrome(
            background = p.danger,
            contentColor = p.timelineCardBg,
            secondaryTextColor = p.timelineCardBg.copy(alpha = 0.82f),
            border = p.danger,
        )
    }
    if (emphasized && emphasisStyle == ComposerCardActionEmphasisStyle.SUBTLE_HIGHLIGHT) {
        return ComposerCardActionChrome(
            background = p.accent.copy(alpha = 0.10f),
            contentColor = p.textPrimary,
            secondaryTextColor = p.textSecondary,
            border = p.accent.copy(alpha = 0.42f),
        )
    }
    if (emphasized) {
        return ComposerCardActionChrome(
            background = p.accent,
            contentColor = p.timelineCardBg,
            secondaryTextColor = p.timelineCardBg.copy(alpha = 0.82f),
            border = p.accent,
        )
    }
    return ComposerCardActionChrome(
        background = p.appBg,
        contentColor = p.textPrimary,
        secondaryTextColor = p.textSecondary,
        border = p.markdownDivider.copy(alpha = 0.9f),
    )
}

@Composable
private fun KeyboardHintIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val t = assistantUiTokens()
    Icon(
        painter = painterResource("/icons/swap-vert.svg"),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(t.controls.iconMd),
    )
}

private fun keyboardHintTint(
    enabled: Boolean,
    emphasized: Boolean,
    emphasisStyle: ComposerCardActionEmphasisStyle,
    danger: Boolean,
    p: DesignPalette,
): Color {
    return when {
        !enabled -> p.textMuted
        emphasized && emphasisStyle == ComposerCardActionEmphasisStyle.SUBTLE_HIGHLIGHT -> p.accent.copy(alpha = 0.88f)
        danger || emphasized -> p.timelineCardBg.copy(alpha = 0.82f)
        else -> p.textMuted
    }
}
