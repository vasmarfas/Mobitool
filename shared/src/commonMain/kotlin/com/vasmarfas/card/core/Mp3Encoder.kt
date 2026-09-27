package com.vasmarfas.card.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val GRANULE = 576
private const val MAX_GRANULE_BITS = 4095
private const val RING = 64
private const val ENCODER_NAME = "Mobitool"

// decoder delay of ISO/IEC 11172-3, the convention behind the LAME delay field
private const val DECODER_DELAY = 529

// a 72-sample block is an attack when its high-frequency energy is this many times the mean of the 8 before
private const val ATTACK_RATIO = 10.0
private const val ATTACK_FLOOR = 7.2e-5

private val SQRT_HALF = sqrt(0.5)

private val LOWPASS_KBPS = intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160)
private val LOWPASS_HZ = intArrayOf(2800, 5500, 8000, 11000, 13000, 15000, 16000, 17000, 18000, 19000, 19500, 20000, 20500)

private val CRC16 = IntArray(256) { byte ->
    var crc = byte
    repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
    crc
}

// the first frame is a silent placeholder, infoFrame() overwrites it once finish() knows the totals
class Mp3Encoder(private val sampleRate: Int, private val channels: Int, private val bitrateKbps: Int) {
    init {
        require(channels == 1 || channels == 2) { "MP3 supports 1 or 2 channels, got $channels" }
        require(sampleRate in sampleRates) { "Unsupported MP3 sample rate $sampleRate Hz" }
        require(bitrateKbps in bitrates(sampleRate)) { "Unsupported MP3 bitrate $bitrateKbps kbps at $sampleRate Hz" }
    }

    private val mpeg1 = sampleRate >= 32000
    private val granules = if (mpeg1) 2 else 1
    private val frameSamples = GRANULE * granules
    private val rateIndex = (if (mpeg1) Mp3Tables.sampleRatesMpeg1 else Mp3Tables.sampleRatesMpeg2).indexOf(sampleRate)
    private val bitrateIndex = (if (mpeg1) Mp3Tables.bitratesMpeg1 else Mp3Tables.bitratesMpeg2).indexOf(bitrateKbps)
    private val sideInfoSize = if (mpeg1) (if (channels == 1) 17 else 32) else (if (channels == 1) 9 else 17)
    private val frameUnits = (if (mpeg1) 144_000 else 72_000) * bitrateKbps
    private val frameBytes = frameUnits / sampleRate
    private val paddingStep = frameUnits % sampleRate
    private var paddingBalance = 0

    // main_data_begin has 9 bits (8 in LSF), and LAME assumes the decoder buffers at most one
    // 320 kbps 32 kHz frame
    private val reservoirLimit = min(if (mpeg1) 511 else 255, max(0, 1440 - frameBytes))
    private val longEdges = if (mpeg1) Mp3Tables.sfbLongMpeg1[rateIndex] else Mp3Tables.sfbLongMpeg2[rateIndex]
    private val shortEdges = if (mpeg1) Mp3Tables.sfbShortMpeg1[rateIndex] else Mp3Tables.sfbShortMpeg2[rateIndex]
    private val lowpass = lowpassHz()
    private val bandGains = DoubleArray(32) { band ->
        val width = sampleRate / 64.0
        val center = (band + 0.5) * width
        when {
            center <= lowpass - width -> 1.0
            center >= lowpass -> 0.0
            else -> 0.5 * (1 + cos(PI * (center - lowpass + width) / width))
        }
    }

    private val filterbanks = Array(channels) { Mp3Filterbank(bandGains, shortEdges) }
    private val detectors = Array(channels) { AttackDetector() }
    private val masking = Mp3Masking(sampleRate, longEdges, shortEdges)
    private val quantizer = Mp3Quantizer(mpeg1, longEdges, masking.shortGroups)
    private val huffman = Mp3Huffman()
    private val incoming = Array(channels) { DoubleArray(GRANULE) }
    private val subbands = Array(channels) { Array(4) { DoubleArray(GRANULE) } }
    private val shortNeeded = BooleanArray(4)
    private val blockTypes = IntArray(granules)
    private var previousType = NORMAL_BLOCK
    private var analyzed = 0
    private val lines = Array(granules) { Array(channels) { DoubleArray(GRANULE) } }
    private val spare = Array(granules) { Array(channels) { DoubleArray(GRANULE) } }
    private val energy = Array(granules) { Array(channels) { DoubleArray(39) } }
    private val xmin = Array(granules) { Array(channels) { DoubleArray(39) } }
    private val demand = Array(granules) { DoubleArray(channels) }
    private val side = Array(granules) { Array(channels) { Mp3Granule() } }
    private val jointEnergy = Array(channels) { DoubleArray(39) }
    private val jointXmin = DoubleArray(39)
    private var midSide = false
    private var filled = 0
    private var samples = 0L
    private var frames = 0
    private var finished = false

    private val tagLayout = tagLayout()
    private val tagSize = if (tagLayout == NO_TAG) 0 else frameBytes
    private var out = ByteArray(8192)
    private var size = 0
    private val slotStart = IntArray(RING)
    private val slotEnd = IntArray(RING)
    private var writeFrame = 0
    private var writePos = 0
    private var reservoir = 0
    private val mainData = Mp3BitWriter(4096)
    private val sideData = Mp3BitWriter(32)
    private var emitted = 0L
    private var musicCrc = 0

    fun encode(samples: ShortArray, count: Int = samples.size): ByteArray {
        check(!finished) { "The encoder is finished" }
        require(count in 0..samples.size && count % channels == 0) { "count must cover whole sample frames within the array" }
        var i = 0
        while (i < count) {
            for (ch in 0 until channels) incoming[ch][filled] = samples[i + ch] / 32768.0
            i += channels
            if (++filled == GRANULE) {
                analyzeGranule()
                filled = 0
            }
        }
        this.samples += count / channels
        return drain(false)
    }

    // at least two audio frames: ffmpeg's demuxer wants two consecutive headers before it accepts a stream
    fun finish(): ByteArray {
        check(!finished) { "The encoder is finished" }
        finished = true
        if (samples == 0L) return ByteArray(0)
        while (frames < 2 || frames.toLong() * frameSamples < samples + DELAY) {
            for (ch in 0 until channels) incoming[ch].fill(0.0, filled, GRANULE)
            analyzeGranule()
            filled = 0
        }
        return drain(true)
    }

    fun infoFrame(): ByteArray {
        check(finished) { "The Info frame is known after finish()" }
        if (frames == 0 || tagSize == 0) return ByteArray(0)
        val tag = ByteArray(tagSize)
        writeHeader(tag, 0, 0, false)
        var p = 4 + sideInfoSize
        for (c in "Info") tag[p++] = c.code.toByte()
        p = putInt(tag, p, if (tagLayout == MINIMAL_TAG) 0x03 else if (tagLayout == FULL_TAG) 0x0F else 0x0B)
        p = putInt(tag, p, frames)
        p = putInt(tag, p, emitted.toInt())
        if (tagLayout == MINIMAL_TAG) return tag
        if (tagLayout == FULL_TAG) {
            for (i in 0 until 100) tag[p + i] = (i * 256 / 100).toByte()
            p += 100
        }
        p = putInt(tag, p, 0)
        for (i in 0 until 9) tag[p + i] = if (i < ENCODER_NAME.length) ENCODER_NAME[i].code.toByte() else 0
        p += 9
        tag[p++] = 1
        tag[p++] = min(255, (lowpass.toInt() + 50) / 100).toByte()
        p += 8
        tag[p++] = 0
        tag[p++] = min(255, bitrateKbps).toByte()
        val start = DELAY - DECODER_DELAY
        val end = (frames.toLong() * frameSamples - samples - start).toInt()
        tag[p++] = (start ushr 4).toByte()
        tag[p++] = (((start and 15) shl 4) or (end ushr 8)).toByte()
        tag[p++] = end.toByte()
        val source = when (sampleRate) {
            44100 -> 1
            48000 -> 2
            else -> 0
        }
        tag[p++] = ((source shl 6) or ((if (channels == 1) 0 else 3) shl 2)).toByte()
        p += 3
        p = putInt(tag, p, emitted.toInt())
        tag[p++] = (musicCrc ushr 8).toByte()
        tag[p++] = musicCrc.toByte()
        val crc = crc16(0, tag, 0, p)
        tag[p++] = (crc ushr 8).toByte()
        tag[p] = crc.toByte()
        return tag
    }

    // an attack at t hits subband sample (t + 225) / 32, short windows cover only the first 12 of a granule
    private fun analyzeGranule() {
        val g = analyzed++
        shortNeeded[(g + 1) and 3] = false
        for (ch in 0 until channels) {
            filterbanks[ch].polyphase(incoming[ch], 0, subbands[ch][g and 3])
            val attacks = detectors[ch].scan(incoming[ch])
            for (block in 0 until 8) {
                if (attacks and (1 shl block) == 0) continue
                val m = (72 * block + 36 + 225) / 32
                shortNeeded[(g + m / 18 + if (m % 18 < 12) 0 else 1) and 3] = true
            }
        }
        if (g > 0) transformGranule(g - 1)
    }

    // neighbouring windows have to overlap in matching halves (start leads into short, stop out of it),
    // so the type of g waits for the attacks of the next granule
    private fun transformGranule(g: Int) {
        val type = when {
            shortNeeded[g and 3] -> SHORT_BLOCK
            shortNeeded[(g + 1) and 3] -> if (previousType == SHORT_BLOCK) SHORT_BLOCK else START_BLOCK
            previousType == SHORT_BLOCK -> STOP_BLOCK
            else -> NORMAL_BLOCK
        }
        previousType = type
        val slot = g % granules
        blockTypes[slot] = type
        for (ch in 0 until channels) filterbanks[ch].transform(subbands[ch][(g - 1) and 3], subbands[ch][g and 3], type, lines[slot][ch])
        if (slot == granules - 1) encodeFrame()
    }

    private fun encodeFrame() {
        if (frames == 0 && tagSize > 0) append(tagSize).also { writeHeader(out, it, 0, false) }
        paddingBalance += paddingStep
        val padding = if (paddingBalance >= sampleRate) 1 else 0
        if (padding == 1) paddingBalance -= sampleRate
        val bytes = frameBytes + padding
        val slots = bytes - 4 - sideInfoSize
        analyze()
        if (reservoir > reservoirLimit) {
            advance(reservoir - reservoirLimit, null)
            reservoir = reservoirLimit
        }
        quantizeFrame(slots)

        mainData.reset()
        for (gr in 0 until granules) {
            for (ch in 0 until channels) {
                writeScalefactors(side[gr][ch], gr)
                huffman.write(mainData, side[gr][ch].values, lines[gr][ch], side[gr][ch], longEdges)
            }
        }
        val start = append(bytes)
        val slot = frames and (RING - 1)
        slotStart[slot] = start + 4 + sideInfoSize
        slotEnd[slot] = start + bytes
        if (frames == 0) {
            writeFrame = 0
            writePos = slotStart[slot]
        }
        writeHeader(out, start, padding, midSide)
        writeSideInfo()
        sideData.bytes.copyInto(out, start + 4, 0, sideInfoSize)
        val mainBytes = (mainData.bits + 7) ushr 3
        advance(mainBytes, mainData.bytes)
        reservoir += slots - mainBytes
        frames++
    }

    private fun analyze() {
        for (gr in 0 until granules) {
            val short = blockTypes[gr] == SHORT_BLOCK
            for (ch in 0 until channels) {
                side[gr][ch].blockType = blockTypes[gr]
                masking.energies(lines[gr][ch], short, energy[gr][ch])
                masking.thresholds(lines[gr][ch], short, energy[gr][ch], xmin[gr][ch])
            }
        }
        midSide = channels == 2 && preferMidSide()
        for (gr in 0 until granules) {
            for (ch in 0 until channels) demand[gr][ch] = masking.demand(energy[gr][ch], blockTypes[gr] == SHORT_BLOCK, xmin[gr][ch])
        }
    }

    // each decoded channel gets the noise of both mid and side, so both have to stay under the lower
    // threshold of the two channels
    private fun preferMidSide(): Boolean {
        var separate = 0.0
        var joint = 0.0
        for (gr in 0 until granules) {
            val short = blockTypes[gr] == SHORT_BLOCK
            val left = lines[gr][0]
            val right = lines[gr][1]
            val mid = spare[gr][0]
            val sides = spare[gr][1]
            for (i in 0 until GRANULE) {
                mid[i] = (left[i] + right[i]) * SQRT_HALF
                sides[i] = (left[i] - right[i]) * SQRT_HALF
            }
            for (b in 0 until 39) jointXmin[b] = min(xmin[gr][0][b], xmin[gr][1][b])
            masking.energies(mid, short, jointEnergy[0])
            masking.energies(sides, short, jointEnergy[1])
            separate += masking.demand(energy[gr][0], short, xmin[gr][0]) + masking.demand(energy[gr][1], short, xmin[gr][1])
            joint += masking.demand(jointEnergy[0], short, jointXmin) + masking.demand(jointEnergy[1], short, jointXmin)
        }
        if (joint >= separate) return false
        for (gr in 0 until granules) {
            for (ch in 0 until 2) {
                val swapped = lines[gr][ch]
                lines[gr][ch] = spare[gr][ch]
                spare[gr][ch] = swapped
                masking.energies(lines[gr][ch], blockTypes[gr] == SHORT_BLOCK, energy[gr][ch])
            }
            for (b in 0 until 39) {
                val low = min(xmin[gr][0][b], xmin[gr][1][b])
                xmin[gr][0][b] = low
                xmin[gr][1][b] = low
            }
        }
        return true
    }

    // each granule gets its mean share, a tenth less while the reservoir fills, plus reservoir bits when
    // the perceptual entropy asks for more, up to 60 % of the reservoir
    private fun quantizeFrame(slots: Int) {
        val mean = slots * 8 / granules
        val limit = reservoirLimit * 8
        val shareScalefactors = granules == 2 && blockTypes[0] != SHORT_BLOCK && blockTypes[1] != SHORT_BLOCK
        var pool = reservoir * 8
        for (gr in 0 until granules) {
            var target = mean
            var extra = 0
            if (limit > 0) {
                val excess = pool - limit * 9 / 10
                if (excess > 0) target += excess else target -= mean / 10
                extra = max(0, min(pool, limit * 6 / 10) - max(0, excess))
            }
            var need = 0.0
            for (ch in 0 until channels) need += demand[gr][ch]
            val granted = target + min(extra.toDouble(), max(0.0, need - target)).toInt()
            var left = granted
            var used = 0
            for (ch in 0 until channels) {
                val share = if (ch == channels - 1) left else (granted * weight(gr, ch, mean)).toInt()
                val granule = side[gr][ch]
                val reference = if (gr == 1 && shareScalefactors) side[0][ch] else null
                quantizer.quantize(lines[gr][ch], xmin[gr][ch], min(MAX_GRANULE_BITS, min(share, left)), granule, reference)
                left -= granule.part23Length
                used += granule.part23Length
            }
            pool += mean - used
        }
    }

    private fun weight(gr: Int, ch: Int, mean: Int): Double {
        val floor = 0.1 * mean / channels
        var total = 0.0
        for (c in 0 until channels) total += demand[gr][c] + floor
        return (demand[gr][ch] + floor) / total
    }

    private fun writeScalefactors(granule: Mp3Granule, gr: Int) {
        val short = granule.blockType == SHORT_BLOCK
        if (mpeg1 && short) {
            for (g in 0 until 36) mainData.write(granule.scalefactors[g], granule.slen[if (g < 18) 0 else 1])
        } else if (mpeg1) {
            for (group in 0 until 4) {
                if (gr == 1 && granule.scfsi and (8 shr group) != 0) continue
                val bits = granule.slen[if (group < 2) 0 else 1]
                for (b in SCFSI_GROUPS[group] until SCFSI_GROUPS[group + 1]) mainData.write(granule.scalefactors[b], bits)
            }
        } else {
            var first = 0
            val partitions = (if (short) Mp3Tables.lsfShortPartitions else Mp3Tables.lsfPartitions)[granule.partitions]
            for (p in 0 until 4) {
                for (g in first until first + partitions[p]) mainData.write(granule.scalefactors[g], granule.slen[p])
                first += partitions[p]
            }
        }
    }

    private fun writeSideInfo() {
        sideData.reset()
        if (mpeg1) {
            sideData.write(reservoir, 9)
            sideData.write(0, if (channels == 1) 5 else 3)
            for (ch in 0 until channels) sideData.write(side[1][ch].scfsi, 4)
        } else {
            sideData.write(reservoir, 8)
            sideData.write(0, channels)
        }
        for (gr in 0 until granules) {
            for (ch in 0 until channels) {
                val g = side[gr][ch]
                sideData.write(g.part23Length, 12)
                sideData.write(g.bigValues, 9)
                sideData.write(g.globalGain, 8)
                sideData.write(g.scalefacCompress, if (mpeg1) 4 else 9)
                if (g.blockType == NORMAL_BLOCK) {
                    sideData.write(0, 1)
                    for (region in 0 until 3) sideData.write(g.tableSelect[region], 5)
                    sideData.write(g.region0Count, 4)
                    sideData.write(g.region1Count, 3)
                } else {
                    sideData.write(1, 1)
                    sideData.write(g.blockType, 2)
                    sideData.write(0, 1)
                    for (region in 0 until 2) sideData.write(g.tableSelect[region], 5)
                    for (w in 0 until 3) sideData.write(0, 3)
                }
                if (mpeg1) sideData.write(g.preflag, 1)
                sideData.write(g.scalefacScale, 1)
                sideData.write(g.count1Table, 1)
            }
        }
    }

    private fun writeHeader(target: ByteArray, at: Int, padding: Int, joint: Boolean) {
        target[at] = 0xFF.toByte()
        target[at + 1] = (if (mpeg1) 0xFB else 0xF3).toByte()
        target[at + 2] = ((bitrateIndex shl 4) or (rateIndex shl 2) or (padding shl 1)).toByte()
        val mode = if (channels == 1) 3 else 1
        target[at + 3] = ((mode shl 6) or ((if (joint) 2 else 0) shl 4) or 4).toByte()
    }

    private fun append(count: Int): Int {
        if (size + count > out.size) out = out.copyOf(max(out.size * 2, size + count))
        out.fill(0, size, size + count)
        size += count
        return size - count
    }

    private fun advance(count: Int, data: ByteArray?) {
        var done = 0
        while (done < count) {
            val end = slotEnd[writeFrame and (RING - 1)]
            if (writePos == end) {
                writeFrame++
                writePos = slotStart[writeFrame and (RING - 1)]
                continue
            }
            val n = min(count - done, end - writePos)
            data?.copyInto(out, writePos, done, done + n)
            writePos += n
            done += n
        }
    }

    private fun drain(all: Boolean): ByteArray {
        val ready = if (all || frames == 0) size else writePos
        val result = out.copyOf(ready)
        val tagBytes = (tagSize - emitted).coerceIn(0L, ready.toLong()).toInt()
        musicCrc = crc16(musicCrc, result, tagBytes, ready)
        emitted += ready
        out.copyInto(out, 0, ready, size)
        size -= ready
        writePos -= ready
        for (f in writeFrame until frames) {
            slotStart[f and (RING - 1)] -= ready
            slotEnd[f and (RING - 1)] -= ready
        }
        return result
    }

    private fun lowpassHz(): Double {
        val perChannel = bitrateKbps.toDouble() / channels
        var hz = LOWPASS_HZ.last().toDouble()
        for (i in 1 until LOWPASS_KBPS.size) {
            if (perChannel <= LOWPASS_KBPS[i]) {
                val t = ((perChannel - LOWPASS_KBPS[i - 1]) / (LOWPASS_KBPS[i] - LOWPASS_KBPS[i - 1])).coerceIn(0.0, 1.0)
                hz = LOWPASS_HZ[i - 1] + t * (LOWPASS_HZ[i] - LOWPASS_HZ[i - 1])
                break
            }
        }
        return min(hz, sampleRate * 0.475)
    }

    private fun tagLayout(): Int {
        val room = frameBytes - 4 - sideInfoSize
        return when {
            room >= 156 -> FULL_TAG
            room >= 56 -> SHORT_TAG
            room >= 16 -> MINIMAL_TAG
            else -> NO_TAG
        }
    }

    companion object {
        // input to output delay of a decoder that does not trim it
        const val DELAY = 1057

        private const val NO_TAG = 0
        private const val MINIMAL_TAG = 1
        private const val SHORT_TAG = 2
        private const val FULL_TAG = 3
        private val SCFSI_GROUPS = intArrayOf(0, 6, 11, 16, 21)

        val sampleRates: Set<Int> = setOf(32000, 44100, 48000, 16000, 22050, 24000)

        fun bitrates(sampleRate: Int): List<Int> = when (sampleRate) {
            32000, 44100, 48000 -> Mp3Tables.bitratesMpeg1.drop(1)
            16000, 22050, 24000 -> Mp3Tables.bitratesMpeg2.drop(1)
            else -> emptyList()
        }

        fun nearestRate(rate: Int): Int = sampleRates.minBy { abs(it - rate) }

        fun nearestBitrate(sampleRate: Int, kbps: Int): Int = bitrates(sampleRate).minBy { abs(it - kbps) }

        private fun putInt(target: ByteArray, at: Int, value: Int): Int {
            target[at] = (value ushr 24).toByte()
            target[at + 1] = (value ushr 16).toByte()
            target[at + 2] = (value ushr 8).toByte()
            target[at + 3] = value.toByte()
            return at + 4
        }

        private fun crc16(crc: Int, data: ByteArray, from: Int, to: Int): Int {
            var c = crc
            for (i in from until to) c = (c ushr 8) xor CRC16[(c xor data[i].toInt()) and 0xFF]
            return c
        }
    }
}

private class AttackDetector {
    private var last = 0.0
    private val recent = DoubleArray(8)
    private var next = 0

    // bit i set: block i of the 576 samples is an attack
    fun scan(samples: DoubleArray): Int {
        var attacks = 0
        for (block in 0 until 8) {
            var energy = 0.0
            for (i in block * 72 until block * 72 + 72) {
                val d = samples[i] - last
                last = samples[i]
                energy += d * d
            }
            var average = 0.0
            for (e in recent) average += e
            average /= recent.size
            if (energy > ATTACK_RATIO * average && energy > ATTACK_FLOOR) attacks = attacks or (1 shl block)
            recent[next] = energy
            next = (next + 1) and 7
        }
        return attacks
    }
}

internal class Mp3BitWriter(capacity: Int) {
    val bytes = ByteArray(capacity)
    var bits = 0
        private set

    fun reset() {
        bytes.fill(0, 0, (bits + 7) ushr 3)
        bits = 0
    }

    fun write(value: Int, count: Int) {
        var remaining = count
        while (remaining > 0) {
            val free = 8 - (bits and 7)
            val take = min(free, remaining)
            val chunk = (value ushr (remaining - take)) and ((1 shl take) - 1)
            val index = bits ushr 3
            bytes[index] = (bytes[index].toInt() or (chunk shl (free - take))).toByte()
            bits += take
            remaining -= take
        }
    }
}
