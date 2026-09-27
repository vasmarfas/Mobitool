package com.vasmarfas.card.core

// Compose Resources reads the language from the platform, so the switch moves the platform locale
expect fun applyPlatformLocale(tag: String)

expect fun regionName(code: String, lang: Lang): String?
