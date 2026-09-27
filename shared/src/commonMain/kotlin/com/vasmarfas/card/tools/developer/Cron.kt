package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.core.Lang
import com.vasmarfas.card.resources.*
import org.jetbrains.compose.resources.StringResource

sealed class CronPart {
    data object Any : CronPart()
    data class Single(val value: Int) : CronPart()
    data class Range(val from: Int, val to: Int) : CronPart()
    data class Step(val from: Int?, val to: Int?, val step: Int) : CronPart()
}

data class CronField(val parts: List<CronPart>, val values: Set<Int>) {
    val isStar: Boolean get() = parts.any { it is CronPart.Any || (it is CronPart.Step && it.from == null) }
}

data class CronExpr(
    val minute: CronField,
    val hour: CronField,
    val dayOfMonth: CronField,
    val month: CronField,
    val dayOfWeek: CronField,
)

data class CronTime(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int) {
    fun formatted(): String = "$year-${pad2(month)}-${pad2(day)} ${pad2(hour)}:${pad2(minute)}"

    fun plusMinute(): CronTime = if (minute < 59) copy(minute = minute + 1) else nextHour()

    fun nextHour(): CronTime = if (hour < 23) copy(hour = hour + 1, minute = 0) else nextDay()

    fun nextDay(): CronTime = when {
        day < Cron.daysInMonth(year, month) -> CronTime(year, month, day + 1, 0, 0)
        month < 12 -> CronTime(year, month + 1, 1, 0, 0)
        else -> CronTime(year + 1, 1, 1, 0, 0)
    }

    fun nextMonth(): CronTime = if (month < 12) CronTime(year, month + 1, 1, 0, 0) else CronTime(year + 1, 1, 1, 0, 0)
}

class CronException(val text: StringResource, vararg val args: Any) : Exception(text.key)

object Cron {
    private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val dayNames = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
    private val macros = mapOf(
        "@yearly" to "0 0 1 1 *",
        "@annually" to "0 0 1 1 *",
        "@monthly" to "0 0 1 * *",
        "@weekly" to "0 0 * * 0",
        "@daily" to "0 0 * * *",
        "@midnight" to "0 0 * * *",
        "@hourly" to "0 * * * *",
    )

    private val monthsEn = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val monthsRuIn = listOf("январе", "феврале", "марте", "апреле", "мае", "июне", "июле", "августе", "сентябре", "октябре", "ноябре", "декабре")
    private val monthsRuFrom = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val monthsRuTo = listOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")
    private val daysEn = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val daysRuOn = listOf("воскресеньям", "понедельникам", "вторникам", "средам", "четвергам", "пятницам", "субботам")
    private val daysRuFrom = listOf("воскресенья", "понедельника", "вторника", "среды", "четверга", "пятницы", "субботы")
    private val daysRuTo = listOf("воскресенье", "понедельник", "вторник", "среду", "четверг", "пятницу", "субботу")

    val presets: List<Pair<String, StringResource>> = listOf(
        "* * * * *" to Res.string.cron_every_minute,
        "*/5 * * * *" to Res.string.cron_every_5_minutes,
        "0 * * * *" to Res.string.cron_every_hour,
        "0 0 * * *" to Res.string.cron_daily_at_midnight,
        "0 9 * * 1-5" to Res.string.cron_weekdays_at_9,
        "0 0 * * 0" to Res.string.cron_weekly_on_sunday,
        "0 0 1 * *" to Res.string.cron_monthly_on_the_1st,
        "0 0 1 1 *" to Res.string.cron_yearly_on_january_1,
        "30 2 * * 6" to Res.string.cron_saturdays_at_0230,
        "0 */6 * * *" to Res.string.cron_every_6_hours,
        "0 12 1,15 * *" to Res.string.cron_1st_and_15th_at_noon,
    )

    fun parse(text: String): Result<CronExpr> {
        val expanded = macros[text.trim().lowercase()] ?: text.trim()
        val fields = expanded.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (fields.size != 5) {
            return Result.failure(CronException(Res.string.cron_expected_5_fields))
        }
        return try {
            Result.success(
                CronExpr(
                    minute = field(fields[0], 0, 59, null),
                    hour = field(fields[1], 0, 23, null),
                    dayOfMonth = field(fields[2], 1, 31, null),
                    month = field(fields[3], 1, 12, monthNames),
                    dayOfWeek = field(fields[4], 0, 7, dayNames),
                ),
            )
        } catch (e: CronException) {
            Result.failure(e)
        }
    }

    private fun field(text: String, min: Int, max: Int, names: List<String>?): CronField {
        val isDow = names === dayNames
        val parts = text.split(',').map { part(it, min, max, names) }
        val values = mutableSetOf<Int>()
        for (p in parts) values += expand(p, min, if (isDow) 6 else max)
        return CronField(parts, if (isDow) values.map { if (it == 7) 0 else it }.toSet() else values)
    }

    private fun value(token: String, min: Int, max: Int, names: List<String>?): Int {
        val named = names?.indexOf(token.lowercase())?.takeIf { it >= 0 }?.let { if (names === monthNames) it + 1 else it }
        val v = named ?: token.toIntOrNull() ?: throw CronException(Res.string.cron_invalid_value, token)
        if (v < min || v > max) throw CronException(Res.string.cron_value_out_of_range, v, min, max)
        return v
    }

    private fun part(piece: String, min: Int, max: Int, names: List<String>?): CronPart {
        val p = piece.trim()
        if (p.isEmpty()) throw CronException(Res.string.cron_empty_list_item)
        if (p == "*" || p == "?") return CronPart.Any
        val slash = p.indexOf('/')
        if (slash >= 0) {
            val base = p.substring(0, slash)
            val step = p.substring(slash + 1).toIntOrNull()?.takeIf { it > 0 }
                ?: throw CronException(Res.string.cron_invalid_step, p)
            if (base == "*" || base == "?") return CronPart.Step(null, null, step)
            val dash = base.indexOf('-')
            if (dash <= 0) return CronPart.Step(value(base, min, max, names), null, step)
            val from = value(base.substring(0, dash), min, max, names)
            val to = value(base.substring(dash + 1), min, max, names)
            if (from > to) throw CronException(Res.string.cron_reversed_range, p)
            return CronPart.Step(from, to, step)
        }
        val dash = p.indexOf('-')
        if (dash > 0) {
            val from = value(p.substring(0, dash), min, max, names)
            val to = value(p.substring(dash + 1), min, max, names)
            if (from > to) throw CronException(Res.string.cron_reversed_range, p)
            return CronPart.Range(from, to)
        }
        return CronPart.Single(value(p, min, max, names))
    }

    private fun expand(part: CronPart, min: Int, max: Int): Set<Int> = when (part) {
        CronPart.Any -> (min..max).toSet()
        is CronPart.Single -> setOf(part.value)
        is CronPart.Range -> (part.from..part.to).toSet()
        is CronPart.Step -> ((part.from ?: min)..(part.to ?: max) step part.step).toSet()
    }

    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    fun dayOfWeek(year: Int, month: Int, day: Int): Int {
        val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        val y = if (month < 3) year - 1 else year
        return (y + y / 4 - y / 100 + y / 400 + t[month - 1] + day) % 7
    }

    private fun dayMatches(expr: CronExpr, t: CronTime): Boolean {
        val domOk = t.day in expr.dayOfMonth.values
        val dowOk = dayOfWeek(t.year, t.month, t.day) in expr.dayOfWeek.values
        return if (expr.dayOfMonth.isStar || expr.dayOfWeek.isStar) domOk && dowOk else domOk || dowOk
    }

    fun matches(expr: CronExpr, t: CronTime): Boolean =
        t.minute in expr.minute.values && t.hour in expr.hour.values && t.month in expr.month.values && dayMatches(expr, t)

    fun nextRuns(expr: CronExpr, from: CronTime, count: Int): List<CronTime> {
        val out = mutableListOf<CronTime>()
        var t = from.plusMinute()
        while (out.size < count && t.year <= from.year + 30) {
            t = when {
                t.month !in expr.month.values -> t.nextMonth()
                !dayMatches(expr, t) -> t.nextDay()
                t.hour !in expr.hour.values -> t.nextHour()
                t.minute !in expr.minute.values -> t.plusMinute()
                else -> {
                    out += t
                    t.plusMinute()
                }
            }
        }
        return out
    }

    private fun joinList(items: List<String>, lang: Lang): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + (if (lang == Lang.RU) " и " else " and ") + items.last()
    }

    private fun ruPlural(n: Int, one: String, few: String, many: String): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1 && mod100 != 11 -> one
            mod10 in 2..4 && mod100 !in 12..14 -> few
            else -> many
        }
    }

    private fun enOrdinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    private fun genericParts(field: CronField, lang: Lang): String = joinList(
        field.parts.map { p ->
            when (p) {
                CronPart.Any -> if (lang == Lang.RU) "каждый" else "every"
                is CronPart.Single -> p.value.toString()
                is CronPart.Range -> if (lang == Lang.RU) "с ${p.from} по ${p.to}" else "${p.from} through ${p.to}"
                is CronPart.Step -> {
                    val every = if (lang == Lang.RU) "каждый ${p.step}-й" else "every ${enOrdinal(p.step)}"
                    when {
                        p.from == null -> every
                        p.to == null -> if (lang == Lang.RU) "$every начиная с ${p.from}" else "$every starting at ${p.from}"
                        else -> if (lang == Lang.RU) "$every с ${p.from} по ${p.to}" else "$every from ${p.from} through ${p.to}"
                    }
                }
            }
        },
        lang,
    )

    private fun timePhrase(expr: CronExpr, lang: Lang): String {
        val m = expr.minute.parts
        val h = expr.hour.parts
        val ru = lang == Lang.RU
        val mSingle = m.singleOrNull() as? CronPart.Single
        val hSingle = h.singleOrNull() as? CronPart.Single
        val mStep = m.singleOrNull() as? CronPart.Step
        val hStep = h.singleOrNull() as? CronPart.Step
        val hAny = h.singleOrNull() is CronPart.Any
        val mAny = m.singleOrNull() is CronPart.Any
        if (m.all { it is CronPart.Single } && h.all { it is CronPart.Single } && m.size * h.size <= 12) {
            val times = h.map { (it as CronPart.Single).value }.sorted().flatMap { hour ->
                m.map { (it as CronPart.Single).value }.sorted().map { minute -> "${pad2(hour)}:${pad2(minute)}" }
            }
            return (if (ru) "В " else "At ") + joinList(times, lang)
        }
        if ((mAny || (mStep != null && mStep.from == null && mStep.step == 1)) && hAny) return if (ru) "Каждую минуту" else "Every minute"
        if (mStep != null && mStep.from == null && hAny) {
            return if (ru) "Каждые ${mStep.step} ${ruPlural(mStep.step, "минуту", "минуты", "минут")}" else "Every ${mStep.step} minutes"
        }
        if (mSingle != null && mSingle.value == 0 && hAny) return if (ru) "Каждый час" else "Every hour"
        if (mSingle != null && mSingle.value == 0 && hStep != null && hStep.from == null) {
            return if (ru) "Каждые ${hStep.step} ${ruPlural(hStep.step, "час", "часа", "часов")}" else "Every ${hStep.step} hours"
        }
        if (mSingle != null && hAny) return if (ru) "В ${mSingle.value} ${ruPlural(mSingle.value, "минуту", "минуты", "минут")} каждого часа" else "At minute ${mSingle.value} past every hour"
        val hourText = if (hAny) (if (ru) "каждого часа" else "every hour") else (if (ru) "часов ${genericParts(expr.hour, lang)}" else "hour ${genericParts(expr.hour, lang)}")
        return if (ru) "В минуты ${genericParts(expr.minute, lang)} $hourText" else "At minute ${genericParts(expr.minute, lang)} past $hourText"
    }

    private fun namedPhrase(field: CronField, lang: Lang, en: List<String>, ruOn: List<String>, ruFrom: List<String>, ruTo: List<String>, enPrefix: String, ruPrefix: String, isMonth: Boolean, unitEn: String, unitRu: String): String {
        val ru = lang == Lang.RU
        fun name(list: List<String>, v: Int) = list[if (isMonth) v - 1 else v % 7]
        val items = field.parts.map { p ->
            when (p) {
                CronPart.Any -> ""
                is CronPart.Single -> if (ru) name(ruOn, p.value) else name(en, p.value)
                is CronPart.Range -> if (ru) "с ${name(ruFrom, p.from)} по ${name(ruTo, p.to)}" else "${name(en, p.from)} through ${name(en, p.to)}"
                is CronPart.Step -> if (ru) "каждый ${p.step}-й $unitRu" else "every ${enOrdinal(p.step)} $unitEn"
            }
        }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return ""
        return (if (ru) ruPrefix else enPrefix) + joinList(items, lang)
    }

    fun describe(expr: CronExpr, lang: Lang): String {
        val ru = lang == Lang.RU
        val pieces = mutableListOf(timePhrase(expr, lang))
        val dom = if (expr.dayOfMonth.isStar && expr.dayOfMonth.parts.all { it is CronPart.Any }) "" else domPhrase(expr.dayOfMonth, lang)
        val dow = namedPhrase(expr.dayOfWeek, lang, daysEn, daysRuOn, daysRuFrom, daysRuTo, "on ", "по ", false, "day of the week", "день недели")
        if (dom.isNotEmpty() && dow.isNotEmpty()) {
            val both = expr.dayOfMonth.isStar || expr.dayOfWeek.isStar
            val joint = if (ru) (if (both) "и" else "или") else (if (both) "and" else "or")
            pieces += "$dom $joint $dow"
        } else {
            if (dom.isNotEmpty()) pieces += dom
            if (dow.isNotEmpty()) pieces += dow
        }
        val month = namedPhrase(expr.month, lang, monthsEn, monthsRuIn, monthsRuFrom, monthsRuTo, "in ", "в ", true, "month", "месяц")
        if (month.isNotEmpty()) pieces += month
        return pieces.joinToString(", ")
    }

    private fun domPhrase(field: CronField, lang: Lang): String {
        val ru = lang == Lang.RU
        val items = field.parts.map { p ->
            when (p) {
                CronPart.Any -> ""
                is CronPart.Single -> if (ru) "${p.value}-го" else p.value.toString()
                is CronPart.Range -> if (ru) "с ${p.from}-го по ${p.to}-е" else "${p.from} through ${p.to}"
                is CronPart.Step -> if (ru) "каждый ${p.step}-й день" else "every ${enOrdinal(p.step)} day"
            }
        }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return ""
        return if (ru) "${joinList(items, lang)} числа" else "on day-of-month ${joinList(items, lang)}"
    }
}
