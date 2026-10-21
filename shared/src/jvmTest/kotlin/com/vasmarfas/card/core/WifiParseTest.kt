package com.vasmarfas.card.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WifiParseTest {
    private val english = """
        Interface name : Wi-Fi
        There are 2 networks currently visible.

        SSID 1 : Home: 5G
            Network type            : Infrastructure
            Authentication          : WPA3-Personal
            Encryption              : CCMP
            BSSID 1                 : 60:a4:b7:7c:1d:e3
                 Signal             : 46%
                 Radio type         : 802.11ax
                 Band               : 5 GHz
                 Channel            : 36
                 Bss Load:
                     Connected Stations:         8
                     Channel Utilization:        47 (18 %)
                     Medium Available Capacity:  25490 (815680 us/s)
                 QoS MSCS Supported    : 0
                 Basic rates (Mbps) : 6 12 24
            BSSID 2                 : 60:a4:b7:7c:1d:e4
                 Signal             : 72%
                 Radio type         : 802.11n
                 Band               : 2.4 GHz
                 Channel            : 9
                 QoS MSCS Supported    : 0

        SSID 2 :
            Network type            : Infrastructure
            Authentication          : Open
            Encryption              : None
            BSSID 1                 : 02:1a:11:f0:00:01
                 Signal             : 100%
                 Radio type         : 802.11ax
                 Band               : 6 GHz
                 Channel            : 5
    """.trimIndent()

    private val russian = """
        Имя интерфейса: Беспроводная сеть
        Сейчас видно следующее количество сетей: 1.

        SSID 1: Кухня
            Тип сети:                 Инфраструктура
            Проверка подлинности:     WPA2-Personal
            Шифрование:               CCMP
            BSSID 1                 : 50:c7:bf:8c:b2:8c
                 Сигнал             : 85%
                 Тип радио         : 802.11n
                 Диапазон               : 2,4 ГГц
                 Канал            : 13
                 Поддерживается QoS MSCS: 0
                 Базовая скорость (мбит/с): 1 2 5.5 11
    """.trimIndent()

    @Test
    fun netshInEnglish() {
        val points = parseNetsh(english, connected = "60:A4:B7:7C:1D:E3")
        assertEquals(3, points.size)
        val first = points[0]
        assertEquals("Home: 5G", first.ssid)
        assertEquals("60:A4:B7:7C:1D:E3", first.bssid)
        assertEquals(-77, first.rssi)
        assertEquals(5180, first.frequency)
        assertEquals("WPA3", first.security)
        assertEquals(8, first.stations)
        assertEquals(18, first.utilization)
        assertTrue(first.connected)
        assertEquals(2452, points[1].frequency)
        assertEquals(null, points[1].stations)
        assertEquals("", points[2].ssid)
        assertEquals(5975, points[2].frequency)
        assertEquals("", points[2].security)
    }

    @Test
    fun netshInRussian() {
        val point = parseNetsh(russian, connected = null).single()
        assertEquals("Кухня", point.ssid)
        assertEquals(2472, point.frequency)
        assertEquals("WPA2", point.security)
        assertEquals("802.11n", point.standard)
        assertEquals(-58, point.rssi)
    }

    @Test
    fun connectedInterface() {
        val text = """
            There is 1 interface on the system:

                Name                   : Wi-Fi
                Description            : Intel(R) Wi-Fi 6E AX211 160MHz
                Physical address       : d4:f3:2d:8f:09:9e
                State                  : connected
                SSID                   : Home
                AP BSSID               : 60:a4:b7:7c:1d:e3
                Band                   : 5 GHz
                Channel                : 44
                Network type           : Infrastructure
                Radio type             : 802.11ax
                Authentication         : WPA2-Personal
                Receive rate (Mbps)    : 1201
                Transmit rate (Mbps)   : 960
                Signal                 : 92%
        """.trimIndent()
        val details = netshInterface(text)
        assertEquals("Home", details["SSID"])
        assertEquals("60:A4:B7:7C:1D:E3", details["BSSID"])
        assertEquals("-54", details["RSSI"])
        assertEquals("5220", details["Frequency"])
        assertEquals("1201", details["Link speed"])
        assertEquals("802.11ax", details["Wi-Fi standard"])
        assertEquals(emptyMap(), netshInterface("    State                  : disconnected"))
    }

    @Test
    fun nmcliEscapesColons() {
        val text = "*:Home\\:5G:60\\:A4\\:B7\\:7C\\:1D\\:E3:5180 MHz:80:WPA2 WPA3\n :Cafe:11\\:22\\:33\\:44\\:55\\:66:2437 MHz:40:\n"
        val points = parseNmcli(text)
        assertEquals("Home:5G", points[0].ssid)
        assertEquals("60:A4:B7:7C:1D:E3", points[0].bssid)
        assertEquals("WPA2/WPA3", points[0].security)
        assertTrue(points[0].connected)
        assertEquals(-80, points[1].rssi)
        assertEquals("", points[1].security)
    }

    @Test
    fun systemProfiler() {
        val json = """
            {"SPAirPortDataType":[{"spairport_airport_interfaces":[{"_name":"en0",
              "spairport_current_network_information":{"_name":"Home","spairport_network_channel":"36 (5GHz, 80MHz)",
                "spairport_network_phymode":"802.11ax","spairport_security_mode":"spairport_security_mode_wpa2_personal",
                "spairport_signal_noise":"-52 dBm / -93 dBm"},
              "spairport_airport_other_local_wireless_networks":[{"_name":"Cafe","spairport_network_channel":"6 (2GHz, 20MHz)",
                "spairport_security_mode":"spairport_security_mode_none","spairport_signal_noise":"-71 dBm / -90 dBm"}]}]}]}
        """.trimIndent()
        val points = parseSystemProfiler(json)
        assertEquals(2, points.size)
        assertEquals(5180, points[0].frequency)
        assertEquals(80, points[0].width)
        assertTrue(points[0].connected)
        assertEquals(-71, points[1].rssi)
        assertEquals(2437, points[1].frequency)
        assertEquals(emptyList(), parseSystemProfiler("not json"))
    }
}
