package com.vasmarfas.card.core

import java.io.File

// only Linux lets a normal process read sensors, hwmon gives millidegrees
private val hwmonRoot = File("/sys/class/hwmon")

private fun hwmonSensors(): List<Pair<File, String>> = hwmonRoot.listFiles().orEmpty()
    .flatMap { device ->
        val chip = File(device, "name").takeIf { it.canRead() }?.readText()?.trim().orEmpty()
        device.listFiles { _, name -> name.matches(Regex("temp\\d+_input")) }.orEmpty()
            .map { input ->
                val labelFile = File(device, input.name.substringBefore("_input") + "_label")
                val label = labelFile.takeIf { it.canRead() }?.readText()?.trim()
                input to listOfNotNull(chip.ifEmpty { null }, label).joinToString(" ")
            }
    }

// package and die sensors track the SoC, anything else can be a chipset or drive probe
private val preferred = listOf("package", "tctl", "tdie", "cpu", "die", "core")

actual fun thermalSupported(): Boolean = hwmonRoot.isDirectory && hwmonSensors().isNotEmpty()

actual fun readThermal(): ThermalReading? {
    val sensors = hwmonSensors().ifEmpty { return null }
    val best = sensors.firstOrNull { (_, label) ->
        preferred.any { label.lowercase().contains(it) }
    } ?: sensors.first()
    val milli = runCatching { best.first.readText().trim().toDouble() }.getOrNull() ?: return null
    return ThermalReading(
        celsius = milli / 1000.0,
        level = ThermalLevel.UNKNOWN,
        source = ThermalSource.SENSOR,
        label = best.second.ifEmpty { null },
    )
}
