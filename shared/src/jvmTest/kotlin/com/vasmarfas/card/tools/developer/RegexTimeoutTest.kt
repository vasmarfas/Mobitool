package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.tools.text.FindReplace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RegexTimeoutTest {
    @Test
    fun runawayBacktrackingIsStopped() {
        val result = RegexTester.run("(a+)+\\1$", "a".repeat(34) + "!", ignoreCase = false, multiline = false, dotAll = false, replacement = null)
        assertTrue(result.timedOut)
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun findReplaceStopsTheSamePattern() = runBlocking {
        val text = "a".repeat(34) + "!"
        val result = FindReplace.runGuarded(text, "(a+)+\\1$", "x", ignoreCase = false, useRegex = true, wholeWord = false)
        assertTrue(result.timedOut)
        assertEquals(text, result.output)
    }

    @Test
    fun findReplaceRunsNormalPatterns() = runBlocking {
        val result = FindReplace.runGuarded("a1 b2", "([a-z])(\\d)", "$2$1", ignoreCase = false, useRegex = true, wholeWord = false)
        assertEquals("1a 2b", result.output)
        assertEquals(2, result.count)
        val broken = FindReplace.runGuarded("x", "(", "", ignoreCase = false, useRegex = true, wholeWord = false)
        assertNotNull(broken.error)
        assertFalse(broken.timedOut)
    }
}
