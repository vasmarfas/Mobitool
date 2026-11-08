package com.vasmarfas.card.tools.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnsiTerminalTest {
    private val esc = Char(27)

    private fun terminal(columns: Int = 20, rows: Int = 4) = AnsiTerminal(columns, rows)

    private fun AnsiTerminal.write(text: String) = apply { feed(text.encodeToByteArray()) }

    private fun AnsiTerminal.line(index: Int) = runs(index).joinToString("") { it.text }.trimEnd()

    @Test
    fun carriageReturnOverwritesTheLine() {
        assertEquals("Xbc", terminal().write("abc\rX").line(0))
    }

    @Test
    fun lineFeedKeepsTheColumnAndCrLfResetsIt() {
        val screen = terminal().write("ab\ncd\r\nef")
        assertEquals("ab", screen.line(0))
        assertEquals("  cd", screen.line(1))
        assertEquals("ef", screen.line(2))
    }

    @Test
    fun backspaceThenEraseToEndIsHowReadlineDeletes() {
        assertEquals("ab", terminal().write("abc\b$esc[K").line(0))
    }

    @Test
    fun tabStopsEveryEightColumns() {
        assertEquals("a       b", terminal().write("a\tb").line(0))
    }

    @Test
    fun longLineWrapsToTheNextRow() {
        val screen = terminal(columns = 3).write("abcd")
        assertEquals("abc", screen.line(0))
        assertEquals("d", screen.line(1))
    }

    @Test
    fun scrollbackIsCapped() {
        val screen = AnsiTerminal(10, 2, scrollback = 1).write("a\r\nb\r\nc\r\nd")
        assertEquals(3, screen.lineCount)
        assertEquals("b", screen.line(0))
        assertEquals("d", screen.line(2))
    }

    @Test
    fun cursorPositionIsOneBased() {
        assertEquals("  X", terminal().write("$esc[1;3HX").line(0))
        assertEquals("X", terminal().write("abc$esc[HX").line(0).take(1))
        assertEquals("  X", terminal().write("$esc[2;3HX").line(1))
    }

    @Test
    fun relativeMovesStopAtTheEdges() {
        assertEquals("aXc", terminal().write("abc$esc[2DX").line(0))
        assertEquals("X", terminal().write("$esc[99A$esc[99DX").line(0))
        assertEquals("X", terminal(columns = 5).write("$esc[99CX").line(0).trim())
    }

    @Test
    fun eraseInLineModes() {
        assertEquals("abc", terminal().write("abcdef$esc[3D$esc[0K").line(0))
        assertEquals("   def", terminal().write("abcdef\r$esc[2C$esc[1K").line(0))
        assertEquals("", terminal().write("abcdef$esc[2K").line(0))
    }

    @Test
    fun clearCommandEmptiesTheScreenAndScrollback() {
        val screen = AnsiTerminal(10, 2).write("a\r\nb\r\nc")
        assertEquals(3, screen.lineCount)
        screen.write("$esc[H$esc[2J$esc[3J")
        assertEquals(2, screen.lineCount)
        assertEquals("", screen.line(0))
        assertEquals("", screen.line(1))
    }

    @Test
    fun eraseBelowKeepsWhatIsAbove() {
        val screen = terminal().write("one\r\ntwo\r\nthree$esc[2;2H$esc[J")
        assertEquals("one", screen.line(0))
        assertEquals("t", screen.line(1))
        assertEquals("", screen.line(2))
    }

    @Test
    fun deleteInsertAndEraseCharacters() {
        assertEquals("bc", terminal().write("abc\r$esc[P").line(0))
        assertEquals("  abc", terminal().write("abc\r$esc[2@").line(0))
        assertEquals("  c", terminal().write("abc\r$esc[2X").line(0))
    }

    @Test
    fun sgrColorsFollowEcma48() {
        val runs = terminal().write("$esc[31mred$esc[0m plain").runs(0)
        assertEquals("red", runs[0].text)
        assertEquals(1, runs[0].style.foreground)
        assertEquals(" plain", runs[1].text)
        assertEquals(TerminalStyle(), runs[1].style)
    }

    @Test
    fun sgrBrightBackgroundAndAttributes() {
        val style = terminal().write("$esc[1;4;7;92;104mx$esc[H").runs(0).last().style
        assertEquals(10, style.foreground)
        assertEquals(12, style.background)
        assertTrue(style.bold)
        assertTrue(style.underline)
    }

    @Test
    fun sgrExtendedColors() {
        assertEquals(196, terminal().write("$esc[38;5;196mx\r\n").runs(0)[0].style.foreground)
        assertEquals(21, terminal().write("$esc[48;5;21mx\r\n").runs(0)[0].style.background)
        val rgb = terminal().write("$esc[38;2;1;2;3;1mx\r\n").runs(0)[0].style
        assertEquals(0x010203, TerminalPalette.rgb(rgb.foreground))
        assertTrue(rgb.bold)
    }

    @Test
    fun cursorCellIsInverted() {
        val runs = terminal().write("ab\b").runs(0)
        assertEquals("a", runs[0].text)
        assertFalse(runs[0].style.inverse)
        assertEquals("b", runs[1].text)
        assertTrue(runs[1].style.inverse)
    }

    @Test
    fun oscTitleIsNotPrinted() {
        assertEquals("ok", terminal().write("$esc]0;user@example.com${Char(7)}ok").line(0))
        assertEquals("ok", terminal().write("$esc]2;title$esc\\ok").line(0))
    }

    @Test
    fun charsetAndUnknownSequencesAreSkipped() {
        assertEquals("ok", terminal().write("$esc(Bok").line(0))
        assertEquals("ok", terminal().write("$esc[?2004h$esc[>cok").line(0))
    }

    @Test
    fun sequenceSplitBetweenReadsStillParses() {
        val screen = terminal().write("$esc[3").write("1mx\r\n")
        assertEquals(1, screen.runs(0)[0].style.foreground)
    }

    @Test
    fun utf8SplitBetweenReadsIsDecoded() {
        val bytes = "Привет".encodeToByteArray()
        val screen = terminal()
        screen.feed(bytes.copyOfRange(0, 3))
        screen.feed(bytes.copyOfRange(3, bytes.size))
        assertEquals("Привет", screen.line(0))
    }

    @Test
    fun fourByteCharacterBecomesASurrogatePair() {
        val screen = terminal()
        screen.feed(byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte()))
        assertEquals("😀", screen.line(0))
    }

    @Test
    fun feedRespectsLength() {
        val screen = terminal()
        screen.feed("abcdef".encodeToByteArray(), 2)
        assertEquals("ab", screen.line(0))
    }

    @Test
    fun oversizedParametersStayInsideTheScreen() {
        val screen = terminal(columns = 5, rows = 2).write("$esc[99999999999;99999999999HX$esc[99999@$esc[99999P")
        assertEquals(2, screen.lineCount)
    }

    @Test
    fun narrowerScreenPullsTheCursorIn() {
        val screen = terminal(columns = 10).write("abcdefgh")
        screen.resize(4)
        assertEquals(4, screen.columns)
        screen.write("X")
        assertEquals("abcXefgh", screen.line(0))
    }

    @Test
    fun applicationCursorModeFollowsDecckm() {
        val screen = terminal().write("$esc[?1h")
        assertTrue(screen.applicationCursor)
        assertFalse(screen.write("$esc[?1l").applicationCursor)
    }

    @Test
    fun arrowKeysMatchXtermSequences() {
        assertEquals("$esc[A", TerminalKeys.arrow(TerminalKey.UP, application = false))
        assertEquals("$esc[D", TerminalKeys.arrow(TerminalKey.LEFT, application = false))
        assertEquals("${esc}OB", TerminalKeys.arrow(TerminalKey.DOWN, application = true))
        assertEquals("${esc}OC", TerminalKeys.arrow(TerminalKey.RIGHT, application = true))
    }

    @Test
    fun controlKeysClearTheHighBits() {
        assertEquals(3, TerminalKeys.control('c')?.toInt())
        assertEquals(3, TerminalKeys.control('C')?.toInt())
        assertEquals(4, TerminalKeys.control('d')?.toInt())
        assertEquals(27, TerminalKeys.control('[')?.toInt())
        assertEquals(0, TerminalKeys.control('@')?.toInt())
        assertNull(TerminalKeys.control('1'))
    }

    @Test
    fun paletteMatchesTheXterm256Table() {
        assertEquals(0x000000, TerminalPalette.rgb(16))
        assertEquals(0x5F87AF, TerminalPalette.rgb(67))
        assertEquals(0xFF0000, TerminalPalette.rgb(196))
        assertEquals(0xFFFFFF, TerminalPalette.rgb(231))
        assertEquals(0x080808, TerminalPalette.rgb(232))
        assertEquals(0xEEEEEE, TerminalPalette.rgb(255))
    }

    @Test
    fun runsCoverEveryCellOnce() {
        val runs = terminal().write("$esc[31ma$esc[32mb$esc[H").runs(0)
        assertContentEquals(listOf("a", "b"), runs.map { it.text })
    }

    @Test
    fun alternateScreenLeavesTheShellAsItWas() {
        val screen = terminal().write("prompt$esc[?1049h$esc[2J$esc[Hvim")
        assertEquals(4, screen.lineCount)
        assertEquals("vim", screen.line(0))
        screen.write("$esc[?1049l")
        assertEquals("prompt", screen.line(0))
    }

    @Test
    fun scrollRegionKeepsTheStatusLine() {
        val screen = terminal(rows = 4).write("a\r\nb\r\nc\r\nstatus$esc[1;3r$esc[3;1H\nd")
        assertEquals(4, screen.lineCount)
        assertEquals("b", screen.line(0))
        assertEquals("d", screen.line(2))
        assertEquals("status", screen.line(3))
    }

    @Test
    fun reverseIndexAtTheTopScrollsDown() {
        val screen = terminal(rows = 3).write("a\r\nb\r\nc$esc[H${esc}M")
        assertEquals("", screen.line(0))
        assertEquals("a", screen.line(1))
        assertEquals("b", screen.line(2))
    }

    @Test
    fun insertAndDeleteLines() {
        val screen = terminal(rows = 3).write("a\r\nb\r\nc$esc[2H$esc[L")
        assertEquals(listOf("a", "", "b"), (0..2).map { screen.line(it) })
        screen.write("$esc[2M")
        assertEquals(listOf("a", "", ""), (0..2).map { screen.line(it) })
    }

    @Test
    fun savedCursorComesBack() {
        assertEquals("aXc", terminal().write("a${esc}7bc${esc}8X").line(0))
    }

    @Test
    fun hiddenCursorIsNotDrawn() {
        assertTrue(terminal().write("$esc[?25l").runs(0).none { it.style.inverse })
    }

    @Test
    fun statusQueriesAreAnswered() {
        val screen = terminal().write("ab$esc[6n$esc[c")
        assertEquals("$esc[1;3R$esc[?1;2c", screen.takeReplies())
        assertEquals("", screen.takeReplies())
    }
}
