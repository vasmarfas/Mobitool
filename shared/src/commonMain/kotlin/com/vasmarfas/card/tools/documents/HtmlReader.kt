package com.vasmarfas.card.tools.documents

import com.vasmarfas.card.core.TextDecoding

internal sealed interface HtmlNode

internal class HtmlText(val text: String) : HtmlNode

internal class HtmlElement(val name: String, val attributes: Map<String, String>) : HtmlNode {
    val children = ArrayList<HtmlNode>()

    fun attr(name: String): String? = attributes[name]
}

private val VOID = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr", "keygen",
    "basefont", "bgsound", "frame", "command", "image",
)
private val RAW_TEXT = setOf("script", "style", "xmp", "iframe", "noembed", "noframes", "noscript", "title", "textarea", "plaintext")
private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
private val CLOSES_P = setOf(
    "address", "article", "aside", "blockquote", "center", "details", "dialog", "dir", "div", "dl", "fieldset", "figcaption",
    "figure", "footer", "form", "header", "hgroup", "hr", "main", "menu", "nav", "ol", "p", "pre", "section", "summary", "table",
    "ul", "li", "dd", "dt", "listing", "xmp", "plaintext",
) + HEADINGS
private val SCOPE = setOf("#root", "html", "table", "td", "th", "caption", "marquee", "object", "applet", "template")
private val BUTTON_SCOPE = SCOPE + "button"
private val SPECIAL = setOf(
    "#root", "applet", "area", "article", "aside", "base", "basefont", "bgsound", "blockquote", "body", "br", "button", "caption",
    "center", "col", "colgroup", "dd", "details", "dir", "dl", "dt", "embed", "fieldset", "figcaption", "figure", "footer", "form",
    "frame", "frameset", "head", "header", "hgroup", "hr", "html", "iframe", "img", "input", "li", "link", "listing", "main",
    "marquee", "menu", "meta", "nav", "noembed", "noframes", "noscript", "object", "ol", "param", "plaintext", "pre", "script",
    "section", "select", "source", "style", "summary", "table", "tbody", "td", "template", "textarea", "tfoot", "th", "thead",
    "title", "tr", "track", "ul", "wbr", "xmp",
) + HEADINGS
private val ITEM_STOP = SPECIAL - setOf("address", "div", "p", "li", "dd", "dt")
private val ROW_STOP = setOf("#root", "html", "template", "table", "tbody", "thead", "tfoot", "tr")
private val TABLE_STOP = setOf("#root", "html", "template", "table", "tbody", "thead", "tfoot")
private val HEAD_CONTENT = setOf("title", "meta", "link", "style", "script", "base", "noscript", "template")

// not the full HTML5 tree builder: implied end tags for p, li, dt/dd, table parts and headings, void
// elements, /> closes anything (XHTML), raw text in script and style, no foster parenting
internal class HtmlParser(private val src: String) {
    private val root = HtmlElement("#root", emptyMap())
    private val stack = arrayListOf(root)
    private var pos = 0

    private val current: HtmlElement get() = stack[stack.lastIndex]

    fun parse(): HtmlElement {
        val n = src.length
        while (pos < n) {
            val lt = src.indexOf('<', pos)
            if (lt < 0) {
                text(src.substring(pos))
                break
            }
            if (lt > pos) text(src.substring(pos, lt))
            pos = lt
            markup()
        }
        return root
    }

    private fun text(raw: String) {
        if (raw.isEmpty()) return
        if (current.name == "head" && raw.isNotBlank()) pop()
        current.children.add(HtmlText(HtmlEntities.decode(raw)))
    }

    private fun markup() {
        val n = src.length
        val next = if (pos + 1 < n) src[pos + 1] else ' '
        when {
            src.startsWith("<!-->", pos) -> pos += 5
            src.startsWith("<!--->", pos) -> pos += 6
            src.startsWith("<!--", pos) -> pos = src.indexOf("-->", pos + 4).let { if (it < 0) n else it + 3 }
            src.startsWith("<![CDATA[", pos) -> {
                val end = src.indexOf("]]>", pos + 9)
                current.children.add(HtmlText(src.substring(pos + 9, if (end < 0) n else end)))
                pos = if (end < 0) n else end + 3
            }
            next == '!' || next == '?' -> pos = src.indexOf('>', pos).let { if (it < 0) n else it + 1 }
            next == '/' -> {
                val c = if (pos + 2 < n) src[pos + 2] else ' '
                if (c in 'a'..'z' || c in 'A'..'Z') {
                    var i = pos + 2
                    while (i < n && !src[i].isWhitespace() && src[i] != '/' && src[i] != '>') i++
                    val name = src.substring(pos + 2, i).lowercase()
                    pos = src.indexOf('>', i).let { if (it < 0) n else it + 1 }
                    endTag(name)
                } else {
                    pos = src.indexOf('>', pos).let { if (it < 0) n else it + 1 }
                }
            }
            next in 'a'..'z' || next in 'A'..'Z' -> startTag()
            else -> {
                current.children.add(HtmlText("<"))
                pos++
            }
        }
    }

    private fun startTag() {
        val n = src.length
        var i = pos + 1
        while (i < n && !src[i].isWhitespace() && src[i] != '/' && src[i] != '>') i++
        val name = src.substring(pos + 1, i).lowercase()
        val attributes = LinkedHashMap<String, String>()
        var selfClosing = false
        while (i < n) {
            while (i < n && src[i].isWhitespace()) i++
            if (i >= n) break
            val c = src[i]
            if (c == '>') {
                i++
                break
            }
            if (c == '/') {
                if (i + 1 < n && src[i + 1] == '>') {
                    selfClosing = true
                    i += 2
                    break
                }
                i++
                continue
            }
            val nameStart = i
            i++
            while (i < n && !src[i].isWhitespace() && src[i] != '/' && src[i] != '>' && src[i] != '=') i++
            val attribute = src.substring(nameStart, i).lowercase()
            while (i < n && src[i].isWhitespace()) i++
            var value = ""
            if (i < n && src[i] == '=') {
                i++
                while (i < n && src[i].isWhitespace()) i++
                if (i < n && (src[i] == '"' || src[i] == '\'')) {
                    val close = src.indexOf(src[i], i + 1)
                    val end = if (close < 0) n else close
                    value = src.substring(i + 1, end)
                    i = if (close < 0) n else close + 1
                } else {
                    val start = i
                    while (i < n && !src[i].isWhitespace() && src[i] != '>') i++
                    value = src.substring(start, i)
                }
            }
            if (attribute !in attributes) attributes[attribute] = HtmlEntities.decode(value, attribute = true)
        }
        pos = i
        val element = open(name, attributes, selfClosing) ?: return
        if (name in RAW_TEXT && !selfClosing) rawText(element)
    }

    private fun rawText(element: HtmlElement) {
        val n = src.length
        val name = element.name
        var end = if (name == "plaintext") n else pos
        if (name != "plaintext") {
            while (true) {
                end = src.indexOf("</", end)
                if (end < 0) {
                    end = n
                    break
                }
                val after = end + 2 + name.length
                if (src.regionMatches(end + 2, name, 0, name.length, ignoreCase = true) && (after >= n || src[after] in " \t\n\r\u000C/>")) break
                end += 2
            }
        }
        val content = src.substring(pos, end)
        if (content.isNotEmpty()) element.children.add(HtmlText(if (name == "title" || name == "textarea") HtmlEntities.decode(content) else content))
        pos = if (end >= n) n else src.indexOf('>', end).let { if (it < 0) n else it + 1 }
        if (element === current) pop()
    }

    private fun open(name: String, attributes: Map<String, String>, selfClosing: Boolean): HtmlElement? {
        when (name) {
            "html", "body", "head" -> if (stack.any { it.name == name }) return null
        }
        if (name == "body") closeImplied(setOf("head"), SCOPE)
        if (current.name == "head" && name !in HEAD_CONTENT) pop()
        if (name in CLOSES_P) closeImplied(setOf("p"), BUTTON_SCOPE)
        when (name) {
            "li" -> closeImplied(setOf("li"), ITEM_STOP)
            "dd", "dt" -> closeImplied(setOf("dd", "dt"), ITEM_STOP)
            in HEADINGS -> if (current.name in HEADINGS) pop()
            "tr" -> closeImplied(setOf("tr"), TABLE_STOP)
            "td", "th" -> {
                closeImplied(setOf("td", "th"), ROW_STOP)
                if (current.name in TABLE_STOP && current.name != "#root" && current.name != "html") push(HtmlElement("tr", emptyMap()))
            }
            "thead", "tbody", "tfoot" -> closeImplied(setOf("thead", "tbody", "tfoot"), setOf("#root", "html", "table"))
            "option" -> if (current.name == "option") pop()
            "optgroup" -> {
                if (current.name == "option") pop()
                if (current.name == "optgroup") pop()
            }
            "a" -> closeImplied(setOf("a"), SCOPE)
        }
        val element = HtmlElement(name, attributes)
        if (selfClosing || name in VOID) current.children.add(element) else push(element)
        return element
    }

    private fun endTag(name: String) {
        when (name) {
            "br" -> open("br", emptyMap(), selfClosing = true)
            "p" -> closeImplied(setOf("p"), BUTTON_SCOPE)
            "li" -> closeImplied(setOf("li"), SCOPE + setOf("ul", "ol"))
            "dd", "dt" -> closeImplied(setOf(name), SCOPE + "dl")
            in HEADINGS -> closeImplied(HEADINGS, SCOPE)
            "body", "html" -> {}
            "table" -> closeImplied(setOf("table"), setOf("#root", "html", "template"))
            "tr", "td", "th", "thead", "tbody", "tfoot", "caption" -> closeImplied(setOf(name), setOf("#root", "html", "template", "table"))
            else -> closeImplied(setOf(name), SCOPE)
        }
    }

    private fun closeImplied(names: Set<String>, stops: Set<String>) {
        for (i in stack.lastIndex downTo 1) {
            val name = stack[i].name
            if (name in names) {
                while (stack.size > i) pop()
                return
            }
            if (name in stops) return
        }
    }

    private fun push(element: HtmlElement) {
        current.children.add(element)
        if (stack.size <= MAX_NESTING) stack.add(element)
    }

    private fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

// only compound selectors (p, .italic, span.b, #id), EPUBs keep most of their bold and italic in them
private class StyleSheet {
    private class Rule(val tag: String?, val classes: List<String>, val id: String?, val weight: Int, val order: Int, val declarations: Map<String, String>)

    private var count = 0
    private val byTag = HashMap<String, MutableList<Rule>>()
    private val byClass = HashMap<String, MutableList<Rule>>()
    private val byId = HashMap<String, MutableList<Rule>>()

    fun add(css: String) {
        val text = stripComments(css)
        var i = 0
        while (i < text.length) {
            val open = text.indexOf('{', i)
            if (open < 0) return
            val selectors = text.substring(i, open).substringAfterLast(';').substringAfterLast('}').trim()
            val close = matchingBrace(text, open)
            if (!selectors.startsWith('@')) {
                val declarations = parseDeclarations(text.substring(open + 1, close))
                if (declarations.isNotEmpty()) {
                    for (selector in selectors.split(',')) parseSelector(selector.trim(), declarations)
                }
            } else if (selectors.startsWith("@media", ignoreCase = true) && !selectors.contains("print", ignoreCase = true)) {
                add(text.substring(open + 1, close))
            }
            i = close + 1
        }
    }

    fun declarations(element: HtmlElement): Map<String, String> {
        val inline = element.attr("style")
        if (count == 0) return if (inline == null) emptyMap() else parseDeclarations(inline)
        val classes = element.attr("class")?.split(' ', '\t', '\n')?.filter { it.isNotEmpty() }.orEmpty()
        val id = element.attr("id")
        val candidates = ArrayList<Rule>()
        byTag[element.name]?.let(candidates::addAll)
        for (c in classes) byClass[c]?.let(candidates::addAll)
        if (id != null) byId[id]?.let(candidates::addAll)
        val matched = candidates.distinct().filter { r ->
            (r.tag == null || r.tag == element.name) && (r.id == null || r.id == id) && r.classes.all { it in classes }
        }
        if (matched.isEmpty() && inline == null) return emptyMap()
        val result = HashMap<String, String>()
        for (r in matched.sortedWith(compareBy<Rule>({ it.weight }, { it.order }))) result.putAll(r.declarations)
        if (inline != null) result.putAll(parseDeclarations(inline))
        return result
    }

    private fun parseSelector(selector: String, declarations: Map<String, String>) {
        if (selector.isEmpty() || selector.any { it.isWhitespace() || it in ">+~[]:()*\\" }) return
        var tag: String? = null
        val classes = ArrayList<String>()
        var id: String? = null
        var i = 0
        while (i < selector.length) {
            val kind = selector[i]
            var j = if (kind == '.' || kind == '#') i + 1 else i
            while (j < selector.length && selector[j] != '.' && selector[j] != '#') j++
            val part = selector.substring(if (kind == '.' || kind == '#') i + 1 else i, j)
            if (part.isEmpty()) return
            when (kind) {
                '.' -> classes.add(part)
                '#' -> id = part
                else -> tag = part.lowercase()
            }
            i = j
        }
        val weight = (if (id != null) 100 else 0) + classes.size * 10 + (if (tag != null) 1 else 0)
        val rule = Rule(tag, classes, id, weight, count++, declarations)
        when {
            id != null -> byId.getOrPut(id) { ArrayList() }.add(rule)
            classes.isNotEmpty() -> byClass.getOrPut(classes[0]) { ArrayList() }.add(rule)
            tag != null -> byTag.getOrPut(tag) { ArrayList() }.add(rule)
        }
    }

    private fun stripComments(css: String): String {
        if ("/*" !in css) return css
        val sb = StringBuilder(css.length)
        var i = 0
        while (i < css.length) {
            val start = css.indexOf("/*", i)
            if (start < 0) {
                sb.append(css, i, css.length)
                break
            }
            sb.append(css, i, start)
            val end = css.indexOf("*/", start + 2)
            i = if (end < 0) css.length else end + 2
        }
        return sb.toString()
    }

    private fun matchingBrace(text: String, open: Int): Int {
        var depth = 0
        for (k in open until text.length) {
            when (text[k]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return k
            }
        }
        return text.length - 1
    }

    companion object {
        fun parseDeclarations(text: String): Map<String, String> {
            val result = HashMap<String, String>()
            for (part in text.split(';')) {
                val colon = part.indexOf(':')
                if (colon <= 0) continue
                val name = part.substring(0, colon).trim().lowercase()
                val value = part.substring(colon + 1).replace("!important", "", ignoreCase = true).trim().lowercase()
                if (name.isNotEmpty() && value.isNotEmpty()) result[name] = value
            }
            return result
        }
    }
}

private enum class WhiteSpace { NORMAL, PRE, PRE_LINE }

private class Ctx(val style: Inline.Text, val align: Align, val whiteSpace: WhiteSpace)

private class Sink(var align: Align) {
    val out = ArrayList<Block>()
    val segments = Segments()

    fun flush() {
        out.addAll(segments.blocks { Block.Paragraph(it, align) })
    }

    fun add(block: Block) {
        flush()
        out.add(block)
    }
}

private val SKIPPED = setOf(
    "head", "title", "style", "script", "meta", "link", "base", "template", "noscript", "xml", "select", "textarea", "input",
    "iframe", "embed", "video", "audio", "canvas", "map", "area", "rt", "rp", "annotation", "annotation-xml", "param", "source",
    "track", "noembed", "noframes", "colgroup", "col", "frameset", "frame", "datalist", "dialog",
)
private val CONTAINERS = setOf(
    "#root", "html", "body", "p", "div", "section", "article", "main", "header", "footer", "nav", "aside", "address", "center",
    "form", "fieldset", "legend", "details", "summary", "figure", "figcaption", "hgroup", "li", "dd", "dt", "caption", "dl",
    "search",
)
private val LISTS = setOf("ul", "ol", "menu", "dir")

internal class HtmlReader(private val resource: ((String) -> ByteArray?)?) {
    private val css = StyleSheet()
    private var title: String? = null
    private var author: String? = null
    private var language: String? = null

    fun read(text: String): Doc {
        val root = HtmlParser(text).parse()
        collectHead(root)
        val sink = Sink(Align.START)
        walk(root, Ctx(PLAIN, Align.START, WhiteSpace.NORMAL), sink)
        sink.flush()
        return Doc(sink.out, title, author, language)
    }

    private fun collectHead(root: HtmlElement) {
        val stack = ArrayList<HtmlElement>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val el = stack.removeAt(stack.lastIndex)
            when (el.name) {
                "html" -> language = language ?: (el.attr("lang") ?: el.attr("xml:lang"))?.trim()?.takeIf { it.isNotEmpty() }
                "title" -> if (title == null) title = el.children.filterIsInstance<HtmlText>().joinToString("") { it.text }.collapseSpaces()
                "meta" -> {
                    val name = el.attr("name")?.lowercase()
                    val content = el.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
                    if (name == "author" || name == "dc.creator") author = author ?: content
                    if (el.attr("http-equiv").equals("content-language", ignoreCase = true)) language = language ?: content
                }
                "style" -> css.add(el.children.filterIsInstance<HtmlText>().joinToString("") { it.text })
                "link" -> {
                    val href = el.attr("href")
                    if (resource != null && href != null && el.attr("rel")?.contains("stylesheet", ignoreCase = true) == true) {
                        resource.invoke(href)?.let { css.add(TextDecoding.decode(it)) }
                    }
                }
                "svg" -> continue
            }
            for (i in el.children.lastIndex downTo 0) (el.children[i] as? HtmlElement)?.let(stack::add)
        }
    }

    private fun walk(el: HtmlElement, ctx: Ctx, sink: Sink) {
        for (child in el.children) {
            when (child) {
                is HtmlText -> text(child.text, ctx, sink)
                is HtmlElement -> element(child, ctx, sink)
            }
        }
    }

    private fun text(value: String, ctx: Ctx, sink: Sink) {
        val inline = sink.segments.inline
        when (ctx.whiteSpace) {
            WhiteSpace.NORMAL -> inline.collapsed(value, ctx.style)
            WhiteSpace.PRE -> inline.preserved(value.replace("\r\n", "\n").replace('\r', '\n'), ctx.style)
            WhiteSpace.PRE_LINE -> value.replace("\r\n", "\n").split('\n').forEachIndexed { i, line ->
                if (i > 0) inline.lineBreak()
                inline.collapsed(line, ctx.style)
            }
        }
    }

    private fun element(el: HtmlElement, parent: Ctx, sink: Sink) {
        val name = el.name
        if (name in SKIPPED) return
        val declarations = css.declarations(el)
        if (declarations["display"] == "none" || declarations["visibility"] == "hidden" || "hidden" in el.attributes) return
        val ctx = derive(parent, el, declarations)
        if (isPageBreak(declarations["page-break-before"] ?: declarations["break-before"])) sink.add(Block.PageBreak)
        val local = name.substringAfterLast(':')
        when {
            name == "br" -> sink.segments.inline.lineBreak()
            name == "img" || local == "image" || local == "imagedata" -> picture(el, declarations, ctx, sink)
            name == "hr" -> sink.add(Block.Rule)
            name in HEADINGS -> headingBlock(el, name[1] - '0', ctx, sink)
            name == "blockquote" -> sink.add(Block.Quote(childBlocks(el.children, ctx)))
            name == "pre" || name == "xmp" || name == "listing" || name == "plaintext" -> code(el, sink)
            name in LISTS -> list(el, ctx, sink)
            name == "table" -> table(el, ctx, sink)
            local == "svg" -> svgImages(el, ctx, sink)
            name == "q" -> {
                sink.segments.inline.collapsed("“", ctx.style)
                walk(el, ctx, sink)
                sink.segments.inline.collapsed("”", ctx.style)
            }
            name in CONTAINERS -> {
                sink.flush()
                val saved = sink.align
                sink.align = ctx.align
                walk(el, ctx, sink)
                sink.flush()
                sink.align = saved
            }
            else -> walk(el, ctx, sink)
        }
        if (isPageBreak(declarations["page-break-after"] ?: declarations["break-after"])) sink.add(Block.PageBreak)
    }

    private fun derive(parent: Ctx, el: HtmlElement, css: Map<String, String>): Ctx {
        var s = parent.style
        when (el.name) {
            "b", "strong", "dt" -> s = s.copy(bold = true)
            "i", "em", "cite", "dfn", "var", "address" -> s = s.copy(italic = true)
            "u", "ins" -> s = s.copy(underline = true)
            "s", "strike", "del" -> s = s.copy(strike = true)
            "sub" -> s = s.copy(script = Script.SUB)
            "sup" -> s = s.copy(script = Script.SUPER)
            "code", "kbd", "samp", "tt" -> s = s.copy(code = true)
            "a" -> externalLink(el.attr("href"))?.let { s = s.copy(link = it) }
            "font" -> if (isMonospaceFont(el.attr("face"))) s = s.copy(code = true)
        }
        css["font-weight"]?.let { w ->
            when {
                w == "bold" || w == "bolder" || (w.toIntOrNull() ?: 0) >= 600 -> s = s.copy(bold = true)
                w == "normal" || w == "lighter" || (w.toIntOrNull() ?: 1000) < 600 -> s = s.copy(bold = false)
            }
        }
        css["font-style"]?.let { s = s.copy(italic = it.startsWith("italic") || it.startsWith("oblique")) }
        (css["text-decoration-line"] ?: css["text-decoration"])?.let { d ->
            if (d.startsWith("none")) {
                s = s.copy(underline = false, strike = false)
            } else {
                if ("underline" in d) s = s.copy(underline = true)
                if ("line-through" in d) s = s.copy(strike = true)
            }
        }
        css["font-family"]?.let { s = s.copy(code = isMonospaceFont(it)) }
        css["font"]?.let { f ->
            if ("bold" in f) s = s.copy(bold = true)
            if ("italic" in f) s = s.copy(italic = true)
            if ("monospace" in f) s = s.copy(code = true)
        }
        when (css["vertical-align"]) {
            "super" -> s = s.copy(script = Script.SUPER)
            "sub" -> s = s.copy(script = Script.SUB)
            "baseline" -> s = s.copy(script = Script.NORMAL)
        }
        val align = alignOf(css["text-align"]) ?: alignOf(el.attr("align")?.lowercase()) ?: when (el.name) {
            "center" -> Align.CENTER
            "figcaption" -> Align.CENTER
            else -> parent.align
        }
        val whiteSpace = when (css["white-space"]) {
            "pre", "pre-wrap", "break-spaces" -> WhiteSpace.PRE
            "pre-line" -> WhiteSpace.PRE_LINE
            "normal", "nowrap" -> WhiteSpace.NORMAL
            else -> parent.whiteSpace
        }
        return Ctx(s, align, whiteSpace)
    }

    private fun alignOf(value: String?): Align? = when (value?.trim()) {
        "left", "start" -> Align.START
        "center", "middle", "-webkit-center" -> Align.CENTER
        "right", "end" -> Align.END
        "justify" -> Align.JUSTIFY
        else -> null
    }

    private fun isPageBreak(value: String?): Boolean = value == "always" || value == "page" || value == "left" || value == "right" ||
        value == "recto" || value == "verso"

    private fun childBlocks(nodes: List<HtmlNode>, ctx: Ctx): List<Block> {
        val sink = Sink(ctx.align)
        for (child in nodes) {
            when (child) {
                is HtmlText -> text(child.text, ctx, sink)
                is HtmlElement -> element(child, ctx, sink)
            }
        }
        sink.flush()
        return sink.out
    }

    private fun headingBlock(el: HtmlElement, level: Int, ctx: Ctx, sink: Sink) {
        sink.flush()
        val blocks = childBlocks(el.children, ctx)
        val content = InlineBuilder()
        var seen = false
        val after = ArrayList<Block>()
        for (block in blocks) {
            if (block is Block.Paragraph) {
                if (seen) content.preserved(" ", PLAIN)
                block.content.forEach(content::inline)
                seen = true
            } else if (seen) {
                after.add(block)
            } else {
                sink.out.add(block)
            }
        }
        if (seen) sink.out.add(heading(level, content.build()))
        sink.out.addAll(after)
    }

    private fun code(el: HtmlElement, sink: Sink) {
        sink.flush()
        val sb = StringBuilder()
        collectText(el, sb)
        var text = sb.toString().replace("\r\n", "\n").replace('\r', '\n')
        if ((el.children.firstOrNull() as? HtmlText)?.text?.startsWith('\n') == true) text = text.removePrefix("\n")
        text = text.trimEnd('\n', ' ')
        if (text.isNotBlank()) sink.out.add(Block.Code(text))
    }

    private fun collectText(el: HtmlElement, sb: StringBuilder) {
        for (child in el.children) {
            when (child) {
                is HtmlText -> sb.append(child.text)
                is HtmlElement -> when (child.name) {
                    "br" -> sb.append('\n')
                    in SKIPPED -> {}
                    else -> collectText(child, sb)
                }
            }
        }
    }

    private fun list(el: HtmlElement, ctx: Ctx, sink: Sink) {
        sink.flush()
        val ordered = el.name == "ol"
        var start = if (ordered) el.attr("start")?.trim()?.toIntOrNull() ?: 1 else 1
        val items = ArrayList<List<Block>>()
        for (child in el.children) {
            when {
                child is HtmlText && child.text.isBlank() -> {}
                child is HtmlElement && child.name == "li" -> {
                    if (items.isEmpty() && ordered) child.attr("value")?.trim()?.toIntOrNull()?.let { start = it }
                    val declarations = css.declarations(child)
                    if (declarations["display"] == "none") continue
                    items.add(childBlocks(child.children, derive(ctx, child, declarations)))
                }
                child is HtmlElement && child.name in LISTS && items.isNotEmpty() -> items[items.lastIndex] = items.last() + childBlocks(listOf(child), ctx)
                else -> items.add(childBlocks(listOf(child), ctx))
            }
        }
        sink.out.add(Block.ListBlock(ordered, items, start))
    }

    private fun table(el: HtmlElement, ctx: Ctx, sink: Sink) {
        sink.flush()
        val grid = TableGrid()
        val hasHead = el.children.any { it is HtmlElement && it.name == "thead" }
        var headerRows = 0
        var leading = true
        val footer = ArrayList<HtmlElement>()

        fun row(tr: HtmlElement, head: Boolean) {
            val cells = ArrayList<Cell>()
            val spans = ArrayList<Int>()
            var allTh = true
            for (child in tr.children) {
                if (child !is HtmlElement || (child.name != "td" && child.name != "th")) continue
                val declarations = css.declarations(child)
                val cellCtx = derive(ctx, child, declarations)
                cells.add(Cell(childBlocks(child.children, cellCtx), (child.attr("colspan")?.trim()?.toIntOrNull() ?: 1).coerceIn(1, MAX_COL_SPAN)))
                spans.add(child.attr("rowspan")?.trim()?.toIntOrNull() ?: 1)
                if (child.name != "th") allTh = false
            }
            if (cells.isEmpty()) return
            if (leading && (if (hasHead) head else allTh)) headerRows++ else leading = false
            grid.row(cells, spans)
        }

        fun rows(container: HtmlElement, head: Boolean) {
            for (child in container.children) {
                if (child !is HtmlElement) continue
                when (child.name) {
                    "tr" -> row(child, head)
                    "thead" -> rows(child, true)
                    "tbody" -> rows(child, false)
                    "tfoot" -> footer.add(child)
                    "caption" -> sink.out.addAll(childBlocks(child.children, Ctx(ctx.style, Align.CENTER, ctx.whiteSpace)))
                }
            }
        }

        rows(el, false)
        for (f in footer) rows(f, false)
        if (grid.rowCount > 0) sink.out.add(grid.build(headerRows))
    }

    private fun picture(el: HtmlElement, css: Map<String, String>, ctx: Ctx, sink: Sink) {
        val src = (el.attr("src") ?: el.attr("href") ?: el.attr("xlink:href") ?: el.attr("data-src"))?.trim().orEmpty()
        val alt = (el.attr("alt") ?: el.attr("o:title") ?: el.attr("title")).orEmpty().collapseSpaces()
        val bytes = when {
            src.isEmpty() -> null
            src.startsWith("data:", ignoreCase = true) -> decodeDataUri(src)
            src.startsWith("//") || externalLink(src) != null -> null
            else -> resource?.invoke(src)
        }
        if (bytes == null || bytes.isEmpty()) {
            if (alt.isNotEmpty()) sink.segments.inline.collapsed(" $alt ", ctx.style)
            return
        }
        val (w, h) = pictureSize(bytes, lengthPt(css["width"]) ?: lengthPt(el.attr("width")), lengthPt(css["height"]) ?: lengthPt(el.attr("height")))
        sink.segments.block(Block.Picture(bytes, w, h, alt))
    }

    private fun svgImages(el: HtmlElement, ctx: Ctx, sink: Sink) {
        for (child in el.children) {
            if (child !is HtmlElement) continue
            if (child.name.substringAfterLast(':') == "image") picture(child, emptyMap(), ctx, sink) else svgImages(child, ctx, sink)
        }
    }

    companion object {
        private val META_CHARSET = Regex("""<meta[^>]+charset\s*=\s*["']?\s*([A-Za-z0-9._:-]+)""", RegexOption.IGNORE_CASE)
        private val CYRILLIC_CHARSET = Regex("koi8|cyr|8859-5|855|866|1251")

        fun decode(bytes: ByteArray): String {
            val b0 = if (bytes.isNotEmpty()) bytes[0].toInt() and 0xFF else 0
            if (b0 == 0xEF || b0 == 0xFF || b0 == 0xFE) return TextDecoding.decode(bytes)
            val head = CharArray(minOf(bytes.size, 4096)) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()
            val declared = META_CHARSET.find(head)?.groupValues?.get(1)?.lowercase()
            if (declared != null) {
                val charset = if (declared == "iso-8859-1" || declared == "latin1" || declared == "us-ascii" || declared == "ascii") "windows-1252" else declared
                try {
                    return TextDecoding.decode(bytes, charset)
                } catch (e: IllegalArgumentException) {
                    // the content-based guess knows only UTF-8 and Cyrillic, other pages are closer to windows-1252
                    if (!CYRILLIC_CHARSET.containsMatchIn(charset)) {
                        return runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrElse { TextDecoding.decode(bytes, "windows-1252") }
                    }
                }
            }
            return TextDecoding.decode(bytes)
        }
    }
}

internal fun String.collapseSpaces(): String {
    val sb = StringBuilder(length)
    var space = false
    for (c in this) {
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C') {
            space = sb.isNotEmpty()
        } else {
            if (space) sb.append(' ')
            space = false
            sb.append(c)
        }
    }
    return sb.toString()
}
