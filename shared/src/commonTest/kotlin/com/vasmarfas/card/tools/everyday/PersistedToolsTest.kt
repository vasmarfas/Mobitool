package com.vasmarfas.card.tools.everyday

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersistedToolsTest {
    @Test
    fun countersRoundTrip() {
        val counters = listOf(Counter("Coffee", 3), Counter("Push-ups"))
        assertEquals(counters, Tally.decode(Tally.encode(counters)))
        assertNull(Tally.decode("not json"))
        assertNull(Tally.decode(null))
    }
}
