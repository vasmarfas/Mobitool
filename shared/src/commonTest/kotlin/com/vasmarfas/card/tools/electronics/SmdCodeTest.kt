package com.vasmarfas.card.tools.electronics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmdCodeTest {
    private fun ohms(code: String) = SmdCode.resistor(code).first().value

    private fun codes(ohms: Double) = SmdCode.resistorCodes(ohms).map { it.code }

    @Test
    fun digitsAreMantissaAndPowerOfTen() {
        assertEquals(10_000.0, ohms("103"), 1e-6)
        assertEquals(4_700.0, ohms("472"), 1e-6)
        assertEquals(10_000.0, ohms("1002"), 1e-6)
        assertEquals(100.0, ohms("1000"), 1e-6)
        assertEquals(SmdFormat.THREE_DIGITS, SmdCode.resistor("103").first().format)
        assertEquals(SmdFormat.FOUR_DIGITS, SmdCode.resistor("1002").first().format)
    }

    @Test
    fun letterRIsTheDecimalPoint() {
        assertEquals(4.7, ohms("4r7"), 1e-9)
        assertEquals(0.1, ohms("R10"), 1e-9)
        assertEquals(10.0, ohms("10R0"), 1e-9)
        assertEquals(0.047, ohms("R047"), 1e-9)
    }

    @Test
    fun zerosAreAJumper() {
        listOf("0", "000", "0000", "0R0").forEach {
            assertEquals(SmdFormat.JUMPER, SmdCode.resistor(it).first().format, it)
            assertEquals(0.0, ohms(it), 0.0, it)
        }
    }

    @Test
    fun eia96FollowsTheTable() {
        assertEquals(10_000.0, ohms("01C"), 1e-6)
        assertEquals(12_400_000.0, ohms("10F"), 1e-3)
        assertEquals(47.5, ohms("66X"), 1e-9)
        assertEquals(215.0, ohms("33A"), 1e-9)
        assertEquals(464.0, ohms("65A"), 1e-9)
        assertEquals(976.0, ohms("96A"), 1e-9)
        assertEquals(1240.0, ohms("10H"), 1e-9)
        assertEquals(0.1, ohms("01Z"), 1e-9)
        assertTrue(SmdCode.resistor("97A").isEmpty())
        assertTrue(SmdCode.resistor("00A").isEmpty())
        assertTrue(SmdCode.resistor("hello").isEmpty())
        assertTrue(SmdCode.resistor("").isEmpty())
    }

    @Test
    fun letterRInEiaPositionGivesTwoReadings() {
        val readings = SmdCode.resistor("47R")
        assertEquals(listOf(SmdFormat.DECIMAL_SHORT, SmdFormat.EIA96), readings.map { it.format })
        assertEquals(47.0, readings[0].value, 1e-9)
        assertEquals(3.01, readings[1].value, 1e-9)
    }

    @Test
    fun capacitorCodesCountPicofarads() {
        assertEquals(100e-9, SmdCode.capacitor("104")!!, 1e-15)
        assertEquals(2.2e-6, SmdCode.capacitor("225")!!, 1e-13)
        assertEquals(4.7e-12, SmdCode.capacitor("4R7")!!, 1e-18)
        assertEquals(2.2e-12, SmdCode.capacitor("229")!!, 1e-18)
        assertEquals(22e-12, SmdCode.capacitor("22")!!, 1e-18)
        assertNull(SmdCode.capacitor("000"))
        assertNull(SmdCode.capacitor("1002"))
    }

    @Test
    fun valueGivesEveryMarking() {
        assertEquals(listOf("103", "1002", "01C"), codes(10_000.0))
        assertEquals(listOf("472", "4701", "66B"), codes(4_700.0))
        assertEquals(listOf("4R7", "4R70", "66Y"), codes(4.7))
        assertEquals(listOf("000", "0000"), codes(0.0))
        assertEquals("R047", codes(0.047).first())
        assertTrue(codes(1e12).isEmpty())
    }

    @Test
    fun roundingCarriesIntoTheNextDecade() {
        assertEquals(listOf("101", "1000", "01A"), codes(99.96))
        assertEquals("01C", codes(9_990.0).last())
    }

    @Test
    fun capacitanceGivesItsCode() {
        assertEquals("104", SmdCode.capacitorCode(100e-9))
        assertEquals("472", SmdCode.capacitorCode(4.7e-9))
        assertEquals("4R7", SmdCode.capacitorCode(4.7e-12))
        assertEquals("107", SmdCode.capacitorCode(100e-6))
        assertNull(SmdCode.capacitorCode(1e-3))
    }
}
