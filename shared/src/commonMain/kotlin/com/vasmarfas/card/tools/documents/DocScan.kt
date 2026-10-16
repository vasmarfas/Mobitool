package com.vasmarfas.card.tools.documents

import androidx.compose.ui.geometry.Offset
import com.vasmarfas.card.tools.media.DecodedImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ScanFilter { COLOR, GRAY, BLACK_WHITE }

data class Quad(val topLeft: Offset, val topRight: Offset, val bottomRight: Offset, val bottomLeft: Offset) {
    val corners: List<Offset> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun moved(index: Int, to: Offset): Quad = when (index) {
        0 -> copy(topLeft = to)
        1 -> copy(topRight = to)
        2 -> copy(bottomRight = to)
        else -> copy(bottomLeft = to)
    }

    fun turned(times: Int): Quad {
        val c = corners
        val shift = times.mod(4)
        return Quad(c[(4 - shift) % 4], c[(5 - shift) % 4], c[(6 - shift) % 4], c[(7 - shift) % 4])
    }

    fun scaled(width: Float, height: Float): Quad = Quad(
        Offset(topLeft.x * width, topLeft.y * height),
        Offset(topRight.x * width, topRight.y * height),
        Offset(bottomRight.x * width, bottomRight.y * height),
        Offset(bottomLeft.x * width, bottomLeft.y * height),
    )

    val convex: Boolean
        get() {
            val c = corners
            return c.indices.all { cross(c[(it + 1) % 4] - c[it], c[(it + 2) % 4] - c[(it + 1) % 4]) > 0f }
        }

    companion object {
        fun inset(margin: Float): Quad =
            Quad(Offset(margin, margin), Offset(1f - margin, margin), Offset(1f - margin, 1f - margin), Offset(margin, 1f - margin))
    }
}

class Homography(quad: Quad) {
    private val a: Double
    private val b: Double
    private val c: Double
    private val d: Double
    private val e: Double
    private val f: Double
    private val g: Double
    private val h: Double

    init {
        val (x0, y0) = quad.topLeft
        val (x1, y1) = quad.topRight
        val (x2, y2) = quad.bottomRight
        val (x3, y3) = quad.bottomLeft
        val sx = (x0 - x1 + x2 - x3).toDouble()
        val sy = (y0 - y1 + y2 - y3).toDouble()
        val dx1 = (x1 - x2).toDouble()
        val dx2 = (x3 - x2).toDouble()
        val dy1 = (y1 - y2).toDouble()
        val dy2 = (y3 - y2).toDouble()
        val det = dx1 * dy2 - dx2 * dy1
        g = (sx * dy2 - dx2 * sy) / det
        h = (dx1 * sy - sx * dy1) / det
        a = x1 - x0 + g * x1
        b = x3 - x0 + h * x3
        c = x0.toDouble()
        d = y1 - y0 + g * y1
        e = y3 - y0 + h * y3
        f = y0.toDouble()
    }

    fun map(u: Double, v: Double): Offset {
        val w = g * u + h * v + 1
        return Offset(((a * u + b * v + c) / w).toFloat(), ((d * u + e * v + f) / w).toFloat())
    }
}

object DocScan {
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val INK_CONTRAST = 0.15f
    private const val MIN_PAGE = 0.1f
    private const val MIN_SQUARENESS = 0.9f
    private const val MIN_FILL = 0.65f

    fun pageSize(quad: Quad, width: Int, height: Int, longSide: Int): Pair<Int, Int> {
        val q = quad.scaled(width.toFloat(), height.toFloat())
        val w = max((q.topRight - q.topLeft).getDistance(), (q.bottomRight - q.bottomLeft).getDistance())
        val h = max((q.bottomLeft - q.topLeft).getDistance(), (q.bottomRight - q.topRight).getDistance())
        val scale = min(1f, longSide / max(w, h))
        return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
    }

    fun warp(source: DecodedImage, quad: Quad, width: Int, height: Int): DecodedImage {
        val map = Homography(quad.scaled(source.width.toFloat(), source.height.toFloat()))
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val v = (y + 0.5) / height
            for (x in 0 until width) {
                val p = map.map((x + 0.5) / width, v)
                out[y * width + x] = sample(source, p.x - 0.5f, p.y - 0.5f)
            }
        }
        return DecodedImage(width, height, out)
    }

    fun applyFilter(image: DecodedImage, filter: ScanFilter) {
        val p = image.pixels
        when (filter) {
            ScanFilter.COLOR -> Unit
            ScanFilter.GRAY -> for (i in p.indices) p[i] = BLACK or luma(p[i]) * 0x010101
            ScanFilter.BLACK_WHITE -> threshold(image)
        }
    }

    fun findPage(image: DecodedImage): Quad? {
        val w = image.width
        val h = image.height
        val luma = IntArray(w * h) { luma(image.pixels[it]) }
        var weighted = 0.0
        var total = 0.0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = luma[i - w + 1] + 2 * luma[i + 1] + luma[i + w + 1] - luma[i - w - 1] - 2 * luma[i - 1] - luma[i + w - 1]
                val gy = luma[i + w - 1] + 2 * luma[i + w] + luma[i + w + 1] - luma[i - w - 1] - 2 * luma[i - w] - luma[i - w + 1]
                val g = abs(gx) + abs(gy)
                weighted += g.toDouble() * luma[i]
                total += g
            }
        }
        if (total == 0.0) return null
        val level = weighted / total
        val labels = IntArray(w * h)
        val queue = IntArray(w * h)
        var best = 0
        var bestSize = 0
        var label = 0
        for (start in luma.indices) {
            if (labels[start] != 0 || luma[start] <= level) continue
            label++
            labels[start] = label
            queue[0] = start
            var head = 0
            var tail = 1
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                for (side in 0..3) {
                    val next = when (side) {
                        0 -> if (x > 0) i - 1 else -1
                        1 -> if (x < w - 1) i + 1 else -1
                        2 -> i - w
                        else -> i + w
                    }
                    if (next < 0 || next >= luma.size || labels[next] != 0 || luma[next] <= level) continue
                    labels[next] = label
                    queue[tail++] = next
                }
            }
            if (tail > bestSize) {
                bestSize = tail
                best = label
            }
        }
        if (bestSize < MIN_PAGE * w * h) return null
        val points = ArrayList<Offset>()
        for (y in 0 until h) {
            var left = -1
            var right = -1
            for (x in 0 until w) {
                if (labels[y * w + x] != best) continue
                if (left < 0) left = x
                right = x + 1
            }
            if (left < 0) continue
            points += Offset(left.toFloat(), y.toFloat())
            points += Offset(left.toFloat(), y + 1f)
            points += Offset(right.toFloat(), y.toFloat())
            points += Offset(right.toFloat(), y + 1f)
        }
        val hull = convexHull(points)
        val quad = largestQuad(hull) ?: return null
        val quadArea = abs(signedArea(quad))
        val hullArea = abs(signedArea(hull))
        if (quadArea < MIN_PAGE * w * h || quadArea < MIN_SQUARENESS * hullArea || bestSize < MIN_FILL * hullArea) return null
        val clockwise = if (signedArea(quad) > 0f) quad else quad.reversed()
        val first = clockwise.indices.minBy { clockwise[it].x + clockwise[it].y }
        val c = List(4) { clockwise[(first + it) % 4] }
        return Quad(c[0], c[1], c[2], c[3]).scaled(1f / w, 1f / h)
    }

    private fun threshold(image: DecodedImage) {
        val w = image.width
        val h = image.height
        val p = image.pixels
        val r = max(1, max(w, h) / 16)
        val rows = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (x in 0..min(r, w - 1)) sum += luma(p[row + x])
            for (x in 0 until w) {
                rows[row + x] = sum
                if (x + r + 1 < w) sum += luma(p[row + x + r + 1])
                if (x - r >= 0) sum -= luma(p[row + x - r])
            }
        }
        val column = IntArray(w)
        for (y in 0..min(r, h - 1)) {
            for (x in 0 until w) column[x] += rows[y * w + x]
        }
        for (y in 0 until h) {
            if (y > 0 && y + r < h) for (x in 0 until w) column[x] += rows[(y + r) * w + x]
            if (y - r - 1 >= 0) for (x in 0 until w) column[x] -= rows[(y - r - 1) * w + x]
            val spanY = min(h - 1, y + r) - max(0, y - r) + 1
            for (x in 0 until w) {
                val spanX = min(w - 1, x + r) - max(0, x - r) + 1
                val i = y * w + x
                p[i] = if (luma(p[i]) * spanX * spanY < column[x] * (1 - INK_CONTRAST)) BLACK else WHITE
            }
        }
    }

    private fun luma(argb: Int): Int = (77 * (argb shr 16 and 0xFF) + 150 * (argb shr 8 and 0xFF) + 29 * (argb and 0xFF)) shr 8

    private fun sample(image: DecodedImage, x: Float, y: Float): Int {
        val cx = x.coerceIn(0f, image.width - 1f)
        val cy = y.coerceIn(0f, image.height - 1f)
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = min(x0 + 1, image.width - 1)
        val y1 = min(y0 + 1, image.height - 1)
        val fx = cx - x0
        val fy = cy - y0
        val p = image.pixels
        val p00 = p[y0 * image.width + x0]
        val p10 = p[y0 * image.width + x1]
        val p01 = p[y1 * image.width + x0]
        val p11 = p[y1 * image.width + x1]
        var out = 0
        for (shift in 0..24 step 8) {
            val top = lerp(p00, p10, shift, fx)
            val bottom = lerp(p01, p11, shift, fx)
            out = out or ((top + (bottom - top) * fy).roundToInt() shl shift)
        }
        return out
    }

    private fun lerp(a: Int, b: Int, shift: Int, t: Float): Float {
        val from = a ushr shift and 0xFF
        return from + ((b ushr shift and 0xFF) - from) * t
    }

    private fun convexHull(points: List<Offset>): List<Offset> {
        val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size < 3) return sorted
        val hull = ArrayList<Offset>()
        for (pass in 0..1) {
            val start = hull.size
            for (p in if (pass == 0) sorted else sorted.asReversed()) {
                while (hull.size >= start + 2 && cross(hull[hull.size - 1] - hull[hull.size - 2], p - hull[hull.size - 1]) <= 0f) hull.removeAt(hull.lastIndex)
                hull += p
            }
            hull.removeAt(hull.lastIndex)
        }
        return hull
    }

    private fun largestQuad(hull: List<Offset>): List<Offset>? {
        val n = hull.size
        var best = 0f
        var pick: List<Offset>? = null
        for (i in 0 until n - 3) {
            for (k in i + 2 until n - 1) {
                var j = -1
                var near = 0f
                for (m in i + 1 until k) {
                    val area = abs(cross(hull[m] - hull[i], hull[k] - hull[i]))
                    if (area > near) {
                        near = area
                        j = m
                    }
                }
                var l = -1
                var far = 0f
                for (m in k + 1 until n) {
                    val area = abs(cross(hull[m] - hull[k], hull[i] - hull[k]))
                    if (area > far) {
                        far = area
                        l = m
                    }
                }
                if (j >= 0 && l >= 0 && near + far > best) {
                    best = near + far
                    pick = listOf(hull[i], hull[j], hull[k], hull[l])
                }
            }
        }
        return pick
    }

    private fun signedArea(polygon: List<Offset>): Float {
        var sum = 0f
        polygon.indices.forEach {
            val a = polygon[it]
            val b = polygon[(it + 1) % polygon.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2
    }
}

private fun cross(u: Offset, v: Offset): Float = u.x * v.y - u.y * v.x
