package com.vasmarfas.card.tools.documents

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvoiceTest {
    private val payee = Payee("Иванов Иван Иванович", "123456789047", "40817810400000000000", "ПАО Банк", "044525225", "30101810400000000225")

    private fun invoice(vararg lines: InvoiceLine) =
        Invoice("12", LocalDate(2026, 11, 4), payee, selfEmployed = true, buyer = "ООО Покупатель", buyerInn = "7707083893", lines = lines.toList())

    // 7707083893: 2·7+4·7+10·0+3·7+5·0+9·8+4·3+6·8+8·9 = 267, 267 % 11 = 3
    // 123456789047: first key 257 % 11 = 4, second 282 % 11 = 7
    @Test
    fun innCheckDigits() {
        assertTrue(InvoiceDoc.innValid("7707083893"))
        assertTrue(InvoiceDoc.innValid("123456789047"))
        assertFalse(InvoiceDoc.innValid("7707083894"))
        assertFalse(InvoiceDoc.innValid("123456789048"))
        assertFalse(InvoiceDoc.innValid("12345"))
    }

    @Test
    fun bikIsNineDigits() {
        assertTrue(InvoiceDoc.bikValid("044525225"))
        assertFalse(InvoiceDoc.bikValid("04452522"))
        assertFalse(InvoiceDoc.bikValid("04452522a"))
    }

    // Bank of Russia key: weights 7 1 3 over the last 3 digits of the BIK and the account, the sum ends in 0
    @Test
    fun accountKeyGoesWithTheBik() {
        assertTrue(InvoiceDoc.correspondentValid("30101810400000000225", "044525225"))
        assertFalse(InvoiceDoc.correspondentValid("30101810500000000225", "044525225"))
        assertFalse(InvoiceDoc.correspondentValid("30101810400000000225", "044525593"))
        assertFalse(InvoiceDoc.correspondentValid("40702810400000000225", "044525225"))
        assertTrue(InvoiceDoc.accountValid("40702810500000000002", "044525225"))
        assertFalse(InvoiceDoc.accountValid("40702810100000000002", "044525225"))
    }

    @Test
    fun amountsAreKopecks() {
        assertEquals(123_450L, InvoiceDoc.kopecks("1 234,50"))
        assertEquals(10L, InvoiceDoc.kopecks("0.1"))
        assertNull(InvoiceDoc.kopecks("-1"))
        assertNull(InvoiceDoc.kopecks("много"))
        assertEquals("1 234,50", InvoiceDoc.rubles(123_450))
        assertEquals("0,05", InvoiceDoc.rubles(5))
        assertEquals(1_000L, InvoiceLine("Услуга", 0.5, "ч", 2_000).sumKopecks)
    }

    @Test
    fun amountInWords() {
        assertEquals("Одна тысяча двести тридцать четыре рубля 50 копеек", InvoiceDoc.inWords(123_450))
        assertEquals("Двадцать один рубль 01 копейка", InvoiceDoc.inWords(2_101))
        assertEquals("Сто рублей 00 копеек", InvoiceDoc.inWords(10_000))
    }

    @Test
    fun dates() {
        assertEquals("4 ноября 2026 г.", InvoiceDoc.longDate(LocalDate(2026, 11, 4)))
        assertEquals("31 января 2027 г.", InvoiceDoc.longDate(LocalDate(2027, 1, 31)))
    }

    @Test
    fun qrPayloadFollowsTheStandard() {
        val text = InvoiceDoc.qrPayload(invoice(InvoiceLine("Разработка", 2.0, "ч", 150_000), InvoiceLine("Правки", 1.0, "усл.", 50)))
        assertEquals(
            "ST00012|Name=Иванов Иван Иванович|PersonalAcc=40817810400000000000|BankName=ПАО Банк|BIC=044525225|" +
                "CorrespAcc=30101810400000000225|PayeeINN=123456789047|Sum=300050|Purpose=Оплата по счёту № 12 от 04.11.2026. Без НДС",
            text,
        )
    }

    @Test
    fun aValueCannotBreakTheFieldList() {
        val odd = Payee("А|Б", payee.inn, payee.account, payee.bank, payee.bik, payee.correspondent)
        val text = InvoiceDoc.qrPayload(Invoice("1", LocalDate(2026, 1, 1), odd, false, "В", "", listOf(InvoiceLine("Г", 1.0, "шт.", 100))))
        assertTrue("Name=А Б|" in text)
        assertEquals(9, text.split('|').size)
    }

    @Test
    fun theActHasNoQrAndBothAreTwoPages() {
        val line = InvoiceLine("Разработка", 1.0, "ч", 150_000)
        val qr = Block.Picture(ByteArray(0))
        assertTrue(InvoiceDoc.build(invoice(line), InvoiceKind.ACT, null).blocks.none { it is Block.Picture })
        assertTrue(qr in InvoiceDoc.build(invoice(line), InvoiceKind.INVOICE, qr).blocks)
        assertEquals(1, InvoiceDoc.build(invoice(line), InvoiceKind.BOTH, qr).blocks.count { it == Block.PageBreak })
    }
}
