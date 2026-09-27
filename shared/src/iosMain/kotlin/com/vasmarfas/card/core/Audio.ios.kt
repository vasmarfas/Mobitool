@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.card.core

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.setActive

// Measurement mode turns off the voice processing and gain control that would flatten a meter
internal object AudioSessions {
    fun playback() = configure(AVAudioSessionCategoryPlayback, AVAudioSessionModeDefault, 0u)

    fun measurement() = configure(AVAudioSessionCategoryPlayAndRecord, AVAudioSessionModeMeasurement, AVAudioSessionCategoryOptionDefaultToSpeaker)

    private fun configure(category: String?, mode: String?, options: ULong) {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(category, mode, options, null)
        session.setActive(true, null)
    }
}

internal class MicChunk(val samples: FloatArray, val sampleRate: Int)

internal fun microphoneChunks(): Flow<MicChunk> = callbackFlow {
    AudioSessions.measurement()
    val engine = AVAudioEngine()
    val input = engine.inputNode
    val format = input.outputFormatForBus(0u)
    val rate = format.sampleRate.toInt()
    if (rate <= 0) {
        close(IllegalStateException("NotAllowedError"))
        return@callbackFlow
    }
    input.installTapOnBus(0u, 4096u, format) { buffer, _ ->
        val pcm = buffer ?: return@installTapOnBus
        val channel = pcm.floatChannelData?.get(0) ?: return@installTapOnBus
        trySend(MicChunk(FloatArray(pcm.frameLength.toInt()) { channel[it] }, rate))
    }
    engine.prepare()
    if (!engine.startAndReturnError(null)) close(IllegalStateException("NotReadableError"))
    awaitClose {
        input.removeTapOnBus(0u)
        engine.stop()
    }
}

internal object ClickPlayer {
    private const val RATE = 48_000
    private val engine = AVAudioEngine()
    private val node = AVAudioPlayerNode()
    private val format = AVAudioFormat(AVAudioPCMFormatFloat32, RATE.toDouble(), 1u, false)

    init {
        engine.attachNode(node)
        engine.connect(node, engine.mainMixerNode, format)
    }

    fun play(frequencyHz: Double, durationMs: Int, volume: Float) {
        val frames = (RATE * durationMs / 1000).coerceAtLeast(1)
        val buffer = AVAudioPCMBuffer(format, frames.toUInt())
        buffer.frameLength = frames.toUInt()
        val out = buffer.floatChannelData?.get(0) ?: return
        val fade = (RATE / 500).coerceAtMost(frames / 2).coerceAtLeast(1)
        for (i in 0 until frames) {
            val envelope = min(1f, min(i, frames - 1 - i).toFloat() / fade)
            out[i] = (sin(2 * PI * frequencyHz * i / RATE) * volume * envelope).toFloat()
        }
        if (!engine.running) {
            AudioSessions.playback()
            if (!engine.startAndReturnError(null)) return
        }
        node.scheduleBuffer(buffer, null)
        if (!node.playing) node.play()
    }
}
