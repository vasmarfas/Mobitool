package com.vasmarfas.card.tools.documents

import com.vasmarfas.card.core.TextDecoding
import com.vasmarfas.card.core.XmlElement
import com.vasmarfas.card.resources.*

private val CONTENT_TYPES = setOf("application/xhtml+xml", "text/html", "application/xml", "text/x-oeb1-document")

internal class EpubReader(private val pkg: Package) {
    private class Item(val href: String, val type: String, val fallback: String?)

    fun read(): Doc {
        val opfPath = pkg.optionalXml("META-INF/container.xml")
            ?.descendants("rootfile")
            ?.firstOrNull { it.attr("media-type") == null || it.attr("media-type") == "application/oebps-package+xml" }
            ?.attr("full-path")
            ?: pkg.names.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
            ?: throw DocumentFormatException("Not an EPUB: no package document")
        checkEncryption()
        val opf = pkg.xml(opfPath) ?: throw DocumentFormatException("Not an EPUB: $opfPath is missing")
        val base = parentDir(opfPath)
        val metadata = opf.child("metadata")
        val manifest = HashMap<String, Item>()
        opf.child("manifest")?.children("item")?.forEach { item ->
            val id = item.attr("id") ?: return@forEach
            val href = item.attr("href") ?: return@forEach
            manifest[id] = Item(resolvePath(base, percentDecode(href)), item.attr("media-type").orEmpty(), item.attr("fallback"))
        }
        val blocks = ArrayList<Block>()
        for (ref in opf.child("spine")?.children("itemref").orEmpty()) {
            val item = content(manifest, ref.attr("idref")) ?: continue
            val chapter = chapter(item) ?: continue
            if (chapter.isEmpty()) continue
            if (blocks.isNotEmpty()) blocks.add(Block.PageBreak)
            blocks.addAll(chapter)
        }
        return Doc(
            blocks = blocks,
            title = metadata?.child("title")?.text()?.collapseSpaces()?.takeIf { it.isNotEmpty() },
            author = metadata?.children("creator")?.map { it.text().collapseSpaces() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.joinToString(", "),
            language = metadata?.child("language")?.text()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun content(manifest: Map<String, Item>, id: String?): Item? {
        var item = manifest[id ?: return null] ?: return null
        repeat(8) {
            if (item.type in CONTENT_TYPES || item.type.startsWith("image/")) return item
            item = manifest[item.fallback ?: return null] ?: return null
        }
        return null
    }

    private fun chapter(item: Item): List<Block>? {
        val bytes = pkg.optionalBytes(item.href) ?: return null
        if (item.type.startsWith("image/")) return listOf(Block.Picture(bytes, alt = ""))
        val dir = parentDir(item.href)
        val reader = HtmlReader { src -> pkg.optionalBytes(resolvePath(dir, percentDecode(src))) }
        return reader.read(TextDecoding.decode(bytes)).blocks
    }

    // font obfuscation is harmless, anything else in encryption.xml is DRM and the chapters would be noise
    private fun checkEncryption() {
        val encryption = pkg.optionalXml("META-INF/encryption.xml") ?: return
        val drm = encryption.descendants("EncryptionMethod").any {
            val algorithm = it.attr("Algorithm").orEmpty()
            algorithm != "http://www.idpf.org/2008/embedding" && algorithm != "http://ns.adobe.com/pdf/enc#RC"
        }
        val onlyFonts = encryption.descendants("CipherReference").all {
            val uri = it.attr("URI").orEmpty().lowercase()
            uri.endsWith(".ttf") || uri.endsWith(".otf") || uri.endsWith(".woff") || uri.endsWith(".woff2")
        }
        if (drm && !onlyFonts) throw DocumentFormatException("The EPUB is protected with DRM", Res.string.document_drm)
    }
}

// iterative, a deep tree would overflow the stack
internal fun XmlElement.descendants(localName: String): List<XmlElement> {
    val result = ArrayList<XmlElement>()
    val stack = ArrayList<XmlElement>()
    stack.add(this)
    while (stack.isNotEmpty()) {
        val el = stack.removeAt(stack.lastIndex)
        if (el !== this && el.localName == localName) result.add(el)
        val children = el.elements
        for (i in children.lastIndex downTo 0) stack.add(children[i])
    }
    return result
}
