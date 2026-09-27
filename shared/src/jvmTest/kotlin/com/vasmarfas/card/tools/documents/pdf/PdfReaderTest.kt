package com.vasmarfas.card.tools.documents.pdf

import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.common.PDRectangle

class PdfReaderTest {
    private fun content(text: String) = latin1("BT /F1 12 Tf 72 720 Td ($text) Tj ET")

    private fun threePages(
        pdf: RawPdf = RawPdf(),
        count: Int = 3,
        kids: String = "4 0 R 5 0 R 6 0 R",
        length: (Int, Int) -> String = { _, size -> size.toString() },
    ): RawPdf {
        pdf.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        pdf.obj(2, "<< /Type /Pages /Kids [$kids] /Count $count /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> >>")
        pdf.obj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        for (i in 0 until 3) pdf.obj(4 + i, "<< /Type /Page /Parent 2 0 R /Contents ${7 + i} 0 R >>")
        for (i in 0 until 3) {
            val data = content("Page ${i + 1} text")
            pdf.stream(7 + i, "<< /Length ${length(7 + i, data.size)} >>", data)
        }
        return pdf
    }

    private val trailer = "<< /Size 10 /Root 1 0 R >>"

    private fun assertThreePages(doc: PdfDocument) {
        assertEquals(3, doc.pageCount)
        for (i in 0 until 3) assertEquals("Page ${i + 1} text", PdfText.extract(doc, i))
        assertEquals(PdfRect(0.0, 0.0, 612.0, 792.0), doc.page(2).mediaBox)
    }

    @Test
    fun matchesPdfBoxOnGeneratedFiles() {
        val created = GregorianCalendar(TimeZone.getTimeZone("GMT+03:00")).apply { set(2024, Calendar.MARCH, 5, 14, 30, 15) }
        for (mode in SaveMode.entries) {
            val bytes = pdf(mode) { doc ->
                doc.textPage(helvetica(), listOf("First page"))
                doc.textPage(times(), listOf("Second"), PDRectangle.LETTER, rotation = 90)
                doc.textPage(courier(), listOf("Third"), PDRectangle(300f, 500f), rotation = 270).cropBox = PDRectangle(10f, 20f, 200f, 300f)
                doc.textPage(doc.arial(), listOf("Четвёртая страница"), PDRectangle.A5, rotation = 180)
                val info = doc.documentInformation
                info.title = "Отчёт за квартал"
                info.author = "Ivan Petrov"
                info.subject = "Subject (with parens)"
                info.keywords = "alpha, beta"
                info.creator = "Test suite"
                info.producer = "PDFBox"
                info.creationDate = created
                info.modificationDate = created
            }
            val ours = PdfDocument.parse(bytes)
            assertFalse(ours.repaired)
            assertFalse(ours.encrypted)
            Loader.loadPDF(bytes).use { box ->
                assertEquals(box.numberOfPages, ours.pageCount)
                assertEquals(box.version.toString(), ours.version)
                for (i in 0 until box.numberOfPages) {
                    val expected = box.getPage(i)
                    val page = ours.page(i)
                    assertRect(expected.mediaBox, page.mediaBox)
                    assertRect(expected.cropBox, page.cropBox)
                    assertEquals(expected.rotation, page.rotation)
                }
                val info = box.documentInformation
                assertEquals(info.title, ours.info["Title"])
                assertEquals(info.author, ours.info["Author"])
                assertEquals(info.subject, ours.info["Subject"])
                assertEquals(info.keywords, ours.info["Keywords"])
                assertEquals(info.creator, ours.info["Creator"])
                assertEquals(info.producer, ours.info["Producer"])
                assertEquals(info.cosObject.getString(COSName.CREATION_DATE), ours.info["CreationDate"])
                assertEquals(info.cosObject.getString(COSName.MOD_DATE), ours.info["ModDate"])
            }
            assertEquals(300.0, ours.page(2).width)
            assertEquals(200.0, ours.page(2).height)
            assertEquals(PDRectangle.LETTER.height.toDouble(), ours.page(1).width, 1e-3)
        }
    }

    private fun assertRect(expected: PDRectangle, actual: PdfRect) {
        assertEquals(expected.lowerLeftX.toDouble(), actual.left, 1e-3)
        assertEquals(expected.lowerLeftY.toDouble(), actual.bottom, 1e-3)
        assertEquals(expected.upperRightX.toDouble(), actual.right, 1e-3)
        assertEquals(expected.upperRightY.toDouble(), actual.top, 1e-3)
    }

    @Test
    fun compressedFilesUseObjectAndXrefStreams() {
        val bytes = pdf(SaveMode.COMPRESSED) { doc -> repeat(5) { doc.textPage(helvetica(), listOf("Compressed $it")) } }
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue("/ObjStm" in text && "/XRef" in text)
        val doc = PdfDocument.parse(bytes)
        assertFalse(doc.repaired)
        assertEquals(5, doc.pageCount)
        for (i in 0 until 5) assertEquals("Compressed $i", PdfText.extract(doc, i))
    }

    @Test
    fun incrementalUpdatesNewestWins() {
        for (mode in SaveMode.entries) {
            val original = pdf(mode) { doc ->
                repeat(3) { doc.textPage(helvetica(), listOf("Page $it")) }
                doc.documentInformation.title = "Old title"
            }
            val updated = Loader.loadPDF(original).use { doc ->
                doc.documentInformation.title = "New title"
                doc.documentInformation.cosObject.isNeedToBeUpdated = true
                doc.getPage(1).rotation = 90
                doc.getPage(1).cosObject.isNeedToBeUpdated = true
                ByteArrayOutputStream().also { doc.saveIncremental(it) }.toByteArray()
            }
            assertContentEquals(original, updated.copyOf(original.size))
            assertEquals(2, Regex("startxref").findAll(String(updated, Charsets.ISO_8859_1)).count())
            val doc = PdfDocument.parse(updated)
            assertFalse(doc.repaired)
            assertEquals("New title", doc.info["Title"])
            assertEquals(listOf(0, 90, 0), doc.pages.map { it.rotation })
            assertEquals("Page 2", PdfText.extract(doc, 2))
            assertEquals("Old title", PdfDocument.parse(original).info["Title"])
        }
    }

    @Test
    fun wellFormedRawFileNeedsNoRepair() {
        val doc = PdfDocument.parse(threePages().finish(trailer))
        assertFalse(doc.repaired)
        assertThreePages(doc)
        assertEquals("1.4", doc.version)
    }

    @Test
    fun garbageStartXrefOffset() {
        for (offset in listOf(5, 150, 999_999)) {
            val doc = PdfDocument.parse(threePages().finish(trailer, startxref = offset))
            assertTrue(doc.repaired)
            assertThreePages(doc)
        }
    }

    @Test
    fun missingXrefAndTrailer() {
        val doc = PdfDocument.parse(threePages().bytes())
        assertTrue(doc.repaired)
        assertThreePages(doc)
        val withTrailerOnly = threePages().raw("trailer\n<< /Root 1 0 R /Size 10 >>\n%%EOF\n").bytes()
        assertThreePages(PdfDocument.parse(withTrailerOnly))
    }

    @Test
    fun xrefOffsetsShiftedByJunkBeforeHeader() {
        val clean = threePages().finish(trailer)
        val shifted = latin1("GARBAGE ".repeat(20)) + clean
        val doc = PdfDocument.parse(shifted)
        assertFalse(doc.repaired)
        assertThreePages(doc)
        val far = latin1("x".repeat(2000)) + clean
        assertFailsWith<PdfException> { PdfDocument.parse(far) }
    }

    @Test
    fun wrongStreamLengths() {
        val tooLong = PdfDocument.parse(threePages(length = { number, size -> if (number == 8) "${size + 500}" else "$size" }).finish(trailer))
        assertThreePages(tooLong)
        val tooShort = PdfDocument.parse(threePages(length = { number, size -> if (number == 7) "3" else "$size" }).finish(trailer))
        assertThreePages(tooShort)
        val missingIndirect = PdfDocument.parse(threePages(length = { number, size -> if (number == 9) "77 0 R" else "$size" }).finish(trailer))
        assertThreePages(missingIndirect)
    }

    @Test
    fun indirectLengthAndMissingEndstream() {
        val pdf = RawPdf()
        pdf.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        pdf.obj(2, "<< /Type /Pages /Kids [4 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        pdf.obj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        pdf.obj(4, "<< /Type /Page /Parent 2 0 R /Contents 5 0 R /Resources << /Font << /F1 3 0 R >> >> >>")
        val data = content("Indirect length")
        pdf.stream(5, "<< /Length 6 0 R >>", data)
        pdf.obj(6, "${data.size}")
        pdf.offsets[7] = pdf.size
        pdf.raw("7 0 obj\n<< /Length 400 >>\nstream\nabc\nendobj\n")
        val bytes = pdf.finish("<< /Size 8 /Root 1 0 R >>")
        val doc = PdfDocument.parse(bytes)
        assertEquals("Indirect length", PdfText.extract(doc, 0))
        val broken = doc.resolve(PdfRef(7, 0))
        assertIs<PdfStream>(broken)
        assertContentEquals(latin1("abc"), broken.data)
    }

    @Test
    fun countLiesAndCycles() {
        val lying = PdfDocument.parse(threePages(count = 10).finish(trailer))
        assertThreePages(lying)
        val cyclic = PdfDocument.parse(threePages(kids = "4 0 R 2 0 R 5 0 R 6 0 R 2 0 R").finish(trailer))
        assertThreePages(cyclic)
        val broken = PdfDocument.parse(threePages(kids = "4 0 R 40 0 R 5 0 R (junk) 6 0 R").finish(trailer))
        assertThreePages(broken)
    }

    @Test
    fun malformedXrefTables() {
        for (eol in listOf("\r\n", " \n", " \r", "\n", "\r")) {
            val doc = PdfDocument.parse(threePages().finish(trailer, eol = eol))
            assertFalse(doc.repaired, "eol ${eol.map { it.code }}")
            assertThreePages(doc)
        }
        val offByOne = PdfDocument.parse(threePages().finish(trailer, first = 1))
        assertFalse(offByOne.repaired)
        assertThreePages(offByOne)
        val shiftedNumbers = PdfDocument.parse(threePages().finish(trailer, first = 3))
        assertThreePages(shiftedNumbers)
    }

    @Test
    fun missingObjectsResolveToNull() {
        val doc = PdfDocument.parse(threePages().finish(trailer))
        assertEquals(PdfNull, doc.resolve(PdfRef(55, 0)))
        assertEquals(PdfNull, doc.resolve(null))
        assertEquals(PdfNull, doc.resolve(PdfRef(1_000_000_000, 0)))
        assertEquals(PdfNull, doc.resolve(PdfRef(Int.MAX_VALUE, 0)))
        assertThreePages(doc)
    }

    private fun freedTitle(pdf: RawPdf, firstTable: String, info: String): ByteArray {
        val firstXref = pdf.size
        pdf.raw(firstTable).raw("trailer\n<< /Size 13 /Root 1 0 R /Info 12 0 R >>\nstartxref\n$firstXref\n%%EOF\n")
        pdf.obj(11, "<< /Title (New) >>")
        val update = pdf.size
        pdf.raw("xref\n0 1\n0000000000 65535 f\r\n12 1\n0000000000 00001 f\r\n")
        return pdf.raw("trailer\n<< /Size 13 /Root 1 0 R /Info $info /Prev $firstXref >>\nstartxref\n$update\n%%EOF\n").bytes()
    }

    @Test
    fun danglingReferencesLeaveTheXrefAlone() {
        val pdf = threePages().obj(12, "<< /Title (Old) >>")
        val doc = PdfDocument.parse(freedTitle(pdf, pdf.xrefTable(), "999 0 R"))
        assertEquals(emptyMap(), doc.info)
        assertEquals(PdfNull, doc.resolve(PdfRef(12, 0)))
        assertFalse(doc.repaired)
        assertThreePages(doc)
    }

    @Test
    fun repairAfterOpeningOnlyAddsWhatIsMissing() {
        val pdf = threePages().obj(12, "<< /Title (Old) >>")
        val table = StringBuilder("xref\n0 10\n0000000000 65535 f\r\n")
        for (n in 1..9) table.append("%010d 00000 n\r\n".format(pdf.offsets.getValue(n)))
        table.append("12 1\n%010d 00000 n\r\n".format(pdf.offsets.getValue(12)))
        val doc = PdfDocument.parse(freedTitle(pdf, table.toString(), "11 0 R"))
        assertEquals("New", doc.info["Title"])
        assertTrue(doc.repaired)
        assertEquals(PdfNull, doc.resolve(PdfRef(12, 0)))
        assertThreePages(doc)
    }

    @Test
    fun objectsInsideStreamsAreNotRecovered() {
        val pdf = threePages()
        val attached = latin1("%PDF-1.4\n4 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 8 0 R /Fake true >>\nendobj\ntrailer\n<< /Root 99 0 R >>\n")
        pdf.stream(10, "<< /Type /EmbeddedFile /Length ${attached.size} >>", attached)
        val doc = PdfDocument.parse(pdf.raw("trailer\n<< /Root 1 0 R /Size 11 >>\nstartxref\n77\n%%EOF\n").bytes())
        assertTrue(doc.repaired)
        assertEquals(null, doc.page(0).dict["Fake"])
        assertThreePages(doc)
    }

    @Test
    fun rejectsNonPdf() {
        assertFailsWith<PdfException> { PdfDocument.parse(latin1("Hello, this is not a PDF at all")) }
        assertFailsWith<PdfException> { PdfDocument.parse(latin1("%PDF-1.7\n1 0 obj\n<< /Foo 1 >>\nendobj\n")) }
    }

    private fun xrefStreamFile(): Pair<RawPdf, Int> {
        val pdf = RawPdf("%PDF-1.5\n")
        pdf.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        pdf.obj(2, "<< /Type /Pages /Kids [4 0 R] /Count 1 >>")
        pdf.obj(4, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Contents 7 0 R /Resources << /Font << /F1 3 0 R >> >> >>")
        val data = content("From object stream")
        pdf.stream(7, "<< /Length ${data.size} >>", data)
        val (objStm, first) = objectStream(3 to "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>", 8 to "<< /Title (Original) >>")
        pdf.stream(10, "<< /Type /ObjStm /N 2 /First $first /Length ${objStm.size} >>", objStm)
        val xrefAt = pdf.size
        val rows = listOf(
            intArrayOf(0, 0, 255),
            intArrayOf(1, pdf.offsets.getValue(1), 0),
            intArrayOf(1, pdf.offsets.getValue(2), 0),
            intArrayOf(2, 10, 0),
            intArrayOf(1, pdf.offsets.getValue(4), 0),
            intArrayOf(0, 0, 0),
            intArrayOf(0, 0, 0),
            intArrayOf(1, pdf.offsets.getValue(7), 0),
            intArrayOf(2, 10, 1),
            intArrayOf(0, 0, 0),
            intArrayOf(1, pdf.offsets.getValue(10), 0),
            intArrayOf(1, xrefAt, 0),
        )
        val stream = xrefStreamData(rows, intArrayOf(1, 4, 1))
        pdf.stream(
            11,
            "<< /Type /XRef /Size 12 /W [1 4 1] /Root 1 0 R /Info 8 0 R /Filter /FlateDecode " +
                "/DecodeParms << /Predictor 12 /Columns 6 >> /Length ${stream.size} >>",
            stream,
        )
        return pdf to xrefAt
    }

    @Test
    fun xrefStreamsWithPredictorAndObjectStreams() {
        val (pdf, xrefAt) = xrefStreamFile()
        val bytes = pdf.raw("startxref\n$xrefAt\n%%EOF\n").bytes()
        val doc = PdfDocument.parse(bytes)
        assertFalse(doc.repaired)
        assertEquals("Original", doc.info["Title"])
        assertEquals("From object stream", PdfText.extract(doc, 0))
        assertEquals("1.5", doc.version)
    }

    @Test
    fun xrefStreamPrevChain() {
        val (pdf, firstXref) = xrefStreamFile()
        pdf.raw("startxref\n$firstXref\n%%EOF\n")
        pdf.obj(12, "<< /Title (Updated) >>")
        val secondXref = pdf.size
        val rows = listOf(intArrayOf(1, pdf.offsets.getValue(12), 0), intArrayOf(1, secondXref, 0))
        val stream = xrefStreamData(rows, intArrayOf(1, 4, 1))
        pdf.stream(
            13,
            "<< /Type /XRef /Size 14 /Index [12 2] /W [1 4 1] /Root 1 0 R /Info 12 0 R /Prev $firstXref /Filter /FlateDecode " +
                "/DecodeParms << /Predictor 12 /Columns 6 >> /Length ${stream.size} >>",
            stream,
        )
        val doc = PdfDocument.parse(pdf.raw("startxref\n$secondXref\n%%EOF\n").bytes())
        assertFalse(doc.repaired)
        assertEquals("Updated", doc.info["Title"])
        assertEquals("From object stream", PdfText.extract(doc, 0))
    }

    @Test
    fun objectStreamsFoundWhenXrefIsBroken() {
        val (pdf, _) = xrefStreamFile()
        val (objStm, first) = objectStream(9 to "<< /Title (Extended) >>")
        pdf.stream(12, "<< /Type /ObjStm /N 1 /First $first /Extends 10 0 R /Length ${objStm.size} >>", objStm)
        pdf.raw("trailer\n<< /Root 1 0 R /Info 9 0 R >>\nstartxref\n123\n%%EOF\n")
        val doc = PdfDocument.parse(pdf.bytes())
        assertTrue(doc.repaired)
        assertEquals("Extended", doc.info["Title"])
        assertEquals("From object stream", PdfText.extract(doc, 0))
    }

    @Test
    fun hybridFile() {
        val pdf = RawPdf("%PDF-1.5\n")
        pdf.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        pdf.obj(2, "<< /Type /Pages /Kids [4 0 R] /Count 1 /MediaBox [0 0 300 300] >>")
        pdf.obj(4, "<< /Type /Page /Parent 2 0 R /Contents 7 0 R /Resources 5 0 R >>")
        val data = content("Hybrid reference")
        pdf.stream(7, "<< /Length ${data.size} >>", data)
        val (objStm, first) = objectStream(3 to "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>", 5 to "<< /Font << /F1 3 0 R >> >>")
        pdf.stream(10, "<< /Type /ObjStm /N 2 /First $first /Length ${objStm.size} >>", objStm)
        val streamAt = pdf.size
        val rows = listOf(intArrayOf(2, 10, 0), intArrayOf(2, 10, 1))
        val xrefData = xrefStreamData(rows, intArrayOf(1, 4, 1))
        pdf.stream(
            11,
            "<< /Type /XRef /Size 12 /Index [3 1 5 1] /W [1 4 1] /Filter /FlateDecode /DecodeParms << /Predictor 12 /Columns 6 >> " +
                "/Length ${xrefData.size} >>",
            xrefData,
        )
        val tableAt = pdf.size
        pdf.raw(pdf.xrefTable(numbers = listOf(1, 2, 4, 7, 10, 11)))
        pdf.raw("trailer\n<< /Size 12 /Root 1 0 R /XRefStm $streamAt >>\nstartxref\n$tableAt\n%%EOF\n")
        val doc = PdfDocument.parse(pdf.bytes())
        assertFalse(doc.repaired)
        assertEquals("Hybrid reference", PdfText.extract(doc, 0))
        assertEquals("Helvetica", doc.page(0).resources.dict("Font", doc)!!.dict("F1", doc)!!.name("BaseFont"))
    }

    @Test
    fun inheritedAttributes() {
        val pdf = RawPdf()
        pdf.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        pdf.obj(2, "<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 3 /MediaBox [0 0 400 600] /Rotate 90 /Resources << /Font << /F1 7 0 R >> >> >>")
        pdf.obj(3, "<< /Type /Pages /Parent 2 0 R /Kids [4 0 R] /Count 1 /CropBox [-50 10 300 1000] /Rotate -90 >>")
        pdf.obj(4, "<< /Type /Page /Parent 3 0 R >>")
        pdf.obj(5, "<< /Type /Page /Parent 2 0 R /MediaBox [612 792 0 0] /Rotate 450 >>")
        pdf.obj(7, "<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman >>")
        val doc = PdfDocument.parse(pdf.finish("<< /Size 8 /Root 1 0 R >>"))
        assertEquals(2, doc.pageCount)
        val first = doc.page(0)
        assertEquals(PdfRect(0.0, 0.0, 400.0, 600.0), first.mediaBox)
        assertEquals(PdfRect(0.0, 10.0, 300.0, 600.0), first.cropBox)
        assertEquals(270, first.rotation)
        assertEquals(590.0, first.width)
        assertEquals("Times-Roman", first.resources.dict("Font", doc)!!.dict("F1", doc)!!.name("BaseFont"))
        val second = doc.page(1)
        assertEquals(PdfRect(0.0, 0.0, 612.0, 792.0), second.mediaBox)
        assertEquals(90, second.rotation)
        assertEquals(PdfRef(5, 0), second.ref)
    }
}
