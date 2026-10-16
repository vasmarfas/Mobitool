package com.vasmarfas.card.tools.documents

import androidx.compose.ui.geometry.Offset
import com.vasmarfas.card.tools.media.DecodedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocScanTest {
    private val trapezoid = Quad(Offset(10f, 20f), Offset(90f, 5f), Offset(110f, 95f), Offset(0f, 80f))

    private fun near(expected: Offset, actual: Offset, tolerance: Float = 1e-3f, message: String = "") =
        assertTrue(abs(expected.x - actual.x) <= tolerance && abs(expected.y - actual.y) <= tolerance, "$message: expected $expected, got $actual")

    private fun gray(level: Int): Int = 0xFF000000.toInt() or level * 0x010101

    private fun luma(argb: Int): Int = argb and 0xFF

    private fun sheet(width: Int, height: Int, corners: Quad, background: Int, paper: Int): DecodedImage {
        val c = corners.corners
        val pixels = IntArray(width * height) { i ->
            val p = Offset(i % width + 0.5f, i / width + 0.5f)
            val inside = c.indices.all { k ->
                val a = c[k]
                val b = c[(k + 1) % 4]
                (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x) >= 0f
            }
            gray(if (inside) paper else background)
        }
        return DecodedImage(width, height, pixels)
    }

    @Test
    fun homographyPutsTheSquareCornersOnTheQuad() {
        val map = Homography(trapezoid)
        near(trapezoid.topLeft, map.map(0.0, 0.0), message = "top left")
        near(trapezoid.topRight, map.map(1.0, 0.0), message = "top right")
        near(trapezoid.bottomRight, map.map(1.0, 1.0), message = "bottom right")
        near(trapezoid.bottomLeft, map.map(0.0, 1.0), message = "bottom left")
    }

    @Test
    fun centreOfTheSquareLandsWhereTheDiagonalsCross() {
        val (p, r) = trapezoid.topLeft to trapezoid.bottomRight
        val (q, s) = trapezoid.topRight to trapezoid.bottomLeft
        val t = ((q.x - p.x) * (s.y - q.y) - (q.y - p.y) * (s.x - q.x)) / ((r.x - p.x) * (s.y - q.y) - (r.y - p.y) * (s.x - q.x))
        near(p + (r - p) * t, Homography(trapezoid).map(0.5, 0.5))
    }

    @Test
    fun rectangleMapsLinearly() {
        val map = Homography(Quad(Offset(10f, 10f), Offset(50f, 10f), Offset(50f, 30f), Offset(10f, 30f)))
        near(Offset(20f, 25f), map.map(0.25, 0.75))
    }

    @Test
    fun warpingTheWholePhotoGivesItBack() {
        val image = DecodedImage(7, 5, IntArray(35) { gray(it * 7) })
        val out = DocScan.warp(image, Quad.inset(0f), 7, 5)
        assertContentEquals(image.pixels, out.pixels)
    }

    @Test
    fun warpingARectangleCropsIt() {
        val image = DecodedImage(8, 4, IntArray(32) { gray(it * 5) })
        val quad = Quad(Offset(0.25f, 0.5f), Offset(0.75f, 0.5f), Offset(0.75f, 1f), Offset(0.25f, 1f))
        val out = DocScan.warp(image, quad, 4, 2)
        val expected = IntArray(8) { image.pixels[(2 + it / 4) * 8 + 2 + it % 4] }
        assertContentEquals(expected, out.pixels)
    }

    @Test
    fun aTurnPutsTheLeftSideOnTop() {
        val (a, b, c, d) = listOf(gray(10), gray(20), gray(30), gray(40))
        val image = DecodedImage(2, 2, intArrayOf(a, b, c, d))
        val out = DocScan.warp(image, Quad.inset(0f).turned(1), 2, 2)
        assertContentEquals(intArrayOf(c, a, d, b), out.pixels)
    }

    @Test
    fun fourTurnsComeBackToTheStart() {
        val quad = Quad.inset(0.1f)
        assertEquals(quad.bottomLeft, quad.turned(1).topLeft)
        assertEquals(quad.topLeft, quad.turned(1).topRight)
        assertEquals(quad, quad.turned(4))
        assertEquals(quad.turned(3), quad.turned(-1))
    }

    @Test
    fun twistedOrDentedQuadIsNotConvex() {
        assertTrue(Quad.inset(0.1f).convex)
        assertTrue(trapezoid.convex)
        val twisted = Quad.inset(0.1f).let { it.copy(topRight = it.bottomRight, bottomRight = it.topRight) }
        assertFalse(twisted.convex)
        assertFalse(Quad.inset(0.1f).moved(1, Offset(0.3f, 0.3f)).convex)
        val counterClockwise = Quad.inset(0.1f).let { Quad(it.topLeft, it.bottomLeft, it.bottomRight, it.topRight) }
        assertFalse(counterClockwise.convex)
    }

    @Test
    fun pageTakesTheLongerSidesAndFitsTheLimit() {
        val rect = Quad(Offset(0.1f, 0.1f), Offset(0.9f, 0.1f), Offset(0.9f, 0.9f), Offset(0.1f, 0.9f))
        assertEquals(800 to 400, DocScan.pageSize(rect, 1000, 500, 2400))
        assertEquals(400 to 200, DocScan.pageSize(rect, 1000, 500, 400))
        val narrowBottom = rect.copy(bottomLeft = Offset(0.3f, 0.9f), bottomRight = Offset(0.7f, 0.9f))
        assertEquals(800, DocScan.pageSize(narrowBottom, 1000, 500, 2400).first)
    }

    @Test
    fun grayKeepsTheBrightness() {
        val image = DecodedImage(1, 1, intArrayOf(0xFFC86432.toInt()))
        DocScan.applyFilter(image, ScanFilter.GRAY)
        val level = luma(image.pixels[0])
        assertEquals(image.pixels[0], gray(level))
        assertTrue(level in 115..125, "level $level")
    }

    @Test
    fun thresholdKeepsInkUnderUnevenLight() {
        val width = 400
        val height = 100
        val ink = { x: Int, y: Int -> y in 40..44 && x % 20 < 6 }
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val paper = 90 + 160 * x / width
            gray(if (ink(x, i / width)) paper * 6 / 10 else paper)
        }
        val image = DecodedImage(width, height, pixels)
        DocScan.applyFilter(image, ScanFilter.BLACK_WHITE)
        var wrong = 0
        for (i in pixels.indices) {
            val black = image.pixels[i] == gray(0)
            assertTrue(black || image.pixels[i] == gray(255))
            if (black != ink(i % width, i / width)) wrong++
        }
        assertTrue(wrong < pixels.size / 500, "$wrong pixels came out wrong")
    }

    @Test
    fun findsATiltedSheetOnADarkTable() {
        val corners = Quad(Offset(60f, 40f), Offset(330f, 70f), Offset(310f, 265f), Offset(40f, 240f))
        val image = sheet(400, 300, corners, background = 70, paper = 225)
        for (y in 100..200 step 12) for (x in 110..260) image.pixels[y * 400 + x] = gray(30)
        val found = assertNotNull(DocScan.findPage(image))
        corners.scaled(1f / 400, 1f / 300).corners.zip(found.corners).forEach { (expected, actual) ->
            near(expected, actual, tolerance = 3f / 300, message = "corner")
        }
    }

    @Test
    fun givesUpOnABlankPhotoAndARoundPatch() {
        assertNull(DocScan.findPage(DecodedImage(50, 40, IntArray(2000) { gray(200) })))
        val disc = DecodedImage(200, 200, IntArray(40000) { i ->
            val dx = i % 200 - 100
            val dy = i / 200 - 100
            gray(if (dx * dx + dy * dy < 80 * 80) 230 else 60)
        })
        assertNull(DocScan.findPage(disc))
    }
}
