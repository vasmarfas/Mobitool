package com.vasmarfas.card.tools.text

import com.vasmarfas.card.resources.*
import org.jetbrains.compose.resources.StringResource

enum class TranslitScheme(val title: StringResource) {
    PASSPORT(Res.string.passport_icao),
    GOST(Res.string.gost_7_79_2000_b),
    READABLE(Res.string.readable),
}

object Translit {
    private const val VOWELS = "аеёиоуыэюя"

    private val passport = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "ie", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "iu", 'я' to "ia",
    )

    private val gost = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "j", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "x", 'ц' to "cz", 'ч' to "ch", 'ш' to "sh", 'щ' to "shh",
        'ъ' to "``", 'ы' to "y`", 'ь' to "`", 'э' to "e`", 'ю' to "yu", 'я' to "ya",
    )

    private val readable = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    private val passportInverse = inverse(passport, listOf("shch" to "щ", "sch" to "щ", "ie" to "ие", "y" to "ы"))
    private val gostInverse = inverse(gost, listOf("shh" to "щ", "c" to "ц", "y" to "ы"))
    private val readableInverse = inverse(readable, listOf("shch" to "щ", "sch" to "щ", "y" to "ы", "j" to "й", "x" to "кс", "w" to "в", "h" to "х", "c" to "к", "q" to "к"))

    private fun inverse(table: Map<Char, String>, extra: List<Pair<String, String>>): List<Pair<String, String>> {
        val pairs = LinkedHashMap<String, String>()
        for ((cyr, lat) in table) if (lat.isNotEmpty() && lat !in pairs) pairs[lat] = cyr.toString()
        for ((lat, cyr) in extra) pairs[lat] = cyr
        return pairs.entries.map { it.key to it.value }.sortedByDescending { it.first.length }
    }

    private fun table(scheme: TranslitScheme) = when (scheme) {
        TranslitScheme.PASSPORT -> passport
        TranslitScheme.GOST -> gost
        TranslitScheme.READABLE -> readable
    }

    fun toLatin(text: String, scheme: TranslitScheme): String {
        val table = table(scheme)
        val sb = StringBuilder(text.length * 2)
        for (i in text.indices) {
            val c = text[i]
            val lower = c.lowercaseChar()
            var mapped = table[lower]
            if (mapped == null) {
                sb.append(c)
                continue
            }
            val next = text.getOrNull(i + 1)
            if (scheme == TranslitScheme.GOST && lower == 'ц' && next != null && next.lowercaseChar() in "иеёыйэ") mapped = "c"
            if (c.isUpperCase() && mapped.isNotEmpty()) {
                val prev = text.getOrNull(i - 1)
                val allCaps = (next != null && next.isUpperCase()) ||
                    (next?.isLetter() != true && prev != null && prev.isUpperCase())
                mapped = if (allCaps) mapped.uppercase() else mapped.replaceFirstChar { it.uppercaseChar() }
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    fun toCyrillic(text: String, scheme: TranslitScheme): String {
        val table = when (scheme) {
            TranslitScheme.PASSPORT -> passportInverse
            TranslitScheme.GOST -> gostInverse
            TranslitScheme.READABLE -> readableInverse
        }
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            var matched: Pair<String, String>? = null
            for (pair in table) {
                if (text.regionMatches(i, pair.first, 0, pair.first.length, ignoreCase = true)) {
                    matched = pair
                    break
                }
            }
            if (matched == null) {
                sb.append(text[i])
                i++
                continue
            }
            var cyr = matched.second
            if (scheme != TranslitScheme.GOST && matched.first == "y") {
                val prevOut = sb.lastOrNull()?.lowercaseChar()
                val nextIn = text.getOrNull(i + 1)?.lowercaseChar()
                if (prevOut != null && prevOut in VOWELS) cyr = "й"
                if (prevOut?.isLetter() != true && nextIn != null && nextIn in "aeiou") cyr = "й"
            }
            when {
                !text[i].isUpperCase() -> sb.append(cyr)
                text.getOrNull(i + 1)?.isUpperCase() == true -> sb.append(cyr.uppercase())
                else -> sb.append(cyr.replaceFirstChar { it.uppercaseChar() })
            }
            i += matched.first.length
        }
        return sb.toString()
    }

    fun slugify(text: String): String {
        val latin = TextCleaner.removeDiacritics(toLatin(text, TranslitScheme.READABLE)).lowercase()
        val sb = StringBuilder(latin.length)
        var dash = false
        for (c in latin) {
            if (c in 'a'..'z' || c in '0'..'9') {
                sb.append(c)
                dash = false
            } else if (!dash && sb.isNotEmpty()) {
                sb.append('-')
                dash = true
            }
        }
        return sb.toString().trimEnd('-')
    }
}
