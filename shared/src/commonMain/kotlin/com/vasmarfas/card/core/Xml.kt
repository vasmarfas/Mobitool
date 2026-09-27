package com.vasmarfas.card.core

import kotlin.math.min

class XmlException(message: String) : Exception(message)

enum class XmlEvent { START_ELEMENT, END_ELEMENT, TEXT, END_DOCUMENT }

private const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
private const val XMLNS_NAMESPACE = "http://www.w3.org/2000/xmlns/"

private const val LATIN1_ENTITY_NAMES =
    "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 acute micro para middot cedil sup1 " +
        "ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc " +
        "Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde auml " +
        "aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave " +
        "uacute ucirc uuml yacute thorn yuml"

private val NAMED_ENTITIES: Map<String, Int> = buildMap {
    put("amp", '&'.code)
    put("lt", '<'.code)
    put("gt", '>'.code)
    put("quot", '"'.code)
    put("apos", '\''.code)
    LATIN1_ENTITY_NAMES.split(' ').forEachIndexed { i, name -> put(name, 0xA0 + i) }
    putAll(
        listOf(
            "OElig" to 0x152, "oelig" to 0x153, "Scaron" to 0x160, "scaron" to 0x161, "Yuml" to 0x178, "fnof" to 0x192, "circ" to 0x2C6,
            "tilde" to 0x2DC, "ensp" to 0x2002, "emsp" to 0x2003, "thinsp" to 0x2009, "zwnj" to 0x200C, "zwj" to 0x200D, "lrm" to 0x200E,
            "rlm" to 0x200F, "ndash" to 0x2013, "mdash" to 0x2014, "lsquo" to 0x2018, "rsquo" to 0x2019, "sbquo" to 0x201A, "ldquo" to 0x201C,
            "rdquo" to 0x201D, "bdquo" to 0x201E, "dagger" to 0x2020, "Dagger" to 0x2021, "bull" to 0x2022, "hellip" to 0x2026,
            "permil" to 0x2030, "prime" to 0x2032, "Prime" to 0x2033, "lsaquo" to 0x2039, "rsaquo" to 0x203A, "euro" to 0x20AC,
            "trade" to 0x2122, "minus" to 0x2212,
        ),
    )
}

private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

private fun isEntityChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '#'

class XmlReader(text: String) {
    private val src = text
    private val end = text.length
    private var pos = if (text.startsWith('\uFEFF')) 1 else 0
    private var event: XmlEvent? = null
    private var closePending = false
    private var selfClosing = false
    private var rootSeen = false
    private val buffer = StringBuilder()

    private val openNames = ArrayList<String>()
    private val openLocalNames = ArrayList<String>()
    private val openNamespaces = ArrayList<String>()
    private var namespaceMarks = IntArray(16)
    private val prefixes = ArrayList<String>()
    private val uris = ArrayList<String>()

    private val attributeNames = ArrayList<String>()
    private val attributeLocalNames = ArrayList<String>()
    private val attributeNamespaces = ArrayList<String>()
    private val attributeValues = ArrayList<String>()

    var name: String = ""
        private set
    var localName: String = ""
        private set

    var namespace: String = ""
        private set

    var text: String = ""
        private set

    // END_ELEMENT reports the depth of its START_ELEMENT, TEXT the depth of the enclosing element
    var depth: Int = 0
        private set

    val attributeCount: Int get() = attributeNames.size

    fun attributeName(index: Int): String = attributeNames[index]

    fun attributeLocalName(index: Int): String = attributeLocalNames[index]

    fun attributeNamespace(index: Int): String = attributeNamespaces[index]

    fun attributeValue(index: Int): String = attributeValues[index]

    fun attribute(localName: String, namespace: String? = null): String? {
        for (i in attributeNames.indices) {
            if (attributeLocalNames[i] == localName && (namespace == null || attributeNamespaces[i] == namespace)) return attributeValues[i]
        }
        return null
    }

    // elements still open at the end get END_ELEMENT before END_DOCUMENT, so a truncated file still reads
    fun next(): XmlEvent {
        if (closePending) {
            closePending = false
            closeElement()
        }
        if (attributeNames.isNotEmpty()) clearAttributes()
        if (selfClosing) {
            selfClosing = false
            closePending = true
            return emit(XmlEvent.END_ELEMENT)
        }
        text = ""
        buffer.clear()
        var hasText = false
        while (pos < end) {
            if (src[pos] != '<') {
                if (depth == 0) {
                    skipOutsideText()
                } else {
                    readText()
                    hasText = true
                }
                continue
            }
            val marker = if (pos + 1 < end) src[pos + 1] else ' '
            when {
                marker == '?' -> skipPast("?>", pos + 2, "processing instruction")
                marker != '!' -> {
                    if (hasText) return textEvent()
                    return if (marker == '/') readEndTag() else readStartTag()
                }
                src.startsWith("<!--", pos) -> skipPast("-->", pos + 4, "comment")
                src.startsWith("<![CDATA[", pos) -> {
                    if (depth == 0) fail("CDATA outside the root element", pos)
                    readCData()
                    hasText = true
                }
                src.startsWith("<!DOCTYPE", pos, ignoreCase = true) -> {
                    if (rootSeen) fail("DOCTYPE after the root element", pos)
                    skipDoctype()
                }
                else -> fail("Unexpected markup", pos)
            }
        }
        if (hasText) return textEvent()
        if (depth > 0) {
            name = openNames[depth - 1]
            localName = openLocalNames[depth - 1]
            namespace = openNamespaces[depth - 1]
            closePending = true
            return emit(XmlEvent.END_ELEMENT)
        }
        if (!rootSeen) fail("No root element", pos)
        name = ""
        localName = ""
        namespace = ""
        return emit(XmlEvent.END_DOCUMENT)
    }

    fun skipElement() {
        check(event == XmlEvent.START_ELEMENT) { "skipElement() needs the reader on START_ELEMENT, was $event" }
        val target = depth
        while (true) {
            val e = next()
            if (e == XmlEvent.END_DOCUMENT || (e == XmlEvent.END_ELEMENT && depth == target)) return
        }
    }

    private fun emit(e: XmlEvent): XmlEvent {
        event = e
        return e
    }

    private fun textEvent(): XmlEvent {
        text = buffer.toString()
        name = ""
        localName = ""
        namespace = ""
        return emit(XmlEvent.TEXT)
    }

    private fun fail(message: String, offset: Int): Nothing = throw XmlException("$message at offset $offset")

    private fun clearAttributes() {
        attributeNames.clear()
        attributeLocalNames.clear()
        attributeNamespaces.clear()
        attributeValues.clear()
    }

    private fun skipPast(terminator: String, from: Int, what: String) {
        val found = src.indexOf(terminator, from)
        if (found < 0) fail("Unterminated $what", pos)
        pos = found + terminator.length
    }

    private fun skipOutsideText() {
        while (pos < end && src[pos] != '<') {
            if (!isSpace(src[pos])) fail(if (rootSeen) "Text after the root element" else "Text before the root element", pos)
            pos++
        }
    }

    private fun skipDoctype() {
        val start = pos
        var i = pos + 9
        var inSubset = false
        while (i < end) {
            val c = src[i]
            when {
                c == '"' || c == '\'' -> {
                    val close = src.indexOf(c, i + 1)
                    if (close < 0) break
                    i = close + 1
                }
                inSubset && c == ']' -> {
                    inSubset = false
                    i++
                }
                c == '[' -> {
                    inSubset = true
                    i++
                }
                inSubset && src.startsWith("<!--", i) -> {
                    val close = src.indexOf("-->", i + 4)
                    if (close < 0) break
                    i = close + 3
                }
                inSubset && src.startsWith("<?", i) -> {
                    val close = src.indexOf("?>", i + 2)
                    if (close < 0) break
                    i = close + 2
                }
                !inSubset && c == '>' -> {
                    pos = i + 1
                    return
                }
                else -> i++
            }
        }
        fail("Unterminated DOCTYPE", start)
    }

    private fun readText() {
        var i = pos
        var run = i
        while (i < end) {
            val c = src[i]
            if (c == '<') break
            if (c == '&') {
                buffer.appendRange(src, run, i)
                i = appendEntity(i)
                run = i
            } else if (c == '\r') {
                buffer.appendRange(src, run, i).append('\n')
                i += if (i + 1 < end && src[i + 1] == '\n') 2 else 1
                run = i
            } else {
                i++
            }
        }
        buffer.appendRange(src, run, i)
        pos = i
    }

    private fun readCData() {
        val close = src.indexOf("]]>", pos + 9)
        if (close < 0) fail("Unterminated CDATA section", pos)
        var i = pos + 9
        var run = i
        while (i < close) {
            if (src[i] == '\r') {
                buffer.appendRange(src, run, i).append('\n')
                i += if (i + 1 < close && src[i + 1] == '\n') 2 else 1
                run = i
            } else {
                i++
            }
        }
        buffer.appendRange(src, run, close)
        pos = close + 3
    }

    private fun appendEntity(amp: Int): Int {
        val limit = min(end, amp + 34)
        var semicolon = amp + 1
        while (semicolon < limit && isEntityChar(src[semicolon])) semicolon++
        if (semicolon >= end || src[semicolon] != ';' || semicolon == amp + 1) {
            buffer.append('&')
            return amp + 1
        }
        val code = if (src[amp + 1] == '#') charReference(amp + 2, semicolon) else NAMED_ENTITIES[src.substring(amp + 1, semicolon)] ?: -1
        when {
            code < 0 -> buffer.appendRange(src, amp, semicolon + 1)
            code < 0x10000 -> buffer.append(code.toChar())
            else -> {
                val v = code - 0x10000
                buffer.append((0xD800 + (v ushr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
            }
        }
        return semicolon + 1
    }

    private fun charReference(from: Int, to: Int): Int {
        var i = from
        var radix = 10
        if (i < to && (src[i] == 'x' || src[i] == 'X')) {
            radix = 16
            i++
        }
        if (i == to) return -1
        var value = 0
        while (i < to) {
            value = value * radix + (src[i].digitToIntOrNull(radix) ?: return -1)
            if (value > 0x10FFFF) return -1
            i++
        }
        return if (value == 0 || value in 0xD800..0xDFFF) -1 else value
    }

    private fun scanName(from: Int): Int {
        var i = from
        while (i < end) {
            when (src[i]) {
                ' ', '\t', '\n', '\r', '/', '>', '=', '<', '"', '\'' -> return i
            }
            i++
        }
        return i
    }

    private fun skipSpace(from: Int): Int {
        var i = from
        while (i < end && isSpace(src[i])) i++
        return i
    }

    private fun readStartTag(): XmlEvent {
        val start = pos
        if (depth == 0 && rootSeen) fail("Content after the root element", start)
        val nameEnd = scanName(start + 1)
        if (nameEnd == start + 1) fail("Invalid tag", start)
        val qName = src.substring(start + 1, nameEnd)
        var i = nameEnd
        while (true) {
            i = skipSpace(i)
            if (i >= end) fail("Unterminated tag <$qName", start)
            val c = src[i]
            if (c == '>') {
                i++
                break
            }
            if (c == '/') {
                if (i + 1 >= end) fail("Unterminated tag <$qName", start)
                if (src[i + 1] != '>') fail("Invalid tag <$qName", start)
                selfClosing = true
                i += 2
                break
            }
            val attrEnd = scanName(i)
            if (attrEnd == i) fail("Invalid attribute in <$qName", i)
            val attrName = src.substring(i, attrEnd)
            i = skipSpace(attrEnd)
            if (i >= end || src[i] != '=') fail("Attribute $attrName has no value", attrEnd)
            i = skipSpace(i + 1)
            val quote = if (i < end) src[i] else ' '
            if (quote != '"' && quote != '\'') fail("Value of attribute $attrName is not quoted", i)
            attributeNames.add(attrName)
            i = readAttributeValue(i + 1, quote)
        }
        pos = i
        openElement(qName)
        return emit(XmlEvent.START_ELEMENT)
    }

    private fun readAttributeValue(from: Int, quote: Char): Int {
        var i = from
        while (i < end) {
            val c = src[i]
            if (c == quote) {
                attributeValues.add(src.substring(from, i))
                return i + 1
            }
            if (c == '&' || c < ' ') break
            i++
        }
        buffer.clear()
        buffer.appendRange(src, from, i)
        while (i < end) {
            val c = src[i]
            when {
                c == quote -> {
                    attributeValues.add(buffer.toString())
                    return i + 1
                }
                c == '&' -> i = appendEntity(i)
                c == '\t' || c == '\n' || c == '\r' -> {
                    buffer.append(' ')
                    i += if (c == '\r' && i + 1 < end && src[i + 1] == '\n') 2 else 1
                }
                else -> {
                    buffer.append(c)
                    i++
                }
            }
        }
        fail("Unterminated attribute value", from - 1)
    }

    private fun openElement(qName: String) {
        if (depth == namespaceMarks.size) namespaceMarks = namespaceMarks.copyOf(depth * 2)
        namespaceMarks[depth] = prefixes.size
        for (i in attributeNames.indices) {
            val attr = attributeNames[i]
            if (attr == "xmlns") {
                prefixes.add("")
                uris.add(attributeValues[i])
            } else if (attr.startsWith("xmlns:")) {
                prefixes.add(attr.substring(6))
                uris.add(attributeValues[i])
            }
        }
        val colon = qName.indexOf(':')
        name = qName
        localName = if (colon < 0) qName else qName.substring(colon + 1)
        namespace = resolve(if (colon < 0) "" else qName.substring(0, colon))
        for (i in attributeNames.indices) {
            val attr = attributeNames[i]
            val c = attr.indexOf(':')
            if (c < 0) {
                attributeLocalNames.add(attr)
                attributeNamespaces.add(if (attr == "xmlns") XMLNS_NAMESPACE else "")
            } else {
                val prefix = attr.substring(0, c)
                attributeLocalNames.add(attr.substring(c + 1))
                attributeNamespaces.add(if (prefix == "xmlns") XMLNS_NAMESPACE else resolve(prefix))
            }
        }
        openNames.add(qName)
        openLocalNames.add(localName)
        openNamespaces.add(namespace)
        depth++
        rootSeen = true
    }

    private fun closeElement() {
        depth--
        openNames.removeAt(depth)
        openLocalNames.removeAt(depth)
        openNamespaces.removeAt(depth)
        val mark = namespaceMarks[depth]
        while (prefixes.size > mark) {
            prefixes.removeAt(prefixes.lastIndex)
            uris.removeAt(uris.lastIndex)
        }
    }

    private fun resolve(prefix: String): String {
        if (prefix == "xml") return XML_NAMESPACE
        for (i in prefixes.lastIndex downTo 0) if (prefixes[i] == prefix) return uris[i]
        return ""
    }

    private fun readEndTag(): XmlEvent {
        val start = pos
        val nameEnd = scanName(start + 2)
        val close = skipSpace(nameEnd)
        if (close >= end) fail("Unterminated end tag", start)
        if (nameEnd == start + 2 || src[close] != '>') fail("Invalid end tag", start)
        if (depth == 0) fail("Unexpected end tag </${src.substring(start + 2, nameEnd)}>", start)
        val expected = openNames[depth - 1]
        if (nameEnd - start - 2 != expected.length || !src.startsWith(expected, start + 2)) {
            fail("Mismatched end tag </${src.substring(start + 2, nameEnd)}>, expected </$expected>", start)
        }
        pos = close + 1
        name = expected
        localName = openLocalNames[depth - 1]
        namespace = openNamespaces[depth - 1]
        closePending = true
        return emit(XmlEvent.END_ELEMENT)
    }
}

sealed interface XmlNode

class XmlText(val text: String) : XmlNode

data class XmlAttribute(val name: String, val localName: String, val namespace: String, val value: String)

class XmlElement(
    val name: String,
    val localName: String,
    val namespace: String,
    val attributes: List<XmlAttribute>,
    val children: List<XmlNode>,
) : XmlNode {
    private var elementCache: List<XmlElement>? = null

    val elements: List<XmlElement>
        get() = elementCache ?: children.filterIsInstance<XmlElement>().also { elementCache = it }

    fun attr(localName: String, namespace: String? = null): String? {
        for (a in attributes) if (a.localName == localName && (namespace == null || a.namespace == namespace)) return a.value
        return null
    }

    fun child(localName: String, namespace: String? = null): XmlElement? {
        for (node in children) if (node is XmlElement && node.matches(localName, namespace)) return node
        return null
    }

    fun children(localName: String, namespace: String? = null): List<XmlElement> = elements.filter { it.matches(localName, namespace) }

    fun text(): String {
        val single = children.singleOrNull()
        if (single is XmlText) return single.text
        val sb = StringBuilder()
        val stack = ArrayList<XmlNode>()
        stack.add(this)
        while (stack.isNotEmpty()) {
            when (val node = stack.removeAt(stack.lastIndex)) {
                is XmlText -> sb.append(node.text)
                is XmlElement -> for (i in node.children.lastIndex downTo 0) stack.add(node.children[i])
            }
        }
        return sb.toString()
    }

    private fun matches(localName: String, namespace: String?): Boolean = this.localName == localName && (namespace == null || this.namespace == namespace)
}

private class PendingElement(val name: String, val localName: String, val namespace: String, val attributes: List<XmlAttribute>) {
    var children: ArrayList<XmlNode>? = null

    fun add(node: XmlNode) {
        (children ?: ArrayList<XmlNode>().also { children = it }).add(node)
    }

    fun build(): XmlElement = XmlElement(name, localName, namespace, attributes, children ?: emptyList())
}

fun parseXml(text: String): XmlElement {
    val reader = XmlReader(text)
    val stack = ArrayList<PendingElement>()
    var root: XmlElement? = null
    while (true) {
        when (reader.next()) {
            XmlEvent.START_ELEMENT -> {
                val count = reader.attributeCount
                val attributes = if (count == 0) {
                    emptyList()
                } else {
                    List(count) {
                        XmlAttribute(reader.attributeName(it), reader.attributeLocalName(it), reader.attributeNamespace(it), reader.attributeValue(it))
                    }
                }
                stack.add(PendingElement(reader.name, reader.localName, reader.namespace, attributes))
            }
            XmlEvent.TEXT -> stack[stack.lastIndex].add(XmlText(reader.text))
            XmlEvent.END_ELEMENT -> {
                val element = stack.removeAt(stack.lastIndex).build()
                if (stack.isEmpty()) root = element else stack[stack.lastIndex].add(element)
            }
            XmlEvent.END_DOCUMENT -> return root ?: throw XmlException("No root element")
        }
    }
}

// CR goes out as &#13; so it survives parsing. Other C0 controls, U+FFFE and U+FFFF are dropped,
// XML 1.0 cannot carry them
class XmlBuilder(declaration: Boolean = true) {
    private val out = StringBuilder()
    private val open = ArrayList<String>()
    private var startTagOpen = false

    init {
        if (declaration) out.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
    }

    fun start(name: String, vararg attributes: Pair<String, String>): XmlBuilder {
        closeStartTag()
        out.append('<').append(name)
        for ((key, value) in attributes) {
            out.append(' ').append(key).append("=\"")
            escape(value, attribute = true)
            out.append('"')
        }
        startTagOpen = true
        open.add(name)
        return this
    }

    fun text(value: String): XmlBuilder {
        closeStartTag()
        escape(value, attribute = false)
        return this
    }

    fun end(): XmlBuilder {
        check(open.isNotEmpty()) { "No open element to end" }
        val name = open.removeAt(open.lastIndex)
        if (startTagOpen) {
            out.append("/>")
            startTagOpen = false
        } else {
            out.append("</").append(name).append('>')
        }
        return this
    }

    fun leaf(name: String, text: String? = null, vararg attributes: Pair<String, String>): XmlBuilder {
        start(name, *attributes)
        if (text != null) this.text(text)
        return end()
    }

    override fun toString(): String {
        if (open.isEmpty()) return out.toString()
        val sb = StringBuilder(out)
        for (i in open.lastIndex downTo 0) {
            if (i == open.lastIndex && startTagOpen) sb.append("/>") else sb.append("</").append(open[i]).append('>')
        }
        return sb.toString()
    }

    private fun closeStartTag() {
        if (startTagOpen) {
            out.append('>')
            startTagOpen = false
        }
    }

    private fun escape(value: String, attribute: Boolean) {
        var run = 0
        for (i in value.indices) {
            val c = value[i]
            val replacement = when {
                c == '&' -> "&amp;"
                c == '<' -> "&lt;"
                c == '>' && !attribute -> "&gt;"
                c == '"' && attribute -> "&quot;"
                c == '\t' && attribute -> "&#9;"
                c == '\n' && attribute -> "&#10;"
                c == '\r' -> "&#13;"
                c < ' ' && c != '\t' && c != '\n' || c == '\uFFFE' || c == '\uFFFF' -> ""
                else -> continue
            }
            out.appendRange(value, run, i).append(replacement)
            run = i + 1
        }
        out.appendRange(value, run, value.length)
    }
}

object TextDecoding {
    private val CP1251 = table(
        "ЂЃ‚ѓ„…†‡€‰Љ‹ЊЌЋЏ",
        "ђ‘’“”•–—\uFFFD™љ›њќћџ",
        "\u00A0ЎўЈ¤Ґ¦§Ё©Є«¬\u00AD®Ї",
        "°±Ііґµ¶·ё№є»јЅѕї",
        "АБВГДЕЖЗИЙКЛМНОП",
        "РСТУФХЦЧШЩЪЫЬЭЮЯ",
        "абвгдежзийклмноп",
        "рстуфхцчшщъыьэюя",
    )

    private val KOI8_R = table(
        "─│┌┐└┘├┤┬┴┼▀▄█▌▐",
        "░▒▓⌠■∙√≈≤≥\u00A0⌡°²·÷",
        "═║╒ё╓╔╕╖╗╘╙╚╛╜╝╞",
        "╟╠╡Ё╢╣╤╥╦╧╨╩╪╫╬©",
        "юабцдефгхийклмно",
        "пярстужвьызшэщчъ",
        "ЮАБЦДЕФГХИЙКЛМНО",
        "ПЯРСТУЖВЬЫЗШЭЩЧЪ",
    )

    private val CP1252 = table(
        "€\uFFFD‚ƒ„…†‡ˆ‰Š‹Œ\uFFFDŽ\uFFFD",
        "\uFFFD‘’“”•–—˜™š›œ\uFFFDžŸ",
    )

    private val CP866 = table(
        "АБВГДЕЖЗИЙКЛМНОП",
        "РСТУФХЦЧШЩЪЫЬЭЮЯ",
        "абвгдежзийклмноп",
        "░▒▓│┤╡╢╖╕╣║╗╝╜╛┐",
        "└┴┬├─┼╞╟╚╔╩╦╠═╬╧",
        "╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀",
        "рстуфхцчшщъыьэюя",
        "ЁёЄєЇїЎў°∙·√№¤■\u00A0",
    )

    private val CP437 = table(
        "ÇüéâäàåçêëèïîìÄÅ",
        "ÉæÆôöòûùÿÖÜ¢£¥₧ƒ",
        "áíóúñÑªº¿⌐¬½¼¡«»",
        "░▒▓│┤╡╢╖╕╣║╗╝╜╛┐",
        "└┴┬├─┼╞╟╚╔╩╦╠═╬╧",
        "╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀",
        "αßΓπΣσµτΦΘΩδ∞φε∩",
        "≡±≥≤⌠⌡÷≈°∙·√ⁿ²■\u00A0",
    )

    private val CP1250 = table(
        "€\uFFFD‚\uFFFD„…†‡\uFFFD‰Š‹ŚŤŽŹ",
        "\uFFFD‘’“”•–—\uFFFD™š›śťžź",
        "\u00A0ˇ˘Ł¤Ą¦§¨©Ş«¬\u00AD®Ż",
        "°±˛ł´µ¶·¸ąş»Ľ˝ľż",
        "ŔÁÂĂÄĹĆÇČÉĘËĚÍÎĎ",
        "ĐŃŇÓÔŐÖ×ŘŮÚŰÜÝŢß",
        "ŕáâăäĺćçčéęëěíîď",
        "đńňóôőö÷řůúűüýţ˙",
    )

    private val LATIN2 = table(
        "\u00A0Ą˘Ł¤ĽŚ§¨ŠŞŤŹ\u00ADŽŻ",
        "°ą˛ł´ľśˇ¸šşťź˝žż",
        "ŔÁÂĂÄĹĆÇČÉĘËĚÍÎĎ",
        "ĐŃŇÓÔŐÖ×ŘŮÚŰÜÝŢß",
        "ŕáâăäĺćçčéęëěíîď",
        "đńňóôőö÷řůúűüýţ˙",
        from = 0xA0,
    )

    private val LATIN1 = table()

    private val DECLARED_ENCODING = Regex("""encoding\s*=\s*["']([A-Za-z0-9._:-]+)["']""")

    fun decode(bytes: ByteArray): String {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) return bytes.decodeToString(3, bytes.size)
        if (startsWith(bytes, 0xFF, 0xFE)) return utf16(bytes, 2, littleEndian = true)
        if (startsWith(bytes, 0xFE, 0xFF)) return utf16(bytes, 2, littleEndian = false)
        if (startsWith(bytes, 0x3C, 0x00, 0x3F, 0x00)) return utf16(bytes, 0, littleEndian = true)
        if (startsWith(bytes, 0x00, 0x3C, 0x00, 0x3F)) return utf16(bytes, 0, littleEndian = false)
        val declared = declaredEncoding(bytes)
        if (declared == "utf8") return bytes.decodeToString()
        tableFor(declared)?.let { return singleByte(bytes, it) }
        if (isUtf8(bytes)) return bytes.decodeToString()
        return singleByte(bytes, if (looksLikeKoi8(bytes)) KOI8_R else CP1251)
    }

    fun decode(bytes: ByteArray, charset: String): String {
        val key = normalize(charset)
        return when (key) {
            "utf8" -> bytes.decodeToString(if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) 3 else 0, bytes.size)
            "utf16le" -> utf16(bytes, if (startsWith(bytes, 0xFF, 0xFE)) 2 else 0, littleEndian = true)
            "utf16be" -> utf16(bytes, if (startsWith(bytes, 0xFE, 0xFF)) 2 else 0, littleEndian = false)
            "utf16" -> when {
                startsWith(bytes, 0xFF, 0xFE) -> utf16(bytes, 2, littleEndian = true)
                startsWith(bytes, 0xFE, 0xFF) -> utf16(bytes, 2, littleEndian = false)
                else -> utf16(bytes, 0, littleEndian = false)
            }
            else -> singleByte(bytes, tableFor(key) ?: throw IllegalArgumentException("Unsupported charset $charset"))
        }
    }

    private fun normalize(charset: String): String = charset.lowercase().filter { it.isLetterOrDigit() }

    private fun tableFor(key: String?): CharArray? = when (key) {
        "windows1251", "cp1251", "win1251", "xcp1251" -> CP1251
        "koi8r", "koi8" -> KOI8_R
        "windows1250", "cp1250", "xcp1250" -> CP1250
        "windows1252", "cp1252" -> CP1252
        "iso88592", "latin2", "l2" -> LATIN2
        "iso88591", "latin1", "l1" -> LATIN1
        "cp866", "ibm866" -> CP866
        "ibm437", "cp437" -> CP437
        else -> null
    }

    private fun declaredEncoding(bytes: ByteArray): String? {
        if (!startsWith(bytes, 0x3C, 0x3F, 0x78, 0x6D, 0x6C)) return null
        val head = CharArray(min(bytes.size, 1024)) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()
        val close = head.indexOf("?>")
        if (close < 0) return null
        return DECLARED_ENCODING.find(head.substring(0, close))?.groupValues?.get(1)?.let(::normalize)
    }

    private fun isUtf8(bytes: ByteArray): Boolean {
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                i++
                continue
            }
            val extra = when (b) {
                in 0xC2..0xDF -> 1
                in 0xE0..0xEF -> 2
                in 0xF0..0xF4 -> 3
                else -> return false
            }
            var cp = b and (0x3F ushr extra)
            for (k in 1..extra) {
                if (i + k == n) return true
                val c = bytes[i + k].toInt() and 0xFF
                if (c and 0xC0 != 0x80) return false
                cp = (cp shl 6) or (c and 0x3F)
            }
            val min = when (extra) {
                1 -> 0x80
                2 -> 0x800
                else -> 0x10000
            }
            if (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return false
            i += extra + 1
        }
        return true
    }

    private fun looksLikeKoi8(bytes: ByteArray): Boolean {
        var koi8Lowercase = 0
        var cp1251Lowercase = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v >= 0xE0) cp1251Lowercase++ else if (v >= 0xC0) koi8Lowercase++
        }
        return koi8Lowercase > cp1251Lowercase
    }

    private fun startsWith(bytes: ByteArray, vararg prefix: Int): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) if (bytes[i].toInt() and 0xFF != prefix[i]) return false
        return true
    }

    private fun singleByte(bytes: ByteArray, table: CharArray): String = CharArray(bytes.size) { table[bytes[it].toInt() and 0xFF] }.concatToString()

    private fun utf16(bytes: ByteArray, from: Int, littleEndian: Boolean): String {
        val units = (bytes.size - from) / 2
        val odd = (bytes.size - from) % 2 != 0
        val chars = CharArray(units + if (odd) 1 else 0)
        for (i in 0 until units) {
            val a = bytes[from + 2 * i].toInt() and 0xFF
            val b = bytes[from + 2 * i + 1].toInt() and 0xFF
            chars[i] = (if (littleEndian) (b shl 8) or a else (a shl 8) or b).toChar()
        }
        if (odd) chars[units] = '\uFFFD'
        return chars.concatToString()
    }

    private fun table(vararg rows: String, from: Int = 0x80): CharArray {
        val table = CharArray(256) { it.toChar() }
        var i = from
        for (row in rows) for (c in row) table[i++] = c
        return table
    }
}
