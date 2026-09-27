package com.vasmarfas.card.tools.developer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemVerTest {
    private fun v(text: String) = assertNotNull(SemVerOps.parse(text))

    @Test
    fun parsesParts() {
        val version = v("1.2.3-rc.1+build.5")
        assertEquals(1, version.major)
        assertEquals(2, version.minor)
        assertEquals(3, version.patch)
        assertEquals("rc.1", version.preRelease)
        assertEquals("build.5", version.build)
        assertNull(SemVerOps.parse("not.a.version"))
    }

    @Test
    fun ordersNumericallyNotLexically() {
        assertTrue(v("1.10.0") > v("1.9.0"))
        assertTrue(v("2.0.0") > v("1.99.99"))
    }

    @Test
    fun preReleaseIsLowerThanRelease() {
        assertTrue(v("1.0.0-alpha") < v("1.0.0"))
        assertTrue(v("1.0.0-alpha") < v("1.0.0-alpha.1"))
        assertTrue(v("1.0.0-alpha.1") < v("1.0.0-beta"))
        assertTrue(v("1.0.0-rc.2") > v("1.0.0-rc.1"))
        assertEquals(0, v("1.0.0+a").compareTo(v("1.0.0+b")))
    }

    @Test
    fun caretRange() {
        assertTrue(SemVerOps.satisfies(v("1.4.2"), "^1.4.0"))
        assertTrue(SemVerOps.satisfies(v("1.99.0"), "^1.4.0"))
        assertFalse(SemVerOps.satisfies(v("2.0.0"), "^1.4.0"))
        assertFalse(SemVerOps.satisfies(v("1.3.0"), "^1.4.0"))
        assertFalse(SemVerOps.satisfies(v("0.3.0"), "^0.2.0"))
    }

    @Test
    fun caretFollowsNpm() {
        assertFalse(SemVerOps.satisfies(v("1.10.0-rc.1"), "^1.4.0"))
        assertTrue(SemVerOps.satisfies(v("1.4.0-rc.2"), "^1.4.0-rc.1"))
        assertFalse(SemVerOps.satisfies(v("1.5.0-rc.1"), "^1.4.0-rc.1"))
        assertTrue(SemVerOps.satisfies(v("0.5.0"), "^0"))
        assertTrue(SemVerOps.satisfies(v("0.0.5"), "^0.0"))
        assertFalse(SemVerOps.satisfies(v("0.1.0"), "^0.0"))
        assertTrue(SemVerOps.satisfies(v("1.9.0"), "~1.x"))
    }

    @Test
    fun tildeAndComparators() {
        assertTrue(SemVerOps.satisfies(v("1.2.9"), "~1.2.3"))
        assertFalse(SemVerOps.satisfies(v("1.3.0"), "~1.2.3"))
        assertTrue(SemVerOps.satisfies(v("1.5.0"), ">=1.0.0 <2.0.0"))
        assertFalse(SemVerOps.satisfies(v("2.1.0"), ">=1.0.0 <2.0.0"))
        assertTrue(SemVerOps.satisfies(v("2.3.0"), "1.x || 2.x"))
        assertTrue(SemVerOps.satisfies(v("1.4.0"), "1.4.x"))
        assertFalse(SemVerOps.satisfies(v("1.5.0"), "1.4.x"))
    }

    @Test
    fun bumps() {
        assertEquals("2.0.0", SemVerOps.bumpMajor(v("1.4.2")).toString())
        assertEquals("1.5.0", SemVerOps.bumpMinor(v("1.4.2")).toString())
        assertEquals("1.4.3", SemVerOps.bumpPatch(v("1.4.2")).toString())
        assertEquals("1.4.2-rc.2", SemVerOps.bumpPreRelease(v("1.4.2-rc.1")).toString())
    }
}
