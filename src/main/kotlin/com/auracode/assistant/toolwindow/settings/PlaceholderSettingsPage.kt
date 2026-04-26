package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun PlaceholderSettingsPage(
    p: DesignPalette,
    title: String,
    body: String,
) {
    val t = assistantUiTokens()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, color = p.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.subtitle1)
        Spacer(Modifier.height(t.spacing.sm))
        Text(body, color = p.textSecondary, style = MaterialTheme.typography.body2)
    }
}
