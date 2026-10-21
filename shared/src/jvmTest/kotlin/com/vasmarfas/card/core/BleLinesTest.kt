package com.vasmarfas.card.core

import com.vasmarfas.card.tools.developer.hexToBytes
import com.vasmarfas.card.tools.developer.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BleLinesTest {
    private fun BleLines.line(text: String) = read(text.split('\t'))

    // lines the watcher printed on a Windows laptop
    @Test
    fun windowsWatcher() {
        val lines = BleLines()
        val first = lines.line("10d561dd5864\t-73\t\t0\t0106\t0201A2\t1601A20044A3C60CC499D0FC6B432437F62303F1")!!
        assertEquals("10:D5:61:DD:58:64", first.id)
        assertEquals(true, first.connectable)
        assertEquals(listOf("A201"), first.services)
        assertEquals("0044a3c60cc499d0fc6b432437f62303f1", first.serviceData["A201"]!!.toHex())
        assertNull(first.name)
        val merged = lines.line("10d561dd5864\t-70\t\t4\t095459\tffD007800300000C0001D01E48672009D2FE0AACF20474E086")!!
        assertEquals("TY", merged.name)
        assertEquals(-70, merged.rssi)
        assertEquals(true, merged.connectable)
        assertEquals(listOf(0x07D0), merged.manufacturer.keys.toList())
        assertEquals(listOf("A201"), merged.services)

        val tag = lines.line("3d8a7e9d793b\t-59\t\t3\t16F3FE4A17234C46504C11329DE368A32E2F967C099795196459599CAC4C")!!
        assertEquals(false, tag.connectable)
        assertEquals(listOf("FEF3"), tag.serviceData.keys.toList())
        assertNull(lines.line("stop\tRadioNotAvailable"))
    }

    @Test
    fun longUuids() {
        val heartRate = hexToBytes("FB349B5F80000080001000000D180000")!!
        val advert = bleAdvert("A", -60, null, null, listOf(0x07 to heartRate, 0x0A to byteArrayOf(-12)))
        assertEquals(listOf("180D"), advert.services)
        assertEquals(-12, advert.txPower)
    }

    // bluetoothctl from BlueZ 5.7x, colour codes and all
    @Test
    fun bluetoothctl() {
        val lines = BluezLines()
        val output = listOf(
            "Discovery started",
            "[\u001B[0;92mNEW\u001B[0m] Device 5C:F3:70:9A:12:34 5C-F3-70-9A-12-34",
            "[\u001B[0;93mCHG\u001B[0m] Device 5C:F3:70:9A:12:34 ManufacturerData Key: 0x004c (76)",
            "[\u001B[0;93mCHG\u001B[0m] Device 5C:F3:70:9A:12:34 ManufacturerData Value:",
            "  12 19 00 9a 3f 31 bb 2e 5d 04 22 87 30 b5 dd 05  ....?1..].\".0...",
            "  14 c5 03                                         ...",
            "[\u001B[0;93mCHG\u001B[0m] Device 5C:F3:70:9A:12:34 RSSI: 0xffffffbd (-67)",
            "[NEW] Device AA:BB:CC:DD:EE:FF Galaxy Buds2",
            "[CHG] Device AA:BB:CC:DD:EE:FF UUIDs: 0000fd6f-0000-1000-8000-00805f9b34fb",
            "[CHG] Device AA:BB:CC:DD:EE:FF RSSI: -58",
        ).mapNotNull(lines::read)
        assertEquals(2, output.size)
        val tag = output[0]
        assertEquals("5C:F3:70:9A:12:34", tag.id)
        assertEquals(-67, tag.rssi)
        assertNull(tag.name)
        assertEquals("12 19 00 9a 3f 31 bb 2e 5d 04 22 87 30 b5 dd 05 14 c5 03", tag.manufacturer.getValue(0x004C).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
        assertEquals("Galaxy Buds2", output[1].name)
        assertEquals(listOf("FD6F"), output[1].services)
        assertEquals(BleProblem.OFF, lines.problem("Failed to start discovery: org.bluez.Error.NotReady"))
        assertNull(lines.problem("Failed to start discovery: org.bluez.Error.InProgress"))
    }
}

