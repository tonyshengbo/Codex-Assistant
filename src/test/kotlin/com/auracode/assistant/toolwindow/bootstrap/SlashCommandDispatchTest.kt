package com.auracode.assistant.toolwindow.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class SlashCommandDispatchTest {
    @Test
    fun `new slash command is routed through session creation flow`() {
        assertEquals(SlashCommandDispatch.START_NEW_SESSION, resolveSlashCommandDispatch("/new"))
        assertEquals(SlashCommandDispatch.START_NEW_SESSION, resolveSlashCommandDispatch("new"))
    }

    @Test
    fun `non session slash commands stay on the local publish path`() {
        assertEquals(SlashCommandDispatch.PUBLISH_ONLY, resolveSlashCommandDispatch("/plan"))
        assertEquals(SlashCommandDispatch.PUBLISH_ONLY, resolveSlashCommandDispatch("/auto"))
    }
}
