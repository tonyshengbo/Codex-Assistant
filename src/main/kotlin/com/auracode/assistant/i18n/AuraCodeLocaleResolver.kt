package com.auracode.assistant.i18n

import com.auracode.assistant.settings.UiLanguageMode
import java.util.Locale

/**
 * Resolves the effective UI locale and resource bundle candidates for Aura Code.
 */
internal object AuraCodeLocaleResolver {
    /**
     * Maps the configured language mode to the effective locale.
     */
    fun resolveLocale(
        mode: UiLanguageMode,
        ideLocale: Locale,
    ): Locale {
        return when (mode) {
            UiLanguageMode.ZH -> Locale.SIMPLIFIED_CHINESE
            UiLanguageMode.EN -> Locale.ENGLISH
            UiLanguageMode.JA -> Locale.JAPANESE
            UiLanguageMode.KO -> Locale.KOREAN
            UiLanguageMode.FOLLOW_IDE -> normalizeIdeLocale(ideLocale)
        }
    }

    /**
     * Returns resource bundle names ordered by priority for the given locale.
     */
    fun bundleCandidates(
        baseName: String,
        locale: Locale,
    ): List<String> {
        return when (locale.language.lowercase(Locale.ROOT)) {
            Locale.SIMPLIFIED_CHINESE.language -> listOf("${baseName}_zh", baseName)
            Locale.JAPANESE.language -> listOf("${baseName}_ja", baseName)
            Locale.KOREAN.language -> listOf("${baseName}_ko", baseName)
            Locale.ENGLISH.language -> listOf(baseName)
            else -> listOf("${baseName}_${locale.language.lowercase(Locale.ROOT)}", baseName)
        }
    }

    /**
     * Normalizes the IDE locale into one of the supported UI locales.
     */
    fun normalizeIdeLocale(locale: Locale): Locale {
        return when (locale.language.lowercase(Locale.ROOT)) {
            Locale.SIMPLIFIED_CHINESE.language -> Locale.SIMPLIFIED_CHINESE
            Locale.JAPANESE.language -> Locale.JAPANESE
            Locale.KOREAN.language -> Locale.KOREAN
            else -> Locale.ENGLISH
        }
    }
}
