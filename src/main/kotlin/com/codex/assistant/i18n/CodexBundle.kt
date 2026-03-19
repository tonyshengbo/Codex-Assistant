package com.codex.assistant.i18n

import com.codex.assistant.settings.AgentSettingsService
import com.codex.assistant.settings.UiLanguageMode
import com.intellij.DynamicBundle
import com.intellij.openapi.application.ApplicationManager
import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

internal object CodexBundle {
    private const val BUNDLE_NAME = "messages.CodexBundle"

    fun message(key: String, vararg args: Any): String {
        val locale = resolveLocale()
        val bundle = resolveBundle(locale)
        val pattern = bundle.getString(key)
        if (args.isEmpty()) return pattern
        return MessageFormat(pattern, locale).format(args)
    }

    private fun resolveLocale(): Locale {
        return when (resolveUiLanguageMode()) {
            UiLanguageMode.ZH -> Locale.SIMPLIFIED_CHINESE
            UiLanguageMode.EN -> Locale.ENGLISH
            UiLanguageMode.FOLLOW_IDE -> {
                val ideLocale = runCatching { DynamicBundle.getLocale() }.getOrElse { Locale.getDefault() }
                if (ideLocale.language.startsWith("zh", ignoreCase = true)) {
                    Locale.SIMPLIFIED_CHINESE
                } else {
                    Locale.ENGLISH
                }
            }
        }
    }

    private fun resolveUiLanguageMode(): UiLanguageMode {
        val app = runCatching { ApplicationManager.getApplication() }.getOrNull() ?: return UiLanguageMode.FOLLOW_IDE
        if (app.isDisposed) return UiLanguageMode.FOLLOW_IDE
        val service = runCatching { app.getService(AgentSettingsService::class.java) }.getOrNull()
        return service?.uiLanguageMode() ?: UiLanguageMode.FOLLOW_IDE
    }

    private fun resolveBundle(locale: Locale): ResourceBundle {
        val loader = javaClass.classLoader
        val candidateNames = when (locale.language.lowercase(Locale.ROOT)) {
            Locale.SIMPLIFIED_CHINESE.language -> listOf("${BUNDLE_NAME}_zh", BUNDLE_NAME)
            Locale.ENGLISH.language -> listOf(BUNDLE_NAME)
            else -> listOf("${BUNDLE_NAME}_${locale.language.lowercase(Locale.ROOT)}", BUNDLE_NAME)
        }
        candidateNames.forEach { bundleName ->
            try {
                return ResourceBundle.getBundle(bundleName, Locale.ROOT, loader)
            } catch (_: MissingResourceException) {
            }
        }
        return ResourceBundle.getBundle(BUNDLE_NAME, Locale.ROOT, loader)
    }
}
