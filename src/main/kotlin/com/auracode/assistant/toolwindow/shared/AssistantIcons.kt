package com.auracode.assistant.toolwindow.shared

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

internal object AssistantIcons {
    val send: Icon = load("/icons/send.svg")
    val stop: Icon = load("/icons/stop.svg")
    val codex: Icon = load("/icons/codex.svg")
    val gpt: Icon = load("/icons/gpt.svg")
    val autoMode: Icon = load("/icons/auto-mode.svg")
    val autoModeOff: Icon = load("/icons/auto-mode-off.svg")
    val arrowDown: Icon = load("/icons/arrow-down.svg")
    val arrowUp: Icon = load("/icons/arrow-up.svg")
    val attachFile: Icon = load("/icons/attach-file.svg")
    val closeSmall: Icon = load("/icons/close-small.svg")
    val reasoningLow: Icon = load("/icons/stat_0.svg")
    val reasoningMedium: Icon = load("/icons/stat_1.svg")
    val reasoningHigh: Icon = load("/icons/stat_2.svg")
    val reasoningMax: Icon = load("/icons/stat_3.svg")

    private fun load(path: String): Icon = IconLoader.getIcon(path, AssistantIcons::class.java)
}
