package com.codex.assistant.service

internal object ModelContextWindows {
    private const val GPT_5_CONTEXT_WINDOW = 400_000
    private const val GPT_4_1_CONTEXT_WINDOW = 1_047_576

    fun resolve(model: String?): Int {
        val normalized = model?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank() || normalized == "auto") {
            return 0
        }
        return when {
            normalized.startsWith("gpt-4.1") -> GPT_4_1_CONTEXT_WINDOW
            normalized.startsWith("gpt-5") -> GPT_5_CONTEXT_WINDOW
            else -> 0
        }
    }
}
