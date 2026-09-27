package com.vasmarfas.card.core

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import kotlinx.coroutines.runBlocking
import org.bytedeco.ffmpeg.ffmpeg
import org.bytedeco.javacpp.Loader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class HeifTest {
    init {
        registerSkikoComposeImplementation()
    }

    private val dir: File = createTempDirectory("heif-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun still(color: String, width: Int, height: Int): ByteArray {
        val out = File(dir, "$color-${width}x$height.avif")
        val command = listOf(
            Loader.load(ffmpeg::class.java), "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "color=c=$color:s=${width}x$height",
            "-frames:v", "1", "-c:v", "libaom-av1", "-still-picture", "1", "-cpu-used", "8", out.path,
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), log)
        return out.readBytes()
    }

    private class Box(val body: Int, val end: Int)

    private fun ByteArray.u16(at: Int): Int = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

    private fun ByteArray.child(from: Int, to: Int, type: String): Box {
        var at = from
        while (at + 8 <= to) {
            val size = (u16(at) shl 16) or u16(at + 2)
            if (String(this, at + 4, 4, Charsets.ISO_8859_1) == type) return Box(at + 8, at + size)
            at += size
        }
        error("no $type box")
    }

    private fun item(avif: ByteArray): Pair<ByteArray, ByteArray> {
        val meta = avif.child(0, avif.size, "meta")
        val start = meta.body + 4
        val iloc = avif.child(start, meta.end, "iloc")
        val version = avif[iloc.body].toInt()
        val sizes = avif[iloc.body + 4].toInt()
        val layout = avif[iloc.body + 5].toInt()
        var at = iloc.body + 8
        fun read(size: Int): Int = (0 until size).fold(0) { value, i -> (value shl 8) or (avif[at + i].toInt() and 0xFF) }.also { at += size }
        read(2)
        if (version > 0) read(2)
        read(2)
        val base = read((layout shr 4) and 15)
        assertEquals(1, read(2), "one extent")
        if (version > 0) read(layout and 15)
        val offset = base + read((sizes shr 4) and 15)
        val length = read(sizes and 15)
        val ipco = avif.child(avif.child(start, meta.end, "iprp").body, meta.end, "ipco")
        val av1c = avif.child(ipco.body, ipco.end, "av1C")
        return avif.copyOfRange(offset, offset + length) to avif.copyOfRange(av1c.body - 8, av1c.end)
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    private fun shorts(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 2).apply { values.forEach { putShort(it.toShort()) } }.array()

    private fun ints(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4).apply { values.forEach { putInt(it) } }.array()

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { all, part -> all + part }
        return ints(8 + body.size) + ascii(type) + body
    }

    private fun full(type: String, version: Int, flags: Int, vararg parts: ByteArray): ByteArray = box(type, ints((version shl 24) or flags), *parts)

    private fun grid(tiles: List<ByteArray>, tileWidth: Int, tileHeight: Int): ByteArray {
        val coded = tiles.map(::item)
        val payloads = coded.map { it.first } + (byteArrayOf(0, 0, 1, 1) + shorts(tileWidth * 2, tileHeight * 2))
        val ftyp = box("ftyp", ascii("avif"), ints(0), ascii("avifmif1miaf"))
        val infe = (1..4).map { full("infe", 2, 1, shorts(it, 0), ascii("av01"), byteArrayOf(0)) } + full("infe", 2, 0, shorts(5, 0), ascii("grid"), byteArrayOf(0))
        val properties = box("ipco", coded[0].second, full("ispe", 0, 0, ints(tileWidth, tileHeight)), full("ispe", 0, 0, ints(tileWidth * 2, tileHeight * 2)))
        val associations = (1..4).map { shorts(it) + byteArrayOf(2, 0x81.toByte(), 2) } + (shorts(5) + byteArrayOf(1, 3))
        fun meta(offsets: List<Int>): ByteArray {
            val locations = payloads.indices.map { shorts(it + 1, 0, 1) + ints(offsets[it], payloads[it].size) }
            return full(
                "meta", 0, 0,
                full("hdlr", 0, 0, ints(0), ascii("pict"), ByteArray(13)),
                full("pitm", 0, 0, shorts(5)),
                full("iloc", 0, 0, byteArrayOf(0x44, 0), shorts(payloads.size), *locations.toTypedArray()),
                full("iinf", 0, 0, shorts(infe.size), *infe.toTypedArray()),
                full("iref", 0, 0, box("dimg", shorts(5, 4, 1, 2, 3, 4))),
                box("iprp", properties, full("ipma", 0, 0, ints(payloads.size), *associations.toTypedArray())),
            )
        }
        val start = ftyp.size + meta(List(payloads.size) { 0 }).size + 8
        val offsets = payloads.runningFold(start) { at, payload -> at + payload.size }.dropLast(1)
        return ByteArrayOutputStream().apply {
            write(ftyp)
            write(meta(offsets))
            write(box("mdat", *payloads.toTypedArray()))
        }.toByteArray()
    }

    private fun channels(argb: Int) = listOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    @Test
    fun aTiledPictureComesOutWholeWithEveryTileInPlace() = runBlocking {
        val tiles = listOf("red", "lime", "blue", "white").map { still(it, 256, 192) }
        val raw = assertNotNull(decodeRawImage(grid(tiles, 256, 192)))
        assertEquals(512 to 384, raw.bitmap.width to raw.bitmap.height)
        val pixels = raw.bitmap.pixels()
        val expected = mapOf((128 to 96) to listOf(255, 0, 0), (384 to 96) to listOf(0, 255, 0), (128 to 288) to listOf(0, 0, 255), (384 to 288) to listOf(255, 255, 255))
        expected.forEach { (point, color) ->
            val actual = channels(pixels[point.second * 512 + point.first])
            assertTrue(actual.zip(color).all { (a, e) -> abs(a - e) < 24 }, "tile at $point is $actual, expected $color")
        }
    }

    @Test
    fun aSinglePictureAvifOpens() = runBlocking {
        val raw = assertNotNull(decodeRawImage(still("teal", 320, 200)))
        assertEquals(320 to 200, raw.bitmap.width to raw.bitmap.height)
    }
}
