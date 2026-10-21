package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.BleAdvert
import com.vasmarfas.card.resources.*
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource

enum class BleKind(val title: StringResource, val tracker: Boolean = false) {
    AIRTAG(Res.string.ble_airtag, tracker = true),
    FIND_MY(Res.string.ble_find_my, tracker = true),
    SMARTTAG(Res.string.ble_smarttag, tracker = true),
    TILE(Res.string.ble_tile, tracker = true),
    GOOGLE_TRACKER(Res.string.ble_google_tracker, tracker = true),
    AIRPODS(Res.string.ble_airpods),
    APPLE(Res.string.ble_apple),
    IBEACON(Res.string.ble_ibeacon),
    EDDYSTONE(Res.string.ble_eddystone),
    FAST_PAIR(Res.string.ble_fast_pair),
    SWIFT_PAIR(Res.string.ble_swift_pair),
    OTHER(Res.string.ble_other),
}

class IBeacon(val uuid: String, val major: Int, val minor: Int, val measuredPower: Int)

object BleDecoder {
    private const val APPLE = 0x004C
    private const val MICROSOFT = 0x0006

    private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF
    private fun ByteArray.u16(at: Int) = (u8(at) shl 8) or u8(at + 1)

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

    private fun apple(advert: BleAdvert): ByteArray? = advert.manufacturer[APPLE]?.takeIf { it.isNotEmpty() }

    fun iBeacon(advert: BleAdvert): IBeacon? {
        val data = apple(advert) ?: return null
        if (data.size < 23 || data.u8(0) != 0x02 || data.u8(1) != 0x15) return null
        val uuid = hex(data.copyOfRange(2, 18)).replace(" ", "").let { "${it.take(8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.drop(20)}" }
        return IBeacon(uuid, data.u16(18), data.u16(20), data[22].toInt())
    }


    fun findMy(advert: BleAdvert): Pair<BleKind, Boolean>? {
        val data = apple(advert) ?: return null
        if (data.size < 3 || data.u8(0) != 0x12) return null
        val kind = when ((data.u8(2) shr 4) and 0x03) {
            1 -> BleKind.AIRTAG
            0 -> BleKind.APPLE
            3 -> BleKind.AIRPODS
            else -> BleKind.FIND_MY
        }
        return kind to (data.u8(1) >= 0x19)
    }

    private val eddystoneUrlPrefixes = listOf("http://www.", "https://www.", "http://", "https://")
    private val eddystoneUrlCodes = listOf(".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/", ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov")

    fun eddystoneUrl(advert: BleAdvert): String? {
        val data = advert.serviceData["FEAA"] ?: return null
        if (data.size < 3 || data.u8(0) != 0x10) return null
        val prefix = eddystoneUrlPrefixes.getOrNull(data.u8(2)) ?: return null
        return prefix + (3 until data.size).joinToString("") { i ->
            val code = data.u8(i)
            eddystoneUrlCodes.getOrNull(code) ?: code.toChar().toString()
        }
    }

    fun kind(advert: BleAdvert): BleKind {
        findMy(advert)?.let { return it.first }
        val apple = apple(advert)
        val eddystone = advert.serviceData["FEAA"]?.firstOrNull()?.toInt()?.and(0xFF)
        return when {
            eddystone == 0x40 || eddystone == 0x41 -> BleKind.GOOGLE_TRACKER
            "FD5A" in advert.serviceData || "FD5A" in advert.services -> BleKind.SMARTTAG
            advert.services.any { it in setOf("FEED", "FEEC", "FEEB") } || advert.serviceData.keys.any { it in setOf("FEED", "FEEC") } -> BleKind.TILE
            iBeacon(advert) != null -> BleKind.IBEACON
            eddystone != null -> BleKind.EDDYSTONE
            apple != null && apple.u8(0) == 0x07 -> BleKind.AIRPODS
            apple != null -> BleKind.APPLE
            "FE2C" in advert.serviceData -> BleKind.FAST_PAIR
            advert.manufacturer[MICROSOFT]?.firstOrNull()?.toInt() == 0x03 -> BleKind.SWIFT_PAIR
            else -> BleKind.OTHER
        }
    }

    // path loss exponent 2.5 for rooms; power at 1 m from an iBeacon, else TX power minus 41 dB, else -59 dBm
    fun distance(advert: BleAdvert, rssi: Double = advert.rssi.toDouble()): Double {
        val atMeter = iBeacon(advert)?.measuredPower ?: advert.txPower?.let { it - 41 } ?: -59
        return 10.0.pow((atMeter - rssi) / 25.0)
    }
}

class BluetoothNames(private val companies: Map<Int, String>, private val uuids: Map<String, String>) {
    fun company(id: Int): String? = companies[id]

    fun service(uuid: String): String? = uuids[uuid]

    companion object {
        private var loaded: BluetoothNames? = null

        private fun table(text: String): Map<String, String> = text.lines()
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .associate { it.substringBefore('\t') to it.substringAfter('\t') }

        suspend fun load(): BluetoothNames = loaded ?: withContext(Dispatchers.Default) {
            BluetoothNames(
                table(Res.readBytes("files/bluetooth-companies.tsv").decodeToString()).mapKeys { it.key.toInt(16) },
                table(Res.readBytes("files/bluetooth-uuids.tsv").decodeToString()),
            )
        }.also { loaded = it }
    }
}
