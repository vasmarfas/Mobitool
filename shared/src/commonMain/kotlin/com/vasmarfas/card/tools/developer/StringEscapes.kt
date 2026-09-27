package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.text.appendCodePoint
import com.vasmarfas.card.tools.text.codePointList
import org.jetbrains.compose.resources.StringResource

enum class EscapeTarget(val title: StringResource) {
    JSON(Res.string.json),
    JAVA(Res.string.java),
    KOTLIN(Res.string.kotlin),
    C(Res.string.c),
    JAVASCRIPT(Res.string.javascript),
    HTML(Res.string.html_entities),
    XML(Res.string.xml),
    SHELL(Res.string.shell_single_quotes),
    SQL(Res.string.sql),
    URI(Res.string.uri),
}

object StringEscapes {
    private val htmlEntities = mapOf(
        '&' to "&amp;", '<' to "&lt;", '>' to "&gt;", '"' to "&quot;", '\'' to "&#39;", ' ' to "&nbsp;",
        '©' to "&copy;", '®' to "&reg;", '™' to "&trade;", '«' to "&laquo;", '»' to "&raquo;", '—' to "&mdash;",
        '–' to "&ndash;", '…' to "&hellip;", '€' to "&euro;", '£' to "&pound;", '§' to "&sect;", '°' to "&deg;",
        '±' to "&plusmn;", '×' to "&times;", '÷' to "&divide;", '·' to "&middot;", '•' to "&bull;",
    )

    private val htmlNamed = mapOf(
        "amp" to '&', "lt" to '<', "gt" to '>', "quot" to '"', "apos" to '\'', "nbsp" to ' ',
        "copy" to '©', "reg" to '®', "trade" to '™', "laquo" to '«', "raquo" to '»', "mdash" to '—',
        "ndash" to '–', "hellip" to '…', "euro" to '€', "pound" to '£', "sect" to '§', "deg" to '°',
        "plusmn" to '±', "times" to '×', "divide" to '÷', "middot" to '·', "bull" to '•',
    )

    fun escape(text: String, target: EscapeTarget): String = when (target) {
        EscapeTarget.JSON -> JsonTools.escapeString(text)
        EscapeTarget.JAVA, EscapeTarget.KOTLIN, EscapeTarget.JAVASCRIPT -> backslash(text, target)
        EscapeTarget.C -> cString(text)
        EscapeTarget.HTML -> buildString {
            for (c in text) append(htmlEntities[c] ?: c.toString())
        }
        EscapeTarget.XML -> buildString {
            for (c in text) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(c)
                }
            }
        }
        EscapeTarget.SHELL -> "'" + text.replace("'", "'\\''") + "'"
        EscapeTarget.SQL -> "'" + text.replace("'", "''") + "'"
        EscapeTarget.URI -> UrlCodec.encodeComponent(text)
    }

    private fun backslash(text: String, target: EscapeTarget): String = buildString {
        for (c in text) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\b' -> append("\\b")
                c == '\u000C' && target != EscapeTarget.KOTLIN -> append("\\f")
                c == '$' && target != EscapeTarget.JAVA -> append("\\$")
                c.code < 0x20 || c.code == 0x7F || c.code > 0x7E && target != EscapeTarget.JAVASCRIPT ->
                    append("\\u").append(c.code.toString(16).uppercase().padStart(4, '0'))
                else -> append(c)
            }
        }
    }

    private fun cString(text: String): String = buildString {
        for (cp in text.codePointList()) {
            when {
                cp == '\\'.code -> append("\\\\")
                cp == '"'.code -> append("\\\"")
                cp == '\n'.code -> append("\\n")
                cp == '\r'.code -> append("\\r")
                cp == '\t'.code -> append("\\t")
                cp == '\b'.code -> append("\\b")
                cp == 0x0C -> append("\\f")
                cp < 0x20 || cp in 0x7F..0x9F -> append('\\').append(cp.toString(8).padStart(3, '0'))
                cp > 0xFFFF -> append("\\U").append(cp.toString(16).uppercase().padStart(8, '0'))
                cp > 0x7E -> append("\\u").append(cp.toString(16).uppercase().padStart(4, '0'))
                else -> appendCodePoint(cp)
            }
        }
    }

    fun unescape(text: String, target: EscapeTarget): String? = when (target) {
        EscapeTarget.JSON -> JsonTools.unescapeString(text)
        EscapeTarget.JAVA, EscapeTarget.KOTLIN, EscapeTarget.C, EscapeTarget.JAVASCRIPT -> unescapeBackslash(text)
        EscapeTarget.HTML, EscapeTarget.XML -> unescapeEntities(text)
        EscapeTarget.SHELL -> {
            val t = text.trim()
            if (t.startsWith("'") && t.endsWith("'") && t.length >= 2) t.substring(1, t.length - 1).replace("'\\''", "'") else t
        }
        EscapeTarget.SQL -> {
            val t = text.trim()
            if (t.startsWith("'") && t.endsWith("'") && t.length >= 2) t.substring(1, t.length - 1).replace("''", "'") else t
        }
        EscapeTarget.URI -> UrlCodec.decode(text)
    }

    private fun unescapeBackslash(text: String): String? {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\') {
                sb.append(c)
                i++
                continue
            }
            val next = text.getOrNull(i + 1) ?: return null
            i += 2
            when (next) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                in '0'..'7' -> {
                    var code = next - '0'
                    var digits = 1
                    while (digits < 3 && text.getOrNull(i) in '0'..'7') {
                        code = code * 8 + (text[i] - '0')
                        i++
                        digits++
                    }
                    sb.append(code.toChar())
                }
                'U' -> {
                    if (i + 8 > text.length) return null
                    val cp = text.substring(i, i + 8).toIntOrNull(16)?.takeIf { it <= 0x10FFFF } ?: return null
                    sb.appendCodePoint(cp)
                    i += 8
                }
                '\\' -> sb.append('\\')
                '"' -> sb.append('"')
                '\'' -> sb.append('\'')
                '$' -> sb.append('$')
                'u' -> {
                    if (text.getOrNull(i) == '{') {
                        val close = text.indexOf('}', i)
                        if (close < 0) return null
                        val cp = text.substring(i + 1, close).toIntOrNull(16) ?: return null
                        sb.appendCodePoint(cp)
                        i = close + 1
                    } else {
                        if (i + 4 > text.length) return null
                        val code = text.substring(i, i + 4).toIntOrNull(16) ?: return null
                        sb.append(code.toChar())
                        i += 4
                    }
                }
                'x' -> {
                    if (i + 2 > text.length) return null
                    val code = text.substring(i, i + 2).toIntOrNull(16) ?: return null
                    sb.append(code.toChar())
                    i += 2
                }
                else -> sb.append(next)
            }
        }
        return sb.toString()
    }

    private fun unescapeEntities(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val semi = text.indexOf(';', i)
            if (semi < 0 || semi - i > 10) {
                sb.append(c)
                i++
                continue
            }
            val body = text.substring(i + 1, semi)
            val decoded = when {
                body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)
                body.startsWith("#") -> body.substring(1).toIntOrNull()
                else -> htmlNamed[body]?.code
            }
            if (decoded == null) {
                sb.append(c)
                i++
            } else {
                sb.appendCodePoint(decoded)
                i = semi + 1
            }
        }
        return sb.toString()
    }

    fun charCount(text: String): Int = text.codePointList().size
}
