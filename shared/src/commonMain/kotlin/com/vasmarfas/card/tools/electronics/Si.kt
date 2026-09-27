package com.vasmarfas.card.tools.electronics

import com.vasmarfas.card.core.Lang
import com.vasmarfas.card.core.appLang
import com.vasmarfas.card.core.normalizeNumber
import com.vasmarfas.card.tools.converters.fmtSig
import kotlin.math.abs

object Si {
    private val prefixes = mapOf(
        "p" to 1e-12, "n" to 1e-9, "u" to 1e-6, "µ" to 1e-6, "μ" to 1e-6, "m" to 1e-3,
        "k" to 1e3, "K" to 1e3, "M" to 1e6, "G" to 1e9, "T" to 1e12, "R" to 1.0,
        "п" to 1e-12, "н" to 1e-9, "мк" to 1e-6, "м" to 1e-3, "к" to 1e3, "М" to 1e6, "Г" to 1e9, "Т" to 1e12,
    )
    private val russianPrefixes = mapOf("T" to "Т", "G" to "Г", "M" to "М", "k" to "к", "" to "", "m" to "м", "µ" to "мк", "n" to "н", "p" to "п")
    private val pattern = Regex(
        "^([-+]?(?:\\d+\\.?\\d*|\\.\\d+))(мк|[pnuµμmkKMGTRпнмкМГТ])?(\\d*)(?:Ω|ohm|Ohm|OHM|Ом|ом|V|v|В|в|A|a|А|а|W|w|Вт|вт|F|Ф|H|Гн|Hz|Гц)?$",
    )

    fun parse(text: String): Double? {
        val s = text.normalizeNumber()
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { return it }
        val match = pattern.find(s) ?: return null
        val whole = match.groupValues[1]
        val prefix = match.groupValues[2]
        val tail = match.groupValues[3]
        val number = if (tail.isEmpty()) {
            whole
        } else {
            if (whole.contains('.')) return null
            "$whole.$tail"
        }
        val value = number.toDoubleOrNull() ?: return null
        val factor = if (prefix.isEmpty()) 1.0 else prefixes.getValue(prefix)
        return value * factor
    }

    fun format(value: Double, unit: String, significant: Int = 4): String {
        if (value.isNaN() || value.isInfinite()) return "${value.fmtSig()} $unit"
        val a = abs(value)
        if (a == 0.0) return "0 $unit"
        val (factor, prefix) = when {
            a >= 1e12 -> 1e12 to "T"
            a >= 1e9 -> 1e9 to "G"
            a >= 1e6 -> 1e6 to "M"
            a >= 1e3 -> 1e3 to "k"
            a >= 1 -> 1.0 to ""
            a >= 1e-3 -> 1e-3 to "m"
            a >= 1e-6 -> 1e-6 to "µ"
            a >= 1e-9 -> 1e-9 to "n"
            else -> 1e-12 to "p"
        }
        val shown = if (appLang == Lang.RU) russianPrefixes.getValue(prefix) else prefix
        return "${(value / factor).fmtSig(significant)} $shown$unit"
    }
}
