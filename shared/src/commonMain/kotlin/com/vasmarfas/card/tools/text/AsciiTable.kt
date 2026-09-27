package com.vasmarfas.card.tools.text

import com.vasmarfas.card.resources.*
import org.jetbrains.compose.resources.StringResource

data class AsciiEntry(val code: Int, val symbol: String, val name: StringResource) {
    val hex: String get() = code.toString(16).uppercase().padStart(2, '0')
    val oct: String get() = code.toString(8).padStart(3, '0')
    val bin: String get() = code.toString(2).padStart(8, '0')
    val isControl: Boolean get() = code < 32 || code == 127
}

object AsciiTable {
    private val controls = listOf(
        "NUL" to Res.string.ascii_nul, "SOH" to Res.string.ascii_soh, "STX" to Res.string.ascii_stx, "ETX" to Res.string.ascii_etx,
        "EOT" to Res.string.ascii_eot, "ENQ" to Res.string.ascii_enq, "ACK" to Res.string.ascii_ack, "BEL" to Res.string.ascii_bel,
        "BS" to Res.string.ascii_bs, "HT" to Res.string.ascii_ht, "LF" to Res.string.ascii_lf, "VT" to Res.string.ascii_vt,
        "FF" to Res.string.ascii_ff, "CR" to Res.string.ascii_cr, "SO" to Res.string.ascii_so, "SI" to Res.string.ascii_si,
        "DLE" to Res.string.ascii_dle, "DC1" to Res.string.ascii_dc1, "DC2" to Res.string.ascii_dc2,
        "DC3" to Res.string.ascii_dc3, "DC4" to Res.string.ascii_dc4, "NAK" to Res.string.ascii_nak,
        "SYN" to Res.string.ascii_syn, "ETB" to Res.string.ascii_etb, "CAN" to Res.string.ascii_can, "EM" to Res.string.ascii_em,
        "SUB" to Res.string.ascii_sub, "ESC" to Res.string.ascii_esc, "FS" to Res.string.ascii_fs, "GS" to Res.string.ascii_gs,
        "RS" to Res.string.ascii_rs, "US" to Res.string.ascii_us,
    )

    private val punctuation = mapOf(
        ' ' to Res.string.ascii_space, '!' to Res.string.ascii_exclamation_mark, '"' to Res.string.ascii_quotation_mark,
        '#' to Res.string.ascii_number_sign, '$' to Res.string.ascii_dollar_sign, '%' to Res.string.ascii_percent_sign,
        '&' to Res.string.ascii_ampersand, '\'' to Res.string.ascii_apostrophe, '(' to Res.string.ascii_left_parenthesis,
        ')' to Res.string.ascii_right_parenthesis, '*' to Res.string.ascii_asterisk, '+' to Res.string.ascii_plus_sign,
        ',' to Res.string.ascii_comma, '-' to Res.string.ascii_hyphen_minus, '.' to Res.string.ascii_full_stop,
        '/' to Res.string.ascii_slash, ':' to Res.string.ascii_colon, ';' to Res.string.ascii_semicolon,
        '<' to Res.string.ascii_less_than_sign, '=' to Res.string.ascii_equals_sign, '>' to Res.string.ascii_greater_than_sign,
        '?' to Res.string.ascii_question_mark, '@' to Res.string.ascii_at_sign, '[' to Res.string.ascii_left_square_bracket,
        '\\' to Res.string.ascii_backslash, ']' to Res.string.ascii_right_square_bracket, '^' to Res.string.ascii_caret,
        '_' to Res.string.ascii_underscore, '`' to Res.string.ascii_grave_accent, '{' to Res.string.ascii_left_curly_bracket,
        '|' to Res.string.ascii_vertical_bar, '}' to Res.string.ascii_right_curly_bracket, '~' to Res.string.ascii_tilde,
    )

    val entries: List<AsciiEntry> = (0..127).map { code ->
        when {
            code < 32 -> AsciiEntry(code, controls[code].first, controls[code].second)
            code == 127 -> AsciiEntry(code, "DEL", Res.string.ascii_del)
            else -> {
                val c = code.toChar()
                val name = when {
                    c.isDigit() -> Res.string.ascii_digit
                    c.isUpperCase() -> Res.string.ascii_uppercase
                    c.isLowerCase() -> Res.string.ascii_lowercase
                    else -> punctuation.getValue(c)
                }
                AsciiEntry(code, c.toString(), name)
            }
        }
    }

    fun search(query: String, includeControl: Boolean): List<AsciiEntry> {
        val q = query.trim()
        val lower = q.lowercase()
        val hexQuery = lower.removePrefix("0x")
        return entries.filter { e ->
            (includeControl || !e.isControl) && (
                q.isEmpty() ||
                    e.symbol == q ||
                    e.code.toString() == q ||
                    (hexQuery.length == 2 && e.hex.lowercase() == hexQuery) ||
                    e.nameContains(lower) ||
                    (e.isControl && e.symbol.lowercase().contains(lower))
                )
        }
    }

    // both languages at once, like the tool search
    private fun AsciiEntry.nameContains(query: String): Boolean =
        listOfNotNull(name.english(), name.russian()).any { it.replace("%1\$s", symbol).lowercase().contains(query) }
}
