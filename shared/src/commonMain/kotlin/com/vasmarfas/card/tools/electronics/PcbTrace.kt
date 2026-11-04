package com.vasmarfas.card.tools.electronics

import kotlin.math.pow

// IPC-2221: I = k * dT^0.44 * A^0.725, A in square mils
object PcbTrace {
    private const val K_EXTERNAL = 0.048
    private const val K_INTERNAL = 0.024
    private const val B = 0.44
    private const val C = 0.725
    private const val MM_PER_MIL = 0.0254
    private const val MAX_WIDTH_MM = 400 * MM_PER_MIL
    private const val MAX_EXTERNAL_AMPS = 35.0
    private const val MAX_INTERNAL_AMPS = 17.5

    // WireGauge.ohmsPerKm is copper at 20 °C, the board starts at 25 °C before the trace heats up
    private const val COPPER_TEMPCO = 0.00393
    private const val AMBIENT_ABOVE_REFERENCE = 5.0

    fun areaMil2(amps: Double, rise: Double, external: Boolean): Double =
        (amps / ((if (external) K_EXTERNAL else K_INTERNAL) * rise.pow(B))).pow(1 / C)

    fun areaMm2(amps: Double, rise: Double, external: Boolean): Double = areaMil2(amps, rise, external) * MM_PER_MIL * MM_PER_MIL

    fun milsFromMm(mm: Double): Double = mm / MM_PER_MIL

    fun ohms(areaMm2: Double, lengthMm: Double, rise: Double): Double =
        WireGauge.ohmsPerKm(areaMm2) * lengthMm / 1e6 * (1 + COPPER_TEMPCO * (AMBIENT_ABOVE_REFERENCE + rise))

    fun withinCharts(amps: Double, rise: Double, external: Boolean, widthMm: Double): Boolean =
        amps <= (if (external) MAX_EXTERNAL_AMPS else MAX_INTERNAL_AMPS) && rise in 10.0..100.0 && widthMm <= MAX_WIDTH_MM
}
