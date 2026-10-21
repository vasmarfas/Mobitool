package com.vasmarfas.card.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import androidx.core.util.size

private fun UUID.short(): String {
    val text = toString().uppercase()
    return if (text.startsWith("0000") && text.endsWith("-0000-1000-8000-00805F9B34FB")) text.substring(4, 8) else text
}

private fun ScanResult.toAdvert(): BleAdvert {
    val record = scanRecord
    val manufacturer = record?.manufacturerSpecificData?.let { data -> (0 until data.size).associate { data.keyAt(it) to data.valueAt(it) } }.orEmpty()
    return BleAdvert(
        id = device.address,
        name = record?.deviceName?.ifBlank { null },
        rssi = rssi,
        txPower = txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT } ?: record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
        connectable = isConnectable,
        manufacturer = manufacturer,
        services = record?.serviceUuids.orEmpty().map { it.uuid.short() },
        serviceData = record?.serviceData.orEmpty().mapKeys { it.key.uuid.short() },
    )
}

@SuppressLint("MissingPermission")
actual fun bleAdverts(): Flow<BleAdvert> = callbackFlow {
    val adapter = (AppContextHolder.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    val scanner = adapter?.bluetoothLeScanner
    val problem = when {
        adapter == null -> BleProblem.UNSUPPORTED
        !hasPermission(AppPermission.BLUETOOTH) -> BleProblem.NO_PERMISSION
        !adapter.isEnabled || scanner == null -> BleProblem.OFF
        else -> null
    }
    if (problem != null || scanner == null) {
        close(BleException(problem ?: BleProblem.OFF))
        return@callbackFlow
    }
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            trySend(result.toAdvert())
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { trySend(it.toAdvert()) }
        }

        override fun onScanFailed(errorCode: Int) {
            close(BleException(BleProblem.FAILED))
        }
    }
    scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
    awaitClose { runCatching { scanner.stopScan(callback) } }
}
