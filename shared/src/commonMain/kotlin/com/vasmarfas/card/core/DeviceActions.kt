package com.vasmarfas.card.core

expect fun playTone(frequencyHz: Double, durationMs: Int, volume: Float = 0.6f)

expect fun vibrate(durationMs: Int)

expect fun timeZoneIds(): List<String>

expect fun timeZoneOffsetSeconds(zoneId: String, epochSeconds: Long): Int?

expect fun systemTimeZoneId(): String

expect fun setSystemBarsHidden(hidden: Boolean)

expect fun setSystemBarsDark(dark: Boolean, followSystem: Boolean)
