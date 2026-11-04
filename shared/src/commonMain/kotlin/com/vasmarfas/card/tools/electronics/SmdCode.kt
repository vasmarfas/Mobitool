package com.vasmarfas.card.tools.electronics

import com.vasmarfas.card.resources.*
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.jetbrains.compose.resources.StringResource

enum class SmdFormat(val title: StringResource, val tolerance: Double?) {
    JUMPER(Res.string.smd_codes_jumper, null),
    THREE_DIGITS(Res.string.smd_codes_three_digits, 5.0),
    FOUR_DIGITS(Res.string.smd_codes_four_digits, 1.0),
    EIA96(Res.string.smd_codes_eia96, 1.0),
    DECIMAL_SHORT(Res.string.smd_codes_decimal, 5.0),
    DECIMAL_LONG(Res.string.smd_codes_decimal, 1.0),
}

class SmdReading(val code: String, val format: SmdFormat, val value: Double)

object SmdCode {
    private val digits = Regex("\\d+")
    private val decimal = Regex("\\d{0,3}R\\d{0,3}")
    private val eia = Regex("\\d\\d[ZYRXSABHCDEF]")
    private val eiaExponents = mapOf(
        'Z' to -3, 'Y' to -2, 'R' to -2, 'X' to -1, 'S' to -1, 'A' to 0, 'B' to 1, 'H' to 1, 'C' to 2, 'D' to 3, 'E' to 4, 'F' to 5,
    )
    private const val EIA_LETTERS = "ZYXABCDEF"
    private const val RESISTOR_MAX_EXPONENT = 9

    // 8 and 9 in the third place of a capacitor code mean x0.01 and x0.1
    private const val CAPACITOR_MAX_EXPONENT = 7

    // every E96 value is this expression rounded to three digits, the series has no exceptions
    private fun e96(index: Int): Double = round(10.0.pow(2 + index / 96.0))

    // "47R" is 47 Ω as a decimal mark and 3.01 Ω as EIA-96, so a code can have two readings
    fun resistor(code: String): List<SmdReading> {
        val c = code.trim().uppercase()
        return when {
            c.isEmpty() -> emptyList()
            c.all { it == '0' } -> listOf(SmdReading(c, SmdFormat.JUMPER, 0.0))
            digits.matches(c) && c.length == 3 -> listOf(SmdReading(c, SmdFormat.THREE_DIGITS, scientific(c)))
            digits.matches(c) && c.length == 4 -> listOf(SmdReading(c, SmdFormat.FOUR_DIGITS, scientific(c)))
            else -> listOfNotNull(decimalReading(c), eiaReading(c))
        }
    }

    fun capacitor(code: String): Double? {
        val c = code.trim().uppercase()
        val picofarads = when {
            digits.matches(c) && c.length <= 2 -> c.toDouble()
            digits.matches(c) && c.length == 3 -> c.take(2).toInt() * when (val last = c[2].digitToInt()) {
                8 -> 0.01
                9 -> 0.1
                else -> 10.0.pow(last)
            }
            else -> decimalValue(c)
        }
        return picofarads?.takeIf { it > 0 }?.let { it * 1e-12 }
    }

    fun resistorCodes(ohms: Double): List<SmdReading> =
        listOfNotNull(marking(ohms, 2, RESISTOR_MAX_EXPONENT), marking(ohms, 3, RESISTOR_MAX_EXPONENT), eiaCode(ohms))
            .distinct()
            .mapNotNull { resistor(it).firstOrNull() }

    fun capacitorCode(farads: Double): String? = marking(farads * 1e12, 2, CAPACITOR_MAX_EXPONENT)

    private fun scientific(code: String): Double = code.dropLast(1).toInt() * 10.0.pow(code.last().digitToInt())

    private fun decimalValue(code: String): Double? =
        if (code.length in 2..4 && decimal.matches(code)) ("0" + code.replace('R', '.') + "0").toDoubleOrNull() else null

    private fun decimalReading(code: String): SmdReading? {
        val value = decimalValue(code) ?: return null
        val format = when {
            value == 0.0 -> SmdFormat.JUMPER
            code.length == 4 -> SmdFormat.DECIMAL_LONG
            else -> SmdFormat.DECIMAL_SHORT
        }
        return SmdReading(code, format, value)
    }

    private fun eiaReading(code: String): SmdReading? {
        if (!eia.matches(code)) return null
        val index = code.take(2).toInt()
        if (index !in 1..96) return null
        return SmdReading(code, SmdFormat.EIA96, e96(index - 1) * 10.0.pow(eiaExponents.getValue(code[2])))
    }

    private fun marking(value: Double, significant: Int, maxExponent: Int): String? {
        if (value == 0.0) return "0".repeat(significant + 1)
        var exponent = floor(log10(value)).toInt() - significant + 1
        var mantissa = (value / 10.0.pow(exponent)).roundToLong()
        if (mantissa.toString().length > significant) {
            mantissa /= 10
            exponent++
        }
        val shown = mantissa.toString()
        if (exponent >= 0) return if (exponent <= maxExponent) shown + exponent else null
        val whole = significant + exponent
        val text = if (whole > 0) shown.take(whole) + "R" + shown.drop(whole) else "R" + "0".repeat(-whole) + shown
        return text.take(4).takeIf { text.drop(4).all { it == '0' } }
    }

    private fun eiaCode(ohms: Double): String? {
        if (ohms <= 0) return null
        val decade = floor(log10(ohms)).toInt()
        val step = (96 * (log10(ohms) - decade)).roundToInt()
        val letter = EIA_LETTERS.getOrNull(decade - 2 + step / 96 + 3) ?: return null
        return (step % 96 + 1).toString().padStart(2, '0') + letter
    }
}
