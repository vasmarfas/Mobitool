package com.vasmarfas.card.core

import platform.CoreTelephony.CTTelephonyNetworkInfo

// iOS gives apps the radio technology of each SIM and nothing about cells or signal
actual suspend fun cellReadings(): CellScan {
    val technologies = CTTelephonyNetworkInfo().serviceCurrentRadioAccessTechnology?.values
        ?.mapNotNull { (it as? String)?.removePrefix("CTRadioAccessTechnology") }.orEmpty()
    return CellScan(emptyList(), CellProblem.UNSUPPORTED, technologies)
}
