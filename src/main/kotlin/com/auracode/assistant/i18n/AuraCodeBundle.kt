package com.auracode.assistant.i18n

import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.settings.UiLanguageMode
import com.intellij.DynamicBundle
import com.intellij.openapi.application.ApplicationManager
import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

internal object AuraCodeBundle {
    private const val BUNDLE_NAME = "messages.AuraCodeBundle"

    fun message(key: String, vararg args: Any): String {
        val locale = resolveLocale()
        val bundle = resolveBundle(locale)
        val pattern = bundle.getString(key)
        if (args.isEmpty()) return pattern
        return MessageFormat(pattern, locale).format(args)
    }

    private fun resolveLocale(): Locale {
        val ideLocale = runCatching { DynamicBundle.getLocale() }.getOrElse { Locale.getDefault() }
        return AuraCodeLocaleResolver.resolveLocale(resolveUiLanguageMode(), ideLocale)
    }

    private fun resolveUiLanguageMode(): UiLanguageMode {
        val app = runCatching { ApplicationManager.getApplication() }.getOrNull() ?: return UiLanguageMode.FOLLOW_IDE
        if (app.isDisposed) return UiLanguageMode.FOLLOW_IDE
        val service = runCatching { app.getService(AgentSettingsService::class.java) }.getOrNull()
        return service?.uiLanguageMode() ?: UiLanguageMode.FOLLOW_IDE
    }

    private fun resolveBundle(locale: Locale): ResourceBundle {
        val loader = javaClass.classLoader
        val candidateNames = AuraCodeLocaleResolver.bundleCandidates(BUNDLE_NAME, locale)
        candidateNames.forEach { bundleName ->
            try {
                return ResourceBundle.getBundle(bundleName, Locale.ROOT, loader)
            } catch (_: MissingResourceException) {
            }
        }
        return ResourceBundle.getBundle(BUNDLE_NAME, Locale.ROOT, loader)
    }
}
