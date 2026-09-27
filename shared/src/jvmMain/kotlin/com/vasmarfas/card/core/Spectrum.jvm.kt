package com.vasmarfas.card.core

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

actual fun microphoneSpectrumFlow(fftSize: Int): Flow<SpectrumFrame> = flow {
    val sampleRate = 44_100
    val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
    val line = runCatching { AudioSystem.getTargetDataLine(format) as TargetDataLine }.getOrNull() ?: throw IllegalStateException("NotFoundError")
    runCatching { line.open(format, fftSize * 4) }.onFailure { throw IllegalStateException("NotReadableError") }
    line.start()
    val hop = minOf(fftSize, SpectrumHop)
    val bytes = ByteArray(hop * 2)
    val samples = FloatArray(fftSize)
    var collected = 0
    try {
        while (currentCoroutineContext().isActive) {
            var filled = 0
            while (filled < bytes.size) {
                val read = line.read(bytes, filled, bytes.size - filled)
                if (read <= 0) throw IllegalStateException("NotReadableError")
                filled += read
            }
            samples.copyInto(samples, 0, hop, fftSize)
            for (i in 0 until hop) {
                val value = ((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xFF)).toShort()
                samples[fftSize - hop + i] = value / 32768f
            }
            collected = minOf(fftSize, collected + hop)
            if (collected == fftSize) emit(SpectrumFrame(Fft.magnitudesDb(samples), sampleRate))
        }
    } finally {
        line.stop()
        line.close()
    }
}.flowOn(Dispatchers.IO)
