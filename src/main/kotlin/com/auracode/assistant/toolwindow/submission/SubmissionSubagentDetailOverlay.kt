package com.auracode.assistant.toolwindow.submission

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.auracode.assistant.toolwindow.shared.DesignPalette

/**
 * Shows the selected subagent details as a non-modal anchored overlay inside the tray region.
 */
@Composable
internal fun BoxScope.SubmissionSubagentDetailOverlay(
    p: DesignPalette,
    subagent: SessionSubagentUiModel?,
) {
    subagent ?: return
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(top = 12.dp, end = 8.dp, bottom = 8.dp)
            .widthIn(max = 360.dp)
            .shadow(12.dp),
    ) {
        SubmissionSubagentDetailCard(
            p = p,
            subagent = subagent,
        )
    }
}
