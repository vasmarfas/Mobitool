package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonUnquotedLiteral
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

enum class DataFormat { JSON, YAML, TOML }

class DataFormatException(val text: StringResource, val line: Int?, vararg val args: Any) : Exception(text.key)

class DataFormatOutcome(val output: String = "", val error: String? = null)

object DataFormats {
    private val jsonOffset = Regex("offset (\\d+)")
    // YAML 1.1 readers break lines on the last three
    private val unquotable = listOf(0x7F, 0x85, 0x2028, 0x2029)

    fun parse(text: String, format: DataFormat): JsonElement = when (format) {
        DataFormat.JSON -> parseJson(text)
        DataFormat.YAML -> Yaml.parse(text)
        DataFormat.TOML -> Toml.parse(text)
    }

    fun write(element: JsonElement, format: DataFormat): String = when (format) {
        DataFormat.JSON -> JsonTools.format(element, 2)
        DataFormat.YAML -> Yaml.write(element)
        DataFormat.TOML -> Toml.write(element)
    }

    private fun parseJson(text: String): JsonElement = JsonTools.parse(text).getOrElse { e ->
        if (e is JsonTooDeepException) throw tooDeep(null)
        val offset = e.message?.let { jsonOffset.find(it) }?.groupValues?.get(1)?.toIntOrNull()
        throw DataFormatException(Res.string.data_format_bad_json, lineAt(text, offset ?: text.length))
    }

    fun tooDeep(line: Int?) = DataFormatException(Res.string.json_too_deep, line, JsonTools.MAX_DEPTH)

    fun lineAt(text: String, position: Int): Int = text.take(position).count { it == '\n' } + 1

    // JSON escapes are also valid in YAML double quotes and TOML basic strings
    fun quote(text: String): String {
        val escaped = unquotable.fold(JsonTools.escapeString(text)) { s, code ->
            s.replace(Char(code).toString(), "\\u" + code.toString(16).padStart(4, '0'))
        }
        return "\"$escaped\""
    }

    // takes YAML and TOML spellings that JSON refuses: +1, 007, .5, 5.
    @OptIn(ExperimentalSerializationApi::class)
    fun number(raw: String): JsonElement {
        val digits = raw.trimStart('+', '-')
        val mantissa = digits.substringBefore('e').substringBefore('E')
        val whole = mantissa.substringBefore('.').trimStart('0').ifEmpty { "0" }
        val fraction = mantissa.substringAfter('.', "")
        val sign = if (raw.startsWith('-')) "-" else ""
        return JsonUnquotedLiteral(sign + whole + (if (fraction.isEmpty()) "" else ".$fraction") + digits.drop(mantissa.length))
    }

    suspend fun outcome(work: () -> String): DataFormatOutcome = withContext(Dispatchers.Default) {
        try {
            DataFormatOutcome(output = work())
        } catch (e: DataFormatException) {
            val reason = getString(e.text, *e.args)
            DataFormatOutcome(error = e.line?.let { getString(Res.string.data_format_line_error, it, reason) } ?: reason)
        }
    }
}
