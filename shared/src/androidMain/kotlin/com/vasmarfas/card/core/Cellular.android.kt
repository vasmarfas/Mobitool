package com.vasmarfas.card.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

actual suspend fun cellReadings(): CellScan {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return CellScan(emptyList(), CellProblem.UNSUPPORTED)
    val context = AppContextHolder.context
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        return CellScan(emptyList(), CellProblem.NO_PERMISSION)
    }
    val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    if (telephony.simState != TelephonyManager.SIM_STATE_READY) return CellScan(emptyList(), CellProblem.NO_SIM)
    val cells = fresh(context, telephony) ?: telephony.allCellInfo.orEmpty()
    val operator = telephony.networkOperatorName.orEmpty().ifEmpty { null }
    return CellScan(cells.mapNotNull { it.toReading(operator) })
}

@RequiresApi(Build.VERSION_CODES.Q)
@RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
private suspend fun fresh(context: Context, telephony: TelephonyManager): List<CellInfo>? = withTimeoutOrNull(5_000) {
    suspendCancellableCoroutine { continuation ->
        telephony.requestCellInfoUpdate(
            ContextCompat.getMainExecutor(context),
            object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    if (continuation.isActive) continuation.resume(cellInfo)
                }

                override fun onError(errorCode: Int, detail: Throwable?) {
                    if (continuation.isActive) continuation.resume(null)
                }
            },
        )
    }
}

private fun Int.known(): Int? = takeIf { it != CellInfo.UNAVAILABLE }

private fun Long.known(): Long? = takeIf { it != CellInfo.UNAVAILABLE_LONG }

@RequiresApi(Build.VERSION_CODES.Q)
private fun CellInfo.toReading(operator: String?): CellReading? = when (this) {
    is CellInfoLte -> {
        val id = cellIdentity
        val signal = cellSignalStrength
        CellReading(
            tech = CellTech.LTE,
            registered = isRegistered,
            mcc = id.mccString,
            mnc = id.mncString,
            operator = id.operatorAlphaLong?.toString() ?: operator.takeIf { isRegistered },
            cellId = id.ci.known()?.toLong(),
            area = id.tac.known(),
            pci = id.pci.known(),
            arfcn = id.earfcn.known(),
            bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id.bands.toList() else emptyList(),
            bandwidthKhz = id.bandwidth.known(),
            rsrp = signal.rsrp.known(),
            rsrq = signal.rsrq.known(),
            // a few modems report the signal to noise ratio in tenths of a dB
            sinr = signal.rssnr.known()?.let { if (abs(it) > 50) it / 10.0 else it.toDouble() },
            rssi = signal.rssi.known(),
            timingAdvance = signal.timingAdvance.known(),
            level = signal.level,
        )
    }
    is CellInfoNr -> {
        val id = cellIdentity as CellIdentityNr
        val signal = cellSignalStrength as CellSignalStrengthNr
        CellReading(
            tech = CellTech.NR,
            registered = isRegistered,
            mcc = id.mccString,
            mnc = id.mncString,
            operator = id.operatorAlphaLong?.toString() ?: operator.takeIf { isRegistered },
            cellId = id.nci.known(),
            area = id.tac.known(),
            pci = id.pci.known(),
            arfcn = id.nrarfcn.known(),
            bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id.bands.toList() else emptyList(),
            rsrp = signal.ssRsrp.known(),
            rsrq = signal.ssRsrq.known(),
            sinr = signal.ssSinr.known()?.toDouble(),
            level = signal.level,
        )
    }
    is CellInfoWcdma -> {
        val id = cellIdentity
        val signal = cellSignalStrength
        CellReading(
            tech = CellTech.UMTS,
            registered = isRegistered,
            mcc = id.mccString,
            mnc = id.mncString,
            operator = id.operatorAlphaLong?.toString() ?: operator.takeIf { isRegistered },
            cellId = id.cid.known()?.toLong(),
            area = id.lac.known(),
            pci = id.psc.known(),
            arfcn = id.uarfcn.known(),
            rsrq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) signal.ecNo.known() else null,
            rssi = signal.dbm.known(),
            level = signal.level,
        )
    }
    is CellInfoGsm -> {
        val id = cellIdentity
        val signal = cellSignalStrength
        CellReading(
            tech = CellTech.GSM,
            registered = isRegistered,
            mcc = id.mccString,
            mnc = id.mncString,
            operator = id.operatorAlphaLong?.toString() ?: operator.takeIf { isRegistered },
            cellId = id.cid.known()?.toLong(),
            area = id.lac.known(),
            pci = id.bsic.known(),
            arfcn = id.arfcn.known(),
            rssi = signal.dbm.known(),
            timingAdvance = signal.timingAdvance.known(),
            level = signal.level,
        )
    }
    else -> null
}
