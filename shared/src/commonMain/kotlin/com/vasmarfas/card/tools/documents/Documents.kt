package com.vasmarfas.card.tools.documents

import com.vasmarfas.card.core.TextDecoding
import com.vasmarfas.card.core.ZipArchive
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.developer.hexToBytes
import org.jetbrains.compose.resources.StringResource
import kotlin.math.min

enum class DocFormat(val extension: String, val mimeType: String, val readable: Boolean, val writable: Boolean) {
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", true, true),
    ODT("odt", "application/vnd.oasis.opendocument.text", true, true),
    RTF("rtf", "application/rtf", true, false),
    EPUB("epub", "application/epub+zip", true, true),
    FB2("fb2", "application/x-fictionbook+xml", true, true),
    HTML("html", "text/html", true, true),
    MARKDOWN("md", "text/markdown", true, true),
    TXT("txt", "text/plain", true, true),
}

class DocumentFormatException(message: String, val reason: StringResource = Res.string.document_damaged) : Exception(message)

object Documents {
    fun detect(bytes: ByteArray, fileName: String): DocFormat? {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\').lowercase()
        val byName = formatByName(name)
        if (bytes.isEmpty()) return byName?.takeIf { it == DocFormat.TXT || it == DocFormat.MARKDOWN || it == DocFormat.HTML }
        if (startsWith(bytes, "PK")) return detectZip(bytes)
        if (startsWith(bytes, "{\\rtf")) return DocFormat.RTF
        if (looksBinary(bytes)) return null
        val head = TextDecoding.decode(bytes.copyOf(min(bytes.size, SNIFF_BYTES)))
        when (rootElement(head)) {
            "fictionbook" -> return DocFormat.FB2
            "html" -> return DocFormat.HTML
            null -> {}
            else -> if (head.trimStart(BOM, ' ', '\t', '\r', '\n').startsWith("<?xml")) return null
        }
        if (byName == DocFormat.MARKDOWN || byName == DocFormat.TXT) return byName
        if (looksLikeHtml(head) || byName == DocFormat.HTML) return DocFormat.HTML
        return if (looksLikeMarkdown(head)) DocFormat.MARKDOWN else DocFormat.TXT
    }

    fun read(bytes: ByteArray, format: DocFormat): Doc = guarded {
        when (format) {
            DocFormat.DOCX -> DocxReader(Package(bytes)).read()
            DocFormat.ODT -> OdtReader(Package(bytes)).read()
            DocFormat.RTF -> RtfReader(bytes).read()
            DocFormat.EPUB -> EpubReader(Package(bytes)).read()
            DocFormat.FB2 -> Fb2Reader.read(bytes)
            DocFormat.HTML -> HtmlReader(resource = null).read(HtmlReader.decode(bytes))
            DocFormat.MARKDOWN -> MarkdownReader.read(TextDecoding.decode(bytes))
            DocFormat.TXT -> TxtReader.read(TextDecoding.decode(bytes))
        }
    }

    fun write(doc: Doc, format: DocFormat): ByteArray {
        require(format.writable) { "${format.name} can only be read" }
        return guarded {
            when (format) {
                DocFormat.DOCX -> DocxWriter(doc).write()
                DocFormat.ODT -> OdtWriter(doc).write()
                DocFormat.EPUB -> EpubWriter(doc).write()
                DocFormat.FB2 -> Fb2Writer(doc).write()
                DocFormat.HTML -> HtmlWriter.write(doc).encodeToByteArray()
                DocFormat.MARKDOWN -> MarkdownWriter.write(doc).encodeToByteArray()
                DocFormat.TXT -> TxtWriter.write(doc).encodeToByteArray()
                DocFormat.RTF -> error("unreachable")
            }
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: DocumentFormatException) {
        throw e
    } catch (e: Exception) {
        throw DocumentFormatException(e.message?.takeIf { it.isNotBlank() } ?: "Damaged document (${e::class.simpleName})")
    }

    private fun detectZip(bytes: ByteArray): DocFormat? {
        val zip = try {
            ZipArchive(bytes)
        } catch (e: Exception) {
            return null
        }
        val names = zip.entries.filter { !it.isDirectory }.map { it.name }
        val mimetype = zip.entries.firstOrNull { it.name == "mimetype" && it.size in 1..200 }?.let {
            try {
                zip.read(it).decodeToString().trim()
            } catch (e: Exception) {
                null
            }
        }
        return when {
            mimetype == "application/epub+zip" -> DocFormat.EPUB
            mimetype != null && mimetype.startsWith("application/vnd.oasis.opendocument.text") -> DocFormat.ODT
            mimetype != null && mimetype.startsWith("application/vnd.oasis.opendocument") -> null
            names.any { it.equals("word/document.xml", ignoreCase = true) } || isWordPackage(zip) -> DocFormat.DOCX
            "META-INF/container.xml" in names -> DocFormat.EPUB
            "content.xml" in names && "META-INF/manifest.xml" in names -> DocFormat.ODT
            names.any { it.lowercase().endsWith(".fb2") } -> DocFormat.FB2
            else -> null
        }
    }

    private fun isWordPackage(zip: ZipArchive): Boolean {
        val types = zip.entries.firstOrNull { it.name == "[Content_Types].xml" && it.size < 1_000_000 } ?: return false
        return try {
            zip.read(types).decodeToString().contains("wordprocessingml.document.main+xml")
        } catch (e: Exception) {
            false
        }
    }

    private fun formatByName(name: String): DocFormat? = when (name.substringAfterLast('.', "")) {
        "docx", "docm", "dotx" -> DocFormat.DOCX
        "odt", "ott" -> DocFormat.ODT
        "rtf" -> DocFormat.RTF
        "epub" -> DocFormat.EPUB
        "fb2" -> DocFormat.FB2
        "zip" -> if (name.endsWith(".fb2.zip")) DocFormat.FB2 else null
        "html", "htm", "xhtml", "xht" -> DocFormat.HTML
        "md", "markdown", "mdown", "mkd", "mkdn" -> DocFormat.MARKDOWN
        "txt", "text" -> DocFormat.TXT
        else -> null
    }

    private fun startsWith(bytes: ByteArray, prefix: String): Boolean {
        if (bytes.size < prefix.length) return false
        for (i in prefix.indices) if (bytes[i].toInt() and 0xFF != prefix[i].code) return false
        return true
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = if (bytes.size > 1) bytes[1].toInt() and 0xFF else -1
        if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) return false
        if (BINARY_SIGNATURES.any { sig -> bytes.size >= sig.size && sig.indices.all { bytes[it] == sig[it] } }) return true
        val n = min(bytes.size, SNIFF_BYTES)
        var control = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C && b != 0x1B) control++
        }
        return control * 10 > n
    }

    private fun rootElement(text: String): String? {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == BOM || c.isWhitespace() -> i++
                text.startsWith("<?", i) -> i = text.indexOf("?>", i).let { if (it < 0) return null else it + 2 }
                text.startsWith("<!--", i) -> i = text.indexOf("-->", i).let { if (it < 0) return null else it + 3 }
                text.startsWith("<!", i) -> {
                    if (text.startsWith("<!doctype html", i, ignoreCase = true)) return "html"
                    i = text.indexOf('>', i).let { if (it < 0) return null else it + 1 }
                }
                c == '<' -> {
                    var end = i + 1
                    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == ':' || text[end] == '-' || text[end] == '_')) end++
                    return text.substring(i + 1, end).substringAfterLast(':').lowercase().ifEmpty { null }
                }
                else -> return null
            }
        }
        return null
    }

    private fun looksLikeHtml(text: String): Boolean {
        val lower = text.lowercase()
        if (HTML_MARKERS.any { lower.contains(it) }) return true
        return lower.trimStart().startsWith('<') && HTML_TAGS.count { lower.contains(it) } >= 2
    }

    private fun looksLikeMarkdown(text: String): Boolean {
        var score = 0
        for (line in text.lineSequence().take(200)) {
            val t = line.trimStart()
            when {
                ATX_HEADING.matches(t) || t.startsWith("```") || t.startsWith("~~~") -> return true
                TABLE_DELIMITER.matches(t) -> return true
                MD_LINK.containsMatchIn(t) -> return true
                t.startsWith("> ") || t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> score++
                t.contains("**") || t.contains("__") -> score++
            }
            if (score >= 3) return true
        }
        return false
    }

    private const val SNIFF_BYTES = 4096

    private val BINARY_SIGNATURES = listOf("25504446", "D0CF11E0", "89504E47", "FFD8FF", "47494638", "52617221", "377ABCAF", "1F8B", "7F454C46").map { hexToBytes(it)!! }
    private val HTML_MARKERS = listOf("<html", "<!doctype html", "<head>", "<body", "<meta charset")
    private val HTML_TAGS = listOf("<p>", "<p ", "<div", "<br", "<table", "<span", "<h1", "<h2", "<h3", "<ul>", "<ol>", "<li>", "<a href", "<img")
    private val ATX_HEADING = Regex("#{1,6}\\s+\\S.*")
    private val TABLE_DELIMITER = Regex("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*")
    private val MD_LINK = Regex("\\[[^\\]\\n]+\\]\\([^)\\s]+\\)")
}
