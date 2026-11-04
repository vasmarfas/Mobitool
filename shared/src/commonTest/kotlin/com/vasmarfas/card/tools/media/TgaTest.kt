package com.vasmarfas.card.tools.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TgaTest {
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun tga(type: Int, width: Int, height: Int, depth: Int, descriptor: Int, data: List<Int>, map: List<Int> = emptyList(), mapDepth: Int = 0): ByteArray {
        val entries = if (mapDepth == 0) 0 else map.size / (mapDepth / 8)
        val header = listOf(
            0, if (mapDepth == 0) 0 else 1, type, 0, 0, entries and 0xFF, entries shr 8, mapDepth, 0, 0, 0, 0,
            width and 0xFF, width shr 8, height and 0xFF, height shr 8, depth, descriptor,
        )
        return (header + map + data).map { it.toByte() }.toByteArray()
    }

    private fun pixels(bytes: ByteArray) = assertNotNull(Tga.decode(bytes)).pixels

    @Test
    fun rowsRunBottomUpByDefaultAndChannelsAreBgr() {
        val data = listOf(0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255)
        val image = assertNotNull(Tga.decode(tga(2, 2, 2, 24, 0, data)))
        assertEquals(2, image.width)
        assertEquals(2, image.height)
        assertContentEquals(intArrayOf(blue, white, red, green), image.pixels)
    }

    @Test
    fun descriptorBitsFlipRowsAndColumns() {
        val data = listOf(0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255)
        assertContentEquals(intArrayOf(red, green, blue, white), pixels(tga(2, 2, 2, 24, 0x20, data)))
        assertContentEquals(intArrayOf(green, red, white, blue), pixels(tga(2, 2, 2, 24, 0x30, data)))
    }

    @Test
    fun thirtyTwoBitsKeepAlpha() {
        val image = pixels(tga(2, 2, 1, 32, 0x28, listOf(0, 0, 255, 128, 0, 255, 0, 0)))
        assertContentEquals(intArrayOf(0x80FF0000.toInt(), 0x0000FF00), image)
    }

    @Test
    fun alphaThatIsZeroEverywhereMeansOpaque() {
        assertContentEquals(intArrayOf(red, green), pixels(tga(2, 2, 1, 32, 0x20, listOf(0, 0, 255, 0, 0, 255, 0, 0))))
    }

    @Test
    fun sixteenBitsAreFiveBitsPerChannel() {
        val data = listOf(0x00, 0x7C, 0xE0, 0x03, 0x1F, 0x00)
        assertContentEquals(intArrayOf(red, green, blue), pixels(tga(2, 3, 1, 16, 0x20, data)))
        assertContentEquals(intArrayOf(0x00FF0000, red), pixels(tga(2, 2, 1, 16, 0x21, listOf(0x00, 0x7C, 0x00, 0xFC))))
    }

    @Test
    fun eightBitsAreGrayOrAnIndex() {
        assertContentEquals(intArrayOf(0xFF000000.toInt(), 0xFF808080.toInt()), pixels(tga(3, 2, 1, 8, 0x20, listOf(0, 128))))
        val map = listOf(0, 0, 255, 255, 0, 0)
        assertContentEquals(intArrayOf(blue, red, blue), pixels(tga(1, 3, 1, 8, 0x20, listOf(1, 0, 1), map, 24)))
    }

    @Test
    fun rlePacketsMayCrossRows() {
        val data = listOf(0x83, 0, 0, 255, 0x01, 0, 255, 0, 255, 0, 0)
        assertContentEquals(intArrayOf(red, red, red, red, green, blue), pixels(tga(10, 3, 2, 24, 0x20, data)))
    }

    @Test
    fun rleWorksForGrayAndIndexes() {
        assertContentEquals(IntArray(3) { white }, pixels(tga(11, 3, 1, 8, 0x20, listOf(0x82, 255))))
        assertContentEquals(IntArray(3) { blue }, pixels(tga(9, 3, 1, 8, 0x20, listOf(0x82, 0), listOf(255, 0, 0), 24)))
    }

    @Test
    fun filesThatDoNotAddUpAreRejected() {
        assertNull(Tga.decode(tga(2, 2, 2, 24, 0, listOf(1, 2, 3))))
        assertNull(Tga.decode(tga(10, 3, 1, 24, 0x20, listOf(0x81, 0, 0, 255))))
        assertNull(Tga.decode(tga(10, 65535, 65535, 24, 0, listOf(0xFF, 0, 0, 255))))
        assertNull(Tga.decode(tga(2, 0, 2, 24, 0, emptyList())))
        assertNull(Tga.decode(tga(2, 1, 1, 12, 0, listOf(1, 2))))
        assertNull(Tga.decode(tga(1, 1, 1, 8, 0, listOf(0))))
        assertNull(Tga.decode(Png.SIGNATURE + ByteArray(32)))
        assertNull(Tga.decode(ByteArray(4)))
    }
}
