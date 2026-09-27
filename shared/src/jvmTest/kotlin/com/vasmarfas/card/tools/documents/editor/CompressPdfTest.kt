package com.vasmarfas.card.tools.documents.editor

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import com.vasmarfas.card.tools.documents.pdf.PdfDocument
import com.vasmarfas.card.tools.documents.pdf.helvetica
import com.vasmarfas.card.tools.documents.pdf.loadCleanly
import com.vasmarfas.card.tools.documents.pdf.normalize
import com.vasmarfas.card.tools.documents.pdf.pdf
import com.vasmarfas.card.tools.documents.pdf.pdfboxText
import com.vasmarfas.card.tools.documents.pdf.textPage
import java.awt.image.BufferedImage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject

@OptIn(InternalComposeUiApi::class)
class CompressPdfTest {
    init {
        registerSkikoComposeImplementation()
    }

    private fun scan(width: Int, height: Int): BufferedImage {
        val random = Random(7)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val noise = random.nextInt(24)
                image.setRGB(x, y, (minOf(255, x * 230 / width + noise) shl 16) or (minOf(255, y * 230 / height + noise) shl 8) or 128)
            }
        }
        return image
    }

    @Test
    fun largePicturesBecomeJpegAndTextStays() = runBlocking {
        val source = pdf { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val picture = LosslessFactory.createFromImage(doc, scan(2000, 1400))
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(picture, 50f, 300f, 495f, 346f)
                cs.beginText()
                cs.setFont(helvetica(), 14f)
                cs.newLineAtOffset(50f, 760f)
                cs.showText("Invoice 42")
                cs.endText()
            }
            doc.textPage(helvetica(), listOf("Second page"))
        }
        val compressed = compressPdf(PdfDocument.parse(source), ImageQuality.MEDIUM) {}
        assertTrue(compressed.size < source.size / 2, "${source.size} -> ${compressed.size}")
        loadCleanly(compressed) { doc ->
            assertEquals(2, doc.numberOfPages)
            val resources = doc.getPage(0).resources
            val image = resources.xObjectNames.map { resources.getXObject(it) }.single() as PDImageXObject
            assertEquals(1600, image.width)
            assertEquals("jpg", image.suffix)
        }
        assertEquals("Invoice 42", normalize(pdfboxText(compressed, 0)))
        assertEquals("Second page", normalize(pdfboxText(compressed, 1)))
    }

    @Test
    fun bigPicturesAreAveragedDownAndHugeOnesLeftAlone() = runBlocking {
        val source = pdf { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val photo = LosslessFactory.createFromImage(doc, scan(2400, 1700))
            val poster = LosslessFactory.createFromImage(doc, BufferedImage(7000, 6000, BufferedImage.TYPE_BYTE_GRAY))
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(photo, 50f, 400f, 495f, 350f)
                cs.drawImage(poster, 50f, 50f, 300f, 257f)
            }
        }
        val compressed = compressPdf(PdfDocument.parse(source), ImageQuality.LOW) {}
        loadCleanly(compressed) { doc ->
            val resources = doc.getPage(0).resources
            val images = resources.xObjectNames.map { resources.getXObject(it) as PDImageXObject }.sortedBy { it.width }
            assertEquals(listOf(1100, 7000), images.map { it.width })
            assertEquals(listOf("jpg", "png"), images.map { it.suffix })
        }
    }

    @Test
    fun filesWithoutPicturesComeOutWhole() = runBlocking {
        val source = pdf { doc -> doc.textPage(helvetica(), listOf("Only text")) }
        val compressed = compressPdf(PdfDocument.parse(source), ImageQuality.LOW) {}
        loadCleanly(compressed) { assertEquals(1, it.numberOfPages) }
        assertEquals("Only text", normalize(pdfboxText(compressed, 0)))
    }
}
