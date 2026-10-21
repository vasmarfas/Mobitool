package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.CellReading
import com.vasmarfas.card.core.CellTech
import com.vasmarfas.card.core.parseXml
import com.vasmarfas.card.resources.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.StringResource

class CellChannel(val bands: List<String>, val downlinkMhz: Double)

object CellBands {
    private class LteBand(val band: Int, val downlinkLow: Double, val first: Int, val last: Int, val name: String)

    // 3GPP TS 36.101 table 5.7.3-1, downlink: F = F_DL_low + 0.1 (N_DL - N_offs)
    private val lte = listOf(
        LteBand(1, 2110.0, 0, 599, "2100"),
        LteBand(2, 1930.0, 600, 1199, "1900"),
        LteBand(3, 1805.0, 1200, 1949, "1800"),
        LteBand(4, 2110.0, 1950, 2399, "AWS"),
        LteBand(5, 869.0, 2400, 2649, "850"),
        LteBand(7, 2620.0, 2750, 3449, "2600"),
        LteBand(8, 925.0, 3450, 3799, "900"),
        LteBand(12, 729.0, 5010, 5179, "700"),
        LteBand(13, 746.0, 5180, 5279, "700"),
        LteBand(14, 758.0, 5280, 5379, "700"),
        LteBand(17, 734.0, 5730, 5849, "700"),
        LteBand(18, 860.0, 5850, 5999, "800"),
        LteBand(19, 875.0, 6000, 6149, "800"),
        LteBand(20, 791.0, 6150, 6449, "800"),
        LteBand(21, 1495.9, 6450, 6599, "1500"),
        LteBand(25, 1930.0, 8040, 8689, "1900"),
        LteBand(26, 859.0, 8690, 9039, "850"),
        LteBand(28, 758.0, 9210, 9659, "700"),
        LteBand(29, 717.0, 9660, 9769, "700"),
        LteBand(30, 2350.0, 9770, 9869, "2300"),
        LteBand(31, 462.5, 9870, 9919, "450"),
        LteBand(32, 1452.0, 9920, 10359, "1500"),
        LteBand(34, 2010.0, 36200, 36349, "2000 TDD"),
        LteBand(38, 2570.0, 37750, 38249, "2600 TDD"),
        LteBand(39, 1880.0, 38250, 38649, "1900 TDD"),
        LteBand(40, 2300.0, 38650, 39649, "2300 TDD"),
        LteBand(41, 2496.0, 39650, 41589, "2500 TDD"),
        LteBand(42, 3400.0, 41590, 43589, "3500 TDD"),
        LteBand(43, 3600.0, 43590, 45589, "3700 TDD"),
        LteBand(46, 5150.0, 46790, 54539, "5 GHz"),
        LteBand(48, 3550.0, 55240, 56739, "3500 TDD"),
        LteBand(66, 2110.0, 66436, 67335, "AWS-3"),
        LteBand(71, 617.0, 68586, 68935, "600"),
    )

    private val nr = listOf(
        1 to 2110.0..2170.0, 3 to 1805.0..1880.0, 5 to 869.0..894.0, 7 to 2620.0..2690.0, 8 to 925.0..960.0,
        20 to 791.0..821.0, 28 to 758.0..803.0, 38 to 2570.0..2620.0, 40 to 2300.0..2400.0, 41 to 2496.0..2690.0,
        71 to 617.0..652.0, 77 to 3300.0..4200.0, 78 to 3300.0..3800.0, 79 to 4400.0..5000.0,
        257 to 26500.0..29500.0, 258 to 24250.0..27500.0, 260 to 37000.0..40000.0, 261 to 27500.0..28350.0,
    )

    fun nrFrequency(arfcn: Int): Double = when {
        arfcn < 600_000 -> arfcn * 0.005
        arfcn < 2_016_667 -> 3000 + (arfcn - 600_000) * 0.015
        else -> 24250.08 + (arfcn - 2_016_667) * 0.06
    }

    fun channel(tech: CellTech, arfcn: Int): CellChannel? = when (tech) {
        CellTech.LTE -> lte.firstOrNull { arfcn in it.first..it.last }?.let { band ->
            CellChannel(listOf("B${band.band} (${band.name})"), band.downlinkLow + 0.1 * (arfcn - band.first))
        }
        CellTech.NR -> nrFrequency(arfcn).let { frequency -> CellChannel(nr.filter { frequency in it.second }.map { "n${it.first}" }, frequency) }
        CellTech.UMTS -> when (arfcn) {
            in 10562..10838 -> CellChannel(listOf("B1 (2100)"), arfcn / 5.0)
            in 9662..9938 -> CellChannel(listOf("B2 (1900)"), arfcn / 5.0)
            in 1162..1513 -> CellChannel(listOf("B3 (1800)"), 1575 + arfcn / 5.0)
            in 4357..4458 -> CellChannel(listOf("B5 (850)"), arfcn / 5.0)
            in 2937..3088 -> CellChannel(listOf("B8 (900)"), 340 + arfcn / 5.0)
            else -> null
        }
        CellTech.GSM -> when (arfcn) {
            in 0..124 -> CellChannel(listOf("GSM 900"), 935 + 0.2 * arfcn)
            in 975..1023 -> CellChannel(listOf("E-GSM 900"), 935 + 0.2 * (arfcn - 1024))
            in 128..251 -> CellChannel(listOf("GSM 850"), 869.2 + 0.2 * (arfcn - 128))
            in 512..885 -> CellChannel(listOf("DCS 1800"), 1805.2 + 0.2 * (arfcn - 512))
            else -> null
        }
        else -> null
    }
}

enum class SignalGrade(val title: StringResource) {
    EXCELLENT(Res.string.wifi_quality_excellent),
    GOOD(Res.string.wifi_quality_good),
    FAIR(Res.string.wifi_quality_fair),
    POOR(Res.string.wifi_quality_weak),
}

object CellGrades {
    private fun grade(value: Double, excellent: Double, good: Double, fair: Double) = when {
        value >= excellent -> SignalGrade.EXCELLENT
        value >= good -> SignalGrade.GOOD
        value >= fair -> SignalGrade.FAIR
        else -> SignalGrade.POOR
    }

    fun rsrp(value: Int) = grade(value.toDouble(), -80.0, -90.0, -100.0)
    fun rsrq(value: Int) = grade(value.toDouble(), -10.0, -15.0, -20.0)
    fun sinr(value: Double) = grade(value, 20.0, 13.0, 0.0)
    fun rscp(value: Int) = grade(value.toDouble(), -80.0, -95.0, -105.0)
    fun gsmRssi(value: Int) = grade(value.toDouble(), -70.0, -85.0, -100.0)

    fun main(cell: CellReading): Pair<Int, SignalGrade>? = when {
        cell.rsrp != null -> cell.rsrp to rsrp(cell.rsrp)
        cell.rssi != null && cell.tech == CellTech.UMTS -> cell.rssi to rscp(cell.rssi)
        cell.rssi != null -> cell.rssi to gsmRssi(cell.rssi)
        else -> null
    }
}

enum class ModemKind(val title: StringResource, val address: String) {
    HUAWEI(Res.string.cell_modem_huawei, "192.168.8.1"),
    ZTE(Res.string.cell_modem_zte, "192.168.0.1"),
}

object Modems {
    private fun number(text: String?): Double? = text?.let { Regex("-?\\d+(\\.\\d+)?").find(it)?.value?.toDoubleOrNull() }

    fun huaweiSession(xml: String): Pair<String, String>? = runCatching {
        val root = parseXml(xml)
        val session = root.child("SesInfo")?.text()?.trim().orEmpty()
        val token = root.child("TokInfo")?.text()?.trim().orEmpty()
        if (session.isEmpty() || token.isEmpty()) null else (if ('=' in session) session else "SessionID=$session") to token
    }.getOrNull()

    fun huaweiSignal(xml: String): CellReading? = runCatching {
        val root = parseXml(xml)
        if (root.localName != "response") return null
        fun field(name: String) = root.child(name)?.text()?.trim()?.ifEmpty { null }
        val tech = when (field("mode")) {
            "0" -> CellTech.GSM
            "2" -> CellTech.UMTS
            "11" -> CellTech.NR
            else -> CellTech.LTE
        }
        val plmn = field("plmn")
        CellReading(
            tech = tech,
            registered = true,
            mcc = plmn?.take(3),
            mnc = plmn?.drop(3)?.ifEmpty { null },
            cellId = field("cell_id")?.toLongOrNull(),
            area = field("tac")?.toIntOrNull() ?: field("lac")?.toIntOrNull(),
            pci = field("pci")?.toIntOrNull(),
            arfcn = field("earfcn")?.let { Regex("DL:\\s*(\\d+)").find(it)?.groupValues?.get(1) ?: it }?.toIntOrNull(),
            bands = listOfNotNull(field("band")?.toIntOrNull()),
            bandwidthKhz = number(field("dlbandwidth"))?.let { (it * 1000).toInt() },
            rsrp = number(field("rsrp"))?.toInt(),
            rsrq = number(field("rsrq"))?.toInt(),
            sinr = number(field("sinr")),
            rssi = number(field("rscp") ?: field("rssi"))?.toInt(),
        )
    }.getOrNull()

    const val ZTE_FIELDS = "network_type,network_provider,lte_rsrp,lte_rsrq,lte_snr,lte_rssi,lte_pci,cell_id,lte_band,lte_ca_pcell_arfcn," +
        "Z5g_rsrp,Z5g_rsrq,Z5g_SINR,nr5g_pci,nr5g_action_band,rscp,ecio,rssi"

    // ZTE firmwares print the cell id and PCI in hex
    fun zteSignal(json: JsonObject): CellReading? {
        fun field(name: String) = (json[name] as? JsonPrimitive)?.content?.trim()?.ifEmpty { null }
        val type = field("network_type")?.uppercase() ?: return null
        val nr = type.contains("SA") && !type.contains("NSA") || type == "5G"
        return CellReading(
            tech = when {
                nr -> CellTech.NR
                "LTE" in type || "NSA" in type -> CellTech.LTE
                "WCDMA" in type || "HSPA" in type || "UMTS" in type -> CellTech.UMTS
                else -> CellTech.GSM
            },
            registered = true,
            operator = field("network_provider"),
            cellId = field("cell_id")?.toLongOrNull(16),
            pci = field(if (nr) "nr5g_pci" else "lte_pci")?.toIntOrNull(16),
            arfcn = field("lte_ca_pcell_arfcn")?.toIntOrNull()?.takeUnless { nr },
            bands = listOfNotNull(number(field(if (nr) "nr5g_action_band" else "lte_band"))?.toInt()),
            rsrp = number(field(if (nr) "Z5g_rsrp" else "lte_rsrp"))?.toInt(),
            rsrq = number(field(if (nr) "Z5g_rsrq" else "lte_rsrq"))?.toInt(),
            sinr = number(field(if (nr) "Z5g_SINR" else "lte_snr")),
            rssi = number(field("lte_rssi") ?: field("rscp") ?: field("rssi"))?.toInt(),
        )
    }
}
