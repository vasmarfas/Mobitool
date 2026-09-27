package com.vasmarfas.card.core

// no browser API exposes device temperature
actual fun thermalSupported(): Boolean = false

actual fun readThermal(): ThermalReading? = null
