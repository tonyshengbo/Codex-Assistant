package com.codex.assistant.toolwindow.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.assistant.i18n.CodexBundle
import com.codex.assistant.service.AgentChatService
import com.codex.assistant.toolwindow.drawer.settings.DrawerHeader
import com.codex.assistant.toolwindow.eventing.UiIntent
import com.codex.assistant.toolwindow.shared.DesignPalette
import com.codex.assistant.toolwindow.shared.HoverTooltip
import com.codex.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun HistoryOverlay(
    p: DesignPalette,
    state: RightDrawerAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    Column(modifier = Modifier.fillMaxSize().padding(t.spacing.md)) {
        DrawerHeader(
            p = p,
            title = CodexBundle.message("drawer.history.title"),
            subtitle = CodexBundle.message("drawer.history.subtitle"),
        )
        Spacer(Modifier.height(t.spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
        ) {
            SummaryCard(
                p = p,
                title = CodexBundle.message("drawer.history.summary.total"),
                value = state.sessions.size.toString(),
                modifier = Modifier.weight(1f),
            )
            SummaryCard(
                p = p,
                title = CodexBundle.message("drawer.history.summary.active"),
                value = if (state.activeSessionId.isBlank()) "-" else state.activeSessionId.take(8),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(t.spacing.md))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(p.topBarBg.copy(alpha = 0.7f), RoundedCornerShape(t.spacing.lg))
                .border(1.dp, p.markdownDivider.copy(alpha = 0.48f), RoundedCornerShape(t.spacing.lg)),
        ) {
            if (state.sessions.isEmpty()) {
                EmptyHistoryState(
                    p = p,
                    title = CodexBundle.message("drawer.history.empty.title"),
                    description = CodexBundle.message("drawer.history.empty.body"),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(t.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
                ) {
                    items(state.sessions, key = { it.id }) { session ->
                        HistorySessionRow(
                            p = p,
                            session = session,
                            active = session.id == state.activeSessionId,
                            onOpen = { onIntent(UiIntent.SwitchSession(session.id)) },
                            onDelete = { onIntent(UiIntent.DeleteSession(session.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    p: DesignPalette,
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val t = assistantUiTokens()
    Box(
        modifier = modifier
            .background(p.topBarBg.copy(alpha = 0.72f), RoundedCornerShape(t.spacing.lg))
            .border(1.dp, p.markdownDivider.copy(alpha = 0.45f), RoundedCornerShape(t.spacing.lg)),
    ) {
        Column(modifier = Modifier.padding(horizontal = t.spacing.lg, vertical = t.spacing.md)) {
            Text(title, color = p.textSecondary, style = MaterialTheme.typography.body2)
            Spacer(Modifier.height(t.spacing.sm))
            Text(value, color = p.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.h6)
        }
    }
}

@Composable
private fun HistorySessionRow(
    p: DesignPalette,
    session: AgentChatService.SessionSummary,
    active: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = assistantUiTokens()
    val borderColor = if (active) p.accent.copy(alpha = 0.45f) else p.markdownDivider.copy(alpha = 0.36f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.spacing.lg))
            .background(if (active) p.userBubbleBg.copy(alpha = 0.45f) else p.topStripBg.copy(alpha = 0.88f))
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(t.spacing.lg))
            .clickable(onClick = onOpen)
            .padding(horizontal = t.spacing.md, vertical = t.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(t.spacing.sm)
                .clip(CircleShape)
                .background(if (active) p.accent else p.textMuted.copy(alpha = 0.4f)),
        )
        Spacer(Modifier.width(t.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title.trim().ifBlank { CodexBundle.message("session.new") },
                color = p.textPrimary,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.body1,
            )
            Spacer(Modifier.height(t.spacing.xs))
            Text(
                text = CodexBundle.message("drawer.history.row.meta", session.messageCount.toString(), session.updatedAt.toString()),
                color = p.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.caption,
            )
        }
        HoverTooltip(text = CodexBundle.message("common.delete")) {
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource("/icons/delete.svg"),
                    contentDescription = CodexBundle.message("common.delete"),
                    tint = p.textSecondary,
                    modifier = Modifier.size(t.controls.iconMd),
                )
            }
        }
    }
}

@Composable
private fun EmptyHistoryState(
    p: DesignPalette,
    title: String,
    description: String,
) {
    val t = assistantUiTokens()
    Column(
        modifier = Modifier.fillMaxSize().padding(t.spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = p.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.h6)
        Spacer(Modifier.height(t.spacing.sm))
        Text(description, color = p.textSecondary, style = MaterialTheme.typography.body2)
    }
}
