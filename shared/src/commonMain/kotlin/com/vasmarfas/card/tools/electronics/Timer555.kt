package com.vasmarfas.card.tools.electronics

class AstableTiming(val high: Double, val low: Double) {
    val period: Double get() = high + low
    val frequency: Double get() = 1 / period
    val duty: Double get() = high / period
}

class AstableParts(val ra: Double, val rb: Double)

// formulas and limits from the TI NE555 datasheet, SLFS022K
object Timer555 {
    const val MAX_FREQUENCY = 100e3
    const val MIN_PULSE = 10e-6
    const val MIN_RESISTOR = 1e3
    const val MAX_RESISTOR = 10e6
    private const val CHARGE = 0.693
    private const val PULSE = 1.1

    fun astable(ra: Double, rb: Double, c: Double) = AstableTiming(CHARGE * (ra + rb) * c, CHARGE * rb * c)

    // C charges through both resistors and discharges through RB, so the output cannot stay high for half the period or less
    fun astableParts(frequency: Double, duty: Double, c: Double): AstableParts? {
        if (duty <= 0.5 || duty >= 1) return null
        val period = 1 / frequency
        return AstableParts((2 * duty - 1) * period / (CHARGE * c), (1 - duty) * period / (CHARGE * c))
    }

    fun pulse(r: Double, c: Double): Double = PULSE * r * c

    fun pulseResistor(seconds: Double, c: Double): Double = seconds / (PULSE * c)
}
