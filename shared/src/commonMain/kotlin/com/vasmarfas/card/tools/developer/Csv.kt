package com.vasmarfas.card.tools.developer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

data class CsvTable(val rows: List<List<String>>, val delimiter: Char) {
    val columns: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

object Csv {
    private val delimiters = listOf(';', '\t', ',', '|')
    private val jsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().take(20).joinToString("\n")
        val counts = delimiters.associateWith { recordCounts(sample, it) }
        val steady = delimiters.firstOrNull { d -> counts.getValue(d).let { c -> c.isNotEmpty() && c[0] > 0 && c.all { it == c[0] } } }
        return steady ?: delimiters.filter { counts.getValue(it).sum() > 0 }.maxByOrNull { counts.getValue(it).sum() } ?: ','
    }

    private fun recordCounts(text: String, delimiter: Char): List<Int> {
        val counts = mutableListOf<Int>()
        var count = 0
        var blank = true
        var inQuotes = false
        for (c in text + '\n') {
            if (c == '\n' && !inQuotes) {
                if (!blank) counts += count
                count = 0
                blank = true
                continue
            }
            if (c == '"') inQuotes = !inQuotes else if (c == delimiter && !inQuotes) count++
            if (!c.isWhitespace()) blank = false
        }
        return counts
    }

    fun parse(text: String, delimiter: Char): CsvTable {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        var started = false
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (text.getOrNull(i + 1) == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(c)
                    }
                }
                c == '"' && field.isBlank() -> {
                    field.clear()
                    inQuotes = true
                }
                c == delimiter -> {
                    row += field.toString()
                    field.clear()
                    started = true
                }
                c == '\r' -> {}
                c == '\n' -> {
                    row += field.toString()
                    field.clear()
                    if (started || row.size > 1 || row[0].isNotEmpty()) rows += row.toList()
                    row.clear()
                    started = false
                }
                else -> {
                    field.append(c)
                    started = true
                }
            }
            i++
        }
        row += field.toString()
        if (started || row.size > 1 || row[0].isNotEmpty()) rows += row.toList()
        return CsvTable(rows, delimiter)
    }

    fun escape(value: String, delimiter: Char): String =
        if (value.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    fun toJson(table: CsvTable, hasHeader: Boolean): JsonArray {
        if (table.rows.isEmpty()) return JsonArray(emptyList())
        val header = if (hasHeader) table.rows.first() else List(table.columns) { "column${it + 1}" }
        val body = if (hasHeader) table.rows.drop(1) else table.rows
        return buildJsonArray {
            body.forEach { row ->
                add(
                    buildJsonObject {
                        header.forEachIndexed { i, name ->
                            val key = name.ifBlank { "column${i + 1}" }
                            put(key, valueElement(row.getOrElse(i) { "" }))
                        }
                    },
                )
            }
        }
    }

    private fun valueElement(raw: String): JsonPrimitive {
        val t = raw.trim()
        return when {
            t == "true" || t == "false" -> JsonPrimitive(t == "true")
            isNumber(t) -> JsonUnquotedLiteral(t)
            else -> JsonPrimitive(raw)
        }
    }

    private fun isNumber(text: String): Boolean {
        if (!jsonNumber.matches(text)) return false
        return if (text.none { it == '.' || it == 'e' || it == 'E' }) text.toLongOrNull() != null else text.toDouble().isFinite()
    }

    fun toMarkdown(table: CsvTable, hasHeader: Boolean): String {
        if (table.rows.isEmpty()) return ""
        val columns = table.columns
        val header = if (hasHeader) table.rows.first() else List(columns) { "Column ${it + 1}" }
        val body = if (hasHeader) table.rows.drop(1) else table.rows
        val widths = IntArray(columns) { i ->
            maxOf(3, header.getOrElse(i) { "" }.length, body.maxOfOrNull { it.getOrElse(i) { "" }.length } ?: 0)
        }
        fun line(cells: List<String>) = (0 until columns).joinToString(" | ", "| ", " |") { i ->
            cells.getOrElse(i) { "" }.replace("|", "\\|").padEnd(widths[i])
        }
        return buildString {
            appendLine(line(header))
            appendLine((0 until columns).joinToString(" | ", "| ", " |") { "-".repeat(widths[it]) })
            body.forEach { appendLine(line(it)) }
        }.trimEnd()
    }

    fun fromJson(array: JsonArray, delimiter: Char): String? {
        val objects = array.map { it as? JsonObject ?: return null }
        val header = LinkedHashSet<String>()
        objects.forEach { header += it.keys }
        val sb = StringBuilder()
        sb.append(header.joinToString(delimiter.toString()) { escape(it, delimiter) }).append('\n')
        objects.forEach { obj ->
            sb.append(
                header.joinToString(delimiter.toString()) { key ->
                    val value = obj[key]
                    val text = when (value) {
                        null -> ""
                        is JsonPrimitive -> if (value.isString) value.content else value.content
                        else -> JsonTools.minify(value)
                    }
                    escape(text, delimiter)
                },
            ).append('\n')
        }
        return sb.toString().trimEnd()
    }
}
