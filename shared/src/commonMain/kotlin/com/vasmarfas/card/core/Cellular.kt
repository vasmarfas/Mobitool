package com.vasmarfas.card.core

enum class CellTech { GSM, UMTS, LTE, NR, CDMA, TDSCDMA }

data class CellReading(
    val tech: CellTech,
    val registered: Boolean,
    val mcc: String? = null,
    val mnc: String? = null,
    val operator: String? = null,
    val cellId: Long? = null,
    val area: Int? = null,
    val pci: Int? = null,
    val arfcn: Int? = null,
    val bands: List<Int> = emptyList(),
    val bandwidthKhz: Int? = null,
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val sinr: Double? = null,
    val rssi: Int? = null,
    val timingAdvance: Int? = null,
    val level: Int? = null,
)

enum class CellProblem { NO_PERMISSION, NO_SIM, UNSUPPORTED }

class CellScan(val cells: List<CellReading>, val problem: CellProblem? = null, val technologies: List<String> = emptyList())

expect suspend fun cellReadings(): CellScan
