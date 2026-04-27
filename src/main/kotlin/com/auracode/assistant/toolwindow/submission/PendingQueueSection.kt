package com.auracode.assistant.toolwindow.submission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun PendingQueueSection(
    p: DesignPalette,
    submissions: List<PendingSubmission>,
    onIntent: (UiIntent) -> Unit,
) {
    if (submissions.isEmpty()) return
    val t = assistantUiTokens()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.topBarBg.copy(alpha = 0.42f), RoundedCornerShape(t.spacing.sm))
            .border(1.dp, p.markdownDivider.copy(alpha = 0.3f), RoundedCornerShape(t.spacing.sm))
            .padding(horizontal = t.spacing.sm, vertical = t.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
    ) {
        Text(
            text = AuraCodeBundle.message("composer.pending.title"),
            color = p.textSecondary,
            style = MaterialTheme.typography.caption,
        )
        submissions.forEach { submission ->
            PendingQueueRow(
                p = p,
                submission = submission,
                onCancel = { onIntent(UiIntent.RemovePendingSubmission(submission.id)) },
            )
        }
    }
}

@Composable
private fun PendingQueueRow(
    p: DesignPalette,
    submission: PendingSubmission,
    onCancel: () -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.timelineCardBg.copy(alpha = 0.6f), RoundedCornerShape(t.spacing.sm))
            .padding(start = t.spacing.sm, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(p.accent.copy(alpha = 0.85f), RoundedCornerShape(999.dp)),
        )
        Spacer(Modifier.size(t.spacing.sm))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = submission.summary,
                color = p.textPrimary,
                style = MaterialTheme.typography.body2.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                if (submission.contextFiles.isNotEmpty()) add(AuraCodeBundle.message("composer.pending.meta.context", submission.contextFiles.size))
                if (submission.totalAttachmentCount > 0) add(AuraCodeBundle.message("composer.pending.meta.attachments", submission.totalAttachmentCount))
                add(submission.selectedModel)
            }.joinToString(" · ")
            Text(
                text = meta,
                color = p.textMuted,
                style = MaterialTheme.typography.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier.size(22.dp),
        ) {
            Icon(
                painter = painterResource("/icons/close-small.svg"),
                contentDescription = AuraCodeBundle.message("composer.pending.cancel"),
                tint = p.textMuted,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
