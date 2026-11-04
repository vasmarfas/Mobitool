package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.text.appendCodePoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.compose.resources.StringResource

object Toml {
    private val bareKey = Regex("[A-Za-z0-9_-]+")

    fun parse(text: String): JsonElement = TomlParser(text.removePrefix(Char(0xFEFF).toString()).replace("\r\n", "\n")).document()

    fun write(element: JsonElement): String {
        val root = element as? JsonObject ?: throw DataFormatException(Res.string.data_format_toml_root, null)
        val lines = ArrayList<String>()
        table(root, emptyList(), lines)
        return lines.joinToString("\n")
    }

    private fun isTableArray(element: JsonElement) =
        element is JsonArray && element.isNotEmpty() && element.all { it is JsonObject }

    private fun table(table: JsonObject, path: List<String>, lines: MutableList<String>) {
        val (nested, values) = table.entries.partition { it.value is JsonObject || isTableArray(it.value) }
        values.forEach { (name, value) -> lines += "${key(name)} = ${inline(value, path + name)}" }
        for ((name, value) in nested) {
            val inner = path + name
            val header = inner.joinToString(".") { key(it) }
            if (value is JsonArray) {
                for (item in value) {
                    if (lines.isNotEmpty()) lines += ""
                    lines += "[[$header]]"
                    table(item.jsonObject, inner, lines)
                }
            } else {
                if (lines.isNotEmpty()) lines += ""
                lines += "[$header]"
                table(value.jsonObject, inner, lines)
            }
        }
    }

    private fun key(name: String): String = if (bareKey.matches(name)) name else DataFormats.quote(name)

    private fun inline(element: JsonElement, path: List<String>): String = when (element) {
        is JsonObject -> if (element.isEmpty()) "{}" else element.entries.joinToString(", ", "{ ", " }") { (name, value) ->
            "${key(name)} = ${inline(value, path + name)}"
        }
        is JsonArray -> element.withIndex().joinToString(", ", "[", "]") { (i, item) -> inline(item, path + i.toString()) }
        is JsonNull -> throw DataFormatException(Res.string.data_format_toml_null, null, path.joinToString("."))
        is JsonPrimitive -> when {
            element.isString -> DataFormats.quote(element.content)
            element.content.all { it.isDigit() || it == '-' } && element.content.toLongOrNull() == null ->
                throw DataFormatException(Res.string.data_format_toml_big_int, null, path.joinToString("."))
            else -> element.content
        }
    }
}

private enum class TomlOrigin { IMPLICIT, HEADER, DOTTED }

private class TomlTable(var origin: TomlOrigin, val depth: Int) {
    val entries = LinkedHashMap<String, Any>()
}

private class TomlTableArray {
    val tables = ArrayList<TomlTable>()
}

private class TomlParser(private val text: String) {
    private var pos = 0
    private val root = TomlTable(TomlOrigin.HEADER, 0)
    private var current = root

    private fun fail(reason: StringResource, vararg args: Any): Nothing =
        throw DataFormatException(reason, DataFormats.lineAt(text, pos), *args)

    private fun breakAt(i: Int) = i >= text.length || text[i] == '\n'

    private fun skipSpaces() {
        while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t')) pos++
    }

    private fun skipBlank() {
        while (pos < text.length) {
            val c = text[pos]
            when {
                c == ' ' || c == '\t' || c == '\n' -> pos++
                c == '#' -> while (!breakAt(pos)) pos++
                else -> return
            }
        }
    }

    private fun finishLine() {
        skipSpaces()
        if (text.getOrNull(pos) == '#') while (!breakAt(pos)) pos++
        if (!breakAt(pos)) fail(Res.string.data_format_toml_newline)
    }

    fun document(): JsonElement {
        skipBlank()
        while (pos < text.length) {
            when {
                text.startsWith("[[", pos) -> tableArrayHeader()
                text[pos] == '[' -> tableHeader()
                else -> keyValue(current)
            }
            finishLine()
            skipBlank()
        }
        return toJson(root)
    }

    private fun toJson(table: TomlTable): JsonObject = JsonObject(
        table.entries.mapValues { (_, value) ->
            when (value) {
                is TomlTable -> toJson(value)
                is TomlTableArray -> JsonArray(value.tables.map { toJson(it) })
                else -> value as JsonElement
            }
        },
    )

    private fun child(parent: TomlTable, origin: TomlOrigin): TomlTable {
        if (parent.depth >= JsonTools.MAX_DEPTH) throw DataFormats.tooDeep(DataFormats.lineAt(text, pos))
        return TomlTable(origin, parent.depth + 1)
    }

    private fun keyPath(): List<String> {
        val path = ArrayList<String>()
        while (true) {
            skipSpaces()
            path += simpleKey()
            skipSpaces()
            if (text.getOrNull(pos) != '.') return path
            pos++
        }
    }

    private fun simpleKey(): String = when (text.getOrNull(pos)) {
        '"' -> basicString()
        '\'' -> literalString()
        else -> {
            val start = pos
            while (pos < text.length && (text[pos] in 'a'..'z' || text[pos] in 'A'..'Z' || text[pos] in '0'..'9' || text[pos] == '_' || text[pos] == '-')) pos++
            if (pos == start) fail(Res.string.data_format_toml_key)
            text.substring(start, pos)
        }
    }

    private fun header(close: String): List<String> {
        val path = keyPath()
        if (!text.startsWith(close, pos)) fail(Res.string.data_format_toml_header)
        pos += close.length
        return path
    }

    private fun descend(table: TomlTable, key: String): TomlTable = when (val next = table.entries[key]) {
        null -> child(table, TomlOrigin.IMPLICIT).also { table.entries[key] = it }
        is TomlTable -> next
        is TomlTableArray -> next.tables.last()
        else -> fail(Res.string.data_format_toml_not_table, key)
    }

    private fun tableHeader() {
        pos++
        val path = header("]")
        val parent = path.dropLast(1).fold(root, ::descend)
        val name = path.last()
        val existing = parent.entries[name]
        current = when {
            existing == null -> child(parent, TomlOrigin.HEADER).also { parent.entries[name] = it }
            existing is TomlTable && existing.origin == TomlOrigin.IMPLICIT -> existing.also { it.origin = TomlOrigin.HEADER }
            else -> fail(Res.string.data_format_toml_table_twice, path.joinToString("."))
        }
    }

    private fun tableArrayHeader() {
        pos += 2
        val path = header("]]")
        val parent = path.dropLast(1).fold(root, ::descend)
        val name = path.last()
        val tables = when (val existing = parent.entries[name]) {
            null -> TomlTableArray().also { parent.entries[name] = it }
            is TomlTableArray -> existing
            else -> fail(Res.string.data_format_toml_table_twice, path.joinToString("."))
        }
        current = child(parent, TomlOrigin.HEADER).also { tables.tables += it }
    }

    private fun dotted(table: TomlTable, key: String): TomlTable = when (val next = table.entries[key]) {
        null -> child(table, TomlOrigin.DOTTED).also { table.entries[key] = it }
        is TomlTable -> if (next.origin == TomlOrigin.HEADER) fail(Res.string.data_format_toml_table_twice, key) else next
        else -> fail(Res.string.data_format_toml_not_table, key)
    }

    private fun keyValue(table: TomlTable) {
        val path = keyPath()
        if (text.getOrNull(pos) != '=') fail(Res.string.data_format_toml_equals)
        pos++
        skipSpaces()
        val target = path.dropLast(1).fold(table, ::dotted)
        val name = path.last()
        if (name in target.entries) fail(Res.string.data_format_duplicate_key, name)
        target.entries[name] = value(target.depth + 1)
    }

    private fun value(depth: Int): JsonElement = when {
        text.startsWith("\"\"\"", pos) -> JsonPrimitive(multiline('"'))
        text.startsWith("'''", pos) -> JsonPrimitive(multiline('\''))
        else -> when (text.getOrNull(pos)) {
            '"' -> JsonPrimitive(basicString())
            '\'' -> JsonPrimitive(literalString())
            '[' -> array(depth)
            '{' -> inlineTable(depth)
            else -> bareValue()
        }
    }

    private fun array(depth: Int): JsonElement {
        if (depth > JsonTools.MAX_DEPTH) throw DataFormats.tooDeep(DataFormats.lineAt(text, pos))
        val items = ArrayList<JsonElement>()
        pos++
        while (true) {
            skipBlank()
            if (text.getOrNull(pos) == ']') break
            items += value(depth + 1)
            skipBlank()
            when (text.getOrNull(pos)) {
                ',' -> pos++
                ']' -> Unit
                else -> fail(Res.string.data_format_flow_separator)
            }
        }
        pos++
        return JsonArray(items)
    }

    private fun inlineTable(depth: Int): JsonElement {
        if (depth > JsonTools.MAX_DEPTH) throw DataFormats.tooDeep(DataFormats.lineAt(text, pos))
        val table = TomlTable(TomlOrigin.HEADER, depth)
        pos++
        skipSpaces()
        if (text.getOrNull(pos) == '}') {
            pos++
            return JsonObject(emptyMap())
        }
        while (true) {
            keyValue(table)
            skipSpaces()
            when (text.getOrNull(pos)) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return toJson(table)
                }
                else -> fail(Res.string.data_format_flow_separator)
            }
        }
    }

    private fun bareValue(): JsonElement {
        val start = pos
        fun word() {
            while (!breakAt(pos) && text[pos] !in " \t,]}#") pos++
        }
        word()
        if (localDate.matches(text.substring(start, pos)) && text.getOrNull(pos) == ' ' && text.getOrNull(pos + 1)?.isDigit() == true) {
            pos++
            word()
        }
        val token = text.substring(start, pos)
        val clean = token.replace("_", "")
        return when {
            token == "true" -> JsonPrimitive(true)
            token == "false" -> JsonPrimitive(false)
            decimal.matches(token) -> integer(clean.toLongOrNull(), start)
            hex.matches(token) -> integer(clean.drop(2).toLongOrNull(16), start)
            octal.matches(token) -> integer(clean.drop(2).toLongOrNull(8), start)
            binary.matches(token) -> integer(clean.drop(2).toLongOrNull(2), start)
            real.matches(token) -> DataFormats.number(clean)
            special.matches(token) || dateTime.matches(token) || localDate.matches(token) || localTime.matches(token) -> JsonPrimitive(token)
            else -> {
                pos = start
                fail(Res.string.data_format_toml_value, token)
            }
        }
    }

    private fun integer(value: Long?, start: Int): JsonElement {
        if (value == null) {
            pos = start
            fail(Res.string.data_format_toml_int_range)
        }
        return JsonPrimitive(value)
    }

    private fun checkChar(c: Char) {
        if ((c < ' ' && c != '\t' && c != '\n') || c.code == 0x7F) fail(Res.string.data_format_control_char)
    }

    private fun basicString(): String {
        val open = pos
        val out = StringBuilder()
        pos++
        while (!breakAt(pos)) {
            val c = text[pos]
            when (c) {
                '"' -> {
                    pos++
                    return out.toString()
                }
                '\\' -> escape(out)
                else -> {
                    checkChar(c)
                    out.append(c)
                    pos++
                }
            }
        }
        pos = open
        fail(Res.string.data_format_unclosed_string)
    }

    private fun literalString(): String {
        val open = pos
        val start = ++pos
        while (!breakAt(pos)) {
            if (text[pos] == '\'') return text.substring(start, pos++)
            checkChar(text[pos])
            pos++
        }
        pos = open
        fail(Res.string.data_format_unclosed_string)
    }

    private fun multiline(quote: Char): String {
        val open = pos
        val out = StringBuilder()
        pos += 3
        if (text.getOrNull(pos) == '\n') pos++
        while (pos < text.length) {
            val c = text[pos]
            when {
                c == quote && text.startsWith(quote.toString().repeat(3), pos) -> {
                    var run = 3
                    while (run < 5 && text.getOrNull(pos + run) == quote) run++
                    out.append(quote.toString().repeat(run - 3))
                    pos += run
                    return out.toString()
                }
                c == '\\' && quote == '"' -> if (lineEndingBackslash()) skipBlankLines() else escape(out)
                else -> {
                    checkChar(c)
                    out.append(c)
                    pos++
                }
            }
        }
        pos = open
        fail(Res.string.data_format_unclosed_string)
    }

    private fun lineEndingBackslash(): Boolean {
        var i = pos + 1
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i < text.length && text[i] == '\n'
    }

    private fun skipBlankLines() {
        pos++
        while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\n')) pos++
    }

    private fun escape(out: StringBuilder) {
        val c = text.getOrNull(pos + 1)
        val simple = escapes[c]
        when {
            simple != null -> {
                out.append(simple)
                pos += 2
            }
            c == 'u' -> codePoint(out, 4)
            c == 'U' -> codePoint(out, 8)
            else -> fail(Res.string.data_format_bad_escape)
        }
    }

    private fun codePoint(out: StringBuilder, length: Int) {
        val digits = text.substring(pos + 2, minOf(pos + 2 + length, text.length))
        val code = digits.takeIf { it.length == length }?.toIntOrNull(16)
        if (code == null || code in 0xD800..0xDFFF || code > 0x10FFFF) fail(Res.string.data_format_bad_escape)
        out.appendCodePoint(code)
        pos += 2 + length
    }

    private companion object {
        val decimal = Regex("[+-]?(0|[1-9](_?[0-9])*)")
        val hex = Regex("0x[0-9a-fA-F](_?[0-9a-fA-F])*")
        val octal = Regex("0o[0-7](_?[0-7])*")
        val binary = Regex("0b[01](_?[01])*")
        val real = Regex("[+-]?(0|[1-9](_?[0-9])*)(\\.[0-9](_?[0-9])*)?([eE][+-]?[0-9](_?[0-9])*)?")
        val special = Regex("[+-]?(inf|nan)")
        val localDate = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        val localTime = Regex("[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?")
        val dateTime = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt ][0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?([Zz]|[+-][0-9]{2}:[0-9]{2})?")
        val escapes = mapOf('b' to '\b', 't' to '\t', 'n' to '\n', 'f' to Char(12), 'r' to '\r', '"' to '"', '\\' to '\\')
    }
}
