package com.auracode.assistant.toolwindow.conversation

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shared.DesignPalette

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimelineTextInteractionHost(
    palette: DesignPalette,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTextContextMenu provides rememberTimelineCopyOnlyTextContextMenu(),
        LocalContextMenuRepresentation provides rememberTimelineContextMenuRepresentation(palette),
    ) {
        content()
    }
}

@Composable
internal fun TimelineSelectableText(
    selectionColors: TextSelectionColors,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTextSelectionColors provides selectionColors,
    ) {
        SelectionContainer {
            content()
        }
    }
}

/** Builds stronger markdown selection colors so styled spans stay readable while copying text. */
@Composable
internal fun rememberTimelineMarkdownSelectionColors(
    palette: DesignPalette,
): TextSelectionColors {
    val background = timelineMarkdownSelectionBackground(palette)
    val handle = timelineMarkdownSelectionHandle(palette)
    return remember(background, handle) {
        TextSelectionColors(
            handleColor = handle,
            backgroundColor = background,
        )
    }
}

/** Builds a denser shell selection treatment because the command panel always uses a dark surface. */
@Composable
internal fun rememberTimelineCommandSelectionColors(): TextSelectionColors {
    val background = timelineCommandSelectionBackground()
    val handle = timelineCommandSelectionHandle()
    return remember(background, handle) {
        TextSelectionColors(
            handleColor = handle,
            backgroundColor = background,
        )
    }
}

/** Returns the markdown selection background tuned for either light or dark message surfaces. */
internal fun timelineMarkdownSelectionBackground(
    palette: DesignPalette,
): Color {
    return if (palette.timelineCardBg.luminance() > 0.5f) {
        Color(0xFF7E9CCB).copy(alpha = 0.38f)
    } else {
        Color(0xFF6E87AE).copy(alpha = 0.58f)
    }
}

/** Returns the markdown selection handle color aligned with the active theme accent strength. */
internal fun timelineMarkdownSelectionHandle(
    palette: DesignPalette,
): Color {
    return if (palette.timelineCardBg.luminance() > 0.5f) {
        palette.accent
    } else {
        Color(0xFF86B3FF)
    }
}

/** Returns the command panel selection background tuned for light text on a dark shell surface. */
internal fun timelineCommandSelectionBackground(): Color {
    return Color(0xFF6F86AE).copy(alpha = 0.64f)
}

/** Returns the command panel selection handle color with enough contrast against the shell background. */
internal fun timelineCommandSelectionHandle(): Color {
    return Color(0xFF8FBCFF)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberTimelineCopyOnlyTextContextMenu(): TextContextMenu {
    val copyLabel = timelineCopyMenuLabel()
    return remember(copyLabel) {
        object : TextContextMenu {
            @Composable
            override fun Area(
                textManager: TextContextMenu.TextManager,
                state: ContextMenuState,
                content: @Composable () -> Unit,
            ) {
                val items = timelineTextContextMenuItems(
                    copyLabel = copyLabel,
                    onCopy = textManager.copy,
                )
                ContextMenuArea(
                    items = { items },
                    state = state,
                    enabled = items.isNotEmpty(),
                    content = content,
                )
            }
        }
    }
}

/**
 * Returns the localized copy label shown by timeline text context menus.
 */
internal fun timelineCopyMenuLabel(): String {
    return AuraCodeBundle.message("timeline.copy")
}

@OptIn(ExperimentalFoundationApi::class)
internal fun timelineTextContextMenuItems(
    copyLabel: String,
    onCopy: (() -> Unit)?,
): List<ContextMenuItem> {
    return listOfNotNull(
        onCopy?.let { copyAction ->
            ContextMenuItem(copyLabel, copyAction)
        },
    )
}
