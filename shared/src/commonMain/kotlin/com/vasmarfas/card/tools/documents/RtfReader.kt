package com.vasmarfas.card.tools.documents

import com.vasmarfas.card.core.TextDecoding

private const val MAX_GROUPS = 10_000
private val NBSP = Char(0xA0)
private val RTF_HEADING = Regex("(?:heading|заголовок)\\s*([1-9])")

// SILENT still obeys control words but drops the text: shape properties, old-style list markers
private enum class Dest { TEXT, SKIP, FONTS, STYLES, INFO, TITLE, AUTHOR, INSTRUCTION, PICTURE, LISTS, OVERRIDES, SILENT }

private class RtfFont(val charset: Int, val mono: Boolean, val name: String)

private class ListDef(val levels: MutableList<Pair<Boolean, Int>> = ArrayList(), val indents: MutableList<Int> = ArrayList())

private class RtfField {
    val instruction = StringBuilder()
}

private class PictureData {
    var type = ""
    val hex = StringBuilder()
    var binary: ByteArray? = null
    var width = 0
    var height = 0
    var goalWidth = 0
    var goalHeight = 0
    var scaleX = 100
    var scaleY = 100
}

private class RowDef {
    var header = false
    val merged = ArrayList<Boolean>()
    val vertical = ArrayList<Boolean>()
    var pendingMerge = false
    var pendingVertical = false
}

private class RtfState {
    var dest = Dest.TEXT
    var bold = false
    var italic = false
    var underline = false
    var strike = false
    var script = Script.NORMAL
    var hidden = false
    var font = -1
    var uc = 1
    var link: String? = null
    var field: RtfField? = null
    var picture: PictureData? = null
    var align = Align.START
    var style = 0
    var outline = -1
    var inTable = false
    var listId = 0
    var level = 0
    var pnLevel = -1
    var pnOrdered = false
    var pnStart = 1
    var indent = 0
    var pageBreakBefore = false
    var starred = false
    var onClose: (() -> Unit)? = null

    fun copy(): RtfState {
        val s = RtfState()
        s.dest = dest
        s.bold = bold
        s.italic = italic
        s.underline = underline
        s.strike = strike
        s.script = script
        s.hidden = hidden
        s.font = font
        s.uc = uc
        s.link = link
        s.field = field
        s.picture = picture
        s.align = align
        s.style = style
        s.outline = outline
        s.inTable = inTable
        s.listId = listId
        s.level = level
        s.pnLevel = pnLevel
        s.pnOrdered = pnOrdered
        s.pnStart = pnStart
        s.indent = indent
        s.pageBreakBefore = pageBreakBefore
        return s
    }

    fun resetParagraph() {
        align = Align.START
        style = 0
        outline = -1
        inTable = false
        listId = 0
        level = 0
        pnLevel = -1
        pnOrdered = false
        pnStart = 1
        indent = 0
        pageBreakBefore = false
    }
}

internal class RtfReader(private val src: ByteArray) {
    private inner class Flow {
        val out = ArrayList<Block>()
        val lists = ListCollector(out)
        val segments = Segments()
        val rows = ArrayList<List<Cell>>()
        var headerRows = 0
        var cells = ArrayList<Cell>()
        var cellBlocks = ArrayList<Block>()

        fun flushTable() {
            if (rows.isEmpty()) return
            lists.flush()
            out.add(Block.Table(rows.toList(), headerRows))
            rows.clear()
            headerRows = 0
        }

        fun finish(): List<Block> {
            flushTable()
            lists.flush()
            return out
        }
    }

    private var pos = 0
    private var state = RtfState()
    private val stack = ArrayList<RtfState>()
    private var overflow = 0
    private val flows = arrayListOf(Flow())
    private val flow: Flow get() = flows[flows.lastIndex]

    private val fonts = HashMap<Int, RtfFont>()
    private var fontNumber = -1
    private var fontCharset = 0
    private var fontMono = false
    private val fontName = StringBuilder()
    private var defaultFont = -1
    private var codepage = 1252

    private val styleNames = HashMap<Int, String>()
    private val styleOutline = HashMap<Int, Int>()
    private var styleNumber = 0
    private var styleOutlineLevel = -1
    private var styleIsCharacter = false
    private val styleName = StringBuilder()

    private val listDefs = HashMap<Int, ListDef>()
    private var currentList: ListDef? = null
    private var inLevel = false
    private var levelOrdered = true
    private var levelStart = 1
    private var levelIndent = 0
    private val overrides = HashMap<Int, Int>()
    private var overrideList = 0
    private val counters = HashMap<String, IntArray>()

    private var row = RowDef()
    private val title = StringBuilder()
    private val author = StringBuilder()
    private val notes = Notes()
    private val pendingBytes = ArrayList<Byte>()
    private var skipChars = 0
    private var groupStart = false
    private var sectionBreak = false

    fun read(): Doc {
        if (src.size < 5 || !(src[0].toInt() == '{'.code && src[1].toInt() == '\\'.code && src[2].toInt() == 'r'.code && src[3].toInt() == 't'.code && src[4].toInt() == 'f'.code)) {
            throw DocumentFormatException("Not an RTF document")
        }
        while (pos < src.size) {
            when (val b = src[pos].toInt() and 0xFF) {
                '{'.code -> {
                    pos++
                    open()
                }
                '}'.code -> {
                    pos++
                    close()
                }
                '\\'.code -> control()
                '\r'.code, '\n'.code -> pos++
                else -> {
                    pos++
                    byte(b)
                }
            }
        }
        while (stack.isNotEmpty()) close()
        flushBytes()
        if (!flow.segments.isEmpty) endParagraph()
        val blocks = ArrayList(flow.finish())
        notes.appendTo(blocks)
        return Doc(
            blocks = mergeRuns(blocks),
            title = title.toString().collapseSpaces().takeIf { it.isNotEmpty() },
            author = author.toString().collapseSpaces().takeIf { it.isNotEmpty() },
        )
    }

    private fun open() {
        flushBytes()
        skipChars = 0
        groupStart = true
        if (stack.size >= MAX_GROUPS) {
            overflow++
            return
        }
        stack.add(state)
        state = state.copy()
    }

    private fun close() {
        flushBytes()
        skipChars = 0
        if (overflow > 0) {
            overflow--
            return
        }
        if (stack.isEmpty()) return
        val closing = state
        state = stack.removeAt(stack.lastIndex)
        closing.onClose?.invoke()
    }

    private fun skipGroup() {
        var depth = 1
        while (pos < src.size && depth > 0) {
            when (src[pos].toInt() and 0xFF) {
                '{'.code -> depth++
                '}'.code -> depth--
                '\\'.code -> {
                    if (pos + 4 < src.size && src[pos + 1].toInt() == 'b'.code && src[pos + 2].toInt() == 'i'.code && src[pos + 3].toInt() == 'n'.code) {
                        pos += 4
                        var n = 0L
                        while (pos < src.size && (src[pos].toInt() and 0xFF).toChar().isDigit()) {
                            n = minOf(n * 10 + (src[pos].toInt() - '0'.code), src.size.toLong())
                            pos++
                        }
                        if (pos < src.size && src[pos].toInt() == ' '.code) pos++
                        pos = minOf(src.size.toLong(), pos + n).toInt()
                        continue
                    }
                    pos++
                }
            }
            pos++
        }
        if (overflow > 0) overflow-- else if (stack.isNotEmpty()) state = stack.removeAt(stack.lastIndex)
    }

    private fun control() {
        pos++
        if (pos >= src.size) return
        val c = (src[pos].toInt() and 0xFF).toChar()
        if (c.isAsciiLetterChar()) {
            val start = pos
            while (pos < src.size && (src[pos].toInt() and 0xFF).toChar().isAsciiLetterChar() && pos - start < 32) pos++
            val word = CharArray(pos - start) { (src[start + it].toInt() and 0xFF).toChar() }.concatToString()
            var param: Int? = null
            var negative = false
            if (pos < src.size && src[pos].toInt() == '-'.code) {
                negative = true
                pos++
            }
            if (pos < src.size && (src[pos].toInt() and 0xFF).toChar().isDigit()) {
                var value = 0L
                while (pos < src.size && (src[pos].toInt() and 0xFF).toChar().isDigit()) {
                    if (value < 100_000_000L) value = value * 10 + (src[pos].toInt() - '0'.code)
                    pos++
                }
                param = (if (negative) -value else value).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            }
            if (pos < src.size && src[pos].toInt() == ' '.code) pos++
            word(word, param)
            return
        }
        pos++
        when (c) {
            '\'' -> {
                if (pos + 2 <= src.size) {
                    val hi = (src[pos].toInt() and 0xFF).toChar().digitToIntOrNull(16)
                    val lo = (src[pos + 1].toInt() and 0xFF).toChar().digitToIntOrNull(16)
                    if (hi != null && lo != null) {
                        pos += 2
                        byte((hi shl 4) or lo)
                    }
                }
            }
            '*' -> state.starred = true
            '\\', '{', '}' -> byte(c.code)
            '~' -> char(NBSP)
            '_' -> char('-')
            '-', ':', '|' -> skipOne()
            '\n', '\r' -> {
                flushBytes()
                if (state.dest == Dest.TEXT) endParagraph()
            }
        }
    }

    private fun skipOne() {
        if (skipChars > 0) skipChars--
    }

    private fun byte(b: Int) {
        groupStart = false
        if (skipChars > 0) {
            skipChars--
            return
        }
        when (state.dest) {
            Dest.TEXT -> if (!state.hidden) pendingBytes.add(b.toByte())
            Dest.PICTURE -> state.picture?.hex?.append(b.toChar())
            Dest.FONTS -> if (b == ';'.code) saveFont() else fontName.append(b.toChar())
            Dest.STYLES -> if (b == ';'.code) saveStyle() else styleName.append(b.toChar())
            Dest.TITLE -> title.append(decodeByte(b))
            Dest.AUTHOR -> author.append(decodeByte(b))
            Dest.INSTRUCTION -> state.field?.instruction?.append(decodeByte(b))
            else -> {}
        }
    }

    private fun char(c: Char) {
        flushBytes()
        if (skipChars > 0) {
            skipChars--
            return
        }
        when (state.dest) {
            Dest.TEXT -> if (!state.hidden) flow.segments.inline.preserved(c.toString(), style())
            Dest.TITLE -> title.append(c)
            Dest.AUTHOR -> author.append(c)
            Dest.INSTRUCTION -> state.field?.instruction?.append(c)
            Dest.FONTS -> fontName.append(c)
            Dest.STYLES -> styleName.append(c)
            else -> {}
        }
    }

    private fun decodeByte(b: Int): String = if (b < 0x80) b.toChar().toString() else decode(byteArrayOf(b.toByte()), currentCodepage())

    private fun flushBytes() {
        if (pendingBytes.isEmpty()) return
        val bytes = pendingBytes.toByteArray()
        pendingBytes.clear()
        val font = fonts[state.font]
        val text = if (font != null && (font.charset == 2 || SymbolFonts.isSymbolFont(font.name))) {
            SymbolFonts.text(if (SymbolFonts.isSymbolFont(font.name)) font.name else "Symbol", CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString())
        } else {
            decode(bytes, currentCodepage())
        }
        flow.segments.inline.preserved(text, style())
    }

    private fun currentCodepage(): Int {
        val charset = fonts[state.font]?.charset ?: return codepage
        return when (charset) {
            0, 1 -> codepage
            77 -> 10000
            161 -> 1253
            162 -> 1254
            163 -> 1258
            177 -> 1255
            178 -> 1256
            186 -> 1257
            204 -> 1251
            222 -> 874
            238 -> 1250
            254 -> 437
            255 -> 850
            else -> codepage
        }
    }

    // code pages TextDecoding has no table for fall back to windows-1252, writers put such text into \u anyway
    private fun decode(bytes: ByteArray, page: Int): String {
        val name = when (page) {
            1250 -> "windows-1250"
            1251 -> "windows-1251"
            866 -> "cp866"
            437 -> "ibm437"
            20866 -> "koi8-r"
            28591 -> "iso-8859-1"
            28592 -> "iso-8859-2"
            else -> "windows-1252"
        }
        return TextDecoding.decode(bytes, name)
    }

    private fun style(): Inline.Text = Inline.Text(
        text = "",
        bold = state.bold,
        italic = state.italic,
        underline = state.underline && state.link == null,
        strike = state.strike,
        code = fonts[state.font]?.mono == true,
        script = state.script,
        link = state.link,
    )

    private fun word(word: String, param: Int?) {
        val first = groupStart
        groupStart = false
        if (state.starred) {
            state.starred = false
            if (!starredDestination(word)) {
                skipGroup()
                return
            }
        }
        if (skipChars > 0 && word != "u" && word != "uc") {
            skipChars--
            if (word != "bin") return
        }
        if (word != "u" && word != "bin") flushBytes()
        val on = param != 0
        when (word) {
            "rtf", "ansi" -> {}
            "ansicpg" -> param?.let { codepage = it }
            "mac" -> codepage = 10000
            "pc" -> codepage = 437
            "pca" -> codepage = 850
            "deff" -> param?.let { defaultFont = it }
            "fonttbl" -> state.dest = Dest.FONTS
            "stylesheet" -> state.dest = Dest.STYLES
            "info" -> state.dest = Dest.INFO
            "title" -> state.dest = if (state.dest == Dest.INFO) Dest.TITLE else Dest.SKIP
            "author" -> state.dest = if (state.dest == Dest.INFO) Dest.AUTHOR else Dest.SKIP
            "colortbl", "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf", "ftnsep", "ftnsepc", "ftncn",
            "aftnsep", "aftnsepc", "aftncn", "annotation", "atnauthor", "atnid", "atndate", "atnref", "xe", "tc", "tcn", "txe", "pntext",
            "listtext", "nonshppict", "shprslt", "objdata", "operator", "company", "manager", "category", "keywords", "comment", "doccomm",
            "subject", "creatim", "revtim", "printim", "buptim", "version", "vern", "edmins", "nofpages", "nofwords", "nofchars", "hlinkbase",
            "userprops", "docvar", "pgdsctbl", "revtbl", "filetbl", "do", "falt", "panose", "fname", "levelnumbers", "leveltext",
            "listname", "datafield", "formfield", "fldtype", "picprop", "blipuid", "bkmkstart", "bkmkend", "template",
            -> if (first) skipGroup()
            "footnote" -> footnote()
            "field" -> state.field = RtfField()
            "fldinst" -> state.dest = Dest.INSTRUCTION
            "fldrslt" -> {
                state.dest = Dest.TEXT
                state.link = state.field?.let { hyperlinkTarget(it.instruction.toString()) } ?: state.link
            }
            "pict" -> picture()
            "shppict", "result", "shptxt" -> state.dest = Dest.TEXT
            "shpinst" -> state.dest = Dest.SILENT
            "listtable" -> state.dest = Dest.LISTS
            "listoverridetable" -> state.dest = Dest.OVERRIDES
            "list" -> if (state.dest == Dest.LISTS) currentList = ListDef()
            "listlevel" -> if (state.dest == Dest.LISTS) listLevel()
            "levelnfc", "levelnfcn" -> if (inLevel) levelOrdered = param != 23 && param != 255
            "levelstartat" -> if (inLevel) levelStart = param ?: 1
            "listid" -> if (state.dest == Dest.LISTS) {
                currentList?.let { listDefs[param ?: 0] = it }
            } else if (state.dest == Dest.OVERRIDES) {
                overrideList = param ?: 0
            }
            "ls" -> if (state.dest == Dest.OVERRIDES) overrides[param ?: 0] = overrideList else state.listId = param ?: 0
            "ilvl" -> state.level = (param ?: 0).coerceIn(0, 8)
            "pn" -> {
                val pn = state
                pn.dest = Dest.SILENT
                pn.pnLevel = 0
                pn.onClose = {
                    state.pnLevel = pn.pnLevel
                    state.pnOrdered = pn.pnOrdered
                    state.pnStart = pn.pnStart
                }
            }
            "pnlvlblt" -> {
                state.pnLevel = 0
                state.pnOrdered = false
            }
            "pnlvlbody" -> {
                state.pnLevel = 0
                state.pnOrdered = true
            }
            "pnlvl" -> {
                state.pnLevel = ((param ?: 1) - 1).coerceIn(0, 8)
                state.pnOrdered = true
            }
            "pndec", "pnucltr", "pnlcltr", "pnucrm", "pnlcrm" -> state.pnOrdered = true
            "pnstart" -> state.pnStart = param ?: 1
            "f" -> when (state.dest) {
                Dest.FONTS -> {
                    fontNumber = param ?: 0
                    fontCharset = 0
                    fontMono = false
                    fontName.clear()
                    state.onClose = { if (fontName.isNotBlank()) saveFont() }
                }
                else -> state.font = param ?: 0
            }
            "fcharset" -> if (state.dest == Dest.FONTS) fontCharset = param ?: 0
            "fmodern" -> if (state.dest == Dest.FONTS) fontMono = true
            "fprq" -> if (state.dest == Dest.FONTS && param == 1) fontMono = true
            "s" -> if (state.dest == Dest.STYLES) startStyle(param ?: 0, character = false) else state.style = param ?: 0
            "cs", "ds", "ts", "tsrowd" -> if (state.dest == Dest.STYLES) startStyle(param ?: 0, character = true)
            "outlinelevel" -> if (state.dest == Dest.STYLES) styleOutlineLevel = param ?: -1 else state.outline = param ?: -1
            "plain" -> {
                state.bold = false
                state.italic = false
                state.underline = false
                state.strike = false
                state.script = Script.NORMAL
                state.hidden = false
                state.font = defaultFont
            }
            "b" -> state.bold = on
            "i" -> state.italic = on
            "ul", "uld", "uldash", "uldashd", "uldashdd", "uldb", "ulhwave", "ulldash", "ulth", "ulthd", "ulthdash", "ulthdashd",
            "ulthdashdd", "ulthldash", "ululdbwave", "ulw", "ulwave",
            -> state.underline = on
            "ulnone" -> state.underline = false
            "strike", "striked" -> state.strike = on
            "super" -> state.script = if (on) Script.SUPER else Script.NORMAL
            "sub" -> state.script = if (on) Script.SUB else Script.NORMAL
            "nosupersub" -> state.script = Script.NORMAL
            "up" -> state.script = if ((param ?: 6) > 0) Script.SUPER else Script.NORMAL
            "dn" -> state.script = if ((param ?: 6) > 0) Script.SUB else Script.NORMAL
            "v" -> state.hidden = on
            "deleted" -> state.hidden = on
            "uc" -> state.uc = (param ?: 1).coerceIn(0, 10)
            "u" -> param?.let { unicode(it) }
            "bin" -> binary(param ?: 0)
            "pard" -> state.resetParagraph()
            "ql" -> state.align = Align.START
            "qc" -> state.align = Align.CENTER
            "qr" -> state.align = Align.END
            "qj", "qd" -> state.align = Align.JUSTIFY
            "li", "lin" -> if (inLevel) levelIndent = param ?: 0 else state.indent = param ?: 0
            "intbl" -> state.inTable = true
            "pagebb" -> state.pageBreakBefore = on
            "par" -> if (state.dest == Dest.TEXT) endParagraph()
            "line" -> if (state.dest == Dest.TEXT) flow.segments.inline.lineBreak()
            "tab" -> char(if (fonts[state.font]?.mono == true) '\t' else ' ')
            "page" -> if (state.dest == Dest.TEXT) flow.segments.block(Block.PageBreak)
            "sect" -> if (state.dest == Dest.TEXT) {
                endParagraph()
                sectionBreak = true
            }
            "sbknone" -> sectionBreak = false
            "cell", "nestcell" -> if (state.dest == Dest.TEXT) endCell(nested = word == "nestcell")
            "row" -> if (state.dest == Dest.TEXT) endRow()
            "nestrow" -> if (state.dest == Dest.TEXT) endParagraph()
            "trowd" -> row = RowDef()
            "trhdr" -> row.header = true
            "clmgf" -> row.pendingMerge = false
            "clmrg" -> row.pendingMerge = true
            "clvmgf" -> row.pendingVertical = false
            "clvmrg" -> row.pendingVertical = true
            "cellx" -> {
                row.merged.add(row.pendingMerge)
                row.vertical.add(row.pendingVertical)
                row.pendingMerge = false
                row.pendingVertical = false
            }
            "picw" -> state.picture?.width = param ?: 0
            "pich" -> state.picture?.height = param ?: 0
            "picwgoal" -> state.picture?.goalWidth = param ?: 0
            "pichgoal" -> state.picture?.goalHeight = param ?: 0
            "picscalex" -> state.picture?.scaleX = param ?: 100
            "picscaley" -> state.picture?.scaleY = param ?: 100
            "pngblip" -> state.picture?.type = "png"
            "jpegblip" -> state.picture?.type = "jpeg"
            "emfblip" -> state.picture?.type = "emf"
            "wmetafile", "macpict", "dibitmap", "wbitmap", "pmmetafile" -> state.picture?.type = "other"
            "emdash" -> char('—')
            "endash" -> char('–')
            "emspace" -> char(Char(0x2003))
            "enspace" -> char(Char(0x2002))
            "qmspace" -> char(Char(0x2005))
            "bullet" -> char('•')
            "lquote" -> char('‘')
            "rquote" -> char('’')
            "ldblquote" -> char('“')
            "rdblquote" -> char('”')
        }
    }

    private fun Char.isAsciiLetterChar(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    private fun starredDestination(word: String): Boolean = word in setOf("fldinst", "shpinst", "pn", "listtable", "listoverridetable", "shppict", "footnote")

    private fun unicode(value: Int) {
        val code = if (value < 0) value + 65536 else value
        skipChars = 0
        val font = fonts[state.font]?.name
        if (code in 0xF000..0xF0FF && SymbolFonts.isSymbolFont(font)) {
            SymbolFonts.map(font, code)?.forEach(::char)
        } else {
            char(code.toChar())
        }
        skipChars = state.uc
    }

    private fun binary(length: Int) {
        val n = length.coerceIn(0, src.size - pos)
        if (state.dest == Dest.PICTURE) state.picture?.binary = src.copyOfRange(pos, pos + n)
        pos += n
    }

    private fun saveFont() {
        if (fontNumber >= 0) {
            val name = fontName.toString().trim()
            fonts[fontNumber] = RtfFont(fontCharset, fontMono || isMonospaceFont(name), name)
        }
        fontName.clear()
    }

    private fun startStyle(number: Int, character: Boolean) {
        styleNumber = number
        styleIsCharacter = character
        styleOutlineLevel = -1
        styleName.clear()
    }

    private fun saveStyle() {
        if (!styleIsCharacter) {
            styleNames[styleNumber] = styleName.toString().trim().lowercase()
            if (styleOutlineLevel >= 0) styleOutline[styleNumber] = styleOutlineLevel
        }
        styleName.clear()
        styleNumber = 0
        styleIsCharacter = false
        styleOutlineLevel = -1
    }

    private fun listLevel() {
        inLevel = true
        levelOrdered = true
        levelStart = 1
        levelIndent = 0
        val list = currentList
        state.onClose = {
            list?.levels?.add(levelOrdered to levelStart)
            list?.indents?.add(levelIndent)
            inLevel = false
        }
    }

    private fun picture() {
        val data = PictureData()
        state.picture = data
        state.dest = Dest.PICTURE
        val target = flow
        state.onClose = {
            val bytes = data.binary ?: hexBytes(data.hex)
            if (bytes.isNotEmpty() && (data.type == "png" || data.type == "jpeg" || data.type == "emf")) {
                val w = if (data.goalWidth > 0) data.goalWidth / 20f * data.scaleX / 100f else null
                val h = if (data.goalHeight > 0) data.goalHeight / 20f * data.scaleY / 100f else null
                val (width, height) = if (w != null && h != null) w to h else pictureSize(bytes, w, h).takeIf { it.first > 0f } ?: pixelSize(bytes, data)
                target.segments.block(Block.Picture(bytes, width, height))
            }
        }
    }

    private fun pixelSize(bytes: ByteArray, data: PictureData): Pair<Float, Float> {
        val px = ImageHeader.size(bytes) ?: return 0f to 0f
        return px.first * 0.75f * data.scaleX / 100f to px.second * 0.75f * data.scaleY / 100f
    }

    private fun hexBytes(hex: StringBuilder): ByteArray {
        val out = ByteArray(hex.length / 2)
        var n = 0
        var high = -1
        for (c in hex) {
            val v = c.digitToIntOrNull(16) ?: continue
            if (high < 0) {
                high = v
            } else {
                out[n++] = ((high shl 4) or v).toByte()
                high = -1
            }
        }
        return out.copyOf(n)
    }

    private fun footnote() {
        val parent = flow
        val note = Flow()
        val noteState = state
        flows.add(note)
        noteState.dest = Dest.TEXT
        noteState.resetParagraph()
        noteState.onClose = {
            val outer = state
            state = noteState
            if (!note.segments.isEmpty) endParagraph()
            state = outer
            flows.removeAt(flows.lastIndex)
            parent.segments.inline.inline(notes.add(note.finish()))
        }
    }

    private fun endParagraph() {
        flushBytes()
        val target = flow
        val level = headingLevel()
        val blocks = if (level != null) target.segments.heading(level) else target.segments.blocks { Block.Paragraph(it, state.align) }
        var placed = if (state.pageBreakBefore && blocks.isNotEmpty()) listOf(Block.PageBreak) + blocks else blocks
        if (sectionBreak && placed.isNotEmpty() && flows.size == 1) {
            placed = listOf(Block.PageBreak) + placed
            sectionBreak = false
        }
        if (state.inTable) {
            target.cellBlocks.addAll(placed)
            return
        }
        target.flushTable()
        if (placed.isEmpty()) return
        val list = listInfo()
        when {
            list != null && level == null -> {
                val (key, ordered, start) = list
                target.lists.item(key, state.level, ordered, start, placed)
            }
            level == null && state.indent > 0 && target.lists.isOpen -> {
                val lvl = target.lists.levelFor(state.indent) { key, l -> listIndent(key, l) }
                if (lvl != null) target.lists.continuation(lvl, placed) else addBlocks(target, placed)
            }
            else -> addBlocks(target, placed)
        }
    }

    private fun addBlocks(target: Flow, blocks: List<Block>) {
        target.lists.flush()
        target.out.addAll(blocks)
    }

    private fun headingLevel(): Int? {
        if (state.outline in 0..8) return state.outline + 1
        styleOutline[state.style]?.takeIf { it in 0..8 }?.let { return it + 1 }
        val name = styleNames[state.style] ?: return null
        return when (name) {
            "title" -> 1
            "subtitle" -> 2
            else -> RTF_HEADING.matchEntire(name)?.groupValues?.get(1)?.toInt()
        }
    }

    private fun listInfo(): Triple<String, Boolean, Int>? {
        if (state.listId > 0) {
            val def = listDefs[overrides[state.listId] ?: state.listId]
            val levelInfo = def?.levels?.getOrNull(state.level)
            val ordered = levelInfo?.first ?: false
            val key = "ls${state.listId}"
            val counter = counters.getOrPut(key) { IntArray(9) { Int.MIN_VALUE } }
            val n = if (counter[state.level] == Int.MIN_VALUE) levelInfo?.second ?: 1 else counter[state.level] + 1
            counter[state.level] = n
            for (k in state.level + 1 until 9) counter[k] = Int.MIN_VALUE
            return Triple(key, ordered, n)
        }
        if (state.pnLevel >= 0) {
            state.level = state.pnLevel
            return Triple("pn", state.pnOrdered, state.pnStart)
        }
        return null
    }

    private fun listIndent(key: String, level: Int): Int {
        val id = key.removePrefix("ls").toIntOrNull() ?: return 720 * (level + 1)
        return listDefs[overrides[id] ?: id]?.indents?.getOrNull(level) ?: (720 * (level + 1))
    }

    private fun endCell(nested: Boolean) {
        val wasInTable = state.inTable
        state.inTable = true
        endParagraph()
        state.inTable = wasInTable
        if (nested) return
        val target = flow
        target.cells.add(Cell(target.cellBlocks))
        target.cellBlocks = ArrayList()
    }

    private fun endRow() {
        val target = flow
        if (!target.segments.isEmpty) endCell(nested = false)
        val cells = ArrayList<Cell>()
        for ((i, cell) in target.cells.withIndex()) {
            when {
                row.merged.getOrElse(i) { false } && cells.isNotEmpty() -> {
                    val last = cells.removeAt(cells.lastIndex)
                    cells.add(Cell(last.blocks + cell.blocks, (last.colSpan + 1).coerceAtMost(MAX_COL_SPAN)))
                }
                row.vertical.getOrElse(i) { false } -> cells.add(Cell(emptyList()))
                else -> cells.add(cell)
            }
        }
        target.cells = ArrayList()
        target.cellBlocks = ArrayList()
        if (cells.isEmpty()) return
        if (row.header && target.headerRows == target.rows.size) target.headerRows++
        target.rows.add(cells)
    }

    private fun hyperlinkTarget(instruction: String): String? {
        val text = instruction.trim()
        if (!text.startsWith("HYPERLINK", ignoreCase = true)) return null
        val rest = text.substring(9).trim()
        val url = if (rest.startsWith('"')) rest.substring(1).substringBefore('"') else rest.substringBefore(' ')
        return if (url.startsWith("\\")) null else externalLink(url)
    }
}
