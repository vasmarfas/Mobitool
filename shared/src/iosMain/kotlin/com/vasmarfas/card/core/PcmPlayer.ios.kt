@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.card.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPlayerNode
import kotlin.concurrent.AtomicInt

// three 50 ms buffers in flight, each completion schedules the next, the way AVAudioPlayerNode wants it
actual class PcmPlayer actual constructor() {
    private var engine: AVAudioEngine? = null
    private var node: AVAudioPlayerNode? = null
    private val generation = AtomicInt(0)
    private var active = false

    actual fun start(sampleRate: Int, channels: Int, source: (ShortArray) -> Int) {
        stop()
        AudioSessions.playback()
        val audio = AVAudioEngine()
        val player = AVAudioPlayerNode()
        val format = AVAudioFormat(AVAudioPCMFormatFloat32, sampleRate.toDouble(), channels.toUInt(), false)
        audio.attachNode(player)
        audio.connect(player, audio.mainMixerNode, format)
        if (!audio.startAndReturnError(null)) return
        player.play()
        engine = audio
        node = player
        active = true
        val run = generation.incrementAndGet()
        val chunk = ShortArray(sampleRate / 20 * channels)
        var pending = 0

        fun next() {
            if (generation.value != run) return
            val n = source(chunk)
            if (n <= 0) {
                if (pending == 0) active = false
                return
            }
            val frames = n / channels
            val buffer = AVAudioPCMBuffer(format, frames.toUInt())
            buffer.frameLength = frames.toUInt()
            val data = buffer.floatChannelData ?: return
            for (c in 0 until channels) {
                val out = data[c] ?: continue
                for (i in 0 until frames) out[i] = chunk[i * channels + c] / 32768f
            }
            pending++
            player.scheduleBuffer(buffer) {
                pending--
                next()
            }
        }
        repeat(3) { next() }
    }

    actual fun stop() {
        generation.incrementAndGet()
        node?.stop()
        engine?.stop()
        node = null
        engine = null
        active = false
    }

    actual val position: Long
        get() {
            val player = node ?: return 0
            val time = player.lastRenderTime ?: return 0
            return player.playerTimeForNodeTime(time)?.sampleTime ?: 0
        }

    actual val playing: Boolean get() = active
}
