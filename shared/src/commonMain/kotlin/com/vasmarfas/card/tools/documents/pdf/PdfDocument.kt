package com.vasmarfas.card.tools.documents.pdf

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class PdfRect(val left: Double, val bottom: Double, val right: Double, val top: Double) {
    val width: Double get() = right - left
    val height: Double get() = top - bottom
}

class PdfPage internal constructor(
    val document: PdfDocument,
    val index: Int,
    val ref: PdfRef?,
    val dict: PdfDict,
    internal val resourcesEntry: PdfObject?,
    val mediaBox: PdfRect,
    val cropBox: PdfRect,
    val rotation: Int,
) {
    val resources: PdfDict get() = document.resolve(resourcesEntry) as? PdfDict ?: PdfDict()

    val width: Double get() = if (rotation % 180 == 0) cropBox.width else cropBox.height

    val height: Double get() = if (rotation % 180 == 0) cropBox.height else cropBox.width
}

class PdfDocument private constructor(private val data: ByteArray) {
    internal val names = NameCache()
    internal val fontCache = HashMap<PdfDict, PdfFont>()
    private val xref = XrefTable()
    private var cache = arrayOfNulls<PdfObject>(0)
    private val objectStreams = HashMap<Int, ObjectStream>()
    private val loading = IntArray(48)
    private var loadingDepth = 0
    private val maxObjectNumber = (data.size.toLong() * 4).coerceIn(1L shl 16, 1L shl 24).toInt()
    private var headerOffset = 0
    private var security: PdfSecurity? = null
    private var encryptNumber = -1
    private val treeNodes = HashSet<Int>()

    var trailer: PdfDict = PdfDict()
        private set

    var version: String = "1.4"
        private set

    var repaired: Boolean = false
        private set

    private var opened = false

    val encrypted: Boolean get() = security != null

    val catalog: PdfDict get() = resolve(trailer["Root"]) as? PdfDict ?: PdfDict()

    val pages: List<PdfPage> by lazy(LazyThreadSafetyMode.NONE) { collectPages() }

    val pageCount: Int get() = pages.size

    val info: Map<String, String> by lazy(LazyThreadSafetyMode.NONE) { readInfo() }

    fun page(index: Int): PdfPage = pages.getOrNull(index) ?: throw IndexOutOfBoundsException("Page $index, document has ${pages.size}")

    // missing objects and reference cycles give PdfNull
    fun resolve(obj: PdfObject?): PdfObject {
        var current = obj ?: return PdfNull
        var hops = 0
        while (current is PdfRef) {
            if (++hops > 32) return PdfNull
            current = load(current.number)
        }
        return current
    }

    fun decodedStream(stream: PdfStream): ByteArray = PdfFilters.decode(stream.data, stream.dict, this)

    internal fun isPageTreeNode(number: Int): Boolean {
        pages
        return number in treeNodes
    }

    private fun open(password: String) {
        headerOffset = indexOf("%PDF-", 0, min(data.size, 1024))
        if (headerOffset < 0) throw PdfException("No PDF header")
        version = readVersion(headerOffset + 5)
        val start = findStartXref()
        if (start < 0 || !readXrefChain(start) || resolve(trailer["Root"]) !is PdfDict) repair()
        if (resolve(trailer["Root"]) !is PdfDict) throw PdfException("Document catalog not found")
        setUpEncryption(password)
        val declared = catalog.name("Version")
        if (declared != null && declared.length == 3 && declared[1] == '.' && declared > version) version = declared
        opened = true
    }

    private fun readVersion(at: Int): String {
        val sb = StringBuilder()
        var i = at
        while (i < data.size && sb.length < 4 && (data[i].toInt() in 48..57 || data[i].toInt() == '.'.code)) sb.append(data[i++].toInt().toChar())
        return if (sb.length >= 3) sb.toString() else "1.4"
    }

    private fun indexOf(word: String, from: Int, until: Int): Int = PdfLexer(data, names, 0, until).indexOf(word, from)

    private fun lastIndexOf(word: String, from: Int): Int {
        var i = data.size - word.length
        outer@ while (i >= from) {
            for (k in word.indices) {
                if (data[i + k].toInt() != word[k].code) {
                    i--
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    private fun findStartXref(): Int {
        var at = lastIndexOf("startxref", max(0, data.size - 4096))
        if (at < 0) at = lastIndexOf("startxref", 0)
        if (at < 0) return -1
        val lexer = PdfLexer(data, names, at + 9)
        lexer.skipWhitespace()
        val offset = lexer.readDigits()
        return if (offset in 1 until data.size) offset.toInt() else -1
    }

    private fun readXrefChain(start: Int): Boolean {
        val sections = ArrayList<PdfDict>()
        val seen = HashSet<Int>()
        var offset = start
        while (seen.add(offset)) {
            val section = readSection(offset) ?: break
            sections.add(section)
            offset = section.int("Prev") ?: break
            if (offset !in 1 until data.size) break
        }
        if (sections.isEmpty()) return false
        val merged = PdfDict()
        for (section in sections) {
            for ((key, value) in section.entries) {
                if (key !in xrefKeys && key !in merged) merged[key] = value
            }
        }
        trailer = merged
        return true
    }

    private fun readSection(offset: Int): PdfDict? {
        readSectionAt(offset)?.let { return it }
        if (headerOffset > 0) readSectionAt(offset + headerOffset)?.let { return it }
        val nearby = indexOf("xref", max(0, offset - 64), min(data.size, offset + 68))
        return if (nearby >= 0 && nearby != offset) readSectionAt(nearby) else null
    }

    private fun readSectionAt(offset: Int): PdfDict? {
        if (offset !in 0 until data.size) return null
        val lexer = PdfLexer(data, names, offset)
        lexer.skipWhitespace()
        if (lexer.atKeyword("xref")) return readTable(lexer)
        val stream = PdfParser(data, names).readIndirect(offset)?.value as? PdfStream ?: return null
        if (stream.dict.name("Type") != "XRef" && stream.dict["W"] == null) return null
        return if (readXrefStream(stream)) stream.dict else null
    }

    private fun readTable(lexer: PdfLexer): PdfDict? {
        lexer.pos += 4
        val free = IntList()
        while (true) {
            lexer.skipWhitespace()
            if (lexer.peek() !in 48..57) break
            var number = lexer.readDigits()
            lexer.skipWhitespace()
            if (lexer.readDigits() < 0) break
            var index = 0
            while (true) {
                lexer.skipWhitespace()
                val save = lexer.pos
                val offset = lexer.readDigits()
                if (offset < 0) break
                lexer.skipWhitespace()
                val generation = lexer.readDigits()
                lexer.skipWhitespace()
                val kind = lexer.peek()
                if (generation < 0 || (kind != 'n'.code && kind != 'f'.code)) {
                    lexer.pos = save
                    break
                }
                lexer.pos++
                if (index == 0 && number == 1L && kind == 'f'.code && offset == 0L && generation == 65535L) number = 0
                if (number in 1..maxObjectNumber.toLong()) {
                    if (kind == 'f'.code) {
                        free.add(number.toInt())
                    } else if (offset in 1 until data.size) {
                        xref.offer(number.toInt(), IN_FILE, offset.toInt(), generation.coerceAtMost(65535).toInt())
                    }
                }
                number++
                index++
            }
        }
        lexer.skipWhitespace()
        if (!lexer.atKeyword("trailer")) {
            val at = lexer.indexOf("trailer", lexer.pos)
            if (at < 0) return null
            lexer.pos = at
        }
        val trailer = PdfParser(data, names, lexer.pos + 7).parseObject() as? PdfDict ?: return null
        val streamOffset = trailer.int("XRefStm")
        if (streamOffset != null) {
            val stream = PdfParser(data, names).readIndirect(streamOffset)?.value as? PdfStream
            if (stream != null) readXrefStream(stream)
        }
        for (i in 0 until free.size) xref.offer(free[i], FREE, 0, 0)
        return trailer
    }

    private fun readXrefStream(stream: PdfStream): Boolean {
        val dict = stream.dict
        val widths = dict.array("W")?.numbers() ?: return false
        if (widths.size < 3) return false
        val w0 = widths[0].toInt()
        val w1 = widths[1].toInt()
        val w2 = widths[2].toInt()
        if (w0 !in 0..4 || w1 !in 0..8 || w2 !in 0..8 || w0 + w1 + w2 == 0) return false
        val entry = w0 + w1 + w2
        val bytes = try {
            PdfFilters.decode(stream.data, dict, null)
        } catch (_: PdfException) {
            return false
        }
        val index = dict.array("Index")?.numbers() ?: doubleArrayOf(0.0, (dict.int("Size") ?: (bytes.size / entry)).toDouble())
        var p = 0
        var i = 0
        while (i + 1 < index.size) {
            val start = index[i].toLong()
            val count = index[i + 1].toLong()
            var k = 0L
            while (k < count && p + entry <= bytes.size) {
                val type = if (w0 == 0) 1L else field(bytes, p, w0)
                val second = field(bytes, p + w0, w1)
                val third = field(bytes, p + w0 + w1, w2)
                p += entry
                val number = start + k
                k++
                if (number !in 1..maxObjectNumber.toLong()) continue
                when (type) {
                    0L -> xref.offer(number.toInt(), FREE, 0, 0)
                    1L -> if (second in 1 until data.size) xref.offer(number.toInt(), IN_FILE, second.toInt(), third.toInt())
                    2L -> if (second in 1..maxObjectNumber.toLong()) xref.offer(number.toInt(), COMPRESSED, second.toInt(), third.toInt())
                }
            }
            i += 2
        }
        return true
    }

    private fun field(bytes: ByteArray, at: Int, width: Int): Long {
        var value = 0L
        for (k in 0 until width) value = (value shl 8) or (bytes[at + k].toLong() and 0xFF)
        return value
    }

    private fun load(number: Int): PdfObject {
        if (number <= 0 || number > maxObjectNumber) return PdfNull
        if (number < cache.size) cache[number]?.let { return it }
        for (i in 0 until loadingDepth) if (loading[i] == number) return PdfNull
        if (loadingDepth == loading.size) return PdfNull
        loading[loadingDepth++] = number
        val obj = try {
            locate(number)
        } finally {
            loadingDepth--
        }
        if (number >= xref.size) return obj
        if (number >= cache.size) cache = cache.copyOf(max(number + 1, max(xref.size, cache.size * 2)))
        cache[number] = obj
        return obj
    }

    private fun locate(number: Int): PdfObject {
        locateOnce(number)?.let { return it }
        if (!repaired && number < (trailer.int("Size") ?: Int.MAX_VALUE)) {
            repair()
            locateOnce(number)?.let { return it }
        }
        return PdfNull
    }

    private fun locateOnce(number: Int): PdfObject? = when (xref.type(number)) {
        IN_FILE -> readObjectAt(xref.field(number), number)
        COMPRESSED -> readCompressed(number, xref.field(number), xref.extra(number))
        FREE -> PdfNull
        else -> null
    }

    private fun readObjectAt(offset: Int, number: Int): PdfObject? {
        val parser = PdfParser(data, names, lengthOf = { resolve(it) })
        var found = parser.readIndirect(offset)
        if ((found == null || found.number != number) && headerOffset > 0) found = parser.readIndirect(offset + headerOffset)
        if (found == null || found.number != number) return null
        val sec = security
        return if (sec == null || number == encryptNumber) found.value else sec.decrypt(found.value, number, found.generation)
    }

    private fun readCompressed(number: Int, streamNumber: Int, index: Int): PdfObject? {
        if (streamNumber == number) return null
        val objects = objectStream(streamNumber) ?: return null
        var i = index
        if (i !in objects.numbers.indices || objects.numbers[i] != number) i = objects.numbers.indexOf(number)
        if (i < 0) return null
        val start = objects.first + objects.offsets[i]
        if (start !in 0 until objects.data.size) return null
        return PdfParser(objects.data, names, start).parseObject()
    }

    private fun objectStream(number: Int): ObjectStream? {
        objectStreams[number]?.let { return it }
        val stream = load(number) as? PdfStream ?: return null
        val decoded = try {
            decodedStream(stream)
        } catch (_: PdfException) {
            return null
        }
        val count = (stream.dict.int("N", this) ?: return null).coerceIn(0, decoded.size / 2)
        val first = stream.dict.int("First", this) ?: return null
        val lexer = PdfLexer(decoded, names, 0, min(first, decoded.size).coerceAtLeast(0))
        val numbers = IntList(count)
        val offsets = IntList(count)
        for (k in 0 until count) {
            lexer.skipWhitespace()
            val objectNumber = lexer.readDigits()
            lexer.skipWhitespace()
            val offset = lexer.readDigits()
            if (objectNumber < 0 || offset < 0 || objectNumber > Int.MAX_VALUE || offset > Int.MAX_VALUE) break
            numbers.add(objectNumber.toInt())
            offsets.add(offset.toInt())
        }
        val result = ObjectStream(decoded, IntArray(numbers.size) { numbers[it] }, IntArray(offsets.size) { offsets[it] }, first)
        objectStreams[number] = result
        return result
    }

    private fun repair() {
        if (repaired) return
        repaired = true
        val scan = scanObjects(data)
        var highest = 0
        for (k in 0 until scan.count) highest = max(highest, scan.numbers[k])
        val done = BooleanArray(min(highest, maxObjectNumber) + 1)
        if (opened) {
            for (k in 0 until scan.count) {
                val number = scan.numbers[k]
                if (number in 1..maxObjectNumber && xref.type(number) == IN_FILE && leadsTo(xref.field(number), scan.offsets[k])) done[number] = true
            }
        }
        for (k in scan.count - 1 downTo 0) {
            val number = scan.numbers[k]
            if (number <= 0 || number > maxObjectNumber || done[number]) continue
            done[number] = true
            val type = xref.type(number)
            if (type == COMPRESSED || (opened && type == FREE)) continue
            xref.set(number, IN_FILE, scan.offsets[k], scan.generations[k])
            if (number < cache.size && cache[number] == PdfNull) cache[number] = null
        }
        for (k in scan.count - 1 downTo 0) {
            if (scan.flags[k] and ScannedObjects.OBJECT_STREAM == 0) continue
            val streamNumber = scan.numbers[k]
            val objects = objectStream(streamNumber) ?: continue
            for (i in objects.numbers.indices) {
                val number = objects.numbers[i]
                if (number in 1..maxObjectNumber && number != streamNumber && xref.type(number) == ABSENT) xref.set(number, COMPRESSED, streamNumber, i)
            }
        }
        rebuildTrailer(scan)
    }

    private fun leadsTo(offset: Int, header: Int): Boolean =
        intArrayOf(offset, offset + headerOffset).any { at -> PdfLexer(data, names, at).also { it.skipWhitespace() }.pos == header }

    private fun rebuildTrailer(scan: ScannedObjects) {
        val found = PdfDict()
        val parser = PdfParser(data, names)
        var t = 0
        var k = 0
        while (t < scan.trailers.size || k < scan.count) {
            val takeTrailer = k >= scan.count || (t < scan.trailers.size && scan.trailers[t] < scan.offsets[k])
            val dict = if (takeTrailer) {
                parser.lexer.pos = scan.trailers[t++]
                parser.parseObject() as? PdfDict
            } else {
                val index = k++
                if (scan.flags[index] and ScannedObjects.XREF_STREAM != 0) (load(scan.numbers[index]) as? PdfStream)?.dict else null
            }
            if (dict != null) for (key in trailerKeys) dict[key]?.let { found[key] = it }
        }
        val merged = PdfDict()
        merged.entries.putAll(found.entries)
        merged.entries.putAll(trailer.entries)
        trailer = merged
        if (resolve(merged["Root"]) is PdfDict) return
        found["Root"]?.let { if (resolve(it) is PdfDict) merged["Root"] = it }
        if (resolve(merged["Root"]) is PdfDict) return
        for (pass in 0..1) {
            for (i in scan.count - 1 downTo 0) {
                if (pass == 0 && scan.flags[i] and ScannedObjects.CATALOG == 0) continue
                val number = scan.numbers[i]
                val dict = load(number) as? PdfDict ?: continue
                if (dict.name("Type") == "Catalog" || (pass == 1 && dict["Pages"] != null && dict["Type"] == null)) {
                    merged["Root"] = PdfRef(number, scan.generations[i])
                    return
                }
            }
        }
    }

    private fun setUpEncryption(password: String) {
        val entry = trailer["Encrypt"] ?: return
        val dict = resolve(entry) as? PdfDict ?: throw PdfEncryptedException("Encryption dictionary is missing")
        encryptNumber = (entry as? PdfRef)?.number ?: -1
        val id = (trailer.array("ID", this)?.resolved(0, this) as? PdfString)?.bytes ?: ByteArray(0)
        security = PdfSecurity.open(dict, id, password, this)
        val kept = if (encryptNumber in cache.indices) cache[encryptNumber] else null
        cache = arrayOfNulls(cache.size)
        if (kept != null) cache[encryptNumber] = kept
        objectStreams.clear()
    }

    private class Inherited(val resources: PdfObject?, val mediaBox: PdfObject?, val cropBox: PdfObject?, val rotate: PdfObject?)

    private fun collectPages(): List<PdfPage> {
        val result = ArrayList<PdfPage>()
        val stack = ArrayDeque<Pair<PdfObject, Inherited>>()
        catalog["Pages"]?.let { stack.addLast(it to Inherited(null, null, null, null)) }
        while (stack.isNotEmpty()) {
            val (item, inherited) = stack.removeLast()
            val ref = item as? PdfRef
            if (ref != null && !treeNodes.add(ref.number)) continue
            val node = resolve(item) as? PdfDict ?: continue
            val here = Inherited(
                node["Resources"] ?: inherited.resources,
                node["MediaBox"] ?: inherited.mediaBox,
                node["CropBox"] ?: inherited.cropBox,
                node["Rotate"] ?: inherited.rotate,
            )
            val kids = node.array("Kids", this)
            val type = node.name("Type", this)
            if (type == "Pages" || (type != "Page" && kids != null)) {
                if (kids != null) for (i in kids.size - 1 downTo 0) stack.addLast(kids[i] to here)
            } else {
                result.add(makePage(result.size, ref, node, here))
            }
        }
        if (result.isEmpty()) {
            for (number in 1 until xref.size) {
                val type = xref.type(number)
                if (type != IN_FILE && type != COMPRESSED) continue
                val dict = load(number) as? PdfDict ?: continue
                if (dict.name("Type", this) != "Page") continue
                treeNodes.add(number)
                result.add(makePage(result.size, PdfRef(number, 0), dict, Inherited(dict["Resources"], dict["MediaBox"], dict["CropBox"], dict["Rotate"])))
            }
        }
        return result
    }

    private fun makePage(index: Int, ref: PdfRef?, dict: PdfDict, inherited: Inherited): PdfPage {
        val media = rect(inherited.mediaBox) ?: PdfRect(0.0, 0.0, 612.0, 792.0)
        val crop = rect(inherited.cropBox)?.let { box ->
            val clipped = PdfRect(max(box.left, media.left), max(box.bottom, media.bottom), min(box.right, media.right), min(box.top, media.top))
            if (clipped.width > 0 && clipped.height > 0) clipped else null
        } ?: media
        val rotate = resolve(inherited.rotate).asDouble() ?: 0.0
        val quarter = ((rotate / 90).roundToInt() % 4 + 4) % 4
        return PdfPage(this, index, ref, dict, inherited.resources, media, crop, quarter * 90)
    }

    private fun rect(obj: PdfObject?): PdfRect? {
        val numbers = (resolve(obj) as? PdfArray)?.numbers(this) ?: return null
        if (numbers.size < 4) return null
        val box = PdfRect(min(numbers[0], numbers[2]), min(numbers[1], numbers[3]), max(numbers[0], numbers[2]), max(numbers[1], numbers[3]))
        return if (box.width > 0 && box.height > 0) box else null
    }

    private fun readInfo(): Map<String, String> {
        val dict = trailer.dict("Info", this) ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for ((key, value) in dict.entries) {
            when (val v = resolve(value)) {
                is PdfString -> result[key] = v.text()
                is PdfName -> result[key] = v.name
                else -> Unit
            }
        }
        return result
    }

    companion object {
        private val xrefKeys = setOf("Prev", "XRefStm", "Type", "W", "Index", "Length", "Filter", "DecodeParms", "DL")
        private val trailerKeys = listOf("Root", "Info", "ID", "Encrypt", "Size")

        // password is tried as the user password, then as the owner one
        fun parse(bytes: ByteArray, password: String = ""): PdfDocument = PdfDocument(bytes).also { it.open(password) }
    }
}
