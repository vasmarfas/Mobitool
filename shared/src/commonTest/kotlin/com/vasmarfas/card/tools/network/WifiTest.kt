package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.Wifi
import com.vasmarfas.card.core.WifiAccessPoint
import com.vasmarfas.card.core.WifiBand
import com.vasmarfas.card.core.WifiElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WifiTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun channelsAndFrequencies() {
        assertEquals(1, Wifi.channel(2412))
        assertEquals(14, Wifi.channel(2484))
        assertEquals(36, Wifi.channel(5180))
        assertEquals(165, Wifi.channel(5825))
        assertEquals(5, Wifi.channel(5975))
        assertEquals(2, Wifi.channel(5935))
        for (band in WifiBand.entries) for (channel in WifiChannels.channels(band)) {
            assertEquals(channel, Wifi.channel(Wifi.frequency(channel, band)), "$band $channel")
            assertEquals(band, WifiBand.of(Wifi.frequency(channel, band)))
        }
        assertEquals(-59, Wifi.percentToDbm(82))
        assertEquals("Wi-Fi 6E", Wifi.generation("802.11ax", WifiBand.GHZ_6))
        assertEquals("Wi-Fi 5", Wifi.generation("802.11ac", WifiBand.GHZ_5))
    }

    @Test
    fun securityFromAndroidCapabilities() {
        assertEquals("WPA2", Wifi.securityOf("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]"))
        assertEquals("WPA2/WPA3", Wifi.securityOf("[RSN-PSK+SAE-CCMP][ESS][MFPC]"))
        assertEquals("WPA3", Wifi.securityOf("[RSN-SAE-CCMP][ESS][MFPR]"))
        assertEquals("WPA", Wifi.securityOf("[WPA-PSK-TKIP][ESS]"))
        assertEquals("WPA2-Enterprise", Wifi.securityOf("[RSN-EAP/SHA1-CCMP][ESS]"))
        assertEquals("WPA3-Enterprise", Wifi.securityOf("[RSN-EAP-SUITE-B-192-GCMP-256][ESS]"))
        assertEquals("OWE", Wifi.securityOf("[RSN-OWE-CCMP][ESS]"))
        assertEquals("WEP", Wifi.securityOf("[WEP][ESS]"))
        assertEquals("", Wifi.securityOf("[ESS]"))
    }

    @Test
    fun informationElements() {
        val info = Wifi.elementFeatures(
            listOf(
                WifiElement(7, 0, "RU ".encodeToByteArray()),
                WifiElement(11, 0, bytes(12, 0, 64, 0, 0)),
                WifiElement(45, 0, bytes(0xEF, 0x09, 0x17, 0xFF, 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
                // VHT: four bytes of capabilities, then an Rx map with streams 1-3 supported
                WifiElement(191, 0, bytes(0, 0, 0, 0, 0b11101010, 0xFF, 0, 0, 0, 0, 0, 0)),
                WifiElement(54, 0, bytes(1, 2, 3)),
                WifiElement(70, 0, bytes(0x73, 0, 0, 0, 0)),
                WifiElement(127, 0, bytes(4, 0, 8, 0)),
                WifiElement(221, 0, bytes(0x00, 0x50, 0xF2, 4, 0x10, 0x4A)),
            ),
        )
        assertEquals("RU", info.country)
        assertEquals(12, info.stations)
        assertEquals(25, info.utilization)
        assertEquals(3, info.streams)
        assertEquals(setOf("802.11r", "802.11k", "802.11v", "WPS"), info.features)
        val he = Wifi.elementFeatures(listOf(WifiElement(255, 35, ByteArray(17) + bytes(0b11111010, 0xFF))))
        assertEquals(2, he.streams)
    }

    private fun hex(value: Int) = (value and 0xFF).toString(16).padStart(2, '0').uppercase()

    private fun ap(ssid: String, rssi: Int, frequency: Int, width: Int = 20, center: Int = frequency) =
        WifiAccessPoint(ssid, "00:11:${hex(ssid.hashCode())}:${hex(rssi)}:44:55", rssi, frequency, width, center)

    @Test
    fun channelLoadAndChoice() {
        val own = ap("own", -30, 2462)
        val guest = own.copy(ssid = "guest", bssid = "02" + own.bssid.drop(2))
        val points = listOf(ap("a", -40, 2412), ap("b", -45, 2417), own, guest, ap("c", -80, 2437))
        val load = WifiChannels.load(points, WifiBand.GHZ_2_4, own).associateBy { it.channel }
        assertEquals(2, load.getValue(1).networks)
        assertEquals(0, load.getValue(13).networks)
        val choices = WifiChannels.choices(points, WifiBand.GHZ_2_4, own)
        assertEquals("11", choices.first().label)
        assertEquals("1", choices.last().label)
        val five = WifiChannels.choices(listOf(ap("x", -50, 5180, 80, 5210)), WifiBand.GHZ_5, null)
        assertEquals(6, five.size)
        assertTrue(five.first().load == 0.0 && !five.first().dfs)
        assertEquals("36–48", five.last().label)
        assertEquals(1, five.last().networks)
        assertEquals(-50, powerDbm(five.last().load))
    }

    @Test
    fun surveyInterpolation() {
        val survey = Survey(
            1, "flat", 2f, false,
            listOf(
                SurveyPoint(0.1f, 0.5f, rssi = -40, signals = mapOf("A" to -40, "B" to -80)),
                SurveyPoint(0.9f, 0.5f, rssi = -80, signals = mapOf("A" to -85, "B" to -45)),
            ),
        )
        val grid = SurveyMap.grid(survey, SurveyMetric.SIGNAL, null, 20)
        assertEquals(10, grid.rows)
        val left = grid[2, 5]
        val middle = grid[10, 5]
        assertTrue(left > -45, "near the first point: $left")
        assertTrue(middle.isNaN(), "half the width away from both points is past the reach")
        assertTrue(grid[0, 0].isNaN() || grid[0, 0] > -45)
        val zones = SurveyMap.accessPointGrid(survey, listOf("A", "B"), 20)
        assertEquals(0.0, zones[1, 5])
        assertEquals(1.0, zones[18, 5])
        assertEquals(listOf("A", "B"), SurveyMap.accessPoints(survey))
    }
}
