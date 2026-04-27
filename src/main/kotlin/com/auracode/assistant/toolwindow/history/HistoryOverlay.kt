package com.auracode.assistant.toolwindow.history

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
import androidx.compose.material.TextButton
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shell.SidePanelAreaState
import com.auracode.assistant.toolwindow.settings.PanelHeader
import com.auracode.assistant.toolwindow.settings.OverlayCloseButton
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.HoverTooltip
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun HistoryOverlay(
    p: DesignPalette,
    state: SidePanelAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = t.spacing.md, end = t.spacing.md, top = t.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                PanelHeader(
                    p = p,
                    title = AuraCodeBundle.message("drawer.history.title"),
                    subtitle = AuraCodeBundle.message("drawer.history.subtitle"),
                )
            }
            OverlayCloseButton(
                p = p,
                onClick = { onIntent(UiIntent.CloseSidePanel) },
            )
        }
        Spacer(Modifier.height(t.spacing.md))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = t.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
        ) {
            SummaryCard(
                p = p,
                title = AuraCodeBundle.message("drawer.history.summary.total"),
                value = state.historyConversations.size.toString(),
                modifier = Modifier.weight(1f),
            )
            SummaryCard(
                p = p,
                title = AuraCodeBundle.message("drawer.history.summary.active"),
                value = state.activeRemoteConversationId.ifBlank { "-" }.take(12),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(t.spacing.md))
        TextField(
            value = state.historyQuery,
            onValueChange = { onIntent(UiIntent.EditHistorySearchQuery(it)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = t.spacing.md),
            singleLine = true,
            placeholder = {
                Text(
                    text = AuraCodeBundle.message("drawer.history.search.placeholder"),
                    color = p.textMuted,
                    style = MaterialTheme.typography.body2,
                )
            },
            colors = TextFieldDefaults.textFieldColors(
                textColor = p.textPrimary,
                backgroundColor = p.topBarBg.copy(alpha = 0.72f),
                focusedIndicatorColor = p.accent,
                unfocusedIndicatorColor = p.markdownDivider.copy(alpha = 0.4f),
                cursorColor = p.accent,
            ),
        )
        Spacer(Modifier.height(t.spacing.md))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = t.spacing.xs),
        ) {
            if (state.historyLoading && state.historyConversations.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = p.accent)
                }
            } else if (state.historyConversations.isEmpty()) {
                EmptyHistoryState(
                    p = p,
                    title = AuraCodeBundle.message("drawer.history.empty.title"),
                    description = AuraCodeBundle.message("drawer.history.empty.body"),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
                ) {
                    items(state.historyConversations, key = { it.remoteConversationId }) { session ->
                        HistorySessionRow(
                            p = p,
                            session = session,
                            active = session.remoteConversationId == state.activeRemoteConversationId,
                            onOpen = {
                                onIntent(
                                    UiIntent.OpenRemoteConversation(
                                        remoteConversationId = session.remoteConversationId,
                                        title = session.title,
                                    ),
                                )
                            },
                            onExport = {
                                onIntent(
                                    UiIntent.ExportRemoteConversation(
                                        remoteConversationId = session.remoteConversationId,
                                        title = session.title,
                                    ),
                                )
                            },
                        )
                    }
                    if (state.historyNextCursor != null || state.historyLoading) {
                        item("history-load-more") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(top = t.spacing.sm),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (state.historyLoading) {
                                    CircularProgressIndicator(
                                        color = p.accent,
                                        modifier = Modifier.size(t.controls.iconMd),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    TextButton(onClick = { onIntent(UiIntent.LoadMoreHistoryConversations) }) {
                                        Text(
                                            text = AuraCodeBundle.message("timeline.loadMore"),
                                            color = p.accent,
                                        )
                                    }
                                }
                            }
                        }
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
    session: com.auracode.assistant.conversation.ConversationSummary,
    active: Boolean,
    onOpen: () -> Unit,
    onExport: () -> Unit,
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
                text = session.title.trim().ifBlank { AuraCodeBundle.message("session.new") },
                color = p.textPrimary,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.body1,
            )
            Spacer(Modifier.height(t.spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            )
            {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(p.accent.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = formatHistoryStatus(session.status),
                        color = p.accent,
                        style = MaterialTheme.typography.caption,
                    )
                }
                Spacer(Modifier.width(t.spacing.sm))
                Text(
                    text = formatHistoryUpdatedAt(session.updatedAt),
                    color = p.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.caption,
                )
            }
        }
        HoverTooltip(text = AuraCodeBundle.message("header.action.history")) {
            IconButton(onClick = onOpen) {
                Icon(
                    painter = painterResource("/icons/history.svg"),
                    contentDescription = AuraCodeBundle.message("header.action.history"),
                    tint = p.textSecondary,
                    modifier = Modifier.size(t.controls.iconMd),
                )
            }
        }
        HoverTooltip(text = AuraCodeBundle.message("drawer.history.export.action")) {
            IconButton(onClick = onExport) {
                Icon(
                    painter = painterResource("/icons/document.svg"),
                    contentDescription = AuraCodeBundle.message("drawer.history.export.action"),
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
