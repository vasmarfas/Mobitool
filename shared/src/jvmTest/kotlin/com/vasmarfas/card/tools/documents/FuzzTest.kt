package com.vasmarfas.card.tools.documents

import com.vasmarfas.card.core.ZipArchive
import com.vasmarfas.card.core.ZipWriter
import com.vasmarfas.card.resources.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

// damaged input may only end in DocumentFormatException. Mutations are seeded so a failure reproduces,
// every read is timed to catch a hang
class FuzzTest {
    private val random = Random(20260923)

    private val rtfSeed = (
        "{\\rtf1\\ansi\\ansicpg1251{\\fonttbl{\\f0\\fcharset204 Arial;}{\\f1\\fmodern Courier;}}{\\stylesheet{\\s1 heading 1;}}" +
            "{\\*\\listtable{\\list{\\listlevel\\levelnfc23\\li720}\\listid1}}{\\*\\listoverridetable{\\listoverride\\listid1\\ls1}}" +
            "\\pard\\s1\\outlinelevel0 \\'c3\\'eb\\'e0\\'e2\\'e0\\par\\pard\\ls1 item\\par\\pard{\\b bold}\\u1071?{\\field{\\*\\fldinst HYPERLINK \"https://x.y\"}{\\fldrslt link}}\\par" +
            "\\trowd\\cellx100\\clmrg\\cellx200\\pard\\intbl a\\cell b\\cell\\row{\\pict\\pngblip 89504e47}{\\footnote note}\\par}"
        ).encodeToByteArray()

    private fun seeds(): Map<DocFormat, List<ByteArray>> {
        val doc = Samples.rich()
        val result = HashMap<DocFormat, List<ByteArray>>()
        for (format in DocFormat.entries.filter { it.writable }) result[format] = listOf(Documents.write(doc, format))
        result[DocFormat.RTF] = listOf(rtfSeed)
        result[DocFormat.FB2] = result.getValue(DocFormat.FB2) + ZipWriter().add("book.fb2", result.getValue(DocFormat.FB2)[0]).toByteArray()
        return result
    }

    private fun readSafely(bytes: ByteArray, format: DocFormat, label: String) {
        val mark = TimeSource.Monotonic.markNow()
        try {
            Documents.read(bytes, format)
        } catch (e: DocumentFormatException) {
        } catch (e: Throwable) {
            fail("$format/$label threw ${e::class.simpleName}: ${e.message}", e)
        }
        val elapsed = mark.elapsedNow().inWholeMilliseconds
        assertTrue(elapsed < 5000, "$format/$label took $elapsed ms")
    }

    @Test
    fun truncatedInputs() {
        for ((format, list) in seeds()) {
            for (seed in list) {
                val cuts = (0 until 60).map { random.nextInt(seed.size + 1) } + listOf(0, 1, 2, 3, 4, seed.size - 1, seed.size / 2)
                for (cut in cuts) readSafely(seed.copyOf(cut.coerceIn(0, seed.size)), format, "truncated at $cut")
            }
        }
    }

    @Test
    fun flippedBytes() {
        for ((format, list) in seeds()) {
            for (seed in list) {
                repeat(80) { round ->
                    val copy = seed.copyOf()
                    repeat(1 + random.nextInt(12)) { copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() }
                    readSafely(copy, format, "flip round $round")
                }
            }
        }
    }

    // byte flips in deflated data mostly end at the CRC check, so the XML inside is damaged before packing
    @Test
    fun damagedPartsInsideArchives() {
        val garbage = listOf("<", "</", "<w:p>", "</w:p>", "<w:tbl><w:tr>", "<text:list><text:list-item>", "<table:table-row>", "&", "&#xD800;", "<![CDATA[", "\"", "<?x", "<!--", "\u0000", "é", "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>")
        for (format in listOf(DocFormat.DOCX, DocFormat.ODT, DocFormat.EPUB)) {
            val seed = Documents.write(Samples.rich(), format)
            val archive = ZipArchive(seed)
            val parts = archive.entries.map { it.name to archive.read(it) }
            repeat(150) { round ->
                val zip = ZipWriter()
                val victim = random.nextInt(parts.size)
                parts.forEachIndexed { i, (name, data) ->
                    val bytes = if (i != victim || name == "mimetype") data else mutateText(data.decodeToString(), garbage).encodeToByteArray()
                    zip.add(name, bytes, compress = name != "mimetype")
                }
                readSafely(zip.toByteArray(), format, "part ${parts[victim].first} round $round")
            }
        }
    }

    @Test
    fun textFormatsWithInsertedMarkup() {
        val markup = mapOf(
            DocFormat.HTML to listOf("<", "<table>", "<td>", "</tr>", "<ul>", "<li>", "<pre>", "&#", "<img src=\"data:image/png;base64,", "<!--", "<svg>", "<style>"),
            DocFormat.MARKDOWN to listOf("*", "**", "_", "`", "```", "[", "](", "![", "<", "|", "> ", "- ", "1. ", "\\", "&", "    ", "\t"),
            DocFormat.RTF to listOf("{", "}", "\\", "\\'", "\\u", "\\bin", "\\pict", "\\field", "\\cell", "\\row", "\\*", "\\uc2", "\\'ff"),
            DocFormat.FB2 to listOf("<section>", "</section>", "<a l:href=\"#", "<binary id=\"x\">", "&", "<poem>", "<table><tr>"),
            DocFormat.TXT to listOf("\u000C", "\r", "\u0000", "\uFEFF"),
        )
        for ((format, pieces) in markup) {
            val seed = if (format == DocFormat.RTF) rtfSeed.decodeToString() else Documents.write(Samples.rich(), format).decodeToString()
            repeat(150) { round -> readSafely(mutateText(seed, pieces).encodeToByteArray(), format, "markup round $round") }
        }
    }

    @Test
    fun randomBytes() {
        for (format in DocFormat.entries) {
            repeat(40) { round ->
                val bytes = ByteArray(random.nextInt(2000)) { random.nextInt(256).toByte() }
                if (round % 4 == 0 && bytes.size >= 4) "PK\u0003\u0004".encodeToByteArray().copyInto(bytes)
                if (round % 4 == 1 && bytes.size >= 5) "{\\rtf".encodeToByteArray().copyInto(bytes)
                readSafely(bytes, format, "random round $round")
            }
        }
    }

    @Test
    fun deepNesting() {
        val depth = 100_000
        readSafely(("<w:document><w:body>" + "<w:sdt><w:sdtContent>".repeat(depth) + "<w:p/>").encodeToByteArray().let(::docxWith), DocFormat.DOCX, "sdt")
        readSafely(("<FictionBook><body>" + "<section>".repeat(depth)).encodeToByteArray(), DocFormat.FB2, "sections")
        readSafely(("<FictionBook><body><section><p>" + "<strong>".repeat(depth) + "x").encodeToByteArray(), DocFormat.FB2, "inline")
        readSafely(("<p>" + "<span><b>".repeat(depth)).encodeToByteArray(), DocFormat.HTML, "html")
        readSafely((">".repeat(depth) + "x\n" + "- ".repeat(depth) + "y\n" + "*".repeat(depth) + "z").encodeToByteArray(), DocFormat.MARKDOWN, "markdown")
        readSafely(("{\\rtf1" + "{".repeat(depth) + "x").encodeToByteArray(), DocFormat.RTF, "rtf")
    }

    private fun docxWith(document: ByteArray): ByteArray = ZipWriter().add("word/document.xml", document).toByteArray()

    @Test
    fun zipBombsFailBeforeAllocating() {
        val real = ZipWriter().add("word/document.xml", ByteArray(20 * 1024 * 1024)).toByteArray()
        val lyingSmall = patchUncompressedSize(real, 1000)
        assertFailsWith<DocumentFormatException> { Documents.read(lyingSmall, DocFormat.DOCX) }
        val lyingHuge = patchUncompressedSize(ZipWriter().add("word/document.xml", "<w:document/>".encodeToByteArray(), compress = false).toByteArray(), 250 * 1024 * 1024)
        val mark = TimeSource.Monotonic.markNow()
        assertFailsWith<DocumentFormatException> { Documents.read(lyingHuge, DocFormat.DOCX) }
        assertTrue(mark.elapsedNow().inWholeMilliseconds < 1000)
        val chapters = 1..3
        val opf = "<package><manifest>" + chapters.joinToString("") { "<item id=\"c$it\" href=\"c$it.xhtml\" media-type=\"application/xhtml+xml\"/>" } +
            "</manifest><spine>" + chapters.joinToString("") { "<itemref idref=\"c$it\"/>" } + "</spine></package>"
        val spaces = ByteArray(70 * 1024 * 1024) { ' '.code.toByte() }
        val bomb = ZipWriter()
            .add("mimetype", "application/epub+zip".encodeToByteArray(), compress = false)
            .add("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"a.opf\"/></rootfiles></container>".encodeToByteArray())
            .add("a.opf", opf.encodeToByteArray())
        for (i in chapters) bomb.add("c$i.xhtml", spaces)
        val packed = bomb.toByteArray()
        assertTrue(packed.size < 2 * 1024 * 1024)
        assertEquals(Res.string.document_too_large, assertFailsWith<DocumentFormatException> { Documents.read(packed, DocFormat.EPUB) }.reason)
    }

    private fun patchUncompressedSize(zip: ByteArray, size: Int): ByteArray {
        val out = zip.copyOf()
        fun put(at: Int) {
            out[at] = size.toByte()
            out[at + 1] = (size ushr 8).toByte()
            out[at + 2] = (size ushr 16).toByte()
            out[at + 3] = (size ushr 24).toByte()
        }
        for (i in 0 until out.size - 4) {
            val signature = (out[i].toInt() and 0xFF) or ((out[i + 1].toInt() and 0xFF) shl 8) or ((out[i + 2].toInt() and 0xFF) shl 16) or ((out[i + 3].toInt() and 0xFF) shl 24)
            if (signature == 0x04034b50) put(i + 22)
            if (signature == 0x02014b50) put(i + 24)
        }
        return out
    }

    private fun mutateText(text: String, pieces: List<String>): String {
        val sb = StringBuilder(text)
        repeat(1 + random.nextInt(6)) {
            val at = random.nextInt(sb.length + 1)
            when (random.nextInt(4)) {
                0 -> sb.insert(at, pieces[random.nextInt(pieces.size)])
                1 -> if (sb.isNotEmpty()) sb.deleteRange(at.coerceAtMost(sb.length - 1), (at + random.nextInt(50)).coerceAtMost(sb.length))
                2 -> sb.insert(at, sb.substring(at, (at + random.nextInt(200)).coerceAtMost(sb.length)))
                else -> sb.setLength(at)
            }
        }
        return sb.toString()
    }
}
