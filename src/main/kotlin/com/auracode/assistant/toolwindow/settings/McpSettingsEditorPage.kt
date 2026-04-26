package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.settings.mcp.validate
import com.auracode.assistant.toolwindow.shell.RightDrawerAreaState
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun McpSettingsEditorPage(
    p: DesignPalette,
    state: RightDrawerAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val draft = state.mcpDraft
    val configInputState = rememberSettingsTextInputState(draft.configJson)
    val validation = state.mcpValidationErrors.takeIf { it.hasAny() } ?: draft.validate()
    val canSave = !validation.hasAny() && !state.mcpBusyState.saving

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = t.spacing.xl + t.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
        ) {
            SettingsField(
                p = p,
                title = AuraCodeBundle.message("settings.mcp.json.label"),
                description = AuraCodeBundle.message("settings.mcp.json.hint"),
            ) {
                SettingsTextInput(
                    p = p,
                    value = configInputState.value,
                    onValueChange = {
                        configInputState.value = it
                        onIntent(UiIntent.EditMcpDraftJson(it.text))
                    },
                    singleLine = false,
                    minLines = 14,
                    maxLines = 24,
                )
                FieldErrorText(p = p, error = validation.json)
                if (draft.usesLegacyConfigShape()) {
                    Spacer(Modifier.height(t.spacing.sm))
                    Text(
                        text = AuraCodeBundle.message("settings.mcp.json.legacyHint"),
                        color = p.textMuted,
                        style = MaterialTheme.typography.caption,
                    )
                }
            }

            state.mcpFeedbackMessage?.takeIf { it.isNotBlank() }?.let { message ->
                SettingsField(
                    p = p,
                    title = AuraCodeBundle.message("settings.mcp.feedback.label"),
                    description = AuraCodeBundle.message("settings.mcp.feedback.hint"),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(t.spacing.sm))
                            .background(
                                if (state.mcpFeedbackIsError) {
                                    p.danger.copy(alpha = 0.12f)
                                } else {
                                    p.accent.copy(alpha = 0.12f)
                                },
                            )
                            .border(
                                1.dp,
                                if (state.mcpFeedbackIsError) {
                                    p.danger.copy(alpha = 0.45f)
                                } else {
                                    p.accent.copy(alpha = 0.45f)
                                },
                                RoundedCornerShape(t.spacing.sm),
                            )
                            .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
                    ) {
                        Text(
                            text = message,
                            color = if (state.mcpFeedbackIsError) p.danger else p.textPrimary,
                            style = MaterialTheme.typography.body2,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(p.topBarBg.copy(alpha = 0.72f), RoundedCornerShape(t.spacing.lg))
                .border(1.dp, p.markdownDivider.copy(alpha = 0.45f), RoundedCornerShape(t.spacing.lg))
                .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
        ) {
            Spacer(Modifier.weight(1f))
            SettingsActionButton(
                p = p,
                text = if (state.mcpBusyState.saving) {
                    AuraCodeBundle.message("settings.mcp.saving")
                } else {
                    AuraCodeBundle.message("common.save")
                },
                enabled = canSave,
                onClick = { onIntent(UiIntent.SaveMcpDraft) },
            )
        }
    }
}

@Composable
private fun FieldErrorText(
    p: DesignPalette,
    error: String?,
) {
    if (error.isNullOrBlank()) return
    Text(
        text = error,
        color = p.danger,
        style = MaterialTheme.typography.caption,
    )
}
