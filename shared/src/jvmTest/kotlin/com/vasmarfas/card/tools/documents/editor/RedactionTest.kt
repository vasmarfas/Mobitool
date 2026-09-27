package com.vasmarfas.card.tools.documents.editor

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import com.vasmarfas.card.core.imageBitmapOf
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.documents.documentFonts
import com.vasmarfas.card.tools.documents.pdf.PdfDocument
import com.vasmarfas.card.tools.documents.pdf.PdfRect
import com.vasmarfas.card.tools.documents.pdf.helvetica
import com.vasmarfas.card.tools.documents.pdf.loadCleanly
import com.vasmarfas.card.tools.documents.pdf.normalize
import com.vasmarfas.card.tools.documents.pdf.pdf
import com.vasmarfas.card.tools.documents.pdf.pdfboxText
import com.vasmarfas.card.tools.documents.pdf.textPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.compose.resources.getString

@OptIn(InternalComposeUiApi::class)
class RedactionTest {
    init {
        registerSkikoComposeImplementation()
    }

    private val black = 0xFF000000.toInt()

    private val statement = pdf { doc ->
        doc.textPage(helvetica(), listOf("IBAN 1234", "Keep this line"))
        doc.textPage(helvetica(), listOf("Second page"))
    }

    private val form = pdf { doc ->
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        val acro = PDAcroForm(doc)
        doc.documentCatalog.acroForm = acro
        val widget = PDAnnotationWidget()
        widget.rectangle = PDRectangle(50f, 650f, 16f, 16f)
        widget.page = page
        page.annotations.add(widget)
        val normal = COSDictionary()
        for (name in listOf("Yes", "Off")) {
            val stream = PDAppearanceStream(doc)
            stream.bBox = PDRectangle(16f, 16f)
            stream.cosObject.createOutputStream().use { it.write((if (name == "Off") "" else "0 g 2 2 12 12 re f").toByteArray()) }
            normal.setItem(COSName.getPDFName(name), stream)
        }
        widget.cosObject.setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, normal) })
        widget.cosObject.setName(COSName.AS, "Off")
        val box = PDCheckBox(acro)
        box.partialName = "agree"
        box.widgets = listOf(widget)
        acro.fields.add(box)
    }

    private suspend fun open(bytes: ByteArray): EditorSession {
        val document = PdfDocument.parse(bytes)
        return EditorSession("test.pdf", document, MarkFonts(documentFonts(serif = true)), startingEdit(document), PdfForms.read(document), signed = false)
    }

    private fun EditorSession.redacted(box: PdfRect): EditPage = edit.pages.first().copy(marks = listOf(CoverMark(id(), box, black, redact = true)))

    private fun pixel(doc: PDDocument, page: Int, x: Double, y: Double): Int {
        val image = PDFRenderer(doc).renderImage(page, 1f)
        return image.getRGB(x.toInt(), (doc.getPage(page).mediaBox.height - y).toInt()) and 0xFFFFFF
    }

    private fun dark(rgb: Int) = rgb shr 16 < 0x40 && (rgb shr 8 and 0xFF) < 0x40 && (rgb and 0xFF) < 0x40

    private fun red(rgb: Int) = rgb shr 16 > 0xC0 && (rgb shr 8 and 0xFF) < 0x40 && (rgb and 0xFF) < 0x40

    @Test
    fun extractedPagesKeepNothingUnderTheBox() = runBlocking {
        val session = open(statement)
        val pages = listOf(session.redacted(PdfRect(45.0, 777.0, 150.0, 795.0)))
        val out = buildPdf(session, MarkRenderer(session.fonts), session.edit.copy(pages = pages, outline = emptyList()), EditorSaveOptions()) {}
        loadCleanly(out) { doc ->
            assertEquals(1, doc.numberOfPages)
            assertTrue(dark(pixel(doc, 0, 100.0, 786.0)))
        }
        assertEquals("", normalize(pdfboxText(out, 0)))
    }

    @Test
    fun picturesAndBlankPagesAreBurnedToo() = runBlocking {
        val fonts = MarkFonts(documentFonts(serif = true))
        val photo = MarkImage(40, 40, rgb = ByteArray(40 * 40 * 3) { if (it % 3 == 0) 0xFF.toByte() else 0 })
        val pages = listOf(
            EditPage(1, ImagePage(photo, 200.0, 200.0), marks = listOf(CoverMark(3, PdfRect(0.0, 0.0, 100.0, 200.0), black, redact = true))),
            EditPage(
                2,
                BlankPage(200.0, 200.0),
                marks = listOf(TextMark(4, PdfRect(20.0, 100.0, 180.0, 130.0), "IBAN 1234", 14f, black), CoverMark(5, PdfRect(10.0, 90.0, 190.0, 140.0), black, redact = true)),
            ),
        )
        val session = EditorSession("new.pdf", null, fonts, DocumentEdit(pages), emptyList(), signed = false)
        session.images[photo] = imageBitmapOf(IntArray(40 * 40) { 0xFFFF0000.toInt() }, 40, 40)
        val out = buildPdf(session, MarkRenderer(fonts), session.edit, EditorSaveOptions()) {}
        loadCleanly(out) { doc ->
            val resources = doc.getPage(0).resources
            assertEquals(listOf("jpg"), resources.xObjectNames.map { (resources.getXObject(it) as PDImageXObject).suffix })
            assertTrue(dark(pixel(doc, 0, 50.0, 100.0)))
            assertTrue(red(pixel(doc, 0, 150.0, 100.0)))
        }
        assertEquals("", normalize(pdfboxText(out, 1)))
    }

    @Test
    fun burnedPagesKeepWhatTheFieldsShow() = runBlocking {
        val session = open(form)
        session.setField(session.fields.single(), FieldValue.Check("Yes"))
        val pages = listOf(session.redacted(PdfRect(300.0, 300.0, 400.0, 400.0)))
        val out = buildPdf(session, MarkRenderer(session.fonts), session.edit.copy(pages = pages), EditorSaveOptions()) {}
        loadCleanly(out) { doc ->
            assertTrue(doc.getPage(0).annotations.isEmpty())
            assertTrue(dark(pixel(doc, 0, 58.0, 658.0)))
            assertTrue(dark(pixel(doc, 0, 350.0, 350.0)))
        }
    }

    @Test
    fun aPageThatCannotBeRenderedStopsTheSave() = runBlocking {
        val fonts = MarkFonts(documentFonts(serif = true))
        val lost = MarkImage(4, 4, rgb = ByteArray(4 * 4 * 3))
        val page = EditPage(1, ImagePage(lost, 200.0, 200.0), marks = listOf(CoverMark(2, PdfRect(0.0, 0.0, 100.0, 100.0), black, redact = true)))
        val session = EditorSession("new.pdf", null, fonts, DocumentEdit(listOf(page)), emptyList(), signed = false)
        val error = assertFailsWith<IllegalStateException> { buildPdf(session, MarkRenderer(fonts), session.edit, EditorSaveOptions()) {} }
        assertEquals(getString(Res.string.pdf_edit_redaction_failed), error.message)
    }
}
