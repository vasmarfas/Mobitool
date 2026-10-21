package com.vasmarfas.card.core

data class WifiAccessPoint(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val width: Int = 20,
    val centerFrequency: Int = frequency,
    val security: String = "",
    val standard: String = "",
    val stations: Int? = null,
    val utilization: Int? = null,
    val streams: Int? = null,
    val country: String? = null,
    val features: Set<String> = emptySet(),
    val connected: Boolean = false,
    val approximate: Boolean = false,
) {
    val band: WifiBand get() = WifiBand.of(frequency)
    val channel: Int get() = Wifi.channel(frequency)
    val generation: String get() = Wifi.generation(standard, band)
}

enum class WifiBand(val label: String) {
    GHZ_2_4("2.4"), GHZ_5("5"), GHZ_6("6");

    companion object {
        fun of(frequency: Int): WifiBand = when {
            frequency < 3000 -> GHZ_2_4
            frequency < 5925 -> GHZ_5
            else -> GHZ_6
        }
    }
}

enum class WifiProblem { NO_PERMISSION, LOCATION_OFF, WIFI_OFF, UNSUPPORTED }

class WifiScan(val points: List<WifiAccessPoint>, val fresh: Boolean, val problem: WifiProblem? = null)

class WifiElement(val id: Int, val extension: Int, val bytes: ByteArray)

expect suspend fun scanWifi(): WifiScan

// Android allows four scans in two minutes unless a developer option lifts it, HIDDEN: the options are off
enum class ScanLimit { OFF, ON, HIDDEN }

expect fun wifiScanLimit(): ScanLimit?

expect fun openScanLimitSettings()

object Wifi {
    fun channel(frequency: Int): Int = when {
        frequency == 2484 -> 14
        frequency < 3000 -> (frequency - 2407) / 5
        frequency < 5925 -> (frequency - 5000) / 5
        frequency == 5935 -> 2
        else -> (frequency - 5950) / 5
    }

    fun frequency(channel: Int, band: WifiBand): Int = when (band) {
        WifiBand.GHZ_2_4 -> if (channel == 14) 2484 else 2407 + 5 * channel
        WifiBand.GHZ_5 -> 5000 + 5 * channel
        WifiBand.GHZ_6 -> if (channel == 2) 5935 else 5950 + 5 * channel
    }

    // Windows and nmcli report a 0..100 quality that is linear in dBm between -100 and -50
    fun percentToDbm(percent: Int): Int = percent.coerceIn(0, 100) / 2 - 100

    fun generation(standard: String, band: WifiBand): String = when {
        standard.endsWith("be") -> "Wi-Fi 7"
        standard.endsWith("ax") -> if (band == WifiBand.GHZ_6) "Wi-Fi 6E" else "Wi-Fi 6"
        standard.endsWith("ac") -> "Wi-Fi 5"
        standard.endsWith("n") -> "Wi-Fi 4"
        else -> ""
    }

    fun securityOf(capabilities: String): String {
        val caps = capabilities.uppercase()
        val rsn = "RSN" in caps || "WPA2" in caps
        val sae = "SAE" in caps
        val psk = "PSK" in caps
        return when {
            "EAP" in caps -> (if ("SUITE_B" in caps || "SUITE-B" in caps) "WPA3" else if (rsn) "WPA2" else "WPA") + "-Enterprise"
            sae && psk -> "WPA2/WPA3"
            sae -> "WPA3"
            psk -> if (rsn) "WPA2" else "WPA"
            "OWE" in caps -> "OWE"
            "WEP" in caps -> "WEP"
            else -> ""
        }
    }

    fun elementFeatures(elements: List<WifiElement>): WifiElementInfo {
        var info = WifiElementInfo()
        val features = mutableSetOf<String>()
        for (element in elements) {
            val b = element.bytes
            fun u8(at: Int) = b[at].toInt() and 0xFF
            fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
            fun streamsFromMap(at: Int): Int? {
                if (b.size < at + 2) return null
                val map = u16(at)
                return (0 until 8).lastOrNull { (map shr (2 * it)) and 3 != 3 }?.plus(1)
            }
            when (element.id) {
                7 -> if (b.size >= 2) info = info.copy(country = b.copyOfRange(0, 2).decodeToString().trim().ifEmpty { null })
                11 -> if (b.size >= 3) info = info.copy(stations = u16(0), utilization = u8(2) * 100 / 255)
                45 -> if (b.size >= 7) info = info.copy(streams = maxOf(info.streams ?: 0, (3..6).count { u8(it) != 0 }))
                54 -> features += "802.11r"
                70 -> features += "802.11k"
                127 -> if (b.size >= 3 && u8(2) and 0x08 != 0) features += "802.11v"
                191 -> streamsFromMap(4)?.let { info = info.copy(streams = maxOf(info.streams ?: 0, it)) }
                221 -> if (b.size >= 4 && u8(0) == 0x00 && u8(1) == 0x50 && u8(2) == 0xF2 && u8(3) == 4) features += "WPS"
                255 -> if (element.extension == 35) streamsFromMap(17)?.let { info = info.copy(streams = maxOf(info.streams ?: 0, it)) }
            }
        }
        return info.copy(features = features)
    }
}

data class WifiElementInfo(
    val stations: Int? = null,
    val utilization: Int? = null,
    val streams: Int? = null,
    val country: String? = null,
    val features: Set<String> = emptySet(),
)
