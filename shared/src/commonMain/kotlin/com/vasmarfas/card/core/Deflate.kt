package com.vasmarfas.card.core

import kotlin.math.max
import kotlin.math.min

class DeflateException(message: String) : Exception(message)

private const val MAX_BITS = 15
private const val MAX_CODE_LENGTH_BITS = 7
private const val LITERAL_CODES = 286
private const val DISTANCE_CODES = 30
private const val CODE_LENGTH_CODES = 19
private const val END_OF_BLOCK = 256
private const val WINDOW_SIZE = 1 shl 15
private const val MIN_MATCH = 3
private const val MAX_MATCH = 258
private const val TOO_FAR = 4096
private const val SYMBOL_BUFFER = 1 shl 14
private const val STORED_BLOCK_MAX = 0xFFFF
private const val LITERAL_FAST_BITS = 10
private const val DISTANCE_FAST_BITS = 8
private const val HASH_MULTIPLIER = -0x61C88647
private const val MAX_ARRAY_SIZE = Int.MAX_VALUE - 8
private const val MAX_EXPANSION = 1032L

private val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
private val DISTANCE_BASE = intArrayOf(
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
)
private val DISTANCE_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

private val GOOD_MATCH = intArrayOf(0, 4, 4, 4, 4, 8, 8, 8, 32, 32)
private val MAX_LAZY = intArrayOf(0, 4, 5, 6, 4, 16, 16, 32, 128, 258)
private val NICE_MATCH = intArrayOf(0, 8, 16, 32, 16, 32, 128, 128, 258, 258)
private val MAX_CHAIN = intArrayOf(0, 4, 8, 32, 16, 32, 128, 256, 1024, 4096)

private val LENGTH_CODE = IntArray(MAX_MATCH + 1).also { table ->
    for (code in 0 until 28) {
        for (k in 0 until (1 shl LENGTH_EXTRA[code])) table[LENGTH_BASE[code] + k] = code
    }
    table[MAX_MATCH] = 28
}

private val DISTANCE_CODE = IntArray(512).also { table ->
    for (code in 0 until DISTANCE_CODES) {
        for (k in 0 until (1 shl DISTANCE_EXTRA[code])) {
            val d = DISTANCE_BASE[code] - 1 + k
            if (d < 256) table[d] = code else table[256 + (d ushr 7)] = code
        }
    }
}

private val FIXED_LITERAL_LENGTHS = IntArray(288) {
    when {
        it < 144 -> 8
        it < 256 -> 9
        it < 280 -> 7
        else -> 8
    }
}
private val FIXED_LITERAL_CODES = IntArray(288).also { buildCodes(FIXED_LITERAL_LENGTHS, 288, it) }
private val FIXED_DISTANCE_LENGTHS = IntArray(32) { 5 }
private val FIXED_DISTANCE_CODES = IntArray(32).also { buildCodes(FIXED_DISTANCE_LENGTHS, 32, it) }

private val FIXED_LITERAL_TABLE = HuffmanTable(LITERAL_FAST_BITS, 288).also { it.build(FIXED_LITERAL_LENGTHS, 0, 288, allowIncomplete = false) }
private val FIXED_DISTANCE_TABLE = HuffmanTable(DISTANCE_FAST_BITS, 32).also { it.build(FIXED_DISTANCE_LENGTHS, 0, 32, allowIncomplete = false) }

private fun reverseBits(value: Int, bits: Int): Int {
    var v = value
    v = ((v and 0x5555) shl 1) or ((v ushr 1) and 0x5555)
    v = ((v and 0x3333) shl 2) or ((v ushr 2) and 0x3333)
    v = ((v and 0x0F0F) shl 4) or ((v ushr 4) and 0x0F0F)
    v = ((v and 0x00FF) shl 8) or ((v ushr 8) and 0x00FF)
    return v ushr (16 - bits)
}

private fun distanceCode(distance: Int): Int {
    val d = distance - 1
    return if (d < 256) DISTANCE_CODE[d] else DISTANCE_CODE[256 + (d ushr 7)]
}

object Inflate {
    fun inflate(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, sizeHint: Int = 0): ByteArray {
        require(offset >= 0 && length >= 0 && length <= data.size - offset) { "Range $offset+$length is out of bounds for ${data.size} bytes" }
        return Inflater(data, offset, offset + length, sizeHint, MAX_ARRAY_SIZE).run()
    }

    internal fun inflateUpTo(data: ByteArray, offset: Int, length: Int, maxSize: Int): ByteArray =
        Inflater(data, offset, offset + length, maxSize, maxSize).run()

    fun inflatePartial(data: ByteArray, offset: Int = 0): ByteArray =
        Inflater(data, offset, data.size, (data.size - offset) * 4, MAX_ARRAY_SIZE, partial = true).run()
}

private class HuffmanTable(private val fastBits: Int, maxSymbols: Int) {
    val fast = IntArray(1 shl fastBits)
    val fastMask = (1 shl fastBits) - 1
    private val counts = IntArray(MAX_BITS + 1)
    private val firstCode = IntArray(MAX_BITS + 1)
    private val firstIndex = IntArray(MAX_BITS + 1)
    private val nextIndex = IntArray(MAX_BITS + 1)
    private val limit = IntArray(MAX_BITS + 1)
    private val symbols = IntArray(maxSymbols)

    fun build(lengths: IntArray, offset: Int, n: Int, allowIncomplete: Boolean) {
        counts.fill(0)
        for (i in offset until offset + n) counts[lengths[i]]++
        var left = 1
        for (len in 1..MAX_BITS) {
            left = (left shl 1) - counts[len]
            if (left < 0) throw DeflateException("Over-subscribed Huffman code")
        }
        if (left > 0 && !(allowIncomplete && counts[0] + counts[1] == n)) throw DeflateException("Incomplete Huffman code")
        var code = 0
        var index = 0
        for (len in 1..MAX_BITS) {
            firstCode[len] = code
            firstIndex[len] = index
            nextIndex[len] = index
            code += counts[len]
            index += counts[len]
            limit[len] = code shl (16 - len)
            code = code shl 1
        }
        for (symbol in 0 until n) {
            val len = lengths[offset + symbol]
            if (len != 0) symbols[nextIndex[len]++] = symbol
        }
        fast.fill(0)
        for (len in 1..fastBits) {
            val step = 1 shl len
            for (k in 0 until counts[len]) {
                val entry = (symbols[firstIndex[len] + k] shl 4) or len
                var slot = reverseBits(firstCode[len] + k, len)
                while (slot < fast.size) {
                    fast[slot] = entry
                    slot += step
                }
            }
        }
    }

    fun decodeSlow(next16Bits: Int): Int {
        val code = reverseBits(next16Bits, 16)
        for (len in fastBits + 1..MAX_BITS) {
            if (code < limit[len]) return (symbols[firstIndex[len] + (code ushr (16 - len)) - firstCode[len]] shl 4) or len
        }
        return -1
    }
}

private class Inflater(
    private val src: ByteArray,
    private var pos: Int,
    private val end: Int,
    sizeHint: Int,
    private val maxSize: Int,
    private val partial: Boolean = false,
) {
    private var out: ByteArray
    private var outPos = 0
    private var bitBuffer = 0L
    private var bitCount = 0
    private val lengths = IntArray(LITERAL_CODES + DISTANCE_CODES)
    private val literalTable = HuffmanTable(LITERAL_FAST_BITS, LITERAL_CODES)
    private val distanceTable = HuffmanTable(DISTANCE_FAST_BITS, DISTANCE_CODES)
    private val codeLengthTable = HuffmanTable(MAX_CODE_LENGTH_BITS, CODE_LENGTH_CODES)

    init {
        val bound = (end - pos) * MAX_EXPANSION + 1024
        val wanted = if (sizeHint > 0) sizeHint.toLong() else (end - pos) * 4L + 64
        out = ByteArray(minOf(wanted, bound, maxSize.toLong()).toInt())
    }

    fun run(): ByteArray {
        try {
            while (true) {
                val header = bits(3)
                when (header ushr 1) {
                    0 -> storedBlock()
                    1 -> huffmanBlock(FIXED_LITERAL_TABLE, FIXED_DISTANCE_TABLE)
                    2 -> {
                        readDynamicTables()
                        huffmanBlock(literalTable, distanceTable)
                    }
                    else -> throw DeflateException("Invalid block type")
                }
                if (header and 1 != 0) break
            }
        } catch (e: DeflateException) {
            if (!partial || outPos == 0) throw e
        }
        return if (outPos == out.size) out else out.copyOf(outPos)
    }

    private fun refill() {
        while (bitCount <= 56 && pos < end) {
            bitBuffer = bitBuffer or ((src[pos].toLong() and 0xFF) shl bitCount)
            pos++
            bitCount += 8
        }
    }

    private fun bits(n: Int): Int {
        if (bitCount < n) {
            refill()
            if (bitCount < n) throw DeflateException("Unexpected end of compressed data")
        }
        val value = bitBuffer.toInt() and ((1 shl n) - 1)
        bitBuffer = bitBuffer ushr n
        bitCount -= n
        return value
    }

    private fun decode(table: HuffmanTable): Int {
        var entry = table.fast[bitBuffer.toInt() and table.fastMask]
        if (entry == 0) {
            entry = table.decodeSlow(bitBuffer.toInt() and 0xFFFF)
            if (entry < 0) throw DeflateException("Invalid Huffman code")
        }
        val len = entry and 0xF
        if (len > bitCount) throw DeflateException("Unexpected end of compressed data")
        bitBuffer = bitBuffer ushr len
        bitCount -= len
        return entry ushr 4
    }

    private fun grow(needed: Int) {
        if (needed < 0 || needed > maxSize) {
            throw DeflateException(if (maxSize == MAX_ARRAY_SIZE) "Decompressed data is too large" else "Decompressed data exceeds $maxSize bytes")
        }
        out = out.copyOf(max(needed, min(out.size * 2L, maxSize.toLong()).toInt()))
    }

    private fun storedBlock() {
        val partial = bitCount and 7
        bitBuffer = bitBuffer ushr partial
        bitCount -= partial
        pos -= bitCount ushr 3
        bitBuffer = 0
        bitCount = 0
        if (end - pos < 4) throw DeflateException("Unexpected end of compressed data")
        val len = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
        val complement = (src[pos + 2].toInt() and 0xFF) or ((src[pos + 3].toInt() and 0xFF) shl 8)
        if (len != complement.inv() and 0xFFFF) throw DeflateException("Stored block length does not match its complement")
        pos += 4
        if (end - pos < len) throw DeflateException("Unexpected end of compressed data")
        if (outPos + len > out.size) grow(outPos + len)
        src.copyInto(out, outPos, pos, pos + len)
        outPos += len
        pos += len
    }

    private fun readDynamicTables() {
        val literalCount = bits(5) + 257
        val distanceCount = bits(5) + 1
        val codeLengthCount = bits(4) + 4
        if (literalCount > LITERAL_CODES || distanceCount > DISTANCE_CODES) throw DeflateException("Too many length or distance codes")
        lengths.fill(0, 0, CODE_LENGTH_CODES)
        for (i in 0 until codeLengthCount) lengths[CODE_LENGTH_ORDER[i]] = bits(3)
        codeLengthTable.build(lengths, 0, CODE_LENGTH_CODES, allowIncomplete = false)
        val total = literalCount + distanceCount
        var i = 0
        while (i < total) {
            if (bitCount < 16) refill()
            val symbol = decode(codeLengthTable)
            if (symbol < 16) {
                lengths[i++] = symbol
                continue
            }
            val value: Int
            val repeat: Int
            when (symbol) {
                16 -> {
                    if (i == 0) throw DeflateException("Repeat code without a previous length")
                    value = lengths[i - 1]
                    repeat = 3 + bits(2)
                }
                17 -> {
                    value = 0
                    repeat = 3 + bits(3)
                }
                else -> {
                    value = 0
                    repeat = 11 + bits(7)
                }
            }
            if (i + repeat > total) throw DeflateException("Code length repeat overflows the table")
            lengths.fill(value, i, i + repeat)
            i += repeat
        }
        if (lengths[END_OF_BLOCK] == 0) throw DeflateException("Missing end-of-block code")
        literalTable.build(lengths, 0, literalCount, allowIncomplete = true)
        distanceTable.build(lengths, literalCount, distanceCount, allowIncomplete = true)
    }

    private fun huffmanBlock(literals: HuffmanTable, distances: HuffmanTable) {
        while (true) {
            if (bitCount < 48) refill()
            val symbol = decode(literals)
            if (symbol < 256) {
                if (outPos == out.size) grow(outPos + 1)
                out[outPos++] = symbol.toByte()
                continue
            }
            if (symbol == END_OF_BLOCK) return
            val lengthCode = symbol - 257
            if (lengthCode >= 29) throw DeflateException("Invalid length code")
            val length = LENGTH_BASE[lengthCode] + bits(LENGTH_EXTRA[lengthCode])
            val distanceCode = decode(distances)
            if (distanceCode >= DISTANCE_CODES) throw DeflateException("Invalid distance code")
            val distance = DISTANCE_BASE[distanceCode] + bits(DISTANCE_EXTRA[distanceCode])
            if (distance > outPos) throw DeflateException("Distance $distance reaches before the start of the output")
            if (outPos + length > out.size) grow(outPos + length)
            val from = outPos - distance
            if (distance >= length) {
                out.copyInto(out, outPos, from, from + length)
            } else {
                for (k in 0 until length) out[outPos + k] = out[from + k]
            }
            outPos += length
        }
    }
}

object Deflate {
    fun deflate(data: ByteArray, level: Int = 6): ByteArray {
        require(level in 0..9) { "Compression level must be in 0..9, was $level" }
        if (level == 0) {
            val out = BitWriter(data.size + (data.size / STORED_BLOCK_MAX + 1) * 5)
            out.storedBlocks(data, 0, data.size, last = true)
            return out.toByteArray()
        }
        return Compressor(data, level).compress()
    }
}

private class BitWriter(capacity: Int) {
    private var buf = ByteArray(max(capacity, 64))
    private var size = 0
    private var bits = 0L
    var bitCount = 0
        private set

    fun write(value: Int, count: Int) {
        bits = bits or (value.toLong() shl bitCount)
        bitCount += count
        if (bitCount >= 32) {
            if (size + 4 > buf.size) grow(4)
            buf[size] = bits.toByte()
            buf[size + 1] = (bits ushr 8).toByte()
            buf[size + 2] = (bits ushr 16).toByte()
            buf[size + 3] = (bits ushr 24).toByte()
            size += 4
            bits = bits ushr 32
            bitCount -= 32
        }
    }

    fun alignToByte() {
        while (bitCount > 0) {
            if (size == buf.size) grow(1)
            buf[size++] = bits.toByte()
            bits = bits ushr 8
            bitCount -= 8
        }
        bits = 0
        bitCount = 0
    }

    fun storedBlocks(data: ByteArray, from: Int, length: Int, last: Boolean) {
        val end = from + length
        var p = from
        do {
            val chunk = min(STORED_BLOCK_MAX, end - p)
            write(if (last && p + chunk == end) 1 else 0, 3)
            alignToByte()
            if (size + chunk + 4 > buf.size) grow(chunk + 4)
            buf[size++] = chunk.toByte()
            buf[size++] = (chunk ushr 8).toByte()
            buf[size++] = chunk.inv().toByte()
            buf[size++] = (chunk.inv() ushr 8).toByte()
            data.copyInto(buf, size, p, p + chunk)
            size += chunk
            p += chunk
        } while (p < end)
    }

    fun toByteArray(): ByteArray {
        alignToByte()
        return buf.copyOf(size)
    }

    private fun grow(extra: Int) {
        buf = buf.copyOf(max(size + extra, buf.size * 2))
    }
}

private class Compressor(private val data: ByteArray, level: Int) {
    private val goodMatch = GOOD_MATCH[level]
    private val maxLazy = MAX_LAZY[level]
    private val niceMatch = NICE_MATCH[level]
    private val maxChain = MAX_CHAIN[level]
    private val lazyMatching = level >= 4
    private val hashBits = min(15, 32 - (data.size or 0xFF).countLeadingZeroBits())
    private val hashShift = 32 - hashBits
    private val windowMask = (1 shl hashBits) - 1
    private val head = IntArray(1 shl hashBits).also { it.fill(-1) }
    private val prev = IntArray(1 shl hashBits)
    private var matchStart = 0

    private val symbols = IntArray(SYMBOL_BUFFER)
    private var symbolCount = 0
    private var blockStart = 0
    private var blockLength = 0
    private val literalFreq = IntArray(LITERAL_CODES)
    private val distanceFreq = IntArray(DISTANCE_CODES)
    private val out = BitWriter(data.size / 2 + 64)

    private val literalLengths = IntArray(LITERAL_CODES)
    private val literalCodes = IntArray(LITERAL_CODES)
    private val distanceLengths = IntArray(DISTANCE_CODES)
    private val distanceCodes = IntArray(DISTANCE_CODES)
    private val allLengths = IntArray(LITERAL_CODES + DISTANCE_CODES)
    private val runs = IntArray(LITERAL_CODES + DISTANCE_CODES)
    private val codeLengthFreq = IntArray(CODE_LENGTH_CODES)
    private val codeLengthLengths = IntArray(CODE_LENGTH_CODES)
    private val codeLengthCodes = IntArray(CODE_LENGTH_CODES)

    fun compress(): ByteArray {
        if (lazyMatching) compressLazy() else compressFast()
        flushBlock(last = true)
        return out.toByteArray()
    }

    private fun insert(pos: Int): Int {
        val d = data
        val key = (d[pos].toInt() and 0xFF) or ((d[pos + 1].toInt() and 0xFF) shl 8) or ((d[pos + 2].toInt() and 0xFF) shl 16)
        val hash = (key * HASH_MULTIPLIER) ushr hashShift
        val candidate = head[hash]
        head[hash] = pos
        prev[pos and windowMask] = candidate
        return candidate
    }

    private fun longestMatch(pos: Int, candidate: Int, prevLength: Int): Int {
        val d = data
        val maxLength = min(MAX_MATCH, d.size - pos)
        if (prevLength >= maxLength) return 0
        val nice = min(niceMatch, maxLength)
        val limit = max(pos - WINDOW_SIZE, -1)
        var chain = if (prevLength >= goodMatch) maxChain ushr 2 else maxChain
        var best = prevLength
        var found = 0
        val first = d[pos]
        val second = d[pos + 1]
        var scanEnd = d[pos + best]
        var scanEndPrev = d[pos + best - 1]
        var cand = candidate
        while (cand > limit) {
            if (d[cand + best] == scanEnd && d[cand + best - 1] == scanEndPrev && d[cand] == first && d[cand + 1] == second) {
                var length = 2
                while (length < maxLength && d[cand + length] == d[pos + length]) length++
                if (length > best) {
                    best = length
                    found = length
                    matchStart = cand
                    if (length >= nice) break
                    scanEnd = d[pos + length]
                    scanEndPrev = d[pos + length - 1]
                }
            }
            if (--chain == 0) break
            cand = prev[cand and windowMask]
        }
        return found
    }

    private fun compressFast() {
        val n = data.size
        val lastInsert = n - MIN_MATCH
        var pos = 0
        while (pos < n) {
            var length = 0
            if (pos <= lastInsert) {
                val candidate = insert(pos)
                if (candidate >= 0) length = longestMatch(pos, candidate, MIN_MATCH - 1)
            }
            if (length >= MIN_MATCH) {
                match(length, pos - matchStart)
                val end = pos + length
                if (length <= maxLazy) {
                    var p = pos + 1
                    while (p < end) {
                        if (p <= lastInsert) insert(p)
                        p++
                    }
                }
                pos = end
            } else {
                literal(data[pos].toInt() and 0xFF)
                pos++
            }
        }
    }

    private fun compressLazy() {
        val n = data.size
        val lastInsert = n - MIN_MATCH
        var pos = 0
        var matchLength = MIN_MATCH - 1
        var matchPos = 0
        var pending = false
        while (pos < n) {
            val prevLength = matchLength
            val prevPos = matchPos
            matchLength = MIN_MATCH - 1
            if (pos <= lastInsert) {
                val candidate = insert(pos)
                if (candidate >= 0 && prevLength < maxLazy) {
                    val length = longestMatch(pos, candidate, prevLength)
                    if (length > MIN_MATCH || (length == MIN_MATCH && pos - matchStart <= TOO_FAR)) {
                        matchLength = length
                        matchPos = matchStart
                    }
                }
            }
            if (prevLength >= MIN_MATCH && matchLength <= prevLength) {
                match(prevLength, pos - 1 - prevPos)
                val end = pos - 1 + prevLength
                var p = pos + 1
                while (p < end) {
                    if (p <= lastInsert) insert(p)
                    p++
                }
                pos = end
                pending = false
                matchLength = MIN_MATCH - 1
            } else {
                if (pending) literal(data[pos - 1].toInt() and 0xFF)
                pending = true
                pos++
            }
        }
        if (pending) literal(data[pos - 1].toInt() and 0xFF)
    }

    private fun literal(value: Int) {
        symbols[symbolCount++] = value
        literalFreq[value]++
        blockLength++
        if (symbolCount == SYMBOL_BUFFER) flushBlock(last = false)
    }

    private fun match(length: Int, distance: Int) {
        symbols[symbolCount++] = (distance shl 9) or length
        literalFreq[257 + LENGTH_CODE[length]]++
        distanceFreq[distanceCode(distance)]++
        blockLength += length
        if (symbolCount == SYMBOL_BUFFER) flushBlock(last = false)
    }

    private fun flushBlock(last: Boolean) {
        literalFreq[END_OF_BLOCK] = 1
        buildLengths(literalFreq, LITERAL_CODES, MAX_BITS, literalLengths)
        buildLengths(distanceFreq, DISTANCE_CODES, MAX_BITS, distanceLengths)
        var literalCount = LITERAL_CODES
        while (literalCount > 257 && literalLengths[literalCount - 1] == 0) literalCount--
        var distanceCount = DISTANCE_CODES
        while (distanceCount > 1 && distanceLengths[distanceCount - 1] == 0) distanceCount--
        literalLengths.copyInto(allLengths, 0, 0, literalCount)
        distanceLengths.copyInto(allLengths, literalCount, 0, distanceCount)
        codeLengthFreq.fill(0)
        val runCount = encodeRuns(literalCount + distanceCount)
        buildLengths(codeLengthFreq, CODE_LENGTH_CODES, MAX_CODE_LENGTH_BITS, codeLengthLengths)
        var codeLengthCount = CODE_LENGTH_CODES
        while (codeLengthCount > 4 && codeLengthLengths[CODE_LENGTH_ORDER[codeLengthCount - 1]] == 0) codeLengthCount--

        var extraBits = 0
        for (code in 0 until 29) extraBits += literalFreq[257 + code] * LENGTH_EXTRA[code]
        for (code in 0 until DISTANCE_CODES) extraBits += distanceFreq[code] * DISTANCE_EXTRA[code]
        var dynamicBits = 3 + 14 + codeLengthCount * 3 + extraBits + codeLengthFreq[16] * 2 + codeLengthFreq[17] * 3 + codeLengthFreq[18] * 7
        for (s in 0 until CODE_LENGTH_CODES) dynamicBits += codeLengthFreq[s] * codeLengthLengths[s]
        var fixedBits = 3 + extraBits
        for (s in 0 until LITERAL_CODES) {
            dynamicBits += literalFreq[s] * literalLengths[s]
            fixedBits += literalFreq[s] * FIXED_LITERAL_LENGTHS[s]
        }
        for (code in 0 until DISTANCE_CODES) {
            dynamicBits += distanceFreq[code] * distanceLengths[code]
            fixedBits += distanceFreq[code] * 5
        }

        when {
            storedBits(blockLength) <= min(fixedBits, dynamicBits) -> out.storedBlocks(data, blockStart, blockLength, last)
            fixedBits <= dynamicBits -> {
                out.write(if (last) 3 else 2, 3)
                writeSymbols(FIXED_LITERAL_CODES, FIXED_LITERAL_LENGTHS, FIXED_DISTANCE_CODES, FIXED_DISTANCE_LENGTHS)
            }
            else -> {
                buildCodes(literalLengths, LITERAL_CODES, literalCodes)
                buildCodes(distanceLengths, DISTANCE_CODES, distanceCodes)
                buildCodes(codeLengthLengths, CODE_LENGTH_CODES, codeLengthCodes)
                out.write(if (last) 5 else 4, 3)
                out.write(literalCount - 257, 5)
                out.write(distanceCount - 1, 5)
                out.write(codeLengthCount - 4, 4)
                for (i in 0 until codeLengthCount) out.write(codeLengthLengths[CODE_LENGTH_ORDER[i]], 3)
                for (i in 0 until runCount) {
                    val run = runs[i]
                    val symbol = run and 0x1F
                    out.write(codeLengthCodes[symbol], codeLengthLengths[symbol])
                    when (symbol) {
                        16 -> out.write(run ushr 5, 2)
                        17 -> out.write(run ushr 5, 3)
                        18 -> out.write(run ushr 5, 7)
                    }
                }
                writeSymbols(literalCodes, literalLengths, distanceCodes, distanceLengths)
            }
        }
        literalFreq.fill(0)
        distanceFreq.fill(0)
        symbolCount = 0
        blockStart += blockLength
        blockLength = 0
    }

    private fun storedBits(length: Int): Int {
        val blocks = max(1, (length + STORED_BLOCK_MAX - 1) / STORED_BLOCK_MAX)
        val firstPadding = (8 - ((out.bitCount + 3) and 7)) and 7
        return firstPadding + blocks * 35 + (blocks - 1) * 5 + length * 8
    }

    private fun encodeRuns(count: Int): Int {
        var k = 0
        var i = 0
        while (i < count) {
            val value = allLengths[i]
            var run = 1
            while (i + run < count && allLengths[i + run] == value) run++
            i += run
            if (value == 0) {
                while (run >= 11) {
                    val r = min(run, 138)
                    runs[k++] = 18 or ((r - 11) shl 5)
                    codeLengthFreq[18]++
                    run -= r
                }
                if (run >= 3) {
                    runs[k++] = 17 or ((run - 3) shl 5)
                    codeLengthFreq[17]++
                    run = 0
                }
            } else {
                runs[k++] = value
                codeLengthFreq[value]++
                run--
                while (run >= 3) {
                    val r = min(run, 6)
                    runs[k++] = 16 or ((r - 3) shl 5)
                    codeLengthFreq[16]++
                    run -= r
                }
            }
            repeat(run) {
                runs[k++] = value
                codeLengthFreq[value]++
            }
        }
        return k
    }

    private fun writeSymbols(literalCodes: IntArray, literalLengths: IntArray, distanceCodes: IntArray, distanceLengths: IntArray) {
        for (i in 0 until symbolCount) {
            val symbol = symbols[i]
            if (symbol < 256) {
                out.write(literalCodes[symbol], literalLengths[symbol])
                continue
            }
            val length = symbol and 0x1FF
            val distance = symbol ushr 9
            val lengthCode = LENGTH_CODE[length]
            out.write(literalCodes[257 + lengthCode], literalLengths[257 + lengthCode])
            val lengthExtra = LENGTH_EXTRA[lengthCode]
            if (lengthExtra != 0) out.write(length - LENGTH_BASE[lengthCode], lengthExtra)
            val distanceCode = distanceCode(distance)
            out.write(distanceCodes[distanceCode], distanceLengths[distanceCode])
            val distanceExtra = DISTANCE_EXTRA[distanceCode]
            if (distanceExtra != 0) out.write(distance - DISTANCE_BASE[distanceCode], distanceExtra)
        }
        out.write(literalCodes[END_OF_BLOCK], literalLengths[END_OF_BLOCK])
    }
}

internal fun buildLengths(freq: IntArray, n: Int, maxBits: Int, lengths: IntArray) {
    lengths.fill(0, 0, n)
    val keys = IntArray(n)
    var used = 0
    for (s in 0 until n) if (freq[s] > 0) keys[used++] = (freq[s] shl 9) or s
    if (used < 2) {
        val symbol = if (used == 1) keys[0] and 0x1FF else 0
        lengths[symbol] = 1
        lengths[if (symbol == 0) 1 else 0] = 1
        return
    }
    keys.sort(0, used)
    val nodes = 2 * used - 1
    val weight = IntArray(nodes)
    val parent = IntArray(nodes)
    for (i in 0 until used) weight[i] = keys[i] ushr 9
    var leaf = 0
    var inner = used
    for (next in used until nodes) {
        val a = if (leaf < used && (inner >= next || weight[leaf] <= weight[inner])) leaf++ else inner++
        val b = if (leaf < used && (inner >= next || weight[leaf] <= weight[inner])) leaf++ else inner++
        weight[next] = weight[a] + weight[b]
        parent[a] = next
        parent[b] = next
    }
    val depth = IntArray(nodes)
    val depthCount = IntArray(used + 1)
    var maxDepth = 0
    for (i in nodes - 2 downTo 0) {
        depth[i] = depth[parent[i]] + 1
        if (i < used) {
            depthCount[depth[i]]++
            maxDepth = max(maxDepth, depth[i])
        }
    }
    for (len in maxDepth downTo maxBits + 1) {
        while (depthCount[len] > 0) {
            var shorter = len - 2
            while (depthCount[shorter] == 0) shorter--
            depthCount[len] -= 2
            depthCount[len - 1]++
            depthCount[shorter + 1] += 2
            depthCount[shorter]--
        }
    }
    var k = 0
    for (len in min(maxDepth, maxBits) downTo 1) {
        repeat(depthCount[len]) { lengths[keys[k++] and 0x1FF] = len }
    }
}

private fun buildCodes(lengths: IntArray, n: Int, codes: IntArray) {
    val counts = IntArray(MAX_BITS + 1)
    for (i in 0 until n) counts[lengths[i]]++
    counts[0] = 0
    val next = IntArray(MAX_BITS + 1)
    var code = 0
    for (len in 1..MAX_BITS) {
        code = (code + counts[len - 1]) shl 1
        next[len] = code
    }
    for (i in 0 until n) {
        val len = lengths[i]
        if (len != 0) codes[i] = reverseBits(next[len]++, len)
    }
}

private fun adler32(data: ByteArray): Int {
    var a = 1L
    var b = 0L
    var i = 0
    while (i < data.size) {
        val end = min(i + 5552, data.size)
        while (i < end) {
            a += data[i].toInt() and 0xFF
            b += a
            i++
        }
        a %= 65521
        b %= 65521
    }
    return ((b shl 16) or a).toInt()
}

object Zlib {
    fun compress(data: ByteArray, level: Int = 6): ByteArray {
        val body = Deflate.deflate(data, level)
        val levelFlag = when {
            level < 2 -> 0
            level < 6 -> 1
            level == 6 -> 2
            else -> 3
        }
        val cmf = 0x78
        var flg = levelFlag shl 6
        flg += 31 - (cmf * 256 + flg) % 31
        val out = ByteArray(body.size + 6)
        out[0] = cmf.toByte()
        out[1] = flg.toByte()
        body.copyInto(out, 2)
        val adler = adler32(data)
        val p = body.size + 2
        out[p] = (adler ushr 24).toByte()
        out[p + 1] = (adler ushr 16).toByte()
        out[p + 2] = (adler ushr 8).toByte()
        out[p + 3] = adler.toByte()
        return out
    }

    // RFC 1950. A missing or wrong Adler-32 and trailing garbage are tolerated, PDF streams have both
    fun decompress(data: ByteArray): ByteArray {
        if (data.size < 2) throw DeflateException("Truncated zlib header")
        val cmf = data[0].toInt() and 0xFF
        val flg = data[1].toInt() and 0xFF
        if (cmf and 0x0F != 8 || cmf ushr 4 > 7 || (cmf * 256 + flg) % 31 != 0) throw DeflateException("Invalid zlib header")
        if (flg and 0x20 != 0) throw DeflateException("Preset dictionaries are not supported")
        return Inflate.inflate(data, 2)
    }
}
