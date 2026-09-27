package com.vasmarfas.card.tools.text

import com.vasmarfas.card.resources.*
import kotlin.random.Random
import org.jetbrains.compose.resources.StringResource

enum class LineOp(val title: StringResource) {
    SORT_ASC(Res.string.sort_a_z),
    SORT_DESC(Res.string.sort_z_a),
    SORT_NATURAL(Res.string.natural_sort),
    SORT_LENGTH(Res.string.sort_by_length),
    DEDUPE(Res.string.remove_duplicates),
    REVERSE(Res.string.reverse_order),
    SHUFFLE(Res.string.shuffle),
    TRIM(Res.string.trim_lines),
    REMOVE_EMPTY(Res.string.remove_empty_lines),
    NUMBER(Res.string.number_lines),
    PREFIX_SUFFIX(Res.string.prefix_suffix),
    JOIN(Res.string.join_lines),
    SPLIT(Res.string.split_by_separator),
}

data class LineOptions(
    val ignoreCase: Boolean = false,
    val prefix: String = "",
    val suffix: String = "",
    val separator: String = ", ",
)

object LineOps {
    private val whitespace = Regex("\\s+")

    fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ie = i
                while (ie < a.length && a[ie].isDigit()) ie++
                var je = j
                while (je < b.length && b[je].isDigit()) je++
                val na = a.substring(i, ie).trimStart('0')
                val nb = b.substring(j, je).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return cmp
                i = ie
                j = je
            } else {
                val cmp = letterWeight(ca) - letterWeight(cb)
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    fun compareAlphabetical(a: String, b: String): Int {
        for (k in 0 until minOf(a.length, b.length)) {
            val cmp = letterWeight(a[k]) - letterWeight(b[k])
            if (cmp != 0) return cmp
        }
        return a.length - b.length
    }

    private fun letterWeight(c: Char): Int {
        val lower = c.lowercaseChar()
        return if (lower == 'ё') 'е'.code * 2 + 1 else lower.code * 2
    }

    fun unescape(separator: String): String = separator.replace("\\t", "\t").replace("\\n", "\n")

    fun apply(text: String, op: LineOp, options: LineOptions = LineOptions(), random: Random = Random.Default): String {
        val lines = text.lines()
        val key: (String) -> String = if (options.ignoreCase) {
            { s -> s.lowercase() }
        } else {
            { s -> s }
        }
        val alphabetical = if (options.ignoreCase) Comparator(::compareAlphabetical) else Comparator(::compareAlphabetical).thenBy { it }
        val out = when (op) {
            LineOp.SORT_ASC -> lines.sortedWith(alphabetical)
            LineOp.SORT_DESC -> lines.sortedWith(alphabetical.reversed())
            LineOp.SORT_NATURAL -> lines.sortedWith(Comparator { a, b -> compareNatural(a, b) })
            LineOp.SORT_LENGTH -> lines.sortedBy { it.length }
            LineOp.DEDUPE -> lines.distinctBy(key)
            LineOp.REVERSE -> lines.reversed()
            LineOp.SHUFFLE -> lines.shuffled(random)
            LineOp.TRIM -> lines.map { it.trim() }
            LineOp.REMOVE_EMPTY -> lines.filter { it.isNotBlank() }
            LineOp.NUMBER -> {
                val width = lines.size.toString().length
                lines.mapIndexed { i, line -> "${(i + 1).toString().padStart(width)}. $line" }
            }
            LineOp.PREFIX_SUFFIX -> lines.map { options.prefix + it + options.suffix }
            LineOp.JOIN -> listOf(lines.joinToString(unescape(options.separator)))
            LineOp.SPLIT -> {
                val separator = unescape(options.separator)
                if (separator.isEmpty()) text.split(whitespace).filter { it.isNotEmpty() } else text.split(separator)
            }
        }
        return out.joinToString("\n")
    }
}
