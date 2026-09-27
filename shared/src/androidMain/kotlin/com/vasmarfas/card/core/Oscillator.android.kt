package com.vasmarfas.card.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.concurrent.thread

private fun buildTrack(sampleRate: Int, bufferBytes: Int): AudioTrack = AudioTrack.Builder()
    .setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build(),
    )
    .setAudioFormat(
        AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build(),
    )
    .setBufferSizeInBytes(bufferBytes)
    .setTransferMode(AudioTrack.MODE_STREAM)
    .build()

private val outputRate: Int by lazy {
    AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48_000
}

private val state = OscillatorState()

@Volatile
private var worker: Thread? = null

actual fun toneSampleRate(): Int = outputRate

actual fun startTone(frequencyHz: Double, waveform: Waveform, volume: Float) {
    state.set(frequencyHz, waveform, volume)
    if (worker != null) return
    worker = thread(isDaemon = true, name = "oscillator") {
        runCatching {
            val rate = outputRate
            val minimum = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(2048)
            val track = buildTrack(rate, minimum * 2)
            val buffer = ShortArray(minimum / 2)
            track.play()
            while (worker === Thread.currentThread()) {
                state.fill(buffer, rate)
                track.write(buffer, 0, buffer.size)
            }
            track.stop()
            track.release()
        }
    }
}

actual fun stopTone() {
    worker = null
}
