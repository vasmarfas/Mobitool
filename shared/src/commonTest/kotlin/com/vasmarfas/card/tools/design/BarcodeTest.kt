package com.vasmarfas.card.tools.design

import io.github.alexzhirkevich.qrose.oned.BarcodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BarcodeTest {
    @Test
    fun checkDigits() {
        assertEquals(7, Ean13.checksum("590123412345"))
        assertEquals(4, Ean13.checksum("9638507"))
        assertEquals(2, Ean13.checksum("03600029145"))
        assertEquals("5901234123457", Ean13.complete("590123412345"))
        assertTrue(Ean13.verify("5901234123457"))
        assertTrue(Ean13.verify("96385074"))
        assertFalse(Ean13.verify("5901234123450"))
        assertNull(Ean13.checksum("59012a"))
    }

    @Test
    fun validation() {
        assertNull(validateBarcode(BarcodeType.EAN13, "5901234123457"))
        assertNotNull(validateBarcode(BarcodeType.EAN13, "590123412345"))
        assertNotNull(validateBarcode(BarcodeType.EAN13, "5901234123450"))
        assertNull(validateBarcode(BarcodeType.EAN8, "96385074"))
        assertNull(validateBarcode(BarcodeType.UPCA, "036000291452"))
        assertNotNull(validateBarcode(BarcodeType.ITF, "1234567"))
        assertNull(validateBarcode(BarcodeType.ITF, "12345678"))
        assertNull(validateBarcode(BarcodeType.Code39, "VASMARFAS-39"))
        assertNull(validateBarcode(BarcodeType.Code39, "vasmarfas-39"))
        assertNotNull(validateBarcode(BarcodeType.Code39, "lower!"))
        assertNull(validateBarcode(BarcodeType.Code39, "A".repeat(80)))
        assertNotNull(validateBarcode(BarcodeType.Code39, "A".repeat(81)))
        assertNotNull(validateBarcode(BarcodeType.ITF, "12".repeat(41)))
        assertNull(validateBarcode(BarcodeType.Codabar, "A12345B"))
        assertNotNull(validateBarcode(BarcodeType.Codabar, "12345"))
        assertNull(validateBarcode(BarcodeType.Code128, "VASMARFAS 128"))
        assertNotNull(validateBarcode(BarcodeType.Code128, ""))
    }

    @Test
    fun qrPayloads() {
        assertEquals("WIFI:T:WPA;S:home;P:pa\\;ss;H:false;;", QrPayload.wifi("home", "pa;ss", "WPA", false))
        assertEquals("tel:+79781234567", QrPayload.tel("+79781234567"))
        assertEquals("geo:44.95,34.1", QrPayload.geo("44.95", "34.1"))
        assertEquals("https://t.me/durov", QrPayload.telegram("@durov"))
        assertEquals("mailto:a@b.c?subject=Hi%20there", QrPayload.mailto("a@b.c", "Hi there", ""))
        assertTrue(QrPayload.vcard("Ann", "", "+7", "", "").startsWith("BEGIN:VCARD"))
    }

    @Test
    fun wifiCodesAreReadBack() {
        val wifi = assertNotNull(QrPayload.parseWifi(QrPayload.wifi("home; 5G", "pa;ss:1\\", "WPA", true)))
        assertEquals("home; 5G", wifi.ssid)
        assertEquals("pa;ss:1\\", wifi.password)
        assertEquals("WPA", wifi.security)
        assertTrue(wifi.hidden)
        assertEquals("nopass", QrPayload.parseWifi("WIFI:S:cafe;;")?.security)
        assertNull(QrPayload.parseWifi("https://example.com"))
    }
}
