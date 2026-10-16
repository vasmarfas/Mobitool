package com.vasmarfas.card.tools.documents

import com.vasmarfas.card.core.OcrLine
import com.vasmarfas.card.core.OcrPage

object OcrText {
    private const val PARAGRAPH_GAP = 0.8

    private val softHyphen = Char(0xAD)

    fun paragraphs(lines: List<OcrLine>): List<List<OcrLine>> {
        val visible = lines.filter { it.text.isNotBlank() }
        if (visible.isEmpty()) return emptyList()
        val median = visible.map { it.height }.sorted()[visible.size / 2].coerceAtLeast(1)
        val result = mutableListOf(mutableListOf(visible.first()))
        visible.zipWithNext { previous, line ->
            val gap = line.top - previous.bottom
            if (gap > median * PARAGRAPH_GAP || line.bottom < previous.top) result += mutableListOf(line) else result.last() += line
        }
        return result
    }

    fun text(page: OcrPage, joinLines: Boolean): String = paragraphs(page.lines).joinToString("\n\n") { paragraph ->
        val lines = paragraph.map { it.text.trim() }
        if (joinLines) join(lines) else lines.joinToString("\n")
    }

    fun join(lines: List<String>): String = buildString {
        lines.filter { it.isNotEmpty() }.forEach { line ->
            val last = lastOrNull()
            when {
                last == null -> append(line)
                (last == '-' || last == softHyphen) && length >= 2 && this[length - 2].isLetter() && line.first().isLowerCase() -> {
                    setLength(length - 1)
                    append(line)
                }
                else -> append(' ').append(line)
            }
        }
    }

    fun pages(texts: List<String>): String = texts.filter { it.isNotBlank() }.joinToString("\n\n")
}
