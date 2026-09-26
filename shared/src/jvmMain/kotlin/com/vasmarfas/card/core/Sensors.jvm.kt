package com.vasmarfas.card.core

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private val osName = (System.getProperty("os.name") ?: "").lowercase()

actual fun availableSensors(): Set<SensorType> = emptySet()

actual fun sensorFlow(type: SensorType): Flow<SensorReading> = emptyFlow()

actual suspend fun requestMotionAccess(): Boolean = false

actual fun locationSupported(): Boolean = false

actual fun locationFlow(): Flow<LocationFix> = emptyFlow()

private fun runCommand(vararg command: String): String = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    process.inputStream.bufferedReader().readText().also { process.waitFor() }
}.getOrDefault("")

actual suspend fun batteryInfo(): BatteryInfo? = withContext(Dispatchers.IO) {
    when {
        osName.contains("win") -> {
            val out = runCommand(
                "powershell", "-NoProfile", "-Command",
                "Get-CimInstance Win32_Battery | Select-Object EstimatedChargeRemaining,BatteryStatus,EstimatedRunTime,DesignVoltage,Name | ConvertTo-Json",
            )
            if (out.isBlank() || out.trim() == "null") return@withContext null
            val level = Regex("\"EstimatedChargeRemaining\":\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
            val status = Regex("\"BatteryStatus\":\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
            val runtime = Regex("\"EstimatedRunTime\":\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
            val voltage = Regex("\"DesignVoltage\":\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
            val name = Regex("\"Name\":\\s*\"([^\"]*)\"").find(out)?.groupValues?.get(1)
            if (level == null) return@withContext null
            val charging = status == 2 || status in 6..9
            BatteryInfo(
                level, charging,
                buildList {
                    add("Status" to when (status) { 1 -> "discharging"; 2 -> "on AC"; 3 -> "fully charged"; 4 -> "low"; 5 -> "critical"; 6 -> "charging"; 7 -> "charging (high)"; 8 -> "charging (low)"; 9 -> "charging (critical)"; else -> status.toString() })
                    if (runtime != null && runtime in 1..(60 * 24 * 7)) add("Estimated runtime" to "${runtime / 60} h ${runtime % 60} min")
                    if (voltage != null && voltage > 0) add("Design voltage" to "$voltage mV")
                    if (!name.isNullOrBlank()) add("Name" to name)
                },
            )
        }
        osName.contains("mac") -> {
            val out = runCommand("pmset", "-g", "batt")
            val level = Regex("(\\d+)%").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext null
            val charging = out.contains("charging") && !out.contains("discharging")
            BatteryInfo(level, charging, listOf("Raw" to out.lines().drop(1).joinToString(" ").trim()))
        }
        else -> {
            val dir = File("/sys/class/power_supply").listFiles()?.firstOrNull { it.name.startsWith("BAT") } ?: return@withContext null
            fun read(name: String) = runCatching { File(dir, name).readText().trim() }.getOrNull()
            val level = read("capacity")?.toIntOrNull() ?: return@withContext null
            val status = read("status") ?: ""
            BatteryInfo(
                level, status.equals("Charging", true) || status.equals("Full", true),
                buildList {
                    add("Status" to status)
                    read("voltage_now")?.toLongOrNull()?.let { add("Voltage" to "${it / 1000} mV") }
                    read("current_now")?.toLongOrNull()?.let { add("Current" to "${it / 1000} mA") }
                    read("energy_full")?.toLongOrNull()?.let { full -> read("energy_full_design")?.toLongOrNull()?.let { design -> add("Health" to "${full * 100 / design}% of design") } }
                    read("cycle_count")?.let { add("Cycle count" to it) }
                    read("technology")?.let { add("Technology" to it) }
                    read("manufacturer")?.let { add("Manufacturer" to it) }
                    read("model_name")?.let { add("Model" to it) }
                },
            )
        }
    }
}

actual fun torchSupported(): Boolean = false

actual fun setTorch(on: Boolean): Boolean = false

actual fun displayExtras(): List<Pair<String, String>> = runCatching {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val toolkit = Toolkit.getDefaultToolkit()
    buildList {
        env.screenDevices.forEachIndexed { index, device ->
            val mode = device.displayMode
            add("Display ${index + 1}" to "${mode.width} × ${mode.height} @ ${mode.refreshRate} Hz, ${mode.bitDepth} bit")
            val transform = device.defaultConfiguration.defaultTransform
            add("Scale ${index + 1}" to "${(transform.scaleX * 100).toInt()}%")
        }
        add("Logical DPI" to toolkit.screenResolution.toString())
    }
}.getOrDefault(emptyList())

// the monitor with the focused window, where the ruler is shown
private fun activeDevice(): GraphicsDevice = Window.getWindows().firstOrNull { it.isActive }?.graphicsConfiguration?.device
    ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice

// macOS reports modes and DPI in points, while Compose draws into the backing store
private fun GraphicsDevice.backingScale(): Double = if (osName.startsWith("mac")) defaultConfiguration.defaultTransform.scaleX else 1.0

actual fun screenDpi(): Float? = runCatching {
    (Toolkit.getDefaultToolkit().screenResolution * activeDevice().backingScale()).toFloat()
}.getOrNull()

actual fun screenPixels(): Pair<Int, Int>? = runCatching {
    val device = activeDevice()
    val scale = device.backingScale()
    device.displayMode.let { (it.width * scale).roundToInt() to (it.height * scale).roundToInt() }
}.getOrNull()

actual fun appleScreen(): AppleScreen? = null

private const val EDID_SCRIPT = "Get-CimInstance -Namespace root\\wmi -ClassName WmiMonitorDescriptorMethods | ForEach-Object { " +
    "[BitConverter]::ToString((Invoke-CimMethod -InputObject \$_ -MethodName WmiGetMonitorRawEEdidV1Block -Arguments @{BlockId = 0}).BlockContent) }"

// Windows gives the raw EDID of active monitors through WMI without admin rights, Linux keeps it in sysfs
actual suspend fun displayPanels(): List<DisplayPanel> = withContext(Dispatchers.IO) {
    val os = System.getProperty("os.name").lowercase()
    if (os.startsWith("mac")) return@withContext macPanels()
    val blocks = when {
        os.startsWith("windows") -> runCatching {
            val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", EDID_SCRIPT).redirectErrorStream(true).start()
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            lines.filter { it.startsWith("00-FF-FF") }.map { line -> line.trim().split('-').map { it.toInt(16).toByte() }.toByteArray() }
        }.getOrDefault(emptyList())
        os.startsWith("linux") -> File("/sys/class/drm").listFiles().orEmpty()
            .filter { runCatching { File(it, "status").readText().trim() == "connected" }.getOrDefault(false) }
            .mapNotNull { runCatching { File(it, "edid").readBytes() }.getOrNull() }
        else -> emptyList()
    }
    blocks.mapNotNull(::parseEdid).distinctBy { listOf(it.name, it.widthPx, it.heightPx, it.widthMm, it.heightMm) }
}

@Structure.FieldOrder("width", "height")
open class CGSize : Structure() {
    @JvmField var width = 0.0
    @JvmField var height = 0.0

    class ByValue : CGSize(), Structure.ByValue
}

@Suppress("FunctionName")
internal interface CoreGraphics : Library {
    fun CGGetActiveDisplayList(maxDisplays: Int, displays: IntArray, count: IntByReference): Int
    fun CGDisplayScreenSize(display: Int): CGSize.ByValue
    fun CGDisplayCopyDisplayMode(display: Int): Pointer?
    fun CGDisplayModeGetPixelWidth(mode: Pointer): Long
    fun CGDisplayModeGetPixelHeight(mode: Pointer): Long
    fun CGDisplayModeRelease(mode: Pointer)
    fun CGDisplayIsBuiltin(display: Int): Int
}

private fun macPanels(): List<DisplayPanel> = runCatching {
    val cg = Native.load("CoreGraphics", CoreGraphics::class.java)
    val ids = IntArray(16)
    val count = IntByReference()
    if (cg.CGGetActiveDisplayList(ids.size, ids, count) != 0) return@runCatching emptyList()
    val active = ids.take(count.value)
    val externals = active.count { cg.CGDisplayIsBuiltin(it) == 0 }
    var external = 0
    active.mapNotNull { id ->
        val size = cg.CGDisplayScreenSize(id)
        val mode = cg.CGDisplayCopyDisplayMode(id) ?: return@mapNotNull null
        val width = cg.CGDisplayModeGetPixelWidth(mode).toInt()
        val height = cg.CGDisplayModeGetPixelHeight(mode).toInt()
        cg.CGDisplayModeRelease(mode)
        if (size.width < 1 || size.height < 1 || width <= 0 || height <= 0) return@mapNotNull null
        val name = if (cg.CGDisplayIsBuiltin(id) != 0) {
            Tr("Built-in display", "Встроенный дисплей")[appLang]
        } else {
            external++
            Tr("External display", "Внешний монитор")[appLang] + if (externals > 1) " $external" else ""
        }
        DisplayPanel(name, width, height, size.width.roundToInt(), size.height.roundToInt())
    }
}.getOrDefault(emptyList())

actual fun microphoneSupported(): Boolean = true

actual fun microphoneLevelFlow(): Flow<Double> = flow {
    val format = AudioFormat(44100f, 16, 1, true, false)
    val line = runCatching { AudioSystem.getTargetDataLine(format) as TargetDataLine }.getOrNull() ?: return@flow
    line.open(format)
    line.start()
    val buffer = ByteArray(4096)
    try {
        while (true) {
            val read = line.read(buffer, 0, buffer.size)
            if (read > 0) {
                var sum = 0.0
                var i = 0
                while (i + 1 < read) {
                    val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toDouble()
                    sum += sample * sample
                    i += 2
                }
                val rms = sqrt(sum / (read / 2))
                emit(20 * log10((rms / 32768.0).coerceAtLeast(1e-9)) + 90)
            }
            delay(50.milliseconds)
        }
    } finally {
        line.stop()
        line.close()
    }
}.flowOn(Dispatchers.IO)

actual fun setScreenBrightness(value: Float?) = Unit
