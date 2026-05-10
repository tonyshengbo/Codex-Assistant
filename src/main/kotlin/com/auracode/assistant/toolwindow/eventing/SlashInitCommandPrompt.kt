package com.auracode.assistant.toolwindow.eventing

/**
 * Loads the shared `/init` prompt from bundled resources so every provider uses
 * the same contributor-guide generation instructions.
 */
internal object SlashInitCommandPrompt {
    private const val RESOURCE_PATH: String = "/prompts/init-command.md"

    /** Returns the normalized prompt text that Aura submits for the `/init` command. */
    fun load(): String {
        val stream = checkNotNull(javaClass.getResourceAsStream(RESOURCE_PATH)) {
            "Missing init prompt resource at $RESOURCE_PATH"
        }
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText().trim()
        }
    }
}
