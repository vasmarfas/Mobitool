package com.vasmarfas.card.tools.documents

import com.vasmarfas.card.tools.developer.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RtfReaderTest {
    private val bs = "\\"

    private fun u(text: String): String = text.map { "${bs}u${it.code}?" }.joinToString("")

    private fun cp(text: String): String = text.map { c ->
        val code = when (c) {
            in 'А'..'я' -> c.code - 0x410 + 0xC0
            'Ё' -> 0xA8
            'ё' -> 0xB8
            else -> return@map c.toString()
        }
        "${bs}'" + code.toString(16)
    }.joinToString("")

    private fun read(rtf: String): Doc = Documents.read(rtf.encodeToByteArray(), DocFormat.RTF)

    private fun blocks(rtf: String): String = dumpBlocks(read(rtf).blocks)

    private val header = "{${bs}rtf1${bs}ansi${bs}ansicpg1251${bs}uc1${bs}deff0" +
        "{${bs}fonttbl{${bs}f0${bs}froman${bs}fcharset204${bs}fprq2{${bs}*${bs}panose 02020603050405020304}Times New Roman;}" +
        "{${bs}f1${bs}fmodern${bs}fcharset204${bs}fprq1 Courier New;}{${bs}f2${bs}fnil${bs}fcharset2${bs}fprq2 Symbol;}{${bs}f3${bs}fswiss${bs}fcharset0 Arial;}}" +
        "{${bs}colortbl;${bs}red0${bs}green0${bs}blue0;${bs}red0${bs}green0${bs}blue255;}" +
        "{${bs}stylesheet{${bs}ql${bs}li0${bs}ri0${bs}widctlpar${bs}fs24${bs}snext0 Normal;}" +
        "{${bs}s1${bs}ql${bs}sb240${bs}sa60${bs}keepn${bs}outlinelevel0${bs}b${bs}fs32${bs}sbasedon0${bs}snext0 heading 1;}" +
        "{${bs}s2${bs}ql${bs}sbasedon0${bs}snext0 heading 2;}{${bs}*${bs}cs10${bs}additive Default Paragraph Font;}}"

    @Test
    fun wordStyleDocument() {
        val rtf = header +
            "{${bs}info{${bs}title ${cp("Название")}}{${bs}author ${u("Автор")}}{${bs}operator x}{${bs}creatim${bs}yr2024${bs}mo1${bs}dy1}}" +
            "{${bs}header ${bs}pard${bs}plain ${cp("Колонтитул")}${bs}par}{${bs}footer ${bs}pard ${cp("Низ")}${bs}par}" +
            "${bs}pard${bs}plain ${bs}s1${bs}outlinelevel0 ${bs}b${bs}fs32 ${cp("Глава")} 1${bs}par" +
            "${bs}pard${bs}plain ${bs}s2 ${cp("Раздел по стилю")}${bs}par" +
            "${bs}pard${bs}plain ${bs}qc {${bs}b ${cp("жирный")}} {${bs}i ${u("курсив")}} {${bs}ul ${cp("подч")}}{${bs}ulnone  }{${bs}strike x}${bs}par" +
            "${bs}pard${bs}qr ${cp("справа")}${bs}par${bs}pard${bs}qj ${cp("ширина")}${bs}par" +
            "${bs}pard H{${bs}sub 2}O x{${bs}super 2}{${bs}up6 3} {${bs}f1 code()}${bs}tab${cp("таб")}${bs}line ${cp("строка")}${bs}~${cp("нбсп")}${bs}emdash${bs}bullet${bs}lquote${bs}rquote{${bs}v ${cp("скрыто")}}${bs}par" +
            "${bs}pard {${bs}f2 ${bs}'b7 a}${bs}par" +
            "${bs}pard {${bs}field{${bs}*${bs}fldinst{HYPERLINK \"https://example.com/\"}}{${bs}fldrslt{${bs}ul${bs}cf2 ${cp("ссылка")}}}} " +
            "{${bs}field{${bs}*${bs}fldinst{ PAGE }}{${bs}fldrslt 5}}${bs}par" +
            "${bs}pard ${cp("Перед")}${bs}page ${cp("после разрыва")}${bs}par" +
            "${bs}pard ${cp("Сноска")}{${bs}super${bs}chftn}{${bs}footnote${bs}pard${bs}plain{${bs}super${bs}chftn} ${cp("текст сноски")}${bs}par}${bs}par" +
            "}"
        val doc = read(rtf)
        assertEquals("Название", doc.title)
        assertEquals("Автор", doc.author)
        val nbsp = Char(0xA0)
        assertEquals(
            """
            H1 "Глава 1"
            H2 "Раздел по стилю"
            P[center] {b}"жирный" " " {i}"курсив" " " {u}"подч" " " {s}"x"
            P[end] "справа"
            P[justify] "ширина"
            P "H" {sub}"2" "O x" {sup}"23" " " {c}"code()" " таб" \n "строка${nbsp}нбсп—•‘’"
            P "• α"
            P {link=https://example.com/}"ссылка" " 5"
            P "Перед"
            PAGEBREAK
            P "после разрыва"
            P "Сноска" {sup}"[1]"
            RULE
            OL start=1
              ITEM
                P "текст сноски"

            """.trimIndent(),
            dumpBlocks(doc.blocks),
        )
    }

    @Test
    fun listTableLists() {
        val lists = "{${bs}*${bs}listtable" +
            "{${bs}list${bs}listtemplateid-1${bs}listhybrid{${bs}listlevel${bs}levelnfc23${bs}levelnfcn23${bs}levelstartat1{${bs}leveltext${bs}'01${bs}u-3913 ?;}{${bs}levelnumbers;}${bs}f2${bs}fi-360${bs}li720${bs}lin720 }" +
            "{${bs}listlevel${bs}levelnfc0${bs}levelstartat1{${bs}leveltext${bs}'02${bs}'01.;}{${bs}levelnumbers${bs}'01;}${bs}fi-360${bs}li1440${bs}lin1440 }{${bs}listname ;}${bs}listid100}" +
            "{${bs}list${bs}listtemplateid-2{${bs}listlevel${bs}levelnfc0${bs}levelstartat3{${bs}leveltext${bs}'02${bs}'00.;}{${bs}levelnumbers${bs}'01;}${bs}fi-360${bs}li720${bs}lin720 }{${bs}listname ;}${bs}listid200}}" +
            "{${bs}*${bs}listoverridetable{${bs}listoverride${bs}listid100${bs}listoverridecount0${bs}ls1}{${bs}listoverride${bs}listid200${bs}listoverridecount0${bs}ls2}}"
        fun item(ls: Int, level: Int, text: String) = "{${bs}listtext${bs}pard${bs}plain${bs}f2 ${bs}'b7${bs}tab}${bs}pard${bs}plain ${bs}ls$ls${bs}ilvl$level ${cp(text)}${bs}par"
        val rtf = header + lists +
            item(1, 0, "маркер") + item(1, 1, "вложенный") + item(1, 1, "второй") +
            "${bs}pard${bs}li1440 ${cp("продолжение")}${bs}par" +
            item(1, 0, "снова маркер") +
            "${bs}pard ${cp("обычный")}${bs}par" +
            item(2, 0, "три") + item(2, 0, "четыре") + "}"
        assertEquals(
            """
            UL
              ITEM
                P "маркер"
                OL start=1
                  ITEM
                    P "вложенный"
                  ITEM
                    P "второй"
                    P "продолжение"
              ITEM
                P "снова маркер"
            P "обычный"
            OL start=3
              ITEM
                P "три"
              ITEM
                P "четыре"

            """.trimIndent(),
            blocks(rtf),
        )
    }

    @Test
    fun wordPadStyleLists() {
        val rtf = "{${bs}rtf1${bs}ansi${bs}ansicpg1251${bs}deff0${bs}nouicompat${bs}deflang1049{${bs}fonttbl{${bs}f0${bs}fnil${bs}fcharset204 Calibri;}{${bs}f1${bs}fnil${bs}fcharset2 Symbol;}}" +
            "{${bs}*${bs}generator Riched20 10.0.19041}${bs}viewkind4${bs}uc1 " +
            "${bs}pard${bs}sa200${bs}sl276${bs}slmult1${bs}f0${bs}fs22${bs}lang9 ${cp("Привет")}${bs}par" +
            "{${bs}pntext${bs}f1${bs}'B7${bs}tab}{${bs}*${bs}pn${bs}pnlvlblt${bs}pnf1${bs}pnindent0{${bs}pntxtb${bs}'B7}}${bs}fi-360${bs}li720${bs}sa200 Item one${bs}par" +
            "{${bs}pntext${bs}f1${bs}'B7${bs}tab}Item two${bs}par" +
            "${bs}pard${bs}sa200 {${bs}pntext 1.${bs}tab}{${bs}*${bs}pn${bs}pnlvlbody${bs}pnf0${bs}pnindent0${bs}pnstart4${bs}pndec{${bs}pntxta.}}${bs}fi-360${bs}li720 Four${bs}par" +
            "${bs}pard${bs}sa200 After${bs}par}"
        assertEquals(
            "P \"Привет\"\nUL\n  ITEM\n    P \"Item one\"\n  ITEM\n    P \"Item two\"\nOL start=4\n  ITEM\n    P \"Four\"\nP \"After\"\n",
            blocks(rtf),
        )
    }

    @Test
    fun tables() {
        fun cell(text: String) = "${bs}pard${bs}intbl ${cp(text)}${bs}cell "
        val rtf = header +
            "${bs}trowd${bs}trhdr${bs}cellx2000${bs}cellx4000${bs}cellx6000 " + cell("А") + cell("Б") + cell("В") + "${bs}row " +
            "${bs}trowd${bs}clmgf${bs}cellx2000${bs}clmrg${bs}cellx4000${bs}clvmgf${bs}cellx6000 " + cell("широкая") + cell("") + cell("высокая") + "${bs}row " +
            "${bs}trowd${bs}cellx2000${bs}cellx4000${bs}clvmrg${bs}cellx6000 " + cell("1") + "${bs}pard${bs}intbl ${cp("два")}${bs}par ${cp("абзаца")}${bs}cell " + cell("") +
            "{${bs}trowd${bs}cellx2000${bs}cellx4000${bs}clvmrg${bs}cellx6000${bs}row }" +
            "${bs}pard ${cp("после таблицы")}${bs}par}"
        assertEquals(
            """
            TABLE header=1
              ROW
                CELL
                  P "А"
                CELL
                  P "Б"
                CELL
                  P "В"
              ROW
                CELL span=2
                  P "широкая"
                CELL
                  P "высокая"
              ROW
                CELL
                  P "1"
                CELL
                  P "два"
                  P "абзаца"
                CELL
            P "после таблицы"

            """.trimIndent(),
            blocks(rtf),
        )
    }

    @Test
    fun pictures() {
        val png = TestImages.png(4, 2)
        val prefix = header +
            "${bs}pard ${cp("до")}{${bs}*${bs}shppict{${bs}pict{${bs}*${bs}picprop${bs}shplid1025}${bs}picscalex50${bs}picscaley100${bs}picw106${bs}pich53${bs}picwgoal1200${bs}pichgoal600${bs}pngblip " +
            png.toHex().chunked(64).joinToString("\r\n") + "}}{${bs}nonshppict{${bs}pict${bs}wmetafile8 0102}}${cp("после")}${bs}par" +
            "${bs}pard {${bs}pict${bs}jpegblip${bs}bin4 ABCD}{${bs}pict${bs}pngblip${bs}bin${png.size} "
        val doc = Documents.read(prefix.encodeToByteArray() + png + "}${bs}par}".encodeToByteArray(), DocFormat.RTF)
        val pictures = doc.blocks.filterIsInstance<Block.Picture>()
        assertEquals(3, pictures.size)
        assertTrue(pictures[0].bytes.contentEquals(png))
        assertEquals(30f, pictures[0].widthPt)
        assertEquals(30f, pictures[0].heightPt)
        assertEquals("ABCD", pictures[1].bytes.decodeToString())
        assertTrue(pictures[2].bytes.contentEquals(png))
        assertEquals("P \"до\"", dumpBlocks(doc.blocks.take(1)).trim())
        assertEquals("P \"после\"", dumpBlocks(listOf(doc.blocks[2])).trim())
    }

    @Test
    fun codePagesAndUnicode() {
        val rtf = "{${bs}rtf1${bs}ansi${bs}ansicpg1252{${bs}fonttbl{${bs}f0 Arial;}{${bs}f1${bs}fcharset204 Arial Cyr;}{${bs}f2${bs}fcharset0 Arial;}}" +
            "${bs}f0 caf${bs}'e9 {${bs}f1 ${bs}'cf${bs}'f0${bs}'e8${bs}'e2${bs}'e5${bs}'f2} ${bs}uc2${bs}u1071${bs}'3f${bs}'3f${bs}u1071${bs}'3f${bs}'3f " +
            "${bs}uc1${bs}u-10179${bs}'3f${bs}u-8704${bs}'3f {${bs}f2${bs}'80} ${bs}{${bs}}${bs}${bs}${bs}par}"
        assertEquals("P \"café Привет ЯЯ 😀 € {}\\\\\"\n", blocks(rtf))
        assertEquals("P \"ąć Łódź\"\n", blocks("{${bs}rtf1${bs}ansi${bs}ansicpg1250 ${bs}'b9${bs}'e6 ${bs}'a3${bs}'f3d${bs}'9f${bs}par}"))
    }

    @Test
    fun robustness() {
        assertFailsWith<DocumentFormatException> { Documents.read("not rtf".encodeToByteArray(), DocFormat.RTF) }
        assertEquals("P {b}\"обрезано\"\n", blocks("{${bs}rtf1${bs}ansi${bs}ansicpg1251{${bs}b ${cp("обрезано")}"))
        assertEquals("P \"лишние скобки\"\n", blocks("{${bs}rtf1${bs}ansicpg1251 ${cp("лишние")}}} ${cp("скобки")}}}"))
        assertEquals("P \"глубоко\"\n", blocks("{${bs}rtf1${bs}ansicpg1251" + "{".repeat(30_000) + cp("глубоко") + "}".repeat(30_000) + "}"))
        assertEquals("P \"видно\"\n", blocks("{${bs}rtf1${bs}ansicpg1251{${bs}*${bs}unknowndest {не видно}}${cp("видно")}${bs}par}"))
        assertEquals("P \"ëèøíèå\"\n", blocks("{${bs}rtf1 ${cp("лишние")}}"))
        assertEquals("P \"b\"\n", blocks("{${bs}rtf1{${bs}*${bs}objdata ${bs}bin3 }}}}b${bs}par}"))
    }
}
