package com.vasmarfas.card.tools.converters

import com.vasmarfas.card.core.normalizeNumber
import kotlin.math.abs

enum class WordsLang { EN, RU }

enum class WordsCurrency { NONE, RUB, USD }

class Amount(val negative: Boolean, val whole: Long, val fraction: String)

private class Scale(val one: String, val few: String, val many: String, val feminine: Boolean)

object NumberWords {
    private val amountPattern = Regex("^(-?)(\\d{1,18})(?:[.,](\\d*))?$")

    private val ruUnits = listOf(
        "ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
        "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
    )
    private val ruTens = listOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
    private val ruHundreds = listOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")
    private val ruScales = listOf(
        Scale("тысяча", "тысячи", "тысяч", true),
        Scale("миллион", "миллиона", "миллионов", false),
        Scale("миллиард", "миллиарда", "миллиардов", false),
        Scale("триллион", "триллиона", "триллионов", false),
        Scale("квадриллион", "квадриллиона", "квадриллионов", false),
        Scale("квинтиллион", "квинтиллиона", "квинтиллионов", false),
    )
    private val ruFractions = listOf(
        "десятая" to "десятых",
        "сотая" to "сотых",
        "тысячная" to "тысячных",
        "десятитысячная" to "десятитысячных",
        "стотысячная" to "стотысячных",
        "миллионная" to "миллионных",
    )

    private val enUnits = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val enTens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val enScales = listOf("thousand", "million", "billion", "trillion", "quadrillion", "quintillion")

    fun parse(text: String): Amount? {
        val match = amountPattern.find(text.normalizeNumber()) ?: return null
        return Amount(match.groupValues[1] == "-", match.groupValues[2].toLong(), match.groupValues[3])
    }

    fun plural(n: Long, one: String, few: String, many: String): String {
        val n10 = n % 10
        val n100 = n % 100
        return when {
            n100 in 11..19 -> many
            n10 == 1L -> one
            n10 in 2..4 -> few
            else -> many
        }
    }

    fun spell(value: Long, lang: WordsLang, feminine: Boolean = false): String {
        if (value == 0L) return if (lang == WordsLang.RU) "ноль" else "zero"
        val words = mutableListOf<String>()
        if (value < 0) words.add(if (lang == WordsLang.RU) "минус" else "minus")
        val groups = mutableListOf<Int>()
        var rest = abs(value)
        while (rest > 0) {
            groups.add((rest % 1000).toInt())
            rest /= 1000
        }
        for (i in groups.indices.reversed()) {
            val group = groups[i]
            if (group == 0) continue
            if (lang == WordsLang.RU) {
                val scale = if (i == 0) null else ruScales[i - 1]
                words.addAll(ruGroup(group, scale?.feminine ?: feminine))
                if (scale != null) words.add(plural(group.toLong(), scale.one, scale.few, scale.many))
            } else {
                words.addAll(enGroup(group))
                if (i > 0) words.add(enScales[i - 1])
            }
        }
        return words.joinToString(" ")
    }

    fun spellAmount(amount: Amount, lang: WordsLang, currency: WordsCurrency): String {
        val text = if (currency == WordsCurrency.NONE) plain(amount, lang) else money(amount, lang, currency)
        return text.replaceFirstChar { it.uppercase() }
    }

    fun roundedCents(amount: Amount): Pair<Long, Int> {
        val padded = (amount.fraction + "000").take(3)
        var cents = padded.substring(0, 2).toInt()
        var whole = amount.whole
        if (padded[2] >= '5') cents++
        if (cents == 100) {
            cents = 0
            whole++
        }
        return whole to cents
    }

    private fun ruGroup(n: Int, feminine: Boolean): List<String> = buildList {
        if (n >= 100) add(ruHundreds[n / 100])
        val rest = n % 100
        if (rest in 1..19) {
            add(ruUnit(rest, feminine))
        } else if (rest >= 20) {
            add(ruTens[rest / 10])
            val unit = rest % 10
            if (unit > 0) add(ruUnit(unit, feminine))
        }
    }

    private fun ruUnit(n: Int, feminine: Boolean): String = when {
        feminine && n == 1 -> "одна"
        feminine && n == 2 -> "две"
        else -> ruUnits[n]
    }

    private fun enGroup(n: Int): List<String> = buildList {
        if (n >= 100) {
            add(enUnits[n / 100])
            add("hundred")
        }
        val rest = n % 100
        if (rest in 1..19) {
            add(enUnits[rest])
        } else if (rest >= 20) {
            val unit = rest % 10
            add(if (unit == 0) enTens[rest / 10] else "${enTens[rest / 10]}-${enUnits[unit]}")
        }
    }

    private fun plain(amount: Amount, lang: WordsLang): String {
        val fraction = amount.fraction.trimEnd('0')
        val sign = if (amount.negative && (amount.whole != 0L || fraction.isNotEmpty())) (if (lang == WordsLang.RU) "минус " else "minus ") else ""
        if (fraction.isEmpty()) return sign + spell(amount.whole, lang)
        if (lang == WordsLang.EN) {
            return sign + spell(amount.whole, lang) + " point " + fraction.map { enUnits[it - '0'] }.joinToString(" ")
        }
        if (fraction.length > ruFractions.size) {
            return sign + spell(amount.whole, lang) + " запятая " + fraction.map { ruUnits[it - '0'] }.joinToString(" ")
        }
        val numerator = fraction.toLong()
        val wholeWord = if (amount.whole % 10 == 1L && amount.whole % 100 != 11L) "целая" else "целых"
        val (one, many) = ruFractions[fraction.length - 1]
        val denominator = if (numerator % 10 == 1L && numerator % 100 != 11L) one else many
        return sign + spell(amount.whole, lang, feminine = true) + " " + wholeWord + " " + spell(numerator, lang, feminine = true) + " " + denominator
    }

    private fun money(amount: Amount, lang: WordsLang, currency: WordsCurrency): String {
        val (whole, cents) = roundedCents(amount)
        val sign = if (amount.negative && (whole != 0L || cents != 0)) (if (lang == WordsLang.RU) "минус " else "minus ") else ""
        if (lang == WordsLang.RU) {
            val major = if (currency == WordsCurrency.RUB) plural(whole, "рубль", "рубля", "рублей") else plural(whole, "доллар", "доллара", "долларов")
            val minor = if (currency == WordsCurrency.RUB) plural(cents.toLong(), "копейка", "копейки", "копеек") else plural(cents.toLong(), "цент", "цента", "центов")
            return "$sign${spell(whole, lang)} $major ${cents.toString().padStart(2, '0')} $minor"
        }
        val major = when {
            currency == WordsCurrency.RUB -> if (whole == 1L) "ruble" else "rubles"
            else -> if (whole == 1L) "dollar" else "dollars"
        }
        val minor = when {
            currency == WordsCurrency.RUB -> if (cents == 1) "kopeck" else "kopecks"
            else -> if (cents == 1) "cent" else "cents"
        }
        return if (cents == 0) "$sign${spell(whole, lang)} $major" else "$sign${spell(whole, lang)} $major and ${spell(cents.toLong(), lang)} $minor"
    }
}
