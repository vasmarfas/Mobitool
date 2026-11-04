package com.vasmarfas.card.tools.documents.editor

import com.vasmarfas.card.tools.documents.pdf.PdfRect
import kotlin.test.Test
import kotlin.test.assertEquals

class TextSpansTest {
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF1565C0.toInt()
    private val mark = TextMark(1, PdfRect(0.0, 0.0, 200.0, 20.0), "plain red", 12f, black, spans = listOf(StyleSpan(6, 9, false, false, red)))

    @Test
    fun typedTextTakesTheLookOfWhatItJoins() {
        assertEquals(listOf(StyleSpan(6, 12, false, false, red)), mark.withText("plain redder").spans)
        assertEquals(listOf(StyleSpan(9, 12, false, false, red)), mark.withText("so plain red").spans)
        assertEquals(listOf(StyleSpan(6, 10, false, false, red)), mark.withText("plain blue").spans)
        assertEquals(emptyList(), mark.withText("plain").spans)
    }

    @Test
    fun theToolbarChangesEveryRunAndKeepsTheRest() {
        val bold = mark.restyled(TextLook(black, 14f, MarkFont.SERIF, bold = true, italic = false, MarkAlign.CENTER))
        assertEquals(true, bold.bold)
        assertEquals(listOf(StyleSpan(6, 9, true, false, red)), bold.spans)
        assertEquals(emptyList(), mark.restyled(TextLook(blue, 12f, MarkFont.SANS, bold = false, italic = false, MarkAlign.START)).spans)
    }

    private fun look(color: Int, bold: Boolean = false, italic: Boolean = false) = TextLook(color, 12f, MarkFont.SANS, bold, italic, MarkAlign.START)

    @Test
    fun aRangeChangesOnlyItsOwnLetters() {
        val bold = mark.restyledRange(0, 5, mark.lookAt(0), look(black, bold = true))
        assertEquals(listOf(StyleSpan(0, 5, true, false, black), StyleSpan(6, 9, false, false, red)), bold.spans)
        assertEquals(false, bold.bold)
        assertEquals(black, bold.color)
    }

    @Test
    fun aRangeOverTwoRunsKeepsWhatTheToolbarLeftAlone() {
        val italic = mark.restyledRange(3, 8, mark.lookAt(3), look(black, italic = true))
        assertEquals(
            listOf(StyleSpan(3, 6, false, true, black), StyleSpan(6, 8, false, true, red), StyleSpan(8, 9, false, false, red)),
            italic.spans,
        )
        val allRed = mark.restyledRange(0, 6, mark.lookAt(0), look(red))
        assertEquals(listOf(StyleSpan(0, 9, false, false, red)), allRed.spans)
    }

    @Test
    fun clearingARangeDropsTheSpan() {
        val bold = mark.copy(spans = emptyList()).restyledRange(2, 4, mark.look, look(black, bold = true))
        val plain = bold.restyledRange(2, 4, bold.lookAt(2), look(black))
        assertEquals(emptyList(), plain.spans)
    }
}
