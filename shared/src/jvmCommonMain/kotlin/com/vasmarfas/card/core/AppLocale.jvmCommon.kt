package com.vasmarfas.card.core

import java.util.Locale

private var chosen: Locale? = null

actual fun applyPlatformLocale(tag: String) {
    chosen = Locale.forLanguageTag(tag).also(Locale::setDefault)
}

fun reapplyPlatformLocale() {
    chosen?.let(Locale::setDefault)
}

actual fun regionName(code: String, lang: Lang): String? =
    Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.forLanguageTag(lang.code)).takeIf { it.isNotEmpty() && it != code }
