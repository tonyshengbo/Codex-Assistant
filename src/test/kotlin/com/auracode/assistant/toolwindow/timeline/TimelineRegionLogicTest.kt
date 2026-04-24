package com.auracode.assistant.toolwindow.timeline

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

class TimelineRegionLogicTest {
    @Test
    fun `reasoning timeline title uses thinking label`() {
        assertEquals("Thinking", AuraCodeBundle.message("timeline.reasoning"))
    }

    @Test
    fun `user message node is recognized by role`() {
        val userItem = TimelineNode.MessageNode(
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
        val toolItem = TimelineNode.ToolCallNode(
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
        assertEquals(220.dp, timelineExpandedBodyMaxHeight())
    }

    @Test
    fun `plan activity body has no max height cap`() {
        assertEquals(null, timelinePlanExpandedBodyMaxHeight())
    }

    @Test
    fun `plan node uses compact title and checklist count badge`() {
        assertEquals("Plan", timelinePlanDisplayTitle())
        assertEquals("3 steps", timelinePlanBadgeText("- [x] One\n- [ ] Two\n- [done] Three"))
        assertEquals(null, timelinePlanBadgeText("Summary only"))
    }

    @Test
    fun `plan collapsed summary prefers first checklist item`() {
        assertEquals(
            "Ship protocol support",
            timelinePlanCollapsedSummary(
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
            timelinePlanCollapsedSummary(
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
            timelinePlanCollapsedSummary(
                """
                # Plan

                Tighten the collapsed hierarchy.

                More detail here.
                """.trimIndent(),
            ),
        )
        assertEquals(null, timelinePlanCollapsedSummary("# Plan\n## Summary"))
    }

    @Test
    fun `file change diff lines map to restrained preview colors`() {
        assertEquals(TimelineDiffLineKind.HUNK, timelineDiffLineKind("@@ -1 +1 @@"))
        assertEquals(TimelineDiffLineKind.ADDITION, timelineDiffLineKind("+fun b() = 2"))
        assertEquals(TimelineDiffLineKind.DELETION, timelineDiffLineKind("-fun b() = 1"))
        assertEquals(TimelineDiffLineKind.META, timelineDiffLineKind("+++ b/src/Main.kt"))
        assertEquals(TimelineDiffLineKind.CONTEXT, timelineDiffLineKind(" fun a() = 1"))
    }

    @Test
    fun `command panel content keeps command and raw output separate`() {
        val panel = timelineCommandPanelContent(
            commandText = "./gradlew --no-daemon test",
            outputText = "> Task :prepareTest\n> Task :test",
        )

        assertEquals("Shell", panel.label)
        assertEquals("./gradlew --no-daemon test", panel.commandText)
        assertEquals("> Task :prepareTest\n> Task :test", panel.outputText)
    }

    @Test
    fun `timeline copy text returns plain text for supported node types`() {
        val message = TimelineNode.MessageNode(
            id = "message",
            sourceId = "message",
            role = MessageRole.USER,
            text = "ship timeline copy",
            status = ItemStatus.SUCCESS,
            timestamp = null,
            turnId = null,
            cursor = null,
        )
        val plan = TimelineNode.PlanNode(
            id = "plan",
            sourceId = "plan",
            title = "Plan",
            body = "- [ ] Add copy action\n- [ ] Polish hover state",
            status = ItemStatus.SUCCESS,
            turnId = null,
        )
        val command = TimelineNode.CommandNode(
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

        assertEquals("ship timeline copy", timelineNodeCopyText(message))
        assertEquals("- [ ] Add copy action\n- [ ] Polish hover state", timelineNodeCopyText(plan))
        assertEquals(
            "Run tests\n\n./gradlew test\n\n> Task :test",
            timelineNodeCopyText(command),
        )
    }

    @Test
    fun `timeline copy text skips unsupported or blank nodes`() {
        val fileChange = TimelineNode.FileChangeNode(
            id = "diff",
            sourceId = "diff",
            title = "Diff",
            changes = emptyList(),
            collapsedSummary = null,
            status = ItemStatus.SUCCESS,
            turnId = null,
        )
        val blankMessage = TimelineNode.MessageNode(
            id = "blank-message",
            sourceId = "blank-message",
            role = MessageRole.USER,
            text = "   ",
            status = ItemStatus.SUCCESS,
            timestamp = null,
            turnId = null,
            cursor = null,
        )
        val loadMore = TimelineNode.LoadMoreNode(isLoading = false)

        assertEquals(null, timelineNodeCopyText(fileChange))
        assertEquals(null, timelineNodeCopyText(blankMessage))
        assertEquals(null, timelineNodeCopyText(loadMore))
    }

    @Test
    fun `command copy body joins only non blank sections with blank lines`() {
        assertEquals(
            "Plan command\n\n./gradlew test\n\n> Task :test",
            timelineCommandCopyBody(
                title = "Plan command",
                commandText = "./gradlew test",
                outputText = "> Task :test",
            ),
        )
        assertEquals(
            "./gradlew test",
            timelineCommandCopyBody(
                title = null,
                commandText = "./gradlew test",
                outputText = "   ",
            ),
        )
    }

    @Test
    fun `command panel near bottom only when scroll tail stays within threshold`() {
        val pinned = TimelineCommandScrollSnapshot(
            currentOffset = 396,
            maxOffset = 420,
        )
        val drifting = pinned.copy(currentOffset = 340)

        assertTrue(timelineCommandIsNearBottom(pinned))
        assertFalse(timelineCommandIsNearBottom(drifting))
    }

    @Test
    fun `command panel bottom overflow reports remaining scroll distance`() {
        val hiddenTail = TimelineCommandScrollSnapshot(
            currentOffset = 280,
            maxOffset = 420,
        )
        val pinned = hiddenTail.copy(currentOffset = 420)

        assertEquals(140, timelineCommandBottomOverflowPx(hiddenTail))
        assertEquals(0, timelineCommandBottomOverflowPx(pinned))
    }

    @Test
    fun `command panel follow mode only changes after scrolling settles`() {
        assertTrue(
            timelineCommandResolveAutoFollow(
                wasAutoFollowEnabled = true,
                isScrollInProgress = true,
                isNearBottom = false,
            ),
        )
        assertFalse(
            timelineCommandResolveAutoFollow(
                wasAutoFollowEnabled = true,
                isScrollInProgress = false,
                isNearBottom = false,
            ),
        )
        assertTrue(
            timelineCommandResolveAutoFollow(
                wasAutoFollowEnabled = false,
                isScrollInProgress = false,
                isNearBottom = true,
            ),
        )
    }

    @Test
    fun `timeline text context menu exposes copy only when selection exists`() {
        var copied = false

        val present = timelineTextContextMenuItems(
            copyLabel = "Copy",
            onCopy = { copied = true },
        )
        val absent = timelineTextContextMenuItems(
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
        assertEquals(AuraCodeBundle.message("timeline.copy"), timelineCopyMenuLabel())
    }

    @Test
    fun `timeline context menu appearance follows shared palette chrome`() {
        val lightPalette = assistantPalette(EffectiveTheme.LIGHT)
        val darkPalette = assistantPalette(EffectiveTheme.DARK)

        assertEquals(
            TimelineContextMenuAppearance(
                backgroundColor = lightPalette.topBarBg.copy(alpha = 0.96f),
                borderColor = lightPalette.markdownDivider.copy(alpha = 0.52f),
                itemHoverColor = lightPalette.accent.copy(alpha = 0.14f),
                textColor = lightPalette.textPrimary,
                hoveredTextColor = lightPalette.accent,
            ),
            timelineContextMenuAppearance(lightPalette),
        )
        assertEquals(
            TimelineContextMenuAppearance(
                backgroundColor = darkPalette.topBarBg.copy(alpha = 0.96f),
                borderColor = darkPalette.markdownDivider.copy(alpha = 0.52f),
                itemHoverColor = darkPalette.accent.copy(alpha = 0.14f),
                textColor = darkPalette.textPrimary,
                hoveredTextColor = darkPalette.accent,
            ),
            timelineContextMenuAppearance(darkPalette),
        )
    }

    @Test
    fun `markdown selection colors deepen for both light and dark message surfaces`() {
        val lightPalette = assistantPalette(EffectiveTheme.LIGHT)
        val darkPalette = assistantPalette(EffectiveTheme.DARK)

        assertEquals(Color(0xFF7E9CCB).copy(alpha = 0.38f), timelineMarkdownSelectionBackground(lightPalette))
        assertEquals(lightPalette.accent, timelineMarkdownSelectionHandle(lightPalette))
        assertEquals(Color(0xFF6E87AE).copy(alpha = 0.58f), timelineMarkdownSelectionBackground(darkPalette))
        assertEquals(Color(0xFF86B3FF), timelineMarkdownSelectionHandle(darkPalette))
    }

    @Test
    fun `command panel selection colors stay dense on the dark shell surface`() {
        assertEquals(Color(0xFF6F86AE).copy(alpha = 0.64f), timelineCommandSelectionBackground())
        assertEquals(Color(0xFF8FBCFF), timelineCommandSelectionHandle())
    }

    @Test
    fun `quick scroll controls stay hidden when overlay is inactive`() {
        val hidden = timelineQuickScrollVisibility(
            overlayVisible = false,
            canScrollToTop = true,
            canScrollToBottom = true,
        )

        assertFalse(hidden.showTop)
        assertFalse(hidden.showBottom)
    }

    @Test
    fun `quick scroll controls hide unavailable directions`() {
        val topOnly = timelineQuickScrollVisibility(
            overlayVisible = true,
            canScrollToTop = false,
            canScrollToBottom = true,
        )
        val bottomOnly = timelineQuickScrollVisibility(
            overlayVisible = true,
            canScrollToTop = true,
            canScrollToBottom = false,
        )
        val both = timelineQuickScrollVisibility(
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
        val pinned = TimelineBottomSnapshot(
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

        assertTrue(timelineIsNearBottom(pinned))
        assertFalse(timelineIsNearBottom(drifting))
    }

    @Test
    fun `bottom scroll strategy adjusts visible tail when last item already remains on screen`() {
        val snapshot = TimelineBottomSnapshot(
            totalItemsCount = 8,
            lastVisibleItemIndex = 7,
            lastVisibleItemOffset = 380,
            lastVisibleItemSize = 320,
            viewportEndOffset = 600,
        )

        assertEquals(
            TimelineBottomScrollStrategy.ADJUST_VISIBLE_TAIL,
            timelineBottomScrollStrategy(snapshot),
        )
    }

    @Test
    fun `bottom scroll strategy reveals last item when tail is not yet visible`() {
        val snapshot = TimelineBottomSnapshot(
            totalItemsCount = 8,
            lastVisibleItemIndex = 6,
            lastVisibleItemOffset = 360,
            lastVisibleItemSize = 180,
            viewportEndOffset = 600,
        )

        assertEquals(
            TimelineBottomScrollStrategy.REVEAL_LAST_ITEM,
            timelineBottomScrollStrategy(snapshot),
        )
    }

    @Test
    fun `bottom overflow reports remaining pixels below the viewport`() {
        val hiddenTail = TimelineBottomSnapshot(
            totalItemsCount = 8,
            lastVisibleItemIndex = 7,
            lastVisibleItemOffset = 420,
            lastVisibleItemSize = 240,
            viewportEndOffset = 600,
        )
        val fullyVisible = hiddenTail.copy(lastVisibleItemSize = 180)
        val nonTerminal = hiddenTail.copy(lastVisibleItemIndex = 6)

        assertEquals(60, timelineBottomOverflowPx(hiddenTail))
        assertEquals(0, timelineBottomOverflowPx(fullyVisible))
        assertEquals(0, timelineBottomOverflowPx(nonTerminal))
    }

    @Test
    fun `timeline follow mode ignores programmatic settle when tall tail is still growing`() {
        val resolution = timelineResolveAutoFollow(
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
        val resolution = timelineResolveAutoFollow(
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
        val resolution = timelineResolveAutoFollow(
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
            timelineHasPendingPromptScrollRequest(
                requestVersion = 2L,
                handledVersion = 1L,
            ),
        )
        assertFalse(
            timelineHasPendingPromptScrollRequest(
                requestVersion = 2L,
                handledVersion = 2L,
            ),
        )
    }
}
