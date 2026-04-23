package com.auracode.assistant.toolwindow.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.ToolWindowUiText
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ComposerInputSection(
    p: DesignPalette,
    state: ComposerAreaState,
    running: Boolean,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val selectedMention = state.mentionSuggestions.getOrNull(state.activeMentionIndex)
    val selectedAgent = state.agentSuggestions.getOrNull(state.activeAgentIndex)
    val selectedSlash = state.slashSuggestions.getOrNull(state.activeSlashIndex)
    val composing = state.document.composition != null

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            val mentionVisualTransformation = remember(state.mentionEntries, p) {
                MentionVisualTransformation(state.mentionEntries, p)
            }
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent {
                        if (it.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        if (!composing && state.slashPopupVisible) {
                            when (it.key) {
                                Key.DirectionDown -> {
                                    onIntent(UiIntent.MoveSlashSelectionNext)
                                    return@onPreviewKeyEvent true
                                }
                                Key.DirectionUp -> {
                                    onIntent(UiIntent.MoveSlashSelectionPrevious)
                                    return@onPreviewKeyEvent true
                                }
                                Key.Escape -> {
                                    onIntent(UiIntent.DismissSlashPopup)
                                    return@onPreviewKeyEvent true
                                }
                                Key.Enter -> {
                                    if (!it.isShiftPressed && selectedSlash != null) {
                                        when (selectedSlash) {
                                            is SlashSuggestionItem.Command -> {
                                                if (selectedSlash.enabled) {
                                                    onIntent(UiIntent.SelectSlashCommand(selectedSlash.command))
                                                }
                                            }
                                            is SlashSuggestionItem.Skill -> onIntent(UiIntent.SelectSlashSkill(selectedSlash.name))
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                else -> Unit
                            }
                        }
                        if (!composing && state.mentionPopupVisible) {
                            when (it.key) {
                                Key.DirectionDown -> {
                                    onIntent(UiIntent.MoveMentionSelectionNext)
                                    return@onPreviewKeyEvent true
                                }
                                Key.DirectionUp -> {
                                    onIntent(UiIntent.MoveMentionSelectionPrevious)
                                    return@onPreviewKeyEvent true
                                }
                                Key.Escape -> {
                                    onIntent(UiIntent.DismissMentionPopup)
                                    return@onPreviewKeyEvent true
                                }
                                Key.Enter -> {
                                    if (!it.isShiftPressed && selectedMention != null) {
                                        when (selectedMention) {
                                            is MentionSuggestion.File -> onIntent(UiIntent.SelectMentionFile(selectedMention.entry.path))
                                            is MentionSuggestion.Agent -> {
                                                onIntent(UiIntent.SelectSessionSubagentMention(selectedMention.agent.threadId))
                                            }
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                else -> Unit
                            }
                        }
                        if (!composing && state.agentPopupVisible) {
                            when (it.key) {
                                Key.DirectionDown -> {
                                    onIntent(UiIntent.MoveAgentSelectionNext)
                                    return@onPreviewKeyEvent true
                                }
                                Key.DirectionUp -> {
                                    onIntent(UiIntent.MoveAgentSelectionPrevious)
                                    return@onPreviewKeyEvent true
                                }
                                Key.Escape -> {
                                    onIntent(UiIntent.DismissAgentPopup)
                                    return@onPreviewKeyEvent true
                                }
                                Key.Enter -> {
                                    if (!it.isShiftPressed && selectedAgent != null) {
                                        onIntent(UiIntent.SelectAgent(selectedAgent))
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                else -> Unit
                            }
                        }
                        if (!composing && !it.isShiftPressed && !it.isMetaPressed && !it.isCtrlPressed) {
                            when (it.key) {
                                Key.DirectionLeft -> {
                                    moveCursorLeftAcrossMention(state.document, state.mentionEntries)?.let { next ->
                                        onIntent(UiIntent.UpdateDocument(next))
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                Key.DirectionRight -> {
                                    moveCursorRightAcrossMention(state.document, state.mentionEntries)?.let { next ->
                                        onIntent(UiIntent.UpdateDocument(next))
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                Key.Backspace -> {
                                    val next = if (state.document.selection.collapsed) {
                                        removeMentionByBackspace(state.document, state.mentionEntries)
                                    } else {
                                        removeMentionSelection(state.document, state.mentionEntries)
                                    }
                                    next?.let { removed ->
                                        onIntent(UiIntent.UpdateDocument(removed.first))
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                Key.Delete -> {
                                    val next = if (state.document.selection.collapsed) {
                                        removeMentionByDelete(state.document, state.mentionEntries)
                                    } else {
                                        removeMentionSelection(state.document, state.mentionEntries)
                                    }
                                    next?.let { removed ->
                                        onIntent(UiIntent.UpdateDocument(removed.first))
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                else -> Unit
                            }
                        }
                        if (it.key == Key.V && (it.isMetaPressed || it.isCtrlPressed)) {
                            onIntent(UiIntent.PasteImageFromClipboard)
                            return@onPreviewKeyEvent false
                        }
                        if (!composing && it.key == Key.Enter && !it.isShiftPressed) {
                            onIntent(UiIntent.SendPrompt)
                            true
                        } else {
                            false
                        }
                    },
                value = state.document,
                onValueChange = { onIntent(UiIntent.UpdateDocument(it)) },
                textStyle = TextStyle(color = p.textPrimary, fontSize = t.type.body, lineHeight = 19.sp),
                label = {
                    Text(
                        text = ToolWindowUiText.COMPOSER_HINT,
                        color = p.textMuted,
                        style = androidx.compose.material.MaterialTheme.typography.body2,
                    )
                },
                visualTransformation = mentionVisualTransformation,
                singleLine = false,
                maxLines = 6,
                shape = RoundedCornerShape(t.spacing.sm),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = p.textPrimary,
                    backgroundColor = p.timelineCardBg,
                    cursorColor = p.textPrimary,
                    focusedBorderColor = p.accent,
                    unfocusedBorderColor = p.markdownDivider.copy(alpha = 0.55f),
                    focusedLabelColor = p.textSecondary,
                    unfocusedLabelColor = p.textMuted,
                ),
            )
            BoxWithConstraints {
                val maxPopupHeight = maxHeight * 0.52f
                val popupMode = when {
                    state.slashPopupVisible -> ComposerPopupMode.SLASH
                    state.agentPopupVisible -> ComposerPopupMode.AGENT
                    state.mentionPopupVisible -> ComposerPopupMode.MENTION
                    else -> ComposerPopupMode.NONE
                }
                val popupContent = buildComposerPopupContent(
                    slashSuggestions = state.slashSuggestions,
                    activeSlashIndex = state.activeSlashIndex,
                    mentionSuggestions = state.mentionSuggestions,
                    activeMentionIndex = state.activeMentionIndex,
                    agentSuggestions = state.agentSuggestions,
                    activeAgentIndex = state.activeAgentIndex,
                    mode = popupMode,
                )
                val popupScrollState = rememberScrollState()
                val popupRowRequesters = popupContent.rows.map { remember { BringIntoViewRequester() } }

                LaunchedEffect(popupMode, popupContent.selectedRowIndex) {
                    if (popupMode != ComposerPopupMode.NONE) {
                        popupContent.selectedRowIndex?.let { selectedRowIndex ->
                            popupRowRequesters.getOrNull(selectedRowIndex)?.bringIntoView()
                        }
                    }
                }
                DropdownMenu(
                    expanded = state.slashPopupVisible || state.mentionPopupVisible || state.agentPopupVisible,
                    onDismissRequest = {
                        if (state.slashPopupVisible) {
                            onIntent(UiIntent.DismissSlashPopup)
                        } else if (state.agentPopupVisible) {
                            onIntent(UiIntent.DismissAgentPopup)
                        } else {
                            onIntent(UiIntent.DismissMentionPopup)
                        }
                    },
                    modifier = Modifier.heightIn(max = maxPopupHeight),
                    properties = PopupProperties(focusable = false),
                ) {
                    Column(
                        modifier = Modifier
                            .width(360.dp)
                            .heightIn(max = maxPopupHeight)
                            .verticalScroll(popupScrollState),
                    ) {
                        popupContent.rows.forEachIndexed { index, row ->
                            when (row) {
                                is ComposerPopupRow.Header -> Box(
                                    modifier = Modifier.bringIntoViewRequester(popupRowRequesters[index]),
                                ) {
                                    SlashSectionHeader(
                                        title = row.title,
                                        p = p,
                                    )
                                }
                                is ComposerPopupRow.SlashItem -> {
                                    DropdownMenuItem(
                                        modifier = Modifier.bringIntoViewRequester(popupRowRequesters[index]),
                                        enabled = row.item.isEnabled(),
                                        onClick = { row.item.dispatch(onIntent) },
                                    ) {
                                        SlashSuggestionRow(
                                            title = row.item.title,
                                            description = row.item.description,
                                            selected = index == popupContent.selectedRowIndex,
                                            enabled = row.item.isEnabled(),
                                            p = p,
                                        )
                                    }
                                }
                                is ComposerPopupRow.AgentItem -> {
                                    DropdownMenuItem(
                                        modifier = Modifier.bringIntoViewRequester(popupRowRequesters[index]),
                                        onClick = { onIntent(UiIntent.SelectAgent(row.agent)) },
                                    ) {
                                        AgentSuggestionRow(
                                            name = row.agent.name,
                                            selected = index == popupContent.selectedRowIndex,
                                            p = p,
                                        )
                                    }
                                }
                                is ComposerPopupRow.MentionItem -> {
                                    DropdownMenuItem(
                                        modifier = Modifier.bringIntoViewRequester(popupRowRequesters[index]),
                                        onClick = {
                                            when (row) {
                                                is ComposerPopupRow.MentionAgentItem -> {
                                                    onIntent(UiIntent.SelectSessionSubagentMention(row.agent.threadId))
                                                }

                                                is ComposerPopupRow.MentionFileItem -> {
                                                    onIntent(UiIntent.SelectMentionFile(row.entry.path))
                                                }
                                            }
                                        },
                                    ) {
                                        when (row) {
                                            is ComposerPopupRow.MentionAgentItem -> MentionAgentSuggestionRow(
                                                agent = row.agent,
                                                selected = index == popupContent.selectedRowIndex,
                                                p = p,
                                            )

                                            is ComposerPopupRow.MentionFileItem -> MentionFileSuggestionRow(
                                                entry = row.entry,
                                                selected = index == popupContent.selectedRowIndex,
                                                p = p,
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
}

@Composable
private fun SlashSectionHeader(
    title: String,
    p: DesignPalette,
) {
    Text(
        text = title,
        color = p.textMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun SlashSuggestionRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    p: DesignPalette,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) p.topStripBg else Color.Transparent, RoundedCornerShape(t.spacing.sm))
            .padding(horizontal = t.spacing.sm, vertical = t.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                color = if (enabled) p.textPrimary else p.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.material.MaterialTheme.typography.body2,
            )
            Text(
                text = description,
                color = p.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.material.MaterialTheme.typography.caption,
            )
        }
    }
}

private class MentionVisualTransformation(
    mentions: List<MentionEntry>,
    private val palette: DesignPalette,
) : VisualTransformation {
    private val spans = mentions.map { MentionTransformSpan(start = it.start, endExclusive = it.endExclusive) }

    override fun filter(text: AnnotatedString): TransformedText {
        return buildMentionTransformedText(
            text = text.text,
            spans = spans,
        ) { builder, start, endExclusive ->
            builder.addStyle(
                SpanStyle(
                    color = palette.textPrimary,
                    background = palette.topStripBg,
                ),
                start,
                endExclusive,
            )
        }
    }
}

@Composable
private fun MentionFileSuggestionRow(
    entry: ContextEntry,
    selected: Boolean,
    p: DesignPalette,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) p.topStripBg else Color.Transparent, RoundedCornerShape(t.spacing.sm))
            .padding(horizontal = t.spacing.sm, vertical = t.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        ComposerFileTypeIcon(
            fileName = entry.path.substringAfterLast('/').substringAfterLast('\\').ifBlank { entry.displayName },
            tint = p.textSecondary,
            tokens = t,
        )
        Spacer(Modifier.width(t.spacing.sm))
        Text(
            text = entry.displayName,
            color = if (selected) p.textPrimary else p.textSecondary,
            maxLines = 1,
        )
        if (entry.tailPath.isNotBlank()) {
            Spacer(Modifier.width(t.spacing.sm))
            Text(
                text = entry.tailPath,
                color = p.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MentionAgentSuggestionRow(
    agent: SessionSubagentUiModel,
    selected: Boolean,
    p: DesignPalette,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) p.topStripBg else Color.Transparent, RoundedCornerShape(t.spacing.sm))
            .padding(horizontal = t.spacing.sm, vertical = t.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource("/icons/community.svg"),
            contentDescription = null,
            tint = p.textSecondary,
            modifier = Modifier.size(t.controls.iconMd),
        )
        Spacer(Modifier.width(t.spacing.sm))
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "@${agent.mentionSlug}",
                color = if (selected) p.textPrimary else p.textSecondary,
                maxLines = 1,
            )
            Text(
                text = agent.summary ?: agent.displayName,
                color = p.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.material.MaterialTheme.typography.caption,
            )
        }
    }
}

@Composable
private fun AgentSuggestionRow(
    name: String,
    selected: Boolean,
    p: DesignPalette,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) p.topStripBg else Color.Transparent, RoundedCornerShape(t.spacing.sm))
            .padding(horizontal = t.spacing.sm, vertical = t.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource("/icons/agent-settings.svg"),
            contentDescription = null,
            tint = p.textSecondary,
            modifier = Modifier.size(t.controls.iconMd),
        )
        Spacer(Modifier.width(t.spacing.sm))
        Text(
            text = name,
            color = if (selected) p.textPrimary else p.textSecondary,
            maxLines = 1,
        )
    }
}

internal enum class ComposerPopupMode {
    NONE,
    SLASH,
    MENTION,
    AGENT,
}

internal data class ComposerPopupContent(
    val rows: List<ComposerPopupRow>,
    val selectedRowIndex: Int?,
)

internal sealed interface ComposerPopupRow {
    data class Header(val title: String) : ComposerPopupRow
    data class SlashItem(val item: SlashSuggestionItem) : ComposerPopupRow
    sealed interface MentionItem : ComposerPopupRow
    data class MentionFileItem(val entry: ContextEntry) : MentionItem
    data class MentionAgentItem(val agent: SessionSubagentUiModel) : MentionItem
    data class AgentItem(val agent: com.auracode.assistant.settings.SavedAgentDefinition) : ComposerPopupRow
}

/** Builds one popup row model so slash, mention, and agent menus share navigation behavior. */
internal fun buildComposerPopupContent(
    slashSuggestions: List<SlashSuggestionItem>,
    activeSlashIndex: Int,
    mentionSuggestions: List<MentionSuggestion>,
    activeMentionIndex: Int,
    agentSuggestions: List<com.auracode.assistant.settings.SavedAgentDefinition>,
    activeAgentIndex: Int,
    mode: ComposerPopupMode,
): ComposerPopupContent {
    return when (mode) {
        ComposerPopupMode.SLASH -> {
            val rows = mutableListOf<ComposerPopupRow>()
            var selectedRowIndex: Int? = null
            val commandItems = slashSuggestions.filterIsInstance<SlashSuggestionItem.Command>()
            val skillItems = slashSuggestions.filterIsInstance<SlashSuggestionItem.Skill>()
            if (commandItems.isNotEmpty()) {
                rows += ComposerPopupRow.Header(AuraCodeBundle.message("composer.slash.section.commands"))
                commandItems.forEachIndexed { index, item ->
                    if (index == activeSlashIndex) {
                        selectedRowIndex = rows.size
                    }
                    rows += ComposerPopupRow.SlashItem(item)
                }
            }
            if (skillItems.isNotEmpty()) {
                rows += ComposerPopupRow.Header(AuraCodeBundle.message("composer.slash.section.skills"))
                skillItems.forEachIndexed { index, item ->
                    val overallIndex = commandItems.size + index
                    if (overallIndex == activeSlashIndex) {
                        selectedRowIndex = rows.size
                    }
                    rows += ComposerPopupRow.SlashItem(item)
                }
            }
            ComposerPopupContent(rows = rows, selectedRowIndex = selectedRowIndex)
        }
        ComposerPopupMode.MENTION -> {
            val rows = mutableListOf<ComposerPopupRow>()
            var selectedRowIndex: Int? = null
            val agents = mentionSuggestions.filterIsInstance<MentionSuggestion.Agent>()
            val files = mentionSuggestions.filterIsInstance<MentionSuggestion.File>()
            if (agents.isNotEmpty()) {
                rows += ComposerPopupRow.Header(AuraCodeBundle.message("composer.mention.section.agents"))
                agents.forEachIndexed { index, item ->
                    if (index == activeMentionIndex) {
                        selectedRowIndex = rows.size
                    }
                    rows += ComposerPopupRow.MentionAgentItem(item.agent)
                }
            }
            if (files.isNotEmpty()) {
                rows += ComposerPopupRow.Header(AuraCodeBundle.message("composer.mention.section.files"))
                files.forEachIndexed { index, item ->
                    val overallIndex = agents.size + index
                    if (overallIndex == activeMentionIndex) {
                        selectedRowIndex = rows.size
                    }
                    rows += ComposerPopupRow.MentionFileItem(item.entry)
                }
            }
            ComposerPopupContent(rows = rows, selectedRowIndex = selectedRowIndex)
        }
        ComposerPopupMode.AGENT -> ComposerPopupContent(
            rows = agentSuggestions.map { ComposerPopupRow.AgentItem(it) },
            selectedRowIndex = agentSuggestions.indices.firstOrNull { it == activeAgentIndex },
        )
        ComposerPopupMode.NONE -> ComposerPopupContent(rows = emptyList(), selectedRowIndex = null)
    }
}

/** Routes popup row clicks back through the existing slash selection intents. */
private fun SlashSuggestionItem.dispatch(onIntent: (UiIntent) -> Unit) {
    when (this) {
        is SlashSuggestionItem.Command -> onIntent(UiIntent.SelectSlashCommand(command))
        is SlashSuggestionItem.Skill -> onIntent(UiIntent.SelectSlashSkill(name))
    }
}

/** Exposes the enabled flag for command rows while keeping skill rows always selectable. */
private fun SlashSuggestionItem.isEnabled(): Boolean {
    return when (this) {
        is SlashSuggestionItem.Command -> enabled
        is SlashSuggestionItem.Skill -> true
    }
}

/** Provides stable keys so the popup list keeps its scroll position while selection changes. */
private fun ComposerPopupRow.stableKey(index: Int): String {
    return when (this) {
        is ComposerPopupRow.Header -> "header:$title:$index"
        is ComposerPopupRow.SlashItem -> "slash:${item.title}"
        is ComposerPopupRow.MentionAgentItem -> "mention-agent:${agent.threadId}"
        is ComposerPopupRow.MentionFileItem -> "mention-file:${entry.path}"
        is ComposerPopupRow.AgentItem -> "agent:${agent.id}"
    }
}
