package com.vasmarfas.card.tools.network

private const val ESC_CODE = 27
private const val BEL_CODE = 7
private const val MAX_PARAMS = 32
private const val MAX_PARAM = 99_999
private const val REPLACEMENT = 0xFFFD

data class TerminalStyle(
    val foreground: Int = DEFAULT_COLOR,
    val background: Int = DEFAULT_COLOR,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
) {
    companion object {
        const val DEFAULT_COLOR = -1
    }
}

class TerminalRun(val text: String, val style: TerminalStyle)

object TerminalPalette {
    const val TRUE_COLOR = 0x1000000

    private val base = intArrayOf(
        0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
        0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
    )
    private val cube = intArrayOf(0, 95, 135, 175, 215, 255)

    fun rgb(color: Int): Int = when {
        color >= TRUE_COLOR -> color and 0xFFFFFF
        color < 16 -> base[color]
        color < 232 -> (color - 16).let { (cube[it / 36] shl 16) or (cube[it / 6 % 6] shl 8) or cube[it % 6] }
        else -> (8 + (color - 232) * 10).let { (it shl 16) or (it shl 8) or it }
    }
}

enum class TerminalKey(val final: Char) { UP('A'), DOWN('B'), RIGHT('C'), LEFT('D') }

object TerminalKeys {
    val ESCAPE = Char(ESC_CODE).toString()
    val BACKSPACE = Char(127).toString()

    fun arrow(key: TerminalKey, application: Boolean): String = "$ESCAPE${if (application) 'O' else '['}${key.final}"

    fun control(letter: Char): Byte? = letter.uppercaseChar().takeIf { it in '@'..'_' }?.let { (it.code and 0x1F).toByte() }
}

private class TerminalCell(val codePoint: Int, val style: TerminalStyle)

private fun StringBuilder.appendScalar(codePoint: Int) {
    if (codePoint < 0x10000) {
        append(Char(codePoint))
    } else {
        val offset = codePoint - 0x10000
        append(Char(0xD800 + (offset shr 10)))
        append(Char(0xDC00 + (offset and 0x3FF)))
    }
}

private fun screenOf(rows: Int) = ArrayDeque<MutableList<TerminalCell>>().apply { repeat(rows) { addLast(mutableListOf()) } }

// shortcut: one cell per code point, so wide characters shift the row, a cell grid with widths is the upgrade
// if CJK output matters
class AnsiTerminal(columns: Int, val rows: Int, private val scrollback: Int = 2000) {
    private enum class State { GROUND, ESCAPE, CSI, STRING, STRING_ESCAPE, SKIP }

    var columns = columns
        private set
    var applicationCursor = false
        private set

    private val main = screenOf(rows)

    // full-screen programs such as vim and htop draw here and leave the main screen and its scrollback as they were
    private var alternate: ArrayDeque<MutableList<TerminalCell>>? = null
    private val lines get() = alternate ?: main
    private val blank = TerminalCell(' '.code, TerminalStyle())
    private var state = State.GROUND
    private var style = TerminalStyle()
    private val params = mutableListOf<Int>()
    private var param = -1
    private var privateMode = false
    private var utf8Left = 0
    private var utf8Value = 0
    private var row = 0
    private var column = 0
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var savedRow = 0
    private var savedColumn = 0
    private var savedStyle = TerminalStyle()
    private var cursorVisible = true
    private var autowrap = true
    private val replies = StringBuilder()

    private val top: Int get() = lines.size - rows
    private val current: MutableList<TerminalCell> get() = lines[top + row]

    val lineCount: Int get() = lines.size

    // answers to status queries such as the cursor position, the caller sends them back to the host
    fun takeReplies(): String = replies.toString().also { replies.clear() }

    fun resize(columns: Int) {
        this.columns = columns
        column = column.coerceAtMost(columns - 1)
    }

    fun runs(index: Int): List<TerminalRun> {
        val line = lines[index]
        val cursor = if (cursorVisible && index == top + row) minOf(column, columns - 1) else -1
        val result = mutableListOf<TerminalRun>()
        val text = StringBuilder()
        var runStyle = TerminalStyle()
        for (at in 0 until maxOf(line.size, cursor + 1)) {
            val cell = line.getOrNull(at) ?: blank
            val cellStyle = if (at == cursor) cell.style.copy(inverse = !cell.style.inverse) else cell.style
            if (cellStyle != runStyle) {
                if (text.isNotEmpty()) result += TerminalRun(text.toString(), runStyle)
                text.clear()
                runStyle = cellStyle
            }
            text.appendScalar(cell.codePoint)
        }
        if (text.isNotEmpty()) result += TerminalRun(text.toString(), runStyle)
        return result
    }

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        for (at in 0 until length) {
            val byte = bytes[at].toInt() and 0xFF
            when {
                utf8Left > 0 && byte and 0xC0 == 0x80 -> {
                    utf8Value = (utf8Value shl 6) or (byte and 0x3F)
                    if (--utf8Left == 0) accept(utf8Value)
                }
                byte < 0x80 -> {
                    utf8Left = 0
                    accept(byte)
                }
                byte and 0xE0 == 0xC0 -> startUtf8(byte and 0x1F, 1)
                byte and 0xF0 == 0xE0 -> startUtf8(byte and 0x0F, 2)
                byte and 0xF8 == 0xF0 -> startUtf8(byte and 0x07, 3)
                else -> {
                    utf8Left = 0
                    accept(REPLACEMENT)
                }
            }
        }
    }

    private fun startUtf8(value: Int, left: Int) {
        utf8Value = value
        utf8Left = left
    }

    private fun accept(c: Int) {
        when (state) {
            State.GROUND -> ground(c)
            State.ESCAPE -> escape(c)
            State.CSI -> csi(c)
            State.STRING -> when (c) {
                BEL_CODE -> state = State.GROUND
                ESC_CODE -> state = State.STRING_ESCAPE
            }
            State.STRING_ESCAPE, State.SKIP -> state = State.GROUND
        }
    }

    private fun ground(c: Int) {
        when {
            c == ESC_CODE -> state = State.ESCAPE
            c == '\r'.code -> column = 0
            c == '\n'.code || c == 0x0B || c == 0x0C -> lineFeed()
            c == '\b'.code -> column = (column - 1).coerceAtLeast(0)
            c == '\t'.code -> column = minOf((column / 8 + 1) * 8, columns - 1)
            c < 0x20 || c == 0x7F -> Unit
            else -> put(c)
        }
    }

    private fun escape(c: Int) {
        state = when (c) {
            '['.code -> State.CSI
            ']'.code, 'P'.code, 'X'.code, '^'.code, '_'.code -> State.STRING
            '('.code, ')'.code, '*'.code, '+'.code, '#'.code, '%'.code, ' '.code -> State.SKIP
            else -> State.GROUND
        }
        when (c) {
            '7'.code -> saveCursor()
            '8'.code -> restoreCursor()
            'D'.code -> lineFeed()
            'E'.code -> {
                column = 0
                lineFeed()
            }
            'M'.code -> if (row == scrollTop) scrollDown(1) else moveTo(row - 1, column)
            'c'.code -> reset()
        }
    }

    private fun saveCursor() {
        savedRow = row
        savedColumn = column
        savedStyle = style
    }

    private fun restoreCursor() {
        moveTo(savedRow, savedColumn)
        style = savedStyle
    }

    private fun reset() {
        alternate = null
        style = TerminalStyle()
        scrollTop = 0
        scrollBottom = rows - 1
        cursorVisible = true
        autowrap = true
        applicationCursor = false
        eraseDisplay(2)
        moveTo(0, 0)
    }

    private fun csi(c: Int) {
        when (c) {
            in '0'.code..'9'.code -> param = (param.coerceAtLeast(0) * 10 + c - '0'.code).coerceAtMost(MAX_PARAM)
            ';'.code, ':'.code -> pushParam()
            in 0x3C..0x3F -> privateMode = true
            in 0x40..0x7E -> {
                pushParam()
                state = State.GROUND
                if (privateMode) setMode(c) else dispatch(c)
                params.clear()
                privateMode = false
            }
        }
    }

    private fun pushParam() {
        if (params.size < MAX_PARAMS) params += param
        param = -1
    }

    private fun setMode(final: Int) {
        if (final != 'h'.code && final != 'l'.code) return
        val on = final == 'h'.code
        for (mode in params) {
            when (mode) {
                1 -> applicationCursor = on
                7 -> autowrap = on
                25 -> cursorVisible = on
                47, 1047, 1049 -> {
                    if (mode == 1049 && on) saveCursor()
                    alternate = if (on) alternate ?: screenOf(rows) else null
                    if (mode == 1049 && !on) restoreCursor()
                }
            }
        }
    }

    private fun count(at: Int): Int = params.getOrElse(at) { -1 }.coerceAtLeast(1)

    private fun dispatch(final: Int) {
        val mode = params[0].coerceAtLeast(0)
        when (final) {
            'A'.code -> moveTo(row - count(0), column)
            'B'.code, 'e'.code -> moveTo(row + count(0), column)
            'C'.code, 'a'.code -> moveTo(row, column + count(0))
            'D'.code -> moveTo(row, column - count(0))
            'E'.code -> moveTo(row + count(0), 0)
            'F'.code -> moveTo(row - count(0), 0)
            'G'.code, '`'.code -> moveTo(row, count(0) - 1)
            'd'.code -> moveTo(count(0) - 1, column)
            'H'.code, 'f'.code -> moveTo(count(0) - 1, count(1) - 1)
            'J'.code -> eraseDisplay(mode)
            'K'.code -> eraseLine(mode)
            'L'.code -> if (row in scrollTop..scrollBottom) {
                shift(row, scrollBottom, minOf(count(0), scrollBottom - row + 1), down = true)
                column = 0
            }
            'M'.code -> if (row in scrollTop..scrollBottom) {
                shift(row, scrollBottom, minOf(count(0), scrollBottom - row + 1), down = false)
                column = 0
            }
            'S'.code -> scrollUp(count(0))
            'T'.code -> scrollDown(count(0))
            'P'.code -> repeat(minOf(count(0), current.size - column).coerceAtLeast(0)) { current.removeAt(column) }
            '@'.code -> insertBlanks(count(0))
            'X'.code -> for (at in column until minOf(column + count(0), current.size)) current[at] = blank
            'm'.code -> selectGraphics()
            'r'.code -> {
                val first = count(0) - 1
                val last = if (params.getOrElse(1) { -1 } > 0) params[1] - 1 else rows - 1
                if (first < last && last < rows) {
                    scrollTop = first
                    scrollBottom = last
                } else {
                    scrollTop = 0
                    scrollBottom = rows - 1
                }
                moveTo(0, 0)
            }
            's'.code -> saveCursor()
            'u'.code -> restoreCursor()
            'n'.code -> when (mode) {
                5 -> replies.append("\u001b[0n")
                6 -> replies.append("\u001b[${row + 1};${column + 1}R")
            }
            'c'.code -> if (mode == 0) replies.append("\u001b[?1;2c")
        }
    }

    // the scroll region moves up, the top line goes to the scrollback when the region is the whole main screen
    private fun scrollUp(count: Int) {
        if (alternate != null || scrollTop != 0 || scrollBottom != rows - 1) return shift(scrollTop, scrollBottom, count, down = false)
        repeat(minOf(count, rows)) {
            main.addLast(mutableListOf())
            if (main.size > rows + scrollback) main.removeFirst()
        }
    }

    private fun scrollDown(count: Int) = shift(scrollTop, scrollBottom, count, down = true)

    // moves the rows first..last of the screen down or up, blank rows come in at the freed end. The base is
    // taken once: removing a row shrinks the list, and top would point one row higher until the row is added back
    private fun shift(first: Int, last: Int, count: Int, down: Boolean) {
        val base = top
        repeat(minOf(count, last - first + 1)) {
            if (down) {
                lines.removeAt(base + last)
                lines.add(base + first, mutableListOf())
            } else {
                lines.removeAt(base + first)
                lines.add(base + last, mutableListOf())
            }
        }
    }

    private fun moveTo(row: Int, column: Int) {
        this.row = row.coerceIn(0, rows - 1)
        this.column = column.coerceIn(0, columns - 1)
    }

    private fun lineFeed() {
        if (row == scrollBottom) scrollUp(1) else if (row < rows - 1) row++
    }

    private fun put(codePoint: Int) {
        if (column >= columns) {
            if (autowrap) {
                column = 0
                lineFeed()
            } else {
                column = columns - 1
            }
        }
        val line = current
        while (line.size < column) line.add(blank)
        val cell = TerminalCell(codePoint, style)
        if (column < line.size) line[column] = cell else line.add(cell)
        column++
    }

    private fun insertBlanks(count: Int) {
        val line = current
        while (line.size < column) line.add(blank)
        repeat(minOf(count, columns - column)) { line.add(column, blank) }
        while (line.size > columns) line.removeAt(line.lastIndex)
    }

    private fun eraseLine(mode: Int) {
        val line = current
        when (mode) {
            0 -> if (column < line.size) line.subList(column, line.size).clear()
            1 -> for (at in 0..minOf(column, line.size - 1)) line[at] = blank
            2 -> line.clear()
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (at in row + 1 until rows) lines[top + at].clear()
            }
            1 -> {
                eraseLine(1)
                for (at in 0 until row) lines[top + at].clear()
            }
            2 -> for (at in 0 until rows) lines[top + at].clear()
            3 -> while (lines.size > rows) lines.removeFirst()
        }
    }

    private fun selectGraphics() {
        var at = 0
        while (at < params.size) {
            when (val code = params[at].coerceAtLeast(0)) {
                0 -> style = TerminalStyle()
                1 -> style = style.copy(bold = true)
                4 -> style = style.copy(underline = true)
                7 -> style = style.copy(inverse = true)
                22 -> style = style.copy(bold = false)
                24 -> style = style.copy(underline = false)
                27 -> style = style.copy(inverse = false)
                in 30..37 -> style = style.copy(foreground = code - 30)
                39 -> style = style.copy(foreground = TerminalStyle.DEFAULT_COLOR)
                in 40..47 -> style = style.copy(background = code - 40)
                49 -> style = style.copy(background = TerminalStyle.DEFAULT_COLOR)
                in 90..97 -> style = style.copy(foreground = code - 90 + 8)
                in 100..107 -> style = style.copy(background = code - 100 + 8)
                38, 48 -> {
                    extendedColor(at)?.let { style = if (code == 38) style.copy(foreground = it) else style.copy(background = it) }
                    at += if (params.getOrNull(at + 1) == 5) 2 else 4
                }
            }
            at++
        }
    }

    private fun extendedColor(at: Int): Int? = when (params.getOrNull(at + 1)) {
        5 -> params.getOrNull(at + 2)?.coerceIn(0, 255)
        2 -> if (params.size > at + 4) {
            TerminalPalette.TRUE_COLOR or ((params[at + 2] and 0xFF) shl 16) or ((params[at + 3] and 0xFF) shl 8) or (params[at + 4] and 0xFF)
        } else {
            null
        }
        else -> null
    }
}
