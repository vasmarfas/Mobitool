package com.vasmarfas.card.data

import com.vasmarfas.card.core.Lang
import com.vasmarfas.card.core.appLang
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsLocaleTest {
    @Test
    fun onlyAPickedLanguageMovesThePlatformLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("de-DE"))
            assertEquals(Lang.EN, AppSettings(MemoryStore()).lang)
            assertEquals("de-DE", Locale.getDefault().toLanguageTag())
            assertEquals(Lang.RU, AppSettings(MemoryStore("settings.lang" to "ru")).lang)
            assertEquals("ru", Locale.getDefault().toLanguageTag())
        } finally {
            Locale.setDefault(previous)
            appLang = Lang.EN
        }
    }
}
