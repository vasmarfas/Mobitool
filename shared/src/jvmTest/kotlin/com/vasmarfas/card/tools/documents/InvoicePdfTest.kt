package com.vasmarfas.card.tools.documents

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.vasmarfas.card.tools.documents.pdf.TrueTypeFont
import kotlinx.datetime.LocalDate
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvoicePdfTest {
    private fun font(name: String) = TrueTypeFont(File("src/commonMain/composeResources/files/fonts/$name.ttf").readBytes())

    private val sans = FontFamily(font("MobitoolSans-Regular"), font("MobitoolSans-Bold"), font("MobitoolSans-Italic"), font("MobitoolSans-BoldItalic"))
    private val fonts = DocFonts(sans, sans, FontFamily(font("MobitoolMono-Regular"), font("MobitoolMono-Bold")))

    private val invoice = Invoice(
        "12",
        LocalDate(2026, 11, 4),
        Payee("Иванов Иван Иванович", "123456789047", "40817810400000000000", "ПАО Банк", "044525225", "30101810400000000225"),
        selfEmployed = true,
        buyer = "ООО Покупатель",
        buyerInn = "7707083893",
        lines = listOf(InvoiceLine("Разработка приложения", 2.0, "ч", 150_000)),
    )

    private fun read(image: PreparedImage): BufferedImage {
        val source = ImageIO.read(ByteArrayInputStream(image.jpeg))
        val flat = BufferedImage(source.width + 64, source.height + 64, BufferedImage.TYPE_INT_RGB)
        val graphics = flat.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, flat.width, flat.height)
        graphics.drawImage(source, 32, 32, null)
        graphics.dispose()
        return flat
    }

    @Test
    fun theQrImageReadsBackAsThePayload() {
        val payload = InvoiceDoc.qrPayload(invoice)
        val image = read(invoiceQr(payload))
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))
        val decoded = QRCodeReader().decode(bitmap, mapOf(DecodeHintType.CHARACTER_SET to "UTF-8"))
        assertEquals(payload, decoded.text)
    }

    @Test
    fun bothDocumentsCarryCyrillicAndTheAmountInWords() {
        val qr = Block.Picture(ByteArray(0), 113f, 113f)
        val bytes = PdfTypesetter.typeset(
            InvoiceDoc.build(invoice, InvoiceKind.BOTH, qr),
            fonts,
            mapOf(qr to invoiceQr(InvoiceDoc.qrPayload(invoice))),
            TypesetOptions(fontSize = 10f, pageNumbers = false),
        )
        Loader.loadPDF(bytes).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            assertTrue("Счёт на оплату № 12 от 4 ноября 2026 г." in text, text)
            assertTrue("Акт оказанных услуг № 12" in text, text)
            assertTrue("Три тысячи рублей 00 копеек" in text, text)
        }
    }
}
