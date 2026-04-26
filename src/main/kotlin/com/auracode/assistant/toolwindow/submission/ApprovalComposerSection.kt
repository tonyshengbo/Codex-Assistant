package com.auracode.assistant.toolwindow.submission

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.protocol.UnifiedApprovalRequestKind
import com.auracode.assistant.toolwindow.execution.ApprovalAction
import com.auracode.assistant.toolwindow.execution.ApprovalAreaState
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantMonospaceStyle
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun ApprovalComposerSection(
    p: DesignPalette,
    state: ApprovalAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val current = state.current ?: return
    val t = assistantUiTokens()
    val focusRequester = FocusRequester()
    val visibleActions = ApprovalAction.entries
        .filter { it != ApprovalAction.ALLOW_FOR_SESSION || current.allowForSession }

    LaunchedEffect(current.requestId) {
        withFrameNanos { }
        focusRequester.requestFocus()
    }

    ComposerInteractionCard(
        p = p,
        contentPadding = PaddingValues(
            horizontal = t.spacing.md,
            vertical = t.spacing.sm,
        ),
        verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
        onRequestFocus = {
            // Approval cards always navigate at the card level, so a card refocus is sufficient.
            focusRequester.requestFocus()
        },
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight,
                    Key.DirectionDown,
                    -> {
                        if (visibleActions.size > 1) {
                            onIntent(UiIntent.MoveApprovalActionNext)
                            true
                        } else {
                            false
                        }
                    }

                    Key.DirectionLeft,
                    Key.DirectionUp,
                    -> {
                        if (visibleActions.size > 1) {
                            onIntent(UiIntent.MoveApprovalActionPrevious)
                            true
                        } else {
                            false
                        }
                    }

                    Key.Enter,
                    Key.NumPadEnter,
                    -> {
                        onIntent(UiIntent.SubmitApprovalAction())
                        true
                    }

                    Key.Escape -> {
                        onIntent(UiIntent.SubmitApprovalAction(ApprovalAction.REJECT))
                        true
                    }

                    else -> false
                }
            },
    ) {
        Text(
            text = approvalBadgeLabel(current.kind) +
                if (current.queueSize > 1) " ${current.queuePosition}/${current.queueSize}" else "",
            color = p.textSecondary,
        )
        Text(
            text = current.title,
            color = p.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = current.body,
            color = p.textSecondary,
        )
        if (!current.command.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(p.markdownCodeBg, RoundedCornerShape(12.dp))
                    .padding(t.spacing.sm),
            ) {
                Text(
                    text = current.command,
                    color = p.markdownCodeText,
                    style = assistantMonospaceStyle(),
                )
            }
        }
        if (!current.cwd.isNullOrBlank()) {
            Text(
                text = "${approvalCwdLabel()}: ${current.cwd}",
                color = p.textMuted,
                style = assistantMonospaceStyle(),
            )
        }
        if (current.permissions.isNotEmpty()) {
            Text(
                text = current.permissions.joinToString("\n"),
                color = p.textSecondary,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
        ) {
            visibleActions.forEach { action ->
                val selected = state.selectedAction == action
                ComposerCardAction(
                    label = action.label(),
                    emphasized = selected && action != ApprovalAction.REJECT,
                    danger = selected && action == ApprovalAction.REJECT,
                    p = p,
                    modifier = Modifier.fillMaxWidth(),
                    compactVerticalPadding = 8.dp,
                    onClick = {
                        onIntent(UiIntent.SelectApprovalAction(action))
                        onIntent(UiIntent.SubmitApprovalAction(action))
                    },
                )
            }
        }
        Text(
            text = approvalFooterText(),
            color = p.textMuted,
        )
    }
}

/**
 * Maps approval request kinds to localized badge labels.
 */
private fun approvalBadgeLabel(kind: UnifiedApprovalRequestKind): String {
    return when (kind) {
        UnifiedApprovalRequestKind.COMMAND -> AuraCodeBundle.message("composer.approval.badge.command")
        UnifiedApprovalRequestKind.FILE_CHANGE -> AuraCodeBundle.message("composer.approval.badge.fileChange")
        UnifiedApprovalRequestKind.PERMISSIONS -> AuraCodeBundle.message("composer.approval.badge.permissions")
    }
}

/**
 * Maps approval actions to localized action labels.
 */
private fun ApprovalAction.label(): String {
    return when (this) {
        ApprovalAction.ALLOW -> AuraCodeBundle.message("composer.approval.action.allow")
        ApprovalAction.REJECT -> AuraCodeBundle.message("composer.approval.action.reject")
        ApprovalAction.ALLOW_FOR_SESSION -> AuraCodeBundle.message("composer.approval.action.remember")
    }
}

/**
 * Returns the localized footer hint for the approval card.
 */
private fun approvalFooterText(): String = AuraCodeBundle.message("composer.approval.footer")

/**
 * Returns the localized label for the current working directory field.
 */
private fun approvalCwdLabel(): String = AuraCodeBundle.message("composer.approval.cwd")
