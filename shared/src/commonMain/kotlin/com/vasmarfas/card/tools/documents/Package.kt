package com.vasmarfas.card.tools.documents

import com.vasmarfas.card.core.TextDecoding
import com.vasmarfas.card.core.XmlElement
import com.vasmarfas.card.core.XmlException
import com.vasmarfas.card.core.ZipArchive
import com.vasmarfas.card.core.ZipEntryInfo
import com.vasmarfas.card.core.ZipException
import com.vasmarfas.card.core.parseXml
import com.vasmarfas.card.resources.*
import kotlin.io.encoding.Base64

private const val MAX_UNPACKED = 200L * 1024 * 1024

// every read is charged against MAX_UNPACKED by the size the central directory declares, ZipArchive
// enforces it, so a zip bomb fails before anything large is allocated
internal class Package(bytes: ByteArray) {
    private val zip = try {
        ZipArchive(bytes)
    } catch (e: ZipException) {
        throw DocumentFormatException("Not a ZIP package: ${e.message}")
    }
    private val exact = HashMap<String, ZipEntryInfo>()
    private val folded = HashMap<String, ZipEntryInfo>()
    private val cache = HashMap<ZipEntryInfo, ByteArray>()
    private var unpacked = 0L

    init {
        for (entry in zip.entries) {
            if (entry.isDirectory) continue
            exact.getOrPut(entry.name) { entry }
            folded.getOrPut(entry.name.lowercase()) { entry }
        }
    }

    val names: List<String> get() = zip.entries.filter { !it.isDirectory }.map { it.name }

    operator fun contains(name: String): Boolean = find(name) != null

    // OPC part names are case-insensitive and may start with a slash
    fun read(name: String): ByteArray? {
        val entry = find(name) ?: return null
        cache[entry]?.let { return it }
        unpacked += entry.size
        if (unpacked > MAX_UNPACKED) throw DocumentFormatException("The package unpacks to more than ${MAX_UNPACKED / 1024 / 1024} MB", Res.string.document_too_large)
        return zip.read(entry).also { cache[entry] = it }
    }

    fun xml(name: String): XmlElement? = read(name)?.let { parseXml(TextDecoding.decode(it)) }

    fun optionalXml(name: String): XmlElement? = try {
        xml(name)
    } catch (e: XmlException) {
        null
    } catch (e: ZipException) {
        null
    }

    fun optionalBytes(name: String): ByteArray? = try {
        read(name)
    } catch (e: ZipException) {
        null
    }

    private fun find(name: String): ZipEntryInfo? {
        val key = name.removePrefix("/")
        return exact[key] ?: folded[key.lowercase()]
    }
}

internal fun parentDir(path: String): String = path.substring(0, path.lastIndexOf('/') + 1)

internal fun resolvePath(base: String, href: String): String {
    val clean = href.substringBefore('#').substringBefore('?')
    val segments = ArrayList<String>()
    if (!clean.startsWith('/')) segments.addAll(base.split('/').filter { it.isNotEmpty() })
    for (part in clean.split('/')) {
        when (part) {
            "", "." -> {}
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments.add(part)
        }
    }
    return segments.joinToString("/")
}

internal fun percentDecode(value: String): String = if ('%' in value) percentDecodeBytes(value).decodeToString() else value

internal fun percentDecodeBytes(value: String): ByteArray {
    val bytes = value.encodeToByteArray()
    val out = ByteArray(bytes.size)
    var n = 0
    var i = 0
    while (i < bytes.size) {
        if (bytes[i] == PERCENT && i + 2 < bytes.size) {
            val hi = (bytes[i + 1].toInt().toChar()).digitToIntOrNull(16)
            val lo = (bytes[i + 2].toInt().toChar()).digitToIntOrNull(16)
            if (hi != null && lo != null) {
                out[n++] = ((hi shl 4) or lo).toByte()
                i += 3
                continue
            }
        }
        out[n++] = bytes[i++]
    }
    return out.copyOf(n)
}

private const val PERCENT = '%'.code.toByte()

private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*:.*")

internal fun externalLink(href: String?): String? {
    val link = href?.trim()?.filter { it >= ' ' } ?: return null
    if (link.startsWith("//")) return "https:$link"
    if (!SCHEME.matches(link)) return null
    val scheme = link.substringBefore(':').lowercase()
    if (scheme == "javascript" || scheme == "vbscript" || scheme == "data" || scheme == "about") return null
    return link
}

// percent-encodes what RFC 3986 forbids but keeps non-ASCII, IRIs allow it
internal fun uriSafe(link: String): String = buildString {
    for (c in link) {
        if (c <= ' ' || c in "\"<>\\^`{|}" || c == '\u007F') {
            for (b in c.toString().encodeToByteArray()) {
                val v = b.toInt() and 0xFF
                append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
            }
        } else {
            append(c)
        }
    }
}

private const val HEX = "0123456789ABCDEF"

private val LENIENT_BASE64 = Base64.Mime.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

internal fun decodeBase64(text: String): ByteArray? = try {
    LENIENT_BASE64.decode(text.filter { !it.isWhitespace() })
} catch (e: IllegalArgumentException) {
    null
}

internal fun encodeBase64(bytes: ByteArray): String = Base64.Default.encode(bytes)

internal fun decodeDataUri(uri: String): ByteArray? {
    val comma = uri.indexOf(',')
    if (!uri.startsWith("data:", ignoreCase = true) || comma < 0) return null
    val header = uri.substring(5, comma)
    val payload = uri.substring(comma + 1)
    return if (header.endsWith(";base64", ignoreCase = true)) decodeBase64(percentDecode(payload)) else percentDecodeBytes(payload)
}

internal fun lengthPt(value: String?, defaultUnit: String = "px"): Float? {
    val v = value?.trim()?.lowercase() ?: return null
    var end = 0
    while (end < v.length && (v[end].isDigit() || v[end] == '.' || (end == 0 && (v[end] == '-' || v[end] == '+')))) end++
    val number = v.substring(0, end).toFloatOrNull() ?: return null
    val factor = when (v.substring(end).trim().ifEmpty { defaultUnit }) {
        "pt" -> 1f
        "px" -> 0.75f
        "in" -> 72f
        "cm" -> 72f / 2.54f
        "mm" -> 72f / 25.4f
        "pc" -> 12f
        "q" -> 72f / 101.6f
        else -> return null
    }
    return (number * factor).takeIf { it > 0f && it.isFinite() }
}

internal fun pictureSize(bytes: ByteArray, widthPt: Float?, heightPt: Float?): Pair<Float, Float> {
    val w = widthPt ?: 0f
    val h = heightPt ?: 0f
    if (w > 0f && h > 0f) return w to h
    if (w <= 0f && h <= 0f) return 0f to 0f
    val px = ImageHeader.size(bytes) ?: return 0f to 0f
    return if (w > 0f) w to w * px.second / px.first else h * px.first / px.second to h
}

internal fun blocksText(blocks: List<Block>, limit: Int = Int.MAX_VALUE): String = buildString {
    fun visit(list: List<Block>) {
        for (b in list) {
            if (length >= limit) return
            when (b) {
                is Block.Heading -> append(b.content.plainText()).append('\n')
                is Block.Paragraph -> append(b.content.plainText()).append('\n')
                is Block.ListBlock -> b.items.forEach(::visit)
                is Block.Quote -> visit(b.blocks)
                is Block.Code -> append(b.text).append('\n')
                is Block.Table -> b.rows.forEach { row -> row.forEach { visit(it.blocks) } }
                is Block.Picture -> append(b.alt).append('\n')
                Block.Rule, Block.PageBreak -> {}
            }
        }
    }
    visit(blocks)
}

internal fun languageOf(doc: Doc): String {
    doc.language?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val text = blocksText(doc.blocks, 20_000)
    var cyrillic = 0
    var latin = 0
    for (c in text) {
        if (c.code in 0x400..0x4FF) cyrillic++ else if (c in 'a'..'z' || c in 'A'..'Z') latin++
    }
    return if (cyrillic > latin) "ru" else "en"
}
