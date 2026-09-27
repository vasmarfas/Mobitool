package com.vasmarfas.card.core

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

actual class PcmPlayer actual constructor() {
    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var line: SourceDataLine? = null

    @Volatile
    private var written = 0L

    actual fun start(sampleRate: Int, channels: Int, source: (ShortArray) -> Int) {
        stop()
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val output = runCatching { AudioSystem.getSourceDataLine(format).apply { open(format, sampleRate * channels * 2 / 5) } }.getOrNull() ?: return
        line = output
        written = 0
        worker = thread(isDaemon = true, name = "pcm-player") {
            val samples = ShortArray(sampleRate / 20 * channels)
            val bytes = ByteArray(samples.size * 2)
            output.start()
            while (worker === Thread.currentThread()) {
                val n = source(samples)
                if (n <= 0) break
                for (i in 0 until n) {
                    bytes[i * 2] = samples[i].toByte()
                    bytes[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
                }
                output.write(bytes, 0, n * 2)
                written += n / channels
            }
            if (worker === Thread.currentThread()) {
                output.drain()
                worker = null
            } else {
                output.flush()
            }
            output.stop()
            output.close()
            if (line === output) line = null
        }
    }

    actual fun stop() {
        worker = null
    }

    actual val position: Long get() = line?.longFramePosition ?: written

    actual val playing: Boolean get() = worker != null
}
