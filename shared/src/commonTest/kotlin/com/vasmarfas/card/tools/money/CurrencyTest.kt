package com.vasmarfas.card.tools.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CurrencyTest {
    private val sample = """{"result":"success","base_code":"USD","rates":{"USD":1,"RUB":92.5,"EUR":0.92,"JPY":150}}"""

    @Test
    fun parseAndConvert() {
        val rates = Currency.parse(sample)!!
        assertEquals(4, rates.size)
        assertEquals(92.5, rates.getValue("RUB"), 1e-9)
        assertEquals(9250.0, Currency.convert(100.0, "USD", "RUB", rates)!!, 1e-9)
        assertEquals(1.0, Currency.convert(92.5, "RUB", "USD", rates)!!, 1e-9)
        assertEquals(100.54, Currency.convert(1.0, "EUR", "RUB", rates)!!, 0.01)
        assertNull(Currency.convert(1.0, "USD", "XXX", rates))
    }

    @Test
    fun copiedAmountMatchesTheShownOne() {
        assertEquals("0.01234", money(0.01234, grouping = false))
        assertEquals("1 234.5", money(1234.5))
        assertEquals("1234.5", money(1234.5, grouping = false))
    }

    @Test
    fun invalidPayload() {
        assertNull(Currency.parse("not json"))
        assertNull(Currency.parse("""{"rates":{"EUR":0.9}}"""))
        assertNull(Currency.parse("""{"result":"error"}"""))
    }
}
