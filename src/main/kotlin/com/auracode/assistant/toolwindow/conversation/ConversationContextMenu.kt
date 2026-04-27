@file:OptIn(ExperimentalComposeUiApi::class)

package com.auracode.assistant.toolwindow.conversation

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

/**
 * Describes the visual tokens used by the custom timeline context menu.
 */
internal data class ConversationContextMenuAppearance(
    val backgroundColor: Color,
    val borderColor: Color,
    val itemHoverColor: Color,
    val textColor: Color,
    val hoveredTextColor: Color,
)

/**
 * Maps the shared tool-window palette to the timeline context menu chrome.
 */
internal fun conversationContextMenuAppearance(
    palette: DesignPalette,
): ConversationContextMenuAppearance {
    return ConversationContextMenuAppearance(
        backgroundColor = palette.topBarBg.copy(alpha = 0.96f),
        borderColor = palette.markdownDivider.copy(alpha = 0.52f),
        itemHoverColor = palette.accent.copy(alpha = 0.14f),
        textColor = palette.textPrimary,
        hoveredTextColor = palette.accent,
    )
}

/**
 * Remembers the custom context menu representation used by timeline text selections.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberConversationContextMenuRepresentation(
    palette: DesignPalette,
): ContextMenuRepresentation {
    val tokens = assistantUiTokens()
    val appearance = remember(palette) { conversationContextMenuAppearance(palette) }
    val shape = remember(tokens.spacing.sm) { RoundedCornerShape(tokens.spacing.sm) }
    return remember(appearance, shape) {
        ConversationContextMenuRepresentation(
            appearance = appearance,
            shape = shape,
        )
    }
}

/**
 * Renders the popup shell so timeline right-click actions match the project chrome.
 */
@OptIn(ExperimentalFoundationApi::class)
private class ConversationContextMenuRepresentation(
    private val appearance: ConversationContextMenuAppearance,
    private val shape: RoundedCornerShape,
) : ContextMenuRepresentation {
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Representation(
        state: ContextMenuState,
        items: () -> List<ContextMenuItem>,
    ) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return

        Popup(
            onDismissRequest = { state.status = ContextMenuState.Status.Closed },
            popupPositionProvider = rememberPopupPositionProviderAtPosition(
                positionPx = status.rect.center,
            ),
            properties = PopupProperties(focusable = true),
        ) {
            Column(
                modifier = Modifier
                    .shadow(8.dp, shape)
                    .background(appearance.backgroundColor, shape)
                    .border(1.dp, appearance.borderColor, shape)
                    .padding(vertical = 4.dp)
                    .width(IntrinsicSize.Max)
                    .verticalScroll(rememberScrollState()),
            ) {
                items().forEach { item ->
                    ConversationContextMenuItem(
                        item = item,
                        appearance = appearance,
                        onClick = {
                            state.status = ContextMenuState.Status.Closed
                            item.onClick()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Renders one menu row with the shared hover treatment used across the project.
 */
@Composable
private fun ConversationContextMenuItem(
    item: ContextMenuItem,
    appearance: ConversationContextMenuAppearance,
    onClick: () -> Unit,
) {
    val tokens = assistantUiTokens()
    val interactionSource = remember(item.label) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minWidth = 120.dp, maxWidth = 280.dp, minHeight = 32.dp)
            .hoverable(interactionSource = interactionSource)
            .background(if (hovered) appearance.itemHoverColor else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = tokens.spacing.md, vertical = tokens.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.label,
            color = if (hovered) appearance.hoveredTextColor else appearance.textColor,
            style = MaterialTheme.typography.body2,
        )
    }
}
