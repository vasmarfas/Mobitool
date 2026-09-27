package com.vasmarfas.card.tools.electronics

import com.vasmarfas.card.resources.*
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import org.jetbrains.compose.resources.StringResource

enum class ResistorColor(
    val title: StringResource,
    val argb: Long,
    val digit: Int?,
    val multiplier: Double?,
    val tolerance: Double?,
    val tempco: Int?,
) {
    BLACK(Res.string.black, 0xFF000000L, 0, 1.0, null, 250),
    BROWN(Res.string.brown, 0xFF795548L, 1, 10.0, 1.0, 100),
    RED(Res.string.red, 0xFFE53935L, 2, 100.0, 2.0, 50),
    ORANGE(Res.string.orange, 0xFFFB8C00L, 3, 1e3, null, 15),
    YELLOW(Res.string.yellow, 0xFFFDD835L, 4, 1e4, null, 25),
    GREEN(Res.string.green, 0xFF43A047L, 5, 1e5, 0.5, 20),
    BLUE(Res.string.blue, 0xFF1E88E5L, 6, 1e6, 0.25, 10),
    VIOLET(Res.string.violet, 0xFF8E24AAL, 7, 1e7, 0.1, 5),
    GREY(Res.string.grey, 0xFF9E9E9EL, 8, 1e8, 0.05, 1),
    WHITE(Res.string.white, 0xFFFFFFFFL, 9, 1e9, null, null),
    GOLD(Res.string.gold, 0xFFD4AF37L, null, 0.1, 5.0, null),
    SILVER(Res.string.silver, 0xFFC0C0C0L, null, 0.01, 10.0, null),
    NONE(Res.string.none, 0x00000000L, null, null, 20.0, null),
}

class ResistorValue(val ohms: Double, val tolerance: Double, val tempco: Int?)

object ResistorCode {
    val digitColors = ResistorColor.entries.filter { it.digit != null }
    val multiplierColors = ResistorColor.entries.filter { it.multiplier != null }
    val toleranceColors = ResistorColor.entries.filter { it.tolerance != null }
    val tempcoColors = ResistorColor.entries.filter { it.tempco != null }

    fun digitCount(bandCount: Int): Int = if (bandCount == 4) 2 else 3

    fun decode(bands: List<ResistorColor>): ResistorValue? {
        if (bands.size !in 4..6) return null
        val digits = digitCount(bands.size)
        var mantissa = 0
        for (index in 0 until digits) {
            val digit = bands[index].digit ?: return null
            mantissa = mantissa * 10 + digit
        }
        val multiplier = bands[digits].multiplier ?: return null
        val tolerance = bands[digits + 1].tolerance ?: return null
        val tempco = if (bands.size == 6) (bands[5].tempco ?: return null) else null
        return ResistorValue(mantissa * multiplier, tolerance, tempco)
    }

    fun encode(ohms: Double, bandCount: Int, tolerance: ResistorColor, tempco: ResistorColor?): List<ResistorColor>? {
        if (ohms <= 0 || ohms.isInfinite() || ohms.isNaN()) return null
        val digits = digitCount(bandCount)
        var multiplierExponent = floor(log10(ohms)).toInt() - (digits - 1)
        var mantissa = (ohms / 10.0.pow(multiplierExponent)).roundToLong()
        if (mantissa >= 10.0.pow(digits).toLong()) {
            mantissa /= 10
            multiplierExponent++
        }
        val multiplier = multiplierColors.firstOrNull { abs(log10(it.multiplier!!) - multiplierExponent) < 1e-9 } ?: return null
        val digitBands = mantissa.toString().padStart(digits, '0').map { c -> digitColors.first { it.digit == c - '0' } }
        return buildList {
            addAll(digitBands)
            add(multiplier)
            add(tolerance)
            if (bandCount == 6 && tempco != null) add(tempco)
        }
    }
}
