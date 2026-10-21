package com.vasmarfas.card.core

import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.vasmarfas.card.tools.developer.hexToBytes
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

// PowerShell 5.1 cannot subscribe to WinRT events, so the watcher is built once with the csc of .NET Framework
private val watcherSource = """
using System;
using System.Text;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Storage.Streams;

static class BleWatch {
    static void Main() {
        var watcher = new BluetoothLEAdvertisementWatcher();
        watcher.ScanningMode = BluetoothLEScanningMode.Active;
        var gate = new object();
        watcher.Received += (s, a) => {
            var line = new StringBuilder();
            line.Append(a.BluetoothAddress.ToString("x12")).Append('\t').Append(a.RawSignalStrengthInDBm).Append('\t')
                .Append(a.TransmitPowerLevelInDBm.HasValue ? a.TransmitPowerLevelInDBm.Value.ToString() : "").Append('\t')
                .Append((int)a.AdvertisementType);
            foreach (var section in a.Advertisement.DataSections) {
                var bytes = new byte[section.Data.Length];
                DataReader.FromBuffer(section.Data).ReadBytes(bytes);
                line.Append('\t').Append(section.DataType.ToString("x2")).Append(BitConverter.ToString(bytes).Replace("-", ""));
            }
            lock (gate) Console.Out.WriteLine(line);
        };
        watcher.Stopped += (s, a) => { lock (gate) Console.Out.WriteLine("stop\t" + a.Error); };
        try {
            watcher.Start();
        } catch (Exception) {
            Console.Out.WriteLine("stop\tNotSupported");
            return;
        }
        Console.In.ReadLine();
        watcher.Stop();
    }
}
""".trimIndent()

private fun watcherExe(): File? {
    val windows = File(System.getenv("windir") ?: return null)
    val exe = File(System.getProperty("java.io.tmpdir"), "mobitool-ble-${watcherSource.hashCode().toUInt()}.exe")
    if (exe.isFile) return exe
    val runtime = File(windows, "Microsoft.NET/Framework64/v4.0.30319").takeIf { it.isDirectory } ?: File(windows, "Microsoft.NET/Framework/v4.0.30319")
    val metadata = File(windows, "System32/WinMetadata")
    val references = listOf("Windows.Devices.winmd", "Windows.Foundation.winmd", "Windows.Storage.winmd").map { File(metadata, it) } +
        listOf("System.Runtime.WindowsRuntime.dll", "System.Runtime.dll", "System.Runtime.InteropServices.WindowsRuntime.dll").map { File(runtime, it) }
    val source = File.createTempFile("mobitool-ble", ".cs").apply { writeText(watcherSource) }
    try {
        val process = ProcessBuilder(listOf(File(runtime, "csc.exe").path, "/nologo", "/out:${exe.path}") + references.map { "/r:${it.path}" } + source.path)
            .redirectErrorStream(true).start()
        process.inputStream.readBytes()
        return exe.takeIf { process.waitFor() == 0 && it.isFile }
    } finally {
        source.delete()
    }
}

private fun stopProblem(error: String?) = when (error) {
    "RadioNotAvailable", "DisabledByUser", "DisabledByPolicy", "ConsentRequired" -> BleProblem.OFF
    "NotSupported", "TransportNotSupported" -> BleProblem.UNSUPPORTED
    else -> BleProblem.FAILED
}

private val os = (System.getProperty("os.name") ?: "").lowercase()

actual fun bleAdverts(): Flow<BleAdvert> = when {
    "win" in os -> windowsAdverts()
    "mac" in os -> MacBluetooth.adverts()
    else -> linuxAdverts()
}

private fun windowsAdverts(): Flow<BleAdvert> = callbackFlow {
    val exe = runCatching { watcherExe() }.getOrNull()
    if (exe == null) {
        close(BleException(BleProblem.UNSUPPORTED))
        return@callbackFlow
    }
    val process = ProcessBuilder(exe.path).start()
    thread(isDaemon = true) {
        val lines = BleLines()
        runCatching {
            process.inputStream.bufferedReader().useLines { output ->
                for (line in output) {
                    val fields = line.split('\t')
                    if (fields[0] == "stop") {
                        close(BleException(stopProblem(fields.getOrNull(1))))
                        break
                    }
                    lines.read(fields)?.let { trySend(it) }
                }
            }
        }
        close(BleException(BleProblem.FAILED))
    }
    awaitClose { process.destroy() }
}

// an active scan delivers the scan response as an event of its own, Android hands both over merged
internal class BleLines {
    private val primary = HashMap<String, List<Pair<Int, ByteArray>>>()
    private val response = HashMap<String, List<Pair<Int, ByteArray>>>()
    private val connectable = HashMap<String, Boolean>()

    fun read(fields: List<String>): BleAdvert? {
        if (fields.size < 4 || fields[0].length != 12) return null
        val id = fields[0].uppercase().chunked(2).joinToString(":")
        val rssi = fields[1].toIntOrNull()?.takeIf { it > -127 } ?: return null
        val type = fields[3].toIntOrNull() ?: return null
        val sections = fields.drop(4).mapNotNull { hexToBytes(it)?.takeIf { bytes -> bytes.isNotEmpty() } }
            .map { (it[0].toInt() and 0xFF) to it.copyOfRange(1, it.size) }
        if (type == SCAN_RESPONSE) {
            response[id] = sections
        } else {
            primary[id] = sections
            connectable[id] = type <= 1
        }
        return bleAdvert(id, rssi, fields[2].toIntOrNull(), connectable[id], primary[id].orEmpty() + response[id].orEmpty())
    }

    private companion object {
        const val SCAN_RESPONSE = 4
    }
}

internal fun bleAdvert(id: String, rssi: Int, txPower: Int?, connectable: Boolean?, sections: List<Pair<Int, ByteArray>>): BleAdvert {
    var name: String? = null
    var tx = txPower
    val manufacturer = LinkedHashMap<Int, ByteArray>()
    val services = mutableListOf<String>()
    val serviceData = LinkedHashMap<String, ByteArray>()
    for ((type, data) in sections) {
        when (type) {
            0x08 -> name = name ?: data.decodeToString()
            0x09 -> name = data.decodeToString()
            0x0A -> tx = tx ?: data.firstOrNull()?.toInt()
            0xFF -> if (data.size >= 2) manufacturer[(data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)] = data.copyOfRange(2, data.size)
            0x02, 0x03 -> (0..data.size - 2 step 2).forEach { services += uuid(data, it, 2) }
            0x04, 0x05 -> (0..data.size - 4 step 4).forEach { services += uuid(data, it, 4) }
            0x06, 0x07 -> (0..data.size - 16 step 16).forEach { services += uuid(data, it, 16) }
            0x16 -> if (data.size >= 2) serviceData[uuid(data, 0, 2)] = data.copyOfRange(2, data.size)
            0x20 -> if (data.size >= 4) serviceData[uuid(data, 0, 4)] = data.copyOfRange(4, data.size)
            0x21 -> if (data.size >= 16) serviceData[uuid(data, 0, 16)] = data.copyOfRange(16, data.size)
        }
    }
    return BleAdvert(id, name?.trimEnd('\u0000')?.ifBlank { null }, rssi, tx, connectable, manufacturer, services.distinct(), serviceData)
}

private fun uuid(data: ByteArray, from: Int, size: Int): String {
    val hex = (from + size - 1 downTo from).joinToString("") { (data[it].toInt() and 0xFF).toString(16).padStart(2, '0') }.uppercase()
    val full = when (size) {
        2 -> "0000$hex$BASE_UUID"
        4 -> "$hex$BASE_UUID"
        else -> "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }
    return if (full.startsWith("0000") && full.endsWith(BASE_UUID)) full.substring(4, 8) else full
}

private const val BASE_UUID = "-0000-1000-8000-00805F9B34FB"

// run as a command, bluetoothctl exits at once unless --timeout keeps it scanning
private fun linuxAdverts(): Flow<BleAdvert> = callbackFlow {
    val process = runCatching { ProcessBuilder("bluetoothctl", "--timeout", "86400", "scan", "on").redirectErrorStream(true).start() }.getOrNull()
    if (process == null) {
        close(BleException(BleProblem.UNSUPPORTED))
        return@callbackFlow
    }
    thread(isDaemon = true) {
        val lines = BluezLines()
        runCatching {
            process.inputStream.bufferedReader().useLines { output ->
                for (line in output) {
                    lines.problem(line)?.let {
                        close(BleException(it))
                        return@useLines
                    }
                    lines.read(line)?.let { trySend(it) }
                }
            }
        }
        close(BleException(BleProblem.FAILED))
    }
    awaitClose { process.destroy() }
}

private fun shortUuid(text: String): String {
    val upper = text.trim().uppercase()
    return if (upper.length == 36 && upper.startsWith("0000") && upper.endsWith(BASE_UUID)) upper.substring(4, 8) else upper
}

internal class BluezLines {
    private class Device {
        var name: String? = null
        var rssi: Int? = null
        var tx: Int? = null
        val manufacturer = LinkedHashMap<Int, ByteArray>()
        val services = LinkedHashSet<String>()
        val serviceData = LinkedHashMap<String, ByteArray>()
    }

    private val devices = HashMap<String, Device>()
    private var manufacturerKey: Int? = null
    private var serviceKey: String? = null
    private var dump: ((ByteArray) -> Unit)? = null
    private val bytes = ArrayList<Byte>()

    private fun clean(line: String) = line.replace(escapes, "").replace("\r", "").trimEnd()

    private fun number(text: String): Int? =
        Regex("""\((-?\d+)\)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: text.trim().substringBefore(' ').let { if (it.startsWith("0x")) it.drop(2).toLongOrNull(16)?.toInt() else it.toIntOrNull() }

    private fun flush() {
        dump?.invoke(bytes.toByteArray())
        dump = null
        bytes.clear()
    }

    private fun startDump(rest: String, store: (ByteArray) -> Unit) {
        flush()
        dump = store
        rest.substringAfter("Value:").trim().split(' ').mapNotNull { it.removePrefix("0x").takeIf { t -> t.length == 2 }?.toIntOrNull(16) }
            .forEach { bytes += it.toByte() }
    }

    fun problem(line: String): BleProblem? {
        val text = clean(line)
        return when {
            "No default controller" in text -> BleProblem.UNSUPPORTED
            "Failed to start discovery" in text && "InProgress" !in text -> BleProblem.OFF
            else -> null
        }
    }

    fun read(line: String): BleAdvert? {
        val text = clean(line)
        val row = hexRow.matchEntire(text)
        if (dump != null && row != null) {
            row.groupValues[1].split(' ').forEach { bytes += it.toInt(16).toByte() }
            return null
        }
        flush()
        val (kind, mac, rest) = event.find(text)?.destructured ?: return null
        val id = mac.uppercase()
        if (kind == "DEL") {
            devices.remove(id)
            return null
        }
        val device = devices.getOrPut(id) { Device() }
        when {
            kind == "NEW" -> if (rest.isNotBlank() && rest != id.replace(':', '-')) device.name = rest
            rest.startsWith("Name: ") -> device.name = rest.removePrefix("Name: ")
            rest.startsWith("RSSI: ") -> device.rssi = number(rest.removePrefix("RSSI: "))
            rest.startsWith("TxPower: ") -> device.tx = number(rest.removePrefix("TxPower: "))
            rest.startsWith("UUIDs: ") -> device.services += shortUuid(rest.removePrefix("UUIDs: "))
            rest.startsWith("ManufacturerData Key: ") -> manufacturerKey = number(rest.removePrefix("ManufacturerData Key: "))
            rest.startsWith("ServiceData Key: ") -> serviceKey = shortUuid(rest.removePrefix("ServiceData Key: "))
            rest.startsWith("ManufacturerData Value:") -> manufacturerKey.let { key -> startDump(rest) { data -> key?.let { device.manufacturer[it] = data } } }
            rest.startsWith("ServiceData Value:") -> serviceKey.let { key -> startDump(rest) { data -> key?.let { device.serviceData[it] = data } } }
            else -> return null
        }
        val rssi = device.rssi ?: return null
        return BleAdvert(id, device.name, rssi, device.tx, null, LinkedHashMap(device.manufacturer), device.services.toList(), LinkedHashMap(device.serviceData))
    }

    private companion object {
        val escapes = Regex("\u001B\\[[0-9;]*[A-Za-z]|[\u0001\u0002]")
        val event = Regex("""\[(NEW|CHG|DEL)\] Device ([0-9A-Fa-f:]{17}) ?(.*)$""")
        val hexRow = Regex("""^\s+((?:[0-9a-fA-F]{2} )*[0-9a-fA-F]{2})(?: {2,}.*)?$""")
    }
}

internal interface StateChanged : Callback {
    fun invoke(self: Pointer?, command: Pointer?, central: Pointer?)
}

internal interface Discovered : Callback {
    fun invoke(self: Pointer?, command: Pointer?, central: Pointer?, peripheral: Pointer?, data: Pointer?, rssi: Pointer?)
}

// objc_msgSend goes out with the exact argument types, which arm64 needs
private object MacBluetooth {
    private val objc by lazy { NativeLibrary.getInstance("objc") }
    private val send by lazy { objc.getFunction("objc_msgSend") }

    private fun cls(name: String): Pointer? = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))
    private fun sel(name: String): Pointer = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))
    private fun msg(target: Pointer?, selector: String, vararg args: Any?): Pointer? = send.invokePointer(arrayOf(target, sel(selector), *args))
    private fun long(target: Pointer?, selector: String): Long = send.invokeLong(arrayOf(target, sel(selector)))
    private fun int(target: Pointer?, selector: String): Int = send.invokeInt(arrayOf(target, sel(selector)))
    private fun nsString(text: String) = msg(cls("NSString"), "stringWithUTF8String:", text)
    private fun string(value: Pointer?): String? = value?.let { msg(it, "UTF8String") }?.getString(0, "UTF-8")
    private fun bytes(value: Pointer?): ByteArray? = value?.let { msg(it, "bytes")?.getByteArray(0, long(it, "length").toInt()) }
    private fun Pointer.value(key: String): Pointer? = msg(this, "objectForKey:", nsString(key))
    private fun Pointer.items(): List<Pointer> = (0 until long(this, "count")).mapNotNull { msg(this, "objectAtIndex:", it) }

    @Volatile
    private var onState: ((Pointer?, Long) -> Unit)? = null

    @Volatile
    private var onAdvert: ((BleAdvert) -> Unit)? = null

    // the runtime holds only raw pointers to the methods, these fields keep the callbacks from being collected
    private val stateChanged = object : StateChanged {
        override fun invoke(self: Pointer?, command: Pointer?, central: Pointer?) {
            onState?.invoke(central, long(central, "state"))
        }
    }

    private val discovered = object : Discovered {
        override fun invoke(self: Pointer?, command: Pointer?, central: Pointer?, peripheral: Pointer?, data: Pointer?, rssi: Pointer?) {
            runCatching { advert(peripheral, data, rssi) }.getOrNull()?.let { onAdvert?.invoke(it) }
        }
    }

    private val delegateClass: Pointer by lazy {
        NativeLibrary.getInstance("CoreBluetooth")
        val name = "MobitoolBleDelegate"
        objc.getFunction("objc_allocateClassPair").invokePointer(arrayOf(cls("NSObject"), name, 0L))?.also { created ->
            val add = objc.getFunction("class_addMethod")
            add.invokeInt(arrayOf(created, sel("centralManagerDidUpdateState:"), stateChanged, "v@:@"))
            add.invokeInt(arrayOf(created, sel("centralManager:didDiscoverPeripheral:advertisementData:RSSI:"), discovered, "v@:@@@@"))
            objc.getFunction("objc_registerClassPair").invokeVoid(arrayOf(created))
        } ?: cls(name)!!
    }

    private fun advert(peripheral: Pointer?, data: Pointer?, rssi: Pointer?): BleAdvert? {
        // 127 is CoreBluetooth's "no reading"
        val strength = int(rssi, "intValue").takeIf { it != 127 } ?: return null
        val id = string(msg(msg(peripheral, "identifier"), "UUIDString")) ?: return null
        val manufacturer = LinkedHashMap<Int, ByteArray>()
        bytes(data?.value("kCBAdvDataManufacturerData"))?.takeIf { it.size >= 2 }?.let {
            manufacturer[(it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8)] = it.copyOfRange(2, it.size)
        }
        val serviceData = LinkedHashMap<String, ByteArray>()
        data?.value("kCBAdvDataServiceData")?.let { table ->
            msg(table, "allKeys")?.items().orEmpty().forEach { key ->
                val uuid = string(msg(key, "UUIDString"))
                val value = bytes(msg(table, "objectForKey:", key))
                if (uuid != null && value != null) serviceData[uuid] = value
            }
        }
        return BleAdvert(
            id = id,
            name = string(data?.value("kCBAdvDataLocalName")) ?: string(msg(peripheral, "name")),
            rssi = strength,
            txPower = data?.value("kCBAdvDataTxPowerLevel")?.let { int(it, "intValue") },
            connectable = data?.value("kCBAdvDataIsConnectable")?.let { int(it, "boolValue") and 0xFF != 0 },
            manufacturer = manufacturer,
            services = data?.value("kCBAdvDataServiceUUIDs")?.items().orEmpty().mapNotNull { string(msg(it, "UUIDString")) },
            serviceData = serviceData,
        )
    }

    // states: 2 unsupported, 3 not allowed, 4 off, 5 on; the scan starts once the radio reports itself on
    fun adverts(): Flow<BleAdvert> = callbackFlow {
        onAdvert = { trySend(it) }
        onState = { central, state ->
            when (state) {
                5L -> {
                    val duplicates = msg(cls("NSDictionary"), "dictionaryWithObject:forKey:", msg(cls("NSNumber"), "numberWithBool:", 1.toByte()), nsString("kCBScanOptionAllowDuplicates"))
                    msg(central, "scanForPeripheralsWithServices:options:", null, duplicates)
                }
                4L -> close(BleException(BleProblem.OFF))
                2L -> close(BleException(BleProblem.UNSUPPORTED))
                3L -> close(BleException(BleProblem.FAILED))
            }
        }
        val manager = runCatching {
            val queue = NativeLibrary.getInstance("System").getFunction("dispatch_queue_create").invokePointer(arrayOf("mobitool.ble", null))
            msg(msg(cls("CBCentralManager"), "alloc"), "initWithDelegate:queue:", msg(msg(delegateClass, "alloc"), "init"), queue)
        }.getOrNull()
        if (manager == null) close(BleException(BleProblem.UNSUPPORTED))
        awaitClose {
            manager?.let { runCatching { msg(it, "stopScan") } }
            onAdvert = null
            onState = null
        }
    }
}
