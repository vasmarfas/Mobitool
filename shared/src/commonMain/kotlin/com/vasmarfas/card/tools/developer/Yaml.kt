package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.text.appendCodePoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.StringResource

object Yaml {
    private val number = Regex("[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?")
    private val hex = Regex("0x[0-9a-fA-F]+")
    private val octal = Regex("0o[0-7]+")
    private val nulls = setOf("", "~", "null", "Null", "NULL")
    private val truths = setOf("true", "True", "TRUE")
    private val lies = setOf("false", "False", "FALSE")
    // YAML 1.1 readers still take these for booleans, and anything starting with a digit may be a date or a time there
    private val oldBooleans = setOf("y", "n", "yes", "no", "on", "off")

    fun parse(text: String): JsonElement = YamlParser(text.removePrefix(Char(0xFEFF).toString()).replace("\r\n", "\n")).document()

    fun write(element: JsonElement): String = lines(element).joinToString("\n")

    fun scalar(raw: String): JsonElement = when {
        raw in nulls -> JsonNull
        raw in truths -> JsonPrimitive(true)
        raw in lies -> JsonPrimitive(false)
        number.matches(raw) -> DataFormats.number(raw)
        hex.matches(raw) -> radix(raw, 16)
        octal.matches(raw) -> radix(raw, 8)
        else -> JsonPrimitive(raw)
    }

    private fun radix(raw: String, radix: Int): JsonPrimitive =
        raw.drop(2).toLongOrNull(radix)?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)

    private fun lines(element: JsonElement): List<String> = when {
        element is JsonObject && element.isNotEmpty() -> element.flatMap { (key, value) ->
            val name = string(key) + ":"
            if (isBlock(value)) listOf(name) + lines(value).map { "  $it" } else listOf("$name ${inline(value)}")
        }
        element is JsonArray && element.isNotEmpty() -> element.flatMap { item ->
            val body = lines(item)
            listOf("- " + body.first()) + body.drop(1).map { "  $it" }
        }
        else -> listOf(inline(element))
    }

    private fun isBlock(element: JsonElement): Boolean =
        (element is JsonObject && element.isNotEmpty()) || (element is JsonArray && element.isNotEmpty())

    private fun inline(element: JsonElement): String = when (element) {
        is JsonObject -> "{}"
        is JsonArray -> "[]"
        is JsonPrimitive -> if (element.isString) string(element.content) else element.content
    }

    private fun string(text: String): String = if (plainSafe(text)) text else DataFormats.quote(text)

    private fun plainSafe(text: String): Boolean {
        val resolved = scalar(text)
        return resolved is JsonPrimitive && resolved.isString &&
            text.first().let { it.isLetter() || it == '_' || it == '/' } &&
            text.all { it.isLetterOrDigit() || it in " _-./:+@()=" } &&
            !text.endsWith(' ') && !text.endsWith(':') && ": " !in text &&
            text.lowercase() !in oldBooleans
    }
}

private const val NO_PARENT = -1

private class YamlParser(private val text: String) {
    private var pos = 0

    private val column: Int get() = pos - (text.lastIndexOf('\n', pos - 1) + 1)

    private val atEnd: Boolean get() = pos >= text.length || marker("---") || marker("...")

    private fun fail(reason: StringResource, vararg args: Any): Nothing =
        throw DataFormatException(reason, DataFormats.lineAt(text, pos), *args)

    private fun breakAt(i: Int) = i >= text.length || text[i] == '\n'

    private fun spaceAt(i: Int) = breakAt(i) || text[i] == ' ' || text[i] == '\t'

    private fun marker(mark: String) = text.startsWith(mark, pos) && spaceAt(pos + 3) && column == 0

    private fun entryAt(i: Int) = text[i] == '-' && spaceAt(i + 1)

    private fun lineEndsHere() = breakAt(pos) || text[pos] == '#'

    private fun skipSpaces() {
        while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t')) pos++
    }

    private fun skipBlank() {
        while (pos < text.length) {
            val start = pos
            skipSpaces()
            when {
                pos >= text.length -> return
                text[pos] == '#' -> while (!breakAt(pos)) pos++
                text[pos] == '\n' -> pos++
                else -> {
                    if ('\t' in text.subSequence(start, pos)) fail(Res.string.data_format_yaml_tab)
                    return
                }
            }
        }
    }

    private fun finishLine() {
        skipSpaces()
        if (!lineEndsHere()) fail(Res.string.data_format_unexpected)
        skipBlank()
    }

    private fun checkSupported() {
        val c = text[pos]
        if (c == '&' || c == '*' || c == '!' || c == '%' || (c == '?' && spaceAt(pos + 1))) fail(Res.string.data_format_yaml_unsupported)
    }

    fun document(): JsonElement {
        skipBlank()
        if (marker("---")) {
            pos += 3
            skipSpaces()
            if (lineEndsHere()) skipBlank()
        }
        val root = if (atEnd) JsonNull else node(NO_PARENT, 0)
        if (marker("...")) {
            pos += 3
            finishLine()
        }
        if (marker("---")) fail(Res.string.data_format_yaml_documents)
        if (pos < text.length) fail(Res.string.data_format_yaml_indent)
        return root
    }

    private fun node(parent: Int, depth: Int): JsonElement {
        if (depth > JsonTools.MAX_DEPTH) throw DataFormats.tooDeep(DataFormats.lineAt(text, pos))
        return when {
            entryAt(pos) -> sequence(column, depth)
            keyAhead() -> mapping(column, depth)
            else -> inline(parent, depth)
        }
    }

    private fun sequence(indent: Int, depth: Int): JsonElement {
        val items = ArrayList<JsonElement>()
        while (true) {
            pos++
            skipSpaces()
            items += if (lineEndsHere()) {
                skipBlank()
                if (!atEnd && column > indent) node(indent, depth + 1) else JsonNull
            } else {
                node(indent, depth + 1)
            }
            if (atEnd || column < indent) break
            if (column > indent) fail(Res.string.data_format_yaml_indent)
            if (!entryAt(pos)) break
        }
        return JsonArray(items)
    }

    private fun mapping(indent: Int, depth: Int): JsonElement {
        val entries = LinkedHashMap<String, JsonElement>()
        while (true) {
            val start = pos
            val name = key()
            if (name in entries) {
                pos = start
                fail(Res.string.data_format_duplicate_key, name)
            }
            entries[name] = mappingValue(indent, depth + 1)
            if (atEnd || column < indent) break
            if (column > indent) fail(Res.string.data_format_yaml_indent)
            if (!keyAhead()) fail(Res.string.data_format_yaml_key)
        }
        return JsonObject(entries)
    }

    private fun mappingValue(indent: Int, depth: Int): JsonElement {
        skipSpaces()
        if (!lineEndsHere()) return inline(indent, depth)
        skipBlank()
        return when {
            atEnd -> JsonNull
            column > indent -> node(indent, depth)
            column == indent && entryAt(pos) -> sequence(indent, depth)
            else -> JsonNull
        }
    }

    private fun keyAhead(): Boolean {
        val first = text[pos]
        if (first == '"' || first == '\'') {
            var i = quoteEnd(first)
            if (i < 0) return false
            while (i < text.length && text[i] == ' ') i++
            return i < text.length && text[i] == ':' && spaceAt(i + 1)
        }
        if (first == '[' || first == '{' || first == '|' || first == '>') return false
        var i = pos
        while (!breakAt(i)) {
            if (text[i] == ':' && spaceAt(i + 1)) return true
            if (text[i] == '#' && i > pos && spaceAt(i - 1)) return false
            i++
        }
        return false
    }

    private fun quoteEnd(quote: Char): Int {
        var i = pos + 1
        while (!breakAt(i)) {
            when {
                quote == '"' && text[i] == '\\' -> i++
                quote == '\'' && text[i] == '\'' && text.getOrNull(i + 1) == '\'' -> i++
                text[i] == quote -> return i + 1
            }
            i++
        }
        return -1
    }

    private fun key(): String {
        checkSupported()
        val name = when (text[pos]) {
            '"' -> doubleQuoted()
            '\'' -> singleQuoted()
            else -> {
                val start = pos
                while (!(text[pos] == ':' && spaceAt(pos + 1))) pos++
                text.substring(start, pos).trimEnd()
            }
        }
        skipSpaces()
        pos++
        return name
    }

    private fun inline(parent: Int, depth: Int): JsonElement {
        checkSupported()
        if (entryAt(pos)) fail(Res.string.data_format_yaml_indent)
        return when (text[pos]) {
            '|', '>' -> blockScalar(parent)
            '[', '{' -> flow(depth).also { finishLine() }
            '"' -> JsonPrimitive(doubleQuoted()).also { finishLine() }
            '\'' -> JsonPrimitive(singleQuoted()).also { finishLine() }
            else -> plain(parent)
        }
    }

    private fun plainLine(): String {
        val start = pos
        while (!breakAt(pos) && !(text[pos] == '#' && pos > start && spaceAt(pos - 1))) pos++
        val line = text.substring(start, pos).trimEnd()
        if (": " in line || line.endsWith(':')) {
            pos = start
            fail(Res.string.data_format_yaml_colon)
        }
        return line
    }

    private fun plain(parent: Int): JsonElement {
        val out = StringBuilder(plainLine())
        var folded = false
        while (pos < text.length) {
            if (text[pos] == '#') {
                finishLine()
                break
            }
            val lineBreak = pos
            skipBlank()
            if (atEnd || column <= parent) break
            val blanks = text.substring(lineBreak, pos).count { it == '\n' } - 1
            out.append(if (blanks == 0) " " else "\n".repeat(blanks)).append(plainLine())
            folded = true
        }
        return if (folded) JsonPrimitive(out.toString()) else Yaml.scalar(out.toString())
    }

    private fun blockScalar(parent: Int): JsonElement {
        val folded = text[pos] == '>'
        var chomp = ' '
        var explicit = 0
        pos++
        while (!spaceAt(pos)) {
            when (val c = text[pos]) {
                '+', '-' -> chomp = c
                in '1'..'9' -> explicit = c - '0'
                else -> fail(Res.string.data_format_unexpected)
            }
            pos++
        }
        skipSpaces()
        if (!lineEndsHere()) fail(Res.string.data_format_unexpected)
        while (!breakAt(pos)) pos++
        pos = minOf(pos + 1, text.length)

        var indent = if (explicit > 0) maxOf(parent, 0) + explicit else -1
        val lines = ArrayList<String>()
        while (!atEnd) {
            val end = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            val line = text.substring(pos, end)
            if (line.isNotBlank()) {
                val spaces = line.length - line.trimStart(' ').length
                if (indent < 0) {
                    if (spaces <= parent) break
                    indent = spaces
                }
                if (spaces < indent) break
            }
            lines += if (indent in 0..line.length) line.drop(indent) else ""
            pos = minOf(end + 1, text.length)
        }
        val terminated = pos > 0 && text[pos - 1] == '\n'
        val content = lines.dropLastWhile { it.isBlank() }
        val trailing = lines.size - content.size
        val ending = when {
            chomp == '-' -> ""
            chomp == '+' -> "\n".repeat(trailing + if (content.isEmpty()) 0 else 1)
            content.isEmpty() -> ""
            else -> "\n"
        }
        skipBlank()
        val body = if (folded) foldLines(content) else content.joinToString("\n")
        return JsonPrimitive(body + if (terminated) ending else ending.removeSuffix("\n"))
    }

    private fun foldLines(lines: List<String>): String {
        val out = StringBuilder()
        var previous: String? = null
        var blanks = 0
        for (line in lines) {
            if (line.isEmpty()) {
                blanks++
                continue
            }
            val last = previous
            when {
                last == null -> out.append("\n".repeat(blanks))
                last.first().isWhitespace() || line.first().isWhitespace() -> out.append("\n".repeat(blanks + 1))
                blanks == 0 -> out.append(' ')
                else -> out.append("\n".repeat(blanks))
            }
            out.append(line)
            previous = line
            blanks = 0
        }
        return out.toString()
    }

    private fun doubleQuoted(): String {
        val open = pos
        val out = StringBuilder()
        pos++
        while (pos < text.length) {
            when (val c = text[pos]) {
                '"' -> {
                    pos++
                    return out.toString()
                }
                '\\' -> escape(out)
                '\n' -> foldBreak(out)
                else -> {
                    out.append(c)
                    pos++
                }
            }
        }
        pos = open
        fail(Res.string.data_format_unclosed_string)
    }

    private fun singleQuoted(): String {
        val open = pos
        val out = StringBuilder()
        pos++
        while (pos < text.length) {
            val c = text[pos]
            when {
                c == '\'' && text.getOrNull(pos + 1) == '\'' -> {
                    out.append(c)
                    pos += 2
                }
                c == '\'' -> {
                    pos++
                    return out.toString()
                }
                c == '\n' -> foldBreak(out)
                else -> {
                    out.append(c)
                    pos++
                }
            }
        }
        pos = open
        fail(Res.string.data_format_unclosed_string)
    }

    private fun foldBreak(out: StringBuilder) {
        while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) out.setLength(out.length - 1)
        var breaks = 0
        while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\n')) {
            if (text[pos] == '\n') breaks++
            pos++
        }
        out.append(if (breaks == 1) " " else "\n".repeat(breaks - 1))
    }

    private fun escape(out: StringBuilder) {
        val start = pos
        val c = text.getOrNull(pos + 1)
        pos += 2
        when (c) {
            'x' -> codePoint(out, 2, start)
            'u' -> codePoint(out, 4, start)
            'U' -> codePoint(out, 8, start)
            '\n' -> skipSpaces()
            else -> {
                val code = escapes[c]
                if (code == null) {
                    pos = start
                    fail(Res.string.data_format_bad_escape)
                }
                out.appendCodePoint(code)
            }
        }
    }

    private fun codePoint(out: StringBuilder, length: Int, start: Int) {
        val code = text.substring(pos, minOf(pos + length, text.length)).takeIf { it.length == length }?.toIntOrNull(16)
        if (code == null) {
            pos = start
            fail(Res.string.data_format_bad_escape)
        }
        out.appendCodePoint(code)
        pos += length
    }

    private fun skipFlowSpace() {
        while (pos < text.length) {
            val c = text[pos]
            when {
                c == ' ' || c == '\t' || c == '\n' -> pos++
                c == '#' -> while (!breakAt(pos)) pos++
                else -> return
            }
        }
    }

    private fun flow(depth: Int): JsonElement {
        if (depth > JsonTools.MAX_DEPTH) throw DataFormats.tooDeep(DataFormats.lineAt(text, pos))
        return if (text[pos] == '[') flowSequence(depth) else flowMapping(depth)
    }

    private fun flowEntryEnd(close: Char) {
        skipFlowSpace()
        when (text.getOrNull(pos)) {
            ',' -> pos++
            close -> Unit
            else -> fail(Res.string.data_format_flow_separator)
        }
    }

    private fun flowSequence(depth: Int): JsonElement {
        val items = ArrayList<JsonElement>()
        pos++
        while (true) {
            skipFlowSpace()
            if (text.getOrNull(pos) == ']') break
            items += flowValue(depth + 1)
            flowEntryEnd(']')
        }
        pos++
        return JsonArray(items)
    }

    private fun flowMapping(depth: Int): JsonElement {
        val entries = LinkedHashMap<String, JsonElement>()
        pos++
        while (true) {
            skipFlowSpace()
            if (text.getOrNull(pos) == '}') break
            val start = pos
            val name = when (text.getOrNull(pos)) {
                '"' -> doubleQuoted()
                '\'' -> singleQuoted()
                else -> flowPlain()
            }
            if (name in entries) {
                pos = start
                fail(Res.string.data_format_duplicate_key, name)
            }
            skipFlowSpace()
            entries[name] = if (text.getOrNull(pos) == ':') {
                pos++
                skipFlowSpace()
                if (text.getOrNull(pos) == ',' || text.getOrNull(pos) == '}') JsonNull else flowValue(depth + 1)
            } else {
                JsonNull
            }
            flowEntryEnd('}')
        }
        pos++
        return JsonObject(entries)
    }

    private fun flowValue(depth: Int): JsonElement = when (text.getOrNull(pos)) {
        '[', '{' -> flow(depth)
        '"' -> JsonPrimitive(doubleQuoted())
        '\'' -> JsonPrimitive(singleQuoted())
        else -> Yaml.scalar(flowPlain())
    }

    private fun flowPlain(): String {
        if (pos >= text.length) fail(Res.string.data_format_flow_separator)
        checkSupported()
        val start = pos
        while (!breakAt(pos) && text[pos] !in ",[]{}" && !flowColonAt(pos) && !(text[pos] == '#' && spaceAt(pos - 1))) pos++
        if (pos == start) fail(Res.string.data_format_flow_separator)
        return text.substring(start, pos).trimEnd()
    }

    private fun flowColonAt(i: Int) = text[i] == ':' && (spaceAt(i + 1) || text[i + 1] in ",[]{}")

    private companion object {
        val escapes = mapOf(
            '0' to 0, 'a' to 7, 'b' to 8, 't' to 9, '\t' to 9, 'n' to 10, 'v' to 11, 'f' to 12, 'r' to 13, 'e' to 27,
            ' ' to 32, '"' to 34, '/' to 47, '\\' to 92, 'N' to 0x85, '_' to 0xA0, 'L' to 0x2028, 'P' to 0x2029,
        )
    }
}
