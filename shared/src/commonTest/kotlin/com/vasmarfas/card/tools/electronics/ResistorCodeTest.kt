package com.vasmarfas.card.tools.electronics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResistorCodeTest {
    @Test
    fun decode() {
        val value = ResistorCode.decode(listOf(ResistorColor.YELLOW, ResistorColor.VIOLET, ResistorColor.RED, ResistorColor.GOLD))!!
        assertEquals(4700.0, value.ohms, 1e-9)
        assertEquals(5.0, value.tolerance, 1e-9)
        assertNull(value.tempco)
        val six = ResistorCode.decode(listOf(ResistorColor.BROWN, ResistorColor.BLACK, ResistorColor.BLACK, ResistorColor.BROWN, ResistorColor.BROWN, ResistorColor.RED))!!
        assertEquals(1000.0, six.ohms, 1e-9)
        assertEquals(1.0, six.tolerance, 1e-9)
        assertEquals(50, six.tempco)
        assertNull(ResistorCode.decode(listOf(ResistorColor.GOLD, ResistorColor.VIOLET, ResistorColor.RED, ResistorColor.GOLD)))
        val fiveBands = listOf(ResistorColor.BROWN, ResistorColor.BLACK, ResistorColor.BLACK, ResistorColor.BROWN, ResistorColor.BROWN)
        assertEquals(250, ResistorCode.decode(fiveBands + ResistorColor.BLACK)?.tempco)
        assertEquals(20, ResistorCode.decode(fiveBands + ResistorColor.GREEN)?.tempco)
        assertEquals(1, ResistorCode.decode(fiveBands + ResistorColor.GREY)?.tempco)
    }

    @Test
    fun encode() {
        assertEquals(
            listOf(ResistorColor.YELLOW, ResistorColor.VIOLET, ResistorColor.RED, ResistorColor.GOLD),
            ResistorCode.encode(4700.0, 4, ResistorColor.GOLD, null),
        )
        assertEquals(
            listOf(ResistorColor.YELLOW, ResistorColor.VIOLET, ResistorColor.BLACK, ResistorColor.BROWN, ResistorColor.BROWN),
            ResistorCode.encode(4700.0, 5, ResistorColor.BROWN, null),
        )
        assertEquals(
            listOf(ResistorColor.GREEN, ResistorColor.BLACK, ResistorColor.SILVER, ResistorColor.GOLD),
            ResistorCode.encode(0.5, 4, ResistorColor.GOLD, null),
        )
        assertNull(ResistorCode.encode(1e12, 4, ResistorColor.GOLD, null))
    }
}
