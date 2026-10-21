@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.card.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.CoreBluetooth.CBAdvertisementDataIsConnectable
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBAdvertisementDataServiceDataKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAdvertisementDataTxPowerLevelKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).also { array -> array.usePinned { memcpy(it.addressOf(0), bytes, length) } }
}

private class BleScan(val onState: (CBCentralManager) -> Unit, val onAdvert: (BleAdvert) -> Unit) : NSObject(), CBCentralManagerDelegateProtocol {
    private val manager = CBCentralManager(this, dispatch_get_main_queue())

    fun stop() {
        manager.stopScan()
        manager.delegate = null
    }

    override fun centralManagerDidUpdateState(central: CBCentralManager) = onState(central)

    override fun centralManager(central: CBCentralManager, didDiscoverPeripheral: CBPeripheral, advertisementData: Map<Any?, *>, RSSI: NSNumber) {
        val raw = (advertisementData[CBAdvertisementDataManufacturerDataKey] as? NSData)?.toByteArray()
        val manufacturer = if (raw != null && raw.size >= 2) {
            mapOf(((raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)) to raw.copyOfRange(2, raw.size))
        } else {
            emptyMap()
        }
        onAdvert(
            BleAdvert(
                id = didDiscoverPeripheral.identifier.UUIDString,
                name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?: didDiscoverPeripheral.name,
                rssi = RSSI.intValue,
                txPower = (advertisementData[CBAdvertisementDataTxPowerLevelKey] as? NSNumber)?.intValue,
                connectable = (advertisementData[CBAdvertisementDataIsConnectable] as? NSNumber)?.boolValue,
                manufacturer = manufacturer,
                services = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<*>).orEmpty().mapNotNull { (it as? CBUUID)?.UUIDString },
                serviceData = (advertisementData[CBAdvertisementDataServiceDataKey] as? Map<*, *>).orEmpty().entries
                    .mapNotNull { (key, value) -> (key as? CBUUID)?.UUIDString?.let { uuid -> (value as? NSData)?.let { uuid to it.toByteArray() } } }
                    .toMap(),
            ),
        )
    }
}

// the manager keeps its delegate weakly, so the flow holds the scan until it stops
actual fun bleAdverts(): Flow<BleAdvert> = callbackFlow {
    val scan = BleScan(
        onState = { central ->
            when (central.state) {
                CBManagerStatePoweredOn -> central.scanForPeripheralsWithServices(null, mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true))
                CBManagerStateUnauthorized -> close(BleException(BleProblem.NO_PERMISSION))
                CBManagerStatePoweredOff -> close(BleException(BleProblem.OFF))
                CBManagerStateUnsupported -> close(BleException(BleProblem.UNSUPPORTED))
                else -> Unit
            }
        },
        onAdvert = { trySend(it) },
    )
    awaitClose { scan.stop() }
}
