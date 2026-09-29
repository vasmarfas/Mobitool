package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.core.dotMatchesAll
import com.vasmarfas.card.resources.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource

@Serializable
data class RegexMatchInfo(
    val index: Int,
    val value: String,
    val start: Int,
    val end: Int,
    val groups: List<String?>,
)

@Serializable
data class RegexRunResult(
    val text: String,
    val matches: List<RegexMatchInfo>,
    val error: String?,
    val replaced: String?,
    val timedOut: Boolean = false,
)

expect suspend fun runRegex(
    pattern: String,
    text: String,
    ignoreCase: Boolean,
    multiline: Boolean,
    dotAll: Boolean,
    replacement: String?,
): RegexRunResult

private class RegexTimeout : RuntimeException()

private class DeadlineText(private val text: CharSequence, private val deadline: TimeMark) : CharSequence {
    private var reads = 0

    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        if ((++reads and 0xFFF) == 0 && deadline.hasPassedNow()) throw RegexTimeout()
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

    override fun toString(): String = text.toString()
}

object RegexTester {
    const val MAX_MATCHES = 500
    const val TIME_LIMIT_MS = 2_000L

    fun run(
        pattern: String,
        text: String,
        ignoreCase: Boolean,
        multiline: Boolean,
        dotAll: Boolean,
        replacement: String?,
    ): RegexRunResult {
        if (pattern.isEmpty()) return RegexRunResult(text, emptyList(), null, null)
        val options = buildSet {
            if (ignoreCase) add(RegexOption.IGNORE_CASE)
            if (multiline) add(RegexOption.MULTILINE)
            if (dotAll) add(dotMatchesAll)
        }
        val regex = try {
            Regex(pattern, options)
        } catch (e: Exception) {
            return RegexRunResult(text, emptyList(), reason(e), null)
        }
        val input = DeadlineText(text, TimeSource.Monotonic.markNow() + TIME_LIMIT_MS.milliseconds)
        return try {
            val matches = regex.findAll(input).take(MAX_MATCHES).mapIndexed { i, m ->
                RegexMatchInfo(i, m.value, m.range.first, m.range.last + 1, m.groups.drop(1).map { it?.value })
            }.toList()
            val replaced = replacement?.let { regex.replace(input, it) }
            RegexRunResult(text, matches, null, replaced)
        } catch (e: RegexTimeout) {
            RegexRunResult(text, emptyList(), null, null, timedOut = true)
        } catch (e: Exception) {
            RegexRunResult(text, emptyList(), reason(e), null)
        }
    }

    private fun reason(e: Exception): String = e.message?.substringBefore('\n') ?: e.toString()

    val cheatSheet: List<Pair<String, StringResource>> = listOf(
        "." to Res.string.any_character_except_newline,
        "\\d  \\D" to Res.string.digit_non_digit,
        "\\w  \\W" to Res.string.word_character_a_za_z0_9_other,
        "\\s  \\S" to Res.string.whitespace_non_whitespace,
        "\\b" to Res.string.regex_word_boundary,
        "^  $" to Res.string.regex_start_end_of_text,
        "[abc]  [^abc]" to Res.string.one_of_none_of_the_characters,
        "[a-z]" to Res.string.character_range,
        "a|b" to Res.string.regex_alternation,
        "(…)" to Res.string.regex_capturing_group,
        "(?:…)" to Res.string.non_capturing_group,
        "(?<name>…)" to Res.string.named_group,
        "*  +  ?" to Res.string.s_0_or_more_1_or_more_0_or_1,
        "{n}  {n,}  {n,m}" to Res.string.exactly_n_at_least_n_from_n_to_m,
        "*?  +?  ??" to Res.string.lazy_quantifiers,
        "(?=…)  (?!…)" to Res.string.positive_negative_lookahead,
        "(?<=…)  (?<!…)" to Res.string.positive_negative_lookbehind,
        "\\1  $1" to Res.string.regex_back_reference_in_pattern,
        "\\.  \\\\  \\(" to Res.string.escaped_special_characters,
        "\\t  \\n  \\r" to Res.string.tab_newline_carriage_return,
        "\\uFFFF" to Res.string.character_by_code,
    )
}
