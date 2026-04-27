package com.auracode.assistant.toolwindow.conversation

import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.protocol.ItemStatus
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.auracode.assistant.toolwindow.shared.EffectiveTheme
import com.auracode.assistant.toolwindow.shared.assistantPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationActivityRegionLogicTest {
    @Test
    fun `reasoning timeline title uses thinking label`() {
        assertEquals("Thinking", AuraCodeBundle.message("timeline.reasoning"))
    }

    @Test
    fun `user message node is recognized by role`() {
        val userItem = ConversationActivityItem.MessageNode(
            id = "n1",
            sourceId = "n1",
            role = MessageRole.USER,
            text = "hello",
            status = ItemStatus.SUCCESS,
            timestamp = null,
            turnId = null,
            cursor = null,
        )
        val assistantItem = userItem.copy(id = "n2", role = MessageRole.ASSISTANT)
        val toolItem = ConversationActivityItem.ToolCallNode(
            id = "n3",
            sourceId = "tool-1",
            title = "shell",
            body = "ls",
            status = ItemStatus.SUCCESS,
            turnId = null,
        )

        assertTrue(isUserMessageNode(userItem))
        assertFalse(isUserMessageNode(assistantItem))
        assertFalse(isUserMessageNode(toolItem))
    }

    @Test
    fun `expanded activity body max height stays compact`() {
        assertEquals(220.dp, conversationExpandedBodyMaxHeight())
    }

    @Test
    fun `plan activity body has no max height cap`() {
        assertEquals(null, conversationPlanExpandedBodyMaxHeight())
    }

    @Test
    fun `plan node uses compact title and checklist count badge`() {
        assertEquals("Plan", conversationPlanDisplayTitle())
        assertEquals("3 steps", conversationPlanBadgeText("- [x] One\n- [ ] Two\n- [done] Three"))
        assertEquals(null, conversationPlanBadgeText("Summary only"))
    }

    @Test
    fun `plan collapsed summary prefers first checklist item`() {
        assertEquals(
            "Ship protocol support",
            conversationPlanCollapsedSummary(
                """
                ## Summary
                Refresh the UI

                - [ ] Ship protocol support
                - [ ] Polish timeline card
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `plan collapsed summary falls back to summary section`() {
        assertEquals(
            "Refresh composer cumulative files view.",
            conversationPlanCollapsedSummary(
                """
                # Plan
                Summary

                Refresh composer cumulative files view.

                ## Notes
                Keep timeline flat.
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `plan collapsed summary falls back to first meaningful line`() {
        assertEquals(
            "Tighten the collapsed hierarchy.",
            conversationPlanCollapsedSummary(
                """
                # Plan

                Tighten the collapsed hierarchy.

                More detail here.
                """.trimIndent(),
            ),
        )
        assertEquals(null, conversationPlanCollapsedSummary("# Plan\n## Summary"))
    }

    @Test
    fun `file change diff lines map to restrained preview colors`() {
        assertEquals(ConversationDiffLineKind.HUNK, conversationDiffLineKind("@@ -1 +1 @@"))
        assertEquals(ConversationDiffLineKind.ADDITION, conversationDiffLineKind("+fun b() = 2"))
        assertEquals(ConversationDiffLineKind.DELETION, conversationDiffLineKind("-fun b() = 1"))
        assertEquals(ConversationDiffLineKind.META, conversationDiffLineKind("+++ b/src/Main.kt"))
        assertEquals(ConversationDiffLineKind.CONTEXT, conversationDiffLineKind(" fun a() = 1"))
    }

    @Test
    fun `command panel content keeps command and raw output separate`() {
        val panel = conversationCommandPanelContent(
            commandText = "./gradlew --no-daemon test",
            outputText = "> Task :prepareTest\n> Task :test",
        )

        assertEquals("Shell", panel.label)
        assertEquals("./gradlew --no-daemon test", panel.commandText)
        assertEquals("> Task :prepareTest\n> Task :test", panel.outputText)
    }

    @Test
    fun `timeline copy text returns plain text for supported node types`() {
        val message = ConversationActivityItem.MessageNode(
            id = "message",
            sourceId = "message",
            role = MessageRole.USER,
            text = "ship timeline copy",
            status = ItemStatus.SUCCESS,
            timestamp = null,
            turnId = null,
            cursor = null,
        )
        val plan = ConversationActivityItem.PlanNode(
            id = "plan",
            sourceId = "plan",
            title = "Plan",
            body = "- [ ] Add copy action\n- [ ] Polish hover state",
            status = ItemStatus.SUCCESS,
            turnId = null,
        )
        val command = ConversationActivityItem.CommandNode(
            id = "command",
            sourceId = "command",
            title = "Run tests",
            body = "",
            commandText = "./gradlew test",
            outputText = "> Task :test",
            collapsedSummary = null,
            status = ItemStatus.SUCCESS,
            turnId = null,
        )

        assertEquals("ship timeline copy", conversationActivityCopyText(message))
        assertEquals("- [ ] Add copy action\n- [ ] Polish hover state", conversationActivityCopyText(plan))
        assertEquals(
            "Run tests\n\n./gradlew test\n\n> Task :test",
            conversationActivityCopyText(command),
        )
    }

    @Test
    fun `timeline copy text skips unsupported or blank nodes`() {
        val fileChange = ConversationActivityItem.FileChangeNode(
            id = "diff",
            sourceId = "diff",
            title = "Diff",
            changes = emptyList(),
            collapsedSummary = null,
            status = ItemStatus.SUCCESS,
            turnId = null,
        )
        val blankMessage = ConversationActivityItem.MessageNode(
            id = "blank-message",
            sourceId = "blank-message",
            role = MessageRole.USER,
            text = "   ",
            status = ItemStatus.SUCCESS,
            timestamp = null,
            turnId = null,
            cursor = null,
        )
        val loadMore = ConversationActivityItem.LoadMoreNode(isLoading = false)

        assertEquals(null, conversationActivityCopyText(fileChange))
        assertEquals(null, conversationActivityCopyText(blankMessage))
        assertEquals(null, conversationActivityCopyText(loadMore))
    }

    @Test
    fun `command copy body joins only non blank sections with blank lines`() {
        assertEquals(
            "Plan command\n\n./gradlew test\n\n> Task :test",
            conversationCommandCopyBody(
                title = "Plan command",
                commandText = "./gradlew test",
                outputText = "> Task :test",
            ),
        )
        assertEquals(
            "./gradlew test",
            conversationCommandCopyBody(
                title = null,
                commandText = "./gradlew test",
                outputText = "   ",
            ),
        )
    }

    @Test
    fun `command panel near bottom only when scroll tail stays within threshold`() {
        val pinned = ConversationCommandScrollSnapshot(
            currentOffset = 396,
            maxOffset = 420,
        )
        val drifting = pinned.copy(currentOffset = 340)

        assertTrue(conversationCommandIsNearBottom(pinned))
        assertFalse(conversationCommandIsNearBottom(drifting))
    }

    @Test
    fun `command panel bottom overflow reports remaining scroll distance`() {
        val hiddenTail = ConversationCommandScrollSnapshot(
            currentOffset = 280,
            maxOffset = 420,
        )
        val pinned = hiddenTail.copy(currentOffset = 420)

        assertEquals(140, conversationCommandBottomOverflowPx(hiddenTail))
        assertEquals(0, conversationCommandBottomOverflowPx(pinned))
    }

    @Test
    fun `command panel follow mode only changes after scrolling settles`() {
        assertTrue(
            conversationCommandResolveAutoFollow(
                wasAutoFollowEnabled = true,
                isScrollInProgress = true,
                isNearBottom = false,
            ),
        )
        assertFalse(
            conversationCommandResolveAutoFollow(
                wasAutoFollowEnabled = true,
                isScrollInProgress = false,
                isNearBottom = false,
            ),
        )
        assertTrue(
            conversationCommandResolveAutoFollow(
                wasAutoFollowEnabled = false,
                isScrollInProgress = false,
                isNearBottom = true,
            ),
        )
    }

    @Test
    fun `timeline text context menu exposes copy only when selection exists`() {
        var copied = false

        val present = conversationTextContextMenuItems(
            copyLabel = "Copy",
            onCopy = { copied = true },
        )
        val absent = conversationTextContextMenuItems(
            copyLabel = "Copy",
            onCopy = null,
        )

        assertEquals(1, present.size)
        assertEquals("Copy", present.single().label)
        present.single().onClick()
        assertTrue(copied)
        assertTrue(absent.isEmpty())
    }

    @Test
    fun `timeline copy menu label uses project bundle wording`() {
        assertEquals(AuraCodeBundle.message("timeline.copy"), conversationCopyMenuLabel())
    }

    @Test
    fun `timeline context menu appearance follows shared palette chrome`() {
        val lightPalette = assistantPalette(EffectiveTheme.LIGHT)
        val darkPalette = assistantPalette(EffectiveTheme.DARK)

        assertEquals(
            ConversationContextMenuAppearance(
                backgroundColor = lightPalette.topBarBg.copy(alpha = 0.96f),
                borderColor = lightPalette.markdownDivider.copy(alpha = 0.52f),
                itemHoverColor = lightPalette.accent.copy(alpha = 0.14f),
                textColor = lightPalette.textPrimary,
                hoveredTextColor = lightPalette.accent,
            ),
            conversationContextMenuAppearance(lightPalette),
        )
        assertEquals(
            ConversationContextMenuAppearance(
                backgroundColor = darkPalette.topBarBg.copy(alpha = 0.96f),
                borderColor = darkPalette.markdownDivider.copy(alpha = 0.52f),
                itemHoverColor = darkPalette.accent.copy(alpha = 0.14f),
                textColor = darkPalette.textPrimary,
                hoveredTextColor = darkPalette.accent,
            ),
            conversationContextMenuAppearance(darkPalette),
        )
    }

    @Test
    fun `markdown selection colors deepen for both light and dark message surfaces`() {
        val lightPalette = assistantPalette(EffectiveTheme.LIGHT)
        val darkPalette = assistantPalette(EffectiveTheme.DARK)

        assertEquals(Color(0xFF7E9CCB).copy(alpha = 0.38f), conversationMarkdownSelectionBackground(lightPalette))
        assertEquals(lightPalette.accent, conversationMarkdownSelectionHandle(lightPalette))
        assertEquals(Color(0xFF6E87AE).copy(alpha = 0.58f), conversationMarkdownSelectionBackground(darkPalette))
        assertEquals(Color(0xFF86B3FF), conversationMarkdownSelectionHandle(darkPalette))
    }

    @Test
    fun `command panel selection colors stay dense on the dark shell surface`() {
        assertEquals(Color(0xFF6F86AE).copy(alpha = 0.64f), conversationCommandSelectionBackground())
        assertEquals(Color(0xFF8FBCFF), conversationCommandSelectionHandle())
    }

    @Test
    fun `quick scroll controls stay hidden when overlay is inactive`() {
        val hidden = conversationQuickScrollVisibility(
            overlayVisible = false,
            canScrollToTop = true,
            canScrollToBottom = true,
        )

        assertFalse(hidden.showTop)
        assertFalse(hidden.showBottom)
    }

    @Test
    fun `quick scroll controls hide unavailable directions`() {
        val topOnly = conversationQuickScrollVisibility(
            overlayVisible = true,
            canScrollToTop = false,
            canScrollToBottom = true,
        )
        val bottomOnly = conversationQuickScrollVisibility(
            overlayVisible = true,
            canScrollToTop = true,
            canScrollToBottom = false,
        )
        val both = conversationQuickScrollVisibility(
            overlayVisible = true,
            canScrollToTop = true,
            canScrollToBottom = true,
        )

        assertFalse(topOnly.showTop)
        assertTrue(topOnly.showBottom)
        assertTrue(bottomOnly.showTop)
        assertFalse(bottomOnly.showBottom)
        assertTrue(both.showTop)
        assertTrue(both.showBottom)
    }

    @Test
    fun `near bottom requires the last item to be actually close to viewport bottom`() {
        val pinned = ConversationBottomSnapshot(
            totalItemsCount = 8,
            lastVisibleItemIndex = 7,
            lastVisibleItemOffset = 420,
            lastVisibleItemSize = 160,
            viewportEndOffset = 600,
        )
        val drifting = pinned.copy(
            lastVisibleItemOffset = 420,
            lastVisibleItemSize = 240,
        )

        assertTrue(conversationIsNearBottom(pinned))
        assertFalse(conversationIsNearBottom(drifting))
    }

    @Test
    fun `bottom scroll strategy adjusts visible tail when last item already remains on screen`() {
        val snapshot = ConversationBottomSnapshot(
            totalItemsCount = 8,
            lastVisibleItemIndex = 7,
            lastVisibleItemOffset = 380,
            lastVisibleItemSize = 320,
            viewportEndOffset = 600,
        )

        assertEquals(
            ConversationBottomScrollStrategy.ADJUST_VISIBLE_TAIL,
            conversationBottomScrollStrategy(snapshot),
        )
    }

    @Test
    fun `bottom scroll strategy reveals last item when tail is not yet visible`() {
        val snapshot = ConversationBottomSnapshot(
            totalItemsCount = 8,
            lastVisibleItemIndex = 6,
            lastVisibleItemOffset = 360,
            lastVisibleItemSize = 180,
            viewportEndOffset = 600,
        )

        assertEquals(
            ConversationBottomScrollStrategy.REVEAL_LAST_ITEM,
            conversationBottomScrollStrategy(snapshot),
        )
    }

    @Test
    fun `bottom overflow reports remaining pixels below the viewport`() {
        val hiddenTail = ConversationBottomSnapshot(
            totalItemsCount = 8,
            lastVisibleItemIndex = 7,
            lastVisibleItemOffset = 420,
            lastVisibleItemSize = 240,
            viewportEndOffset = 600,
        )
        val fullyVisible = hiddenTail.copy(lastVisibleItemSize = 180)
        val nonTerminal = hiddenTail.copy(lastVisibleItemIndex = 6)

        assertEquals(60, conversationBottomOverflowPx(hiddenTail))
        assertEquals(0, conversationBottomOverflowPx(fullyVisible))
        assertEquals(0, conversationBottomOverflowPx(nonTerminal))
    }

    @Test
    fun `timeline follow mode ignores programmatic settle when tall tail is still growing`() {
        val resolution = conversationResolveAutoFollow(
            wasAutoFollowEnabled = true,
            isScrollInProgress = false,
            isNearBottom = false,
            hadProgrammaticScroll = true,
        )

        assertTrue(resolution.autoFollowEnabled)
        assertFalse(resolution.hadProgrammaticScroll)
    }

    @Test
    fun `timeline follow mode disables after manual upward scroll settles away from bottom`() {
        val resolution = conversationResolveAutoFollow(
            wasAutoFollowEnabled = true,
            isScrollInProgress = false,
            isNearBottom = false,
            hadProgrammaticScroll = false,
        )

        assertFalse(resolution.autoFollowEnabled)
        assertFalse(resolution.hadProgrammaticScroll)
    }

    @Test
    fun `timeline follow mode preserves state while scrolling is still in progress`() {
        val resolution = conversationResolveAutoFollow(
            wasAutoFollowEnabled = true,
            isScrollInProgress = true,
            isNearBottom = false,
            hadProgrammaticScroll = true,
        )

        assertTrue(resolution.autoFollowEnabled)
        assertTrue(resolution.hadProgrammaticScroll)
    }

    @Test
    fun `prompt accepted requests force bottom only once per version`() {
        assertTrue(
            conversationHasPendingPromptScrollRequest(
                requestVersion = 2L,
                handledVersion = 1L,
            ),
        )
        assertFalse(
            conversationHasPendingPromptScrollRequest(
                requestVersion = 2L,
                handledVersion = 2L,
            ),
        )
    }
}
