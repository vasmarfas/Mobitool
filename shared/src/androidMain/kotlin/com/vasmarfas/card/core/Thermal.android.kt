package com.vasmarfas.card.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

actual fun thermalSupported(): Boolean = true

// battery temperature, /sys/class/thermal is closed to apps and there is no public SoC temperature API
actual fun readThermal(): ThermalReading? {
    val context = AppContextHolder.context
    val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    val celsius = tenths?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }

    val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        when (power?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
            -> ThermalLevel.CRITICAL
            else -> ThermalLevel.UNKNOWN
        }
    } else {
        ThermalLevel.UNKNOWN
    }

    if (celsius == null && level == ThermalLevel.UNKNOWN) return null
    return ThermalReading(celsius, level, ThermalSource.BATTERY)
}
