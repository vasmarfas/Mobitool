package com.vasmarfas.card.tools.text

object TextCleaner {
    private val diacritics: Map<Char, String> = buildMap {
        "ÀÁÂÃÄÅĀĂĄ".forEach { put(it, "A") }
        "àáâãäåāăą".forEach { put(it, "a") }
        "ÇĆĈĊČ".forEach { put(it, "C") }
        "çćĉċč".forEach { put(it, "c") }
        "ĎĐ".forEach { put(it, "D") }
        "ďđ".forEach { put(it, "d") }
        "ÈÉÊËĒĔĖĘĚ".forEach { put(it, "E") }
        "èéêëēĕėęě".forEach { put(it, "e") }
        "ĜĞĠĢ".forEach { put(it, "G") }
        "ĝğġģ".forEach { put(it, "g") }
        "ĤĦ".forEach { put(it, "H") }
        "ĥħ".forEach { put(it, "h") }
        "ÌÍÎÏĨĪĬĮİ".forEach { put(it, "I") }
        "ìíîïĩīĭįı".forEach { put(it, "i") }
        put('Ĵ', "J"); put('ĵ', "j")
        put('Ķ', "K"); put('ķ', "k")
        "ĹĻĽĿŁ".forEach { put(it, "L") }
        "ĺļľŀł".forEach { put(it, "l") }
        "ÑŃŅŇ".forEach { put(it, "N") }
        "ñńņň".forEach { put(it, "n") }
        "ÒÓÔÕÖØŌŎŐ".forEach { put(it, "O") }
        "òóôõöøōŏő".forEach { put(it, "o") }
        "ŔŖŘ".forEach { put(it, "R") }
        "ŕŗř".forEach { put(it, "r") }
        "ŚŜŞŠ".forEach { put(it, "S") }
        "śŝşš".forEach { put(it, "s") }
        "ŢŤŦ".forEach { put(it, "T") }
        "ţťŧ".forEach { put(it, "t") }
        "ÙÚÛÜŨŪŬŮŰŲ".forEach { put(it, "U") }
        "ùúûüũūŭůűų".forEach { put(it, "u") }
        put('Ŵ', "W"); put('ŵ', "w")
        "ÝŶŸ".forEach { put(it, "Y") }
        "ýÿŷ".forEach { put(it, "y") }
        "ŹŻŽ".forEach { put(it, "Z") }
        "źżž".forEach { put(it, "z") }
        put('Æ', "AE"); put('æ', "ae"); put('Œ', "OE"); put('œ', "oe"); put('ß', "ss"); put('Þ', "Th"); put('þ', "th"); put('Ð', "D"); put('ð', "d")
    }

    fun removeDiacritics(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(diacritics[c] ?: c.toString())
        return sb.toString()
    }

    fun collapseWhitespace(text: String): String = text.replace(Regex("[ \\t\\u00A0]+"), " ").replace(Regex(" ?\\n ?"), "\n").trim()

    fun stripHtml(text: String): String = text.replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    fun removeNonPrintable(text: String): String = text.filter { it == '\n' || it == '\t' || (it.code >= 32 && it.code != 127 && it.code !in 0x80..0x9F) }

    fun normalizePunctuation(text: String): String = text
        .replace(Regex("[‘’‚‛′]"), "'")
        .replace(Regex("[“”„‟″«»]"), "\"")
        .replace(Regex("[–—―−]"), "-")
        .replace("…", "...")

    fun removeEmoji(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = codePointAt(text, i)
            val width = if (cp > 0xFFFF) 2 else 1
            val emoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x231A..0x231B || cp in 0x23E9..0x23FA ||
                cp in 0x2B05..0x2B55 || cp in 0xE0020..0xE007F || cp == 0x200D || cp == 0xFE0F || cp == 0x20E3
            if (!emoji) sb.append(text, i, i + width)
            i += width
        }
        return sb.toString()
    }

    private fun codePointAt(text: String, index: Int): Int {
        val high = text[index]
        if (high.isHighSurrogate() && index + 1 < text.length) {
            val low = text[index + 1]
            if (low.isLowSurrogate()) return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
        }
        return high.code
    }
}
