package com.auracode.assistant.toolwindow.drawer.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

/** Describes the lightweight executable-path card shown in each runtime tab. */
internal data class RuntimeExecutablePathsUiModel(
    val cliPathLabel: String,
    val cliPathHint: String,
    val cliPathValue: String,
    val cliPathError: String? = null,
    val nodePathLabel: String,
    val nodePathHint: String,
    val nodePathValue: String,
    val nodePathError: String? = null,
    val readinessText: String? = null,
)

/** Renders the shared executable-path layout for both Codex and Claude runtime tabs. */
@Composable
internal fun RuntimeExecutablePathsSection(
    p: DesignPalette,
    model: RuntimeExecutablePathsUiModel,
    onCliPathChange: (String) -> Unit,
    onNodePathChange: (String) -> Unit,
) {
    val t = assistantUiTokens()
    val cliPathInputState = rememberSettingsTextInputState(model.cliPathValue)
    val nodePathInputState = rememberSettingsTextInputState(model.nodePathValue)
    SettingsField(
        p = p,
        title = AuraCodeBundle.message("settings.runtime.paths.title"),
        description = AuraCodeBundle.message("settings.runtime.paths.hint"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(t.spacing.md),
        ) {
            model.readinessText?.let {
                SettingsInlineMessage(
                    p = p,
                    text = it,
                )
            }
            RuntimePathInput(
                p = p,
                label = model.cliPathLabel,
                hint = model.cliPathHint,
                value = cliPathInputState.value,
                errorText = model.cliPathError,
                onValueChange = {
                    cliPathInputState.value = it
                    onCliPathChange(it.text)
                },
            )
            RuntimePathInput(
                p = p,
                label = model.nodePathLabel,
                hint = model.nodePathHint,
                value = nodePathInputState.value,
                errorText = model.nodePathError,
                onValueChange = {
                    nodePathInputState.value = it
                    onNodePathChange(it.text)
                },
            )
        }
    }
}

/** Draws a single executable input row with optional inline validation feedback. */
@Composable
private fun RuntimePathInput(
    p: DesignPalette,
    label: String,
    hint: String,
    value: TextFieldValue,
    errorText: String?,
    onValueChange: (TextFieldValue) -> Unit,
) {
    val t = assistantUiTokens()
    Column(verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
        Text(
            text = label,
            color = p.textPrimary,
            style = MaterialTheme.typography.body2,
        )
        SettingsTextInput(
            p = p,
            value = value,
            onValueChange = onValueChange,
        )
        if (!errorText.isNullOrBlank()) {
            SettingsInlineMessage(
                p = p,
                text = errorText,
                isError = true,
            )
        }
        SettingsInlineMessage(
            p = p,
            text = hint,
        )
    }
}
