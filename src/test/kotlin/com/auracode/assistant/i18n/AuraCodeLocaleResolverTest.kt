package com.auracode.assistant.i18n

import com.auracode.assistant.settings.UiLanguageMode
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class AuraCodeLocaleResolverTest {
    @Test
    fun `maps explicit language modes to supported locales`() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, AuraCodeLocaleResolver.resolveLocale(UiLanguageMode.ZH, Locale.ENGLISH))
        assertEquals(Locale.ENGLISH, AuraCodeLocaleResolver.resolveLocale(UiLanguageMode.EN, Locale.JAPANESE))
        assertEquals(Locale.JAPANESE, AuraCodeLocaleResolver.resolveLocale(UiLanguageMode.JA, Locale.ENGLISH))
        assertEquals(Locale.KOREAN, AuraCodeLocaleResolver.resolveLocale(UiLanguageMode.KO, Locale.ENGLISH))
    }

    @Test
    fun `follow ide normalizes supported locales and falls back to english`() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, AuraCodeLocaleResolver.resolveLocale(UiLanguageMode.FOLLOW_IDE, Locale.TRADITIONAL_CHINESE))
        assertEquals(Locale.JAPANESE, AuraCodeLocaleResolver.resolveLocale(UiLanguageMode.FOLLOW_IDE, Locale.JAPAN))
        assertEquals(Locale.KOREAN, AuraCodeLocaleResolver.resolveLocale(UiLanguageMode.FOLLOW_IDE, Locale.KOREA))
        assertEquals(Locale.ENGLISH, AuraCodeLocaleResolver.resolveLocale(UiLanguageMode.FOLLOW_IDE, Locale.FRENCH))
    }

    @Test
    fun `builds bundle candidates for supported locales`() {
        assertEquals(
            listOf("messages.AuraCodeBundle_ja", "messages.AuraCodeBundle"),
            AuraCodeLocaleResolver.bundleCandidates("messages.AuraCodeBundle", Locale.JAPANESE),
        )
        assertEquals(
            listOf("messages.AuraCodeBundle_ko", "messages.AuraCodeBundle"),
            AuraCodeLocaleResolver.bundleCandidates("messages.AuraCodeBundle", Locale.KOREAN),
        )
    }
}
