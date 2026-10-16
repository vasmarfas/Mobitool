package com.vasmarfas.card.tools.media

class TextSample(val timeMs: Long, val text: String)

class TextSpan(val startMs: Long, val endMs: Long, val text: String)

object VideoText {
    const val SAME_TEXT = 0.75

    private const val MAX_COMPARED = 400

    fun normalize(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    fun similarity(a: String, b: String): Double {
        val x = normalize(a).take(MAX_COMPARED)
        val y = normalize(b).take(MAX_COMPARED)
        if (x.isEmpty() && y.isEmpty()) return 1.0
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var previous = IntArray(y.length + 1) { it }
        var current = IntArray(y.length + 1)
        for (i in 1..x.length) {
            current[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current.also { current = previous }
        }
        return 1.0 - previous[y.length].toDouble() / maxOf(x.length, y.length)
    }

    fun spans(samples: List<TextSample>, stepMs: Long): List<TextSpan> {
        val spans = mutableListOf<TextSpan>()
        var start = 0L
        var last = 0L
        var best: String? = null
        fun close() {
            best?.let { spans += TextSpan(start, last + stepMs, it) }
            best = null
        }
        samples.sortedBy { it.timeMs }.forEach { sample ->
            val text = sample.text.trim()
            val open = best
            when {
                normalize(text).isEmpty() -> close()
                open != null && similarity(open, text) >= SAME_TEXT -> {
                    last = sample.timeMs
                    if (text.length > open.length) best = text
                }
                else -> {
                    close()
                    start = sample.timeMs
                    last = sample.timeMs
                    best = text
                }
            }
        }
        close()
        return spans
    }

    fun brightText(pixels: IntArray, threshold: Int = 180): IntArray = IntArray(pixels.size) { i ->
        val p = pixels[i]
        val y = (299 * (p shr 16 and 0xFF) + 587 * (p shr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000
        if (y > threshold) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    fun clock(ms: Long, separator: Char): String {
        val total = ms.coerceAtLeast(0)
        val hours = total / 3_600_000
        val minutes = total / 60_000 % 60
        val seconds = total / 1000 % 60
        val millis = total % 1000
        return "${two(hours)}:${two(minutes)}:${two(seconds)}$separator${millis.toString().padStart(3, '0')}"
    }

    private fun two(value: Long): String = value.toString().padStart(2, '0')

    fun srt(spans: List<TextSpan>): String = spans.mapIndexed { index, span ->
        "${index + 1}\n${clock(span.startMs, ',')} --> ${clock(span.endMs, ',')}\n${span.text}\n"
    }.joinToString("\n")

    fun plain(spans: List<TextSpan>, withTimes: Boolean): String = spans.joinToString("\n\n") { span ->
        if (withTimes) "[${clock(span.startMs, '.').substringBefore('.')}] ${span.text}" else span.text
    }
}
