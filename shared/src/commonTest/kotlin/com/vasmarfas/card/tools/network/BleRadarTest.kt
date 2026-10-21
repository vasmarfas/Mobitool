package com.vasmarfas.card.tools.network
import com.vasmarfas.card.core.BleAdvert
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BleRadarTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun advert(
        manufacturer: Map<Int, ByteArray> = emptyMap(),
        services: List<String> = emptyList(),
        serviceData: Map<String, ByteArray> = emptyMap(),
        rssi: Int = -60,
        txPower: Int? = null,
    ) = BleAdvert("AA:BB:CC:DD:EE:FF", null, rssi, txPower, true, manufacturer, services, serviceData)

    @Test
    fun appleFindMy() {
        val separated = advert(mapOf(0x004C to bytes(0x12, 0x19, 0x10) + ByteArray(24)))
        assertEquals(BleKind.AIRTAG, BleDecoder.kind(separated))
        assertEquals(true, BleDecoder.findMy(separated)?.second)
        val nearOwner = advert(mapOf(0x004C to bytes(0x12, 0x02, 0x24, 0x00)))
        assertEquals(BleKind.FIND_MY, BleDecoder.kind(nearOwner))
        assertEquals(false, BleDecoder.findMy(nearOwner)?.second)
        assertEquals(BleKind.AIRPODS, BleDecoder.kind(advert(mapOf(0x004C to bytes(0x07, 0x19, 0x01, 0x0E, 0x20)))))
        assertEquals(BleKind.APPLE, BleDecoder.kind(advert(mapOf(0x004C to bytes(0x10, 0x05, 0x01)))))
    }

    @Test
    fun beacons() {
        val uuid = (0 until 16).map { 0xA0 + it }.toIntArray()
        val ibeacon = advert(mapOf(0x004C to bytes(0x02, 0x15, *uuid, 0x00, 0x07, 0x01, 0x2C, 0xC5)))
        assertEquals(BleKind.IBEACON, BleDecoder.kind(ibeacon))
        BleDecoder.iBeacon(ibeacon)!!.let {
            assertEquals("A0A1A2A3-A4A5-A6A7-A8A9-AAABACADAEAF", it.uuid)
            assertEquals(7, it.major)
            assertEquals(300, it.minor)
            assertEquals(-59, it.measuredPower)
        }
        val url = advert(serviceData = mapOf("FEAA" to bytes(0x10, 0xEE, 0x03) + "example".encodeToByteArray() + bytes(0x07)))
        assertEquals(BleKind.EDDYSTONE, BleDecoder.kind(url))
        assertEquals("https://example.com", BleDecoder.eddystoneUrl(url))
        assertNull(BleDecoder.eddystoneUrl(advert()))
    }

    @Test
    fun trackersAndPairing() {
        assertEquals(BleKind.GOOGLE_TRACKER, BleDecoder.kind(advert(serviceData = mapOf("FEAA" to bytes(0x41) + ByteArray(20)))))
        assertEquals(BleKind.SMARTTAG, BleDecoder.kind(advert(serviceData = mapOf("FD5A" to ByteArray(10)))))
        assertEquals(BleKind.TILE, BleDecoder.kind(advert(services = listOf("FEED"))))
        assertEquals(BleKind.FAST_PAIR, BleDecoder.kind(advert(serviceData = mapOf("FE2C" to ByteArray(3)))))
        assertEquals(BleKind.SWIFT_PAIR, BleDecoder.kind(advert(mapOf(0x0006 to bytes(0x03, 0x00, 0x80)))))
        assertEquals(BleKind.OTHER, BleDecoder.kind(advert(mapOf(0x0075 to bytes(0x42, 0x09)))))
        assertTrue(BleKind.TILE.tracker && !BleKind.AIRPODS.tracker)
    }

    @Test
    fun distance() {
        assertTrue(abs(BleDecoder.distance(advert(rssi = -59)) - 1.0) < 1e-9)
        assertTrue(abs(BleDecoder.distance(advert(rssi = -84)) - 10.0) < 1e-9)
        assertTrue(abs(BleDecoder.distance(advert(rssi = -41, txPower = 0)) - 1.0) < 1e-9)
    }
}
