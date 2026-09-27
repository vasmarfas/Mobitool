package com.vasmarfas.card.tools.documents.editor

import com.vasmarfas.card.tools.documents.DocFonts
import com.vasmarfas.card.tools.documents.FontFamily
import com.vasmarfas.card.tools.documents.pdf.PageContent
import com.vasmarfas.card.tools.documents.pdf.PdfDocument
import com.vasmarfas.card.tools.documents.pdf.PdfRect
import com.vasmarfas.card.tools.documents.pdf.PdfText
import com.vasmarfas.card.tools.documents.pdf.SaveMode
import com.vasmarfas.card.tools.documents.pdf.TrueTypeFont
import com.vasmarfas.card.tools.documents.pdf.arial
import com.vasmarfas.card.tools.documents.pdf.helvetica
import com.vasmarfas.card.tools.documents.pdf.loadCleanly
import com.vasmarfas.card.tools.documents.pdf.normalize
import com.vasmarfas.card.tools.documents.pdf.pdf
import com.vasmarfas.card.tools.documents.pdf.pdfboxText
import com.vasmarfas.card.tools.documents.pdf.save
import com.vasmarfas.card.tools.documents.pdf.squeeze
import com.vasmarfas.card.tools.documents.pdf.textPage
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.graphics.color.PDColor
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField
import org.apache.pdfbox.rendering.PDFRenderer

class EditWriterTest {
    private fun font(name: String) = TrueTypeFont(File("src/commonMain/composeResources/files/fonts/$name.ttf").readBytes())

    private val fonts = MarkFonts(
        DocFonts(
            body = FontFamily(font("MobitoolSerif-Regular"), font("MobitoolSerif-Bold"), font("MobitoolSerif-Italic"), font("MobitoolSerif-BoldItalic")),
            headings = FontFamily(font("MobitoolSans-Regular"), font("MobitoolSans-Bold"), font("MobitoolSans-Italic"), font("MobitoolSans-BoldItalic")),
            code = FontFamily(font("MobitoolMono-Regular"), font("MobitoolMono-Bold")),
        ),
    )

    private var nextId = 1L

    private fun id() = nextId++

    private fun threePages() = pdf { doc ->
        doc.textPage(helvetica(), listOf("Page one"))
        doc.textPage(helvetica(), listOf("Page two"))
        doc.textPage(helvetica(), listOf("Page three"))
        doc.documentInformation.title = "Three"
    }

    private fun pages(doc: PdfDocument) = doc.pages.map { EditPage(id(), SourcePage(it)) }

    private fun write(main: PdfDocument?, edit: DocumentEdit, options: SaveOptions = SaveOptions()) = PdfEditWriter.write(main, edit, fonts, options, date = "D:20260925120000Z")

    private fun pixel(doc: PDDocument, page: Int, x: Double, y: Double): Int {
        val image = PDFRenderer(doc).renderImage(page, 1f)
        val height = doc.getPage(page).mediaBox.height
        return image.getRGB(x.toInt(), (height - y).toInt()) and 0xFFFFFF
    }

    @Test
    fun pagesAreReorderedTurnedCroppedAndInserted() {
        val source = PdfDocument.parse(threePages())
        val (one, _, three) = pages(source)
        val crop = PdfRect(20.0, 400.0, 500.0, 842.0)
        val edit = DocumentEdit(listOf(three.copy(turn = 90), EditPage(id(), BlankPage(300.0, 400.0)), one.copy(crop = crop)))
        loadCleanly(write(source, edit)) { doc ->
            assertEquals(3, doc.numberOfPages)
            assertEquals(90, doc.getPage(0).rotation)
            assertEquals("Pagethree", squeeze(pdfboxText(doc, 0)))
            assertEquals(300f, doc.getPage(1).mediaBox.width)
            assertEquals("", normalize(pdfboxText(doc, 1)))
            assertEquals(20f, doc.getPage(2).cropBox.lowerLeftX)
            assertEquals(400f, doc.getPage(2).cropBox.lowerLeftY)
            assertEquals("Page one", normalize(pdfboxText(doc, 2)))
            assertEquals("Three", doc.documentInformation.title)
            assertEquals("vasmarfas Mobitool", doc.documentInformation.producer)
        }
    }

    @Test
    fun marksAreDrawnIntoThePage() {
        val source = PdfDocument.parse(threePages())
        val page = pages(source).first()
        val marks = listOf(
            TextMark(id(), PdfRect(100.0, 500.0, 400.0, 540.0), "Привет, мир ₽", 14f, 0xFF1565C0.toInt()),
            InkMark(id(), InkPath(floatArrayOf(100f, 300f, 200f, 300f, 300f, 300f)), 0xFFFF0000.toInt(), 6f),
            CoverMark(id(), PdfRect(400.0, 100.0, 500.0, 200.0), 0xFF000000.toInt(), redact = true),
            ShapeMark(id(), ShapeKind.RECTANGLE, 100.0, 600.0, 200.0, 650.0, 0xFF00AA00.toInt(), 2f, fill = 0xFF00AA00.toInt()),
        )
        loadCleanly(write(source, DocumentEdit(listOf(page.copy(marks = marks))))) { doc ->
            val text = normalize(pdfboxText(doc, 0))
            assertTrue("Page one" in text, text)
            assertTrue("Привет, мир ₽" in text, text)
            assertEquals(0x000000, pixel(doc, 0, 450.0, 150.0))
            assertEquals(0xFF0000, pixel(doc, 0, 200.0, 300.0))
            assertEquals(0x00AA00, pixel(doc, 0, 150.0, 625.0))
            assertTrue(doc.getPage(0).annotations.isEmpty())
        }
    }

    @Test
    fun marksCanStayEditable() {
        val source = PdfDocument.parse(threePages())
        val page = pages(source).first()
        val quad = Quad(doubleArrayOf(50.0, 790.0, 150.0, 790.0, 50.0, 775.0, 150.0, 775.0))
        val marks = listOf(
            InkMark(id(), InkPath(floatArrayOf(100f, 300f, 200f, 320f)), 0xFF0000FF.toInt(), 3f),
            TextMark(id(), PdfRect(100.0, 500.0, 300.0, 530.0), "Заметка", 12f, 0xFF000000.toInt()),
            MarkupMark(id(), MarkupKind.HIGHLIGHT, listOf(quad), 0xFFFFEB3B.toInt(), "Page one"),
            NoteMark(id(), 400.0, 700.0, "Проверить", 0xFFFFEB3B.toInt()),
            CoverMark(id(), PdfRect(400.0, 100.0, 500.0, 200.0), 0xFFFFFFFF.toInt()),
        )
        loadCleanly(write(source, DocumentEdit(listOf(page.copy(marks = marks))), SaveOptions(keepMarksEditable = true))) { doc ->
            val annotations = doc.getPage(0).annotations
            assertEquals(listOf("Ink", "FreeText", "Highlight", "Text"), annotations.map { it.subtype })
            assertTrue(annotations.all { it.normalAppearanceStream != null })
            assertEquals("Проверить", annotations[3].contents)
            assertEquals("Page one", annotations[2].contents)
            assertEquals(0xFFFFFF, pixel(doc, 0, 450.0, 150.0))
        }
    }

    @Test
    fun shapeFillStaysTranslucent() {
        val shape = ShapeMark(id(), ShapeKind.RECTANGLE, 100.0, 600.0, 300.0, 700.0, 0xFF00AA00.toInt(), 2f, fill = 0x4000AA00)
        val page = EditPage(id(), BlankPage(595.0, 842.0), marks = listOf(shape))
        for (options in listOf(SaveOptions(), SaveOptions(keepMarksEditable = true))) {
            loadCleanly(write(null, DocumentEdit(listOf(page)), options)) { doc ->
                val inside = pixel(doc, 0, 200.0, 650.0)
                assertTrue(inside shr 16 in 0xB0..0xD0 && (inside shr 8 and 0xFF) > 0xE0, Integer.toHexString(inside))
            }
        }
    }

    private fun widget(doc: PDDocument, page: PDPage, rect: PDRectangle, state: String? = null): PDAnnotationWidget {
        val widget = PDAnnotationWidget()
        widget.rectangle = rect
        widget.page = page
        page.annotations.add(widget)
        if (state != null) {
            val normal = COSDictionary()
            for (name in listOf(state, "Off")) {
                val stream = PDAppearanceStream(doc)
                stream.bBox = PDRectangle(rect.width, rect.height)
                stream.cosObject.createOutputStream().use { it.write((if (name == "Off") "" else "0 g 2 2 ${rect.width - 4} ${rect.height - 4} re f").toByteArray()) }
                normal.setItem(COSName.getPDFName(name), stream)
            }
            widget.cosObject.setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, normal) })
            widget.cosObject.setName(COSName.AS, "Off")
        }
        return widget
    }

    private fun formDocument() = pdf { doc ->
        val first = PDPage(PDRectangle.A4)
        val second = PDPage(PDRectangle.A4)
        doc.addPage(first)
        doc.addPage(second)
        val form = PDAcroForm(doc)
        doc.documentCatalog.acroForm = form
        form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), helvetica()) }
        form.defaultAppearance = "/Helv 0 Tf 0 g"
        val name = PDTextField(form)
        name.partialName = "name"
        name.defaultAppearance = "/Helv 12 Tf 0 0 1 rg"
        name.widgets = listOf(widget(doc, first, PDRectangle(50f, 700f, 200f, 20f)))
        val agree = PDCheckBox(form)
        agree.partialName = "agree"
        agree.widgets = listOf(widget(doc, first, PDRectangle(50f, 650f, 16f, 16f), "Yes"))
        val choice = PDRadioButton(form)
        choice.partialName = "choice"
        choice.widgets = listOf(widget(doc, first, PDRectangle(50f, 600f, 16f, 16f), "A"), widget(doc, first, PDRectangle(80f, 600f, 16f, 16f), "B"))
        val city = PDComboBox(form)
        city.partialName = "city"
        city.defaultAppearance = "/Helv 10 Tf 0 g"
        city.options = listOf("Москва", "Казань")
        city.widgets = listOf(widget(doc, first, PDRectangle(50f, 550f, 150f, 20f)))
        val other = PDTextField(form)
        other.partialName = "other"
        other.defaultAppearance = "/Helv 12 Tf 0 g"
        other.widgets = listOf(widget(doc, second, PDRectangle(50f, 700f, 200f, 20f)))
        form.fields.addAll(listOf(name, agree, choice, city, other))
    }

    @Test
    fun formFieldsAreReadWithTheirKinds() {
        val fields = PdfForms.read(PdfDocument.parse(formDocument()))
        assertEquals(listOf("name", "agree", "choice", "city", "other"), fields.map { it.name })
        assertEquals(listOf(FieldKind.TEXT, FieldKind.CHECKBOX, FieldKind.RADIO, FieldKind.COMBO, FieldKind.TEXT), fields.map { it.kind })
        assertEquals(listOf("A", "B"), fields[2].widgets.map { it.onState })
        assertEquals(listOf("Москва", "Казань"), fields[3].options.map { it.label })
        assertEquals(12f, fields[0].fontSize)
        assertEquals(0xFF0000FF.toInt(), fields[0].textColor)
        assertEquals(listOf(0, 0, 0, 0, 1), fields.map { it.widgets.first().pageIndex })
    }

    @Test
    fun filledFormKeepsWorking() {
        val source = PdfDocument.parse(formDocument())
        val values = mapOf(
            "name" to FieldValue.Text("Иван Петров"),
            "agree" to FieldValue.Check("Yes"),
            "choice" to FieldValue.Check("B"),
            "city" to FieldValue.Choice(listOf("Казань")),
        )
        val kept = pages(source).take(1)
        loadCleanly(write(source, DocumentEdit(kept, fields = values))) { doc ->
            val form = assertNotNull(doc.documentCatalog.acroForm)
            assertEquals(listOf("name", "agree", "choice", "city"), form.fields.map { it.partialName })
            assertEquals("Иван Петров", form.getField("name").valueAsString)
            assertTrue((form.getField("agree") as PDCheckBox).isChecked)
            assertEquals("B", (form.getField("choice") as PDRadioButton).value)
            assertEquals(listOf("Казань"), (form.getField("city") as PDComboBox).value)
            val widget = form.getField("name").widgets.single()
            assertEquals(doc.getPage(0).cosObject, widget.page.cosObject)
            assertNotNull(widget.normalAppearanceStream)
            assertEquals(0x000000, pixel(doc, 0, 58.0, 658.0))
        }
    }

    @Test
    fun duplicatedFormPagesGetTheirOwnWidgets() {
        val source = PdfDocument.parse(formDocument())
        val first = pages(source).first()
        val edit = DocumentEdit(listOf(first, first.copy(id = id())), fields = mapOf("name" to FieldValue.Text("Иван Петров")))
        loadCleanly(write(source, edit)) { doc ->
            val one = doc.getPage(0).annotations
            val two = doc.getPage(1).annotations
            assertEquals(5, two.size)
            for ((a, b) in one.zip(two)) assertNotSame(a.cosObject, b.cosObject)
            for (i in 0..1) {
                for (annotation in doc.getPage(i).annotations) assertSame(doc.getPage(i).cosObject, annotation.cosObject.getCOSDictionary(COSName.P))
            }
            val name = assertNotNull(doc.documentCatalog.acroForm).getField("name")
            assertEquals("Иван Петров", name.valueAsString)
            assertEquals(listOf(doc.getPage(0).cosObject), name.widgets.map { it.page.cosObject })
            assertNotNull(two[0].normalAppearanceStream)
        }
    }

    @Test
    fun flattenedFormBecomesText() {
        val source = PdfDocument.parse(formDocument())
        val edit = DocumentEdit(pages(source), fields = mapOf("name" to FieldValue.Text("Мария")))
        loadCleanly(write(source, edit, SaveOptions(flattenForm = true))) { doc ->
            assertNull(doc.documentCatalog.acroForm)
            assertTrue(doc.getPage(0).annotations.isEmpty())
            assertEquals("Мария", normalize(pdfboxText(doc, 0)))
        }
    }

    @Test
    fun flatteningKeepsNotesThatHaveNothingToPaint() {
        val bytes = pdf { doc ->
            val page = doc.textPage(helvetica(), listOf("Annotated"))
            val note = PDAnnotationText().apply {
                rectangle = PDRectangle(300f, 700f, 20f, 20f)
                contents = "Remember this"
            }
            val square = PDAnnotationSquare().apply {
                rectangle = PDRectangle(100f, 100f, 100f, 100f)
                color = PDColor(floatArrayOf(1f, 0f, 0f), PDDeviceRGB.INSTANCE)
                constructAppearances(doc)
            }
            page.annotations = listOf(note, square)
        }
        val source = PdfDocument.parse(bytes)
        loadCleanly(write(source, DocumentEdit(pages(source)), SaveOptions(flattenAnnotations = true))) { doc ->
            val kept = doc.getPage(0).annotations.single()
            assertEquals("Text", kept.subtype)
            assertEquals("Remember this", kept.contents)
        }
    }

    @Test
    fun flatteningDrawsValuesFilledElsewhere() {
        val bytes = Loader.loadPDF(formDocument()).use { doc ->
            val form = doc.documentCatalog.acroForm
            (form.getField("name") as PDTextField).value = "Stale"
            form.getField("name").cosObject.setString(COSName.V, "Fresh")
            form.getField("other").cosObject.setString(COSName.V, "No appearance")
            form.setNeedAppearances(true)
            save(doc, SaveMode.CLASSIC)
        }
        val source = PdfDocument.parse(bytes)
        loadCleanly(write(source, DocumentEdit(pages(source)), SaveOptions(flattenForm = true))) { doc ->
            assertEquals("Fresh", normalize(pdfboxText(doc, 0)))
            assertEquals("No appearance", normalize(pdfboxText(doc, 1)))
        }
    }

    private fun outlined() = pdf { doc ->
        doc.textPage(helvetica(), listOf("First"))
        doc.textPage(helvetica(), listOf("Second"))
        doc.textPage(helvetica(), listOf("Third"))
        val outline = PDDocumentOutline()
        doc.documentCatalog.documentOutline = outline
        for ((i, title) in listOf("Глава 1", "Глава 2", "Глава 3").withIndex()) {
            val item = PDOutlineItem()
            item.title = title
            item.destination = PDPageFitDestination().apply { page = doc.getPage(i) }
            outline.addLast(item)
        }
    }

    @Test
    fun outlineFollowsThePages() {
        val source = PdfDocument.parse(outlined())
        val pages = pages(source)
        val outline = PdfOutline.read(source) { pages.getOrNull(it)?.id }
        assertEquals(listOf("Глава 1", "Глава 2", "Глава 3"), outline.map { it.title })
        assertEquals(pages.map { it.id }, outline.map { it.pageId })
        val edit = DocumentEdit(listOf(pages[2], pages[0]), outline = outline)
        loadCleanly(write(source, edit)) { doc ->
            val items = doc.documentCatalog.documentOutline.children().toList()
            assertEquals(listOf("Глава 1", "Глава 2", "Глава 3"), items.map { it.title })
            fun pageOf(item: PDOutlineItem) = (item.destination as? PDPageDestination)?.page?.let { doc.pages.indexOf(it) }
            assertEquals(listOf(1, null, 0), items.map { pageOf(it) })
        }
    }

    @Test
    fun unlockedCopyKeepsBookmarksAndTitle() {
        val bytes = Loader.loadPDF(outlined()).use { doc ->
            doc.documentInformation.title = "Locked"
            doc.protect(StandardProtectionPolicy("owner", "", AccessPermission()).apply { encryptionKeyLength = 128 })
            save(doc, SaveMode.CLASSIC)
        }
        val source = PdfDocument.parse(bytes)
        assertTrue(source.encrypted)
        loadCleanly(write(source, startingEdit(source))) { doc ->
            assertTrue(!doc.isEncrypted)
            assertEquals("Locked", doc.documentInformation.title)
            assertEquals(listOf("Глава 1", "Глава 2", "Глава 3"), doc.documentCatalog.documentOutline.children().map { it.title })
        }
    }

    @Test
    fun passwordProtectsTheFile() {
        val source = PdfDocument.parse(threePages())
        val bytes = write(source, DocumentEdit(pages(source), security = Security("secret", "owner", allowPrint = false, allowCopy = false)))
        assertFailsWith<InvalidPasswordException> { Loader.loadPDF(bytes).close() }
        Loader.loadPDF(bytes, "secret").use { doc ->
            assertTrue(doc.isEncrypted)
            assertEquals("Page two", normalize(pdfboxText(doc, 1)))
            assertTrue(!doc.currentAccessPermission.canPrint())
            assertTrue(!doc.currentAccessPermission.canExtractContent())
            assertTrue(doc.currentAccessPermission.canModify())
        }
        Loader.loadPDF(bytes, "owner").use { assertTrue(it.currentAccessPermission.isOwnerPermission) }
        assertEquals("Page three", PdfText.extract(PdfDocument.parse(bytes, "secret"), 2))
    }

    @Test
    fun watermarkAndNumbersAreText() {
        val source = PdfDocument.parse(pdf { doc -> repeat(3) { doc.textPage(doc.arial(), listOf("Текст")) } })
        val edit = DocumentEdit(pages(source), numbering = PageNumbering("{n} / {total}"), watermark = Watermark("ЧЕРНОВИК"))
        loadCleanly(write(source, edit)) { doc ->
            for (i in 0 until 3) {
                val text = normalize(pdfboxText(doc, i))
                assertTrue("${i + 1} / 3" in text, text)
                assertTrue("ЧЕРНОВИК" in squeeze(text), text)
            }
        }
    }

    @Test
    fun imagesBecomePagesAndMarks() {
        val red = ByteArray(4 * 4 * 3) { if (it % 3 == 0) 0xFF.toByte() else 0 }
        val half = ByteArray(4 * 4) { 0x80.toByte() }
        val image = MarkImage(4, 4, rgb = red)
        val translucent = MarkImage(4, 4, rgb = red, alpha = half)
        val pages = listOf(
            EditPage(id(), ImagePage(image, 200.0, 200.0)),
            EditPage(id(), BlankPage(200.0, 200.0), marks = listOf(ImageMark(id(), PdfRect(50.0, 50.0, 150.0, 150.0), translucent))),
        )
        loadCleanly(write(null, DocumentEdit(pages))) { doc ->
            assertEquals(0xFF0000, pixel(doc, 0, 100.0, 100.0))
            val blended = pixel(doc, 1, 100.0, 100.0)
            assertTrue(blended shr 16 == 0xFF && (blended shr 8 and 0xFF) in 0x70..0x90, Integer.toHexString(blended))
        }
    }

    @Test
    fun rotatedPagesGetUprightMarks() {
        val source = PdfDocument.parse(pdf { doc -> doc.textPage(helvetica(), listOf("Turned"), rotation = 90) })
        val page = pages(source).single()
        val frame = page.frame
        val box = frame.toUser(100.0, 100.0, 300.0, 140.0)
        val mark = TextMark(id(), box, "Upright", 16f, 0xFF000000.toInt(), angle = page.rotation)
        loadCleanly(write(source, DocumentEdit(listOf(page.copy(marks = listOf(mark)))))) { doc ->
            assertTrue("Upright" in normalize(pdfboxText(doc, 0)))
        }
    }

    @Test
    fun theDocumentsOwnContentIsTakenOutAndMoved() {
        val bytes = pdf { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val picture = LosslessFactory.createFromImage(doc, BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB))
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(picture, 100f, 400f, 60f, 60f)
                cs.beginText()
                cs.setFont(helvetica(), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("Stays")
                cs.newLineAtOffset(0f, -30f)
                cs.showText("Goes away")
                cs.endText()
            }
        }
        val source = PdfDocument.parse(bytes)
        val content = PageContent.of(source.page(0))
        val blocks = TextBlocks.build(content.runs)
        val gone = blocks.single { it.text == "Goes away" }
        val image = content.graphics.single()
        val objects = gone.ops.associateWith { ObjectEdit(removed = true) } + (image.first to ObjectEdit(transform = Affine(1.0, 0.0, 0.0, 1.0, 200.0, 0.0)))
        val page = pages(source).single().copy(objects = objects)
        val out = write(source, DocumentEdit(listOf(page)))
        loadCleanly(out) { doc -> assertEquals("Stays", normalize(pdfboxText(doc, 0))) }
        val moved = PageContent.of(PdfDocument.parse(out).page(0)).graphics.single()
        assertEquals(image.bounds.left + 200, moved.bounds.left, 1e-6)
        assertEquals(image.bounds.bottom, moved.bounds.bottom, 1e-6)
    }

    @Test
    fun movedTextIsReadWhereItWent() {
        val bytes = pdf { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(helvetica(), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("Moves down")
                cs.endText()
            }
        }
        val source = PdfDocument.parse(bytes)
        val run = PageContent.of(source.page(0)).runs.single()
        val page = pages(source).single().copy(objects = mapOf(run.op to ObjectEdit(transform = Affine(1.0, 0.0, 0.0, 1.0, 20.0, -300.0))))
        val glyphs = PdfText.glyphs(PdfDocument.parse(write(null, DocumentEdit(listOf(page)))), 0)
        assertEquals("Moves down", PageText(glyphs).text)
        assertEquals(70.0, glyphs.first().x0, 1e-6)
        assertEquals(400.0, glyphs.first().y0, 1e-6)
    }

    @Test
    fun styledRunsAreWrittenWithTheirWeightAndColour() {
        val black = 0xFF000000.toInt()
        val red = 0xFFFF0000.toInt()
        val spans = listOf(StyleSpan(0, 5, true, false, black), StyleSpan(11, 14, false, false, red))
        val mark = TextMark(id(), PdfRect(100.0, 500.0, 400.0, 540.0), "Bold plain red", 14f, black, spans = spans)
        val page = EditPage(id(), BlankPage(595.0, 842.0), marks = listOf(mark))
        val glyphs = PdfText.glyphs(PdfDocument.parse(write(null, DocumentEdit(listOf(page)))), 0)
        assertEquals("Boldplainred", glyphs.joinToString("") { it.text })
        val looks = List(4) { Look(true, false, black) } + List(5) { Look(false, false, black) } + List(3) { Look(false, false, red) }
        assertEquals(looks, glyphs.map { Look(it.style.bold, it.style.italic, it.color) })
    }

    @Test
    fun missingCharactersCopyAsTheQuestionMarkTheyShowAs() {
        val mark = TextMark(id(), PdfRect(50.0, 700.0, 500.0, 730.0), "Готово ✅ ок?", 14f, 0xFF000000.toInt())
        val bytes = write(null, DocumentEdit(listOf(EditPage(id(), BlankPage(595.0, 842.0), marks = listOf(mark)))))
        assertEquals("Готово ? ок?", normalize(pdfboxText(bytes, 0)))
    }

    @Test
    fun textBoxesTakeTheHeightOfTheirLinesAtAnyAngle() {
        val renderer = MarkRenderer(fonts)
        val black = 0xFF000000.toInt()
        val start = PdfRect(100.0, 500.0, 300.0, 700.0)
        for (angle in listOf(0, 90, 180, 270)) {
            val small = renderer.fitted(TextMark(id(), start, "one\ntwo\nthree", 12f, black, angle = angle))
            val large = renderer.fitted(small.restyled(TextLook(black, 16f, MarkFont.SANS, bold = false, italic = false, MarkAlign.START)))
            for (mark in listOf(small, large)) assertEquals(renderer.layout(mark).height.toDouble(), Affine.frameSize(mark.box, angle).second, 1e-9)
            assertTrue(Affine.frameSize(large.box, angle).second > Affine.frameSize(small.box, angle).second)
            val top = { box: PdfRect ->
                when (angle) {
                    90 -> box.left
                    180 -> box.bottom
                    270 -> box.right
                    else -> box.top
                }
            }
            assertEquals(top(start), top(large.box), 1e-9)
        }
    }

    @Test
    fun glyphOutlinesStayInsideTheBox() {
        val sans = font("MobitoolSans-Regular")
        for (ch in "AЖ€") {
            val contours = sans.outline(sans.glyphId(ch.code))
            assertTrue(contours.isNotEmpty(), "$ch")
            for (c in contours) {
                assertTrue(c.x.all { it in sans.bboxXMin.toFloat()..sans.bboxXMax.toFloat() })
                assertTrue(c.y.all { it in sans.bboxYMin.toFloat()..sans.bboxYMax.toFloat() })
            }
        }
    }
}
