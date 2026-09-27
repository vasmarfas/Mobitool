package com.vasmarfas.card.tools.text

import com.vasmarfas.card.core.Lang
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneticAlphabetTest {
    @Test
    fun wordsFollowTheScriptAndLanguage() {
        assertEquals("Alfa", PhoneticAlphabet.word('a', Lang.EN))
        assertEquals("Альфа", PhoneticAlphabet.word('A', Lang.RU))
        assertEquals("X-ray", PhoneticAlphabet.word('x', Lang.EN))
        assertEquals("Новембер", PhoneticAlphabet.word('n', Lang.RU))
        assertEquals("Экс-рей", PhoneticAlphabet.word('X', Lang.RU))
        assertEquals("Женя", PhoneticAlphabet.word('ж', Lang.EN))
        assertEquals("Иван краткий", PhoneticAlphabet.word('Й', Lang.RU))
        assertEquals("семь", PhoneticAlphabet.word('7', Lang.RU))
        assertEquals("Seven", PhoneticAlphabet.word('7', Lang.EN))
        assertNull(PhoneticAlphabet.word('!', Lang.EN))
    }

    @Test
    fun spellingKeepsSpacesAsGaps() {
        assertEquals(listOf("Bravo", null, "Анна"), PhoneticAlphabet.spell("b а", Lang.EN).map { it.word })
    }
}
