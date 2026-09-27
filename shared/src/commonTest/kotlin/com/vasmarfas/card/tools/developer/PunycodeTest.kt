package com.vasmarfas.card.tools.developer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PunycodeTest {
    @Test
    fun encodesGermanLabel() {
        assertEquals("mnchen-3ya", Punycode.encode("münchen"))
    }

    @Test
    fun encodesCyrillicLabel() {
        assertEquals("80akhbyknj4f", Punycode.encode("испытание"))
        assertEquals("e1afmkfd", Punycode.encode("пример"))
        assertEquals("p1ai", Punycode.encode("рф"))
    }

    @Test
    fun encodesLatinWithDiacritics() {
        assertEquals("bcher-kva", Punycode.encode("bücher"))
    }

    @Test
    fun decodeIsInverse() {
        listOf("münchen", "испытание", "пример", "bücher").forEach { label ->
            assertEquals(label, Punycode.decode(Punycode.encode(label)))
        }
    }

    @Test
    fun hostConversion() {
        assertEquals("xn--mnchen-3ya.de", Punycode.toAscii("München.de"))
        assertEquals("xn--e1afmkfd.xn--p1ai", Punycode.toAscii("пример.рф"))
        assertEquals("пример.рф", Punycode.toUnicode("xn--e1afmkfd.xn--p1ai"))
        assertEquals("example.com", Punycode.toAscii("example.com"))
    }

    @Test
    fun overflowIsInvalid() {
        assertNull(Punycode.toUnicode("xn--7zw896697766a"))
        assertNull(Punycode.toUnicode("xn--zwx86866778zya"))
        assertNull(Punycode.decode("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz9"))
    }
}
