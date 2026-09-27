package com.vasmarfas.card.core

// both properties are read-only, so own getters shadow them: ui-text reads languages[0], compose-resources language
actual fun applyPlatformLocale(tag: String) {
    js(
        """{
        Object.defineProperty(navigator, 'language', { get: function () { return tag; }, configurable: true });
        Object.defineProperty(navigator, 'languages', { get: function () { return [tag]; }, configurable: true });
    }"""
    )
}

private fun jsRegionName(code: String, lang: String): String? =
    js("{ try { return new Intl.DisplayNames([lang], { type: 'region' }).of(code) || null; } catch (e) { return null; } }")

actual fun regionName(code: String, lang: Lang): String? = jsRegionName(code, lang.code)?.takeIf { it != code }
