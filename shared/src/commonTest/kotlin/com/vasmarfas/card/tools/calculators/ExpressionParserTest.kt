package com.vasmarfas.card.tools.calculators

import com.vasmarfas.card.core.Lang
import com.vasmarfas.card.core.appLang
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExpressionParserTest {
    @Test
    fun precedenceAndParentheses() {
        assertEquals(7.0, ExpressionParser.evaluate("1+2*3"))
        assertEquals(9.0, ExpressionParser.evaluate("(1+2)*3"))
        assertEquals(-4.0, ExpressionParser.evaluate("-2^2"))
        assertEquals(512.0, ExpressionParser.evaluate("2^3^2"))
        assertEquals(0.5, ExpressionParser.evaluate("2^-1"))
        assertEquals(1.0, ExpressionParser.evaluate("7 % 3"))
    }

    @Test
    fun implicitMultiplicationAndConstants() {
        assertEquals(14.0, ExpressionParser.evaluate("2(3+4)"))
        assertEquals(6.0, ExpressionParser.evaluate("(1+2)(2)"))
        assertEquals(2 * PI, ExpressionParser.evaluate("2pi"), 1e-12)
        assertEquals(2000.0, ExpressionParser.evaluate("2e3"))
    }

    @Test
    fun keypadSymbolsDoNotMergeWithNeighbours() {
        assertEquals(2 * PI, ExpressionParser.evaluate("π2"), 1e-12)
        assertEquals(sqrt(2.0), ExpressionParser.evaluate("√2"), 1e-12)
        assertEquals(sqrt(PI), ExpressionParser.evaluate("√π"), 1e-12)
        assertEquals(2 * E, ExpressionParser.evaluate("e2"), 1e-12)
        assertEquals(2 * E - 3, ExpressionParser.evaluate("2×e−3"), 1e-12)
        assertEquals(0.002, ExpressionParser.evaluate("2e−3"), 1e-15)
        assertEquals(3.0, ExpressionParser.evaluate("log2(8)"), 1e-12)
    }

    @Test
    fun commaFollowsTheLanguage() {
        assertEquals(1001.0, ExpressionParser.evaluate("1,000+1"))
        assertEquals(2.5, ExpressionParser.evaluate("1,5+1"))
        appLang = Lang.RU
        try {
            assertEquals(2.0, ExpressionParser.evaluate("1,000+1"))
        } finally {
            appLang = Lang.EN
        }
    }

    @Test
    fun functionsAndFactorial() {
        assertEquals(120.0, ExpressionParser.evaluate("5!"))
        assertEquals(0.5, ExpressionParser.evaluate("sin(30)", degrees = true), 1e-12)
        assertEquals(1.0, ExpressionParser.evaluate("sin(pi/2)"), 1e-12)
        assertEquals(90.0, ExpressionParser.evaluate("asin(1)", degrees = true), 1e-9)
        assertEquals(3.0, ExpressionParser.evaluate("sqrt 9"))
        assertEquals(2.0, ExpressionParser.evaluate("log(100)"))
        assertEquals(3.0, ExpressionParser.evaluate("round(2.5)"))
    }

    @Test
    fun errors() {
        assertFailsWith<ExpressionException> { ExpressionParser.evaluate("1/0") }
        assertFailsWith<ExpressionException> { ExpressionParser.evaluate("2+") }
        assertFailsWith<ExpressionException> { ExpressionParser.evaluate("foo(1)") }
        assertFailsWith<ExpressionException> { ExpressionParser.evaluate("(1+2") }
    }
}
