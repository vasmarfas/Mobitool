package com.vasmarfas.card.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Base64

private val os = (System.getProperty("os.name") ?: "").lowercase()

private val ipv4 = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""")

private val macAddress = Regex("""^([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}$""")

private val keyValue = Regex("""^\s*(.+?)\s*:(?:\s+(.*?))?\s*$""")

private val bandValue = Regex("""^\d+([.,]\d+)? \S*(Hz|Гц)$""")

private fun command(vararg args: String): String = runCatching {
    val process = ProcessBuilder(*args).redirectErrorStream(true).start()
    process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.also { process.waitFor() }
}.getOrDefault("")

// netsh writes in the console code page, which mangles non-Latin network names unless it is UTF-8
private fun netsh(vararg args: String): String = command("cmd", "/c", "chcp 65001 >nul & netsh " + args.joinToString(" "))

private fun file(path: String): String = runCatching { File(path).readText() }.getOrDefault("")

actual suspend fun wifiDetails(): Map<String, String> = withContext(Dispatchers.IO) {
    val gateway = when {
        "win" in os -> windowsGateway(command("route", "print", "-4", "0.0.0.0"))
        "mac" in os -> command("route", "-n", "get", "default").lines().firstOrNull { it.trim().startsWith("gateway:") }?.substringAfter(':')?.trim()
        else -> linuxGateway(file("/proc/net/route"))
    }
    val dns = when {
        "win" in os -> ipv4.findAll(command("netsh", "interface", "ipv4", "show", "dnsservers")).map { it.value }.toList()
        "mac" in os -> command("scutil", "--dns").lines().filter { it.trim().startsWith("nameserver[") }.map { it.substringAfter(':').trim() }
        else -> file("/etc/resolv.conf").lines().filter { it.startsWith("nameserver") }.map { it.substringAfter("nameserver").trim() }
    }.distinct()
    val wifi = when {
        "win" in os -> netshInterface(netsh("wlan", "show", "interfaces"))
        "mac" in os -> emptyMap()
        else -> parseNmcli(command(*nmcli("no"))).firstOrNull { it.connected }?.let { ap ->
            mapOf("SSID" to ap.ssid, "BSSID" to ap.bssid, "RSSI" to ap.rssi.toString(), "Frequency" to ap.frequency.toString())
        }.orEmpty()
    }
    buildMap {
        put("Gateway", gateway.orEmpty())
        if (dns.isNotEmpty()) put("DNS", dns.joinToString(", "))
        putAll(wifi)
    }
}

internal fun windowsGateway(routes: String): String? = routes.lines()
    .map { it.trim().split(Regex("\\s+")) }
    .filter { it.size >= 5 && it[0] == "0.0.0.0" && it[1] == "0.0.0.0" && ipv4.matches(it[2]) }
    .minByOrNull { it[4].toIntOrNull() ?: Int.MAX_VALUE }
    ?.get(2)

// /proc/net/route keeps addresses as little-endian hex
internal fun linuxGateway(table: String): String? = table.lines().drop(1)
    .map { it.trim().split(Regex("\\s+")) }
    .filter { it.size >= 7 && it[1] == "00000000" && it[2] != "00000000" }
    .minByOrNull { it[6].toIntOrNull() ?: Int.MAX_VALUE }
    ?.get(2)?.chunked(2)?.reversed()?.joinToString(".") { it.toInt(16).toString() }

// netsh only lists what the last system scan saw; WlanScan asks the driver for a new one, and the
// wlanapi call goes through PowerShell because the JVM has no other way to reach it
private val wlanScanScript = """
    Add-Type -TypeDefinition @'
    using System; using System.Runtime.InteropServices;
    public static class WlanScanner {
      [DllImport("wlanapi.dll")] static extern int WlanOpenHandle(uint v, IntPtr r, out uint nv, out IntPtr h);
      [DllImport("wlanapi.dll")] static extern int WlanEnumInterfaces(IntPtr h, IntPtr r, out IntPtr list);
      [DllImport("wlanapi.dll")] static extern int WlanScan(IntPtr h, ref Guid g, IntPtr ssid, IntPtr ie, IntPtr r);
      [DllImport("wlanapi.dll")] static extern void WlanFreeMemory(IntPtr p);
      [DllImport("wlanapi.dll")] static extern int WlanCloseHandle(IntPtr h, IntPtr r);
      public static int Scan() {
        uint nv; IntPtr h;
        if (WlanOpenHandle(2, IntPtr.Zero, out nv, out h) != 0) return -1;
        IntPtr list; WlanEnumInterfaces(h, IntPtr.Zero, out list);
        int n = Marshal.ReadInt32(list), done = 0;
        for (int i = 0; i < n; i++) {
          Guid g = (Guid)Marshal.PtrToStructure(new IntPtr(list.ToInt64() + 8 + i * 532), typeof(Guid));
          if (WlanScan(h, ref g, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero) == 0) done++;
        }
        WlanFreeMemory(list); WlanCloseHandle(h, IntPtr.Zero);
        return done;
      }
    }
    '@
    [WlanScanner]::Scan()
""".trimIndent()

private fun nmcli(rescan: String) =
    arrayOf("nmcli", "-t", "-e", "yes", "-f", "IN-USE,SSID,BSSID,FREQ,SIGNAL,SECURITY", "dev", "wifi", "list", "--rescan", rescan)

actual suspend fun scanWifi(): WifiScan = withContext(Dispatchers.IO) {
    when {
        "win" in os -> {
            val encoded = Base64.getEncoder().encodeToString(wlanScanScript.toByteArray(Charsets.UTF_16LE))
            val fresh = (command("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded).trim().toIntOrNull() ?: 0) > 0
            if (fresh) delay(4000)
            val connected = netshInterface(netsh("wlan", "show", "interfaces"))["BSSID"]
            val output = netsh("wlan", "show", "networks", "mode=bssid")
            val points = parseNetsh(output, connected)
            val problem = when {
                points.isNotEmpty() -> null
                "location" in output.lowercase() -> WifiProblem.LOCATION_OFF
                else -> WifiProblem.WIFI_OFF
            }
            WifiScan(points, fresh, problem)
        }
        "mac" in os -> {
            val points = parseSystemProfiler(command("system_profiler", "SPAirPortDataType", "-json"))
            WifiScan(points, true, if (points.isEmpty()) WifiProblem.WIFI_OFF else null)
        }
        else -> {
            val rescanned = command(*nmcli("yes"))
            val fresh = !rescanned.startsWith("Error")
            val points = parseNmcli(if (fresh) rescanned else command(*nmcli("no")))
            WifiScan(points, fresh, if (points.isEmpty()) WifiProblem.WIFI_OFF else null)
        }
    }
}

internal fun windowsSecurity(authentication: String, cipher: String): String = when {
    authentication.startsWith("WPA") -> authentication.removeSuffix("-Personal")
    authentication.contains("OWE", ignoreCase = true) -> "OWE"
    cipher.contains("WEP", ignoreCase = true) || authentication.startsWith("Shared") -> "WEP"
    else -> ""
}

private fun bandOf(value: String, channel: Int): WifiBand = when {
    value.startsWith("2") -> WifiBand.GHZ_2_4
    value.startsWith("5") -> WifiBand.GHZ_5
    value.startsWith("6") -> WifiBand.GHZ_6
    channel <= 14 -> WifiBand.GHZ_2_4
    else -> WifiBand.GHZ_5
}

// field names follow the system language, so values are recognised by their shape: 81% is the signal,
// 802.11ax the radio, 5 GHz or 2,4 ГГц the band, and the first bare number the channel
internal fun parseNetsh(text: String, connected: String?): List<WifiAccessPoint> {
    val result = mutableListOf<WifiAccessPoint>()
    var ssid = ""
    val header = mutableListOf<String>()
    var bssid: String? = null
    var signal: Int? = null
    var radio = ""
    var band = ""
    var channel: Int? = null
    var stations: Int? = null
    var utilization: Int? = null
    fun flush() {
        val ch = channel
        if (bssid != null && signal != null && ch != null) {
            result += WifiAccessPoint(
                ssid = ssid,
                bssid = bssid!!.uppercase(),
                rssi = Wifi.percentToDbm(signal!!),
                frequency = Wifi.frequency(ch, bandOf(band, ch)),
                security = windowsSecurity(header.getOrElse(1) { "" }, header.getOrElse(2) { "" }),
                standard = radio,
                stations = stations,
                utilization = utilization,
                connected = bssid.equals(connected, ignoreCase = true),
                approximate = true,
            )
        }
        bssid = null; signal = null; radio = ""; band = ""; channel = null; stations = null; utilization = null
    }
    for (line in text.lines()) {
        val (key, value) = keyValue.find(line)?.destructured ?: continue
        when {
            Regex("SSID \\d+").matches(key) -> {
                flush()
                ssid = value
                header.clear()
            }
            Regex("BSSID \\d+").matches(key) && macAddress.matches(value) -> {
                flush()
                bssid = value
            }
            bssid == null -> header += value
            signal == null && Regex("\\d+%").matches(value) -> signal = value.dropLast(1).toInt()
            radio.isEmpty() && value.startsWith("802.11") -> radio = value
            band.isEmpty() && bandValue.matches(value) -> band = value
            channel == null && Regex("\\d+").matches(value) -> channel = value.toInt()
            key.contains("station", ignoreCase = true) || key.contains("станц", ignoreCase = true) -> stations = value.toIntOrNull()
            Regex("\\d+ \\(\\d+ %\\)").matches(value) -> utilization = value.substringAfter('(').substringBefore(' ').toIntOrNull()
        }
    }
    flush()
    return result
}

internal fun netshInterface(text: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    var band = ""
    var channel: Int? = null
    for (line in text.lines()) {
        val (key, value) = keyValue.find(line)?.destructured ?: continue
        when {
            key == "SSID" -> result["SSID"] = value
            "BSSID" in key && macAddress.matches(value) -> result["BSSID"] = value.uppercase()
            "BSSID" !in result -> Unit
            Regex("\\d+%").matches(value) -> result["RSSI"] = Wifi.percentToDbm(value.dropLast(1).toInt()).toString()
            value.startsWith("802.11") -> result["Wi-Fi standard"] = value
            bandValue.matches(value) -> band = value
            channel == null && Regex("\\d+").matches(value) -> channel = value.toInt()
            (key.contains("bps)", ignoreCase = true) || key.contains("бит/с)", ignoreCase = true)) && "Link speed" !in result -> result["Link speed"] = value
        }
    }
    channel?.let { result["Frequency"] = Wifi.frequency(it, bandOf(band, it)).toString() }
    return result
}

// terse nmcli escapes colons inside values as \:
internal fun parseNmcli(text: String): List<WifiAccessPoint> = text.lines().mapNotNull { line ->
    val fields = line.split(Regex("(?<!\\\\):")).map { it.replace("\\:", ":") }
    if (fields.size < 6) return@mapNotNull null
    val frequency = fields[3].substringBefore(' ').toIntOrNull() ?: return@mapNotNull null
    val security = fields[5]
    WifiAccessPoint(
        ssid = fields[1],
        bssid = fields[2].uppercase(),
        rssi = Wifi.percentToDbm(fields[4].toIntOrNull() ?: 0),
        frequency = frequency,
        security = when {
            "802.1X" in security -> "WPA2-Enterprise"
            "WPA3" in security && "WPA2" in security -> "WPA2/WPA3"
            "WPA3" in security -> "WPA3"
            "WPA2" in security -> "WPA2"
            "WPA1" in security -> "WPA"
            "WEP" in security -> "WEP"
            "OWE" in security -> "OWE"
            else -> ""
        },
        connected = fields[0].trim() == "*",
        approximate = true,
    )
}

// shortcut: system_profiler names no BSSID and needs location access for SSIDs since macOS 14.4,
// a CoreWLAN bridge would lift both
internal fun parseSystemProfiler(json: String): List<WifiAccessPoint> = runCatching {
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()
    fun point(network: JsonObject, connected: Boolean): WifiAccessPoint? {
        val channel = network.text("spairport_network_channel")
        val number = channel.substringBefore(' ').toIntOrNull() ?: return null
        val details = channel.substringAfter('(', "").substringBefore(')').split(',').map { it.trim() }
        val band = bandOf(details.firstOrNull().orEmpty().replace("GHz", " GHz"), number)
        val rssi = network.text("spairport_signal_noise").substringBefore(" dBm").trim().toIntOrNull() ?: return null
        val name = network.text("_name")
        return WifiAccessPoint(
            ssid = name,
            bssid = "$name/$number",
            rssi = rssi,
            frequency = Wifi.frequency(number, band),
            width = details.getOrNull(1)?.removeSuffix("MHz")?.toIntOrNull() ?: 20,
            security = network.text("spairport_security_mode").removePrefix("spairport_security_mode_").let {
                when {
                    "wpa3" in it && "wpa2" in it -> "WPA2/WPA3"
                    "wpa3" in it -> "WPA3"
                    "wpa2" in it -> if ("enterprise" in it) "WPA2-Enterprise" else "WPA2"
                    "wpa" in it -> "WPA"
                    "wep" in it -> "WEP"
                    else -> ""
                }
            },
            standard = network.text("spairport_network_phymode"),
            connected = connected,
        )
    }
    val root = Net.json.parseToJsonElement(json) as JsonObject
    val interfaces = ((root["SPAirPortDataType"] as JsonArray).first() as JsonObject)["spairport_airport_interfaces"] as JsonArray
    interfaces.filterIsInstance<JsonObject>().flatMap { nif ->
        listOfNotNull((nif["spairport_current_network_information"] as? JsonObject)?.let { point(it, true) }) +
            (nif["spairport_airport_other_local_wireless_networks"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { point(it, false) }
    }
}.getOrDefault(emptyList())

actual suspend fun cellReadings(): CellScan = CellScan(emptyList(), CellProblem.UNSUPPORTED)

actual fun wifiScanLimit(): ScanLimit? = null

actual fun openScanLimitSettings() = Unit
