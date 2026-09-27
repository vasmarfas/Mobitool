package com.vasmarfas.card.tools.documents

import com.vasmarfas.card.core.ZipWriter
import com.vasmarfas.card.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// laid out the way Calibre does it: OPF outside OEBPS, CSS classes for emphasis, images one level up
class EpubReaderTest {
    private val png = TestImages.png(5, 5)

    private fun xhtml(body: String) = """<?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>глава</title><link rel="stylesheet" type="text/css" href="../css/style.css"/></head>
        <body>$body</body></html>"""

    private fun epub(encryption: String? = null): ByteArray {
        val opf = """<?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
              <dc:title>Книга EPUB</dc:title><dc:creator opf:role="aut">Первый</dc:creator><dc:creator>Второй</dc:creator><dc:language>ru</dc:language>
            </metadata>
            <manifest>
              <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
              <item id="css" href="css/style.css" media-type="text/css"/>
              <item id="c1" href="text/ch%201.xhtml" media-type="application/xhtml+xml"/>
              <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
              <item id="dtb" href="text/ch3.xml" media-type="application/x-dtbook+xml" fallback="c3"/>
              <item id="c3" href="text/ch3.xhtml" media-type="application/xhtml+xml"/>
              <item id="img" href="images/my%20pic.png" media-type="image/png"/>
            </manifest>
            <spine toc="ncx"><itemref idref="c1"/><itemref idref="c2" linear="no"/><itemref idref="dtb"/><itemref idref="missing"/></spine>
            </package>"""
        val zip = ZipWriter()
        zip.add("mimetype", "application/epub+zip".encodeToByteArray(), compress = false)
        zip.add(
            "META-INF/container.xml",
            "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"content/book.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>".encodeToByteArray(),
        )
        if (encryption != null) zip.add("META-INF/encryption.xml", encryption.encodeToByteArray())
        zip.add("content/book.opf", opf.encodeToByteArray())
        zip.add("content/css/style.css", ".bold { font-weight: bold } .center { text-align: center }".encodeToByteArray())
        zip.add(
            "content/text/ch 1.xhtml",
            xhtml("<h1 class=\"center\">Глава 1</h1><p class=\"bold\">жирный абзац</p><p><img src=\"../images/my%20pic.png\" alt=\"рис\"/></p><p><a href=\"ch2.xhtml#x\">внутренняя</a> <a href=\"https://example.com\">внешняя</a></p>").encodeToByteArray(),
        )
        zip.add("content/text/ch2.xhtml", xhtml("<p>вне линейного порядка</p>").encodeToByteArray())
        zip.add("content/text/ch3.xhtml", xhtml("<h2>Запасная глава</h2>").encodeToByteArray())
        zip.add("content/images/my pic.png", png)
        return zip.toByteArray()
    }

    @Test
    fun readsSpineInOrder() {
        val bytes = epub()
        assertEquals(DocFormat.EPUB, Documents.detect(bytes, "book.zip"))
        val doc = Documents.read(bytes, DocFormat.EPUB)
        assertEquals("Книга EPUB", doc.title)
        assertEquals("Первый, Второй", doc.author)
        assertEquals("ru", doc.language)
        val sha = dumpBlocks(listOf(Block.Picture(png))).removePrefix("PICTURE ").substringBefore(" size")
        assertEquals(
            """
            H1 "Глава 1"
            P {b}"жирный абзац"
            PICTURE $sha size=0x0 alt="рис"
            P "внутренняя " {link=https://example.com}"внешняя"
            PAGEBREAK
            P "вне линейного порядка"
            PAGEBREAK
            H2 "Запасная глава"

            """.trimIndent(),
            dumpBlocks(doc.blocks),
        )
    }

    @Test
    fun fontObfuscationIsFineButDrmIsNot() {
        val fonts = "<encryption xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" xmlns:enc=\"http://www.w3.org/2001/04/xmlenc#\">" +
            "<enc:EncryptedData><enc:EncryptionMethod Algorithm=\"http://www.idpf.org/2008/embedding\"/><enc:CipherData><enc:CipherReference URI=\"fonts/a.ttf\"/></enc:CipherData></enc:EncryptedData></encryption>"
        Documents.read(epub(fonts), DocFormat.EPUB)
        val drm = "<encryption xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" xmlns:enc=\"http://www.w3.org/2001/04/xmlenc#\">" +
            "<enc:EncryptedData><enc:EncryptionMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#aes128-cbc\"/><enc:CipherData><enc:CipherReference URI=\"content/text/ch2.xhtml\"/></enc:CipherData></enc:EncryptedData></encryption>"
        assertEquals(Res.string.document_drm, assertFailsWith<DocumentFormatException> { Documents.read(epub(drm), DocFormat.EPUB) }.reason)
    }

    @Test
    fun missingPackageDocument() {
        val zip = ZipWriter().add("mimetype", "application/epub+zip".encodeToByteArray(), compress = false).toByteArray()
        assertEquals(Res.string.document_damaged, assertFailsWith<DocumentFormatException> { Documents.read(zip, DocFormat.EPUB) }.reason)
    }
}
