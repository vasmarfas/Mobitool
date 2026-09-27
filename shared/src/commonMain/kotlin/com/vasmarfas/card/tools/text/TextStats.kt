package com.vasmarfas.card.tools.text

import kotlin.math.roundToInt

data class TextStatsResult(
    val chars: Int,
    val charsNoSpaces: Int,
    val words: Int,
    val uniqueWords: Int,
    val sentences: Int,
    val paragraphs: Int,
    val lines: Int,
    val utf8Bytes: Int,
    val readingSeconds: Int,
    val speakingSeconds: Int,
    val topWords: List<Pair<String, Int>>,
)
object TextStats {
    private val paragraphBreak = Regex("\\n[ \\t]*\\n")
    private const val TERMINATORS = ".!?…"
    fun words(text: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in text) {
            if (c.isLetterOrDigit() || (sb.isNotEmpty() && (c == '\'' || c == '’' || c == '-'))) {
                sb.append(c)
            } else if (sb.isNotEmpty()) {
                result += sb.toString().trimEnd('\'', '’', '-')
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) result += sb.toString().trimEnd('\'', '’', '-')
        return result.filter { it.isNotEmpty() }
    }
    fun sentences(text: String): Int {
        var count = 0
        var content = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in TERMINATORS) {
                var j = i
                while (j < text.length && text[j] in TERMINATORS) j++
                val closing = j >= text.length || text[j].isWhitespace() || text[j] == '"' || text[j] == '»' || text[j] == ')'
                if (content && closing) {
                    count++
                    content = false
                }
                i = j
                continue
            }
            if (!c.isWhitespace()) content = true
            i++
        }
        if (content) count++
        return count
    }
    fun analyze(text: String): TextStatsResult {
        val words = words(text)
        val freq = words.groupingBy { it.lowercase() }.eachCount()
        val top = freq.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(10)
            .map { it.key to it.value }
        val codePoints = text.codePointList()
        return TextStatsResult(
            chars = codePoints.size,
            charsNoSpaces = codePoints.count { it > 0x20 && it != 0xA0 && it != 0x2028 && it != 0x2029 },
            words = words.size,
            uniqueWords = freq.size,
            sentences = sentences(text),
            paragraphs = if (text.isBlank()) 0 else text.split(paragraphBreak).count { it.isNotBlank() },
            lines = if (text.isEmpty()) 0 else text.lines().size - (if (text.last() == '\n' || text.last() == '\r') 1 else 0),
            utf8Bytes = text.encodeToByteArray().size,
            readingSeconds = (words.size * 60.0 / 200).roundToInt(),
            speakingSeconds = (words.size * 60.0 / 130).roundToInt(),
            topWords = top,
        )
    }
}