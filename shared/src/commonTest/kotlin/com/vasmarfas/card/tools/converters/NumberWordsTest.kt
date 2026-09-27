package com.vasmarfas.card.tools.converters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NumberWordsTest {
    private fun spellRu(text: String, currency: WordsCurrency = WordsCurrency.NONE) =
        NumberWords.spellAmount(NumberWords.parse(text)!!, WordsLang.RU, currency)

    private fun spellEn(text: String, currency: WordsCurrency = WordsCurrency.NONE) =
        NumberWords.spellAmount(NumberWords.parse(text)!!, WordsLang.EN, currency)

    @Test
    fun russianGrammar() {
        assertEquals("один", NumberWords.spell(1, WordsLang.RU))
        assertEquals("две тысячи", NumberWords.spell(2000, WordsLang.RU))
        assertEquals("двадцать одна тысяча", NumberWords.spell(21000, WordsLang.RU))
        assertEquals("одна тысяча один", NumberWords.spell(1001, WordsLang.RU))
        assertEquals("пять тысяч", NumberWords.spell(5000, WordsLang.RU))
        assertEquals("один миллион", NumberWords.spell(1000000, WordsLang.RU))
        assertEquals("минус сто одиннадцать", NumberWords.spell(-111, WordsLang.RU))
        assertEquals("один квадриллион", NumberWords.spell(1_000_000_000_000_000, WordsLang.RU))
    }

    @Test
    fun english() {
        assertEquals("zero", NumberWords.spell(0, WordsLang.EN))
        assertEquals("one hundred twenty-three million four hundred fifty-six thousand seven hundred eighty-nine", NumberWords.spell(123456789, WordsLang.EN))
        assertEquals("One point two five", spellEn("1.25"))
    }

    @Test
    fun currency() {
        assertEquals("Двадцать один рубль 02 копейки", spellRu("21.02", WordsCurrency.RUB))
        assertEquals("Пять рублей 00 копеек", spellRu("5", WordsCurrency.RUB))
        assertEquals("Две тысячи долларов 50 центов", spellRu("2000.5", WordsCurrency.USD))
        assertEquals("One dollar and fifty cents", spellEn("1.5", WordsCurrency.USD))
        assertEquals("Two rubles", spellEn("2", WordsCurrency.RUB))
        assertEquals("Одна тысяча рублей 00 копеек", spellRu("999.999", WordsCurrency.RUB))
    }

    @Test
    fun fractions() {
        assertEquals("Две целых пять десятых", spellRu("2.5"))
        assertEquals("Одна целая двадцать одна сотая", spellRu("1.21"))
        assertEquals("Ноль целых пять сотых", spellRu("0.05"))
    }

    @Test
    fun parsing() {
        assertNull(NumberWords.parse("abc"))
        assertNull(NumberWords.parse("1234567890123456789"))
        assertEquals(1234567L, NumberWords.parse("1 234 567,89")!!.whole)
        assertEquals("89", NumberWords.parse("1 234 567,89")!!.fraction)
        assertEquals(1234567L, NumberWords.parse("1\u00A0234\u202F567,89")!!.whole)
        assertEquals(1234567L, NumberWords.parse("1,234,567.89")!!.whole)
    }
}
