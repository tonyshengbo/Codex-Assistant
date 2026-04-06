package com.auracode.assistant.toolwindow.drawer.settings

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

internal object AboutPluginInfo {
    const val pluginId: String = "com.auracode.assistant"
    const val defaultVersion: String = "1.0.0"
    const val repositoryUrl: String = "https://github.com/tonyshengbo/Aura"
    const val qqGroupNumber: String = "1097916033"

    fun pluginVersion(): String {
        val rawVersion = PluginManagerCore.getPlugin(PluginId.getId(pluginId))?.version
        return normalizeVersion(rawVersion = rawVersion)
    }
}

internal fun normalizeVersion(
    rawVersion: String?,
    fallbackVersion: String = AboutPluginInfo.defaultVersion,
): String {
    return rawVersion?.trim().orEmpty().ifBlank { fallbackVersion }
}
