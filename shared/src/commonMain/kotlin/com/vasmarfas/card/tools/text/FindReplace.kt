package com.vasmarfas.card.tools.text

data class FindReplaceResult(
    val output: String,
    val count: Int,
    val error: String? = null,
)

object FindReplace {
    fun run(
        text: String,
        find: String,
        replacement: String,
        ignoreCase: Boolean,
        useRegex: Boolean,
        wholeWord: Boolean,
    ): FindReplaceResult {
        if (find.isEmpty()) return FindReplaceResult(text, 0)
        val options = buildSet {
            if (ignoreCase) add(RegexOption.IGNORE_CASE)
            if (useRegex) add(RegexOption.MULTILINE)
        }
        val regex = try {
            Regex(if (useRegex) find else Regex.escape(find), options)
        } catch (e: Exception) {
            return FindReplaceResult(text, 0, e.message.orEmpty())
        }
        var count = 0
        val output = try {
            regex.replace(text) { m ->
                if (wholeWord && !isWholeWord(text, m.range)) {
                    m.value
                } else {
                    count++
                    if (useRegex) expand(replacement, m) else replacement
                }
            }
        } catch (e: Exception) {
            return FindReplaceResult(text, 0, e.message.orEmpty())
        }
        return FindReplaceResult(output, count)
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun isWholeWord(text: String, range: IntRange): Boolean {
        val before = text.getOrNull(range.first - 1)
        val after = text.getOrNull(range.last + 1)
        return (before == null || !isWordChar(before)) && (after == null || !isWordChar(after))
    }

    private fun expand(template: String, m: MatchResult): String {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            val next = template.getOrNull(i + 1)
            when {
                c == '$' && next != null && next.isDigit() -> {
                    sb.append(m.groupValues.getOrElse(next - '0') { "" })
                    i += 2
                }
                c == '$' && next == '$' -> {
                    sb.append('$')
                    i += 2
                }
                c == '\\' && next == 'n' -> {
                    sb.append('\n')
                    i += 2
                }
                c == '\\' && next == 't' -> {
                    sb.append('\t')
                    i += 2
                }
                c == '\\' && next == '\\' -> {
                    sb.append('\\')
                    i += 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }
}
