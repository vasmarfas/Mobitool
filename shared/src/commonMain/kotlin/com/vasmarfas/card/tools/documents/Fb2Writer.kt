package com.vasmarfas.card.tools.documents

import com.vasmarfas.card.core.XmlBuilder
import com.vasmarfas.card.tools.developer.Sha256
import com.vasmarfas.card.tools.developer.toHex

private const val FB2_NS = "http://www.gribuser.ru/xml/fictionbook/2.0"
private const val XLINK_NS = "http://www.w3.org/1999/xlink"
private const val FB2_DATE = "2026-01-01"
private val INDENT = Char(0xA0).toString().repeat(2)

// FictionBook 2.1 XSD: a section holds content or subsections but not both, cite takes no images
internal class Fb2Writer(private val doc: Doc) {
    private class Section(val title: Block.Heading?, val level: Int) {
        val content = ArrayList<Block>()
        val children = ArrayList<Section>()
    }

    private class Flow(val cite: Boolean = false) {
        var started = false
        var leadImage = false
    }

    private val binaries = ArrayList<Triple<String, String, ByteArray>>()
    private val xml = XmlBuilder()

    fun write(): ByteArray {
        val root = sections()
        xml.start("FictionBook", "xmlns" to FB2_NS, "xmlns:l" to XLINK_NS)
        description()
        xml.start("body")
        if (root.content.isNotEmpty() || root.children.isEmpty()) {
            xml.start("section")
            sectionContent(root.content)
            xml.end()
        }
        for (child in root.children) section(child)
        xml.end()
        for ((id, type, bytes) in binaries) xml.leaf("binary", encodeBase64(bytes), "id" to id, "content-type" to type)
        xml.end()
        return xml.toString().encodeToByteArray()
    }

    private fun sections(): Section {
        val root = Section(null, 0)
        val stack = arrayListOf(root)
        for (block in doc.blocks) {
            if (block !is Block.Heading) {
                stack.last().content.add(block)
                continue
            }
            val level = block.level.coerceIn(1, 6)
            while (stack.size > 1 && stack.last().level >= level) stack.removeAt(stack.lastIndex)
            while (stack.last().level < level - 1) {
                val filler = Section(null, stack.last().level + 1)
                stack.last().children.add(filler)
                stack.add(filler)
            }
            val section = Section(block, level)
            stack.last().children.add(section)
            stack.add(section)
        }
        return root
    }

    private fun section(section: Section) {
        xml.start("section")
        section.title?.let { title(it.content) }
        if (section.children.isEmpty()) {
            sectionContent(section.content)
        } else {
            if (section.content.isNotEmpty()) {
                xml.start("section")
                sectionContent(section.content)
                xml.end()
            }
            section.children.forEach(::section)
        }
        xml.end()
    }

    private fun sectionContent(blocks: List<Block>) {
        val flow = Flow()
        content(blocks, flow)
        if (!flow.started) xml.leaf("empty-line")
    }

    private fun title(content: List<Inline>) {
        val lines = lines(content)
        if (lines.isEmpty()) return
        xml.start("title")
        for (line in lines) {
            xml.start("p")
            inlines(line)
            xml.text("").end()
        }
        xml.end()
    }

    private fun content(blocks: List<Block>, flow: Flow, indent: String = "") {
        for (block in blocks) {
            when (block) {
                is Block.Heading -> {
                    xml.start("subtitle")
                    inlines(block.content.map { if (it == Inline.LineBreak) Inline.Text(" ") else it })
                    xml.text("").end()
                    flow.started = true
                }
                is Block.Paragraph -> paragraph(block.content, indent, flow)
                is Block.ListBlock -> list(block, flow, indent)
                is Block.Quote -> if (flow.cite) content(flatten(block.blocks), flow, indent) else quote(block.blocks, flow)
                is Block.Code -> {
                    for (line in block.text.split('\n')) {
                        val shown = line.replace(' ', Char(0xA0)).ifEmpty { Char(0xA0).toString() }
                        xml.start("p").leaf("code", indent + shown).end()
                    }
                    flow.started = true
                }
                is Block.Table -> table(block, flow)
                is Block.Picture -> picture(block, flow)
                Block.Rule -> {
                    xml.leaf("subtitle", "* * *")
                    flow.started = true
                }
                Block.PageBreak -> {
                    xml.leaf("empty-line")
                    flow.started = true
                }
            }
        }
    }

    // FB2 has line breaks only as the lines of a stanza
    private fun paragraph(content: List<Inline>, indent: String, flow: Flow) {
        val lines = lines(content)
        if (lines.isEmpty()) return
        if (lines.size == 1) {
            xml.start("p")
            if (indent.isNotEmpty()) xml.text(indent)
            inlines(lines[0])
            xml.text("").end()
        } else {
            xml.start("poem").start("stanza")
            for (line in lines) {
                xml.start("v")
                inlines(line)
                xml.text("").end()
            }
            xml.end().end()
        }
        flow.started = true
    }

    private fun lines(content: List<Inline>): List<List<Inline>> {
        val result = ArrayList<List<Inline>>()
        var current = ArrayList<Inline>()
        for (item in content) {
            if (item == Inline.LineBreak) {
                result.add(current)
                current = ArrayList()
            } else {
                current.add(item)
            }
        }
        result.add(current)
        return result.filter { line -> line.any { it is Inline.Text && it.text.isNotBlank() } }
    }

    private fun list(list: Block.ListBlock, flow: Flow, indent: String) {
        list.items.forEachIndexed { i, item ->
            val marker = if (list.ordered) "${list.start + i}. " else "• "
            val first = item.firstOrNull()
            if (first is Block.Paragraph) {
                paragraph(listOf(Inline.Text(marker)) + first.content, indent, flow)
                content(item.drop(1), flow, indent + INDENT)
            } else {
                paragraph(listOf(Inline.Text(marker.trim())), indent, flow)
                content(item, flow, indent + INDENT)
            }
        }
    }

    private fun quote(blocks: List<Block>, flow: Flow) {
        val pending = ArrayList<Block>()
        fun flush() {
            if (pending.isEmpty()) return
            val inner = Flow(cite = true)
            xml.start("cite")
            content(pending, inner)
            if (!inner.started) xml.leaf("empty-line")
            xml.end()
            pending.clear()
            flow.started = true
        }
        for (block in flatten(blocks)) {
            if (block is Block.Picture) {
                flush()
                picture(block, flow)
            } else {
                pending.add(block)
            }
        }
        flush()
    }

    private fun flatten(blocks: List<Block>): List<Block> = blocks.flatMap { if (it is Block.Quote) flatten(it.blocks) else listOf(it) }

    private fun table(table: Block.Table, flow: Flow) {
        val rows = table.rows.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return
        xml.start("table")
        rows.forEachIndexed { r, row ->
            xml.start("tr")
            for (cell in row) {
                val attributes = ArrayList<Pair<String, String>>()
                if (cell.colSpan > 1) attributes.add("colspan" to cell.colSpan.toString())
                when ((cell.blocks.firstOrNull() as? Block.Paragraph)?.align) {
                    Align.CENTER -> attributes.add("align" to "center")
                    Align.END -> attributes.add("align" to "right")
                    else -> {}
                }
                xml.start(if (r < table.headerRows) "th" else "td", *attributes.toTypedArray())
                val inline = cellInlines(cell.blocks)
                inlines(inline)
                xml.text("").end()
            }
            xml.end()
        }
        xml.end()
        flow.started = true
    }

    private fun cellInlines(blocks: List<Block>): List<Inline> {
        val result = ArrayList<Inline>()
        for (block in blocks) {
            val part = when (block) {
                is Block.Paragraph -> block.content
                is Block.Heading -> block.content
                else -> listOf(Inline.Text(blocksText(listOf(block)).collapseSpaces()))
            }.map { if (it == Inline.LineBreak) Inline.Text(" ") else it }
            if (result.isNotEmpty() && part.isNotEmpty()) result.add(Inline.Text(" "))
            result.addAll(part)
        }
        return result
    }

    private fun picture(picture: Block.Picture, flow: Flow) {
        val type = ImageHeader.mimeType(picture.bytes)
        if (type != "image/jpeg" && type != "image/png" || flow.cite) {
            if (picture.alt.isNotBlank()) paragraph(listOf(Inline.Text(picture.alt)), "", flow)
            return
        }
        val id = binaries.firstOrNull { it.third.contentEquals(picture.bytes) }?.first
            ?: "image${binaries.size + 1}.${if (type == "image/png") "png" else "jpg"}".also { binaries.add(Triple(it, type, picture.bytes)) }
        if (!flow.started && flow.leadImage) {
            xml.leaf("empty-line")
            flow.started = true
        }
        if (picture.alt.isNotBlank()) xml.leaf("image", null, "l:href" to "#$id", "alt" to picture.alt) else xml.leaf("image", null, "l:href" to "#$id")
        if (!flow.started) flow.leadImage = true
    }

    private fun inlines(content: List<Inline>) {
        val open = ArrayList<Pair<String, String?>>()
        for (item in content) {
            val t = item as? Inline.Text ?: continue
            if (t.text.isEmpty()) continue
            val wanted = buildList {
                externalLink(t.link)?.let { add("a" to uriSafe(it)) }
                if (t.bold) add("strong" to null)
                if (t.italic) add("emphasis" to null)
                if (t.strike) add("strikethrough" to null)
                if (t.code) add("code" to null)
                when (t.script) {
                    Script.SUPER -> add("sup" to null)
                    Script.SUB -> add("sub" to null)
                    Script.NORMAL -> {}
                }
            }
            var common = 0
            while (common < open.size && common < wanted.size && open[common] == wanted[common]) common++
            while (open.size > common) {
                xml.end()
                open.removeAt(open.lastIndex)
            }
            for (tag in wanted.subList(common, wanted.size)) {
                if (tag.second != null) xml.start(tag.first, "l:href" to tag.second!!) else xml.start(tag.first)
                open.add(tag)
            }
            xml.text(t.text)
        }
        repeat(open.size) { xml.end() }
    }

    private fun description() {
        val title = documentTitle(doc)
        val authors = doc.author?.split(',', ';')?.map { it.collapseSpaces() }?.filter { it.isNotEmpty() }.orEmpty()
        xml.start("description").start("title-info")
        xml.leaf("genre", "nonfiction")
        if (authors.isEmpty()) xml.start("author").leaf("nickname", "Unknown").end() else authors.forEach(::author)
        xml.leaf("book-title", title)
        xml.leaf("lang", languageOf(doc))
        xml.end()
        xml.start("document-info")
        if (authors.isEmpty()) xml.start("author").leaf("nickname", "Unknown").end() else authors.forEach(::author)
        xml.leaf("program-used", "vasmarfas Mobitool")
        xml.leaf("date", FB2_DATE, "value" to FB2_DATE)
        val digest = Sha256.digest((title + "\u0000" + doc.author.orEmpty() + "\u0000" + blocksText(doc.blocks)).encodeToByteArray()).toHex()
        xml.leaf("id", digest.substring(0, 32))
        xml.leaf("version", "1.0")
        xml.end()
        xml.end()
    }

    private fun author(name: String) {
        val words = name.split(' ')
        xml.start("author")
        when {
            words.size == 1 -> xml.leaf("nickname", name)
            words.size == 2 -> xml.leaf("first-name", words[0]).leaf("last-name", words[1])
            else -> xml.leaf("first-name", words[0]).leaf("middle-name", words.subList(1, words.size - 1).joinToString(" ")).leaf("last-name", words.last())
        }
        xml.end()
    }
}
