package com.vasmarfas.card.tools.documents.pdf

internal const val ABSENT = 0
internal const val IN_FILE = 1
internal const val COMPRESSED = 2
internal const val FREE = 3

internal class IntList(capacity: Int = 16) {
    private var items = IntArray(maxOf(4, capacity))
    var size = 0
        private set

    fun add(value: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    operator fun get(index: Int): Int = items[index]
}

internal class XrefTable {
    var size = 0
        private set
    private var types = ByteArray(64)
    private var fields = IntArray(64)
    private var extras = IntArray(64)

    fun type(number: Int): Int = if (number in 0 until size) types[number].toInt() else ABSENT

    fun field(number: Int): Int = fields[number]

    fun extra(number: Int): Int = extras[number]

    fun offer(number: Int, type: Int, field: Int, extra: Int) {
        if (type(number) == ABSENT) set(number, type, field, extra)
    }

    fun set(number: Int, type: Int, field: Int, extra: Int) {
        if (number >= types.size) {
            var capacity = types.size
            while (capacity <= number) capacity *= 2
            types = types.copyOf(capacity)
            fields = fields.copyOf(capacity)
            extras = extras.copyOf(capacity)
        }
        types[number] = type.toByte()
        fields[number] = field
        extras[number] = extra
        if (number >= size) size = number + 1
    }
}

internal class ObjectStream(val data: ByteArray, val numbers: IntArray, val offsets: IntArray, val first: Int)

internal class ScannedObjects {
    val numbers = IntList(1024)
    val generations = IntList(1024)
    val offsets = IntList(1024)
    val flags = IntList(1024)
    val trailers = IntList()

    val count: Int get() = numbers.size

    companion object {
        const val OBJECT_STREAM = 1
        const val XREF_STREAM = 2
        const val CATALOG = 4
    }
}

internal fun scanObjects(data: ByteArray): ScannedObjects {
    val result = ScannedObjects()
    val n = data.size
    var i = 0
    while (i + 3 <= n) {
        val c = data[i].toInt()
        if (c == 'o'.code && data[i + 1].toInt() == 'b'.code && data[i + 2].toInt() == 'j'.code && (i + 3 == n || !isRegular(data[i + 3].toInt()))) {
            val start = headerStart(data, i)
            if (start >= 0) {
                var p = start
                var number = 0L
                while (data[p].toInt() in 48..57) number = number * 10 + (data[p++] - 48)
                while (isWhitespace(data[p].toInt())) p++
                var generation = 0
                while (data[p].toInt() in 48..57) generation = generation * 10 + (data[p++] - 48)
                if (number <= Int.MAX_VALUE) {
                    result.numbers.add(number.toInt())
                    result.generations.add(generation)
                    result.offsets.add(start)
                    result.flags.add(classify(data, i + 3))
                }
            }
            i += 3
            continue
        }
        if (c == 't'.code && matchesWord(data, i, "trailer") && (i == 0 || !isRegular(data[i - 1].toInt()))) {
            result.trailers.add(i + 7)
            i += 7
            continue
        }
        if (c == 's'.code && opensStream(data, i)) {
            val end = indexOf(data, "endstream", i + 6)
            if (end > 0) {
                i = end + 9
                continue
            }
        }
        i++
    }
    return result
}

private fun opensStream(data: ByteArray, at: Int): Boolean {
    if (!matchesWord(data, at, "stream")) return false
    var after = at + 6
    while (after < data.size && data[after].toInt() == ' '.code) after++
    if (after >= data.size || (data[after].toInt() != '\r'.code && data[after].toInt() != '\n'.code)) return false
    var before = at - 1
    while (before > 0 && isWhitespace(data[before].toInt())) before--
    return before > 0 && data[before].toInt() == '>'.code && data[before - 1].toInt() == '>'.code
}

private fun indexOf(data: ByteArray, word: String, from: Int): Int {
    var i = from
    while (i + word.length <= data.size) {
        var k = 0
        while (k < word.length && data[i + k].toInt() == word[k].code) k++
        if (k == word.length) return i
        i++
    }
    return -1
}

private fun headerStart(data: ByteArray, objAt: Int): Int {
    var j = objAt - 1
    while (j >= 0 && isWhitespace(data[j].toInt())) j--
    val generationEnd = j
    while (j >= 0 && data[j].toInt() in 48..57) j--
    if (j == generationEnd || generationEnd - j > 5 || j < 0 || !isWhitespace(data[j].toInt())) return -1
    while (j >= 0 && isWhitespace(data[j].toInt())) j--
    val numberEnd = j
    while (j >= 0 && data[j].toInt() in 48..57) j--
    if (j == numberEnd || numberEnd - j > 10) return -1
    if (j >= 0 && isRegular(data[j].toInt())) return -1
    return j + 1
}

private fun matchesWord(data: ByteArray, at: Int, word: String): Boolean {
    if (at + word.length > data.size) return false
    for (k in word.indices) if (data[at + k].toInt() != word[k].code) return false
    return at + word.length == data.size || !isRegular(data[at + word.length].toInt())
}

private fun classify(data: ByteArray, from: Int): Int {
    val limit = minOf(data.size, from + 2048)
    var flags = 0
    var k = from
    while (k < limit) {
        when (data[k].toInt()) {
            '/'.code -> when {
                matchesWord(data, k, "/ObjStm") -> flags = flags or ScannedObjects.OBJECT_STREAM
                matchesWord(data, k, "/XRef") -> flags = flags or ScannedObjects.XREF_STREAM
                matchesWord(data, k, "/Catalog") -> flags = flags or ScannedObjects.CATALOG
            }
            's'.code -> if (matchesWord(data, k, "stream")) return flags
            'e'.code -> if (matchesWord(data, k, "endobj")) return flags
        }
        k++
    }
    return flags
}
