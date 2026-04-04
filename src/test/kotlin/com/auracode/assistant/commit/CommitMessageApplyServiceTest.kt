package com.auracode.assistant.commit

import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.vcs.commit.CommitMessageUi
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitMessageApplyServiceTest {
    @Test
    fun `apply overwrites both commit controls`() {
        val service = CommitMessageApplyService()
        val control = RecordingCommitMessageControl()
        val uiState = CommitMessageUiState()
        val ui = Proxy.newProxyInstance(
            CommitMessageUi::class.java.classLoader,
            arrayOf(CommitMessageUi::class.java),
        ) { _, method, args ->
            when (method.name) {
                "setText" -> {
                    uiState.textValue = args?.firstOrNull() as? String ?: ""
                    Unit
                }
                "getText" -> uiState.textValue
                "focus", "startLoading", "stopLoading" -> Unit
                else -> error("Unexpected method: ${method.name}")
            }
        } as CommitMessageUi

        service.apply(
            message = "feat: add Aura commit message generation",
            commitMessageControl = control,
            commitMessageUi = ui,
        )

        assertEquals("feat: add Aura commit message generation", control.message)
        assertEquals("feat: add Aura commit message generation", uiState.textValue)
    }

    private class RecordingCommitMessageControl : CommitMessageI {
        var message: String = ""

        override fun setCommitMessage(commitMessage: String) {
            message = commitMessage
        }
    }

    private class CommitMessageUiState {
        var textValue: String = ""
    }
}
