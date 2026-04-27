package com.auracode.assistant.toolwindow.execution

import com.auracode.assistant.toolwindow.eventing.SubmissionMode
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanCompletionPromptModelsTest {
    @Test
    fun `compact preview extracts title and skips section headers`() {
        val preview = prompt(
            """
            # 新建建造者模式 Demo（Java）

            Summary

            在当前目录新增一个独立的 Java 示例文件，演示标准建造者模式结构。

            Key Changes

            1. 新增 `BuilderPatternDemo.java`
            2. 包含 Product、Builder、Director、main
            """.trimIndent(),
        ).compactPreview()

        assertEquals("新建建造者模式 Demo（Java）", preview.title)
        assertEquals(
            "在当前目录新增一个独立的 Java 示例文件，演示标准建造者模式结构。 新增 BuilderPatternDemo.java",
            preview.summary,
        )
    }

    @Test
    fun `compact preview falls back to checklist content when plan body is only steps`() {
        val preview = prompt(
            """
            - [pending] 实现 plan 模式
            - [pending] 保持审批弹窗独立
            """.trimIndent(),
        ).compactPreview()

        assertEquals("实现 plan 模式", preview.title)
        assertEquals("保持审批弹窗独立", preview.summary)
    }

    private fun prompt(body: String): PlanCompletionPromptUiModel {
        return PlanCompletionPromptUiModel(
            turnId = "turn-1",
            threadId = "thread-1",
            body = body,
            preferredExecutionMode = SubmissionMode.APPROVAL,
        )
    }
}
