package com.auracode.assistant.toolwindow.settings

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/** Stores the static metadata displayed by the About settings page. */
internal object AboutPluginInfo {
    const val pluginId: String = "com.auracode.assistant"
    const val defaultVersion: String = "1.0.0"
    const val repositoryUrl: String = "https://github.com/tonyshengbo/Aura"
    const val communityJoinUrl: String = "https://qm.qq.com/cgi-bin/qm/qr?k=PluvekRnnoJlIo8MHKNbs8OwGXaFv7c3&jump_from=webapi&authKey=vVjBYa4mKyXwFCQGsJ6GpJR4uD18XmPX2kgiKeSxqVgE8At1RPjYWRe8IW8Yv+xc"

    /** Resolves the installed plugin version and falls back when unavailable. */
    fun pluginVersion(): String {
        val rawVersion = PluginManagerCore.getPlugin(PluginId.getId(pluginId))?.version
        return normalizeVersion(rawVersion = rawVersion)
    }
}

/** Normalizes a raw version string into a non-blank version label. */
internal fun normalizeVersion(
    rawVersion: String?,
    fallbackVersion: String = AboutPluginInfo.defaultVersion,
): String {
    return rawVersion?.trim().orEmpty().ifBlank { fallbackVersion }
}
