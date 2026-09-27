package com.vasmarfas.card.core

import com.vasmarfas.card.tools.converters.fmtSig
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

fun Double.fmt(maxFraction: Int = 2, minFraction: Int = 0, grouping: Boolean = false): String {
    if (isNaN()) return "NaN"
    if (isInfinite()) return if (this > 0) "∞" else "-∞"
    val factor = 10.0.pow(maxFraction)
    if (abs(this) * factor >= Long.MAX_VALUE) return fmtSig(15)
    val rounded = (abs(this) * factor).roundToLong()
    var intPart = (rounded / factor.toLong()).toString()
    var frac = (rounded % factor.toLong()).toString().padStart(maxFraction, '0')
    frac = frac.trimEnd('0')
    if (frac.length < minFraction) frac = frac.padEnd(minFraction, '0')
    if (grouping) intPart = groupThousands(intPart)
    val sign = if (this < 0 && rounded != 0L) "-" else ""
    return if (frac.isEmpty()) "$sign$intPart" else "$sign$intPart.$frac"
}

fun Long.fmtGrouped(): String {
    val s = abs(this).toString()
    return (if (this < 0) "-" else "") + groupThousands(s)
}

fun Int.fmtGrouped(): String = toLong().fmtGrouped()

private fun groupThousands(digits: String): String {
    val sb = StringBuilder()
    digits.forEachIndexed { i, c ->
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append(' ')
        sb.append(c)
    }
    return sb.toString()
}

fun formatBytes(bytes: Long, binary: Boolean = true): String {
    val ru = appLang == Lang.RU
    val unit = if (binary) 1024.0 else 1000.0
    if (bytes < unit) return if (ru) "$bytes Б" else "$bytes B"
    val units = when {
        binary && ru -> listOf("КиБ", "МиБ", "ГиБ", "ТиБ", "ПиБ")
        binary -> listOf("KiB", "MiB", "GiB", "TiB", "PiB")
        ru -> listOf("кБ", "МБ", "ГБ", "ТБ", "ПБ")
        else -> listOf("kB", "MB", "GB", "TB", "PB")
    }
    var value = bytes.toDouble()
    var idx = -1
    while (value >= unit && idx < units.lastIndex) {
        value /= unit
        idx++
    }
    return "${value.fmt(2)} ${units[idx]}"
}

fun formatDurationMs(ms: Long): String {
    val ru = appLang == Lang.RU
    if (ms < 1000) return if (ru) "$ms мс" else "$ms ms"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return buildString {
        if (h > 0) append(h).append(if (ru) " ч " else "h ")
        if (h > 0 || m > 0) append(m).append(if (ru) " мин " else "m ")
        append(s).append(if (ru) " с" else "s")
    }
}

private val thousandsCommas = Regex("[-+]?[1-9]\\d{0,2}(,\\d{3})+(\\.\\d+)?")

fun String.normalizeNumber(): String {
    val s = filterNot { it.isWhitespace() }
    return if (appLang == Lang.EN && thousandsCommas.matches(s)) s.replace(",", "") else s.replace(',', '.')
}

fun String.toDoubleLenient(): Double? = normalizeNumber().toDoubleOrNull()

fun formatCount(n: Int): String {
    val ru = appLang == Lang.RU
    fun short(value: Int, unit: Int, suffix: String): String {
        val tenths = (value + unit / 20) / (unit / 10)
        return if (tenths < 100 && tenths % 10 != 0) "${tenths / 10}.${tenths % 10}$suffix" else "${(value + unit / 2) / unit}$suffix"
    }
    return when {
        n < 1_000 -> n.toString()
        n < 999_500 -> short(n, 1_000, if (ru) "\u00A0тыс." else "K")
        else -> short(n, 1_000_000, if (ru) "\u00A0млн" else "M")
    }
}

fun parseCount(text: String?): Int? {
    val t = text?.trim()?.removeSuffix("+")?.replace(',', '.')?.takeIf { it.isNotEmpty() } ?: return null
    val multiplier = when (t.last().uppercaseChar()) {
        'K' -> 1_000
        'M' -> 1_000_000
        else -> 1
    }
    val number = (if (multiplier == 1) t else t.dropLast(1)).toDoubleOrNull() ?: return null
    return (number * multiplier).roundToLong().toInt()
}
