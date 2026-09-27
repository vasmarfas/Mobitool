package com.vasmarfas.card.tools.developer

import kotlin.test.Test
import kotlin.test.assertTrue

class RegexTimeoutTest {
    @Test
    fun runawayBacktrackingIsStopped() {
        val result = RegexTester.run("(a+)+\\1$", "a".repeat(34) + "!", ignoreCase = false, multiline = false, dotAll = false, replacement = null)
        assertTrue(result.timedOut)
        assertTrue(result.matches.isEmpty())
    }
}
