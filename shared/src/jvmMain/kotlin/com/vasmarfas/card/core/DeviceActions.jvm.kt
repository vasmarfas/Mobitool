package com.vasmarfas.card.core

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

actual fun playTone(frequencyHz: Double, durationMs: Int, volume: Float) {
    thread(isDaemon = true, name = "tone") {
        runCatching {
            val sampleRate = 44100f
            val format = AudioFormat(sampleRate, 16, 1, true, false)
            val line = AudioSystem.getSourceDataLine(format)
            line.open(format)
            line.start()
            val samples = (sampleRate * durationMs / 1000).toInt()
            val buffer = ByteArray(samples * 2)
            val fade = min(samples / 10, 400)
            for (i in 0 until samples) {
                val envelope = when {
                    i < fade -> i.toFloat() / fade
                    i > samples - fade -> (samples - i).toFloat() / fade
                    else -> 1f
                }
                val value = (sin(2.0 * PI * frequencyHz * i / sampleRate) * Short.MAX_VALUE * volume * envelope).toInt()
                buffer[i * 2] = (value and 0xFF).toByte()
                buffer[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            line.write(buffer, 0, buffer.size)
            line.drain()
            line.close()
        }
    }
}

actual fun vibrate(durationMs: Int) = Unit

actual fun setSystemBarsHidden(hidden: Boolean) = Unit

actual fun setSystemBarsDark(dark: Boolean, followSystem: Boolean) = Unit

