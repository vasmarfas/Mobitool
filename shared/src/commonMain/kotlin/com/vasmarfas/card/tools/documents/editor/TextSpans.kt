package com.vasmarfas.card.tools.documents.editor

internal data class Look(val bold: Boolean, val italic: Boolean, val color: Int)

internal val TextMark.look: Look get() = Look(bold, italic, color)

internal fun spansOf(looks: List<Look>, base: Look): List<StyleSpan> {
    val spans = ArrayList<StyleSpan>()
    var start = 0
    for (i in 1..looks.size) {
        if (i < looks.size && looks[i] == looks[start]) continue
        val look = looks[start]
        if (look != base) spans += StyleSpan(start, i, look.bold, look.italic, look.color)
        start = i
    }
    return spans
}

internal fun TextMark.looks(): List<Look> {
    val looks = MutableList(text.length) { look }
    for (span in spans) {
        for (i in span.start until span.end.coerceAtMost(text.length)) looks[i] = Look(span.bold, span.italic, span.color)
    }
    return looks
}

internal fun TextMark.restyled(next: TextLook): TextMark {
    val looks = looks().map {
        Look(if (next.bold != bold) next.bold else it.bold, if (next.italic != italic) next.italic else it.italic, if (next.color != color) next.color else it.color)
    }
    val spans = spansOf(looks, Look(next.bold, next.italic, next.color))
    return copy(color = next.color, size = next.size, font = next.font, bold = next.bold, italic = next.italic, align = next.align, spans = spans)
}

internal fun TextMark.lookAt(index: Int): Look = looks().getOrElse(index) { look }

// only what the toolbar moved away from the look it showed reaches the range, the runs inside keep the rest
internal fun TextMark.restyledRange(start: Int, end: Int, shown: Look, next: TextLook): TextMark {
    val looks = looks().mapIndexed { i, it ->
        if (i !in start until end) {
            it
        } else {
            Look(
                if (next.bold != shown.bold) next.bold else it.bold,
                if (next.italic != shown.italic) next.italic else it.italic,
                if (next.color != shown.color) next.color else it.color,
            )
        }
    }
    return copy(spans = spansOf(looks, look))
}

internal fun TextMark.withText(next: String): TextMark {
    if (spans.isEmpty() || next == text) return copy(text = next)
    var prefix = 0
    while (prefix < text.length && prefix < next.length && text[prefix] == next[prefix]) prefix++
    var suffix = 0
    while (suffix < text.length - prefix && suffix < next.length - prefix && text[text.length - 1 - suffix] == next[next.length - 1 - suffix]) suffix++
    val old = looks()
    val replaced = text.length - suffix > prefix
    val typed = when {
        replaced -> old[prefix]
        prefix > 0 -> old[prefix - 1]
        old.isNotEmpty() -> old[0]
        else -> look
    }
    val looks = old.subList(0, prefix) + List(next.length - suffix - prefix) { typed } + old.subList(text.length - suffix, text.length)
    return copy(text = next, spans = spansOf(looks, look))
}
