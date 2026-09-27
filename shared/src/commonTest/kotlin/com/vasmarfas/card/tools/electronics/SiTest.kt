package com.vasmarfas.card.tools.electronics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SiTest {
    @Test
    fun parse() {
        assertEquals(4700.0, Si.parse("4.7k")!!, 1e-9)
        assertEquals(4700.0, Si.parse("4k7")!!, 1e-9)
        assertEquals(4700.0, Si.parse("4.7 kΩ")!!, 1e-9)
        assertEquals(0.1, Si.parse("100m")!!, 1e-12)
        assertEquals(2.2e-6, Si.parse("2.2u")!!, 1e-18)
        assertEquals(1e7, Si.parse("10M")!!, 1e-6)
        assertEquals(5.0, Si.parse("5V")!!, 1e-9)
        assertEquals(1000.0, Si.parse("1e3")!!, 1e-9)
        assertEquals(4.7, Si.parse("4R7")!!, 1e-9)
        assertEquals(4.7, Si.parse("4,7")!!, 1e-9)
        assertEquals(10_000.0, Si.parse("10,000")!!, 1e-9)
        assertNull(Si.parse("abc"))
        assertNull(Si.parse(""))
    }

    @Test
    fun format() {
        assertEquals("4.7 kΩ", Si.format(4700.0, "Ω"))
        assertEquals("21.2 mA", Si.format(0.0212, "A"))
        assertEquals("12 V", Si.format(12.0, "V"))
        assertEquals("2.2 µF", Si.format(2.2e-6, "F"))
    }
}
